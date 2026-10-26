package com.soul2soul.app.webrtc

/** Converts cumulative WebRTC RTP counters into metrics for one diagnostic interval. */
internal object RtpQualityWindow {
    data class Counters(
        val packetsLost: Long = -1L,
        val packetsReceived: Long = -1L,
        val jitterSeconds: Double = -1.0,
        val concealedSamples: Long = -1L,
    )

    data class Metrics(
        /** -1 means that this interval could not be measured (first sample, reset, or missing stats). */
        val packetLossPercent: Double,
        val jitterMs: Double,
        val concealedMs: Double,
    )

    fun between(previous: Counters?, current: Counters?): Metrics {
        if (current == null) return unavailable()
        val lossPercent = if (
            previous != null && previous.packetsLost >= 0 && current.packetsLost >= previous.packetsLost &&
            previous.packetsReceived >= 0 && current.packetsReceived >= previous.packetsReceived
        ) {
            val lost = current.packetsLost - previous.packetsLost
            val received = current.packetsReceived - previous.packetsReceived
            val total = lost + received
            if (total > 0) lost * 100.0 / total else -1.0
        } else {
            -1.0
        }
        val concealedMs = if (
            previous != null && previous.concealedSamples >= 0 &&
            current.concealedSamples >= previous.concealedSamples
        ) {
            // WebRTC audio RTP is decoded at 48 kHz; 48 samples represent one millisecond.
            (current.concealedSamples - previous.concealedSamples) / 48.0
        } else {
            -1.0
        }
        val jitterMs = current.jitterSeconds
            .takeIf { it.isFinite() && it >= 0.0 }
            ?.times(1000.0) ?: -1.0
        return Metrics(lossPercent, jitterMs, concealedMs)
    }

    private fun unavailable() = Metrics(-1.0, -1.0, -1.0)

    /** 短于这个窗口的帧计数差算不出有意义的帧率（2s 轮询抖动会把它放大成 ±30%）。 */
    private const val MIN_FPS_WINDOW_MS = 1000L

    /**
     * 区间帧率。Android 版 libwebrtc 的 `outbound-rtp`/`inbound-rtp` **不提供**
     * `framesPerSecond`（v0.2.62 双模拟器实测 `videoFps=x`），所以帧率只能由累计
     * `framesSent`/`framesReceived` 除以这段区间的真实时长得到。
     *
     * 返回 -1 = **这一段没读数**（缺计数、计数回退、或窗口太短）。调用方必须把 -1 当
     * "测不到"而不是"0 帧"：把"读不到"判成"卡死"就是线上那 6 条假 `video_stall`
     * （`ice=CONNECTED`、`rttMs=84/59/91`、链路明明是好的）的唯一成因。
     */
    fun framesFps(previousFrames: Long, currentFrames: Long, elapsedMs: Long): Double {
        if (previousFrames < 0 || currentFrames < 0) return -1.0
        if (currentFrames < previousFrames) return -1.0
        if (elapsedMs < MIN_FPS_WINDOW_MS) return -1.0
        return (currentFrames - previousFrames) * 1000.0 / elapsedMs
    }

    fun bitrateBps(previousBytes: Long, currentBytes: Long, elapsedMs: Long): Long {
        if (previousBytes < 0 || currentBytes < 0 || currentBytes < previousBytes) return -1L
        if (elapsedMs < MIN_FPS_WINDOW_MS) return -1L
        val deltaBytes = currentBytes - previousBytes
        val bitsPerSecond = deltaBytes.toDouble() * 8_000.0 / elapsedMs.toDouble()
        if (!bitsPerSecond.isFinite() || bitsPerSecond < 0.0) return -1L
        return if (bitsPerSecond >= Long.MAX_VALUE.toDouble()) {
            Long.MAX_VALUE
        } else {
            bitsPerSecond.toLong()
        }
    }
}
