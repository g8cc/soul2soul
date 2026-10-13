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
}
