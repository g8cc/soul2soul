package com.soul2soul.app.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.UUID

/**
 * 共享端自画笔迹层：全屏透明，挂在悬浮窗里。画出的圈直接留在本机屏幕上，
 * 被录屏采集进共享视频 → 观看端所见即所得（位置天然精确，无需坐标换算）。
 * 触摸状态机复用 OverlayGestureEngine（persistStrokes + tapDrawsDot）：
 * 笔迹不淡出、轻点也落点；本端不发送任何 DataChannel 消息（回显走视频）。
 */
class SharerDoodleView(context: Context) : View(context) {

    val engine = OverlayGestureEngine(
        touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        clock = { SystemClock.uptimeMillis() },
        idGen = { UUID.randomUUID().toString() },
    ).apply {
        persistStrokes = true
        tapDrawsDot = true
    }

    fun setColor(index: Int) {
        engine.colorIndex = index.mod(StrokeColors.COLORS.size)
    }

    fun clearAll() {
        engine.clearStrokes()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.setVideoRect(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val acts = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> engine.onDown(event.getPointerId(0), event.x, event.y)
            MotionEvent.ACTION_MOVE -> {
                val i = event.actionIndex
                engine.onMove(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP -> engine.onUp(0)
            MotionEvent.ACTION_CANCEL -> engine.onCancel(0)
            else -> return true
        }
        // 自画模式无发送语义，也不产生 Tap 动作（tapDrawsDot 已改记为点）
        if (acts.isNotEmpty()) invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (s in engine.visibleStrokes(SystemClock.uptimeMillis())) {
            paint.color = StrokeColors.COLORS[s.colorIndex.mod(StrokeColors.COLORS.size)]
            paint.alpha = s.alpha
            var prev: Pair<Float, Float>? = null
            for (p in s.points) {
                prev?.let { canvas.drawLine(it.first, it.second, p.first, p.second, paint) }
                prev = p
            }
            if (s.points.size == 1) {
                val only = s.points[0]
                canvas.drawCircle(only.first, only.second, paint.strokeWidth / 2f, paint)
            }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f * resources.displayMetrics.density
    }
}
