package com.soul2soul.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRecoveryTrackerTest {
    @Test
    fun `peer signal loss gets a grace period and recovery cancels teardown`() {
        val tracker = SignalRecoveryTracker(graceMs = 20_000L)

        val started = tracker.onPeerSignal(online = false, nowMs = 1_000L, iceState = "CONNECTED")
        assertEquals(SignalRecoveryTracker.Decision.Started(SignalRecoveryTracker.Source.PEER, 21_000L), started)
        assertTrue(tracker.isRecovering)
        assertEquals(SignalRecoveryTracker.Decision.None, tracker.onTimeout(20_999L))
        assertEquals(
            SignalRecoveryTracker.Decision.Restored(SignalRecoveryTracker.Source.PEER),
            tracker.onPeerSignal(online = true, nowMs = 5_000L, iceState = "CONNECTED"),
        )
        assertFalse(tracker.isRecovering)
        assertEquals(SignalRecoveryTracker.Decision.None, tracker.onTimeout(30_000L))
    }

    @Test
    fun `both local and peer signal legs must recover before clearing degradation`() {
        val tracker = SignalRecoveryTracker(graceMs = 20_000L)

        tracker.onLocalSignal(online = false, nowMs = 100L, iceState = "CONNECTED")
        assertEquals(SignalRecoveryTracker.Decision.None,
            tracker.onPeerSignal(online = false, nowMs = 200L, iceState = "CONNECTED"))
        assertEquals(SignalRecoveryTracker.Decision.None,
            tracker.onLocalSignal(online = true, nowMs = 500L, iceState = "CONNECTED"))
        assertTrue(tracker.isRecovering)
        assertEquals(SignalRecoveryTracker.Decision.Restored(SignalRecoveryTracker.Source.PEER),
            tracker.onPeerSignal(online = true, nowMs = 700L, iceState = "CONNECTED"))
        assertFalse(tracker.isRecovering)
    }

    @Test
    fun `terminal ice state ends immediately instead of waiting for signal grace`() {
        val tracker = SignalRecoveryTracker()

        assertEquals(
            SignalRecoveryTracker.Decision.EndNow(SignalRecoveryTracker.Source.PEER),
            tracker.onPeerSignal(online = false, nowMs = 100L, iceState = "FAILED"),
        )
        assertFalse(tracker.isRecovering)
    }

    @Test
    fun `timeout ends only after the deadline`() {
        val tracker = SignalRecoveryTracker(graceMs = 2_000L)

        tracker.onLocalSignal(online = false, nowMs = 10_000L, iceState = "DISCONNECTED")
        assertEquals(SignalRecoveryTracker.Decision.None, tracker.onTimeout(11_999L))
        assertEquals(
            SignalRecoveryTracker.Decision.TimedOut(SignalRecoveryTracker.Source.LOCAL),
            tracker.onTimeout(12_000L),
        )
        assertFalse(tracker.isRecovering)
    }

    @Test
    fun `simultaneous losses expire at the earliest deadline`() {
        val tracker = SignalRecoveryTracker(graceMs = 2_000L)

        tracker.onLocalSignal(online = false, nowMs = 100L, iceState = "CONNECTED")
        tracker.onPeerSignal(online = false, nowMs = 500L, iceState = "CONNECTED")

        assertEquals(2_100L, tracker.deadlineMs())
        assertEquals(
            SignalRecoveryTracker.Decision.TimedOut(SignalRecoveryTracker.Source.LOCAL),
            tracker.onTimeout(2_100L),
        )
        assertFalse(tracker.localOnline)
        assertFalse(tracker.peerOnline)
        assertFalse(tracker.isRecovering)
        assertEquals(null, tracker.deadlineMs())
    }

    @Test
    fun `restoring one leg keeps the other leg recovery timer active`() {
        val tracker = SignalRecoveryTracker(graceMs = 2_000L)

        tracker.onLocalSignal(online = false, nowMs = 100L, iceState = "CONNECTED")
        tracker.onPeerSignal(online = false, nowMs = 200L, iceState = "CONNECTED")
        assertEquals(
            SignalRecoveryTracker.Decision.None,
            tracker.onLocalSignal(online = true, nowMs = 300L, iceState = "CONNECTED"),
        )

        assertTrue(tracker.localOnline)
        assertFalse(tracker.peerOnline)
        assertTrue(tracker.isRecovering)
        assertEquals(2_200L, tracker.deadlineMs())
        assertEquals(
            SignalRecoveryTracker.Decision.TimedOut(SignalRecoveryTracker.Source.PEER),
            tracker.onTimeout(2_200L),
        )
        assertFalse(tracker.peerOnline)
        assertFalse(tracker.isRecovering)
    }

    @Test
    fun `active recovery source follows the leg that is still offline`() {
        val tracker = SignalRecoveryTracker(graceMs = 2_000L)

        tracker.onLocalSignal(online = false, nowMs = 100L, iceState = "CONNECTED")
        tracker.onPeerSignal(online = false, nowMs = 200L, iceState = "CONNECTED")
        assertEquals(SignalRecoveryTracker.Source.LOCAL, tracker.recoverySource())

        tracker.onPeerSignal(online = true, nowMs = 300L, iceState = "CONNECTED")
        assertEquals(SignalRecoveryTracker.Source.LOCAL, tracker.recoverySource())

        tracker.onLocalSignal(online = true, nowMs = 400L, iceState = "CONNECTED")
        assertEquals(null, tracker.recoverySource())
    }

    @Test
    fun `closed ice state ends immediately`() {
        val tracker = SignalRecoveryTracker()

        assertEquals(
            SignalRecoveryTracker.Decision.EndNow(SignalRecoveryTracker.Source.LOCAL),
            tracker.onLocalSignal(online = false, nowMs = 100L, iceState = "CLOSED"),
        )
        assertFalse(tracker.localOnline)
        assertFalse(tracker.isRecovering)
        assertEquals(null, tracker.deadlineMs())
    }
}
