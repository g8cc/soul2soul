package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IceRestartBudgetTest {
    @Test
    fun `单次故障最多重启三次`() {
        val budget = IceRestartBudget()
        assertEquals(1, budget.reserveAttempt())
        assertEquals(2, budget.reserveAttempt())
        assertEquals(3, budget.reserveAttempt())
        assertNull(budget.reserveAttempt())
    }

    @Test
    fun `必须稳定连接满窗口才清空重启额度`() {
        val budget = IceRestartBudget()
        budget.reserveAttempt()
        assertFalse(budget.resetIfStable(IceRestartBudget.STABLE_RESET_MS - 1))
        assertEquals(2, budget.reserveAttempt())
        assertTrue(budget.resetIfStable(IceRestartBudget.STABLE_RESET_MS))
        assertEquals(0, budget.usedAttempts)
        assertEquals(1, budget.reserveAttempt())
    }

    @Test
    fun `未消耗额度时稳定窗口不报告重置`() {
        assertFalse(IceRestartBudget().resetIfStable(IceRestartBudget.STABLE_RESET_MS))
    }

    @Test
    fun `restartIce同步失败释放本次预留额度`() {
        val budget = IceRestartBudget()
        val attempt = budget.reserveAttempt()!!

        assertTrue(budget.releaseAttempt(attempt))
        assertEquals(0, budget.usedAttempts)
        assertEquals(1, budget.reserveAttempt())
    }

    @Test
    fun `不能释放较早或不存在的额度`() {
        val budget = IceRestartBudget()
        budget.reserveAttempt()
        val latest = budget.reserveAttempt()!!

        assertFalse(budget.releaseAttempt(latest - 1))
        assertTrue(budget.releaseAttempt(latest))
        assertEquals(1, budget.usedAttempts)
    }
}
