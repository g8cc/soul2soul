package com.soul2soul.app.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import org.json.JSONObject

/** 笔迹颜色（观看端/共享端共用） */
object StrokeColors {
    val COLORS = intArrayOf(
        Color.parseColor("#FF3B5C"), // 粉红
        Color.parseColor("#34C759"), // 绿
        Color.parseColor("#1E90FF"), // 蓝
    )
    const val FADE_MS = 2000L
}

/**
 * 共享端悬浮标注层（懒加载窗口内的绘制内容）：
 *  - 笔迹（激光笔，2 秒淡出）
 *  - 表情互动（观看端发来的 emoji，从下方飘起并放大淡出——直播式互动）
 * 该窗口 FLAG_NOT_TOUCHABLE，不干扰共享端任何操作；内容会被屏幕采集进视频流，
 * 观看端由此看到自己的笔迹/表情在对方屏幕上生效。
 */
class OverlayCanvasView(context: Context) : View(context) {

    private class Stroke(
        val id: String,
        val color: Int,
        val bornAt: Long,
    ) {
        val points = mutableListOf<PointF>()

        /** 收笔时刻（0 = 还在书写中）。淡出从收笔才开始计——网络延迟不影响显示 */
        var endAt = 0L

        /** 最后一个笔点的到达时刻：收笔信号丢失时的兜底依据 */
        var lastPointAt = 0L
    }

    private class Emoji(
        val char: String,
        val xN: Float,
        val bornAt: Long,
    )

    private val strokes = mutableListOf<Stroke>()
    private val emojis = mutableListOf<Emoji>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    init {
        instance = this
    }

    override fun onDetachedFromWindow() {
        instance = null
        super.onDetachedFromWindow()
    }

    fun applyStroke(json: JSONObject) {
        val id = json.optString("id")
        when (json.optString("k")) {
            "clear" -> {
                strokes.clear()
                emojis.clear()
            }
            "s" -> {
                // 幂等：同 id 重复落笔（极端重发场景）不产生双笔画
                if (strokes.none { it.id == id }) strokes.add(
                    Stroke(
                        id,
                        StrokeColors.COLORS[json.optInt("c", 0).mod(StrokeColors.COLORS.size)],
                        SystemClock.uptimeMillis(),
                    )
                )
            }
            "p" -> {
                // 自愈：容忍丢包通道丢了落笔"s"时，凭笔点现场建笔，整笔不再凭空消失
                val s = strokes.lastOrNull { it.id == id }
                    ?: Stroke(id, StrokeColors.COLORS[0], SystemClock.uptimeMillis()).also { strokes.add(it) }
                s.points.add(PointF((json.optDouble("x") * width).toFloat(), (json.optDouble("y") * height).toFloat()))
                s.lastPointAt = SystemClock.uptimeMillis()
            }
            "e" -> strokes.lastOrNull { it.id == id }?.let { if (it.endAt == 0L) it.endAt = SystemClock.uptimeMillis() }
        }
        invalidate()
    }

    fun applyEmoji(json: JSONObject) {
        val e = json.optString("e")
        if (e.isEmpty()) return
        emojis.add(Emoji(e, json.optDouble("x", 0.5).toFloat(), SystemClock.uptimeMillis()))
        invalidate()
    }

    fun hasVisibleContent(): Boolean = strokes.isNotEmpty() || emojis.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val strokeW = 6f * resources.displayMetrics.density

        // 笔迹生命周期：
        //  1) 书写中（未收笔）→ 完整显示，永不消失
        //  2) 收笔后 → 2 秒淡出
        //  3) 收笔信号丢失（容忍丢包通道会丢小消息）→ 以"最后笔点后 1.2 秒无新点"判定收笔，兜底淡出
        val iter = strokes.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            val fadeStart = when {
                s.endAt > 0L -> s.endAt
                s.lastPointAt > 0L && now - s.lastPointAt > STROKE_SILENCE_MS -> s.lastPointAt + STROKE_SILENCE_MS
                else -> 0L // 仍在书写中：完整显示
            }
            if (fadeStart == 0L) {
                paintStroke(canvas, s, 1f, strokeW)
                continue
            }
            val sinceEnd = now - fadeStart
            if (sinceEnd > StrokeColors.FADE_MS) {
                iter.remove()
                continue
            }
            paintStroke(canvas, s, 1f - sinceEnd.toFloat() / StrokeColors.FADE_MS, strokeW)
        }

        // 表情：从下往上飘 + 放大 + 淡出（1.6 秒生命周期）
        val emojiIter = emojis.iterator()
        while (emojiIter.hasNext()) {
            val e = emojiIter.next()
            val age = now - e.bornAt
            if (age > EMOJI_LIFE_MS) {
                emojiIter.remove()
                continue
            }
            val t = age.toFloat() / EMOJI_LIFE_MS
            val size = resources.displayMetrics.density * (56f + 40f * t)
            emojiPaint.textSize = size
            emojiPaint.alpha = (255f * (1f - t * t)).toInt().coerceIn(0, 255)
            val x = e.xN * width
            val y = height * 0.78f - t * height * 0.38f
            canvas.drawText(e.char, x, y, emojiPaint)
        }

        if (strokes.isNotEmpty() || emojis.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun paintStroke(canvas: Canvas, s: Stroke, fade: Float, strokeW: Float) {
        paint.color = s.color
        paint.alpha = (255f * fade.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        paint.strokeWidth = strokeW
        var prev: PointF? = null
        for (p in s.points) {
            prev?.let { canvas.drawLine(it.x, it.y, p.x, p.y, paint) }
            prev = p
        }
    }

    companion object {
        private const val EMOJI_LIFE_MS = 1600L

        /** 收笔信号丢失的兜底：最后笔点后静默此时长即视为已收笔，开始淡出 */
        const val STROKE_SILENCE_MS = 1200L

        private var instance: OverlayCanvasView? = null

        /** ScreenShareService.onDataMessage 回调入口（主线程投递） */
        fun onStroke(json: JSONObject) {
            instance?.post {
                instance?.applyStroke(json)
            }
        }

        /** 表情互动入口 */
        fun onEmoji(json: JSONObject) {
            instance?.post {
                instance?.applyEmoji(json)
            }
        }

        /** 服务销毁时清理静态引用与残留内容 */
        fun discard() {
            instance = null
        }
    }
}
