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
            // 先校验/钳制，再做边缘手势判定和像素换算；否则 NaN/Infinity 可能绕过
            // 普通路径检查，导致无效坐标仍被识别成系统级 BACK/HOME 手势。
            val normalized = pts.mapNotNull { p ->
                val xn = StrokeMapping.normalizedOrNull(p.x) ?: return@mapNotNull null
                val yn = StrokeMapping.normalizedOrNull(p.y) ?: return@mapNotNull null
                xn to yn
            }
            if (normalized.isEmpty()) return false
            val norm = normalized.map { (x, y) -> GestureIntent.NormPt(x, y) }
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
            // 真实显示尺寸：dispatchGesture 的坐标系是全屏幕，用 app 视角 dm 会整体偏小、底部点不到
            val (screenW, screenH) = com.soul2soul.app.util.ScreenSize.real(svc)
            val path = Path()
            var prevX = 0f
            var prevY = 0f
            normalized.forEachIndexed { i, (xn, yn) ->
                val (rawX, rawY) = StrokeMapping.denormalize(
                    xn, yn, screenW.toFloat(), screenH.toFloat()
                )
                val x = rawX.coerceIn(1f, (screenW - 1).coerceAtLeast(1).toFloat())
                val y = rawY.coerceIn(1f, (screenH - 1).coerceAtLeast(1).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                prevX = x
                prevY = y
            }
            if (normalized.size == 1) {
                // 纯点击也要有非零路径；在右边缘向左补点，避免钳制后仍是零长度。
                val maxX = (screenW - 1).coerceAtLeast(1).toFloat()
                val clickEndX = if (prevX + 0.5f <= maxX) prevX + 0.5f else prevX - 0.5f
                path.lineTo(clickEndX.coerceIn(1f, maxX), prevY)
            }
            val stroke = GestureDescription.StrokeDescription(
                path, 0L, durMs.coerceIn(50L, 3000L),
            )
            val ok = runCatching {
                // 不注册回调：注入结果只记录发起，被系统打断时也不重试，避免"幽灵操作"
                svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }.getOrDefault(false)
            Log.d(TAG, "dispatchGesture pts=${normalized.size}/${pts.size} dur=$durMs display=${screenW}x${screenH} ok=$ok")
            return ok
        }
    }
}
