package com.soul2soul.app.session

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.soul2soul.app.App
import com.soul2soul.app.R
import com.soul2soul.app.signaling.SignalBus
import com.soul2soul.app.webrtc.IceServerParser
import com.soul2soul.app.webrtc.WebRtcClient
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.Locale

/**
 * 观看端会话页：来电(铃声/振动/30s倒计时) → 接受 → 全屏观看 + 画笔 + 语音 → 挂断。
 * 支持画中画（PiP）：退出全屏时自动缩成小窗继续陪看。
 */
class SessionActivity : AppCompatActivity(), WebRtcClient.Listener {

    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var overlay: DrawingOverlayView
    private lateinit var boxIncoming: View
    private lateinit var tvState: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvRtt: TextView
    private lateinit var boxLive: View
    private lateinit var emojiPanel: View
    private lateinit var controlsContainer: View
    private lateinit var emojiLayer: android.widget.FrameLayout
    private var emojiOpen = false
    private val hideControls = Runnable { setControlsVisible(false) }

    private var client: WebRtcClient? = null
    private val pendingSignals = mutableListOf<JSONObject>() // client 就绪前缓存 offer/ice
    private var accepted = false
    private var endedRemotely = false
    private var byeSent = false
    private var hdOn = false
    private var live = false
    private var elapsedBase = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null
    private var incomingCountdown: CountDownTimer? = null
    private var acceptTimeout: Runnable? = null
    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (!live) return
            val s = (System.currentTimeMillis() - elapsedBase) / 1000
            tvElapsed.text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                accept()
            } else {
                // 降级为纯观看，但要明确告知，避免"TA 怎么不说话"的误会
                android.widget.Toast.makeText(this, R.string.mic_denied_hint, android.widget.Toast.LENGTH_LONG).show()
                accept()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 只有真来电（带 EXTRA_INCOMING 或 PresenceService 拉起）才占用会话；
        // 测试钩子/误启动不置位，避免误吞后续真实来电
        if (intent?.getBooleanExtra(EXTRA_INCOMING, false) == true) {
            Presence.sessionBusy = true
        }
        // 返回键等价挂断：通知对方正常结束，而不是靠掉线兜底
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishWithCleanup(sendBye = true)
                }
            },
        )

        renderer = findViewById(R.id.renderer)
        overlay = findViewById(R.id.drawingOverlay)
        boxIncoming = findViewById(R.id.boxIncoming)
        tvState = findViewById(R.id.tvState)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvElapsed = findViewById(R.id.tvElapsed)
        tvRtt = findViewById(R.id.tvRtt)
        emojiLayer = findViewById(R.id.emojiLayer)
        emojiPanel = findViewById(R.id.emojiPanel)
        controlsContainer = findViewById(R.id.controlsContainer)
        boxLive = findViewById(R.id.boxLive)

        renderer.init(App.instance.eglBase.eglBaseContext, null)
        // 缩放模式必须与 DrawingOverlayView.videoRect() 的 letterbox 假设一致（SPEC §5）
        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        overlay.visibility = View.GONE // 接听并连通前不显示/不响应画笔层
        overlay.sink = object : DrawingOverlayView.StrokeSink {
            override fun onStroke(json: JSONObject) {
                client?.sendAnnotation(json)
            }
        }

        findViewById<View>(R.id.btnAccept).setOnClickListener {
            ensureMicThenAccept()
        }
        findViewById<View>(R.id.btnDecline).setOnClickListener {
            Presence.client.send("decline")
            finish()
        }
        findViewById<View>(R.id.btnHangup).setOnClickListener {
            finishWithCleanup(sendBye = true)
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            overlay.clearAll()
            client?.sendAnnotation(JSONObject().put("k", "clear"))
        }
        findViewById<View>(R.id.btnHd).setOnClickListener { v ->
            hdOn = !hdOn
            (v as android.widget.Button).setText(if (hdOn) R.string.hd_on else R.string.hd_off)
            client?.sendAnnotation(JSONObject().put("k", "res").put("edge", if (hdOn) 1920 else 1280))
        }
        findViewById<View>(R.id.btnColor0).setOnClickListener {
            overlay.setColor(0); refreshColorButtons()
        }
        findViewById<View>(R.id.btnColor1).setOnClickListener {
            overlay.setColor(1); refreshColorButtons()
        }
        findViewById<View>(R.id.btnColor2).setOnClickListener {
            overlay.setColor(2); refreshColorButtons()
        }
        findViewById<View>(R.id.btnEmoji).setOnClickListener {
            emojiOpen = !emojiOpen
            emojiPanel.visibility = if (emojiOpen) View.VISIBLE else View.GONE
            setControlsVisible(true)
        }
        listOf(
            R.id.emoji0 to "❤️", R.id.emoji1 to "😂", R.id.emoji2 to "👍",
            R.id.emoji3 to "😮", R.id.emoji4 to "🥺", R.id.emoji5 to "🔥",
        ).forEach { (id, e) ->
            findViewById<View>(id).setOnClickListener { sendEmoji(e) }
        }
        // 轻点画面 = 呼出/收纳控件；滑动 = 画笔（DrawingOverlayView 内部区分）
        overlay.onTap = { toggleControls() }

        lifecycleScope.launch {
            SignalBus.events.collect { handleSignal(it) }
        }

        if (intent?.getBooleanExtra(EXTRA_INCOMING, false) == true || !accepted) {
            showIncoming()
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_ACCEPT, false) == true) {
            ensureMicThenAccept()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!accepted) {
            boxIncoming.visibility = View.VISIBLE
            if (intent?.getBooleanExtra(EXTRA_AUTO_ACCEPT, false) == true) {
                ensureMicThenAccept()
            }
        }
    }

    private fun refreshColorButtons() {
        val ids = listOf(R.id.btnColor0, R.id.btnColor1, R.id.btnColor2)
        ids.forEachIndexed { i, id ->
            findViewById<View>(id).alpha = if (i == overlay.colorIndex) 1f else 0.4f
        }
    }

    // ---------- 控件显隐 ----------

    private fun toggleControls() {
        setControlsVisible(boxLive.visibility != View.VISIBLE)
    }

    private fun setControlsVisible(visible: Boolean) {
        boxLive.visibility = if (visible) View.VISIBLE else View.GONE
        emojiPanel.visibility = if (visible && emojiOpen) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(hideControls)
        if (visible) mainHandler.postDelayed(hideControls, 5000) // 5 秒无操作自动收纳
    }

    // ---------- 表情互动 ----------

    private val emojiChars = listOf("❤️", "😂", "👍", "😮", "🥺", "🔥")

    private fun sendEmoji(e: String) {
        val x = 0.15 + Math.random() * 0.7
        client?.sendAnnotation(
            JSONObject().put("k", "emoji").put("e", e).put("x", x)
        )
        showLocalEmoji(e)
    }

    /** 本地回显：让对方屏幕飘表情的同时，自己也能立刻看到（正反馈） */
    private fun showLocalEmoji(e: String) {
        val tv = TextView(this).apply {
            text = e
            textSize = 40f
        }
        emojiLayer.addView(
            tv,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                leftMargin = (100..(emojiLayer.width - 200).coerceAtLeast(101)).random()
                bottomMargin = 260
            }
        )
        tv.animate().translationYBy(-500f).alpha(0f).setDuration(1200)
            .withEndAction { (tv.parent as? android.view.ViewGroup)?.removeView(tv) }
            .start()
    }

    // ---------- 来电状态 ----------

    private fun showIncoming() {
        boxIncoming.visibility = View.VISIBLE
        boxLive.visibility = View.GONE
        startRinging()
        // 30 秒不接听自动拒接（主叫端 30s 也会超时取消）
        incomingCountdown = object : CountDownTimer(INCOMING_TIMEOUT_MS, 1000) {
            override fun onTick(msLeft: Long) {
                tvCountdown.text = getString(R.string.countdown_hint, msLeft / 1000 + 1)
            }

            override fun onFinish() {
                if (!accepted) {
                    Presence.client.send("decline")
                    finish()
                }
            }
        }.start()
    }

    private fun startRinging() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    isLooping = true // API 28+；26/27 上铃声响一轮，振动持续补位
                }
                play()
            }
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = longArrayOf(0, 400, 600)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }.onFailure { Log.w("SessionActivity", "ring failed", it) }
    }

    private fun stopRinging() {
        incomingCountdown?.cancel()
        incomingCountdown = null
        runCatching {
            ringtone?.stop()
            vibrator?.cancel()
        }
        ringtone = null
        vibrator = null
    }

    // ---------- 接听 ----------

    private fun ensureMicThenAccept() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            accept()
        } else {
            stopRinging() // 申请权限期间别响铃
            // 锁屏之上弹不出系统权限框：先解锁再授权，别让人以为按钮失灵
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (km.isKeyguardLocked) {
                tvState.visibility = View.VISIBLE
                tvState.setText(R.string.unlock_to_grant_mic)
            }
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 观看端 WebRtcClient 懒创建：accept 前到达的 sdp/ice 会缓存，创建后回放 */
    private fun ensureClient() {
        if (client != null) return
        val c = WebRtcClient(
            context = this,
            isSharer = false,
            iceServers = IceServerParser.parse(Presence.iceServersJson),
            listener = this,
        )
        client = c
        c.startViewer()
        pendingSignals.forEach { dispatchToClient(it) }
        pendingSignals.clear()
    }

    private fun dispatchToClient(json: JSONObject) {
        when (json.optString("type")) {
            "sdp" -> client?.onRemoteSdp(json.getJSONObject("sdp"))
            "ice" -> client?.onRemoteIce(json.getJSONObject("candidate"))
        }
    }

    private fun accept() {
        if (accepted) return
        accepted = true
        Presence.sessionBusy = true
        stopRinging()
        com.soul2soul.app.util.WifiKeeper.acquire(this) // 观看端同样持 WiFi 高性能锁
        ensureClient()
        // 前提：PresenceService 在线（呼叫通知就是它发出来的，说明连接活着）
        Presence.client.send("accept")
        boxIncoming.visibility = View.GONE
        tvState.visibility = View.VISIBLE
        tvState.setText(R.string.connecting)
        // 30 秒没等到 offer（对方崩溃/掉线）就自动结束，别让人对着"连接中"发呆
        val timeout = Runnable {
            if (!live) {
                android.widget.Toast.makeText(this, R.string.connect_timeout, android.widget.Toast.LENGTH_LONG).show()
                finishWithCleanup(sendBye = true)
            }
        }
        acceptTimeout = timeout
        mainHandler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    private fun handleSignal(json: JSONObject) {
        when (json.optString("type")) {
            "sdp" -> {
                acceptTimeout?.let { mainHandler.removeCallbacks(it) }
                if (client == null) pendingSignals += json else dispatchToClient(json)
            }
            "ice" -> if (client == null) pendingSignals += json else dispatchToClient(json)
            // 迟到的 bye/取消（上一轮呼叫的回声）：未接听状态下忽略，别把新页面误杀
            "bye" -> {
                if (accepted) {
                    endedRemotely = true
                    finishWithCleanup(sendBye = false, notice = R.string.peer_ended)
                }
            }
            "peer.gone" -> {
                if (accepted) {
                    endedRemotely = true
                    finishWithCleanup(sendBye = false, notice = R.string.peer_lost)
                }
            }
            "call.canceled" -> if (!accepted) {
                stopRinging()
                android.widget.Toast.makeText(this, R.string.peer_canceled, android.widget.Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // 主叫在授权阶段反悔: 别让人对着"连接中"数 30 秒
                endedRemotely = true
                android.widget.Toast.makeText(this, R.string.peer_canceled, android.widget.Toast.LENGTH_SHORT).show()
                finishWithCleanup(sendBye = false)
            }
        }
    }

    // ---------- WebRtcClient.Listener ----------

    override fun onSignalOut(json: JSONObject) {
        Presence.client.sendRaw(json)
    }

    override fun onRemoteVideo(track: VideoTrack) {
        runOnUiThread {
            track.addSink(renderer)
            Log.d("S2S-Session", "remote video attached")
        }
    }

    override fun onRemoteVideoSize(width: Int, height: Int) {
        runOnUiThread {
            if (overlay.videoWidth != width) {
                Log.d("S2S-Session", "video size: ${width}x$height") // 首帧到达即证明视频链路在出帧
            }
            overlay.videoWidth = width
            overlay.videoHeight = height
        }
    }

    override fun onDataMessage(json: JSONObject) {}

    /** 实时性可视化：网络 RTT 每 2 秒刷新，颜色分级（绿<100ms / 橙<250ms / 红≥250ms） */
    override fun onRtt(ms: Long) {
        runOnUiThread {
            tvRtt.text = getString(R.string.rtt_display, ms)
            tvRtt.setTextColor(
                when {
                    ms < 100 -> androidx.core.content.ContextCompat.getColor(this, R.color.green)
                    ms < 250 -> android.graphics.Color.parseColor("#FF9F0A")
                    else -> androidx.core.content.ContextCompat.getColor(this, R.color.primary)
                }
            )
        }
    }

    override fun onLive() {
        runOnUiThread {
            tvState.visibility = View.GONE
            setControlsVisible(true)
            overlay.visibility = View.VISIBLE // 接听并连通前不显示/不响应画笔层
            setSpeakerphone(true)
            live = true
            elapsedBase = System.currentTimeMillis()
            mainHandler.removeCallbacks(elapsedTicker)
            mainHandler.post(elapsedTicker)
        }
    }

    override fun onEnded(reason: String) {
        runOnUiThread {
            endedRemotely = true
            finishWithCleanup(sendBye = false, notice = R.string.peer_lost)
        }
    }

    // ---------- 生命周期 ----------

    private fun finishWithCleanup(sendBye: Boolean, notice: Int? = null) {
        if (notice != null) {
            android.widget.Toast.makeText(this, notice, android.widget.Toast.LENGTH_SHORT).show()
        }
        if (sendBye && !byeSent) {
            byeSent = true
            Presence.client.send("bye")
        }
        finish()
    }

    private fun setSpeakerphone(on: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    /** 离开全屏时自动进入画中画，继续陪看（观看端核心体验） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (live && android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val aspect = if (overlay.videoWidth > 0 && overlay.videoHeight > 0) {
                android.util.Rational(overlay.videoWidth, overlay.videoHeight)
            } else {
                android.util.Rational(9, 16)
            }
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 小窗里隐藏操作控件，只留画面
        val inPip = isInPictureInPictureMode
        controlsContainer?.visibility = if (inPip || !live) View.GONE else View.VISIBLE
        emojiPanel.visibility = if (inPip || !live || !emojiOpen) View.GONE else View.VISIBLE
        overlay.visibility = if (inPip || !live) View.GONE else View.VISIBLE
        if (!inPip && live) {
            mainHandler.removeCallbacks(hideControls)
            mainHandler.postDelayed(hideControls, 5000)
        }
    }

    override fun onDestroy() {
        if (accepted) Presence.sessionBusy = false
        stopRinging()
        setSpeakerphone(false)
        com.soul2soul.app.util.WifiKeeper.release()
        acceptTimeout?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(elapsedTicker)
        androidx.core.app.NotificationManagerCompat.from(this).cancel(Notif.ID_CALL)
        // 用户划掉小窗/最近任务时 isFinishing=true，这里也要发 bye，否则对端傻等掉线兜底
        if (accepted && !endedRemotely && !byeSent) {
            byeSent = true
            Presence.client.send("bye")
        }
        client?.close()
        client = null
        renderer.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INCOMING = "incoming"

        /** E2E 测试钩子: 跳过点击直接接听（仅模拟器自动化用，不影响真人交互） */
        const val EXTRA_AUTO_ACCEPT = "autoAccept"

        private const val INCOMING_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
