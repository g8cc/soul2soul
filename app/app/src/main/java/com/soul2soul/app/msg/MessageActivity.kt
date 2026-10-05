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
    private lateinit var btnMic: Button
    private lateinit var btnSend: Button
    private val adapter by lazy { MsgAdapter() }
    private val uiTick = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recStartAt = 0L
    private var recFile: File? = null
    private var voiceBusy = false
    private var playingId: String? = null
    private var loadingId: String? = null
    private var player: MediaPlayer? = null
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val storeListener: () -> Unit = { runOnUiThread { syncFromStore() } }

    private val micPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else toast(getString(R.string.msg_need_mic))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        rv = findViewById(R.id.rvMessages)
        tvEmpty = findViewById(R.id.tvEmpty)
        etInput = findViewById(R.id.etInput)
        btnMic = findViewById(R.id.btnMic)
        btnSend = findViewById(R.id.btnSend)
        etInput.filters = arrayOf(InputFilter.LengthFilter(500))
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSend.setOnClickListener { sendText() }
        btnMic.setOnClickListener { onMicClicked() }
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

    /** 看见即读：从服务端删掉未读条目（阅后即删），本地列表继续展示 */
    private fun ackAll() {
        val ids = shown.filter { !it.mine }.map { it.id }
        if (ids.isEmpty()) return
        InboxStore.remove(ids)
        Presence.client.send("msg.read") { put("ids", JSONArray(ids)) }
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

    private fun onMicClicked() {
        if (voiceBusy) return
        if (recorder != null) { stopRecordingAndSend(); return }
        if (!Presence.client.isConnected) {
            toast(getString(R.string.msg_not_connected)); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun startRecording() {
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val rec = try {
            newRecorder().apply {
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
            file.delete()
            toast(getString(R.string.msg_rec_failed))
            return
        }
        recorder = rec
        recFile = file
        recStartAt = System.currentTimeMillis()
        btnMic.text = getString(R.string.msg_rec_stop, "0")
        uiTick.post(tickStop)
    }

    private val tickStop = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val secs = ((System.currentTimeMillis() - recStartAt) / 1000).toInt()
            btnMic.text = getString(R.string.msg_rec_stop, secs.toString())
            if (secs >= 30) { stopRecordingAndSend(); return }
            uiTick.postDelayed(this, 500)
        }
    }

    private fun stopRecordingAndSend() {
        uiTick.removeCallbacks(tickStop)
        val rec = recorder ?: return
        recorder = null
        val durMs = System.currentTimeMillis() - recStartAt
        val file = recFile
        runCatching { rec.stop() }.onFailure { file?.delete() }
        runCatching { rec.release() }
        btnMic.text = getString(R.string.msg_rec)
        if (file == null || durMs < 500 || !file.exists() || file.length() == 0L) {
            file?.delete()
            toast(getString(R.string.msg_rec_too_short))
            return
        }
        voiceBusy = true
        btnMic.text = getString(R.string.msg_sending)
        VoiceClient.upload(file) { voiceId ->
            voiceBusy = false
            btnMic.text = getString(R.string.msg_rec)
            file.delete()
            if (voiceId == null || !Presence.client.send("msg.post") {
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
                playingId = msg.id
                adapter.notifyItemRangeChanged(0, shown.size)
            }
        }
    }

    private fun stopPlay() {
        player?.let { runCatching { it.release() } }
        player = null
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
            holder.bubble.text = when {
                m.kind == "voice" -> {
                    val secs = ceil(m.durMs / 1000.0).toInt().coerceAtLeast(1)
                    when {
                        m.id == loadingId && !m.mine -> getString(R.string.msg_voice_loading)
                        m.id == playingId -> getString(R.string.msg_voice_playing, secs)
                        else -> getString(R.string.msg_voice_play, secs)
                    }
                }
                else -> "$label\n${m.text}"
            }
            if (voice && !m.mine) {
                holder.bubble.setOnClickListener { play(m) }
            } else {
                holder.bubble.setOnClickListener(null)
            }
        }
    }

    // ---------- 生命周期 ----------

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
    }
}
