package com.soul2soul.app.session

import com.soul2soul.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操控授权状态机用例：锁定 0.2.18–0.2.22 真机踩过的坑
 * （乐观写回滚、锁屏收回、节流、入队竞态、跨会话残留）。
 */
class CtlConsentMachineTest {

    private var now = 10_000L
    private fun machine() = CtlConsentMachine { now }
    private fun advance(ms: Long) { now += ms }

    private fun List<CtlConsentMachine.Effect>.toastRes(): Int? =
        filterIsInstance<CtlConsentMachine.Effect.Toast>().firstOrNull()?.res

    @Test
    fun `toggle 开启：无锁屏时给出启用提示并刷新通知`() {
        val m = machine()
        val effects = m.toggle(accReady = true)
        assertTrue(m.allowed)
        assertEquals(R.string.ctl_enabled_toast, effects.toastRes())
        assertTrue(CtlConsentMachine.Effect.RefreshNotification in effects)
        assertFalse(CtlConsentMachine.Effect.DeniedNotice in effects)
    }

    @Test
    fun `toggle 关闭：立刻回灌被拒事件让观看端知道原因`() {
        val m = machine()
        m.toggle(accReady = true)
        advance(5_000) // 越过 toast 节流窗，保证断言的是本条
        val effects = m.toggle(accReady = true)
        assertFalse(m.allowed)
        assertTrue(CtlConsentMachine.Effect.DeniedNotice in effects)
    }

    @Test
    fun `未授权且无障碍未开：只提示缺无障碍不改状态`() {
        val m = machine()
        val effects = m.toggle(accReady = false)
        assertFalse(m.allowed)
        assertEquals(R.string.ctl_need_acc, effects.toastRes())
        assertFalse(CtlConsentMachine.Effect.RefreshNotification in effects)
    }

    @Test
    fun `对话框乐观写被服务拒绝必须落回 false（状态与实况不符是事故源）`() {
        val m = machine()
        m.optimisticSet(true) // CtlConsentActivity 先乐观置 true
        val effects = m.setTarget(on = true, accReady = false)
        assertFalse(m.allowed)
        assertEquals(R.string.ctl_need_acc, effects.toastRes())
        assertFalse(CtlConsentMachine.Effect.RefreshNotification in effects)
    }

    @Test
    fun `setTarget 关闭：回灌被拒 + 刷新通知`() {
        val m = machine()
        m.setTarget(on = true, accReady = true)
        advance(5_000)
        val effects = m.setTarget(on = false, accReady = true)
        assertFalse(m.allowed)
        assertTrue(CtlConsentMachine.Effect.DeniedNotice in effects)
        assertTrue(CtlConsentMachine.Effect.RefreshNotification in effects)
    }

    @Test
    fun `锁屏收回：已授权才发 screenoff 被拒，未授权不重复发`() {
        val m = machine()
        assertTrue(m.revokeOnScreenOff().isEmpty()) // 本来就未授权：静默
        m.optimisticSet(true)
        val effects = m.revokeOnScreenOff()
        assertFalse(m.allowed)
        assertTrue(CtlConsentMachine.Effect.DeniedScreenOff in effects)
        assertTrue(CtlConsentMachine.Effect.RefreshNotification in effects)
        advance(5_000)
        assertTrue(m.revokeOnScreenOff().isEmpty()) // 再次锁屏：已是 false，不重复广播
    }

    @Test
    fun `toast 节流边界：2999ms 压住不发，3000ms 放行`() {
        val m = machine()
        assertEquals(1, m.toggle(accReady = false).size) // 第一次发 Toast
        advance(CtlConsentMachine.TOAST_THROTTLE_MS - 1)
        assertTrue(m.toggle(accReady = false).isEmpty()) // 2999：仍在窗口内
        advance(1) // 恰好 3000
        assertEquals(R.string.ctl_need_acc, m.toggle(accReady = false).toastRes())
    }

    @Test
    fun `被拒回灌节流边界：3999ms 压住，4000ms 放行`() {
        val m = machine()
        val first = m.heldCheck() // 未授权 → Deny + DeniedNotice
        assertTrue(first is CtlConsentMachine.Decision.Deny)
        assertTrue((first as CtlConsentMachine.Decision.Deny).effects.contains(CtlConsentMachine.Effect.DeniedNotice))
        advance(CtlConsentMachine.DENY_THROTTLE_MS - 1)
        val second = m.heldCheck() as CtlConsentMachine.Decision.Deny
        assertTrue(second.effects.isEmpty()) // 3999：4s 窗内不刷屏
        advance(1) // 恰好 4000
        val third = m.heldCheck() as CtlConsentMachine.Decision.Deny
        assertTrue(third.effects.contains(CtlConsentMachine.Effect.DeniedNotice))
    }

    @Test
    fun `入队竞态：授权后入队、注入前被收回则拒绝`() {
        val m = machine()
        m.setTarget(on = true, accReady = true)
        assertTrue(m.heldCheck() is CtlConsentMachine.Decision.Allow) // 第一道判：放行入队
        m.revokeOnScreenOff() // 排队期间锁屏收回
        advance(5_000)
        val pre = m.preInjectCheck(accReady = true)
        assertTrue(pre is CtlConsentMachine.Decision.Deny) // 第二道判：必须拦下
    }

    @Test
    fun `无障碍中途消失：收回授权并提示+回灌被拒`() {
        val m = machine()
        m.setTarget(on = true, accReady = true)
        advance(5_000)
        val pre = m.preInjectCheck(accReady = false)
        assertFalse(m.allowed)
        val effects = (pre as CtlConsentMachine.Decision.Deny).effects
        assertEquals(R.string.ctl_need_acc, effects.toastRes())
        assertTrue(CtlConsentMachine.Effect.DeniedNotice in effects)
    }

    @Test
    fun `preInjectCheck 全就绪时放行且不改状态`() {
        val m = machine()
        m.setTarget(on = true, accReady = true)
        assertTrue(m.preInjectCheck(accReady = true) is CtlConsentMachine.Decision.Allow)
        assertTrue(m.allowed)
    }

    @Test
    fun `新会话归零：上一通话的授权绝不能带进这一通`() {
        val m = machine()
        m.setTarget(on = true, accReady = true)
        m.resetForNewSession()
        assertFalse(m.allowed)
    }

    @Test
    fun `节流窗口锁定的就是线上常量 3s 与 4s`() {
        assertEquals(3000L, CtlConsentMachine.TOAST_THROTTLE_MS)
        assertEquals(4000L, CtlConsentMachine.DENY_THROTTLE_MS)
    }
}
