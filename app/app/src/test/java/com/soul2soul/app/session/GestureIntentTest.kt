package com.soul2soul.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 边缘手势阈值回归锁定（0.2.18–0.2.22 真机踩坑）：
 * 宁可漏判也不误判普通滑动；0.92→0.96 收紧不许回退。
 */
class GestureIntentTest {

    private fun pt(x: Float, y: Float) = GestureIntent.NormPt(x, y)

    // ---------- BACK：左右边缘水平长滑 ----------

    @Test
    fun back_leftEdge_inclusiveBoundary() {
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.04f, 0.5f), pt(0.3f, 0.5f)), 200L)
        assertEquals(GestureIntent.GlobalAction.BACK, a)
    }

    @Test
    fun back_leftEdge_justOutside_isNull() {
        // f.x=0.041 不再贴左边缘；垂直方向也不满足 HOME 条件
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.041f, 0.5f), pt(0.3f, 0.5f)), 200L)
        assertNull(a)
    }

    @Test
    fun back_rightEdge_inclusiveBoundary() {
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.96f, 0.5f), pt(0.7f, 0.5f)), 200L)
        assertEquals(GestureIntent.GlobalAction.BACK, a)
    }

    @Test
    fun back_rightEdge_justOutside_isNull() {
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.959f, 0.5f), pt(0.7f, 0.5f)), 200L)
        assertNull(a)
    }

    @Test
    fun back_verticalDrift_justUnderLimit_stillBack() {
        // |dy| < 0.06：0.059 允许
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.02f, 0.5f), pt(0.3f, 0.559f)), 200L)
        assertEquals(GestureIntent.GlobalAction.BACK, a)
    }

    @Test
    fun back_verticalDrift_atLimit_notBack() {
        // |dy| = 0.06 严格小于才判 BACK（浮点差恰好舍入到 0.06f）
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.02f, 0.5f), pt(0.3f, 0.56f)), 200L)
        assertNull(a)
    }

    @Test
    fun back_horizontalLength_atLimit_isBack() {
        // |dx| >= 0.12 含等号
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.0f, 0.5f), pt(0.12f, 0.5f)), 200L)
        assertEquals(GestureIntent.GlobalAction.BACK, a)
    }

    @Test
    fun back_horizontalLength_justUnder_isNull() {
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.0f, 0.5f), pt(0.119f, 0.5f)), 200L)
        assertNull(a)
    }

    // ---------- HOME / RECENTS：底部边缘垂直上滑 ----------

    @Test
    fun home_fastSwipe() {
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.5f, 0.96f), pt(0.5f, 0.6f)), 399L)
        assertEquals(GestureIntent.GlobalAction.HOME, a)
    }

    @Test
    fun recents_slowSwipe_durationBoundary() {
        // dur 400ms 整即算"按住拖"
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.5f, 0.96f), pt(0.5f, 0.6f)), 400L)
        assertEquals(GestureIntent.GlobalAction.RECENTS, a)
    }

    @Test
    fun bottom_startY_justUnderThreshold_isNull() {
        // 起点 0.959 不算贴底：普通上滑不误判
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.5f, 0.959f), pt(0.5f, 0.6f)), 200L)
        assertNull(a)
    }

    @Test
    fun regression_listScrollFromBottom_notHome() {
        // 真机回归：从列表底部往上刷（起点 0.93）曾被 0.92 阈值误判成回桌面。
        // 收紧到 0.96 后必须为 null——此用例锁定不许把阈值调回去
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.5f, 0.93f), pt(0.5f, 0.3f)), 200L)
        assertNull(a)
    }

    @Test
    fun bottom_horizontalDrift_overLimit_notHome() {
        // |dx| < 0.08 才判 HOME；0.09 明确出界
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.5f, 0.96f), pt(0.59f, 0.6f)), 200L)
        assertNull(a)
    }

    @Test
    fun bottom_verticalShort_notHome() {
        // dy = -0.24 不够 -0.25
        val a = GestureIntent.edgeGlobalAction(listOf(pt(0.5f, 0.96f), pt(0.5f, 0.72f)), 200L)
        assertNull(a)
    }

    // ---------- 输入退化 ----------

    @Test
    fun singlePoint_orEmpty_isNull() {
        assertNull(GestureIntent.edgeGlobalAction(emptyList(), 200L))
        assertNull(GestureIntent.edgeGlobalAction(listOf(pt(0.5f, 0.96f)), 200L))
    }

    // ---------- BACK→HOME 守卫 ----------

    @Test
    fun resolve_backDuringOwnLiveSession_becomesHome() {
        assertEquals(
            GestureIntent.GlobalAction.HOME,
            GestureIntent.resolveGlobalAction(GestureIntent.GlobalAction.BACK, true, true),
        )
    }

    @Test
    fun resolve_backElsewhere_staysBack() {
        assertEquals(
            GestureIntent.GlobalAction.BACK,
            GestureIntent.resolveGlobalAction(GestureIntent.GlobalAction.BACK, true, false),
        )
        assertEquals(
            GestureIntent.GlobalAction.BACK,
            GestureIntent.resolveGlobalAction(GestureIntent.GlobalAction.BACK, false, true),
        )
        assertEquals(
            GestureIntent.GlobalAction.BACK,
            GestureIntent.resolveGlobalAction(GestureIntent.GlobalAction.BACK, false, false),
        )
    }

    @Test
    fun resolve_homeRecents_passThrough() {
        assertEquals(
            GestureIntent.GlobalAction.HOME,
            GestureIntent.resolveGlobalAction(GestureIntent.GlobalAction.HOME, true, true),
        )
        assertEquals(
            GestureIntent.GlobalAction.RECENTS,
            GestureIntent.resolveGlobalAction(GestureIntent.GlobalAction.RECENTS, true, true),
        )
    }
}
