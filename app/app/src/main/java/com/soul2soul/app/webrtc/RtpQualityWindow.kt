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
}
