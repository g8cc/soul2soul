package com.soul2soul.app.session

import com.soul2soul.app.session.OverlayGestureEngine.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触摸状态机回归（0.2.18–0.2.22 真机踩坑锁定）：
 * 多指污染、轻点/画笔分界、采样上限、长按唤菜单、ghost 淡出、收笔补发。
 * 假钟驱动，无 Android 依赖。
 */
class OverlayGestureEngineTest {

    private var now = 1000L
    private var seq = 0
    private val clock = { now }
    private val idGen = { "s${seq++}" }

    private fun engine(slop: Float = 10f) = OverlayGestureEngine(slop, clock, idGen).apply {
        setVideoRect(0f, 0f, 1000f, 1000f)
    }

    // ---------- 轻点 vs 画笔 ----------

    @Test
    fun tap_399ms_fires_400ms_doesNot() {
        val e = engine()
        e.onDown(0, 50f, 50f)
        now = 1399
        val a = e.onUp(0)
        assertEquals(1, a.size)
        assertTrue(a[0] === Action.Tap)

        val e2 = engine()
        now = 2000
        e2.onDown(0, 50f, 50f)
        now = 2400 // 400ms 整：不再算轻点
        assertTrue(e2.onUp(0).isEmpty())
    }

    @Test
    fun move_exactlySlop_doesNotStartStroke() {
        val e = engine()
        e.onDown(0, 0f, 0f)
        // hypot > slop 严格：恰 10px 不启动
        assertTrue(e.onMove(0, 10f, 0f).isEmpty())
        val acts = e.onMove(0, 10.001f, 0f)
        assertEquals(2, acts.size)
        assertTrue(acts[0] is Action.StrokeStart)
        assertTrue(acts[1] is Action.StrokePoint)
    }

    @Test
    fun regression_secondPointer_neverPollutesTrajectory() {
        // 真机回归：第二根手指混入不能把线甩走（0.2.21 修复锁定）
        val e = engine()
        e.onDown(0, 5f, 5f)          // ptr0 首指
        e.onDown(1, 900f, 900f)      // ptr1 后到：必须被忽略
        val acts = e.onMove(1, 950f, 950f) // ptr1 自己动：不产生任何笔迹
        assertTrue(acts.isEmpty())
        val start = e.onMove(0, 60f, 60f)  // 首指动才起笔
        assertEquals(2, start.size)
        // 首指抬起后一切归零：后续 move（无论哪根手指）都被忽略
        val end = e.onUp(0)
        assertEquals(1, end.size)
        assertTrue(end[0] is Action.StrokeEnd)
        assertTrue(e.onMove(1, 300f, 300f).isEmpty())
        assertTrue(e.onUp(1).isEmpty())
    }

    @Test
    fun secondaryPointerCannotFinishOrCancelPrimaryGesture() {
        val e = engine()
        e.onDown(7, 5f, 5f)
        e.onDown(2, 900f, 900f)
        e.onMove(7, 60f, 60f)

        assertTrue(e.onUp(2).isEmpty())
        assertTrue(e.onCancel(2).isEmpty())
        assertTrue(e.onMove(7, 80f, 80f).isNotEmpty())

        val end = e.onUp(7)
        assertEquals(1, end.size)
        assertTrue(end.single() is Action.StrokeEnd)
    }

    @Test
    fun strokeEnd_carriesResendAtPlus300ms() {
        val e = engine()
        e.onDown(0, 0f, 0f)
        e.onMove(0, 100f, 100f)
        now = 2500
        val end = e.onUp(0)[0] as Action.StrokeEnd
        assertEquals(OverlayGestureEngine.END_RESEND_MS, end.resendAt - 2500)
    }

    // ---------- 交棒（v0.2.65 重影根治） ----------

    /** 画一笔并在 2000 收笔，返回笔迹 id */
    private fun strokeUpAt2000(e: OverlayGestureEngine): String {
        e.onDown(0, 0f, 0f)
        e.onMove(0, 100f, 100f)
        now = 2000
        e.onUp(0)
        return (e.visibleStrokes(now)[0]).id
    }

