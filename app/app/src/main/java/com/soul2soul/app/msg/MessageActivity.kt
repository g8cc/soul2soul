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
import android.widget.GridView
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
    private var micFingerDown = false // 授权弹窗回来时手指是否还按着（防幻录）
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
            if (granted) {
                com.soul2soul.app.util.Prefs.setMicWasGranted(this, true)
                // 只有"按住说话"发起且手指还按着才续录；弹窗回来早已松手，再开录就是幻录
                if (MicGatePolicy.shouldAutoStartRecording(
                        granted = true, fromHold = micAskFromHold, voiceMode = voiceMode,
                        holdStarted = holdStarted, recording = recorder != null,
                        fingerDown = micFingerDown,
                    )
                ) startRecording()
                else if (micAskFromHold && !micFingerDown) {
                    toast(getString(R.string.mic_granted_rehold))
                }
            } else when (MicGatePolicy.onDenied(
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            )) {
                // 系统再也不肯弹框（永久拒绝/MIUI 静默拦截）：光 toast 是死路，给"去设置"直达
                MicGatePolicy.MicGateAction.GUIDE -> showMicBlockedDialog()
                else -> toast(getString(R.string.msg_need_mic))
            }
            micAskFromHold = false
        }
    private var micAskFromHold = false

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
        // 微信/QQ 式多行表情网格：加表情只改 strings.xml 的 msg_emojis
        (findViewById<GridView>(R.id.emojiPanel)).adapter = emojiAdapter
        btnHoldTalk.setOnTouchListener { _, ev -> onHoldTouch(ev) }
        syncFromStore()
        InboxStore.addListener(storeListener)
    }

    // ---------- 列表与已读 ----------

    private fun syncFromStore() {
        // 整表取自 InboxStore（条目不可变，撤回/已读都是换新对象）：
        // 增量追加会让撤回墓碑等状态变更到不了屏幕（v0.2.41 撤回依赖这一点）
        shown.clear()
        shown.addAll(InboxStore.conversation())
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
        InboxStore.markRead(ids) // 本地留档继续展示（微信式），服务端才做阅后即删
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
        // id 由客户端生成并随 msg.post 上送：撤回要能对上服务端那条的号
        val id = UUID.randomUUID().toString()
        InboxStore.add(Msg(
            id = id,
            from = Prefs.deviceId(this),
            kind = "text",
            text = text,
            voiceId = "",
            durMs = 0,
            ts = System.currentTimeMillis(),
            mine = true,
        ))
        Presence.client.send("msg.post") {
            put("id", id)
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
                micFingerDown = true
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    ensureMicPermission(fromHold = true)
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
                micFingerDown = false
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
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        } else {
            setEmojiPanel(false) // 语音输入和表情面板互斥（面板插的是文字）
            // 微信式时机：切进语音就把权限缺失处理前置。绑在"按住说话"上会让
            // 弹窗晚拍到下一次点击（MIUI 尤慢），用户会以为"点键盘才弹权限"（v0.2.33 实锤）
            ensureMicPermission(fromHold = false)
        }
    }

    /**
     * 麦克风权限统一入口（弹窗礼仪对齐主流 App）：
     * 已授权→记一笔"曾授权"；首次缺失→直接系统弹窗；
     * 曾授权又消失（多半是覆盖安装后被国产 ROM 重置）→先人话解释再问，
     * 别让用户以为权限"灵异失效"（v0.2.34 真机踩坑）。
     */
    private fun ensureMicPermission(fromHold: Boolean) {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            com.soul2soul.app.util.Prefs.setMicWasGranted(this, true)
            return
        }
        micAskFromHold = fromHold
        if (MicGatePolicy.onMicMissing(
                granted = false,
                everGranted = com.soul2soul.app.util.Prefs.micWasGranted(this),
            ) == MicGatePolicy.MicGateAction.EXPLAIN
        ) showMicRevokedDialog()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun showMicRevokedDialog() {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.mic_revoked_title)
            .setMessage(R.string.mic_revoked_msg)
            .setPositiveButton(R.string.mic_revoked_go) { _, _ ->
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNeutralButton(R.string.mic_settings_go) { _, _ -> openAppPermissionSettings() }
            .setNegativeButton(R.string.msg_got_it, null)
            .show()
    }

    private fun showMicBlockedDialog() {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.mic_blocked_title)
            .setMessage(R.string.mic_blocked_msg)
            .setPositiveButton(R.string.mic_settings_go) { _, _ -> openAppPermissionSettings() }
            .setNegativeButton(R.string.msg_got_it, null)
            .show()
    }

    private fun openAppPermissionSettings() {
        val opened = runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"),
                )
            )
        }.isSuccess
        if (!opened) toast(getString(R.string.msg_need_mic))
    }

    private fun toggleEmojiPanel() = setEmojiPanel(!emojiOpen)

    /** 😊 面板开合：语音模式先来一个回键盘；面板替代软键盘的位置，不抢焦点 */
    private fun setEmojiPanel(open: Boolean) {
        if (open && voiceMode) setVoiceMode(false)
        emojiOpen = open
        emojiPanel.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) return
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
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
            // 同文字留言：客户端 id 贯穿本地存档与 msg.post，撤回靠它对号
            val id = UUID.randomUUID().toString()
            if (!Presence.client.send("msg.post") {
                    put("id", id)
                    put("kind", "voice")
                    put("voiceId", voiceId)
                    put("durMs", durMs)
                }
            ) {
                toast(getString(R.string.msg_send_failed))
                return@upload
            }
            InboxStore.add(Msg(
                id = id,
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

    // ---------- 表情网格 ----------

    private val emojiItems by lazy { resources.getStringArray(R.array.msg_emojis) }
    private val emojiAdapter = object : android.widget.BaseAdapter() {
        override fun getCount(): Int = emojiItems.size
        override fun getItem(position: Int): Any = emojiItems[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView)
                ?: TextView(this@MessageActivity, null, 0, R.style.MsgEmoji)
                    .apply { gravity = Gravity.CENTER }
            tv.text = emojiItems[position]
            tv.setOnClickListener { insertEmoji(emojiItems[position]) }
            return tv
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
            if (m.recalled) {
                // 微信式提示行：内容没了，居中灰字留痕
                holder.row.gravity = Gravity.CENTER
                holder.bubble.setBackgroundResource(0)
                holder.bubble.setTextColor(
                    resources.getColor(R.color.text_secondary, theme)
                )
                holder.bubble.setTextIsSelectable(false)
                holder.bubble.text =
                    getString(if (m.mine) R.string.msg_recalled_me else R.string.msg_recalled_peer)
                holder.bubble.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                holder.bubble.compoundDrawablePadding = 0
                (holder.bubble.layoutParams as ViewGroup.LayoutParams).width =
                    ViewGroup.LayoutParams.WRAP_CONTENT
                holder.bubble.isClickable = false
                holder.bubble.setOnClickListener(null)
                holder.bubble.setOnLongClickListener(null)
                return
            }
            holder.bubble.setBackgroundResource(if (m.mine) R.drawable.bg_msg_me else R.drawable.bg_msg_peer)
            holder.bubble.setTextColor(
                resources.getColor(if (m.mine) R.color.white else R.color.text_primary, theme)
            )
            val label = timeFmt.format(Date(m.ts))
            val voice = m.kind == "voice"
            // 自己的文字不长按选复制：selectable 的 TextView 长按会被选择菜单吃掉，撤回就没入口了
            holder.bubble.setTextIsSelectable(!voice && !m.mine)
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
            // 长按自己的消息 → 撤回（5 分钟内）；服务端回执 msg.recalled 才打墓碑，本地不抢跑
            if (m.mine) {
                holder.bubble.setOnLongClickListener { offerRecall(m); true }
            } else {
                holder.bubble.setOnLongClickListener(null)
            }
        }
    }

    private fun offerRecall(m: Msg) {
        val now = System.currentTimeMillis()
        if (MsgRecallPolicy.tooLate(m, now)) {
            toast(getString(R.string.msg_recall_expired))
            return
        }
        if (!MsgRecallPolicy.canRecall(m, now)) return
        if (!Presence.client.isConnected) {
            toast(getString(R.string.msg_recall_not_connected))
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.msg_recall_action)
            .setMessage(R.string.msg_recall_confirm)
            .setPositiveButton(R.string.msg_recall_action) { _, _ ->
                if (!Presence.client.send("msg.recall") { put("id", m.id) })
                    toast(getString(R.string.msg_recall_not_connected))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
