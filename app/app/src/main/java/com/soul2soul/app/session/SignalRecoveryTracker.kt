package com.soul2soul.app.session

class SignalRecoveryTracker(
    private val graceMs: Long = DEFAULT_GRACE_MS,
) {
    init {
        require(graceMs > 0L)
    }

    enum class Source { LOCAL, PEER }

    sealed class Decision {
        object None : Decision()
        data class Started(val source: Source, val deadlineMs: Long?) : Decision()
        data class Restored(val source: Source) : Decision()
        data class EndNow(val source: Source) : Decision()
        data class TimedOut(val source: Source) : Decision()
    }

    var localOnline: Boolean = true
        private set
    var peerOnline: Boolean = true
        private set
    var isRecovering: Boolean = false
        private set

    private var localDeadlineMs = 0L
    private var peerDeadlineMs = 0L

    fun onLocalSignal(online: Boolean, nowMs: Long, iceState: String): Decision =
        updateLocal(online, nowMs, iceState)

    fun onPeerSignal(online: Boolean, nowMs: Long, iceState: String): Decision =
        updatePeer(online, nowMs, iceState)

    fun onTimeout(nowMs: Long): Decision {
        val expired = listOf(
            Source.LOCAL to localDeadlineMs,
            Source.PEER to peerDeadlineMs,
        ).filter { (source, deadline) ->
            deadline > 0L && nowMs >= deadline && when (source) {
                Source.LOCAL -> !localOnline
                Source.PEER -> !peerOnline
            }
        }.minByOrNull { it.second }
        if (expired != null) {
            localDeadlineMs = 0L
            peerDeadlineMs = 0L
            isRecovering = false
            return Decision.TimedOut(expired.first)
        }
        return Decision.None
    }

    fun deadlineMs(): Long? = listOf(localDeadlineMs, peerDeadlineMs)
        .filter { it > 0L }
        .minOrNull()

    fun recoverySource(): Source? {
        if (!isRecovering) return null
        if (!localOnline && !peerOnline) {
            return if (localDeadlineMs <= peerDeadlineMs) Source.LOCAL else Source.PEER
        }
        return when {
            !peerOnline -> Source.PEER
            !localOnline -> Source.LOCAL
            else -> null
        }
    }

    fun reset() {
        localOnline = true
        peerOnline = true
        isRecovering = false
        localDeadlineMs = 0L
        peerDeadlineMs = 0L
    }

    private fun updateLocal(online: Boolean, nowMs: Long, iceState: String): Decision {
        val changed = localOnline != online
        val wasRecovering = isRecovering
        localOnline = online
        if (!online) {
            if (iceState in TERMINAL_ICE_STATES) return endNow(Source.LOCAL)
            if (changed) localDeadlineMs = nowMs + graceMs
            if (localDeadlineMs == 0L) localDeadlineMs = nowMs + graceMs
            isRecovering = true
            return if (changed && !wasRecovering) {
                Decision.Started(Source.LOCAL, localDeadlineMs)
            } else {
                Decision.None
            }
        }
        localDeadlineMs = 0L
        if (online) {
            val wasRecovering = isRecovering
            isRecovering = !peerOnline
            return if (changed && wasRecovering && !isRecovering) {
                Decision.Restored(Source.LOCAL)
            } else {
                Decision.None
            }
        }
        return Decision.None
    }

    private fun updatePeer(online: Boolean, nowMs: Long, iceState: String): Decision {
        val changed = peerOnline != online
        val wasRecovering = isRecovering
        peerOnline = online
        if (!online) {
            if (iceState in TERMINAL_ICE_STATES) return endNow(Source.PEER)
            if (changed) peerDeadlineMs = nowMs + graceMs
            if (peerDeadlineMs == 0L) peerDeadlineMs = nowMs + graceMs
            isRecovering = true
            return if (changed && !wasRecovering) {
                Decision.Started(Source.PEER, peerDeadlineMs)
            } else {
                Decision.None
            }
        }
        peerDeadlineMs = 0L
        if (online) {
            isRecovering = !localOnline
            return if (changed && wasRecovering && !isRecovering) {
                Decision.Restored(Source.PEER)
            } else {
                Decision.None
            }
        }
        return Decision.None
    }

    private fun endNow(source: Source): Decision {
        isRecovering = false
        localDeadlineMs = 0L
        peerDeadlineMs = 0L
        return Decision.EndNow(source)
    }

    companion object {
        const val DEFAULT_GRACE_MS = 20_000L
        private val TERMINAL_ICE_STATES = setOf("FAILED", "CLOSED")
    }
}
