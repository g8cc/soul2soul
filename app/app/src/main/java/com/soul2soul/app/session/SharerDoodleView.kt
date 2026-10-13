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

    private var activePointerId = INVALID_POINTER_ID

    val engine = OverlayGestureEngine(
        touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        clock = { SystemClock.uptimeMillis() },
        idGen = { UUID.randomUUID().toString() },
    ).apply {
        persistStrokes = true
        tapDrawsDot = true
    }

    /** 本机任何一笔触摸都回调一次（服务据此唤回自动隐藏的工具条） */
    var onActivity: (() -> Unit)? = null

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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onActivity?.invoke()
        val acts = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A vendor may omit CANCEL between streams. Close the old stroke before
                // accepting a new primary pointer instead of joining two fingers together.
                if (activePointerId != INVALID_POINTER_ID) engine.onCancel(activePointerId)
                activePointerId = event.getPointerId(0)
                engine.onDown(activePointerId, event.getX(0), event.getY(0))
            }
            MotionEvent.ACTION_MOVE -> {
                val id = activePointerId
                val i = if (id == INVALID_POINTER_ID) -1 else event.findPointerIndex(id)
                if (i < 0) {
                    activePointerId = INVALID_POINTER_ID
                    engine.onCancel(id)
                } else {
                    engine.onMove(id, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> emptyList()
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == activePointerId) {
                    activePointerId = INVALID_POINTER_ID
                    engine.onUp(id, event.getX(event.actionIndex), event.getY(event.actionIndex))
                } else emptyList()
            }
            MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == activePointerId) {
                    activePointerId = INVALID_POINTER_ID
                    engine.onUp(id, event.getX(event.actionIndex), event.getY(event.actionIndex))
                } else emptyList()
            }
            MotionEvent.ACTION_CANCEL -> {
                val id = activePointerId
                activePointerId = INVALID_POINTER_ID
                if (id == INVALID_POINTER_ID) emptyList() else engine.onCancel(id)
            }
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

    private companion object {
        const val INVALID_POINTER_ID = -1
    }
}
