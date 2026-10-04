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

    /**
     * 操控模式：滑动/点击不再产生画笔，而是攒成一条完整手势，抬指时整体发送
     * {k:"g", pts, dur} → 对方经无障碍服务 dispatchGesture 注入。
     * 一笔一消息（而非逐点流式）：可靠通道下单条消息丢失即整笔重发，不存在半截手势。
     */
    var controlMode = false
        set(value) {
            field = value
            if (!value) {
                gesturePts.clear()
            }
            invalidate()
        }

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
    private val gesturePts = mutableListOf<PointF>() // 操控模式进行中手势（视图像素）
    private var gestureDownAt = 0L
    private var gestureLastAt = 0L

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

    private val gesturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
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
        if (controlMode) {
            handleControlTouch(event)
            invalidate()
            return true
        }
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

    // ---------- 操控模式 ----------

    private fun handleControlTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturePts.clear()
                gesturePts.add(PointF(event.x, event.y))
                gestureDownAt = SystemClock.uptimeMillis()
                gestureLastAt = gestureDownAt
            }
            MotionEvent.ACTION_MOVE -> {
                val now = SystemClock.uptimeMillis()
                val last = gesturePts.lastOrNull()
                val moved = last != null &&
                    hypot(event.x - last.x, event.y - last.y) > GESTURE_MIN_PX
                if ((moved || now - gestureLastAt > GESTURE_MIN_MS) &&
                    gesturePts.size < GESTURE_MAX_PTS
                ) {
                    gesturePts.add(PointF(event.x, event.y))
                    gestureLastAt = now
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gesturePts.isNotEmpty()) {
                    sink?.onStroke(gestureMsg(SystemClock.uptimeMillis() - gestureDownAt))
                }
                gesturePts.clear()
            }
            MotionEvent.ACTION_CANCEL -> gesturePts.clear()
        }
    }

    /** 手势消息：归一化到 letterbox 视频矩形（点哪里 = 点对方屏幕的哪里） */
    private fun gestureMsg(durMs: Long): JSONObject {
        val rect = videoRect()
        val arr = org.json.JSONArray()
        for (p in gesturePts) {
            val xn = ((p.x - rect.left) / rect.width()).coerceIn(0f, 1f)
            val yn = ((p.y - rect.top) / rect.height()).coerceIn(0f, 1f)
            arr.put(
                org.json.JSONArray()
                    .put((xn * 10000).toInt() / 10000.0)
                    .put((yn * 10000).toInt() / 10000.0)
            )
        }
        return JSONObject().put("k", "g").put("pts", arr).put("dur", durMs)
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
            // 书写中永不淡出；收笔后本地笔迹只做短暂留存(GHOST_FADE_MS)快速淡出。
            // 持久的那一条交给「共享端悬浮层渲染→录屏→回灌本端视频」的回显，
            // 它落在 TA 屏幕的真实位置且只有一份。本地留太长会与回显叠成"两条线"。
            if (s !== current && s.endAt > 0L && now - s.endAt > GHOST_FADE_MS) {
                iter.remove()
                continue
            }
            paint.color = s.color
            val fade = if (s === current || s.endAt == 0L) 1f
            else (1f - (now - s.endAt).toFloat() / GHOST_FADE_MS).coerceIn(0f, 1f)
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

        // 操控模式：正在攒的手势画成白色半透明虚影（是"预览"不是墨迹），抬手即发给对方
        if (controlMode && gesturePts.isNotEmpty()) {
            gesturePaint.strokeWidth = 5f * resources.displayMetrics.density
            gesturePaint.alpha = 110
            var prev: PointF? = null
            for (p in gesturePts) {
                prev?.let { canvas.drawLine(it.x, it.y, p.x, p.y, gesturePaint) }
                prev = p
            }
            val tip = gesturePts.last()
            canvas.drawCircle(tip.x, tip.y, 10f * resources.displayMetrics.density, gesturePaint)
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val GESTURE_MIN_PX = 12f
        private const val GESTURE_MIN_MS = 60L
        private const val GESTURE_MAX_PTS = 64
        // 本地笔迹收笔后的留存时长：只做落笔即时反馈的短驻留，
        // 之后由共享端回显（真实位置、仅一份）接管，避免"一条线画成两条"。
        private const val GHOST_FADE_MS = 600L
    }
}
