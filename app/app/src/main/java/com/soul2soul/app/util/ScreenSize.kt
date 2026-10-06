package com.soul2soul.app.util

import android.content.Context
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 真实显示尺寸（含状态栏/导航条/挖孔区域）。
 *
 * 这里不能用 [WindowManager.currentWindowMetrics]：Android 11+ 这个 API 返回的是
 * 当前窗口的 bounds。对 Activity/Service/无障碍服务而言它可能是扣掉状态栏和导航栏
 * 的应用窗口，甚至在分屏时只是窗口子矩形；而 [android.accessibilityservice.AccessibilityService]
 * 的 [android.accessibilityservice.AccessibilityService.dispatchGesture] 坐标原点是整块
 * 物理显示屏左上角。两者混用会形成稳定的纵向偏移（典型表现就是“总点在上面”）。
 *
 * [android.view.Display.getRealSize] 明确保证不扣除系统装饰，且与手势注入坐标系、
 * MediaProjection 的显示坐标系一致。它虽在新 API 上标为 deprecated，但在 minSdk 23
 * 的跨版本场景反而是这里最明确、最稳定的契约。
 */
object ScreenSize {

    /** 返回当前旋转下的 (宽, 高)，像素；不是 dp，也不是应用内容区尺寸。 */
    @Suppress("DEPRECATION")
    fun real(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay

        // getRealSize 是 GestureDescription 使用的全屏像素坐标系。部分 ROM 在显示器
        // 刚旋转/刚亮屏的瞬间可能返回 0，此时再用 getRealMetrics/资源尺寸兜底，避免
        // 生成不可用的 0×0 采集或手势路径。
        val p = Point()
        runCatching { display.getRealSize(p) }
        if (p.x > 0 && p.y > 0) return p.x to p.y

        val metrics = DisplayMetrics()
        runCatching { display.getRealMetrics(metrics) }
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels to metrics.heightPixels
        }

        val fallback = context.resources.displayMetrics
        return fallback.widthPixels.coerceAtLeast(1) to fallback.heightPixels.coerceAtLeast(1)
    }
}
