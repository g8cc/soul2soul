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

    private fun pick(prev: DiagDetail.Qos?, cur: DiagDetail.Qos) =
        QosAnomaly.pick(prev, cur, framesBlockedByUs = false, peerPaused = false)

    @Test
    fun `帧率为 0 且不是自己停的帧才报 fps0`() {
        assertEquals("b=fps0", pick(s(fps = 30.0), s(fps = 0.0)))
        assertNull(pick(s(fps = 30.0), s(fps = 1.0)))
        // 负数 = stats 没给这一格读数，不是"卡成 0 帧"，不许伪造现场
        assertNull(pick(s(fps = 30.0), s(fps = -1.0)))
    }

    @Test
    fun `本端停帧或对方锁屏时 fps0 是设计行为_不报`() {
        val bad = s(fps = 0.0)
        assertNull(
            QosAnomaly.pick(s(), bad, framesBlockedByUs = false, peerPaused = true),
        )
        assertNull(
            QosAnomaly.pick(s(), bad, framesBlockedByUs = true, peerPaused = false),
        )
        // 抑制 fps0 之后仍要看见别的异常，不能被这条吞掉
        assertEquals(
            "b=vl,50",
            QosAnomaly.pick(
                s(),
                s(fps = 0.0, vLoss = 5.0),
                framesBlockedByUs = true,
                peerPaused = false,
            ),
        )
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
    fun `首条没有 prev 时只可能报 fps0`() {
        assertNull(pick(null, s(vLoss = 50.0)))
        assertEquals("b=fps0", pick(null, s(fps = 0.0)))
    }

    @Test
    fun `每条异常码都留得下补报预算`() {
        val codes = listOfNotNull(
            pick(s(), s(fps = 0.0)),
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
