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

    /** 操控模式长按画面回调（唤出功能菜单——此时轻点会被注入对方，不能再兼职开关菜单） */
    var onRevealControls: (() -> Unit)? = null

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
                removeCallbacks(ctlLongPress)
                gestureLongPressFired = false
                gesturePts.clear()
                gestureTimes.clear()
                tailPts.clear()
                tailTimes.clear()
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
    private val gesturePts = mutableListOf<PointF>() // 操控模式进行中手势（视图像素，抬手前完整保留供注入）
    private val gestureTimes = mutableListOf<Long>() // 逐点时间戳：渲染只亮"年轻"的段，老点照常参与手势识别
    private val tailPts = mutableListOf<PointF>()    // 抬手快照：尾迹继续淡出，与原手势解耦
    private val tailTimes = mutableListOf<Long>()
    private var gestureDownAt = 0L
    private var gestureLastAt = 0L
    private var gestureActive = false                // 手指还按着
    private var gestureLongPressFired = false
    private val ctlLongPress = Runnable {
        // 长按 = 本端唤菜单，这笔不再注入对方（否则长按菜单会点进对方 App 里）
        gestureLongPressFired = true
        gesturePts.clear()
        gestureTimes.clear()
        gestureActive = false
        invalidate()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onRevealControls?.invoke()
    }

    /** 当前画笔颜色索引（UI 读取以显示选中态） */
    var colorIndex = 0
        private set

    // 轻点 vs 画笔 的判定状态
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var strokeStarted = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID // 多指同屏只认首指：第二根手指不该把线甩走/污染手势
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

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
                activePointerId = event.getPointerId(0)
                downTime = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
                strokeStarted = false
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    if (!strokeStarted && hypot(x - downX, y - downY) > touchSlopPx) {
                        beginStroke(x, y) // 移动超过阈值：确认是画笔而非轻点
                        strokeStarted = true
                    }
                    if (strokeStarted) appendPoint(x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
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
                removeCallbacks(ctlLongPress)
                gestureLongPressFired = false
                activePointerId = event.getPointerId(0)
                gesturePts.clear()
                gestureTimes.clear()
                val now = SystemClock.uptimeMillis()
                gesturePts.add(PointF(event.x, event.y))
                gestureTimes.add(now)
                gestureDownAt = now
                gestureLastAt = now
                gestureActive = true
                postDelayed(ctlLongPress, CTL_LONG_PRESS_MS) // 按住不动 650ms = 唤菜单
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureLongPressFired) return // 已转为本端菜单手势，放弃注入
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return // 首指已抬起：多余手指的移动不进点序
                val x = event.getX(idx)
                val y = event.getY(idx)
                val first = gesturePts.firstOrNull()
                if (first != null &&
                    hypot(x - first.x, y - first.y) > touchSlopPx
                ) {
                    removeCallbacks(ctlLongPress) // 滑起来了就不是长按
                }
                val now = SystemClock.uptimeMillis()
                val last = gesturePts.lastOrNull()
                val moved = last != null &&
                    hypot(x - last.x, y - last.y) > GESTURE_MIN_PX
                if ((moved || now - gestureLastAt > GESTURE_MIN_MS) &&
                    gesturePts.size < GESTURE_MAX_PTS
                ) {
                    gesturePts.add(PointF(x, y))
                    gestureTimes.add(now)
                    gestureLastAt = now
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(ctlLongPress)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (!gestureLongPressFired && gesturePts.isNotEmpty()) {
                    sink?.onStroke(gestureMsg(SystemClock.uptimeMillis() - gestureDownAt))
                    tailPts.clear()
                    tailPts.addAll(gesturePts)
                    tailTimes.clear()
                    tailTimes.addAll(gestureTimes)
                }
                gestureActive = false
                gesturePts.clear()
                gestureTimes.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(ctlLongPress)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                gestureActive = false
                gesturePts.clear()
                gestureTimes.clear()
            }
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

    private fun beginStroke(x: Float, y: Float) {
        val s = Stroke(
            UUID.randomUUID().toString(),
            StrokeColors.COLORS[colorIndex],
        )
        strokes.add(s)
        current = s
        sink?.onStroke(strokeMsg("s", s.id).put("c", colorIndex))
        appendPoint(x, y)
    }

    private fun appendPoint(x: Float, y: Float) {
        val s = current ?: return
        val rect = videoRect()
        val xn = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val yn = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
        s.points.add(PointF(x, y))
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

        // 操控模式：指尖光晕 + 按点龄淡出的短尾迹。刻意不是"墨迹"——
        // 它只回答"手指正落在对方屏幕哪里"；真正的操作动画由共享端画面回显（唯一权威一份）
        gesturePaint.strokeWidth = 4f * resources.displayMetrics.density
        val d = resources.displayMetrics.density
        drawGestureTrail(canvas, tailPts, tailTimes, now)
        drawGestureTrail(canvas, gesturePts, gestureTimes, now)
        if (gestureActive && gesturePts.isNotEmpty()) {
            val tip = gesturePts.last()
            gesturePaint.alpha = 200
            canvas.drawCircle(tip.x, tip.y, 13f * d, gesturePaint) // 空心指尖环
            pointerPaint.alpha = 210
            canvas.drawCircle(tip.x, tip.y, 5f * d, pointerPaint)  // 实心触点
        }
        if (gesturePts.isNotEmpty() || tailPts.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** 逐段画轨迹：只画 450ms 内的"年轻"点；入参列表本身不动（完整点序还要参与手势识别/注入） */
    private fun drawGestureTrail(canvas: Canvas, pts: List<PointF>, times: List<Long>, now: Long) {
        gesturePaint.color = Color.WHITE
        for (i in 1 until pts.size) {
            val age = now - times[i]
            if (age > GESTURE_TAIL_MS) continue
            gesturePaint.alpha = (130 * (1f - age.toFloat() / GESTURE_TAIL_MS)).toInt().coerceIn(0, 130)
            canvas.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, gesturePaint)
        }
        // 尾迹头部过期即回收（绘制列表才允许收缩，注入列表永不在此裁剪）
        if (pts === tailPts) {
            while (tailPts.isNotEmpty() && now - tailTimes.first() > GESTURE_TAIL_MS) {
                tailPts.removeAt(0)
                tailTimes.removeAt(0)
            }
        }
    }

    companion object {
        private const val GESTURE_MIN_PX = 12f
        private const val GESTURE_MIN_MS = 60L
        private const val GESTURE_MAX_PTS = 64
        private const val GESTURE_TAIL_MS = 450L
        private const val CTL_LONG_PRESS_MS = 650L
        // 本地笔迹收笔后的留存时长：只做落笔即时反馈的短驻留，
        // 之后由共享端回显（真实位置、仅一份）接管，避免"一条线画成两条"。
        private const val GHOST_FADE_MS = 600L
    }
}