    @Test
    fun handover_constantsAreTheContract() {
        // 淡出必须显著短于兜底：否则"白等 ack"变成"永远看不见交棒"
        assertEquals(150L, OverlayGestureEngine.HANDOVER_FADE_MS)
        // 600 = 0.2.64 的 GHOST_FADE_MS：升级期"新观看端 + 旧共享端"（旧版不回 ack）时，
        // 两份墨重叠窗口由这个常量封顶，取它 ⇒ 混跑不比旧版差。改大就是拿她俩的观感赌实测。
        assertEquals(600L, OverlayGestureEngine.HANDOVER_FALLBACK_MS)
        assertTrue(OverlayGestureEngine.HANDOVER_FADE_MS < OverlayGestureEngine.HANDOVER_FALLBACK_MS)
    }

    @Test
    fun handover_keepsFullAlphaUntilAck() {
        val e = engine()
        val id = strokeUpAt2000(e)
        // 收笔后本地这一笔继续顶着：共享端 0.2.65 起缓冲到收笔才落墨，书写期间视频里
        // 根本没有副本，所以"顶着"不会造出双影；提前淡掉只会让观看端看见自己的画凭空消失
        val at2400 = e.visibleStrokes(2400)
        assertEquals(1, at2400.size)
        assertEquals(255, at2400[0].alpha)
        assertTrue("等待交棒期间 alpha 不变，不许逐帧重画", !at2400[0].fading)
        assertEquals(1, e.visibleStrokes(2599).size) // 兜底前一瞬仍在

        assertTrue(e.onRemoteCommit(id, 2500))
        val acked = e.visibleStrokes(2500)
        assertEquals(255, acked[0].alpha)
        assertTrue(acked[0].fading) // 从这一刻起才需要续帧
        assertEquals(127, e.visibleStrokes(2575)[0].alpha)
        assertTrue(e.visibleStrokes(2650).isEmpty()) // 恰 150ms：淡完即回收
    }

    @Test
    fun handover_lostAckFallsBackNeverSticksForever() {
        val e = engine()
        strokeUpAt2000(e)
        assertTrue(e.visibleStrokes(2599).isNotEmpty())
        val due = e.visibleStrokes(2600)
        assertEquals(1, due.size)
        assertTrue("兜底时刻一到就进入淡出", due[0].fading)
        assertTrue(e.visibleStrokes(2750).isEmpty())
    }

    @Test
    fun handover_duplicateOrLateOrUnknownAckIsIdempotent() {
        val e = engine()
        val id = strokeUpAt2000(e)
        assertTrue(e.onRemoteCommit(id, 2100))
        assertFalse("重复 ack（补发/重传）只算第一次", e.onRemoteCommit(id, 2200))
        assertFalse("没见过的 id 不许动别的笔迹", e.onRemoteCommit("nope", 2300))
        assertTrue(e.visibleStrokes(2249).isNotEmpty())
        assertTrue("恰淡满 HANDOVER_FADE_MS 即回收", e.visibleStrokes(2250).isEmpty())
        assertFalse("回收后迟到的 ack 只会得到 false", e.onRemoteCommit(id, 2300))
    }

    @Test
    fun handover_awaitingIsScheduledOnceNotEveryFrame() {
        val e = engine()
        e.onDown(0, 0f, 0f)
        e.onMove(0, 50f, 50f)
        now = 2000
        assertNull("书写期间靠笔点驱动，不需要定时唤醒", e.nextWakeAt(2000))
        e.onUp(0)
        assertEquals("等 ack 期间只排一次：兜底时刻", 2600L, e.nextWakeAt(2000))
        val id = e.visibleStrokes(2000)[0].id
        e.onRemoteCommit(id, 2100)
        assertEquals("进入淡出后唤醒点=淡完", 2250L, e.nextWakeAt(2100))
        assertTrue(e.visibleStrokes(2250).isEmpty())
        assertNull(e.nextWakeAt(2250))
        e.clearStrokes()
        assertNull(e.nextWakeAt(3000))
    }

