package com.soul2soul.app.session

import kotlin.math.abs

/**
 * 边缘手势识别的纯逻辑（无 Android 依赖，可单测），阈值自
 * RemoteControlService.edgeGlobalAction 逐字搬移。
 * 调用方负责 PointF→NormPt 映射与 performGlobalAction 分派。
 */
object GestureIntent {

    enum class GlobalAction { BACK, HOME, RECENTS }

    /** 归一化手势点（0..1，含 letterbox 语义） */
    data class NormPt(val x: Float, val y: Float)

    /** Route Back from the local system to the remote screen using the existing gesture protocol. */
    fun remoteBackPoints(): List<NormPt> = listOf(NormPt(0.99f, 0.5f), NormPt(0.5f, 0.5f))

    /**
     * 边缘手势识别（阈值即"贴边"语义，宁可漏判也不误判普通滑动）：
     *  - 左右边缘水平长滑 → 返回
     *  - 底部边缘垂直上滑：快滑 → 回桌面，慢滑（按住拖）→ 多任务
     * 底部起点的阈值收到 0.96：0.92 会把"从列表底部往上刷"误判成回桌面
     */
    fun edgeGlobalAction(pts: List<NormPt>, durMs: Long): GlobalAction? {
        if (pts.size < 2) return null
        val f = pts.first()
        val l = pts.last()
        val dx = l.x - f.x
        val dy = l.y - f.y
        return when {
            (f.x <= 0.04f || f.x >= 0.96f) && abs(dy) < 0.06f &&
                abs(dx) >= 0.12f ->
                GlobalAction.BACK
            f.y >= 0.96f && abs(dx) < 0.08f && dy <= -0.25f ->
                if (durMs < 400L) GlobalAction.HOME else GlobalAction.RECENTS
            else -> null
        }
    }

    /**
     * BACK→HOME 守卫：返回键打在我们自己的通话界面上 = 退出会话断线（实测踩坑）。
     * 共享进行中改按 HOME：通话界面退到后台、共享继续。
     */
    fun resolveGlobalAction(
        action: GlobalAction,
        sessionLive: Boolean,
        foregroundIsOwnPkg: Boolean,
    ): GlobalAction =
        if (action == GlobalAction.BACK && sessionLive && foregroundIsOwnPkg) {
            GlobalAction.HOME
        } else {
            action
        }
}
