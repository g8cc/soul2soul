package com.soul2soul.app.webrtc

/** Pure screen-sharing quality adaptation policy. */
internal object VideoQualityPolicy {
    const val DEGRADED_EDGE = 960
    const val LEGACY_EDGE = 1280
    const val DEFAULT_EDGE = 1600
    const val HD_EDGE = 1920

    private const val BAD_RTT_MS = 350L
    private const val GOOD_RTT_MS = 150L
    private const val BAD_LOSS_PERCENT = 8.0
    private const val GOOD_LOSS_PERCENT = 2.0
    private const val DOWNGRADE_STREAK = 3
    private const val RESTORE_STREAK = 10

    data class Metrics(
        val rttMs: Long,
        val lossPercent: Double,
        val availableBitrateBps: Long,
        val outboundFps: Double = -1.0,
        /**
         * `availableOutgoingBitrate` 是否已经脱离了带宽估计器的爬坡期。
         * 建会头几秒它钉在 **300kbps 的估计初值**上而不是实测值：v0.2.62 双模拟器实测
         * 连续两条 tick 都是 `q_15_s-s_300_…`、`q_54_s-s_300_…`，而触发降档那一刻
         * logcat 打印的同一列已经是 `available=5382666`（rtt 18ms）⇒ 3 连拍攒的是爬坡期初值，
         * 决策时读数早就不坏了。300k < 3.5M×72% 因此会在 6s 内必然触发一次降档，
         * 而降档要点开采集重启（见 WebRtcClient.applyCaptureLongEdge，Android 14+ 上是致命的）。
         * 爬坡期只按 rtt/丢包/帧率判——不拿一个还没测准的带宽去动画质。
         */
        val bandwidthSettled: Boolean = true,
    )

    data class Decision(val targetEdge: Int?, val highStreak: Int, val lowStreak: Int)

    /**
     * Calculate loss for the interval between two cumulative WebRTC samples.
     *
     * WebRTC packet counters are cumulative for the lifetime of an RTP stream.
     * Using the raw counters makes one early burst of loss poison adaptation for
     * the rest of a call, so quality decisions must use deltas instead. Older
     * libwebrtc builds may omit remote-inbound packetsReceived; in that case the
     * outbound sent delta is used as the denominator when it is available.
     */
    fun intervalLossPercent(
        previousLost: Long,
        previousReceived: Long,
        currentLost: Long,
        currentReceived: Long,
        previousSent: Long = -1L,
        currentSent: Long = -1L,
    ): Double {
        if (previousLost < 0 || currentLost < 0 || currentLost < previousLost) return -1.0
        val lostDelta = currentLost - previousLost
        val receivedDelta = when {
            previousReceived >= 0 && currentReceived >= previousReceived ->
                currentReceived - previousReceived
            previousReceived >= 0 || currentReceived >= 0 -> -1L
            previousSent >= 0 && currentSent >= previousSent ->
                (currentSent - previousSent - lostDelta).takeIf { it >= 0L } ?: -1L
            else -> -1L
        }
        if (receivedDelta < 0) return -1.0
        val total = lostDelta + receivedDelta
        if (total <= 0) return -1.0
        return (lostDelta * 100.0 / total).coerceIn(0.0, 100.0)
    }

    fun step(
        preferredEdge: Int,
        currentEdge: Int,
        metrics: Metrics,
        highStreak: Int,
        lowStreak: Int,
    ): Decision {
        val preferred = normalizeEdge(preferredEdge)
        val current = normalizeEdge(currentEdge)
        val bad = isBad(current, metrics)
        val good = isGood(preferred, metrics)
        val nextHigh = if (bad) highStreak + 1 else 0
        val nextLow = if (good) lowStreak + 1 else 0
        val target = when {
            nextHigh >= DOWNGRADE_STREAK && current > DEGRADED_EDGE ->
                when {
                    current >= HD_EDGE -> DEFAULT_EDGE
                    current >= DEFAULT_EDGE -> LEGACY_EDGE
                    else -> DEGRADED_EDGE
                }
            nextLow >= RESTORE_STREAK && current < preferred -> preferred
            else -> null
        }
        return Decision(target, nextHigh, nextLow)
    }

    fun normalizeEdge(edge: Int): Int = when {
        edge >= HD_EDGE -> HD_EDGE
        edge >= DEFAULT_EDGE -> DEFAULT_EDGE
        edge >= LEGACY_EDGE -> LEGACY_EDGE
        else -> DEGRADED_EDGE
    }

    /**
     * 进入临时协助（操控）时临时采用的长边档：操控要的是跟手不是高清，
     * 降到低档把码率与 CPU 让给帧率。null = 已在最低档，无需切。
     */
    fun controlEnterEdge(preferred: Int): Int? {
        val p = normalizeEdge(preferred)
        return if (p > DEGRADED_EDGE) DEGRADED_EDGE else null
    }

    fun requiredBitrateBps(edge: Int): Long = when (normalizeEdge(edge)) {
        HD_EDGE -> 4_500_000L
        DEFAULT_EDGE -> 3_500_000L
        LEGACY_EDGE -> 2_500_000L
        else -> 1_800_000L
    }

    private fun isBad(currentEdge: Int, metrics: Metrics): Boolean {
        if (metrics.rttMs >= BAD_RTT_MS) return true
        if (metrics.lossPercent >= BAD_LOSS_PERCENT) return true
        if (metrics.outboundFps >= 0.0 && metrics.outboundFps < 18.0) return true
        val available = metrics.availableBitrateBps
        if (!metrics.bandwidthSettled) return false
        return available > 0 && available < requiredBitrateBps(currentEdge) * 72 / 100
    }

    private fun isGood(preferredEdge: Int, metrics: Metrics): Boolean {
        if (metrics.rttMs < 0 || metrics.rttMs >= GOOD_RTT_MS) return false
        if (metrics.lossPercent >= 0 && metrics.lossPercent >= GOOD_LOSS_PERCENT) return false
        if (metrics.outboundFps >= 0.0 && metrics.outboundFps < 24.0) return false
        val available = metrics.availableBitrateBps
        // 爬坡期的低带宽既不该判坏，也不该被"available<=0 当作未知→放行"当成好：
        // 未知才放行，读得到但还没稳的不计回升条件。
        if (!metrics.bandwidthSettled) return available <= 0
        return available <= 0 || available >= requiredBitrateBps(preferredEdge) * 115 / 100
    }
}
