package com.soul2soul.app.webrtc

import java.util.Locale

internal object VideoCodecPolicy {
    const val VP9 = "VP9"
    const val H264 = "H264"
    const val VP8 = "VP8"

    private val screenPriority = listOf(VP9, H264, VP8)

    data class Selection(
        val orderedNames: List<String>,
        val preferredName: String?,
        val preferredHardware: Boolean,
        val hardwareNames: List<String>,
    )

    fun select(
        allCodecNames: Collection<String>,
        hardwareCodecNames: Collection<String>,
    ): Selection {
        val all = canonicalNames(allCodecNames)
        val hardware = canonicalNames(hardwareCodecNames).filter { it in all }
        val orderedKnown = screenPriority.filter { it in all }
            .sortedWith(compareBy { if (it in hardware) 0 else 1 })
        val ordered = (orderedKnown + all.filterNot { it in orderedKnown }).distinct()
        val preferred = screenPriority.firstOrNull { it in hardware }
        return Selection(
            orderedNames = ordered,
            preferredName = preferred,
            preferredHardware = preferred != null && preferred in hardware,
            hardwareNames = hardware,
        )
    }

    fun canonicalName(name: String): String =
        name.substringAfterLast('/').trim().uppercase(Locale.US)

    private fun canonicalNames(names: Collection<String>): List<String> =
        names.asSequence()
            .map(::canonicalName)
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
}
