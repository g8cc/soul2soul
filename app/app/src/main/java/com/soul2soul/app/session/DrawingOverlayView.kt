package com.soul2soul.app.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot

/**
 * 观看端画笔层：叠加在屏幕渲染器上。
 *  - 手指滑动 → 实时画笔（本地 0 延迟 + DataChannel 发给共享端）
 *  - 原地轻点（无移动）→ 呼出/隐藏操作控件（画笔与控件互不打扰）
 * 坐标映射公式见 SPEC §5（相对视频帧矩形，考虑 letterbox）。
 */
class DrawingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface StrokeSink {
        fun onStroke(json: JSONObject)
    }

    /** 轻点回调（呼出/隐藏控件） */
    var onTap: (() -> Unit)? = null

    /** DataChannel 出口（SessionActivity 注入：笔迹/表情/指令都走这里） */
    var sink: StrokeSink? = null

    /** 远端视频帧尺寸（首帧后由 SessionActivity 注入），用于 letterbox 计算 */
    var videoWidth = 0
    var videoHeight = 0

    private class Stroke(
        val id: String,
        val color: Int,
    ) {
        val points = mutableListOf<PointF>() // 视图内绝对坐标，便于本地绘制

        /** 收笔时刻（0 = 还在书写中）。淡出从收笔才开始计——长笔画不会被中途擦掉 */
        var endAt = 0L
    }

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null

    /** 当前画笔颜色索引（UI 读取以显示选中态） */
    var colorIndex = 0
        private set

    // 轻点 vs 画笔 的判定状态
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var strokeStarted = false
    private val touchSlopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f * resources.displayMetrics.density
    }

    fun setColor(index: Int) {
        colorIndex = index.mod(StrokeColors.COLORS.size)
    }

    /** 通知 SessionActivity 刷新画笔颜色按钮的选中态 */
    fun selectedColor(): Int = colorIndex

    fun clearAll() {
        strokes.clear()
        current = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
                strokeStarted = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!strokeStarted &&
                    hypot(event.x - downX, event.y - downY) > touchSlopPx
                ) {
                    beginStroke(event) // 移动超过阈值：确认是画笔而非轻点
                    strokeStarted = true
                }
                if (strokeStarted) appendPoint(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (strokeStarted) {
                    current?.let { s ->
                        s.endAt = SystemClock.uptimeMillis()
                        sink?.onStroke(strokeMsg("e", s.id))
                        // 容忍丢包通道可能丢失收笔信号：补发一次（共享端幂等：endAt 只记一次）
                        postDelayed({
                            sink?.onStroke(strokeMsg("e", s.id))
                        }, 300)
                    }
                    current = null
                } else if (event.actionMasked == MotionEvent.ACTION_UP &&
                    SystemClock.uptimeMillis() - downTime < 400
                ) {
                    onTap?.invoke() // 轻点：切换控件可见性
                }
            }
        }
        invalidate()
        return true
    }

    private fun beginStroke(event: MotionEvent) {
        val s = Stroke(
            UUID.randomUUID().toString(),
            StrokeColors.COLORS[colorIndex],
        )
        strokes.add(s)
        current = s
        sink?.onStroke(strokeMsg("s", s.id).put("c", colorIndex))
        appendPoint(event)
    }

    private fun appendPoint(event: MotionEvent) {
        val s = current ?: return
        val rect = videoRect()
        val xn = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val yn = ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f)
        s.points.add(PointF(event.x, event.y))
        sink?.onStroke(strokeMsg("p", s.id).put("x", xn.toDouble()).put("y", yn.toDouble()))
    }

    private fun strokeMsg(k: String, id: String): JSONObject =
        JSONObject().put("k", k).put("id", id)

    /** 视频在视图内的实际显示矩形（SCALE_ASPECT_FIT） */
    private fun videoRect(): RectF {
        val r = StrokeMapping.fitRect(videoWidth, videoHeight, width.toFloat(), height.toFloat())
        return RectF(r[0], r[1], r[2], r[3])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val iter = strokes.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            // 书写中永不淡出；收笔后 2 秒淡出（与共享端 OverlayCanvasView 行为一致）
            if (s !== current && s.endAt > 0L && now - s.endAt > StrokeColors.FADE_MS) {
                iter.remove()
                continue
            }
            paint.color = s.color
            val fade = if (s === current || s.endAt == 0L) 1f
            else (1f - (now - s.endAt).toFloat() / StrokeColors.FADE_MS).coerceIn(0f, 1f)
            paint.alpha = (255f * fade).toInt()
            var prev: PointF? = null
            for (p in s.points) {
                prev?.let { canvas.drawLine(it.x, it.y, p.x, p.y, paint) }
                prev = p
            }
            // 单击也留个点（"就点这里"是最常用的指引）
            val only = s.points.singleOrNull()
            if (s.points.size == 1 && only != null) {
                canvas.drawCircle(only.x, only.y, paint.strokeWidth / 2f, paint)
            }
        }
        if (strokes.isNotEmpty()) postInvalidateOnAnimation()
    }
}
