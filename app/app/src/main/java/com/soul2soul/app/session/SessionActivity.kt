package com.soul2soul.app.session

import android.content.Context
import android.content.res.Configuration
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var remoteVideoTrack: VideoTrack? = null
    private lateinit var boxIncoming: View
    private lateinit var tvState: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvRtt: TextView
    private lateinit var boxLive: View
    private lateinit var btnBall: View
    private lateinit var emojiPanel: View
    private lateinit var fxRow: View
    private lateinit var controlsContainer: View
    private lateinit var emojiLayer: android.widget.FrameLayout
    private lateinit var fxLayer: OverlayCanvasView
    private lateinit var tvMic: TextView
    // 连通后语音默认开启（有麦克风权限即自动发流）——状态必须可见、可关
    private var micGranted = false
    private var micMuted = false
    private var emojiOpen = false
    // 收纳时连"面板打开"这个状态一起归零：否则下次轻点画面会把表情图层原样弹回来，
    // 用户感觉它永远关不掉，只能手动点 😊+ 收起
    private val hideControls = Runnable {
        emojiOpen = false
        setControlsVisible(false)
    }

    private var client: WebRtcClient? = null
    private val pendingSignals = mutableListOf<JSONObject>() // client 就绪前缓存 offer/ice
    private var accepted = false
    /** 本 Activity 是否占用了 Presence.sessionBusy；未接听退出也必须对称释放。 */
    private var sessionClaimed = false
    /** 来电已取消/拒绝/页面已退出时，拦住迟到的权限和 WebRTC 回调。 */
    private var callInvalidated = false
    private var endedRemotely = false
    private var byeSent = false
    private var hdOn = false
    private var live = false
    private var elapsedBase = 0L
    private var audioRoute: AudioRouteController? = null

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
            if (isFinishing || isDestroyed || callInvalidated || accepted) {
                return@registerForActivityResult
            }
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
            sessionClaimed = true
            Presence.sessionBusy = true
        }
        // 普通模式下返回键等价挂断；操控模式下明确把 Back 发到共享端，不得退出本地 Activity。
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::overlay.isInitialized && LocalBackPolicy.shouldRouteBackToRemote(live, overlay.controlMode)) {
                        sendRemoteBack()
                        return
                    }
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
        tvMic = findViewById(R.id.tvMic)
        tvMic.setOnClickListener {
            if (!micGranted) {
                android.widget.Toast.makeText(this, R.string.mic_denied_hint, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            micMuted = !micMuted
            client?.muteLocalAudio(micMuted)
            refreshMicChip()
        }
        emojiLayer = findViewById(R.id.emojiLayer)
        emojiPanel = findViewById(R.id.emojiPanel)
        fxRow = findViewById(R.id.fxRow)
        fxLayer = findViewById(R.id.fxLayer)
        controlsContainer = findViewById(R.id.controlsContainer)
        boxLive = findViewById(R.id.boxLive)
        btnBall = findViewById(R.id.btnBall)
        // 统一入口：与被控端同款球，点球=展开/收起整套菜单（与轻点画面同逻辑）
        btnBall.setOnClickListener { toggleControls() }
        setupBallDrag()

        renderer.init(App.instance.eglBase.eglBaseContext, null)
        // 让 SurfaceView 在合成层完成缩放，减少高分辨率视频在 UI 线程上的额外重采样。
        // 仍保留等比布局计算，避免 MIUI 合成层裁掉全面屏底部内容。
        renderer.setEnableHardwareScaler(true)
        // 缩放模式必须与 DrawingOverlayView.videoRect() 的 letterbox 假设一致（SPEC §5）
        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        // 黑屏排查：锁屏/解锁会销毁重建 surface，若 surface 死了而 sink 没重挂，
        // 对方画面就永远停在黑屏（媒体仍在收）。这里只报事实，不改行为。
        // 同时它也是"本端能不能渲染"的唯一权威信号：PiP 不销毁 surface，所以按 surface 而非
        // onPause 门控停帧告警（v0.2.57：观看端自己锁屏曾被记成 video_stall，污染 G 的判据）。
        renderer.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                client?.setLocalRenderPaused(false)
                viewerEvent("surface_created")
            }

            override fun surfaceChanged(
                holder: android.view.SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = viewerEvent("surface_size", "${width}x$height")

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                client?.setLocalRenderPaused(true)
                viewerEvent("surface_destroyed")
            }
        })
        // 首帧尺寸事件约 1~2 秒后才到，之前 View 保持 match_parent 会被合成层铺满拉伸裁底；
        // 开场先按上次会话缓存的远端尺寸套用等比布局，事件到达后再校正
        com.soul2soul.app.util.Prefs.lastVideoSize(this)?.let { (w, h) ->
            overlay.videoWidth = w
            overlay.videoHeight = h
            applyRendererFitLayout(w, h)
        }
        // 系统栏收起/唤回会改变根容器高度（约 ± 状态栏+导航栏），等比矩形必须跟着重算，
        // 否则沉浸态下视频按旧高度居中会偏上/偏下
        (renderer.parent as? View)?.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            clampBallIntoParent()
            if (live && (r - l != or - ol || b - t != ob - ot)) {
                applyRendererFitLayout(overlay.videoWidth, overlay.videoHeight)
            }
        }
        overlay.visibility = View.GONE // 接听并连通前不显示/不响应画笔层
        overlay.sink = object : DrawingOverlayView.StrokeSink {
            override fun onStroke(json: JSONObject) {
                // 操控手势走可靠 ctl 通道，其余（笔迹/表情/指令）走容忍丢包的 anno 通道
                if (json.optString("k") == "g") client?.sendControl(json)
                else client?.sendAnnotation(json)
            }
        }

        findViewById<View>(R.id.btnAccept).setOnClickListener {
            ensureMicThenAccept()
        }
        findViewById<View>(R.id.btnDecline).setOnClickListener {
            callInvalidated = true
            stopRinging()
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
            (v as android.widget.TextView).setText(if (hdOn) R.string.hd_on else R.string.hd_off)
            // 画质切换走可靠 ctl 通道，避免标注通道丢包导致按钮已切换但对端仍模糊。
            client?.sendQuality(
                if (hdOn) com.soul2soul.app.webrtc.VideoQualityPolicy.HD_EDGE
                else com.soul2soul.app.webrtc.VideoQualityPolicy.DEFAULT_EDGE,
            )
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
            fxRow.visibility = if (emojiOpen) View.VISIBLE else View.GONE
            setControlsVisible(true)
        }
        findViewById<View>(R.id.btnCtl).setOnClickListener {
            Log.d(
                "S2S-Ctl",
                "btnCtl click: controlMode=${overlay.controlMode} supported=${client?.controlSupported}"
            )
            if (!overlay.controlMode && client?.controlSupported != true) {
                // 旧版对端不会创建 ctl 通道：不进操控模式，否则手势只会淹没对方悬浮窗
                android.widget.Toast.makeText(this, R.string.ctl_peer_unsupported, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            applyControlMode(!overlay.controlMode)
        }
        findViewById<View>(R.id.btnRemoteBack).setOnClickListener {
            sendRemoteBack()
        }
        // 飘表情条按资源列表生成，加表情只改 strings.xml 的 call_emojis
        val emojiRow = findViewById<android.widget.LinearLayout>(R.id.emojiRow)
        for (e in resources.getStringArray(R.array.call_emojis)) {
            val tv = android.widget.TextView(this).apply {
                text = e
                textSize = 28f
                setPadding(
                    (10 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                )
            }
            tv.setOnClickListener { sendEmoji(e) }
            emojiRow.addView(tv)
        }
        listOf(
            R.id.fxBomb to "bomb", R.id.fxGift to "gift", R.id.fxRocket to "rocket",
        ).forEach { (id, t) ->
            findViewById<View>(id).setOnClickListener { sendFx(t) }
        }
        // 轻点画面 = 呼出/收纳控件；滑动 = 画笔（DrawingOverlayView 内部区分）
        overlay.onTap = { toggleControls() }
        // 操控模式里轻点会被注入对方，唤菜单改由长按承担
        overlay.onRevealControls = { setControlsVisible(true) }

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
        if (intent?.getBooleanExtra(EXTRA_INCOMING, false) == true && !accepted) {
            sessionClaimed = true
            callInvalidated = false
            Presence.sessionBusy = true
            showIncoming()
            if (intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) ensureMicThenAccept()
            return
        }
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

    /**
     * 入口球拖动：她反馈"右下角小铅笔拖不动"——这颗球此前只挂了点击，画面里的字幕/笔迹
     * 一旦被它挡住就挪不开。位移不足 touchSlop 时仍按点击处理（展开/收起菜单），超过才进拖动。
     */
    private fun setupBallDrag() {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        btnBall.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startTx = btnBall.translationX
                    startTy = btnBall.translationY
                    dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < slop) return@setOnTouchListener true
                    dragging = true
                    btnBall.translationX = startTx + dx
                    btnBall.translationY = startTy + dy
                    clampBallIntoParent()
                    true
                }
                android.view.MotionEvent.ACTION_UP ->
                    if (dragging) true else btnBall.performClick()
                else -> true
            }
        }
    }

    /** 球始终留在父容器内：转屏/系统栏收起会让父尺寸变化，那时也要把它拉回来 */
    private fun clampBallIntoParent() {
        if (!::btnBall.isInitialized) return
        val parent = btnBall.parent as? View ?: return
        if (parent.width == 0 || parent.height == 0) return
        btnBall.translationX = btnBall.translationX.coerceIn(
            -btnBall.left.toFloat(),
            (parent.width - btnBall.left - btnBall.width).toFloat().coerceAtLeast(-btnBall.left.toFloat()),
        )
        btnBall.translationY = btnBall.translationY.coerceIn(
            -btnBall.top.toFloat(),
            (parent.height - btnBall.top - btnBall.height).toFloat().coerceAtLeast(-btnBall.top.toFloat()),
        )
    }

    private fun toggleControls() {
        setControlsVisible(boxLive.visibility != View.VISIBLE)
    }

    private fun setControlsVisible(visible: Boolean) {
        // 容器曾默认 gone 且只有 PiP 回调会点亮它——正常通话里整套控件永远弹不出
        controlsContainer.visibility = if (visible) View.VISIBLE else View.GONE
        boxLive.visibility = if (visible) View.VISIBLE else View.GONE
        emojiPanel.visibility = if (visible && emojiOpen) View.VISIBLE else View.GONE
        fxRow.visibility = if (visible && emojiOpen) View.VISIBLE else View.GONE
        // 沉浸联动：控件收起时状态栏/导航栏一起收起，整屏只剩对方画面+纯黑边；
        // 轻点唤回控件时系统栏同步回来。侧边滑入仍可临时呼出系统栏
        if (live) applyImmersiveBars(!visible)
        mainHandler.removeCallbacks(hideControls)
        // 操控模式里菜单是"临时唤出"，8 秒不碰也自动收回去让出全屏
        if (visible) mainHandler.postDelayed(hideControls, if (overlay.controlMode) 8000L else 5000L)
    }

    /**
     * 只藏本设备（观看端）自己的系统栏，视频保持等比原始大小、空隙为纯黑。
     * Android 7/MIUI 上 WindowInsetsControllerCompat.hide(systemBars) 只写入了
     * HIDE_NAVIGATION、FULLSCREEN 位被吞（实测 mSystemUiVisibility=0x1606），
     * 故 API<30 直接操作 legacy flags；API>=30 走 controller。
     */
    private fun applyImmersiveBars(hideSystemBars: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hideSystemBars) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hideSystemBars) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                0 // 恢复默认：内容回到系统栏之间，控件不会被导航栏压住
            }
        }
    }

    // ---------- 表情互动 ----------

    private fun sendEmoji(e: String) {
        val x = 0.15 + Math.random() * 0.7
        client?.sendAnnotation(
            JSONObject().put("k", "emoji").put("e", e).put("x", x)
        )
        showLocalEmoji(e)
        // 重置收纳计时：还在连续挑表情就别中途收起，停手 5 秒后整套自动消失
        setControlsVisible(true)
    }

    /** 满屏特效：发给对方 + 本地回显（fxLayer 不消费触摸，不挡操控） */
    private fun sendFx(t: String) {
        val json = JSONObject().put("k", "fx").put("t", t)
        client?.sendAnnotation(json)
        fxLayer.applyFx(json)
        setControlsVisible(true)
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

    private fun refreshMicChip() {
        tvMic.setText(
            when {
                !micGranted -> R.string.mic_none
                micMuted -> R.string.mic_muted
                else -> R.string.mic_on
            }
        )
        tvMic.alpha = if (micGranted) 1f else 0.5f
    }

    // ---------- 来电状态 ----------

    private fun showIncoming() {
        // singleTask 的通知点击/新来电会复用 Activity；先清掉旧计时器和铃声，避免双计时器竞态。
        stopRinging()
        boxIncoming.visibility = View.VISIBLE
        boxLive.visibility = View.GONE
        btnBall.visibility = View.GONE
        startRinging()
        // 30 秒不接听自动拒接（主叫端 30s 也会超时取消）
        incomingCountdown = object : CountDownTimer(INCOMING_TIMEOUT_MS, 1000) {
            override fun onTick(msLeft: Long) {
                tvCountdown.text = getString(R.string.countdown_hint, msLeft / 1000 + 1)
            }

            override fun onFinish() {
                if (!accepted) {
                    callInvalidated = true
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
        if (callInvalidated || isFinishing || isDestroyed || accepted) return
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
        if (accepted || callInvalidated || isFinishing || isDestroyed) return
        accepted = true
        sessionClaimed = true
        Presence.sessionBusy = true
        stopRinging()
        com.soul2soul.app.util.WifiKeeper.acquire(this) // 观看端同样持 WiFi 高性能锁
        // 先稳定通信模式/扬声器路由，再创建 WebRTC 音频模块，避免连通瞬间切路由爆音。
        try {
            audioRoute = AudioRouteController(this).also { it.start() }
            ensureClient()
        } catch (e: Exception) {
            Log.e("SessionActivity", "start WebRTC failed", e)
            android.widget.Toast.makeText(this, R.string.connect_timeout, android.widget.Toast.LENGTH_LONG).show()
            finishWithCleanup(sendBye = true)
            return
        }
        // 前提：PresenceService 在线（呼叫通知就是它发出来的，说明连接活着）
        Presence.client.send("accept")
        boxIncoming.visibility = View.GONE
        tvState.visibility = View.VISIBLE
        tvState.setText(R.string.connecting)
        // 30 秒内 ICE 仍未连通（对方崩溃/协商失败/网络不可达）就自动结束。
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
        when (val action = SignalRouter.routeSignal(json.optString("type"), accepted, client != null)) {
            SignalRouter.SignalAction.None -> Unit
            SignalRouter.SignalAction.BufferMedia -> pendingSignals += json
            SignalRouter.SignalAction.DispatchMedia -> dispatchToClient(json)
            is SignalRouter.SignalAction.PeerEnded -> {
                endedRemotely = true
                val gone = action.noticeRes == R.string.peer_lost
                // 与共享端同一条判据：先记 ICE 实况再拆页面。peer.gone 只是对端**信令**掉线
                // （HyperOS 冻结后台套接字 → 服务器 pong 超时广播），ICE=CONNECTED 的 peer_signal
                // 就等于「会话被信令假死拆掉」，是"米15 发起后几秒即断"的头号候选。
                viewerEvent("peer_signal", if (gone) "sig=gone" else "sig=bye")
                finishWithCleanup(
                    sendBye = false,
                    notice = action.noticeRes,
                    endReason = if (gone) "peer_gone" else "remote_bye",
                )
            }
            SignalRouter.SignalAction.CallerCanceledBeforeAccept -> {
                callInvalidated = true
                stopRinging()
                android.widget.Toast.makeText(this, R.string.peer_canceled, android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
            SignalRouter.SignalAction.CanceledAfterAccept -> {
                // 主叫在授权阶段反悔: 别让人对着"连接中"数 30 秒
                endedRemotely = true
                android.widget.Toast.makeText(this, R.string.peer_canceled, android.widget.Toast.LENGTH_SHORT).show()
                // 这与"她挂断已建立的会话"是两种死法：这一通从没连通过，混成 remote_bye
                // 就会把"授权阶段反悔"读成"连上几秒被挂断"——正是我们要查的那一格的假阳性。
                finishWithCleanup(sendBye = false, endReason = "call_canceled")
            }
        }
    }

    // ---------- WebRtcClient.Listener ----------

    override fun onSignalOut(json: JSONObject) {
        Presence.client.sendRaw(json)
    }

    override fun onRemoteVideo(track: VideoTrack) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (remoteVideoTrack === track) return@runOnUiThread
            remoteVideoTrack?.removeSink(renderer)
            remoteVideoTrack = track
            track.addSink(renderer)
            viewerEvent("sink_attached")
            Log.d("S2S-Session", "remote video attached")
        }
    }

    override fun onRemoteVideoSize(width: Int, height: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed || callInvalidated) return@runOnUiThread
            val changed = overlay.videoWidth != width || overlay.videoHeight != height
            if (changed) {
                Log.d("S2S-Session", "video size: ${width}x$height") // 首帧到达即证明视频链路在出帧
            }
            overlay.videoWidth = width
            overlay.videoHeight = height
            applyRendererFitLayout(width, height)
            if (changed) {
                com.soul2soul.app.util.Prefs.setLastVideoSize(this, width, height)
                val (sw, sh) = com.soul2soul.app.util.ScreenSize.real(this)
                Log.d(
                    "S2S-Geom",
                    "viewer display=${sw}x${sh} app=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                        "video=${width}x${height} view=${overlay.width}x${overlay.height}",
                )
            }
        }
    }

    /**
     * 把渲染 View 缩成"视频等比适配"后的实际矩形并居中（SCALE_ASPECT_FIT 的等效几何）。
     * 原因：letterbox 若只靠 GL 留边实现，MIUI 合成层会把视频 SurfaceView 拉伸铺满、
     * 裁掉留边和画面底部；视图比例=内容比例后，任何合成都无从裁切。
     * DrawingOverlayView 的 fitRect 触控映射基于同一公式，缩放后仍然对齐。
     */
    private fun applyRendererFitLayout(width: Int, height: Int) {
        val parent = renderer.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        if (width <= 0 || height <= 0) return
        if (pw <= 0 || ph <= 0) {
            parent.post { applyRendererFitLayout(width, height) }
            return
        }
        val scale = minOf(pw.toFloat() / width, ph.toFloat() / height)
        val w = Math.round(width * scale)
        val h = Math.round(height * scale)
        val lp = renderer.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        if (lp.width == w && lp.height == h && lp.gravity == android.view.Gravity.CENTER) return
        lp.width = w
        lp.height = h
        lp.gravity = android.view.Gravity.CENTER
        renderer.layoutParams = lp
    }

    /** 共享端 DataChannel 状态通知（锁屏等），在观看端显示明确状态 */
    override fun onDataMessage(json: JSONObject) {
        runOnUiThread {
            if (isFinishing || isDestroyed || callInvalidated) return@runOnUiThread
            // 判据（controlMode/live）与 UI 更新同在主线程：与提取前的读取时序一致
            when (val action = SignalRouter.routeData(
                json.optString("k"), overlay.controlMode, live, json.optString("reason")
            )) {
                SignalRouter.DataAction.None -> Unit
                SignalRouter.DataAction.PeerScreenOff -> {
                    tvState.visibility = View.VISIBLE
                    tvState.setText(R.string.peer_screen_off)
                    // 锁屏期间零帧是设计行为（画面冻在最后一帧·语音继续），
                    // 不同步这个位每次锁屏都会误报一次 video_stall
                    client?.setRemoteScreenPaused(true)
                }
                SignalRouter.DataAction.HideBanner -> {
                    // 对方锁屏/收回授权：手势被静默丢弃时横幅必须说明原因，
                    // 否则操控端只会以为"功能坏了"（历史工单：退出后不能操控=这个）
                    tvState.visibility = View.GONE
                    // 横幅收起与暂停复位同一条路径（screenon 唯一入口），不给漏配机会
                    client?.setRemoteScreenPaused(false)
                }
                SignalRouter.DataAction.RevokedBanner -> {
                    // 对方收回授权=本地操控已成死模式：必须先同步退出，再报原因。
                    // 只改横幅不改模式的话，5 秒后横幅谎称"操控中"，手势永远石沉大海
                    applyControlMode(false)
                    val banner = findViewById<TextView>(R.id.tvCtlMode)
                    banner.visibility = View.VISIBLE
                    banner.setText(R.string.ctl_revoked_banner)
                    mainHandler.removeCallbacks(restoreCtlBanner)
                    mainHandler.postDelayed(restoreCtlBanner, 5000)
                }
                is SignalRouter.DataAction.ControlFailure -> {
                    val banner = findViewById<TextView>(R.id.tvCtlMode)
                    banner.setText(action.messageRes)
                    mainHandler.removeCallbacks(restoreCtlBanner)
                    mainHandler.postDelayed(restoreCtlBanner, 5000)
                }
            }
        }
    }

    /** 操控模式进/出的唯一正道：按钮点击、被收回授权都必须走这里。
     *  六处 UI 状态（模式位/按钮文案/底色/横幅/菜单/返回键）任何一处漏改，
     *  观看端就会出现"横幅说操控中、手势全被对方丢弃"的假状态 */
    private fun applyControlMode(on: Boolean) {
        overlay.controlMode = on
        val btn = findViewById<android.widget.TextView>(R.id.btnCtl)
        btn.setText(if (on) R.string.ctl_on else R.string.ctl_off)
        btn.setBackgroundResource(
            if (on) R.drawable.bg_chip_active else R.drawable.bg_chip
        )
        mainHandler.removeCallbacks(restoreCtlBanner)
        findViewById<View>(R.id.tvCtlMode).visibility =
            if (on) View.VISIBLE else View.GONE
        if (on) findViewById<TextView>(R.id.tvCtlMode).setText(R.string.ctl_banner)
        // 进操控先收起整套菜单：挡全屏画面。长按画面可重新唤出（含退出操控）
        setControlsVisible(!on)
        findViewById<View>(R.id.btnRemoteBack).visibility =
            if (on) View.VISIBLE else View.GONE
    }

    private val restoreCtlBanner = Runnable {
        if (overlay.controlMode) findViewById<TextView>(R.id.tvCtlMode).setText(R.string.ctl_banner)
        else findViewById<View>(R.id.tvCtlMode).visibility = View.GONE
    }

    private fun sendRemoteBack() {
        val pts = org.json.JSONArray()
        GestureIntent.remoteBackPoints().forEach { point ->
            pts.put(org.json.JSONArray().put(point.x).put(point.y))
        }
        client?.sendControl(JSONObject().put("k", "g").put("pts", pts).put("dur", 180L))
    }

    /** 实时性可视化：网络 RTT 每 2 秒刷新，颜色分级（绿<100ms / 橙<250ms / 红≥250ms） */
    override fun onRtt(ms: Long) {
        runOnUiThread {
            if (isFinishing || isDestroyed || callInvalidated) return@runOnUiThread
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
            if (isFinishing || isDestroyed || callInvalidated) return@runOnUiThread
            acceptTimeout?.let { mainHandler.removeCallbacks(it) }
            acceptTimeout = null
            tvState.visibility = View.GONE
            setControlsVisible(true)
            btnBall.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE // 接听并连通前不显示/不响应画笔层
            live = true
            micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            refreshMicChip()
            elapsedBase = System.currentTimeMillis()
            mainHandler.removeCallbacks(elapsedTicker)
            mainHandler.post(elapsedTicker)
            // 机型/权限自检矩阵上报（对方手机不在手边时的远程诊断依据）
            com.soul2soul.app.util.SelfCheck.report(
                this, "viewer",
                JSONObject().put("ctlSupported", client?.controlSupported == true),
            )
            Log.d(
                "S2S-Geom",
                "viewer-ready app=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                    "view=${overlay.width}x${overlay.height}",
            )
        }
    }

    override fun onEnded(reason: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed || callInvalidated) return@runOnUiThread
            // 这是本端 PeerConnection 报告的失败/超时，需要通知共享端立即收口；
            // 远端主动挂断仍由 bye/peer.gone 信令分支处理。
            finishWithCleanup(sendBye = true, notice = R.string.peer_lost)
        }
    }

    // ---------- 生命周期 ----------

    private fun finishWithCleanup(sendBye: Boolean, notice: Int? = null, endReason: String? = null) {
        callInvalidated = true
        if (notice != null) {
            android.widget.Toast.makeText(this, notice, android.widget.Toast.LENGTH_SHORT).show()
        }
        if (sendBye && !byeSent) {
            byeSent = true
            Presence.client.send("bye")
        }
        // 本端收尾也要有名字：否则服务器只看到一个没有原因的 local_close。
        // 名字必须由调用方给出而不是从 sendBye 反推——"她挂断""信令掉线""授权阶段反悔"
        // 三种都走 sendBye=false，全叫 remote_bye 就等于没埋（first-wins，上游真因仍不被覆盖）。
        client?.noteEnd(endReason ?: if (sendBye) "user_hangup" else "remote_bye")
        finish()
    }

    /** 观看端事件自报（服务器看不见媒体与界面生命周期，黑屏/卡死只能靠这里） */
    private fun viewerEvent(event: String, detail: String? = null) {
        if (callInvalidated) return
        com.soul2soul.app.util.SelfCheck.reportEvent(
            this, "viewer", event, client?.iceStateName ?: "-", detail
        )
    }

    override fun onResume() {
        super.onResume()
        viewerEvent("viewer_resumed")
    }

    override fun onPause() {
        viewerEvent("viewer_paused")
        super.onPause()
    }

    /** 离开全屏时自动进入画中画，继续陪看（观看端核心体验） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 锁屏/息屏也会回调 onUserLeaveHint：此时进小窗毫无意义，还会销毁重建 surface
        // （观看端"解锁后永久黑屏"的头号嫌疑——renderer.init 全生命周期只跑一次）
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power?.isInteractive == false) {
            viewerEvent("leave_hint_screen_off")
            return
        }
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
        // 埋点：G 的怀疑路径是"锁屏→自动进小窗→surface 销毁重建无人重挂"，
        // 没有这条事件就无法证实/证伪，只能靠猜
        viewerEvent(
            if (isInPictureInPictureMode) "pip_enter" else "pip_exit",
            "live=$live accepted=$accepted",
        )
        // 小窗里隐藏操作控件，只留画面
        val inPip = isInPictureInPictureMode
        controlsContainer?.visibility = if (inPip || !live) View.GONE else View.VISIBLE
        emojiPanel.visibility = if (inPip || !live || !emojiOpen) View.GONE else View.VISIBLE
        fxRow.visibility = if (inPip || !live || !emojiOpen) View.GONE else View.VISIBLE
        overlay.visibility = if (inPip || !live) View.GONE else View.VISIBLE
        btnBall.visibility = if (inPip || !live) View.GONE else View.VISIBLE
        if (!inPip && live) {
            applyImmersiveBars(false) // 小窗返回全屏：系统栏随控件一起先回来
            mainHandler.removeCallbacks(hideControls)
            // 操控模式：菜单只能长按唤出，唤出后 8 秒自动收回让出全屏（旧版"永不收纳"是怕再也切不回画笔，现在长按就是出口）
            if (!overlay.controlMode) mainHandler.postDelayed(hideControls, 5000)
            else mainHandler.postDelayed(hideControls, 8000)
        }
        findViewById<View>(R.id.tvCtlMode).visibility =
            if (!inPip && live && overlay.controlMode) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        callInvalidated = true
        if (sessionClaimed) Presence.sessionBusy = false
        stopRinging()
        com.soul2soul.app.util.WifiKeeper.release()
        acceptTimeout?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(hideControls)
        mainHandler.removeCallbacks(elapsedTicker)
        mainHandler.removeCallbacks(restoreCtlBanner)
        androidx.core.app.NotificationManagerCompat.from(this).cancel(Notif.ID_CALL)
        // 用户划掉小窗/最近任务时 isFinishing=true，这里也要发 bye，否则对端傻等掉线兜底
        if (accepted && !endedRemotely && !byeSent) {
            byeSent = true
            Presence.client.send("bye")
        }
        remoteVideoTrack?.removeSink(renderer)
        remoteVideoTrack = null
        client?.close()
        client = null
        // WebRTC 播放/采集彻底停止后才恢复系统路由，避免关闭阶段漏出残音。
        audioRoute?.stop()
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
