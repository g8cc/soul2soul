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
    @Volatile private var connectedSinceMs = 0L
    @Volatile private var outageStartedAtMs = 0L
    @Volatile private var recoveryCount = 0
    @Volatile private var connectStartedAtMs = 0L
    @Volatile private var renegoInFlight = false
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

    /** 静音本端上行音轨（只影响本会话，不像 AudioManager.isMicrophoneMute 那样全局关麦） */
    fun muteLocalAudio(muted: Boolean) {
        runCatching { audioTrack?.setEnabled(!muted) }
            .onFailure { Log.w(TAG, "muteLocalAudio failed", it) }
    }

    // ---------- 建立流程 ----------

    /** 共享端入口。projectionData 为系统屏幕录制授权返回的 Intent */
    fun startSharer(projectionData: Intent) {
        val pc = createPeerConnection()
        this.pc = pc
        startConnectTimeout()

        val capturer: VideoCapturer = ScreenCapturerAndroid(
            projectionData,
            object : android.media.projection.MediaProjection.Callback() {
                // 用户在系统隐私提示里"停止"录屏：授权被撤销，会话必须体面结束而非黑屏挂着
                override fun onStop() {
                    if (!closed) listener.onEnded("projection_stopped")
                }
            },
        )
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

        // 远程操控手势：整笔一条消息，可靠有序（丢半截手势比延迟更致命）
        // 注意创建顺序：0.2.8 观看端 onDataChannel 是后到者覆盖；ctl 先建、anno 后建，
        // 保证旧观看端最终持有 anno（丢包容忍），笔迹不会被可靠通道的队头阻塞拖死
        val ctlInit = DataChannel.Init().apply { ordered = true }
        ctlChannel = pc.createDataChannel("ctl", ctlInit)?.also {
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
        preferredLongEdge = VideoQualityPolicy.normalizeEdge(edge)
        applyCaptureLongEdge(preferredLongEdge)
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
                // 屏幕文字比帧率更敏感：优先保分辨率，网络紧张时由 WebRTC 降帧。
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
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

    private fun sendControlNow(json: JSONObject): Boolean {
        val dc = listOfNotNull(ctlChannel, dataChannel).firstOrNull { channel ->
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
                if (!wasConnected) {
                    connectedSinceMs = now
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
                if (outageStartedAtMs == 0L) {
                    outageStartedAtMs = android.os.SystemClock.elapsedRealtime()
                    Log.w(TAG, "ICE_OUTAGE_STARTED state=$state")
                }
                iceHandler.removeCallbacks(disconnectTimeoutRunner)
                iceHandler.postDelayed(disconnectTimeoutRunner, DISCONNECT_TIMEOUT_MS)
            }
            PeerConnection.IceConnectionState.FAILED -> {
                iceHandler.removeCallbacks(iceRestartRunner)
                iceHandler.removeCallbacks(iceStableRunner)
                connectedSinceMs = 0L
                if (outageStartedAtMs == 0L) {
                    outageStartedAtMs = android.os.SystemClock.elapsedRealtime()
                    Log.w(TAG, "ICE_OUTAGE_STARTED state=$state")
                }
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
        if (renegoInFlight) {
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
        runCatching { pc.restartIce() }
            .onFailure { Log.w(TAG, "restartIce unsupported", it) }
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
                    if (best >= 0) listener.onRtt(best)

                    // 统计仍每 2 秒用于自适应；详细媒体诊断每 10 秒记录一次。
                    statsPollCount += 1
                    if (statsPollCount % DIAGNOSTIC_EVERY_POLLS == 0) {
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
                                    Log.d(TAG, "ENC impl=$impl fps=${formatMetric(fps)} bytes=$sent")
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
        if (dc.label() == "ctl") {
            runCatching { ctlChannel?.unregisterObserver() }
            ctlChannel = dc
        } else {
            runCatching { dataChannel?.unregisterObserver() }
            dataChannel = dc
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

    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
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
                        renegoInFlight = false
                        sendSdp(desc)
                    }

                    override fun onSetFailure(error: String?) {
                        renegoInFlight = false
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
        notifyEnded("sdp_$stage")
    }

    private fun notifyEnded(reason: String) {
        if (closed || !endedNotified.compareAndSet(false, true)) return
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
    }
}
