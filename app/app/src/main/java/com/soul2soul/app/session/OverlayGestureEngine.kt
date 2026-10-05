package com.soul2soul.app.session

import kotlin.math.hypot

/**
 * DrawingOverlayView 触摸状态机的纯逻辑层（假钟可注入，JVM 可单测）。
 * 负责：只认首指 / 轻点 vs 画笔 / 笔迹 ghost 淡出 / 操控采样与上限 /
 * 长按唤菜单 / 手势尾迹快照。View 保留 MotionEvent 解析、Paint 渲染与
 * DataChannel 发送：引擎返回 Action 列表，View 按序执行。
 * 常量自 View companion 逐字搬移，行为零变更。
 */
class OverlayGestureEngine(
    private val touchSlopPx: Float,
    private val clock: () -> Long,
    private val idGen: () -> String,
) {

    companion object {
        const val TAP_MAX_MS = 400L
        const val CTL_LONG_PRESS_MS = 650L
        const val GESTURE_MIN_PX = 12f
        const val GESTURE_MIN_MS = 60L
        const val GESTURE_MAX_PTS = 64
        const val GESTURE_TAIL_MS = 450L
        // 本地笔迹收笔后的留存时长：只做落笔即时反馈的短驻留，
        // 之后由共享端回显（真实位置、仅一份）接管，避免"一条线画成两条"。
        const val GHOST_FADE_MS = 600L
        // 容忍丢包通道可能丢失收笔信号：补发一次（共享端幂等：endAt 只记一次）
        const val END_RESEND_MS = 300L
        private const val INVALID = -1
    }

    sealed class Action {
        data class StrokeStart(val id: String, val colorIndex: Int) : Action()
        data class StrokePoint(val id: String, val xn: Double, val yn: Double) : Action()
        data class StrokeEnd(val id: String, val resendAt: Long) : Action()
        object Tap : Action()
        data class Gesture(val pts: List<Pair<Double, Double>>, val durMs: Long) : Action()
        object RevealControls : Action()
        object CancelLongPress : Action()
    }

    /** 渲染快照：View 的 onDraw 只读这些，不再持有触摸状态 */
    class StrokeSnapshot(
        val id: String,
        val colorIndex: Int,
        val points: List<Pair<Float, Float>>,
        val alpha: Int,
    )

    data class TrailSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val alpha: Int)

    private class Stroke(val id: String, val colorIndex: Int) {
        val points = mutableListOf<Pair<Float, Float>>() // 视图内绝对坐标，便于本地绘制
        var endAt = 0L // 0 = 还在书写中。淡出从收笔才开始计
    }

    var colorIndex = 0
    var controlMode = false
        set(value) {
            field = value
            if (!value) {
                longPressFired = false
                longPressArmed = false
                gesturePts.clear()
                gestureTimes.clear()
                tailPts.clear()
                tailTimes.clear()
            }
        }

    /** letterbox 视频矩形（View 坐标），由 View 在布局变化时写入 */
    private var rLeft = 0f; private var rTop = 0f; private var rRight = 0f; private var rBottom = 0f
    fun setVideoRect(left: Float, top: Float, right: Float, bottom: Float) {
        rLeft = left; rTop = top; rRight = right; rBottom = bottom
    }

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null

    private var primaryId = INVALID
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var strokeStarted = false

    // ---------- 操控模式状态 ----------
    private val gesturePts = mutableListOf<Pair<Float, Float>>() // 进行中手势（抬手前完整保留供注入）
    private val gestureTimes = mutableListOf<Long>()             // 逐点时间戳：渲染只亮"年轻"的段
    private var tailPts = mutableListOf<Pair<Float, Float>>()    // 抬手快照：尾迹继续淡出，与原手势解耦
    private var tailTimes = mutableListOf<Long>()
    private var gestureDownAt = 0L
    private var gestureLastAt = 0L
    private var gestureActive = false                // 手指还按着
    private var longPressFired = false
    private var longPressArmed = false

    fun clearStrokes() {
        strokes.clear()
        current = null
    }

    fun onDown(id: Int, x: Float, y: Float): List<Action> {
        if (primaryId != INVALID) return emptyList() // 多指同屏只认首指：第二根手指不该把线甩走
        primaryId = id
        if (controlMode) {
            longPressFired = false
            longPressArmed = true
            gesturePts.clear()
            gestureTimes.clear()
            val now = clock()
            gesturePts.add(x to y)
            gestureTimes.add(now)
            gestureDownAt = now
            gestureLastAt = now
            gestureActive = true
            return emptyList()
        }
        downTime = clock()
        downX = x
        downY = y
        strokeStarted = false
        return emptyList()
    }

    fun onMove(id: Int, x: Float, y: Float): List<Action> {
        if (id != primaryId || primaryId == INVALID) return emptyList()
        if (controlMode) return ctlMove(x, y)
        val acts = mutableListOf<Action>()
        if (!strokeStarted && hypot(x - downX, y - downY) > touchSlopPx) {
            strokeStarted = true // 移动超过阈值：确认是画笔而非轻点
            val s = Stroke(idGen(), colorIndex)
            strokes.add(s)
            current = s
            acts.add(Action.StrokeStart(s.id, s.colorIndex))
        }
        if (strokeStarted) {
            current?.let { s ->
                s.points.add(x to y)
                val (xn, yn) = normalize(x, y)
                acts.add(Action.StrokePoint(s.id, xn, yn))
            }
        }
        return acts
    }

    fun onUp(id: Int): List<Action> {
        // 与原 View 行为一致：抬手收尾认"最后一根手指离开"，不区分 id；
        // 尾指先抬（POINTER_UP）在老代码里本就被忽略，此处沿用
        if (primaryId == INVALID) return emptyList()
        primaryId = INVALID
        if (controlMode) return ctlUp()
        return endDoodle(withTap = true)
    }

    fun onCancel(id: Int): List<Action> {
        if (primaryId == INVALID) return emptyList()
        primaryId = INVALID
        if (controlMode) {
            gestureActive = false
            gesturePts.clear()
            gestureTimes.clear()
            longPressArmed = false
            return emptyList()
        }
        return endDoodle(withTap = false)
    }

    /** View 的 650ms 定时回调：长按 = 本端唤菜单，这笔不再注入对方 */
    fun onLongPressDue(): List<Action> {
        if (!controlMode || !longPressArmed || longPressFired || !gestureActive) return emptyList()
        longPressFired = true
        longPressArmed = false
        gestureActive = false
        gesturePts.clear()
        gestureTimes.clear()
        return listOf(Action.RevealControls)
    }

    private fun endDoodle(withTap: Boolean): List<Action> {
        val acts = mutableListOf<Action>()
        if (strokeStarted) {
            current?.let { s ->
                s.endAt = clock()
                acts.add(Action.StrokeEnd(s.id, s.endAt + END_RESEND_MS))
            }
            current = null
        } else if (withTap && clock() - downTime < TAP_MAX_MS) {
            acts.add(Action.Tap) // 轻点：切换控件可见性
        }
        return acts
    }

    private fun ctlMove(x: Float, y: Float): List<Action> {
        if (longPressFired) return emptyList() // 已转为本端菜单手势，放弃注入
        val acts = mutableListOf<Action>()
        val first = gesturePts.firstOrNull()
        if (longPressArmed && first != null && hypot(x - first.first, y - first.second) > touchSlopPx) {
            longPressArmed = false // 滑起来了就不是长按
            acts.add(Action.CancelLongPress)
        }
        val now = clock()
        val last = gesturePts.lastOrNull()
        val moved = last != null && hypot(x - last.first, y - last.second) > GESTURE_MIN_PX
        if ((moved || now - gestureLastAt > GESTURE_MIN_MS) && gesturePts.size < GESTURE_MAX_PTS) {
            gesturePts.add(x to y)
            gestureTimes.add(now)
            gestureLastAt = now
        }
        return acts
    }

    private fun ctlUp(): List<Action> {
        val acts = mutableListOf<Action>()
        longPressArmed = false
        if (!longPressFired && gesturePts.isNotEmpty()) {
            val pts = gesturePts.map { (px, py) -> normalizeTruncated(px, py) }
            acts.add(Action.Gesture(pts, clock() - gestureDownAt))
            tailPts = ArrayList(gesturePts)
            tailTimes = ArrayList(gestureTimes)
        }
        gestureActive = false
        gesturePts.clear()
        gestureTimes.clear()
        return acts
    }

    private fun normalize(x: Float, y: Float): Pair<Double, Double> {
        val xn = ((x - rLeft) / (rRight - rLeft)).coerceIn(0f, 1f)
        val yn = ((y - rTop) / (rBottom - rTop)).coerceIn(0f, 1f)
        return xn.toDouble() to yn.toDouble()
    }

    /** 手势消息专用：4 位小数截断（与线上 JSON 体积约定一致） */
    private fun normalizeTruncated(x: Float, y: Float): Pair<Double, Double> {
        val (xn, yn) = normalize(x, y)
        val tx = ((xn.toFloat() * 10000).toInt() / 10000.0)
        val ty = ((yn.toFloat() * 10000).toInt() / 10000.0)
        return tx to ty
    }

    // ---------- 渲染快照（onDraw 只读这些） ----------

    /** 书写中永不淡出；收笔后本地笔迹只做短暂留存(GHOST_FADE_MS)，过期即回收 */
    fun visibleStrokes(now: Long): List<StrokeSnapshot> {
        val out = ArrayList<StrokeSnapshot>(strokes.size)
        val iter = strokes.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            if (s !== current && s.endAt > 0L && now - s.endAt > GHOST_FADE_MS) {
                iter.remove()
                continue
            }
            val fade = if (s === current || s.endAt == 0L) 1f
            else (1f - (now - s.endAt).toFloat() / GHOST_FADE_MS).coerceIn(0f, 1f)
            out.add(StrokeSnapshot(s.id, s.colorIndex, ArrayList(s.points), (255f * fade).toInt()))
        }
        return out
    }

    /** 指尖光晕位置：手指还按着且有点才有 */
    fun gestureTip(): Pair<Float, Float>? =
        if (gestureActive && gesturePts.isNotEmpty()) gesturePts.last() else null

    /** 是否仍有需要逐帧刷新的轨迹（View 据此决定是否 postInvalidateOnAnimation） */
    fun hasTrail(): Boolean = gesturePts.isNotEmpty() || tailPts.isNotEmpty()

    /** 逐段尾迹：只算 450ms 内的"年轻"段；绘制列表才允许收缩，注入列表永不在此裁剪 */
    fun trailSegments(now: Long): List<TrailSeg> {
        val segs = ArrayList<TrailSeg>()
        collectTrail(segs, tailPts, tailTimes, now)
        collectTrail(segs, gesturePts, gestureTimes, now)
        // 尾迹头部过期即回收
        while (tailPts.isNotEmpty() && now - tailTimes.first() > GESTURE_TAIL_MS) {
            tailPts.removeAt(0)
            tailTimes.removeAt(0)
        }
        return segs
    }

    private fun collectTrail(
        out: MutableList<TrailSeg>,
        pts: List<Pair<Float, Float>>,
        times: List<Long>,
        now: Long,
    ) {
        for (i in 1 until pts.size) {
            val age = now - times[i]
            if (age > GESTURE_TAIL_MS) continue
            val alpha = (130 * (1f - age.toFloat() / GESTURE_TAIL_MS)).toInt().coerceIn(0, 130)
            val (x1, y1) = pts[i - 1]
            val (x2, y2) = pts[i]
            out.add(TrailSeg(x1, y1, x2, y2, alpha))
        }
    }
}
