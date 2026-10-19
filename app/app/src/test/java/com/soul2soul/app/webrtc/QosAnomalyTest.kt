package com.soul2soul.app.webrtc

import com.soul2soul.app.util.DiagDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QoS 异常插条的判据边界。这些码是"卡那一下"唯一的现场证据，
 * 所以每个阈值都测两侧：≥ 报、< 不报，且不许把本端自停报成对端故障。
 */
class QosAnomalyTest {

    private fun s(
        rtt: Long = 30L,
        path: String = "host->host",
        vLoss: Double = 0.0,
        aLoss: Double = 0.0,
        fps: Double = 30.0,
    ) = DiagDetail.Qos(
        rttMs = rtt,
        path = path,
        availableKbps = 1200L,
        edge = 1080L,
        videoLossPct = vLoss,
        videoJitterMs = 21.0,
        videoFps = fps,
        audioLossPct = aLoss,
        audioJitterMs = 3.0,
        audioConcealedMs = 0.0,
    )

    private fun pick(prev: DiagDetail.Qos?, cur: DiagDetail.Qos) = QosAnomaly.pick(prev, cur)

    /**
     * 0 帧**不是**这里的判据（v0.2.62 t4 实测后撤掉）：静态画面单窗口 0 帧是常态，
     * 双端每 10s 各插一条 `b=fps0` 一分钟 12 条，把 P0 补报配额灌满、真异常反而被挤掉；
     * 而"画面真的死了"已经有 `video_stall`——3 个完整 10s 窗口的 streak + 本端停帧/对方锁屏门控。
     * 这条断言锁的是"只有一个真值来源"：把 fps0 规则加回来，它必须变红。
     */
    @Test
    fun `单窗口 0 帧不插异常条_video_stall 才是零帧的唯一判据`() {
        assertNull(pick(s(fps = 30.0), s(fps = 0.0)))
        assertNull(pick(s(fps = 30.0), s(fps = -1.0)))
        // 0 帧不报，但它盖不住别的异常：同一条读数里的丢包照报
        assertEquals("b=vl,50", pick(s(), s(fps = 0.0, vLoss = 5.0)))
    }

    @Test
    fun `选路变化两侧都已知才报_unknown_变化不报`() {
        assertEquals("b=pth,h-s>h-r", pick(s(path = "host->srflx"), s(path = "host->relay")))
        // unknown -> 已知：上一格根本没读数，报出来只会把"第一次拿到读数"读成故障
        assertNull(pick(s(path = "unknown"), s(path = "host->host")))
        assertNull(pick(s(path = "host->host"), s(path = "unknown")))
        assertNull(pick(s(path = "host->host"), s(path = "host->host")))
    }

    @Test
    fun `丢包恰好等于阈值算异常_差一点不算`() {
        assertEquals("b=vl,50", pick(s(), s(vLoss = QosAnomaly.LOSS_BAD_PCT)))
        assertNull(pick(s(), s(vLoss = 4.999)))
        assertEquals("b=al,50", pick(s(), s(aLoss = QosAnomaly.LOSS_BAD_PCT)))
        // -1 = 无读数，不许当成 0 也不许当成异常
        assertNull(pick(s(), s(vLoss = -1.0, aLoss = -1.0)))
    }

    @Test
    fun `视频丢包优先于音频丢包_一个 tick 只插一条`() {
        assertEquals(
            "b=vl,80",
            pick(s(), s(vLoss = 8.0, aLoss = 9.0)),
        )
    }

    @Test
    fun `rtt 跳变要同时满足 3 倍与 60ms 基线`() {
        assertEquals("b=rtt,60>180", pick(s(rtt = 60L), s(rtt = 180L)))
        assertNull(pick(s(rtt = 59L), s(rtt = 177L)))  // 基线不足 60ms
        assertNull(pick(s(rtt = 60L), s(rtt = 179L)))  // 不到 3 倍
        assertNull(pick(s(rtt = 200L), s(rtt = 100L))) // 变好不是异常
        // 基线 -1（无读数）时不许拿负数当基线放大出假告警
        assertNull(pick(s(rtt = -1L), s(rtt = 999L)))
    }

    @Test
    fun `首条没有 prev 时什么都不报（差分判据没有基线）`() {
        assertNull(pick(null, s(vLoss = 50.0)))
        assertNull(pick(null, s(fps = 0.0)))
        assertNull(pick(null, s(rtt = 999L)))
    }

    @Test
    fun `每条异常码都留得下补报预算`() {
        val codes = listOfNotNull(
            pick(s(path = "host->srflx"), s(path = "relay->relay")),
            pick(s(), s(vLoss = 100.0)),
            pick(s(), s(aLoss = 100.0)),
            pick(s(rtt = 900L), s(rtt = 999L)),
            DiagDetail.qos(
                DiagDetail.Qos(
                    rttMs = 999L, path = "relay->relay", availableKbps = 99999L, edge = 9999L,
                    videoLossPct = 100.0, videoJitterMs = 999.0, videoFps = 999.0,
                    audioLossPct = 100.0, audioJitterMs = 999.0, audioConcealedMs = 999.0,
                ),
            ),
            DiagDetail.gather(999, 999, 999, 999),
        )
        codes.forEach {
            assertTrue("超出预算(${it.length}): $it", it.length <= DiagDetail.QUEUED_BUDGET)
        }
    }

    @Test
    fun `极端读数被夹住而不是写出精确值`() {
        // 判据用的是原始值（1000→4000 确实是 4 倍），拼串时才夹到 999：
        // 夹在判据前会让"已经 999ms 以上"的连续两轮读数和 1000/4000 给出不同结论
        assertEquals("b=rtt,999>999", pick(s(rtt = 1000L), s(rtt = 4000L)))
        assertEquals("b=vl,1000", pick(s(), s(vLoss = 999.0)))
    }
}
