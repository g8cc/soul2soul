package com.soul2soul.app.msg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知栏常驻条数的决策表（锁定 v0.2.31 需求：静音常驻、数字变化才刷新、读完消失）。
 */
class UnreadNotifierTest {

    @Test
    fun `没显示且没未读-不动作`() {
        assertEquals(UnreadNotifier.Action.Keep, UnreadNotifier.plan(shownCount = 0, unread = 0))
    }

    @Test
    fun `首次出现未读-显示`() {
        assertEquals(UnreadNotifier.Action.Show, UnreadNotifier.plan(shownCount = 0, unread = 1))
        assertEquals(UnreadNotifier.Action.Show, UnreadNotifier.plan(shownCount = 0, unread = 5))
    }

    @Test
    fun `条数增加-刷新`() {
        assertEquals(UnreadNotifier.Action.Show, UnreadNotifier.plan(shownCount = 2, unread = 3))
    }

    @Test
    fun `条数不变-不重复刷`() {
        assertEquals(UnreadNotifier.Action.Keep, UnreadNotifier.plan(shownCount = 3, unread = 3))
    }

    @Test
    fun `读完-收起通知`() {
        assertEquals(UnreadNotifier.Action.Hide, UnreadNotifier.plan(shownCount = 3, unread = 0))
    }

    @Test
    fun `显示归零后再次清空-不动作`() {
        // Hide 之后 shownCount 已复位为 0，重复的 0→0 不得再触发 cancel
        assertEquals(UnreadNotifier.Action.Keep, UnreadNotifier.plan(shownCount = 0, unread = 0))
    }
}
