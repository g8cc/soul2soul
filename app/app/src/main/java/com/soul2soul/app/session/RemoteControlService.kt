package com.soul2soul.app.session

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 远程操控注入通道：观看端手势 → 本机 dispatchGesture。
 * 系统要求用户在「设置 → 无障碍」手动开启，无法程序化授权；
 * 会话内是否放行由 ScreenShareService 的「允许TA操控」开关把关。
 */
class RemoteControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "S2S-Ctl"

        @Volatile
        private var instance: RemoteControlService? = null

        /** dispatchGesture 是 API 24（本应用 minSdk 23，必须运行时判级） */
        fun isReady(): Boolean = instance != null && Build.VERSION.SDK_INT >= 24

        /**
         * 归一化手势点（0..1，含 letterbox 语义）→ 本机屏幕像素路径 → 立即注入。
         * 抬手即发、整笔一次，端到端延迟只剩网络单向 + 系统注入。
         */
        fun dispatchNormalized(pts: List<PointF>, durMs: Long): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 24 || pts.isEmpty()) return false
            // 边缘手势（侧滑返回/底部上滑回桌面·多任务）：dispatchGesture 落在
            // y=1px 也让系统边缘手势区不认，硬模仿只会画出四不像。识别意图后改走
            // performGlobalAction —— 本机播放真·系统动画，动画再随视频回显给观看端。
            edgeGlobalAction(pts, durMs)?.let { action ->
                val ok = runCatching { svc.performGlobalAction(action) }.getOrDefault(false)
                Log.d(TAG, "performGlobalAction action=$action ok=$ok")
                return ok
            }
            val dm = svc.resources.displayMetrics
            val path = Path()
            var prevX = 0f
            var prevY = 0f
            pts.forEachIndexed { i, p ->
                val x = (p.x * dm.widthPixels)
                    .coerceIn(1f, (dm.widthPixels - 1).toFloat())
                val y = (p.y * dm.heightPixels)
                    .coerceIn(1f, (dm.heightPixels - 1).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                prevX = x
                prevY = y
            }
            if (pts.size == 1) path.lineTo(prevX + 0.5f, prevY + 0.5f) // 纯点击也要有位移
            val stroke = GestureDescription.StrokeDescription(
                path, 0L, durMs.coerceIn(50L, 3000L),
            )
            val ok = runCatching {
                // 不注册回调：注入结果只记录发起，被系统打断时也不重试，避免"幽灵操作"
                svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }.getOrDefault(false)
            Log.d(TAG, "dispatchGesture pts=${pts.size} dur=$durMs ok=$ok")
            return ok
        }

        /**
         * 边缘手势识别（阈值即"贴边"语义，宁可漏判也不误判普通滑动）：
         *  - 左右边缘水平长滑 → 返回
         *  - 底部边缘垂直上滑：快滑 → 回桌面，慢滑（按住拖）→ 多任务
         */
        private fun edgeGlobalAction(pts: List<PointF>, durMs: Long): Int? {
            if (pts.size < 2) return null
            val f = pts.first()
            val l = pts.last()
            val dx = l.x - f.x
            val dy = l.y - f.y
            return when {
                (f.x <= 0.04f || f.x >= 0.96f) && kotlin.math.abs(dy) < 0.06f &&
                    kotlin.math.abs(dx) >= 0.12f ->
                    GLOBAL_ACTION_BACK
                f.y >= 0.92f && kotlin.math.abs(dx) < 0.08f && dy <= -0.25f ->
                    if (durMs < 400L) GLOBAL_ACTION_HOME else GLOBAL_ACTION_RECENTS
                else -> null
            }
        }
    }
}
