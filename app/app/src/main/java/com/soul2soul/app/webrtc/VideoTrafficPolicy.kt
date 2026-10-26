package com.soul2soul.app.webrtc

internal object VideoTrafficPolicy {
    private const val NETWORK_HEADROOM_PERCENT = 85L
    private const val MIN_VIDEO_BUDGET_BPS = 250_000L

    fun targetMaxBitrateBps(
        profileMaxBps: Long,
        currentBps: Long,
        availableBps: Long,
        actualBps: Long,
        outboundFps: Double,
        bandwidthSettled: Boolean,
        staticMode: Boolean,
    ): Long {
        val current = currentBps.takeIf { it > 0L } ?: profileMaxBps
        if (staticMode || !bandwidthSettled || availableBps <= 0L) return current
        if (actualBps <= 0L || !outboundFps.isFinite() || outboundFps <= 0.0) return current
        val networkBudget = if (availableBps > Long.MAX_VALUE / NETWORK_HEADROOM_PERCENT) {
            Long.MAX_VALUE
        } else {
            availableBps * NETWORK_HEADROOM_PERCENT / 100L
        }
        if (networkBudget < MIN_VIDEO_BUDGET_BPS) return current
        return minOf(profileMaxBps, networkBudget)
    }

    fun shouldUpdate(currentBps: Long, targetBps: Long): Boolean {
        if (targetBps <= 0L || currentBps <= 0L) return true
        val delta = kotlin.math.abs(targetBps - currentBps)
        return delta >= maxOf(100_000L, currentBps * 10L / 100L)
    }
}
