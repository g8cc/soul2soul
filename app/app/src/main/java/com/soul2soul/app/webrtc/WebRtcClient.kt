package com.soul2soul.app.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.soul2soul.app.App
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * 一条会话的 PeerConnection 生命周期封装。
 * 共享端: startSharer(projectionData) —— 内部创建 ScreenCapturer（必须在 FGS startForeground 之后）
 * 观看端: startViewer() 之后等信令 offer 到达调 onRemoteSdp()
 * 标注: 双方通过 DataChannel 收发 stroke 消息（SPEC §3）
 */
class WebRtcClient(
    private val context: Context,
    private val isSharer: Boolean,
    private val iceServers: List<PeerConnection.IceServer>,
    private val listener: Listener,
) : PeerConnection.Observer, DataChannel.Observer {
    private val audioRecordErrorCallback = object : JavaAudioDeviceModule.AudioRecordErrorCallback {
        override fun onWebRtcAudioRecordInitError(error: String) {
            Log.e(TAG, "audio record init error: $error")
        }

        override fun onWebRtcAudioRecordStartError(
            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
            error: String,
        ) {
            Log.e(TAG, "audio record start error: code=$errorCode message=$error")
        }

        override fun onWebRtcAudioRecordError(error: String) {
            Log.e(TAG, "audio record runtime error: $error")
        }
    }

    private val audioTrackErrorCallback = object : JavaAudioDeviceModule.AudioTrackErrorCallback {
        override fun onWebRtcAudioTrackInitError(error: String) {
            Log.e(TAG, "audio playout init error: $error")
        }

        override fun onWebRtcAudioTrackStartError(
            errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
            error: String,
        ) {
            Log.e(TAG, "audio playout start error: code=$errorCode message=$error")
        }

        override fun onWebRtcAudioTrackError(error: String) {
            Log.e(TAG, "audio playout runtime error: $error")
        }
    }

    interface Listener {
        /** 需要经信令外发的消息（ice candidate 等），已按信令协议组好包 */
        fun onSignalOut(json: JSONObject)
        fun onRemoteVideo(track: VideoTrack)
        fun onRemoteVideoSize(width: Int, height: Int)
        fun onDataMessage(json: JSONObject)
        fun onLive()
        /** 网络往返延迟（毫秒），每 2 秒回调一次（实时性可视化用） */
        fun onRtt(ms: Long)
        fun onEnded(reason: String)
    }

    private val eglContext = App.instance.eglBase.eglBaseContext
    private val audioModule: JavaAudioDeviceModule =
        // 啸叫(尖锐声不断变大)=「扬声器→自己麦克风」的正反馈环，只有 AEC 能掐断，NS 挡不住。
        // 软件 AEC3 依赖系统上报准确的播放延迟，MIUI 上经常偏差过大 → 对齐失败 ≈ 没有 AEC。
        // 因此设备支持时改走系统 DSP 硬件回声消除（即"打电话不啸叫"的那条链路）；
        // AEC/NS 分别判定；不可用的一项由原生 APM 单独回退到软件实现。
        run {
            // 使用 libwebrtc 自己的兼容性判定：它会排除不能用于 VoIP 的 AOSP 占位实现。
            // AEC 与 NS 必须分别探测；任一不可用时 libwebrtc 会自动启用对应的软件处理。
            val hwAec = runCatching {
                JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            }.onFailure { Log.w(TAG, "hardware AEC probe failed; use software AEC", it) }
                .getOrDefault(false)
            val hwNs = runCatching {
                JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
            }.onFailure { Log.w(TAG, "hardware NS probe failed; use software NS", it) }
                .getOrDefault(false)
            Log.i(
                TAG,
                "audio config: sdk=${android.os.Build.VERSION.SDK_INT} " +
                    "device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} " +
                    "hardwareAEC=$hwAec hardwareNS=$hwNs",
            )
            JavaAudioDeviceModule.builder(context)
                .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(hwAec)
                .setUseHardwareNoiseSuppressor(hwNs)
                // 依赖默认每 30 秒记录一次系统音量；通话功能不需要，关闭可减少唤醒与日志 I/O。
                .setEnableVolumeLogger(false)
                .setAudioRecordErrorCallback(audioRecordErrorCallback)
                .setAudioTrackErrorCallback(audioTrackErrorCallback)
                .createAudioDeviceModule()
        }
    private val factory: PeerConnectionFactory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(audioModule)
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
        .createPeerConnectionFactory()

    private var pc: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var dataChannel: DataChannel? = null
    @Volatile private var ctlChannel: DataChannel? = null
    @Volatile private var ctlgChannel: DataChannel? = null
    private val qualityLock = Any()
    private var pendingQualityEdge: Int? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var closed = false
    private val rttHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var highRttStreak = 0
    private var lowRttStreak = 0
    private var statsPollCount = 0
    private var previousRemoteVideoLost = -1L
    private var previousRemoteVideoReceived = -1L
    private var previousOutboundVideoSent = -1L
    private val iceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val iceRestartBudget = IceRestartBudget()
    @Volatile private var established = false
    @Volatile private var latestIceState = PeerConnection.IceConnectionState.NEW.name

    /** 诊断上报用的当前 ICE 态（服务/Activity 侧报事件时带上，服务器才能对齐时间线） */
    val iceStateName: String get() = latestIceState
    @Volatile private var connectedSinceMs = 0L
    /**
     * 首次连通时刻（elapsedRealtime），会话内永不清零。
     * connectedSinceMs 会被 ICE 闪断清零，不能拿它算时长——上报必须用这个（见 SessionTiming）。
     */
    @Volatile private var mediaConnectedAtMs = 0L
    /** 连通之后最近一次失去连通的时刻；重新连通即清零，0 表示截至上报仍连着 */
    @Volatile private var mediaLostAtMs = 0L
    @Volatile private var outageStartedAtMs = 0L
    @Volatile private var recoveryCount = 0
    @Volatile private var connectStartedAtMs = 0L
    @Volatile private var renegoInFlight = false
    @Volatile private var restartRequested = false
    @Volatile private var endReason = ""
    private val role = if (isSharer) "sharer" else "viewer"
    private var viewerZeroFpsStreak = 0
    /** 对方锁屏=画面暂停、会话存续（v0.2.56）：零帧是预期行为，不能报成"卡死"告警 */
    @Volatile private var remoteScreenPaused = false

    /** screenoff/screenon 的唯一写入口：恢复侧顺手清 streak，否则解锁瞬间沿用旧计数误报 */
    fun setRemoteScreenPaused(paused: Boolean) {
        remoteScreenPaused = paused
        if (!paused) viewerZeroFpsStreak = 0
    }

    /**
     * 本端渲染面（surface）是否活着。观看端自己锁屏时 surface 被销毁、解码必然停帧，
     * 这与"对方断流"是两回事——0.2.55 双真机日志里每次 viewer_resumed 后 0.2s 内必报一条
     * video_stall，就是把本端锁屏误记成了黑屏故障，把 G 的真信号埋掉（v0.2.57）。
     */
    @Volatile private var localRenderPaused = false
    @Volatile private var resumedSurfaceAtMs = 0L

    /**
     * surfaceCreated/surfaceDestroyed 的唯一写入口（成对，不给漏配的机会）：
     * 恢复侧清 streak 并开一个"等第一帧"窗口，由轮询确认真的出帧后再报 video_resumed。
     */
    fun setLocalRenderPaused(paused: Boolean) {
        localRenderPaused = paused
        if (paused) {
            resumedSurfaceAtMs = 0L
        } else {
            viewerZeroFpsStreak = 0
            resumedSurfaceAtMs = System.currentTimeMillis()
        }
    }
    private val endedNotified = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var remoteDescriptionSet = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var videoSender: org.webrtc.RtpSender? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteVideoSizeSink: VideoSink? = null
    private var lastRemoteVideoWidth = 0
    private var lastRemoteVideoHeight = 0
    private var previousAudioQualityCounters: RtpQualityWindow.Counters? = null
    private var previousVideoQualityCounters: RtpQualityWindow.Counters? = null

    /**
     * 最近一个 10s 诊断窗口的读数与选路（v0.2.59）。
     * 这些数字此前只存在于 `S2S_QOS` 那行 logcat，而每个值都是轮询里的局部变量 ⇒
     * 对那台接不了 adb 的手机（米15）等于从来没算过。留住它们，会话结束时才能随
     * `diag.session` 的 path/proj 两列一起出去（服务器已经印了两天，一直是 `_`）。
     */
    @Volatile private var lastQos: com.soul2soul.app.util.DiagDetail.Qos? = null
    @Volatile private var lastSelectedPath = "unknown"
    /** 本会话是否已经出过至少一条 `qos_tick`（连上后那一条不等 10s 节奏，见轮询里的 firstTick） */
    @Volatile private var qosTickEmitted = false

    /** 采集侧状态：viewer 没有投影 ⇒ "-"，不许用 "off" 冒充"知道它没开" */
    @Volatile private var projectionState = "-"
    private val candidateTally = java.util.concurrent.atomic.AtomicIntegerArray(4) // host/srflx/relay/prflx
    @Volatile private var gatherReported = false

    private val connectTimeoutRunner = Runnable {
        if (!closed && !established) notifyEnded("connect_TIMEOUT")
    }
    private val disconnectTimeoutRunner = Runnable {
        if (!closed) {
            Log.w(
                TAG,
                "ICE_RECOVERY_TIMEOUT state=$latestIceState outageMs=" +
                    "${outageDurationMs()} restarts=${iceRestartBudget.usedAttempts}",
            )
            notifyEnded("ice_DISCONNECTED_TIMEOUT")
        }
    }
    private val iceStableRunner = Runnable {
        if (closed) return@Runnable
        val connectedSince = connectedSinceMs
        if (connectedSince <= 0L) return@Runnable
        val connectedFor = android.os.SystemClock.elapsedRealtime() - connectedSince
        if (iceRestartBudget.resetIfStable(connectedFor)) {
            Log.i(TAG, "ICE_RESTART_BUDGET_RESET stableMs=$connectedFor")
        }
    }

    /** 当前采集长边上限（观看端可通过 DataChannel 切 960/1280/1600/1920） */
    @Volatile
    var captureLongEdge = VideoQualityPolicy.DEFAULT_EDGE
        private set

    /** 当前传给 ScreenCapturerAndroid 的尺寸（用于真机尺寸诊断）。 */
    @Volatile
    var captureWidth: Int = 0
        private set

    @Volatile
    var captureHeight: Int = 0
        private set

    /** 用户意图档位（观看端高清/流畅设定）：弱网自动降档后，网络好转回升到这里 */
    @Volatile
    private var preferredLongEdge = VideoQualityPolicy.DEFAULT_EDGE

    /** 临时协助授权期间为 true：锁低档+保帧率，收回授权恢复（仅共享端会置位） */
    @Volatile
    private var controlMode = false
    // 只在 rttHandler 线程读写
    private var edgeBeforeControl = -1

    /** 静音本端上行音轨（只影响本会话，不像 AudioManager.isMicrophoneMute 那样全局关麦） */
    fun muteLocalAudio(muted: Boolean) {
        runCatching { audioTrack?.setEnabled(!muted) }
            .onFailure { Log.w(TAG, "muteLocalAudio failed", it) }
    }

    // ---------- 建立流程 ----------

    /** 共享端入口。projectionData 为系统屏幕录制授权返回的 Intent */
    fun startSharer(projectionData: Intent) {
        projectionState = "starting"
        val pc = createPeerConnection()
        this.pc = pc
        startConnectTimeout()

        val capturer: VideoCapturer = ScreenCapturerAndroid(
            projectionData,
            object : android.media.projection.MediaProjection.Callback() {
                // 用户在系统隐私提示里"停止"录屏：授权被撤销，会话必须体面结束而非黑屏挂着
                override fun onStop() {
                    projectionState = "stopped"
                    // 必须先 noteEnd 再交出去：ScreenShareService.stopSession 收尾会补一条
                    // local_stop，而 noteEnd 是 first-wins。不占位就会被"她按了停止"覆盖掉，
                    // 系统撤销授权与被按停止在服务器日志里就成同一个原因（0.2.60 生产日志实锤）。
                    // 只占这一格、不另发事件：同一件事发两条就是两个真值来源。
                    if (!closed) {
                        noteEnd("projection_stopped")
                        listener.onEnded("projection_stopped")
                    }
                }
            },
        )
        projectionState = "live"
        this.capturer = capturer
        val source = factory.createVideoSource(true) // isScreencast: 启用内容编码模式
        videoSource = source

        // 与无障碍手势、悬浮窗使用同一套整屏像素尺寸；不能用 app 视角
        // displayMetrics，否则全面屏的状态栏/导航栏会从采集源中被截掉。
        val (w, h) = captureSize()
        captureWidth = w
        captureHeight = h
        val helper = SurfaceTextureHelper.create("s2s-capture", eglContext)
        surfaceHelper = helper
        // 保留异步启动失败通知，但不再为每一帧做 AtomicLong 计数。
        val upstream = source.capturerObserver
        val observingCapturer = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                upstream.onCapturerStarted(success)
                com.soul2soul.app.util.SelfCheck.reportEvent(
                    context, "sharer",
                    if (success) "capture_ok" else "capture_fail",
                    latestIceState,
                    "${captureWidth}x${captureHeight}",
                )
                if (!success) notifyEnded("capture_START_FAILED")
            }

            override fun onCapturerStopped() = upstream.onCapturerStopped()

            override fun onFrameCaptured(frame: VideoFrame) = upstream.onFrameCaptured(frame)
        }
        capturer.initialize(helper, context, observingCapturer)
        capturer.startCapture(w, h, CAPTURE_FPS)

        val videoTrack = factory.createVideoTrack("v0", source)
        videoSender = pc.addTrack(videoTrack, listOf(STREAM))?.also { applyBitrate(it) }

        // 语音（双向通话的两端都发麦克风）
        if (hasAudioPermission()) {
            val audio = factory.createAudioSource(MediaConstraints())
            audioSource = audio
            val track: AudioTrack = factory.createAudioTrack("a0", audio)
            audioTrack = track
            pc.addTrack(track, listOf(STREAM))
        }

        // 授权/拒绝/错误等控制信令：可靠有序（"允许""收回"乱序 = 隐私事故，必须留 ctl）
        // 注意创建顺序：0.2.8 观看端 onDataChannel 是后到者覆盖；ctl、ctlg 先建、anno 最后建，
        // 保证旧观看端最终持有 anno（丢包容忍），笔迹不会被可靠通道的队头阻塞拖死
        val ctlInit = DataChannel.Init().apply { ordered = true }
        ctlChannel = pc.createDataChannel("ctl", ctlInit)?.also {
            it.registerObserver(this)
        }
        // ctlg：手势专用快道。unordered + 全可靠——整笔一条消息自包含，
        // 乱序无所谓（点独立），但绝不能丢（丢一笔=漏点），更不能被 ctl 有序队头阻塞。
        // 必须建在 anno 之前：旧观看端 onDataChannel 后到者覆盖，anno 最后建才保得住。
        val ctlgInit = DataChannel.Init().apply { ordered = false }
        ctlgChannel = pc.createDataChannel("ctlg", ctlgInit)?.also {
            it.registerObserver(this)
        }
        // 共享端是 offerer，负责创建 DataChannel
        // 笔迹是激光笔范式：容忍丢包、不要队头阻塞（reliable-ordered 在弱网下会堵死整队笔迹）
        val dcInit = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 1
        }
        dataChannel = pc.createDataChannel("anno", dcInit)?.also {
            it.registerObserver(this)
        }

        pc.createOffer(object : SdpAdapter("createOffer", ::onSdpFailure) {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter("setLocalOffer", ::onSdpFailure) {
                    override fun onSetSuccess() = sendSdp(desc)
                }, desc)
            }
        }, MediaConstraints())
    }

    /** 观看端入口：只建 pc + 麦克风，等 offer */
    fun startViewer() {
        val pc = createPeerConnection()
        this.pc = pc
        startConnectTimeout()

        if (hasAudioPermission()) {
            val audio = factory.createAudioSource(MediaConstraints())
            audioSource = audio
            val track = factory.createAudioTrack("a1", audio)
            audioTrack = track
            pc.addTrack(track, listOf(STREAM))
        }
    }

    /** 观看端切清晰度（用户意图）：记录档位并立即重启采集 + 调整码率上限 */
    fun setCaptureLongEdge(edge: Int) {
        val normalized = VideoQualityPolicy.normalizeEdge(edge)
        if (controlMode) {
            // 操控授权期间锁在跟手档：远端点画质只记下意图，收回授权退出时再生效，
            // 否则一声"高清"点击就把刚省出来的码率又吃回去
            edgeBeforeControl = normalized
            return
        }
        preferredLongEdge = normalized
        applyCaptureLongEdge(preferredLongEdge)
    }

    /**
     * 临时协助授权开/关（仅共享端调用；consent 状态变化时同步）。
     * 开启：降到低档采集 + 降级偏好改保帧率；关闭：恢复用户档位与保分辨率。
     * 操控要的是跟手不是高清。
     */
    fun setControlMode(on: Boolean) {
        rttHandler.post {
            if (closed || controlMode == on) return@post
            controlMode = on
            if (on) {
                edgeBeforeControl = preferredLongEdge
                val target = VideoQualityPolicy.controlEnterEdge(preferredLongEdge)
                if (target != null) {
                    preferredLongEdge = target // 操控期间自适应回升目标也钉在低档
                    applyCaptureLongEdge(target)
                }
            } else {
                val restore = if (edgeBeforeControl > 0) edgeBeforeControl else preferredLongEdge
                preferredLongEdge = restore
                applyCaptureLongEdge(restore)
                edgeBeforeControl = -1
            }
            videoSender?.let { applyBitrate(it) } // 立即切换保分辨率/保帧率偏好
            Log.d(TAG, "control mode video profile: on=$on edge=$captureLongEdge")
        }
    }

    private fun applyCaptureLongEdge(edge: Int) {
        val normalized = VideoQualityPolicy.normalizeEdge(edge)
        val capturer = capturer ?: return
        val (w, h) = captureSize(normalized)
        if (captureLongEdge == normalized && captureWidth == w && captureHeight == h) {
            videoSender?.let { applyBitrate(it) }
            return
        }
        val oldEdge = captureLongEdge
        val oldWidth = captureWidth
        val oldHeight = captureHeight
        captureWidth = w
        captureHeight = h
        val switched = runCatching {
            capturer.stopCapture()
            capturer.startCapture(w, h, CAPTURE_FPS)
        }.isSuccess
        if (switched) {
            captureLongEdge = normalized
        } else {
            Log.w(TAG, "resolution switch failed; restoring ${oldWidth}x${oldHeight}")
            captureLongEdge = oldEdge
            captureWidth = oldWidth
            captureHeight = oldHeight
            if (oldWidth > 0 && oldHeight > 0) {
                runCatching { capturer.startCapture(oldWidth, oldHeight, CAPTURE_FPS) }
                    .onFailure { Log.w(TAG, "resolution rollback failed", it) }
            }
        }
        videoSender?.let { applyBitrate(it) }
    }

    /**
     * 计算屏幕采集尺寸：长边不超过档位、短边按原始比例缩放，并偶数对齐。
     * ScreenCapturerAndroid/编码器对奇数尺寸支持不一致，因此最后至少保留 2px。
     */
    private fun captureSize(edge: Int = captureLongEdge): Pair<Int, Int> {
        val (sw, sh) = com.soul2soul.app.util.ScreenSize.real(context)
        val scale = minOf(1f, edge.toFloat() / maxOf(sw, sh).toFloat())
        val w = ((sw * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((sh * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return w to h
    }

    private fun applyBitrate(sender: org.webrtc.RtpSender) {
        runCatching {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                // 平时屏幕文字比帧率更敏感：优先保分辨率，网络紧张时由 WebRTC 降帧；
                // 操控授权期间反过来保帧率（跟手优先，画质临时让路）
                params.degradationPreference = if (controlMode)
                    RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                else
                    RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                params.encodings[0].maxBitrateBps =
                    VideoQualityPolicy.requiredBitrateBps(captureLongEdge).toInt()
                params.encodings[0].maxFramerate = CAPTURE_FPS
                sender.parameters = params
            }
        }.onFailure { Log.w(TAG, "bitrate set failed", it) }
    }

    /** 屏幕内容编码优化：将 offer 中 VP9 的载荷提到最前（同码率下 UI/文字清晰度显著优于 H.264） */
    private fun preferVp9(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mIdx = lines.indexOfFirst { it.startsWith("m=video") }
        if (mIdx < 0) return sdp
        val vp9Pts = lines.filter { it.startsWith("a=rtpmap:") && it.contains("VP9/") }
            .map { it.removePrefix("a=rtpmap:").substringBefore(" ") }
        if (vp9Pts.isEmpty()) return sdp
        val rtxPts = lines.filter { it.startsWith("a=fmtp:") && it.contains("apt=") }
            .filter { fm -> vp9Pts.any { fm.contains("apt=$it ") || fm.endsWith("apt=$it") } }
            .map { it.removePrefix("a=fmtp:").substringBefore(" ") }
        val preferred = (vp9Pts + rtxPts).toSet()
        val parts = lines[mIdx].split(" ")
        if (parts.size <= 3) return sdp
        val pts = parts.drop(3)
        val ordered = pts.filter { preferred.contains(it) } + pts.filterNot { preferred.contains(it) }
        lines[mIdx] = (parts.take(3) + ordered).joinToString(" ")
        // join 是 split 的严格逆操作：完整保留原行尾结构（含末尾 CRLF），
        // 否则多出的空行会被对端 libwebrtc 判为 "Invalid SDP line" 整份拒收
        return lines.joinToString("\r\n")
    }

    /** 切清晰度（线程安全入口：DataChannel 回调线程 → 主线程执行采集重启） */
    fun setCaptureLongEdgeOnMain(edge: Int) {
        rttHandler.post { setCaptureLongEdge(edge) }
    }

    /** 信令收到 sdp：观看端应答 offer；共享端收到 answer */
    fun onRemoteSdp(sdpJson: JSONObject) {
        val pc = pc ?: return
        if (closed || endedNotified.get()) return
        val type = sdpJson.optString("type")
        val sdpText = sdpJson.optString("sdp")
        Log.d(TAG, "remote sdp: type=$type len=${sdpText.length}")
        // 兼容旧版(0.2.5 preferVp9 末尾多空行)发来的 offer：规整为单一结尾 CRLF，
        // 否则对端 libwebrtc 严格解析器会整份拒收("Invalid SDP line")
        val remote = try {
            SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sdpText.trimEnd('\r', '\n') + "\r\n",
            )
        } catch (e: Exception) {
            Log.w(TAG, "bad remote sdp", e)
            notifyEnded("sdp_INVALID")
            return
        }
        synchronized(pendingRemoteIce) {
            remoteDescriptionSet = false
        }
        pc.setRemoteDescription(object : SdpAdapter("setRemote", ::onSdpFailure) {
            override fun onSetSuccess() {
                flushPendingRemoteIce(pc)
                if (type == "offer") {
                    pc.createAnswer(object : SdpAdapter("createAnswer", ::onSdpFailure) {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            pc.setLocalDescription(object : SdpAdapter("setLocalAnswer", ::onSdpFailure) {
                                override fun onSetSuccess() = sendSdp(answer)
                            }, answer)
                        }
                    }, MediaConstraints())
                }
            }
        }, remote)
    }

    fun onRemoteIce(candidateJson: JSONObject) {
        if (closed || endedNotified.get()) return
        val candidate = try {
            IceCandidate(
                candidateJson.optString("sdpMid"),
                candidateJson.optInt("sdpMLineIndex"),
                candidateJson.getString("candidate"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "bad remote ICE candidate", e)
            return
        }
        val peer = pc ?: return
        synchronized(pendingRemoteIce) {
            if (!remoteDescriptionSet) {
                pendingRemoteIce += candidate
                return
            }
        }
        peer.addIceCandidate(candidate)
    }

    /** addIceCandidate 在 setRemoteDescription 完成前会失败；按 WebRTC 规范顺序缓存并补交。 */
    private fun flushPendingRemoteIce(peer: PeerConnection) {
        val queued = synchronized(pendingRemoteIce) {
            remoteDescriptionSet = true
            pendingRemoteIce.toList().also { pendingRemoteIce.clear() }
        }
        queued.forEach { peer.addIceCandidate(it) }
    }

    // ---------- 标注 DataChannel ----------

    fun sendAnnotation(json: JSONObject) {
        val dc = dataChannel ?: run { Log.w(TAG, "sendAnnotation: channel null"); return }
        val state = runCatching { dc.state() }.getOrNull()
        if (state != DataChannel.State.OPEN) {
            Log.w(TAG, "annotation dropped: channel state=$state k=${json.opt("k")}")
            return
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val ok = runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) }
            .onFailure { Log.w(TAG, "annotation send failed k=${json.opt("k")}", it) }
            .getOrDefault(false)
        if (!ok) Log.w(TAG, "annotation send rejected k=${json.opt("k")}")
    }

    /** 操控能力握手即通道本身：只有新版共享端会创建 ctl。旧版对端 = null，观看端据此禁用操控 */
    val controlSupported: Boolean get() = ctlChannel != null

    /** 远程操控手势出口（观看端 → 共享端）；ctl 通道未建时退回 anno 通道保兼容 */
    fun sendControl(json: JSONObject) {
        sendControlNow(json)
    }

    /** 画质请求允许在 DataChannel 尚未 OPEN 时排队，通道打开后只发送最新档位。 */
    fun sendQuality(edge: Int) {
        synchronized(qualityLock) {
            pendingQualityEdge = VideoQualityPolicy.normalizeEdge(edge)
            flushPendingQualityLocked()
        }
    }

    private fun flushPendingQuality() {
        synchronized(qualityLock) { flushPendingQualityLocked() }
    }

    private fun flushPendingQualityLocked() {
        val edge = pendingQualityEdge ?: return
        val sent = sendControlNow(JSONObject().put("k", "res").put("edge", edge))
        if (sent) pendingQualityEdge = null
    }

    private fun channelByLabel(label: String): DataChannel? = when (label) {
        CtlChannelPolicy.CTLG -> ctlgChannel
        CtlChannelPolicy.CTL -> ctlChannel
        else -> dataChannel
    }

    private fun sendControlNow(json: JSONObject): Boolean {
        // 手势走 ctlg 快道（unordered 全可靠），其余信令保持 ctl 有序；旧对端无 ctlg 自动回退
        val dc = CtlChannelPolicy.channelOrder(json.optString("k"))
            .asSequence()
            .mapNotNull { channelByLabel(it) }
            .firstOrNull { channel ->
                runCatching { channel.state() == DataChannel.State.OPEN }.getOrDefault(false)
            } ?: run {
            Log.w(TAG, "sendControl: no channel"); return false
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val ok = runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) }
            .onFailure { Log.w(TAG, "control send failed k=${json.opt("k")}", it) }
            .getOrDefault(false)
        if (!ok) Log.w(TAG, "control send rejected k=${json.opt("k")}")
        return ok
    }

    // ---------- PeerConnection.Observer ----------

    override fun onIceCandidate(candidate: IceCandidate) {
        if (closed || endedNotified.get()) return
        // 类型计数就在已有的候选回调里做（不额外 getStats）：TURN 分配失败 = relay 恒 0，
        // 这条判据此前完全不存在，"是不是必须走中继"只能靠猜
        com.soul2soul.app.util.DiagDetail.candidateIndex(candidate.sdp)
            .takeIf { it >= 0 }
            ?.let { candidateTally.incrementAndGet(it) }
        listener.onSignalOut(
            JSONObject()
                .put("type", "ice")
                .put(
                    "candidate",
                    JSONObject()
                        .put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex),
                )
        )
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        val previousState = latestIceState
        latestIceState = state.name
        Log.d(TAG, "ice state: $previousState->$state")
        if (endedNotified.get()) return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                val now = android.os.SystemClock.elapsedRealtime()
                val firstConnection = !established
                val wasConnected = previousState == PeerConnection.IceConnectionState.CONNECTED.name ||
                    previousState == PeerConnection.IceConnectionState.COMPLETED.name
                established = true
                iceHandler.removeCallbacks(connectTimeoutRunner)
                iceHandler.removeCallbacks(disconnectTimeoutRunner)
                iceHandler.removeCallbacks(iceRestartRunner)
                restartRequested = false
                renegoInFlight = false
                if (!wasConnected) {
                    connectedSinceMs = now
                    if (mediaConnectedAtMs == 0L) mediaConnectedAtMs = now
                    mediaLostAtMs = 0L
                    iceHandler.removeCallbacks(iceStableRunner)
                    iceHandler.postDelayed(iceStableRunner, IceRestartBudget.STABLE_RESET_MS)
                }
                if (outageStartedAtMs > 0L) {
                    recoveryCount += 1
                    Log.i(
                        TAG,
                        "ICE_RECOVERED count=$recoveryCount outageMs=${now - outageStartedAtMs} " +
                            "restarts=${iceRestartBudget.usedAttempts}",
                    )
                    com.soul2soul.app.util.SelfCheck.reportEvent(
                        context, role, "ice_recovered", state.name,
                        "outageMs=${now - outageStartedAtMs} restarts=${iceRestartBudget.usedAttempts}",
                    )
                    outageStartedAtMs = 0L
                }
                if (firstConnection) {
                    val setupMs = if (connectStartedAtMs > 0L) now - connectStartedAtMs else -1L
                    Log.i(TAG, "ICE_ESTABLISHED setupMs=$setupMs")
                    listener.onLive()
                    startRttPolling()
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                // 闪断恢复器：短暂断开先自动重启 ICE 尝试原地复活，
                // 而不是等 FAILED 把整场会话判死（微信式韧性）
                if (isSharer) {
                    iceHandler.removeCallbacks(iceRestartRunner)
                    iceHandler.postDelayed(iceRestartRunner, ICE_RESTART_AFTER_MS)
                }
                iceHandler.removeCallbacks(iceStableRunner)
                connectedSinceMs = 0L
                if (mediaConnectedAtMs > 0L && mediaLostAtMs == 0L) {
                    mediaLostAtMs = android.os.SystemClock.elapsedRealtime()
                }
                if (outageStartedAtMs == 0L) {
                    outageStartedAtMs = android.os.SystemClock.elapsedRealtime()
                    Log.w(TAG, "ICE_OUTAGE_STARTED state=$state")
                    com.soul2soul.app.util.SelfCheck.reportEvent(
                        context, role, "ice_outage", state.name,
                        "established=$established restarts=${iceRestartBudget.usedAttempts}",
                    )
                }
                iceHandler.removeCallbacks(disconnectTimeoutRunner)
                iceHandler.postDelayed(disconnectTimeoutRunner, DISCONNECT_TIMEOUT_MS)
            }
            PeerConnection.IceConnectionState.FAILED -> {
                iceHandler.removeCallbacks(iceRestartRunner)
                iceHandler.removeCallbacks(iceStableRunner)
                connectedSinceMs = 0L
                if (mediaConnectedAtMs > 0L && mediaLostAtMs == 0L) {
                    mediaLostAtMs = android.os.SystemClock.elapsedRealtime()
                }
                if (outageStartedAtMs == 0L) {
                    outageStartedAtMs = android.os.SystemClock.elapsedRealtime()
                    Log.w(TAG, "ICE_OUTAGE_STARTED state=$state")
                }
                // DISCONNECTED/CONNECTED 都有自报，FAILED 此前偏偏只打 logcat：
                // "真断"（打洞彻底失败/中继也没拿到）与"闪断"（后面会 ice_recovered）在服务器日志里因此分不开
                com.soul2soul.app.util.SelfCheck.reportEvent(
                    context, role, "ice_failed", state.name,
                    "established=$established restarts=${iceRestartBudget.usedAttempts} " +
                        "path=${com.soul2soul.app.util.DiagDetail.pathCode(lastSelectedPath)}",
                )
                val recovery = IceRecoveryPolicy.onFailed(established, isSharer)
                if (recovery.armRecoveryTimeout) {
                    iceHandler.removeCallbacks(disconnectTimeoutRunner)
                    iceHandler.postDelayed(disconnectTimeoutRunner, DISCONNECT_TIMEOUT_MS)
                }
                if (recovery.restartNow) iceHandler.post(iceRestartRunner)
            }
            PeerConnection.IceConnectionState.CLOSED -> if (!closed) {
                iceHandler.removeCallbacks(iceRestartRunner)
                iceHandler.removeCallbacks(iceStableRunner)
                notifyEnded("ice_CLOSED")
            }
            else -> Unit
        }
    }

    private val iceRestartRunner = Runnable { runIceRestart() }

    private fun runIceRestart() {
        if (closed || endedNotified.get()) return
        if (latestIceState == PeerConnection.IceConnectionState.CONNECTED.name ||
            latestIceState == PeerConnection.IceConnectionState.COMPLETED.name
        ) return
        if (renegoInFlight || restartRequested) {
            // Don't silently lose the one recovery request if another SDP operation is still settling.
            iceHandler.postDelayed(iceRestartRunner, ICE_RESTART_RETRY_MS)
            return
        }
        val pc = pc ?: return
        val attempt = iceRestartBudget.reserveAttempt()
        if (attempt == null) {
            Log.w(TAG, "ICE_RESTART_BUDGET_EXHAUSTED outageMs=${outageDurationMs()}")
            return
        }
        Log.i(TAG, "ICE_RESTART attempt=$attempt state=$latestIceState")
        restartRequested = true
        runCatching { pc.restartIce() }
            .onFailure {
                restartRequested = false
                iceRestartBudget.releaseAttempt(attempt)
                Log.w(TAG, "restartIce failed; retrying without consuming recovery budget", it)
                iceHandler.removeCallbacks(iceRestartRunner)
                iceHandler.postDelayed(iceRestartRunner, ICE_RESTART_RETRY_MS)
            }
    }

    /** 每 2 秒取 RTT 做实时显示/自适应，详细音视频统计每 10 秒记录一次。 */
    private fun startRttPolling() {
        previousRemoteVideoLost = -1L
        previousRemoteVideoReceived = -1L
        previousOutboundVideoSent = -1L
        rttHandler.post(object : Runnable {
            override fun run() {
                val pc = pc ?: return
                pc.getStats { report ->
                    if (closed) return@getStats
                    var best = -1L
                    var availableOutgoingBps = -1L
                    var selectedPairId: String? = null
                    var selectedPath = "unknown"
                    var selectedRtt = -1L
                    var selectedAvailable = -1L
                    var fallbackRtt = -1L
                    var fallbackAvailable = -1L
                    var remoteVideoLost = -1L
                    var remoteVideoReceived = -1L
                    var outboundVideoSent = -1L
                    var outboundVideoFps = -1.0
                    var inboundVideoFps = -1.0
                    var inboundAudioQuality: RtpQualityWindow.Counters? = null
                    var inboundVideoQuality: RtpQualityWindow.Counters? = null
                    // Prefer the pair selected by transport. Older stats reports
                    // may not expose transport.selectedCandidatePairId; in that
                    // case use a nominated pair, then fall back to the best RTT.
                    for ((_, stats) in report.statsMap) {
                        if (stats.type == "transport") {
                            selectedPairId = stats.members["selectedCandidatePairId"]?.toString()
                            if (selectedPairId != null) break
                        }
                    }
                    for ((id, stats) in report.statsMap) {
                        if (stats.type == "candidate-pair" &&
                            stats.members["state"] == "succeeded"
                        ) {
                            val rtt = (stats.members["currentRoundTripTime"] as? Number)?.toDouble()
                            val ms = if (rtt != null && rtt >= 0) (rtt * 1000).toLong() else -1L
                            val available =
                                (stats.members["availableOutgoingBitrate"] as? Number)?.toLong() ?: -1L
                            val selected = id == selectedPairId ||
                                (selectedPairId == null && stats.members["nominated"] == true)
                            if (selected) {
                                if (ms >= 0 && (selectedRtt < 0 || ms < selectedRtt)) selectedRtt = ms
                                if (available >= 0) selectedAvailable = available
                                val localId = stats.members["localCandidateId"]?.toString()
                                val remoteId = stats.members["remoteCandidateId"]?.toString()
                                val localType = localId?.let { report.statsMap[it]?.members?.get("candidateType") }
                                    ?.toString() ?: "?"
                                val remoteType = remoteId?.let { report.statsMap[it]?.members?.get("candidateType") }
                                    ?.toString() ?: "?"
                                selectedPath = "$localType->$remoteType"
                            }
                            if (rtt != null && rtt >= 0) {
                                if (fallbackRtt < 0 || ms < fallbackRtt) fallbackRtt = ms
                            }
                            if (available > fallbackAvailable) fallbackAvailable = available
                        }
                        val mediaKind = stats.members["kind"]?.toString()
                            ?: stats.members["mediaType"]?.toString()
                        if (isSharer && stats.type == "remote-inbound-rtp" && mediaKind == "video") {
                            remoteVideoLost =
                                (stats.members["packetsLost"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: -1L
                            remoteVideoReceived =
                                (stats.members["packetsReceived"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: -1L
                            inboundVideoQuality = RtpQualityWindow.Counters(
                                packetsLost = (stats.members["packetsLost"] as? Number)?.toLong() ?: -1L,
                                packetsReceived = (stats.members["packetsReceived"] as? Number)?.toLong() ?: -1L,
                                jitterSeconds = (stats.members["jitter"] as? Number)?.toDouble() ?: -1.0,
                            )
                        }
                        if (isSharer && stats.type == "outbound-rtp" && mediaKind == "video") {
                            outboundVideoSent =
                                (stats.members["packetsSent"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: -1L
                            outboundVideoFps =
                                (stats.members["framesPerSecond"] as? Number)?.toDouble() ?: -1.0
                        }
                        if (!isSharer && stats.type == "inbound-rtp" && mediaKind == "video") {
                            inboundVideoFps =
                                (stats.members["framesPerSecond"] as? Number)?.toDouble() ?: -1.0
                            inboundVideoQuality = RtpQualityWindow.Counters(
                                packetsLost = (stats.members["packetsLost"] as? Number)?.toLong() ?: -1L,
                                packetsReceived = (stats.members["packetsReceived"] as? Number)?.toLong() ?: -1L,
                                jitterSeconds = (stats.members["jitter"] as? Number)?.toDouble() ?: -1.0,
                            )
                        }
                        if (stats.type == "inbound-rtp" && mediaKind == "audio") {
                            inboundAudioQuality = RtpQualityWindow.Counters(
                                packetsLost = (stats.members["packetsLost"] as? Number)?.toLong() ?: -1L,
                                packetsReceived = (stats.members["packetsReceived"] as? Number)?.toLong() ?: -1L,
                                jitterSeconds = (stats.members["jitter"] as? Number)?.toDouble() ?: -1.0,
                                concealedSamples =
                                    (stats.members["concealedSamples"] as? Number)?.toLong() ?: -1L,
                            )
                        }
                    }
                    best = if (selectedRtt >= 0) selectedRtt else fallbackRtt
                    availableOutgoingBps =
                        if (selectedAvailable >= 0) selectedAvailable else fallbackAvailable
                    // 只在读到"真的有选中的 candidate-pair"时才覆盖。断会之后 stats 里没有 succeeded 的对，
                    // selectedPath 退回 "unknown"，若无脑覆盖，短会话（<10s，一条例行 tick 都来不及出）
                    // 的 path 就永远读不出来——而"P2P 还是走中继"正是这一列存在的唯一理由。
                    // "?->?"（选中了对但候选查不到）同样不覆盖：那是采集缺陷，不是新事实。
                    if (selectedPath.contains("->") && !selectedPath.startsWith("?")) {
                        lastSelectedPath = selectedPath
                    }
                    if (best >= 0) listener.onRtt(best)

                    // 统计仍每 2 秒用于自适应；详细媒体诊断每 10 秒记录一次。
                    // 但"连上之后的第一条"不等 10 秒：她的典型故障是连上几秒就散，
                    // 按 10s 节奏那条永远出不来 ⇒ 短会话零读数，等于又白埋一次（v0.2.62）。
                    statsPollCount += 1
                    val diagDue = statsPollCount % DIAGNOSTIC_EVERY_POLLS == 0
                    val firstTick = established && !qosTickEmitted
                    if (diagDue || firstTick) {
                        // Keep codec diagnostics, but report network quality as interval deltas:
                        // cumulative counters hide whether the current 10s window was actually bad.
                        for ((_, stats) in report.statsMap) {
                            val mediaKind = stats.members["kind"]?.toString()
                                ?: stats.members["mediaType"]?.toString()
                            when {
                                stats.type == "outbound-rtp" && mediaKind == "video" -> {
                                    val impl = stats.members["encoderImplementation"]?.toString() ?: "?"
                                    val fps = (stats.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                                    val sent = (stats.members["bytesSent"] as? Number)?.toLong() ?: 0L
                                    // avgEncodeMs 高 / limitationReason=cpu → 软编瓶颈；
                                    // =bandwidth → 带宽瓶颈（操控模式低清档正是在解这个）
                                    val encodeMs =
                                        (stats.members["avgEncodeMs"] as? Number)?.toDouble() ?: -1.0
                                    val limitation =
                                        stats.members["qualityLimitationReason"]?.toString() ?: "?"
                                    Log.d(
                                        TAG,
                                        "ENC impl=$impl fps=${formatMetric(fps)} " +
                                            "avgEncodeMs=${formatMetric(encodeMs)} " +
                                            "limitation=$limitation bytes=$sent",
                                    )
                                }
                                !isSharer && stats.type == "inbound-rtp" && mediaKind == "video" -> {
                                    val impl = stats.members["decoderImplementation"]?.toString() ?: "?"
                                    Log.d(TAG, "DEC impl=$impl fps=${formatMetric(inboundVideoFps)}")
                                }
                            }
                        }
                        val audioWindow = RtpQualityWindow.between(
                            previousAudioQualityCounters,
                            inboundAudioQuality,
                        )
                        val videoWindow = RtpQualityWindow.between(
                            previousVideoQualityCounters,
                            inboundVideoQuality,
                        )
                        previousAudioQualityCounters = inboundAudioQuality
                        previousVideoQualityCounters = inboundVideoQuality
                        val videoFps = if (isSharer) outboundVideoFps else inboundVideoFps
                        Log.i(
                            TAG,
                            "S2S_QOS role=${if (isSharer) "sharer" else "viewer"} " +
                                "ice=$latestIceState rttMs=$best path=$selectedPath " +
                                "availableBps=$availableOutgoingBps edge=$captureLongEdge " +
                                "videoLossPct=${formatMetric(videoWindow.packetLossPercent)} " +
                                "videoJitterMs=${formatMetric(videoWindow.jitterMs)} " +
                                "videoFps=${formatMetric(videoFps)} " +
                                "audioLossPct=${formatMetric(audioWindow.packetLossPercent)} " +
                                "audioJitterMs=${formatMetric(audioWindow.jitterMs)} " +
                                "audioConcealedMs=${formatMetric(audioWindow.concealedMs)}",
                        )
                        // 同一批读数必须离开 logcat：这行 S2S_QOS 的每个值都是上面那些每 tick 局部变量，
                        // 出循环即销毁，而唯一出口是 listener.onRtt(best) ⇒ 米15（无 adb）等于从没算过。
                        // 每 10s 一条 qos_tick 是背景，异常当场插一条 qos_bad 才是现场。
                        val sample = com.soul2soul.app.util.DiagDetail.Qos(
                            rttMs = best,
                            path = selectedPath,
                            availableKbps =
                                if (availableOutgoingBps >= 0) availableOutgoingBps / 1000 else -1L,
                            edge = captureLongEdge.toLong(),
                            videoLossPct = videoWindow.packetLossPercent,
                            videoJitterMs = videoWindow.jitterMs,
                            videoFps = videoFps,
                            audioLossPct = audioWindow.packetLossPercent,
                            audioJitterMs = audioWindow.jitterMs,
                            audioConcealedMs = audioWindow.concealedMs,
                        )
                        val prevQos = lastQos
                        lastQos = sample
                        if (established) {
                            qosTickEmitted = true
                            com.soul2soul.app.util.SelfCheck.reportEvent(
                                context, role, "qos_tick", latestIceState,
                                com.soul2soul.app.util.DiagDetail.qos(sample),
                            )
                            // 提前出来的那一条（prev 还没有）只当背景，不跑异常判据：
                            // QosAnomaly 的第一条规则是 `videoFps == 0.0 → b=fps0`，而刚连上的
                            // 头一两秒收流帧率本来就可能真是 0——那正是 0.2.57 花力气摘掉的
                            // "假卡顿告警"这一类（docs/AUDIT-MATRIX.md G 段）。
                            if (diagDue || prevQos != null) QosAnomaly.pick(
                                prev = prevQos, cur = sample,
                                // 本机锁屏期间采集不出帧是 0.2.56 的设计语义（画面暂停·语音继续），
                                // 本端 surface 没了停帧也是自己的事——两种都不许报成"卡死"
                                framesBlockedByUs = localRenderPaused || !localScreenOn(),
                                peerPaused = remoteScreenPaused,
                            )?.let { bad ->
                                com.soul2soul.app.util.SelfCheck.reportEvent(
                                    context, role, "qos_bad", latestIceState, bad,
                                )
                            }
                        }
                        // 观看端"黑屏"的直接证据：ICE 仍 CONNECTED 但收流帧率连续为 0，
                        // 说明断的是共享端的采集/编码，不是网络（服务器看不见媒体，只能自报）。
                        // 对方锁屏期间零帧是设计行为（画面暂停·语音继续），排除在外。
                        // `diagDue`：连续计数只有在完整 10s 节奏上才有判据力，
                        // 连上后补的那一条不许把它提前一格（否则 streak 会提早一格变成告警）。
                        if (diagDue && !isSharer && established && !remoteScreenPaused) {
                            if (localRenderPaused) {
                                // 本端 surface 没了，停帧是自己造成的，与对端无关
                                viewerZeroFpsStreak = 0
                            } else if (inboundVideoFps <= 0.0) {
                                viewerZeroFpsStreak++
                                if (viewerZeroFpsStreak == VIEWER_STALL_POLLS) {
                                    com.soul2soul.app.util.SelfCheck.reportEvent(
                                        context, "viewer", "video_stall", latestIceState,
                                        "rttMs=$best path=$selectedPath",
                                    )
                                }
                            } else {
                                val since = resumedSurfaceAtMs
                                if (since > 0L) {
                                    resumedSurfaceAtMs = 0L
                                    // G 的判定证据：surface 重建后到底有没有帧回来。
                                    // 只有 video_stall 没有它，就分不清"流停了"和"渲染链没重挂"。
                                    com.soul2soul.app.util.SelfCheck.reportEvent(
                                        context, "viewer", "video_resumed", latestIceState,
                                        "afterMs=${System.currentTimeMillis() - since}",
                                    )
                                }
                                viewerZeroFpsStreak = 0
                            }
                        }
                    }

                    // 共享端按 RTT + 远端丢包 + 可用上行带宽自动调画质。
                    // 持续高延迟/丢包时逐档降级，恢复稳定后回到用户选择档位。
                    if (isSharer && best >= 0) {
                        // 丢包计数是累计值，使用相邻轮询的增量；没有
                        // packetsReceived 时由 outbound packetsSent 增量兜底。
                        val lossPercent = VideoQualityPolicy.intervalLossPercent(
                            previousRemoteVideoLost,
                            previousRemoteVideoReceived,
                            remoteVideoLost,
                            remoteVideoReceived,
                            previousOutboundVideoSent,
                            outboundVideoSent,
                        )
                        if (remoteVideoLost >= 0) previousRemoteVideoLost = remoteVideoLost
                        if (remoteVideoReceived >= 0) previousRemoteVideoReceived = remoteVideoReceived
                        if (outboundVideoSent >= 0) previousOutboundVideoSent = outboundVideoSent
                        val decision = VideoQualityPolicy.step(
                            preferredLongEdge,
                            captureLongEdge,
                            VideoQualityPolicy.Metrics(
                                best,
                                lossPercent,
                                availableOutgoingBps,
                                outboundVideoFps,
                            ),
                            highRttStreak,
                            lowRttStreak,
                        )
                        highRttStreak = decision.highStreak
                        lowRttStreak = decision.lowStreak
                        decision.targetEdge?.let { target ->
                            Log.d(TAG, "adaptive video quality rtt=${best}ms " +
                                "loss=${"%.1f".format(lossPercent)}% available=${availableOutgoingBps} " +
                                "edge=${captureLongEdge}->${target}")
                            rttHandler.post { if (!closed) applyCaptureLongEdge(target) }
                            // 切档后重新积累连续样本，避免同一份坏指标在下一次轮询立即连降两档。
                            highRttStreak = 0
                            lowRttStreak = 0
                        }
                    }
                }
                rttHandler.postDelayed(this, RTT_POLL_MS)
            }
        })
    }

    private fun stopRttPolling() {
        rttHandler.removeCallbacksAndMessages(null)
    }

    private fun formatMetric(value: Double): String =
        if (value.isFinite() && value >= 0.0) {
            String.format(java.util.Locale.US, "%.1f", value)
        } else {
            "na"
        }

    override fun onDataChannel(dc: DataChannel) {
        // 观看端：channel 均由共享端创建，按 label 分流
        when (dc.label()) {
            "ctl" -> {
                runCatching { ctlChannel?.unregisterObserver() }
                ctlChannel = dc
            }
            "ctlg" -> {
                runCatching { ctlgChannel?.unregisterObserver() }
                ctlgChannel = dc
            }
            else -> {
                runCatching { dataChannel?.unregisterObserver() }
                dataChannel = dc
            }
        }
        dc.registerObserver(this)
        // 对端可能在注册 observer 前就已进入 OPEN；不能只依赖后续状态回调。
        flushPendingQuality()
    }

    override fun onTrack(transceiver: RtpTransceiver) {
        val track = transceiver.receiver.track()
        if (track is VideoTrack) {
            if (remoteVideoTrack === track) return
            remoteVideoSizeSink?.let { sink -> runCatching { remoteVideoTrack?.removeSink(sink) } }
            remoteVideoTrack = track
            lastRemoteVideoWidth = 0
            lastRemoteVideoHeight = 0
            listener.onRemoteVideo(track)
            // 测量远端视频尺寸（观看端 letterbox 映射用）。
            // 注意：VideoTrack 分发给多个 Sink 的同一帧由分发器负责释放，
            // 这里绝不能调 frame.release()（否则 native 渲染还在用就 SIGABRT）。
            val sizeSink = VideoSink { frame ->
                val w = frame.rotatedWidth
                val h = frame.rotatedHeight
                if (w > 0 && h > 0 && (w != lastRemoteVideoWidth || h != lastRemoteVideoHeight)) {
                    lastRemoteVideoWidth = w
                    lastRemoteVideoHeight = h
                    listener.onRemoteVideoSize(w, h)
                }
            }
            remoteVideoSizeSink = sizeSink
            track.addSink(sizeSink)
        }
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState) {
        if (state != PeerConnection.SignalingState.STABLE || !renegoInFlight) return
        renegoInFlight = false
        restartRequested = false
        Log.d(TAG, "ICE_RESTART_NEGOTIATION_STABLE ice=$latestIceState")
        // Keep the request coalesced until the offer/answer exchange is actually complete.
        // If ICE is still down after settling, allow connectivity checks to run before retrying.
        if (!closed && !endedNotified.get() &&
            latestIceState != PeerConnection.IceConnectionState.CONNECTED.name &&
            latestIceState != PeerConnection.IceConnectionState.COMPLETED.name
        ) {
            iceHandler.removeCallbacks(iceRestartRunner)
            iceHandler.postDelayed(iceRestartRunner, ICE_RESTART_RETRY_MS)
        }
    }
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
        // 收集完成那一刻报一次类型分布（每会话一条，不重复）：r=0 就是 TURN 一个中继都没拿到，
        // 这是"P2P 打洞失败"唯一的远程自证——此前只能从服务器日志反推有没有分配过 relay
        if (state != PeerConnection.IceGatheringState.COMPLETE || gatherReported) return
        // 正常时机仍然只在会话还活着时报；没走到 COMPLETE 的那次由会话收尾补（见 reportGatherTally）
        if (closed || endedNotified.get()) return
        reportGatherTally(incomplete = false)
    }

    /**
     * 候选类型分布的唯一上报出口（每会话至多一条）。两个时刻必须都发得出：
     * 只监听 `COMPLETE` 的话，ICE 快速 FAILED 那条路**根本走不到收集完成**，
     * 而"TURN 一个中继都没拿到"恰恰只在死掉的那次才有意义 —— 与 0.2.59 自己修掉的
     * `wake_ok` 顺序 bug 同类的"白埋"（10-19 本机双模拟器 t4 实测 0 条 `gather_done`）。
     * `inc=1` 让取证时能把"收完 6 条里 r=0"与"收集被掐断"分开读。
     */
    private fun reportGatherTally(incomplete: Boolean) {
        if (gatherReported) return
        gatherReported = true
        com.soul2soul.app.util.SelfCheck.reportEvent(
            context, role, "gather_done", latestIceState,
            com.soul2soul.app.util.DiagDetail.gather(
                candidateTally.get(0), candidateTally.get(1),
                candidateTally.get(2), candidateTally.get(3),
                incomplete = incomplete,
            ),
        )
    }
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onAddStream(stream: MediaStream) {}
    /** ICE restart 等触发的重新协商：共享端发新 offer，观看端应答（onRemoteSdp 已兼容） */
    override fun onRenegotiationNeeded() {
        if (!established || closed || renegoInFlight || isSharer.not()) return
        val pc = pc ?: return
        renegoInFlight = true
        pc.createOffer(object : SdpAdapter("renego", ::onSdpFailure) {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter("setLocalRenego", ::onSdpFailure) {
                    override fun onSetSuccess() {
                        sendSdp(desc)
                    }

                    override fun onSetFailure(error: String?) {
                        renegoInFlight = false
                        restartRequested = false
                        Log.w(TAG, "renego setLocal failed: $error")
                        onSdpFailure("setLocalRenego_SET")
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    // ---------- DataChannel.Observer ----------

    override fun onBufferedAmountChange(previousAmount: Long) {
        // send() 可能因发送缓冲区暂满返回 false；缓冲回落时重试最新画质请求。
        flushPendingQuality()
    }

    override fun onStateChange() {
        flushPendingQuality()
    }

    override fun onMessage(buffer: DataChannel.Buffer) {
        if (buffer.binary) return
        val bytes = ByteArray(buffer.data.remaining())
        buffer.data.get(bytes)
        try {
            listener.onDataMessage(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "bad datachannel msg", e)
        }
    }

    // ---------- 释放 ----------

    fun close() {
        if (closed) return
        closed = true
        reportSessionEnd()
        synchronized(qualityLock) { pendingQualityEdge = null }
        iceHandler.removeCallbacksAndMessages(null)
        stopRttPolling()
        synchronized(pendingRemoteIce) {
            pendingRemoteIce.clear()
            remoteDescriptionSet = false
        }
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }; dataChannel = null
        runCatching { ctlChannel?.unregisterObserver() }
        runCatching { ctlChannel?.close() }; ctlChannel = null
        runCatching { ctlgChannel?.unregisterObserver() }
        runCatching { ctlgChannel?.close() }; ctlgChannel = null
        remoteVideoSizeSink?.let { sink -> runCatching { remoteVideoTrack?.removeSink(sink) } }
        remoteVideoSizeSink = null
        remoteVideoTrack = null
        pc?.close(); pc = null
        surfaceHelper?.dispose(); surfaceHelper = null
        factory.dispose()
        audioModule.release()
    }

    // ---------- 内部 ----------

    private fun createPeerConnection(): PeerConnection {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Wi-Fi/蜂窝切换后继续收集新候选，避免只能依赖失效的旧地址。
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(config, this) ?: error("createPeerConnection failed")
    }

    private fun startConnectTimeout() {
        connectStartedAtMs = android.os.SystemClock.elapsedRealtime()
        mediaConnectedAtMs = 0L
        mediaLostAtMs = 0L
        iceHandler.removeCallbacks(connectTimeoutRunner)
        iceHandler.postDelayed(connectTimeoutRunner, CONNECT_TIMEOUT_MS)
    }

    private fun outageDurationMs(): Long = if (outageStartedAtMs > 0L) {
        (android.os.SystemClock.elapsedRealtime() - outageStartedAtMs).coerceAtLeast(0L)
    } else {
        0L
    }

    private fun onSdpFailure(stage: String) {
        renegoInFlight = false
        restartRequested = false
        notifyEnded("sdp_$stage")
    }

    private fun notifyEnded(reason: String) {
        if (closed || !endedNotified.compareAndSet(false, true)) return
        noteEnd(reason)
        iceHandler.removeCallbacks(connectTimeoutRunner)
        iceHandler.removeCallbacks(disconnectTimeoutRunner)
        iceHandler.removeCallbacks(iceRestartRunner)
        iceHandler.removeCallbacks(iceStableRunner)
        Log.w(
            TAG,
            "session ended: $reason established=$established recoveries=$recoveryCount " +
                "restartAttempts=${iceRestartBudget.usedAttempts} outageMs=${outageDurationMs()}",
        )
        listener.onEnded(reason)
    }

    /**
     * 记录"谁先关的这条会话"。first-wins：真·终止原因（超时/采集失败/对端 bye）总是先于
     * 它引发的收尾 close() 到达，因此本地主动结束只在没有任何上游原因时才落地为 local_close。
     */
    fun noteEnd(reason: String) {
        if (endReason.isEmpty()) endReason = reason
    }

    /** 会话终态自报：服务器只看得见信令帧，媒体侧"为什么结束"必须双端自己说 */
    private fun reportSessionEnd() {
        if (connectStartedAtMs <= 0L) return
        // 收集没走到 COMPLETE 就散会（快速 FAILED / 超时）时，COMPLETE 那条路永远不会响，
        // 而 relay 计数只在死掉的那次才有判据价值 ⇒ 在 socket 还能用的最后一刻补一条 inc=1 的。
        // 这条也受上面那句守卫管：没真正发起过连接就报全 0，会被读成"TURN 分配失败"。
        reportGatherTally(incomplete = true)
        val now = android.os.SystemClock.elapsedRealtime()
        // 时长一律走 SessionTiming：connectedSinceMs 会被 ICE 闪断清零，
        // 用它算就会把"聊了 18 分钟被拆"和"根本没连上"报成同一个数。
        val setupMs = SessionTiming.setupMs(connectStartedAtMs, mediaConnectedAtMs)
        com.soul2soul.app.util.SelfCheck.reportSession(
            context,
            role = role,
            reason = endReason.ifEmpty { "local_close" },
            ice = latestIceState,
            setupMs = setupMs,
            liveMs = SessionTiming.liveMs(mediaConnectedAtMs, mediaLostAtMs, now),
            // 会话期间最后一次**真的读到**的选路（不是"散会之后那一条 tick"的读数——那一刻 pairs 已经没了）。
            // 从没读到过就留 "-"（发了但这侧无值），不许拿 u-u/o-o 冒充知道。
            path = if (lastSelectedPath == "unknown") {
                "-"
            } else {
                com.soul2soul.app.util.DiagDetail.pathCode(lastSelectedPath)
            },
            proj = projectionState,
        )
    }

    /**
     * 本机屏幕是否亮着：锁屏期间共享端不出帧是 0.2.56 的设计语义（画面暂停·语音继续），
     * 拿它当"卡死"报就会每 10s 制造一条假异常（旧的 video_stall 假告警正是这类坑）。
     */
    private fun localScreenOn(): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)

    private fun sendSdp(desc: SessionDescription) {
        if (closed || endedNotified.get()) return
        // 屏幕内容编码优化：offer 中将 VP9 载荷提前（对端按相同顺序应答）
        val sdpText = if (desc.type == SessionDescription.Type.OFFER) {
            preferVp9(desc.description)
        } else {
            desc.description
        }
        Log.d(TAG, "send sdp: type=${desc.type.canonicalForm()} len=${sdpText.length}")
        listener.onSignalOut(
            JSONObject()
                .put("type", "sdp")
                .put(
                    "sdp",
                    JSONObject()
                        .put("type", desc.type.canonicalForm())
                        .put("sdp", sdpText),
                )
        )
    }

    private fun hasAudioPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 只覆盖用到的回调，其余留空的 SdpObserver 基类 */
    private abstract class SdpAdapter(
        private val tag: String,
        private val failure: ((String) -> Unit)? = null,
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "sdp create failed ($tag): $error")
            failure?.invoke("${tag}_CREATE")
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "sdp set failed ($tag): $error")
            failure?.invoke("${tag}_SET")
        }
    }

    companion object {
        private const val TAG = "WebRtcClient"
        private const val STREAM = "soul"
        private const val CAPTURE_FPS = 30
        private const val RTT_POLL_MS = 2000L
        private const val DIAGNOSTIC_EVERY_POLLS = 5
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val DISCONNECT_TIMEOUT_MS = 15_000L
        private const val ICE_RESTART_AFTER_MS = 6000L
        private const val ICE_RESTART_RETRY_MS = 1000L
        // 连续 3 个诊断窗口（约 30 秒）零帧才算画面卡死：单窗口 0 帧可能只是静态画面没触发编码
        private const val VIEWER_STALL_POLLS = 3
    }
}
