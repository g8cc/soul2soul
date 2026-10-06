package com.soul2soul.app.util

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/**
 * 真实显示尺寸（含状态栏/导航条/挖孔区域）。
 * resources.displayMetrics 是 app 视角，全面屏上会少一截——采集、手势注入、
 * 悬浮层都必须用真实尺寸，否则画面裁切与触点偏移同时发生。
 */
object ScreenSize {

    /** 返回 (宽, 高)，像素，竖屏坐标系 */
    @Suppress("DEPRECATION")
    fun real(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    }
}