    @Test
    fun writingStroke_neverFades() {
        val e = engine()
        e.onDown(0, 0f, 0f)
        e.onMove(0, 50f, 50f)
        val snaps = e.visibleStrokes(now + 10_000)
        assertEquals(1, snaps.size)
        assertEquals(255, snaps[0].alpha)
    }

    // ---------- 共享端自画模式（v0.2.28 双向涂鸦） ----------

    @Test
    fun persistStrokes_surviveFarBeyondGhostFade() {
        val e = engine().apply { persistStrokes = true }
        e.onDown(0, 0f, 0f)
        e.onMove(0, 100f, 100f)
        now = 2000
        e.onUp(0)
        now = 99_000 // 远超 GHOST_FADE_MS：持久笔迹仍在且全不透明
        val snaps = e.visibleStrokes(now)
        assertEquals(1, snaps.size)
        assertEquals(255, snaps[0].alpha)
        e.clearStrokes()
        assertTrue(e.visibleStrokes(now).isEmpty())
    }

    @Test
    fun tapDrawsDot_tapBecomesSinglePointStrokeNotTapAction() {
        val e = engine().apply { tapDrawsDot = true }
        e.onDown(0, 50f, 60f)
        now = 1300
        val acts = e.onUp(0)
        assertTrue(acts.none { it === Action.Tap })
        assertTrue(acts.filterIsInstance<Action.StrokeStart>().size == 1)
        val pt = acts.filterIsInstance<Action.StrokePoint>().single()
        assertEquals(0.05, pt.xn, 1e-6) // 归一化到全屏矩形 (0,0,1000,1000)；float 除法有末位舍入
        assertEquals(0.06, pt.yn, 1e-6)
        val snaps = e.visibleStrokes(now)
        assertEquals(1, snaps.size)
        assertEquals(1, snaps[0].points.size)
    }

    @Test
    fun tapDrawsDot_slowPressAfter400ms_leavesNothing() {
        val e = engine().apply { tapDrawsDot = true }
        e.onDown(0, 50f, 60f)
        now = 1400 // 400ms 整：不算轻点，也不落点
        assertTrue(e.onUp(0).isEmpty())
        assertTrue(e.visibleStrokes(now).isEmpty())
    }

    // ---------- 操控采样 ----------

    private fun ctlEngine(): OverlayGestureEngine = engine().apply {
        controlMode = true
        onDown(0, 100f, 100f)
    }

    @Test
    fun ctl_sampling_justOver12px_addsRegardlessOfTime() {
        val e = ctlEngine()
        now = 1001 // 距上一点仅 1ms：纯位移驱动
        e.onMove(0, 112.001f, 100f)
        e.onMove(0, 112.002f, 100f) // 位移不足 12px 且时间不足 60ms：不加
        // 首点 + 112.001 一点
        val acts = e.onUp(0)
        val g = acts.filterIsInstance<Action.Gesture>().single()
        assertEquals(2, g.pts.size)
    }

    @Test
    fun ctl_sampling_61msGap_addsStationaryPoint() {
        val e = ctlEngine()
        now = 1060 // 60ms 整：还不加（> 60 才加）
        e.onMove(0, 100f, 100f)
        now = 1061
        e.onMove(0, 100f, 100f)
        val g = e.onUp(0).filterIsInstance<Action.Gesture>().single()
        assertEquals(2, g.pts.size)
        assertEquals(61L, g.durMs)
    }

    @Test
    fun ctl_max64Points_keepsLatestEndpointWhenSamplingIsSaturated() {
        val e = ctlEngine()
        for (i in 0 until 70) {
            now += 100 // 每步时间都超 60ms：必采样，直到撞 64 上限
            e.onMove(0, 100f + i, 100f)
        }
        val g = e.onUp(0, 250f, 100f).filterIsInstance<Action.Gesture>().single()
        assertEquals(OverlayGestureEngine.GESTURE_MAX_PTS, g.pts.size)
        assertEquals(0.25, g.pts.last().first, 1e-9)
    }

