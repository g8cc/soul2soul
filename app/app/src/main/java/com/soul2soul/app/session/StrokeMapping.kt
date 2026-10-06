package com.soul2soul.app.session

import kotlin.math.abs

/**
 * 观看端/共享端笔迹坐标映射的纯逻辑（SPEC §5），无 Android 依赖，可单测。
 * 视频帧与共享端屏幕是同一内容：观看端把触点归一化到视频显示矩形，
 * 共享端按实际显示尺寸还原，与两端分辨率/宽高比无关。
 */
object StrokeMapping {

    /** 视频按 SCALE_ASPECT_FIT 在容器内的显示矩形 [left, top, right, bottom] */
    fun fitRect(videoW: Int, videoH: Int, viewW: Float, viewH: Float): FloatArray {
        if (videoW <= 0 || videoH <= 0 || viewW <= 0f || viewH <= 0f) {
            return floatArrayOf(0f, 0f, viewW, viewH)
        }
        val videoAspect = videoW.toFloat() / videoH
        val viewAspect = viewW / viewH
        return if (videoAspect > viewAspect) {
            val h = viewW / videoAspect
            floatArrayOf(0f, (viewH - h) / 2f, viewW, (viewH + h) / 2f)
        } else {
            val w = viewH * videoAspect
            floatArrayOf((viewW - w) / 2f, 0f, (viewW + w) / 2f, viewH)
        }
    }

    /** 视图内触点 → 归一化坐标（相对视频帧矩形），越界钳制到 [0,1] */
    fun normalize(
        touchX: Float,
        touchY: Float,
        videoW: Int,
        videoH: Int,
        viewW: Float,
        viewH: Float,
    ): Pair<Float, Float> {
        val r = fitRect(videoW, videoH, viewW, viewH)
        val rw = r[2] - r[0]
        val rh = r[3] - r[1]
        if (rw <= 0f || rh <= 0f) return 0f to 0f
        val xn = ((touchX - r[0]) / rw).coerceIn(0f, 1f)
        val yn = ((touchY - r[1]) / rh).coerceIn(0f, 1f)
        return xn to yn
    }

    /** 共享端：归一化坐标 → 本机屏幕像素 */
    fun denormalize(xn: Float, yn: Float, screenW: Float, screenH: Float): Pair<Float, Float> =
        (xn.coerceIn(0f, 1f) * screenW) to (yn.coerceIn(0f, 1f) * screenH)

    /** 无效浮点坐标拒绝，有限坐标钳制；统一用于边缘手势判定和像素注入。 */
    fun normalizedOrNull(value: Float): Float? =
        value.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

    /** 验收容差（PRD AC-3: 位置偏差 ≤ 画面宽度 5%） */
    fun withinTolerance(a: Pair<Float, Float>, b: Pair<Float, Float>, tolerance: Float = 0.05f): Boolean =
        abs(a.first - b.first) <= tolerance && abs(a.second - b.second) <= tolerance
}
