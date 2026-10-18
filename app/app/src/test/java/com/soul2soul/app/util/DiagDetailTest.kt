package com.soul2soul.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * detail 编码器的边界锁定（v0.2.59 待机/媒体遥测版）。
 *
 * 判据只有一条：**任何输入都必须压进 QUEUED_BUDGET**，因为补报路径会在前面再加
 * `at=<13位毫秒> `，而服务器 `safeTag` 截到 64 是**静默**的（没有溢出标记）——
 * 超出的部分不是报错，是读日志的人根本不知道少了什么。今天 `path=_` 就是这么来的。
 */
class DiagDetailTest {

    private fun assertFits(detail: String, what: String) {
        assertTrue("$what 超出补报预算 ${DiagDetail.QUEUED_BUDGET}：${detail.length} → $detail",
            detail.length <= DiagDetail.QUEUED_BUDGET)
    }

    private fun qos(
        rtt: Long = 29, path: String = "host->srflx", kbps: Long = 1200, edge: Long = 1598,
        vl: Double = 0.5, vj: Double = 21.0, vf: Double = 29.0,
        al: Double = 0.0, aj: Double = 3.8, ac: Double = 0.0,
    ) = DiagDetail.Qos(rtt, path, kbps, edge, vl, vj, vf, al, aj, ac)

    @Test
    fun `qos encodes the documented field order`() {
        assertEquals("q=29,h-s,1200,1598,5,21,29,0,3,0", DiagDetail.qos(qos()))
    }

    // 每一个"没读数"的格子都必须是 x，不许静默变成 0 —— 0 是"确实没丢包"的意思
    @Test
    fun `missing readings are x not zero`() {
        val d = DiagDetail.qos(
            qos(rtt = -1, kbps = -1, vl = -1.0, vj = Double.NaN, vf = -1.0, al = -1.0, aj = -1.0, ac = -1.0)
        )
        assertEquals("q=x,h-s,x,1598,x,x,x,x,x,x", d)
    }

    @Test
    fun `worst case numbers still fit the queued budget`() {
        val worst = qos(rtt = Long.MAX_VALUE, path = "relay->relay", kbps = Long.MAX_VALUE,
            edge = Long.MAX_VALUE, vl = 999.9, vj = 9e9, vf = 9e9, al = 999.9, aj = 9e9, ac = 9e9)
        val d = DiagDetail.qos(worst)
        assertEquals("q=999,r-r,99999,9999,1000,999,999,1000,999,999", d)
        assertFits(d, "qos 满载")
    }

    @Test
    fun `path code collapses the four candidate types`() {
        assertEquals("h-h", DiagDetail.pathCode("host->host"))
        assertEquals("s-r", DiagDetail.pathCode("srflx->relay"))
        assertEquals("o-o", DiagDetail.pathCode("->"))
        assertEquals("o-o", DiagDetail.pathCode("peer reflexive"))
    }

    @Test
    fun `heartbeat carries monotonic anchor and gap`() {
        val d = DiagDetail.heartbeat(
            elapsedSec = 123456, gapSec = 300, net = "wifi",
            doze = false, bucket = 10, wakeHeld = true, signalingUp = true,
        )
        assertEquals("k=123456,g=300,n=wifi,d=0,b=10,w=1,s=1", d)
        assertFits(d, "心跳")
    }

    @Test
    fun `heartbeat first beat and unknown bucket render as x`() {
        val d = DiagDetail.heartbeat(
            elapsedSec = 9_999_999_999, gapSec = -1, net = "cellularish",
            doze = true, bucket = -1, wakeHeld = false, signalingUp = false,
        )
        assertEquals("k=9999999,g=x,n=cell,d=1,b=x,w=0,s=0", d)
        assertFits(d, "心跳满载")
    }

    @Test
    fun `exit report carries only enums and age`() {
        val d = DiagDetail.exit(reason = 13, importance = 4, ageMin = -5)
        assertEquals("r=13 i=4 a=0", d)
        assertFits(DiagDetail.exit(13, 4, 999_999), "死因")
        assertEquals("r=13 i=4 a=99999", DiagDetail.exit(13, 4, 999_999))
    }
}
