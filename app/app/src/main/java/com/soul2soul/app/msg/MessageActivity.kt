package com.soul2soul.app.msg

import android.Manifest
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soul2soul.app.R
import com.soul2soul.app.session.Presence
import com.soul2soul.app.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/**
 * 留言页：查看 TA 的留言（打开即阅、阅后即删）+ 发文字/语音（≤30s）。
 * 消息收发走信令 WebSocket（Presence.client），语音文件走 HTTP（VoiceClient）。
 * 文案面向非技术用户：不出现协议词，失败必须说人话。
 */
class MessageActivity : AppCompatActivity() {

    private val shown = mutableListOf<Msg>()
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etInput: EditText
    private lateinit var btnMic: android.widget.ImageView
    private lateinit var btnEmoji: android.widget.ImageView
    private lateinit var emojiPanel: View
    private var emojiOpen = false
    private lateinit var btnSend: Button
    private lateinit var btnHoldTalk: Button
    private lateinit var recOverlay: View
    private lateinit var tvRecTimer: TextView
    private lateinit var tvRecHint: TextView
    private lateinit var tvRecCancel: TextView
    private lateinit var recBars: List<View>
    private val adapter by lazy { MsgAdapter() }
    private val uiTick = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recStartAt = 0L
    private var recFile: File? = null
    private var voiceBusy = false
    private var voiceMode = false
    private var holdStarted = false
    private var cancelState = false
    private var touchDownY = 0f
    private var playingId: String? = null
    private var loadingId: String? = null
    private var player: MediaPlayer? = null
    private var playingAnim: android.graphics.drawable.AnimationDrawable? = null
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val storeListener: () -> Unit = { runOnUiThread { syncFromStore() } }

