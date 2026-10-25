package com.soul2soul.app.webrtc

internal object RtpStatsSelector {
    data class VideoStatRef(
        val id: String,
        val localId: String? = null,
        val ssrc: Long? = null,
        val codecMimeType: String? = null,
        val frameCount: Long = -1L,
        val packetCount: Long = -1L,
        val byteCount: Long = -1L,
        val active: Boolean? = null,
    ) {
        val isRtx: Boolean
            get() = codecMimeType?.lowercase()?.endsWith("/rtx") == true

        val hasFrameCounter: Boolean
            get() = frameCount >= 0L
    }

    fun selectPrimaryVideoStats(candidates: List<VideoStatRef>): VideoStatRef? {
        val nonRtx = candidates.filterNot { it.isRtx }
        if (nonRtx.isEmpty()) return null
        return nonRtx.maxWithOrNull(
            compareBy<VideoStatRef> { it.active == true }
                .thenBy { it.hasFrameCounter }
                .thenBy { it.packetCount }
                .thenBy { it.byteCount }
                .thenBy { it.frameCount },
        )
    }

    fun selectRemoteInboundVideoStats(
        candidates: List<VideoStatRef>,
        primaryOutbound: VideoStatRef?,
    ): VideoStatRef? {
        val nonRtx = candidates.filterNot { it.isRtx }
        if (nonRtx.isEmpty()) return null
        if (primaryOutbound != null) {
            nonRtx.firstOrNull { it.localId == primaryOutbound.id }?.let { return it }
            nonRtx.firstOrNull {
                it.ssrc != null && primaryOutbound.ssrc != null && it.ssrc == primaryOutbound.ssrc
            }?.let { return it }
        }
        return selectPrimaryVideoStats(nonRtx)
    }
}
