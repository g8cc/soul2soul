package com.soul2soul.app.webrtc

/**
 * 「让TA听到我手机里的声音」的判定层（纯逻辑，可注入常量，JVM 可单测）。
 *
 * 这里只回答四个"该不该"的问题——起不起采集、算不算静音、抖动队列要不要丢帧、发送要不要丢帧。
 * 采集/编解码/通道本体留在 Service 与 WebRtcClient 里，因为它们没法在 JVM 里跑。
 *
 * 全族判据共同遵守的一条纪律：**全零 PCM 本身不携带结论**。它既可能是"对方 App 禁止被录制"，
 * 也可能只是"当时没声音在放"，还可能是"她那边本来就静音"。
 *
 * 计划里原本要求"与同一时刻的画面黑度配对"才升级成 blocked——**这一路在公开 API 上走不通**，
 * 两个候选信号都核过：
 * ① 逐帧亮度：ScreenCapturerAndroid 交出来的是纹理帧，取 Y 平面必须 `toI420()` 做一次
 *    GPU→CPU 回读，每帧一遍等于在编码路径旁边再架一条贵路径，不为一个横幅文案值这个价。
 * ② 系统侧"谁在放音"：`AudioManager.getActivePlaybackConfigurations()` 是公开的，但
 *    `AudioPlaybackConfiguration` 只对第三方 App 公开 `getAudioAttributes()`——
 *    `getPlayerState()`/`isCaptured()`/`canBeCapturedBy()` 全是 @hide（android-34 android.jar 里
 *    javap 可见成员即全部），而"暂停中的播放器也留在活跃列表里"这一条恰恰需要那个隐藏状态。
 * 结论：**没有可信的第二路信号，就不许有 blocked 这个状态**。UI 只报 live/silent，
 * 静音文案必须把两种成因与出路一起说清；"当时有没有别的播放器活着"降级成一条遥测读数
 * （`aud_silent detail=f_`），n=0 才允许读成"确实没东西在放"，n>0 什么都不证明。
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

    enum class Reason {
        START,
        SDK_TOO_OLD,
        NO_PROJECTION,
        TOGGLE_OFF,
        PEER_NOT_ACKED,
        NO_RECORD_PERMISSION,
    }

    /** `aud`/`audok` 握手里的 state 两态；对端不认识这个 key 时会落进路由的 None，所以名字要能直接进 JSON。 */
    const val STATE_LIVE = "live"
    const val STATE_SILENT = "silent"

    /** 路由/解析侧唯一允许的 state 集合：加第三态必须先有一条可信的第二路信号（见文件头）。 */
    val STATES = setOf(STATE_LIVE, STATE_SILENT)

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

    /**
     * **共享端自己屏幕上**的那一行说明（与推给她的 `state` 两态不是同一个东西，见 [peerStateOf]）。
     * 之所以要八态而不是两态：门的五个否决理由各自的出路完全不同，把它们统一成"没开"或"失败"
     * 就等于没解释——"TA 版本太旧"和"这个 App 不让录制"是两件事，用户能做的也只有两件事。
     */
    enum class AudStatus {
        /** 开关关着（默认态，不是一条提示） */
        OFF,

        /** API < 29：结构上做不到，与开关无关 */
        SDK_TOO_OLD,

        /** 没给录音权限。Android 里这条权限就叫「麦克风」，文案必须认这个名字（否则她在设置里找不到），
         *  同时要明说不是要她闭麦——内录和说话用的是同一条权限，两个语义在她那儿是相反的。 */
        NO_MIC_PERMISSION,

        /** 开了、但对面还没回 audok（她版本旧，或通道还没 OPEN） */
        WAITING_PEER,

        /** 开了、门也过了，但采集柄还没到位（画面没起来/正在起） */
        WAITING_VIDEO,

        /** 正在采、正在推 */
        HEARD,

        /** 开了且在采，但连续全零——**不判成因**（可能禁止录制，也可能当时没声音在放） */
        NOT_HEARD,

        /**
         * 门过了、采集线程却自己死了（record/codec 错）。
         * 之所以单独一态：10-22 双模拟器实测，`setAudioSource` 与 capture config 互斥导致
         * AudioRecord 构造直接抛，线程一帧没起来，而状态行停在「画面还没连上，声音还在等」——
         * 画面明明是连着的，这句承诺会一直挂着。**失败必须说失败**，不许伪装成"还在等"。
         */
        FAILED,
    }

    /**
     * 状态表。`reason` 来自 [gate]，`running`/`state` 来自采集线程，`captureFailed` 来自
     * 采集线程的最后一次自杀（[DeviceAudioTap.lastFailure]），四者不许由 UI 各自推断
     * （MainActivity 里再抄一遍判定＝两处真值，这是本仓库立过规矩的那种错法）。
     * ⚠️ `reason == START` 但线程没在跑是**真实存在**的两瞬：起不来（FAILED）与正在起
     * （WAITING_VIDEO），把它们混成一条就是"永远在等"的那条谎。
     * 按 Reason 穷举、**不写 else**：以后加一条否决理由而这里没接，编译期就红。
     */
    fun status(
        reason: Reason,
        running: Boolean,
        state: String,
        captureFailed: Boolean,
    ): AudStatus = when (reason) {
        Reason.TOGGLE_OFF -> AudStatus.OFF
        Reason.SDK_TOO_OLD -> AudStatus.SDK_TOO_OLD
        Reason.NO_RECORD_PERMISSION -> AudStatus.NO_MIC_PERMISSION
        Reason.PEER_NOT_ACKED -> AudStatus.WAITING_PEER
        Reason.NO_PROJECTION -> AudStatus.WAITING_VIDEO
        Reason.START -> when {
            captureFailed && !running -> AudStatus.FAILED
            !running -> AudStatus.WAITING_VIDEO
            state == STATE_SILENT -> AudStatus.NOT_HEARD
            else -> AudStatus.HEARD
        }
    }

    /**
     * 协议侧只有两态（[STATES]）。本地那八态**不许**外推：她那边能做的只有"知道有没有声音"，
     * 而多出来的态是给他自己看的。
     */
    fun peerStateOf(status: AudStatus): String =
        if (status == AudStatus.HEARD) STATE_LIVE else STATE_SILENT

    /** 一帧非零即清计数——这是"连续"的定义本身，不许用累计值偷懒。 */
    fun nextSilentStreak(streak: Int, maxAbsSample: Int): Int =
        if (maxAbsSample == 0) streak + 1 else 0

    /**
     * PCM16 一帧里的最大绝对值（判定用的唯一读数）。
     * `len` 是**样本个数**（不是字节数），越界一律不读——采集线程给半帧时不许越界读到上一帧的尾巴。
     * 全零返回 0；空输入也返回 0，但调用方**不许**把"没读到东西"喂进来当静音（那是无读数，
     * 与 `video_stall` 那 6 条假警同族的错法）。
     */
    fun maxAbsSample(pcm: ShortArray, len: Int): Int {
        var max = 0
        val n = minOf(len, pcm.size)
        var i = 0
        while (i < n) {
            val v = pcm[i].toInt()
            val a = if (v < 0) -v else v   // Short.MIN_VALUE 取反在 Int 里不溢出（32768）
            if (a > max) max = a
            i++
        }
        return max
    }

    /**
     * 两态判定：音频有信号就是 live；连续 SILENT_STREAK_FRAMES 帧全零 ⇒ silent。
     * 没有第三种状态不是"没做完"，而是文件头那条：**没有可信的第二路信号就不许有 blocked**。
     * 想加回来，先解决"公开 API 拿不到播放器状态、逐帧亮度要 GPU 回读"这两个问题之一。
     */
    fun stateOf(silentStreak: Int): String =
        if (silentStreak < SILENT_STREAK_FRAMES) STATE_LIVE else STATE_SILENT

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
