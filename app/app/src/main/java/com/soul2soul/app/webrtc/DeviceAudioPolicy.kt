package com.soul2soul.app.webrtc

/**
 * 「让TA听到我手机里的声音」的判定层（纯逻辑，可注入常量，JVM 可单测）。
 *
 * 这里只回答四个"该不该"的问题——起不起采集、算不算静音、算不算被屏蔽、要不要丢帧。
 * 采集/编解码/通道本体留在 Service 与 WebRtcClient 里，因为它们没法在 JVM 里跑。
 *
 * 全族判据共同遵守的一条纪律：**全零 PCM 本身不携带结论**。它既可能是"对方 App 禁止被录制"，
 * 也可能只是"当时没声音在放"，还可能是"她那边本来就静音"。所以任何"被屏蔽"的判定都必须
 * 与同一时刻的画面黑度配对才升级，单靠音频一路一律降级成"静音"（不写"坏了"）。
 */
object DeviceAudioPolicy {

    /** AudioPlaybackCapture 是 API 29 引入的；28 及以下结构上做不到，不是开关问题。 */
    const val MIN_SDK = 29

    /**
     * 连续这么多帧全零才判"这段时间没声音"。一帧 1024 样本 @48kHz = 21.33ms，
     * 25 帧 ≈ 533ms——比一次正常说话的气口长，比一段静音短，取的是"别被一个停顿骗了"。
     */
    const val SILENT_STREAK_FRAMES = 25

    /** 抖动队列的硬上限：超过它，最旧的帧直接扔（400ms 后的声音对上口型已经没有意义）。 */
    const val JITTER_HARD_CAP_MS = 400L

    /**
     * 观看端放音延迟。写成**具名单一常量**而不是"推导值"：
     * 120ms 是抖动目标，80ms 是给视频路径的补偿（共享端的画面经采集/编码/中继比这条音频路慢），
     * 真实分布要靠 `aud_play detail=dl_…` 的读数再来调（计划里明确要求不许假装是推导出来的）。
     */
    const val AUD_PLAYOUT_DELAY_MS = 200L

    /**
     * SCTP 积压超过这个字节数就不再把新的音频帧塞进去（背压时丢音频，绝不丢视频/语音）。
     * 9600 = (放音延迟 200ms + 抖动硬上限 400ms) × 128kbps÷8 —— 即"已经在路上的音频
     * 最多只允许排到它自己判死的那一刻"，再多排进去的帧注定迟到。
     */
    const val SEND_MAX_BUFFERED_BYTES = 9_600

    /** 判"画面是黑的"用到的亮度上界：8-bit YUV 里 16 就是消隐黑，比它低即认为全黑。 */
    const val BLACK_Y_MAX = 16

    enum class Reason {
        START,
        SDK_TOO_OLD,
        NO_PROJECTION,
        TOGGLE_OFF,
        PEER_NOT_ACKED,
        NO_RECORD_PERMISSION,
    }

    /** `aud`/`audok` 握手里的 state 三态；旧版对端不认识这个 key，所以名字要能直接进 JSON。 */
    const val STATE_LIVE = "live"
    const val STATE_SILENT = "silent"
    const val STATE_BLOCKED = "blocked"

    /**
     * 五个条件全满足才开采集——**包括对端 ACK**。
     * 少了 peerAcked 这一条：她版本旧不认识 `aud` 通道，我们这边照样编码往外发，
     * 结果是白烧 140kbps 上行、UI 还显示"已开启"，而她一无所获。
     */
    fun gate(
        sdk: Int,
        hasProjection: Boolean,
        toggleWanted: Boolean,
        peerAcked: Boolean,
        hasRecordPermission: Boolean,
    ): Reason = when {
        !toggleWanted -> Reason.TOGGLE_OFF
        sdk < MIN_SDK -> Reason.SDK_TOO_OLD
        !hasProjection -> Reason.NO_PROJECTION
        !hasRecordPermission -> Reason.NO_RECORD_PERMISSION
        !peerAcked -> Reason.PEER_NOT_ACKED
        else -> Reason.START
    }

    /** 一帧非零即清计数——这是"连续"的定义本身，不许用累计值偷懒。 */
    fun nextSilentStreak(streak: Int, maxAbsSample: Int): Int =
        if (maxAbsSample == 0) streak + 1 else 0

    fun nextBlackStreak(streak: Int, frameIsBlack: Boolean): Int =
        if (frameIsBlack) streak + 1 else 0

    /**
     * Y 平面判黑。空输入按"无读数"处理（返回 false，不判黑）——
     * 把"没测到"读成"黑屏"会造出假 BLOCKED，这条在 video_stall 上已经踩过一次。
     */
    fun isVideoBlack(yPlane: ByteArray): Boolean {
        if (yPlane.isEmpty()) return false
        for (b in yPlane) {
            if ((b.toInt() and 0xFF) > BLACK_Y_MAX) return false
        }
        return true
    }

    /**
     * 三态判定：音频有信号就是 live；音频静音**且**同一时刻画面也黑 ⇒ blocked
     * （那是 DRM/FLAG_SECURE 那类内容保护的样子）；只有音频静音 ⇒ silent
     * （她听到的是"没声音"，出路是换个播放器或让他外放，不是"坏了"）。
     */
    fun stateOf(silentStreak: Int, blackStreak: Int): String = when {
        silentStreak < SILENT_STREAK_FRAMES -> STATE_LIVE
        blackStreak >= SILENT_STREAK_FRAMES -> STATE_BLOCKED
        else -> STATE_SILENT
    }

    enum class JitterAction { PLAY, WAIT, DROP_OLDEST }

    /**
     * @param headEnqueuedAtMs 队首帧入队时刻；空队列传 -1
     * @param tailEnqueuedAtMs 最新入队帧的时刻
     */
    fun jitter(headEnqueuedAtMs: Long, tailEnqueuedAtMs: Long, nowMs: Long): JitterAction {
        if (headEnqueuedAtMs < 0L) return JitterAction.WAIT
        if (tailEnqueuedAtMs - headEnqueuedAtMs > JITTER_HARD_CAP_MS) return JitterAction.DROP_OLDEST
        return if (nowMs - headEnqueuedAtMs >= AUD_PLAYOUT_DELAY_MS) JitterAction.PLAY else JitterAction.WAIT
    }

    enum class SendAdmission { SEND, DROP }

    fun sendAdmission(bufferedBytes: Int): SendAdmission =
        if (bufferedBytes > SEND_MAX_BUFFERED_BYTES) SendAdmission.DROP else SendAdmission.SEND
}
