package com.soul2soul.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手势延迟遥测分段账目锁定（v0.2.40）：排队/回放/溢出三段不能互相污染 */
class CtlLatencyTelemetryTest {

    private var now = 1000L
    private val tele = CtlLatencyTelemetry { now }

    @Test
    fun `三段拆分_排队_回放_系统溢出`() {
        // recv=1000, dispatch=1040（主线程排队40）, play=120, done=1220（溢出60）
        tele.onStrokeAccepted()
        val s = tele.onStrokeSettled(recvAt = 1000L, dispatchedAt = 1040L, doneAt = 1220L, playMs = 120L)
        assertEquals(40L, s.queueWaitMs)
        assertEquals(220L, s.totalMs)
        assertEquals(120L, s.playMs)
        assertEquals(60L, s.overflowMs)
        assertEquals(0, s.inflight)
    }

    @Test
    fun `未完成的手势计入inflight`() {
        tele.onStrokeAccepted()
        tele.onStrokeAccepted()
        val s = tele.onStrokeSettled(recvAt = 1000L, dispatchedAt = 1000L, doneAt = 1100L, playMs = 100L)
        assertEquals(1, s.inflight) // 还有一笔压在系统队列里
        tele.onStrokeAccepted()
        val s2 = tele.onStrokeSettled(recvAt = 1000L, dispatchedAt = 1000L, doneAt = 1300L, playMs = 100L)
        assertEquals(1, s2.inflight)
    }

    @Test
    fun `时钟回拨不产生负数账`() {
        tele.onStrokeAccepted()
        val s = tele.onStrokeSettled(recvAt = 5000L, dispatchedAt = 1000L, doneAt = 1000L, playMs = 900L)
        assertEquals(0L, s.queueWaitMs)
        assertEquals(0L, s.totalMs)
        assertEquals(0L, s.overflowMs)
    }

    @Test
    fun `日志按1秒节流`() {
        assertTrue(tele.shouldLog(1000L))
        assertFalse(tele.shouldLog(1500L))
        assertTrue(tele.shouldLog(2001L))
    }

    @Test
    fun `新会话归零_inflight不跨通话`() {
        tele.onStrokeAccepted()
        tele.onStrokeAccepted()
        tele.reset()
        tele.onStrokeAccepted()
        val s = tele.onStrokeSettled(recvAt = 1000L, dispatchedAt = 1000L, doneAt = 1000L, playMs = 0L)
        assertEquals(0, s.inflight) // reset 后只剩这一笔自己
    }

    @Test
    fun `多余的结算不扣成负数`() {
        tele.onStrokeSettled(recvAt = 1000L, dispatchedAt = 1000L, doneAt = 1000L, playMs = 0L)
        tele.onStrokeAccepted()
        val s = tele.onStrokeSettled(recvAt = 1000L, dispatchedAt = 1000L, doneAt = 1000L, playMs = 0L)
        assertEquals(0, s.inflight)
    }
}
