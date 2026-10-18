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
        // 负年龄报 `a=x` 而不是夹成 `a=0`：AEI 只有绝对时间戳，算出负数说明死 later 系统校正过时钟，
        // 夹成 0 就是把"不知道多久以前"伪装成"刚刚死的"（10-18 双模拟器真实读数 r=16 i=125 a=0=刚重装）
        assertEquals("r=16 i=125 a=x", DiagDetail.exit(reason = 16, importance = 125, ageMin = -5))
        assertEquals("r=0 i=1000 a=0", DiagDetail.exit(0, 1000, 0))
        assertFits(DiagDetail.exit(13, 4, 999_999), "死因")
        assertEquals("r=13 i=4 a=99999", DiagDetail.exit(13, 4, 999_999))
    }

    @Test
    fun `candidate counting only trusts the four known kinds`() {
        // 真实 candidate 行形状：candidate:1:1:2:ip:...:typ host 192.168...:31000
        assertEquals(0, DiagDetail.candidateIndex("candidate:1:1:2:ip 10.0.0.1 5000 typ host"))
        assertEquals(1, DiagDetail.candidateIndex("candidate:2:1:2:ip 1.2.3.4 5000 typ srflx"))
        assertEquals(2, DiagDetail.candidateIndex("candidate:3:1:2:ip 1.2.3.4 5000 typ relay"))
        assertEquals(3, DiagDetail.candidateIndex("candidate:4:1:2:ip 1.2.3.4 5000 typ prflx"))
        // 认不出的一律丢弃：把未知类型塞进 relay 会伪造出"TURN 拿到了"这个结论
        assertEquals(-1, DiagDetail.candidateIndex("candidate:5:1:2:ip 1.2.3.4 5000 typ unknown"))
        assertEquals(-1, DiagDetail.candidateIndex(""))
        // 每个地址端口都带 "typ "，只有四种都试一遍才算数
        assertEquals(-1, DiagDetail.candidateIndex("candidate:6:1:2:ip 1.2.3.4 5000 typ rtp"))
    }

    @Test
    fun `gather line reports relay zero without padding`() {
        assertEquals("h=2 s=1 r=0 p=0", DiagDetail.gather(2, 1, 0, 0))
        val d = DiagDetail.gather(999, 999, 999, 999)
        assertEquals("h=99 s=99 r=99 p=99", d)
        assertFits(d, "gather")
    }

    @Test
    fun `gather line flags the cut-short reading`() {
        // 收集从没到达 COMPLETE 的那条必须带 inc=1："收完 6 条里 r=0"（TURN 分配失败）
        // 与"ICE 5 秒就死了、候选根本没长齐"是两个不同根因，混在一起读就会误判 TURN 配置坏了
        assertEquals("h=0 s=0 r=0 p=0 inc=1", DiagDetail.gather(0, 0, 0, 0, incomplete = true))
        assertFits(DiagDetail.gather(99, 99, 99, 99, incomplete = true), "gather+inc")
    }
}