    @Test
    fun ctl_upCoordinateIsIncludedEvenWithoutFinalMoveEvent() {
        val e = ctlEngine()
        val g = e.onUp(0, 800f, 100f).filterIsInstance<Action.Gesture>().single()
        assertEquals(2, g.pts.size)
        assertEquals(0.8, g.pts.last().first, 1e-9)
    }

    // ---------- 长按唤菜单 ----------

    @Test
    fun ctl_longPressDue_revealsAndVoidsGesture() {
        val e = ctlEngine()
        // 未滑动：650ms 到点应唤菜单，且这笔作废
        val acts = e.onLongPressDue()
        assertEquals(1, acts.size)
        assertTrue(acts[0] === Action.RevealControls)
        // 作废后抬手：不再发手势
        assertTrue(e.onUp(0).isEmpty())
        // 重复触发被 fired 挡住
        assertTrue(e.onLongPressDue().isEmpty())
    }

    @Test
    fun ctl_slidePastSlop_cancelsLongPress() {
        val e = ctlEngine()
        now = 1100
        val acts = e.onMove(0, 100f, 200f) // 距首点 100px > slop
        assertTrue(acts.contains(Action.CancelLongPress))
        assertTrue(e.onLongPressDue().isEmpty()) // 定时器竞态迟到也不唤菜单
    }

    // ---------- 手势封包 ----------

    @Test
    fun ctl_gesture_normalizesClampsAndTruncates() {
        val e = engine(10f).apply {
            controlMode = true
            setVideoRect(100f, 0f, 900f, 1000f) // letterbox：视频矩形只占中间 800px
        }
        e.onDown(0, 100f, 0f)      // 视频矩形左缘 → xn=0
        now = 1061
        e.onMove(0, 1500f, -100f)  // 越界 → 钳制 (1.0, 0.0)
        now = 1161
        e.onMove(0, 100f + 800f * 0.123456f, 0f) // xn≈0.123456 → 截断 0.1234
        val g = e.onUp(0).filterIsInstance<Action.Gesture>().single()
        assertEquals(0.0, g.pts[0].first, 1e-9)
        assertEquals(1.0, g.pts[1].first, 1e-9)
        assertEquals(0.0, g.pts[1].second, 1e-9)
        assertEquals(0.1234, g.pts[2].first, 1e-9)
        assertEquals(161L, g.durMs)
    }

    @Test
    fun ctl_cancel_dropsGestureWithoutSending() {
        val e = ctlEngine()
        now = 1061
        e.onMove(0, 200f, 200f)
        assertTrue(e.onCancel(0).isEmpty())
        assertTrue(e.hasTrail().not()) // CANCEL 连尾迹都不留
    }

    // ---------- 尾迹快照与注入列表解耦 ----------

    @Test
    fun tail_snapshotDecoupledAndHeadPruned() {
        val e = ctlEngine()
        now = 1100
        e.onMove(0, 200f, 200f) // 采样第二点
        now = 1200
        e.onUp(0)               // 手势已发出；注入列表清空，尾迹接管
        assertTrue(e.hasTrail())
        now = 1550 // 第二点龄 450：段仍亮；首点(1000)龄 550：头部回收
        val segs = e.trailSegments(now)
        assertEquals(1, segs.size)
        val seg = segs[0]
        assertEquals(0, seg.alpha) // 130*(1-450/450)=0
        assertTrue(e.hasTrail())   // 回收只削头部，快照本体不被"顺手清空"
        now = 1651                 // 剩余点头部也过期 → 全清
        assertTrue(e.trailSegments(now).isEmpty())
        assertTrue(e.hasTrail().not())
    }

    @Test
    fun gestureTip_visibleOnlyWhileHolding() {
        val e = ctlEngine()
        assertNotNull(e.gestureTip())
        e.onCancel(0)
        assertNull(e.gestureTip())
    }

    // ---------- 模式切换归零 ----------

    @Test
    fun leavingControlMode_clearsGestureAndTail() {
        val e = ctlEngine()
        now = 1100
        e.onMove(0, 200f, 200f)
        now = 1200
        e.onUp(0)
        e.controlMode = false
        assertTrue(e.hasTrail().not())
    }
}
