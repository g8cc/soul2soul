package com.soul2soul.app.webrtc

/** Per-call restart guard; a sufficiently long healthy connection earns a fresh budget. */
internal class IceRestartBudget(private val maxAttempts: Int = MAX_ATTEMPTS) {
    private var attempts = 0
    val usedAttempts: Int get() = synchronized(this) { attempts }

    @Synchronized
    fun reserveAttempt(): Int? {
        if (attempts >= maxAttempts) return null
        attempts += 1
        return attempts
    }

    /** Returns true only when a consumed budget was reset after a stable connection window. */
    @Synchronized
    fun resetIfStable(connectedDurationMs: Long): Boolean {
        if (attempts == 0 || connectedDurationMs < STABLE_RESET_MS) return false
        attempts = 0
        return true
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val STABLE_RESET_MS = 30_000L
    }
}
