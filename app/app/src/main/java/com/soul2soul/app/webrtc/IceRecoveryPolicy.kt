package com.soul2soul.app.webrtc

/** ICE 进入 FAILED 后的恢复决策；具体副作用仍由 WebRtcClient 执行。 */
internal object IceRecoveryPolicy {
    data class Decision(
        val restartNow: Boolean,
        val armRecoveryTimeout: Boolean,
    )

    fun onFailed(established: Boolean, isOfferer: Boolean): Decision = Decision(
        restartNow = established && isOfferer,
        armRecoveryTimeout = established,
    )
}
