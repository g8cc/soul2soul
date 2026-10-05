package com.soul2soul.app.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.soul2soul.app.session.OverlayGestureEngine.Action
import org.json.JSONObject
import java.util.UUID

/**
 * 观看端画笔层：叠加在屏幕渲染器上。
 *  - 手指滑动 → 实时画笔（本地 0 延迟 + DataChannel 发给共享端）
 *  - 原地轻点（无移动）→ 呼出/隐藏操作控件（画笔与控件互不打扰）
 * 坐标映射公式见 SPEC §5（相对视频帧矩形，考虑 letterbox）。
 * 触摸状态机全部在 OverlayGestureEngine（纯逻辑单测覆盖），此处只做
 * MotionEvent 解析、Paint 渲染与 JSON 封包。
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
            engine.controlMode = value
            if (!value) removeCallbacks(ctlLongPress)
            invalidate()
        }

    private val engine = OverlayGestureEngine(
        touchSlopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        clock = { SystemClock.uptimeMillis() },
        idGen = { UUID.randomUUID().toString() },
    )

    private val ctlLongPress = Runnable {
        dispatchActions(engine.onLongPressDue())
        invalidate()
    }

    fun setColor(index: Int) {
        engine.colorIndex = index.mod(StrokeColors.COLORS.size)
    }

    /** 当前画笔颜色索引（UI 读取以显示选中态） */
    val colorIndex: Int get() = engine.colorIndex

    fun selectedColor(): Int = engine.colorIndex

    fun clearAll() {
        engine.clearStrokes()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = videoRect()
        engine.setVideoRect(r.left, r.top, r.right, r.bottom)
        val acts = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (controlMode) {
                    removeCallbacks(ctlLongPress)
                    postDelayed(ctlLongPress, OverlayGestureEngine.CTL_LONG_PRESS_MS)
                }
                engine.onDown(event.getPointerId(0), event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                // 只认首指：引擎按 pointerId 过滤，第二根手指的移动不污染轨迹
                val i = event.actionIndex
                engine.onMove(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP -> engine.onUp(0)
            MotionEvent.ACTION_CANCEL -> engine.onCancel(0)
            else -> return true // POINTER_DOWN/POINTER_UP 与原实现一样不参与判定
        }
        dispatchActions(acts)
        invalidate()
        return true
    }

    private fun dispatchActions(acts: List<Action>) {
        for (a in acts) when (a) {
            is Action.StrokeStart -> sink?.onStroke(strokeMsg("s", a.id).put("c", a.colorIndex))
            is Action.StrokePoint ->
                sink?.onStroke(strokeMsg("p", a.id).put("x", a.xn).put("y", a.yn))
            is Action.StrokeEnd -> {
                sink?.onStroke(strokeMsg("e", a.id))
                // 容忍丢包通道可能丢失收笔信号：补发一次（共享端幂等：endAt 只记一次）
                postDelayed({ sink?.onStroke(strokeMsg("e", a.id)) },
                    OverlayGestureEngine.END_RESEND_MS)
            }
            is Action.Tap -> onTap?.invoke()
            is Action.Gesture -> sink?.onStroke(gestureMsg(a.pts, a.durMs))
            is Action.RevealControls -> {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onRevealControls?.invoke()
            }
            is Action.CancelLongPress -> removeCallbacks(ctlLongPress)
        }
    }

    /** 手势消息：归一化到 letterbox 视频矩形（点哪里 = 点对方屏幕的哪里），4 位小数截断 */
    private fun gestureMsg(pts: List<Pair<Double, Double>>, durMs: Long): JSONObject {
        val arr = org.json.JSONArray()
        for ((xn, yn) in pts) {
            arr.put(org.json.JSONArray().put(xn).put(yn))
        }
        return JSONObject().put("k", "g").put("pts", arr).put("dur", durMs)
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
        // 书写中永不淡出；收笔后本地笔迹只做短暂留存(GHOST_FADE_MS)快速淡出。
        // 持久的那一条交给「共享端悬浮层渲染→录屏→回灌本端视频」的回显，
        // 它落在 TA 屏幕的真实位置且只有一份。本地留太长会与回显叠成"两条线"。
        val snaps = engine.visibleStrokes(now)
        for (s in snaps) {
            paint.color = StrokeColors.COLORS[s.colorIndex.mod(StrokeColors.COLORS.size)]
            paint.alpha = s.alpha
            var prev: Pair<Float, Float>? = null
            for (p in s.points) {
                prev?.let { canvas.drawLine(it.first, it.second, p.first, p.second, paint) }
                prev = p
            }
            // 单击也留个点（"就点这里"是最常用的指引）
            if (s.points.size == 1) {
                val only = s.points[0]
                canvas.drawCircle(only.first, only.second, paint.strokeWidth / 2f, paint)
            }
        }
        if (snaps.isNotEmpty()) postInvalidateOnAnimation()

        // 操控模式：指尖光晕 + 按点龄淡出的短尾迹。刻意不是"墨迹"——
        // 它只回答"手指正落在对方屏幕哪里"；真正的操作动画由共享端画面回显（唯一权威一份）
        gesturePaint.strokeWidth = 4f * resources.displayMetrics.density
        val d = resources.displayMetrics.density
        for (seg in engine.trailSegments(now)) {
            gesturePaint.alpha = seg.alpha
            canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, gesturePaint)
        }
        engine.gestureTip()?.let { (tx, ty) ->
            gesturePaint.alpha = 200
            canvas.drawCircle(tx, ty, 13f * d, gesturePaint) // 空心指尖环
            pointerPaint.alpha = 210
            canvas.drawCircle(tx, ty, 5f * d, pointerPaint)  // 实心触点
        }
        if (engine.hasTrail()) postInvalidateOnAnimation()
    }

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
}
