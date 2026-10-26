package com.soul2soul.app.webrtc

internal object VideoActivityPolicy {
    const val ACTIVE_FPS = 30
    const val STATIC_FPS = 8
    const val STATIC_FPS_THRESHOLD = 2.0
    const val STATIC_BITRATE_THRESHOLD_BPS = 400_000L
    const val MOTION_FPS_THRESHOLD = 12.0
    const val QUIET_WINDOWS_TO_ENTER_STATIC = 3
    const val PROBE_INTERVAL_MS = 8_000L
    const val PROBE_WINDOW_MS = 2_000L

    enum class Mode {
        ACTIVE,
        STATIC,
        PROBING,
    }

    data class State(
        val mode: Mode = Mode.ACTIVE,
        val quietWindows: Int = 0,
        val nextProbeAtMs: Long = 0L,
        val probeUntilMs: Long = 0L,
    )

    data class Metrics(
        val nowMs: Long,
        val outboundFps: Double,
        val actualBitrateBps: Long,
        val constrained: Boolean,
        val controlMode: Boolean,
    )

    data class Decision(
        val targetFps: Int?,
        val state: State,
        val reason: String?,
    )

    fun step(state: State, metrics: Metrics): Decision {
        if (metrics.controlMode || metrics.constrained) {
            val target = if (state.mode == Mode.ACTIVE) null else ACTIVE_FPS
            return Decision(target, State(), "active_priority")
        }
        if (!metrics.outboundFps.isFinite() || metrics.outboundFps < 0.0) {
            return Decision(null, state, null)
        }

        val quiet = metrics.outboundFps <= STATIC_FPS_THRESHOLD ||
            (metrics.actualBitrateBps in 1..STATIC_BITRATE_THRESHOLD_BPS &&
                metrics.outboundFps > STATIC_FPS_THRESHOLD)

        return when (state.mode) {
            Mode.ACTIVE -> {
                if (quiet) {
                    val quiet = state.quietWindows + 1
                    if (quiet >= QUIET_WINDOWS_TO_ENTER_STATIC) {
                        Decision(
                            STATIC_FPS,
                            State(
                                mode = Mode.STATIC,
                                nextProbeAtMs = metrics.nowMs + PROBE_INTERVAL_MS,
                            ),
                            "static_quiet",
                        )
                    } else {
                        Decision(null, state.copy(quietWindows = quiet), null)
                    }
                } else {
                    Decision(null, state.copy(quietWindows = 0), null)
                }
            }
            Mode.STATIC -> {
                if (metrics.nowMs < state.nextProbeAtMs) {
                    Decision(null, state, null)
                } else {
                    Decision(
                        ACTIVE_FPS,
                        State(
                            mode = Mode.PROBING,
                            probeUntilMs = metrics.nowMs + PROBE_WINDOW_MS,
                        ),
                        "motion_probe",
                    )
                }
            }
            Mode.PROBING -> {
                when {
                    metrics.outboundFps >= MOTION_FPS_THRESHOLD ->
                        Decision(null, State(), "motion_detected")
                    metrics.nowMs >= state.probeUntilMs ->
                        Decision(
                            STATIC_FPS,
                            State(
                                mode = Mode.STATIC,
                                nextProbeAtMs = metrics.nowMs + PROBE_INTERVAL_MS,
                            ),
                            "probe_quiet",
                        )
                    else -> Decision(null, state, null)
                }
            }
        }
    }
}
