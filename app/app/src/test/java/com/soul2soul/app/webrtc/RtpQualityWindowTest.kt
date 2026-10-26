package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class RtpQualityWindowTest {
    @Test
    fun `累计音频计数转换成采样窗口指标`() {
        val metrics = RtpQualityWindow.between(
            RtpQualityWindow.Counters(10, 100, 0.02, 4_800),
            RtpQualityWindow.Counters(15, 195, 0.035, 5_280),
        )
        assertEquals(5.0, metrics.packetLossPercent, 0.001)
        assertEquals(35.0, metrics.jitterMs, 0.001)
        assertEquals(10.0, metrics.concealedMs, 0.001)
    }

    @Test
    fun `首个样本和计数器重置时不伪报丢包或隐藏时长`() {
        val sample = RtpQualityWindow.Counters(3, 20, 0.01, 100)
        val first = RtpQualityWindow.between(null, sample)
        assertEquals(-1.0, first.packetLossPercent, 0.001)
        assertEquals(-1.0, first.concealedMs, 0.001)

        val reset = RtpQualityWindow.between(
            RtpQualityWindow.Counters(10, 100, 0.01, 500),
            RtpQualityWindow.Counters(1, 10, 0.01, 10),
        )
        assertEquals(-1.0, reset.packetLossPercent, 0.001)
        assertEquals(-1.0, reset.concealedMs, 0.001)
    }

    @Test
    fun `缺失音频统计返回不可用而非零质量`() {
        val metrics = RtpQualityWindow.between(null, null)
        assertEquals(-1.0, metrics.packetLossPercent, 0.001)
        assertEquals(-1.0, metrics.jitterMs, 0.001)
        assertEquals(-1.0, metrics.concealedMs, 0.001)
    }

    // v0.2.62：这一版 libwebrtc 的 outbound/inbound-rtp 没有 framesPerSecond，帧率只能区间相减。
    // 关键是"算不出"必须返回 -1（没读数），绝不能返回 0——0 会被观看端判成画面卡死。
    @Test
    fun `帧率由累计计数与真实窗口算出`() {
        assertEquals(30.0, RtpQualityWindow.framesFps(100, 400, 10_000), 0.001)
        assertEquals(0.5, RtpQualityWindow.framesFps(0, 1, 2_000), 0.001)
    }

    @Test
    fun `帧率算不出时返回未知而不是零帧`() {
        assertEquals(-1.0, RtpQualityWindow.framesFps(-1, 300, 10_000), 0.001)
        assertEquals(-1.0, RtpQualityWindow.framesFps(300, -1, 10_000), 0.001)
        assertEquals("计数回退（流重建）不折算成 0 帧", -1.0,
            RtpQualityWindow.framesFps(400, 100, 10_000), 0.001)
        assertEquals("窗口太短（第一拍没有基准）不算帧率", -1.0,
            RtpQualityWindow.framesFps(100, 130, 500), 0.001)
        assertEquals("真·零帧：计数没涨但窗口够长，这才是 0.0", 0.0,
            RtpQualityWindow.framesFps(100, 100, 10_000), 0.001)
    }

    @Test
    fun `视频字节累计值按真实窗口换算发送码率`() {
        assertEquals(800_000L, RtpQualityWindow.bitrateBps(100_000, 200_000, 1_000))
        assertEquals(400_000L, RtpQualityWindow.bitrateBps(200_000, 250_000, 1_000))
    }

    @Test
    fun `视频码率首样本回退和短窗口返回未知`() {
        assertEquals(-1L, RtpQualityWindow.bitrateBps(-1, 100, 2_000))
        assertEquals(-1L, RtpQualityWindow.bitrateBps(200, 100, 2_000))
        assertEquals(-1L, RtpQualityWindow.bitrateBps(100, 110, 500))
    }
}