    private val micPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && voiceMode && !holdStarted && recorder == null) {
                // 手指还按着不放：授权回来直接续上这次"按住说话"
                startRecording()
            } else if (!granted) {
                toast(getString(R.string.msg_need_mic))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        rv = findViewById(R.id.rvMessages)
        tvEmpty = findViewById(R.id.tvEmpty)
        etInput = findViewById(R.id.etInput)
        btnMic = findViewById(R.id.btnMic)
        btnEmoji = findViewById(R.id.btnEmoji)
        emojiPanel = findViewById(R.id.emojiPanel)
        btnSend = findViewById(R.id.btnSend)
        btnHoldTalk = findViewById(R.id.btnHoldTalk)
        recOverlay = findViewById(R.id.recOverlay)
        tvRecTimer = findViewById(R.id.tvRecTimer)
        tvRecHint = findViewById(R.id.tvRecHint)
        tvRecCancel = findViewById(R.id.tvRecCancel)
        recBars = listOf(
            findViewById(R.id.bar1), findViewById(R.id.bar2), findViewById(R.id.bar3),
            findViewById(R.id.bar4), findViewById(R.id.bar5),
        )
        etInput.filters = arrayOf(InputFilter.LengthFilter(500))
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSend.setOnClickListener { sendText() }
        btnMic.setOnClickListener { setVoiceMode(!voiceMode) }
        btnEmoji.setOnClickListener { toggleEmojiPanel() }
        val emojiIds = listOf(
            R.id.emoji0, R.id.emoji1, R.id.emoji2, R.id.emoji3, R.id.emoji4,
            R.id.emoji5, R.id.emoji6, R.id.emoji7, R.id.emoji8, R.id.emoji9,
        )
        for (id in emojiIds) {
            val tv = findViewById<TextView>(id)
            tv.setOnClickListener { insertEmoji(tv.text.toString()) }
        }
        btnHoldTalk.setOnTouchListener { _, ev -> onHoldTouch(ev) }
        syncFromStore()
        InboxStore.addListener(storeListener)
    }

    // ---------- 列表与已读 ----------

    private fun syncFromStore() {
        val known = shown.mapTo(HashSet()) { it.id }
        var added = false
        for (m in InboxStore.conversation()) {
            if (m.id !in known) { shown.add(m); added = true }
        }
        if (added) shown.sortWith(compareBy({ it.ts }, { it.id }))
        render()
        ackAll()
    }

    /**
     * 看见即读：从服务端删掉未读条目（阅后即删），本地列表继续展示。
     * 语音例外：本地还没下载到缓存就不 ack——服务端 ack 会连带删语音文件，
     * 抢先 ack 导致点了才下载、必然 403（v0.2.28 真机实锤"下载失败"）。
     */
    private fun ackAll() {
        val ids = shown.filter { !it.mine && (it.kind != "voice" || voiceCached(it.voiceId)) }
            .map { it.id }
        if (ids.isEmpty()) return
        ackIds(ids)
    }

    private fun ackIds(ids: List<String>) {
        if (ids.isEmpty()) return
        InboxStore.remove(ids)
        Presence.client.send("msg.read") { put("ids", JSONArray(ids)) }
    }

    private fun voiceCached(voiceId: String): Boolean {
        val f = File(cacheDir, "voice_$voiceId.m4a")
        return f.exists() && f.length() > 0L
    }

    private fun render() {
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        if (shown.isNotEmpty()) rv.scrollToPosition(shown.size - 1)
    }

    // ---------- 发送 ----------

    private fun sendText() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!Presence.client.isConnected) {
            toast(getString(R.string.msg_not_connected)); return
        }
        etInput.setText("")
        InboxStore.add(Msg(
            id = UUID.randomUUID().toString(),
            from = Prefs.deviceId(this),
            kind = "text",
            text = text,
            voiceId = "",
            durMs = 0,
            ts = System.currentTimeMillis(),
            mine = true,
        ))
        Presence.client.send("msg.post") {
            put("kind", "text")
            put("text", text)
        }
    }

    // ---------- 按住说话（微信式） ----------

    private val holdStart = Runnable { startRecording() }
    private val shortPressHint = Runnable {
        toast(getString(R.string.msg_hold_short_press))
    }

    /** 短按不算录音：按下 400ms 后才真的开始录，避免误触冒出一堆 0″ 语音 */
    private fun onHoldTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (voiceBusy) return true
                if (recorder != null) return true // 卡死兜底：按住时上一段还在录，不重开计时
                if (!Presence.client.isConnected) {
                    toast(getString(R.string.msg_not_connected)); return true
                }
                touchDownY = ev.rawY
                cancelState = false
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    return true
                }
                holdStarted = true
                uiTick.removeCallbacks(holdStart)
                uiTick.postDelayed(holdStart, HOLD_START_DELAY_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!holdStarted && recorder == null) return true
                val up = touchDownY - ev.rawY > resources.displayMetrics.density * CANCEL_SLIDE_DP
                if (up != cancelState) applyCancelState(up)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (recorder != null) {
                    if (cancelState) endRecording("canceled")
                    else endRecording("send")
                } else if (holdStarted) {
                    endRecording("short_press")
                }
                holdStarted = false
                cancelState = false
                uiTick.removeCallbacks(holdStart)
            }
        }
        return true
    }

    private fun applyCancelState(up: Boolean) {
        cancelState = up
        val col = if (up) android.graphics.Color.parseColor("#FF6B6B") else android.graphics.Color.WHITE
        btnHoldTalk.text = getString(if (up) R.string.msg_hold_canceling else R.string.msg_hold_talking)
        tvRecHint.setText(if (up) R.string.msg_hold_canceled else R.string.msg_hold_release)
        tvRecHint.setTextColor(col)
        tvRecTimer.setTextColor(col)
        for (bar in recBars) bar.setBackgroundColor(col)
    }

    /** 文字 ⇄ 语音输入区切换（微信：左下开关，输入区整体换成"按住 说话"） */
    private fun setVoiceMode(on: Boolean) {
        voiceMode = on
        if (!on && recorder != null) endRecording("discard")
        etInput.visibility = if (on) View.GONE else View.VISIBLE
        btnHoldTalk.visibility = if (on) View.VISIBLE else View.GONE
        btnSend.visibility = if (on) View.GONE else View.VISIBLE
        btnMic.setImageResource(
            if (on) R.drawable.ic_keyboard_toggle else R.drawable.ic_mic_toggle
        )
        if (!on) {
            val imm = getSystemService(android.content.Context.INPUT_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        } else {
            setEmojiPanel(false) // 语音输入和表情面板互斥（面板插的是文字）
        }
    }

    private fun toggleEmojiPanel() = setEmojiPanel(!emojiOpen)

    /** 😊 面板开合：语音模式先来一个回键盘；面板替代软键盘的位置，不抢焦点 */
    private fun setEmojiPanel(open: Boolean) {
        if (open && voiceMode) setVoiceMode(false)
        emojiOpen = open
        emojiPanel.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) return
        val imm = getSystemService(android.content.Context.INPUT_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
    }

    /** 点表情插到光标处（可连续点多个），超长部分和文字一样被 500 字过滤挡下 */
    private fun insertEmoji(emoji: String) {
        etInput.requestFocus()
        val t = etInput.text
        val s = etInput.selectionStart.coerceIn(0, t.length)
        val e = etInput.selectionEnd.coerceIn(0, t.length)
        t.replace(minOf(s, e), maxOf(s, e), emoji)
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun startRecording() {
        if (recorder != null) return
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val rec = newRecorder()
        recorder = rec
        try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(22050)
                setAudioEncodingBitRate(32000)
                setMaxDuration(REC_MAX_MS.toInt()) // 客户端硬顶 30s，与服务端 1MB 上限双保险
                setOutputFile(file.path)
                prepare()
                start()
            }
        } catch (e: Exception) {
            recorder = null
            runCatching { rec.release() }
            file.delete()
            toast(getString(R.string.msg_rec_failed))
            return
        }
        recFile = file
        recStartAt = System.currentTimeMillis()
        holdStarted = true
        showRecordingVisuals()
        uiTick.post(tickStop)
    }

    private fun showRecordingVisuals() {
        recOverlay.visibility = View.VISIBLE
        tvRecCancel.visibility = View.VISIBLE
        tvRecHint.visibility = View.VISIBLE
        tvRecHint.setText(R.string.msg_hold_release)
        tvRecHint.setTextColor(android.graphics.Color.WHITE)
        tvRecTimer.setTextColor(android.graphics.Color.WHITE)
        for (bar in recBars) bar.setBackgroundColor(android.graphics.Color.WHITE)
        btnHoldTalk.setText(R.string.msg_hold_talking)
        updateRecDisplay()
    }

    private fun updateRecDisplay() {
        val secs = ((System.currentTimeMillis() - recStartAt) / 1000).toInt().coerceAtMost(30)
        tvRecTimer.text = "$secs″"
        // 微信式跳动音柱：每拍随机高度
        val density = resources.displayMetrics.density
        for (bar in recBars) {
            bar.layoutParams = bar.layoutParams.apply {
                height = ((10 + Math.random() * 30) * density).toInt()
            }
            bar.requestLayout()
        }
        if (secs >= 25) tvRecHint.setText(R.string.msg_rec_warning)
    }

    private val tickStop = object : Runnable {
        override fun run() {
            if (recorder == null) return
            updateRecDisplay()
            if (System.currentTimeMillis() - recStartAt >= REC_MAX_MS) {
                endRecording("send")
                return
            }
            uiTick.postDelayed(this, 200)
        }
    }

    /** 收口所有录音结束路径：mode = send（上传发送）/ canceled（提示已取消）/ short_press / discard */
    private fun endRecording(mode: String) {
        uiTick.removeCallbacks(tickStop)
        uiTick.removeCallbacks(shortPressHint)
        holdStarted = false
        cancelState = false
        val rec = recorder ?: return
        recorder = null
        val durMs = System.currentTimeMillis() - recStartAt
        val file = recFile
        recFile = null
        // stop() 必须调（m4a 落盘要收尾），失败/取消场景也一样，文件随后删掉
        runCatching { rec.stop() }
        runCatching { rec.release() }
        recOverlay.visibility = View.GONE
        btnHoldTalk.setText(R.string.msg_hold_talk)
        if (mode == "canceled") {
            file?.delete()
            toast(getString(R.string.msg_hold_canceled))
            return
        }
        if (mode == "discard") {
            file?.delete()
            return
        }
        if (mode == "short_press") {
            file?.delete()
            if (durMs < 500) {
                uiTick.postDelayed(shortPressHint, 300)
            } else {
                toast(getString(R.string.msg_rec_too_short))
            }
            return
        }
        // send：先清掉半途 stop 可能留下的坏文件，重新录一条的路径由 startRecording 负责
        if (durMs < 500 || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            toast(getString(R.string.msg_rec_too_short))
            return
        }
        uploadAndSend(file, durMs)
    }

    private fun uploadAndSend(file: File, durMs: Long) {
        voiceBusy = true
        btnHoldTalk.text = getString(R.string.msg_sending)
        VoiceClient.upload(file) { voiceId ->
            voiceBusy = false
            if (recorder == null) btnHoldTalk.setText(R.string.msg_hold_talk)
            if (voiceId == null) {
                file.delete()
                toast(getString(R.string.msg_send_failed))
                return@upload
            }
            // 服务端只给收件人下发语音的权限，发送方想回听只能靠这份本地缓存：
            // 改名成 VoiceClient 的缓存路径，"我的"语音气泡直接点着播
            val target = File(cacheDir, "voice_$voiceId.m4a")
            if (!file.renameTo(target)) {
                runCatching { file.copyTo(target, overwrite = true) }
                file.delete()
            }
            if (!Presence.client.send("msg.post") {
                    put("kind", "voice")
                    put("voiceId", voiceId)
                    put("durMs", durMs)
                }
            ) {
                toast(getString(R.string.msg_send_failed))
                return@upload
            }
            InboxStore.add(Msg(
                id = UUID.randomUUID().toString(),
                from = Prefs.deviceId(this),
                kind = "voice",
                text = "",
                voiceId = voiceId,
                durMs = durMs.toInt(),
                ts = System.currentTimeMillis(),
                mine = true,
            ))
        }
    }

    // ---------- 播放 ----------

    private fun play(msg: Msg) {
        if (msg.kind != "voice" || msg.voiceId.isEmpty()) return
        if (playingId == msg.id) { stopPlay(); return }
        stopPlay()
        if (msg.mine) {
            // 自己发的：服务端不下发给自己，只认发送时留下的本地缓存
            val f = File(cacheDir, "voice_${msg.voiceId}.m4a")
            if (!f.exists() || f.length() == 0L) {
                toast(getString(R.string.msg_voice_failed))
                return
            }
            startPlaying(msg.id, f)
            return
        }
        loadingId = msg.id
        adapter.notifyItemRangeChanged(0, shown.size)
        VoiceClient.download(msg.voiceId) { file ->
            if (loadingId != msg.id) return@download // 页面状态已变（取消/重建）
            loadingId = null
            if (file == null) {
                adapter.notifyItemRangeChanged(0, shown.size)
                toast(getString(R.string.msg_voice_failed))
                return@download
            }
            // 落到本地缓存了才"阅"：此时删服务端副本是安全的
            ackIds(listOf(msg.id))
            startPlaying(msg.id, file)
        }
    }

    private fun startPlaying(id: String, file: File) {
        player = try {
            MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { stopPlay() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            stopPlay()
            toast(getString(R.string.msg_voice_failed))
            null
        }
        if (player != null) {
            playingId = id
            adapter.notifyItemRangeChanged(0, shown.size)
        }
    }

    private fun stopPlay() {
        player?.let { runCatching { it.release() } }
        player = null
        playingAnim?.stop()
        playingAnim = null
        if (playingId != null) {
            playingId = null
            adapter.notifyItemRangeChanged(0, shown.size)
        }
    }

    // ---------- 列表适配 ----------

    private inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {
        inner class VH(val row: LinearLayout, val bubble: TextView) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_msg, parent, false) as LinearLayout
            return VH(row, row.findViewById(R.id.tvBubble))
        }

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = shown[position]
            holder.row.gravity = if (m.mine) Gravity.END else Gravity.START
            holder.bubble.setBackgroundResource(if (m.mine) R.drawable.bg_msg_me else R.drawable.bg_msg_peer)
            holder.bubble.setTextColor(
                resources.getColor(if (m.mine) R.color.white else R.color.text_primary, theme)
            )
            val label = timeFmt.format(Date(m.ts))
            val voice = m.kind == "voice"
            holder.bubble.setTextIsSelectable(!voice)
            val secs = ceil(m.durMs / 1000.0).toInt().coerceAtLeast(1)
            holder.bubble.text = if (voice) getString(R.string.msg_voice_play, secs)
            else "$label\n${m.text}"
            // 微信式语音气泡：喇叭图标（播放中声波逐帧跳动）+ 随时长增长的宽度
            val d = if (!voice) null else run {
                val res = when {
                    m.id == playingId && m.mine -> R.drawable.anim_voice_out
                    m.id == playingId -> R.drawable.anim_voice_in
                    m.mine -> R.drawable.ic_voice_out
                    else -> R.drawable.ic_voice_in
                }
                val dr = resources.getDrawable(res, theme).mutate()
                if (m.id == playingId && dr is android.graphics.drawable.AnimationDrawable) {
                    playingAnim?.stop()
                    playingAnim = dr
                    dr.start()
                }
                dr
            }
            holder.bubble.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
            holder.bubble.compoundDrawablePadding =
                if (voice) (6 * holder.bubble.resources.displayMetrics.density).toInt() else 0
            (holder.bubble.layoutParams as ViewGroup.LayoutParams).width =
                if (voice) ((90.0 + 5.0 * ceil(m.durMs / 1000.0)).coerceAtMost(240.0) *
                    holder.bubble.resources.displayMetrics.density).toInt()
                else ViewGroup.LayoutParams.WRAP_CONTENT
            if (voice) {
                holder.bubble.isClickable = true
                holder.bubble.setOnClickListener { play(m) }
            } else {
                holder.bubble.isClickable = false
                holder.bubble.setOnClickListener(null)
            }
        }
    }

    // ---------- 生命周期 ----------

    override fun onResume() {
        super.onResume()
        // 屏上正看着留言：通知栏条数收起，别和自己抢提醒
        UnreadNotifier.suppressed = true
        UnreadNotifier.sync(this)
    }

    override fun onPause() {
        UnreadNotifier.suppressed = false
        UnreadNotifier.sync(this) // 离开时还有没播的语音 → 通知栏重新挂上条数
        super.onPause()
    }

    override fun onDestroy() {
        InboxStore.removeListener(storeListener)
        uiTick.removeCallbacksAndMessages(null)
        loadingId = null // 在途下载回来后发现自己已过期，静默丢弃
        stopPlay()
        if (recorder != null) {
            recorder?.let { runCatching { it.stop(); it.release() } }
            recorder = null
            recFile?.delete()
        }
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REC_MAX_MS = 30_000L
        private const val HOLD_START_DELAY_MS = 400L
        private const val CANCEL_SLIDE_DP = 110f
    }
}
