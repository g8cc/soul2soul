package com.soul2soul.app.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享端笔迹缓冲的锁定用例（v0.2.65 重影根治，计划步骤⑤）。
 *
 * 缓冲这一层是"屏幕上任何时刻只有一份墨"的唯一保证，所以两头都要锁：
 * ① [SharerStrokeBuffer] 的纯逻辑（假钟，真判定）；
 * ② `OverlayCanvasView`/`AnnotationOverlayService` 的接线（View 依赖 Context，JVM 跑不了，
 *    按本仓库既有口径退化为源码扫描锁——`hasVisibleContent` 漏掉 pending 会当场拆掉
 *    正在画的人的窗口，这条在真机上看不见日志、只会表现为"画到一半笔没了"）。
 */
class OverlayStrokeBufferTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private val canvas = source("app/src/main/java/com/soul2soul/app/session/OverlayCanvasView.kt")
    private val service = source("app/src/main/java/com/soul2soul/app/session/AnnotationOverlayService.kt")

    private fun functionBody(lines: List<String>, anchor: String): List<String> {
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertEquals("锚点应恰好出现一次，实际 ${hits.size} 次：$anchor", 1, hits.size)
        val callIdx = hits[0]
        val funHead = Regex("""^\s*(\w+\s+)*fun\s[\w`<(]""")
        val header = (0..callIdx).reversed().firstOrNull { funHead.containsMatchIn(lines[it]) }
            ?: error("行 ${callIdx + 1} 不在任何函数内，扫描口径需重看")
        val pad = " ".repeat(lines[header].takeWhile { it == ' ' }.length)
        val nextFun = Regex("""^$pad(\w+\s+)*fun\s""")
        val end = ((header + 1)..lines.lastIndex).firstOrNull { i ->
            lines[i] == "${pad}}" || nextFun.containsMatchIn(lines[i])
        } ?: lines.lastIndex
        return lines.subList(header, end)
    }

    private class Clock(var now: Long = 1_000L) {
        fun ms(): Long = now
        fun advance(d: Long) { now += d }
    }

    // ── 纯逻辑：笔点绝不上屏，收笔才交出去 ──────────────────────────

    @Test
    fun `points accumulate but only pen-up hands the stroke over`() {
        val c = Clock()
        val b = SharerStrokeBuffer(c::ms)
        assertTrue(b.begin("s1", 1))
        b.point("s1", 0.2f, 0.3f)
        c.advance(100)
        b.point("s1", 0.4f, 0.5f)
        assertTrue("书写期间它必须还压在缓冲里", b.hasPending())
        val p = b.end("s1")
        assertEquals("收笔交出整笔的两个点", 2, p?.points?.size)
        assertFalse("交出后缓冲清空", b.hasPending())
    }

    @Test
    fun `repeatedStartSignalDoesNotCreateASecondPending`() {
        val c = Clock()
        val b = SharerStrokeBuffer(c::ms)
        assertTrue(b.begin("s1", 0))
        assertFalse("同 id 重复落笔必须被吸收（视频里多一条＝重影）", b.begin("s1", 2))
        b.point("s1", 0.1f, 0.1f)
        assertEquals(1, b.end("s1")?.points?.size)
    }

    @Test
    fun `strokeSurvivesLostStartSignal`() {
        // anno 是丢包通道："s" 可能整个没到，只剩笔点——整笔不许凭空消失
        val b = SharerStrokeBuffer({ 5L })
        b.point("s9", 0.5f, 0.5f)
        val p = b.end("s9")
        assertEquals(1, p?.points?.size)
    }

    @Test
    fun `outOfRangeCoordinatesAreClampedNotCredited`() {
        val b = SharerStrokeBuffer({ 5L })
        b.begin("s1", 0)
        b.point("s1", 2.5f, -3f)
        assertEquals(1f, b.end("s1")?.points?.first()?.first)
    }

    @Test
    fun `duplicatePenUpIsIdempotent`() {
        val b = SharerStrokeBuffer({ 5L })
        b.begin("s1", 0)
        b.point("s1", 0.3f, 0.3f)
        assertNotNullish(b.end("s1"))
        assertNull("重复收笔（300ms 幂等补发）不得二次落墨", b.end("s1"))
    }

    @Test
    fun `silenceFallbackCommitsExactlyAtTheDeadline`() {
        val c = Clock()
        val b = SharerStrokeBuffer(c::ms, 1_200L)
        b.begin("s1", 0)
        b.point("s1", 0.5f, 0.5f)      // t=1000
        c.advance(1_199)
        assertTrue("差 1ms 不许抢跑（否则正在画的笔被切成两笔）", b.flushDue(c.ms()).isEmpty())
        c.advance(1)
        assertEquals("恰满静默期即兜底落墨", 1, b.flushDue(c.ms()).size)
        assertFalse(b.hasPending())
    }

    @Test
    fun `lastPointMovesTheDeadline`() {
        val c = Clock()
        val b = SharerStrokeBuffer(c::ms, 1_200L)
        b.begin("s1", 0)
        b.point("s1", 0.1f, 0.1f)
        val firstDue = b.nextDeadline(c.ms())
        c.advance(1_000)
        b.point("s1", 0.2f, 0.2f)
        val moved = b.nextDeadline(c.ms())
        assertTrue("续笔要把兜底往后推，否则长笔画到一半被截断",
            firstDue != null && moved != null && moved > firstDue)
    }

    @Test
    fun `startWithoutAnyPointIsDroppedAndPinsNothing`() {
        val c = Clock()
        val b = SharerStrokeBuffer(c::ms, 1_200L)
        b.begin("ghost", 0)
        c.advance(1_200)
        assertTrue("只落了笔没有点＝隐形内容，到龄直接丢，不许钉住全屏窗口",
            b.flushDue(c.ms()).isEmpty())
        assertFalse(b.hasPending())
        assertNull(b.nextDeadline(c.ms()))
    }

    @Test
    fun `nextDeadlineIsTheEarliestOfSeveralPendingStrokes`() {
        val c = Clock()
        val b = SharerStrokeBuffer(c::ms, 1_200L)
        b.begin("a", 0); b.point("a", 0.1f, 0.1f)   // t=1000 → 2200
        c.advance(300)
        b.begin("b", 0); b.point("b", 0.2f, 0.2f)   // t=1300 → 2500
        assertEquals(2_200L, b.nextDeadline(c.ms()))
        assertEquals("到点只出账到期那一笔", listOf("a"), b.flushDue(2_200L).map { it.id })
    }

    @Test
    fun `clearEmptiesTheBuffer`() {
        val b = SharerStrokeBuffer({ 1L })
        b.begin("s1", 0); b.point("s1", 0.5f, 0.5f)
        b.clear()
        assertFalse(b.hasPending())
        assertNull(b.end("s1"))
    }

    // ── 接线锁：View/Service 里不能漏的那几处 ──────────────────────

    @Test
    fun `pendingInkCountsAsVisibleContentOrTheWindowIsTornMidStroke`() {
        val body = functionBody(canvas, "fun hasVisibleContent()")
        assertTrue("缓冲里的 pending 必须算内容：否则拆窗发生在「上一笔淡出→新一笔首点」的空隙里，" +
            "正在画的人手里画布就没了", body.any { it.contains("buffer.hasPending()") })
    }

    @Test
    fun `frameLoopDoesNotPumpForPendingInk`() {
        // 负判据扫整个函数体（AGENTS：不许魔法行数窗口）——书写期间逐帧重绘＝新的"笔迹一直飘"
        val body = functionBody(canvas, "private fun needsAnotherFrame(")
        assertFalse("needsAnotherFrame 不许把 pending 计入续帧", body.any { it.contains("buffer") })
        assertTrue("已落墨的淡出笔迹仍然要续帧", body.any { it.contains("StrokeColors.FADE_MS") })
    }

    @Test
    fun `ackFiresOncePerCommittedStrokeAndNotForEmptyOnes`() {
        val body = functionBody(canvas, "private fun commit(")
        assertTrue(body.any { it.contains("onCommitted?.invoke(p.id)") })
        val guard = body.indexOfFirst { it.contains("p.points.isEmpty()") }
        val ack = body.indexOfFirst { it.contains("onCommitted?.invoke") }
        assertTrue("没点上屏就不许发 ack（观看端会提前交棒，屏幕上就一份墨都没有）：guard=$guard ack=$ack",
            guard in 0 until ack)
    }

    @Test
    fun `ackHookIsReboundOnEveryAttachAndClearedOnDestroy`() {
        // hideX 必穷举 restoreX 的同族：赋值与置零必须成对，否则会话重建后 ack 永久沉默
        assertTrue("acceptInteraction 每次重挂都要接上 ack 出口",
            service.any { it.contains("cv.onCommitted = { id -> onStrokeCommitted?.invoke(id) }") })
        val destroy = functionBody(service, "override fun onDestroy()")
        assertTrue(destroy.any { it.contains("canvas?.onCommitted = null") })
        assertTrue(destroy.any { it.contains("onStrokeCommitted = null") })
        assertTrue(destroy.any { it.contains("hook = null") })
    }

    @Test
    fun `viewSilenceBudgetIsTheBuffersOwnConstant`() {
        // 两处各写一个数字，改一边就变成"兜底比淡出还慢"或反之，且 JVM 测不出来
        assertTrue(canvas.any { it.contains("const val STROKE_SILENCE_MS = SharerStrokeBuffer.DEFAULT_SILENCE_MS") })
    }

    private fun assertNotNullish(v: Any?) = assertTrue("不应为 null", v != null)
}
