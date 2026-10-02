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
    private val audioModule: org.webrtc.audio.JavaAudioDeviceModule =
        org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            // 通话音源：路由到设备的通话音频通路
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            // 声学策略：回声消除用软件 AEC3（稳定可控）；
            // 噪声抑制走设备硬件 DSP（对稳态环境声压制更强，软件 NS 对键盘/磕碰类瞬态声无效——
            // 那部分靠硬件 DSP + 增益控制，极端场景需耳机，微信亦如此）
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
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
    private var dataChannel: DataChannel? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var closed = false
    private val frameCount = java.util.concurrent.atomic.AtomicLong()
    private val rttHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var highRttStreak = 0
    private var lowRttStreak = 0
    private val iceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var iceRestartCount = 0
    @Volatile private var established = false
    @Volatile private var renegoInFlight = false
    private var videoSender: org.webrtc.RtpSender? = null

    /** 当前采集长边上限（观看端可通过 DataChannel 切 1280/1920） */
    @Volatile
    var captureLongEdge = 1280
        private set

    /** 用户意图档位（观看端 HD 开关设定）：弱网自动降档后，RTT 好转回升到这里 */
    @Volatile
    private var preferredLongEdge = 1280

    /** 静音本端上行音轨（只影响本会话，不像 AudioManager.isMicrophoneMute 那样全局关麦） */
    fun muteLocalAudio(muted: Boolean) {
        runCatching { audioTrack?.setEnabled(!muted) }
            .onFailure { Log.w(TAG, "muteLocalAudio failed", it) }
    }

    /** 共享端已采集的帧数（统计用；锁屏看门狗走 SCREEN_OFF 广播，见 ScreenShareService） */
    fun framesReceived(): Long = frameCount.get()

    // ---------- 建立流程 ----------

    /** 共享端入口。projectionData 为系统屏幕录制授权返回的 Intent */
    fun startSharer(projectionData: Intent) {
        val pc = createPeerConnection()
        this.pc = pc

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

        val metrics = context.resources.displayMetrics
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        val scale = minOf(1f, captureLongEdge.toFloat() / longEdge)
        val w = (metrics.widthPixels * scale).toInt() / 2 * 2
        val h = (metrics.heightPixels * scale).toInt() / 2 * 2
        val helper = SurfaceTextureHelper.create("s2s-capture", eglContext)
        surfaceHelper = helper
        // 计帧观察者：只透传不碰引用计数（在轨道上加 Sink 会破坏 VideoFrame 引用计数导致崩溃）
        val upstream = source.capturerObserver
        val countingObserver = object : org.webrtc.CapturerObserver {
            override fun onFrameCaptured(frame: VideoFrame) {
                frameCount.incrementAndGet()
                upstream.onFrameCaptured(frame)
            }

            override fun onCapturerStarted(success: Boolean) = upstream.onCapturerStarted(success)

            override fun onCapturerStopped() = upstream.onCapturerStopped()
        }
        capturer.initialize(helper, context, countingObserver)
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

        // 共享端是 offerer，负责创建 DataChannel
        // 笔迹是激光笔范式：容忍丢包、不要队头阻塞（reliable-ordered 在弱网下会堵死整队笔迹）
        val dcInit = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 1
        }
        dataChannel = pc.createDataChannel("anno", dcInit)?.also {
            it.registerObserver(this)
        }

        pc.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter("setLocalOffer") {
                    override fun onSetSuccess() = sendSdp(desc)
                }, desc)
            }
        }, MediaConstraints())
    }

    /** 观看端入口：只建 pc + 麦克风，等 offer */
    fun startViewer() {
        val pc = createPeerConnection()
        this.pc = pc

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
        preferredLongEdge = edge.coerceIn(720, 1920)
        applyCaptureLongEdge(preferredLongEdge)
    }

    private fun applyCaptureLongEdge(edge: Int) {
        captureLongEdge = edge
        val capturer = capturer ?: return
        val metrics = context.resources.displayMetrics
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        val scale = minOf(1f, captureLongEdge.toFloat() / longEdge)
        val w = (metrics.widthPixels * scale).toInt() / 2 * 2
        val h = (metrics.heightPixels * scale).toInt() / 2 * 2
        runCatching {
            capturer.stopCapture()
            capturer.startCapture(w, h, CAPTURE_FPS)
        }.onFailure { Log.w(TAG, "resolution switch failed", it) }
        videoSender?.let { applyBitrate(it) }
    }

    private fun applyBitrate(sender: org.webrtc.RtpSender) {
        runCatching {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                // 码率阶梯：分辨率越高上限越高；降档时同步压码率（弱网更流畅）
                params.encodings[0].maxBitrateBps = when (captureLongEdge) {
                    1920 -> 4_000_000
                    960 -> 1_500_000
                    else -> 2_500_000
                }
                // 带宽不足时的降级策略：视频源 isScreencast=true 时 libwebrtc
                // 默认即"保分辨率降帧率"（屏幕共享标准行为），无需显式设置
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
        val type = sdpJson.optString("type")
        val sdpText = sdpJson.optString("sdp")
        Log.d(TAG, "remote sdp: type=$type len=${sdpText.length}")
        // 兼容旧版(0.2.5 preferVp9 末尾多空行)发来的 offer：规整为单一结尾 CRLF，
        // 否则对端 libwebrtc 严格解析器会整份拒收("Invalid SDP line")
        val normalized = sdpText.trimEnd('\r', '\n') + "\r\n"
        val remote = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), normalized)
        pc.setRemoteDescription(object : SdpAdapter("setRemote") {
            override fun onSetSuccess() {
                if (type == "offer") {
                    pc.createAnswer(object : SdpAdapter("createAnswer") {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            pc.setLocalDescription(object : SdpAdapter("setLocalAnswer") {
                                override fun onSetSuccess() = sendSdp(answer)
                            }, answer)
                        }
                    }, MediaConstraints())
                }
            }
        }, remote)
    }

    fun onRemoteIce(candidateJson: JSONObject) {
        val candidate = IceCandidate(
            candidateJson.optString("sdpMid"),
            candidateJson.optInt("sdpMLineIndex"),
            candidateJson.getString("candidate"),
        )
        pc?.addIceCandidate(candidate)
    }

    // ---------- 标注 DataChannel ----------

    fun sendAnnotation(json: JSONObject) {
        val dc = dataChannel ?: run { Log.w(TAG, "sendAnnotation: channel null"); return }
        val state = try { dc.state().name } catch (e: Exception) { "?" }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val ok = dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        Log.d(TAG, "dc send k=${json.opt("k")} state=$state ok=$ok")
    }

    // ---------- PeerConnection.Observer ----------

    override fun onIceCandidate(candidate: IceCandidate) {
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
        Log.d(TAG, "ice state: $state")
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                established = true
                iceHandler.removeCallbacks(iceRestartRunner)
                listener.onLive()
                startRttPolling()
            }
            PeerConnection.IceConnectionState.DISCONNECTED ->
                // 闪断恢复器：短暂断开先自动重启 ICE 尝试原地复活，
                // 而不是等 FAILED 把整场会话判死（微信式韧性）
                if (isSharer) iceHandler.postDelayed(iceRestartRunner, ICE_RESTART_AFTER_MS)
            PeerConnection.IceConnectionState.FAILED -> {
                iceHandler.removeCallbacks(iceRestartRunner)
                listener.onEnded("ice_FAILED")
            }
            PeerConnection.IceConnectionState.CLOSED -> if (!closed) {
                iceHandler.removeCallbacks(iceRestartRunner)
                listener.onEnded("ice_CLOSED")
            }
            else -> Unit
        }
    }

    private val iceRestartRunner = Runnable {
        if (closed || renegoInFlight) return@Runnable
        val pc = pc ?: return@Runnable
        if (iceRestartCount >= MAX_ICE_RESTARTS) {
            Log.w(TAG, "ice restart 上限，放弃恢复")
            return@Runnable
        }
        iceRestartCount += 1
        Log.d(TAG, "ice restart #$iceRestartCount")
        runCatching { pc.restartIce() }
            .onFailure { Log.w(TAG, "restartIce unsupported", it) }
    }

    /** 每 2 秒取一次 candidate-pair 的 RTT + 视频流质量统计（实时性可视化与诊断） */
    private fun startRttPolling() {
        rttHandler.post(object : Runnable {
            override fun run() {
                val pc = pc ?: return
                pc.getStats { report ->
                    var best = -1L
                    for ((_, stats) in report.statsMap) {
                        if (stats.type == "candidate-pair" &&
                            stats.members["state"] == "succeeded"
                        ) {
                            val rtt = (stats.members["currentRoundTripTime"] as? Number)?.toDouble()
                            if (rtt != null && rtt >= 0) {
                                val ms = (rtt * 1000).toLong()
                                if (best < 0 || ms < best) best = ms
                            }
                        }
                    }
                    if (best >= 0) listener.onRtt(best)

                    // 编解码实现与帧率诊断：确认软硬编解码（性能调优的依据）
                    for ((_, stats) in report.statsMap) {
                        when (stats.type) {
                            "outbound-rtp" -> if (stats.members["kind"] == "video") {
                                val impl = stats.members["encoderImplementation"]?.toString() ?: "?"
                                val fps = (stats.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                                val sent = (stats.members["bytesSent"] as? Number)?.toLong() ?: 0L
                                Log.d(TAG, "ENC impl=$impl fps=${"%.1f".format(fps)} bytes=$sent")
                            }
                            "inbound-rtp" -> if (stats.members["kind"] == "video") {
                                val impl = stats.members["decoderImplementation"]?.toString() ?: "?"
                                val fps = (stats.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                                val lost = (stats.members["packetsLost"] as? Number)?.toLong() ?: 0L
                                val recv = (stats.members["packetsReceived"] as? Number)?.toLong() ?: 1L
                                val jitter = (stats.members["jitter"] as? Number)?.toDouble() ?: 0.0
                                Log.d(TAG, "DEC impl=$impl fps=${"%.1f".format(fps)} loss=$lost recv=$recv jitter=${"%.1f".format(jitter * 1000)}ms")
                            }
                        }
                    }

                    // 视频流质量诊断（观看端）：丢包/抖动/帧率
                    if (!isSharer) {
                        for ((_, stats) in report.statsMap) {
                            if (stats.type == "inbound-rtp" &&
                                stats.members["kind"] == "video"
                            ) {
                                val lost = (stats.members["packetsLost"] as? Number)?.toLong() ?: 0L
                                val recv = (stats.members["packetsReceived"] as? Number)?.toLong() ?: 1L
                                val jitter = (stats.members["jitter"] as? Number)?.toDouble() ?: 0.0
                                val fps = (stats.members["framesPerSecond"] as? Number)?.toDouble()
                                Log.d(TAG, "video loss=${lost} recv=${recv} jitter=${"%.3f".format(jitter)}s fps=${"%.1f".format(fps ?: 0.0)}")
                            }
                        }
                    }

                    // 共享端按 RTT 自动调画质（弱网闪断的缓解）：
                    // 持续高 RTT → 降到 960 保流畅；持续好转 → 回升到用户意图档位
                    if (isSharer && best > 0) {
                        if (best > 350) highRttStreak += 1 else highRttStreak = 0
                        if (best < 150) lowRttStreak += 1 else lowRttStreak = 0
                        if (highRttStreak >= 3 && captureLongEdge > 960) {
                            Log.d(TAG, "high rtt ${best}ms, degrade to 960")
                            applyCaptureLongEdge(960)
                        } else if (lowRttStreak >= 10 && captureLongEdge < preferredLongEdge) {
                            Log.d(TAG, "rtt recovered, restore $preferredLongEdge")
                            applyCaptureLongEdge(preferredLongEdge)
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

    override fun onDataChannel(dc: DataChannel) {
        // 观看端：channel 由共享端创建
        dataChannel = dc
        dc.registerObserver(this)
    }

    override fun onTrack(transceiver: RtpTransceiver) {
        val track = transceiver.receiver.track()
        if (track is VideoTrack) {
            listener.onRemoteVideo(track)
            // 测量远端视频尺寸（观看端 letterbox 映射用）。
            // 注意：VideoTrack 分发给多个 Sink 的同一帧由分发器负责释放，
            // 这里绝不能调 frame.release()（否则 native 渲染还在用就 SIGABRT）。
            track.addSink(VideoSink { frame ->
                val w = frame.rotatedWidth
                val h = frame.rotatedHeight
                if (w > 0 && h > 0) listener.onRemoteVideoSize(w, h)
            })
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
        pc.createOffer(object : SdpAdapter("renego") {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter("setLocalRenego") {
                    override fun onSetSuccess() {
                        renegoInFlight = false
                        sendSdp(desc)
                    }

                    override fun onSetFailure(error: String?) {
                        renegoInFlight = false
                        Log.w(TAG, "renego setLocal failed: $error")
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    // ---------- DataChannel.Observer ----------

    override fun onBufferedAmountChange(previousAmount: Long) {}

    override fun onStateChange() {}

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
        stopRttPolling()
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        dataChannel?.close(); dataChannel = null
        pc?.close(); pc = null
        surfaceHelper?.dispose(); surfaceHelper = null
        factory.dispose()
        audioModule.release()
    }

    // ---------- 内部 ----------

    private fun createPeerConnection(): PeerConnection {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return factory.createPeerConnection(config, this) ?: error("createPeerConnection failed")
    }

    private fun sendSdp(desc: SessionDescription) {
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
    private abstract class SdpAdapter(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "sdp create failed ($tag): $error")
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "sdp set failed ($tag): $error")
        }
    }

    companion object {
        private const val TAG = "WebRtcClient"
        private const val STREAM = "soul"
        private const val CAPTURE_FPS = 30
        private const val RTT_POLL_MS = 2000L
        private const val ICE_RESTART_AFTER_MS = 6000L
        private const val MAX_ICE_RESTARTS = 3
    }
}
