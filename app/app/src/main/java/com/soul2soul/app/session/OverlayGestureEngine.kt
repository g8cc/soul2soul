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
        // 交棒淡出时长：本地这一笔在对方画布落墨（收到 k="a"）之后用这么短的时间淡掉，
        // 短到"两条线同时可见"的窗口肉眼读不成重影。刻意小于任何合理回显链路。
        const val HANDOVER_FADE_MS = 150L
        // ack 走的是容忍丢包的 anno 通道，可能永远不来：本地副本最多白留这么久，绝不永久驻留
        const val HANDOVER_FALLBACK_MS = 1500L
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
        /** alpha 逐帧在变 → View 必须续帧；等待交棒（alpha 恒定）不算，那是定时唤醒的活 */
        val fading: Boolean,
    )

    data class TrailSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val alpha: Int)

    private class Stroke(val id: String, val colorIndex: Int) {
        val points = mutableListOf<Pair<Float, Float>>() // 视图内绝对坐标，便于本地绘制
        var endAt = 0L // 0 = 还在书写中。淡出从收笔才开始计

        /** 对方画布落墨（ack）的时刻：0 = 还没交棒，本地继续全不透明顶着 */
        var handoverAt = 0L
    }

    var colorIndex = 0

    /**
     * 持久笔迹（共享端自画模式）：收笔后不淡出、不回收，直到 clearStrokes。
     * 观看端的短交棒淡出(HANDOVER_FADE_MS)是为了避免与视频回显叠成两条线；
     * 共享端自己就是"原画"，找茬类玩法需要圈选内容一直留在屏上。
     */
    var persistStrokes = false

    /** 轻点落点（共享端自画模式）：无移动的点击也记为单点笔迹，而不是 Tap 动作 */
    var tapDrawsDot = false

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

    fun onUp(id: Int, x: Float? = null, y: Float? = null): List<Action> {
        // Only the original pointer may finish the gesture. A secondary finger lifting must
        // not commit stale points from the primary finger after pointer-index reordering.
        if (primaryId == INVALID || id != primaryId) return emptyList()
        // ACTION_UP may contain a newer coordinate than the last ACTION_MOVE. Include that
        // endpoint so fast/long gestures do not finish short or miss an edge-navigation intent.
        val finalMove = if (x != null && y != null) onMove(id, x, y) else emptyList()
        primaryId = INVALID
        val finish = if (controlMode) ctlUp(x, y) else endDoodle(withTap = true)
        return finalMove + finish
    }

    fun onCancel(id: Int): List<Action> {
        if (primaryId == INVALID || id != primaryId) return emptyList()
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
            if (tapDrawsDot) {
                // 自画模式："就点这里"直接落一个点，不产生 Tap 语义
                val s = Stroke(idGen(), colorIndex)
                s.points.add(downX to downY)
                s.endAt = clock()
                strokes.add(s)
                acts.add(Action.StrokeStart(s.id, s.colorIndex))
                acts.add(Action.StrokePoint(s.id, normalize(downX, downY).first, normalize(downX, downY).second))
                acts.add(Action.StrokeEnd(s.id, s.endAt + END_RESEND_MS))
            } else {
                acts.add(Action.Tap) // 轻点：切换控件可见性
            }
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
        if (moved || now - gestureLastAt > GESTURE_MIN_MS) {
            appendGesturePoint(x, y, now)
        }
        return acts
    }

    private fun appendGesturePoint(x: Float, y: Float, at: Long) {
        if (gesturePts.size < GESTURE_MAX_PTS) {
            gesturePts.add(x to y)
            gestureTimes.add(at)
        } else {
            // Keep the message bounded, but never lose the latest position once sampling is
            // saturated. In particular, retain the actual up point for long edge swipes.
            gesturePts[gesturePts.lastIndex] = x to y
            gestureTimes[gestureTimes.lastIndex] = at
        }
        gestureLastAt = at
    }

    private fun ctlUp(x: Float?, y: Float?): List<Action> {
        val acts = mutableListOf<Action>()
        longPressArmed = false
        if (!longPressFired && gesturePts.isNotEmpty()) {
            if (x != null && y != null) {
                val last = gesturePts.last()
                if (last.first != x || last.second != y) appendGesturePoint(x, y, clock())
            }
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

    /**
     * 对方画布已落墨（echo_ack 到达）→ 本地这条可以交棒了。
     * @return 是否命中一条正在等交棒的笔迹（重复 ack、迟到的 ack 一律 false，幂等）
     */
    fun onRemoteCommit(id: String, now: Long): Boolean {
        val s = strokes.lastOrNull { it.id == id && it.endAt > 0L && it.handoverAt == 0L } ?: return false
        s.handoverAt = now
        return true
    }

    // ---------- 渲染快照（onDraw 只读这些） ----------

    /**
     * 本地笔迹三段生命周期（重影根治的核心）：
     *  1) 书写中 → 完整显示，永不淡出（他自己这条线必须 0 延迟）；
     *  2) 收笔后、对方还没落墨 → 继续完整显示。共享端 0.2.65 起缓冲到收笔才上屏，
     *     所以书写期间视频里根本没有副本，这一格不会造出双影；
     *  3) 收到 ack（或白等到 [HANDOVER_FALLBACK_MS]）→ 用 [HANDOVER_FADE_MS] 淡出并回收，
     *     持久的那一份交给视频里的回显（真实位置、全场唯一一份）。
     * @return 0 = 还在满不透明显示
     */
    private fun fadeStartOf(s: Stroke, now: Long): Long = when {
        s === current || s.endAt == 0L -> 0L
        s.handoverAt > 0L -> s.handoverAt
        now - s.endAt >= HANDOVER_FALLBACK_MS -> s.endAt + HANDOVER_FALLBACK_MS
        else -> 0L
    }

    /** 书写中/等待交棒永不淡出；交棒后 [HANDOVER_FADE_MS] 内淡到 0 并回收。
     *  persistStrokes=true（共享端自画）时收笔也永久保留，alpha 恒为 255 */
    fun visibleStrokes(now: Long): List<StrokeSnapshot> {
        val out = ArrayList<StrokeSnapshot>(strokes.size)
        val iter = strokes.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            val fadeStart = fadeStartOf(s, now)
            val fading = fadeStart > 0L && !persistStrokes
            if (fading && now - fadeStart >= HANDOVER_FADE_MS) {
                iter.remove()
                continue
            }
            val fade = if (persistStrokes || !fading) 1f
            else (1f - (now - fadeStart).toFloat() / HANDOVER_FADE_MS).coerceIn(0f, 1f)
            out.add(
                StrokeSnapshot(
                    s.id, s.colorIndex, ArrayList(s.points),
                    (255f * fade).toInt(), fading,
                )
            )
        }
        return out
    }

    /**
     * View 下一次需要自主重绘的时刻：正在淡出的笔迹=淡完那一刻，
     * 等待交棒的笔迹=兜底时刻，书写中的笔迹=null（笔点消息自己会触发重绘）。
     * 这条存在的意义就是**取消逐帧空转**：等 ack 的 1.5 秒里 alpha 恒定，
     * 60fps 重画一条不变的线只会让全屏悬浮层抖动（"笔迹一直飘"的老坑）。
     */
    fun nextWakeAt(now: Long): Long? {
        var next = Long.MAX_VALUE
        for (s in strokes) {
            if (persistStrokes) continue
            val fadeStart = fadeStartOf(s, now)
            val wake = if (fadeStart > 0L) fadeStart + HANDOVER_FADE_MS
            else if (s !== current && s.endAt > 0L) s.endAt + HANDOVER_FALLBACK_MS
            else continue
            if (wake < next) next = wake
        }
        return if (next == Long.MAX_VALUE) null else next
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
