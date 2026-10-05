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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 追踪前台包名（配置已订阅 typeWindowStateChanged）：供"返回键打在自己通话界面上"的保护用
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPkg = event.packageName?.toString()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "S2S-Ctl"

        @Volatile
        private var instance: RemoteControlService? = null

        @Volatile
        private var lastForegroundPkg: String? = null

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
            // 阈值判定在 GestureIntent（纯逻辑，单测锁定）。
            val norm = pts.map { GestureIntent.NormPt(it.x, it.y) }
            GestureIntent.edgeGlobalAction(norm, durMs)?.let { action ->
                // 返回键打在我们自己的通话界面上 = 退出会话断线（实测踩坑）。
                // 共享进行中改按 HOME：通话界面退到后台、共享继续，她立刻落到桌面接着操作
                val resolved = GestureIntent.resolveGlobalAction(
                    action, ScreenShareService.isLive(), lastForegroundPkg == svc.packageName
                )
                val target = when (resolved) {
                    GestureIntent.GlobalAction.BACK -> GLOBAL_ACTION_BACK
                    GestureIntent.GlobalAction.HOME -> GLOBAL_ACTION_HOME
                    GestureIntent.GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
                }
                val ok = runCatching { svc.performGlobalAction(target) }.getOrDefault(false)
                Log.d(TAG, "performGlobalAction action=$target (raw=$action) ok=$ok")
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
    }
}
