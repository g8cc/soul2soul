package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoQualityPolicyTest {
    private fun bad(rtt: Long = 400L) = VideoQualityPolicy.Metrics(rtt, 0.0, -1L)
    private fun good() = VideoQualityPolicy.Metrics(80L, 0.5, 6_000_000L)

    @Test
    fun `连续三次高延迟从高清降到均衡`() {
        var high = 0
        var low = 0
        repeat(2) {
            val d = VideoQualityPolicy.step(1920, 1920, bad(), high, low)
            high = d.highStreak
            low = d.lowStreak
            assertNull(d.targetEdge)
        }
        assertEquals(VideoQualityPolicy.DEFAULT_EDGE,
            VideoQualityPolicy.step(1920, 1920, bad(), high, low).targetEdge)
    }

    @Test
    fun `均衡档位继续恶化时降到低档`() {
        var high = 0
        var low = 0
        repeat(3) {
            val d = VideoQualityPolicy.step(1600, 1600, bad(), high, low)
            high = d.highStreak
            low = d.lowStreak
        }
        assertEquals(VideoQualityPolicy.LEGACY_EDGE,
            VideoQualityPolicy.step(1600, 1600, bad(), high, low).targetEdge)
        high = 0
        repeat(2) {
            high = VideoQualityPolicy.step(1600, VideoQualityPolicy.LEGACY_EDGE, bad(), high, 0).highStreak
        }
        assertEquals(VideoQualityPolicy.DEGRADED_EDGE,
            VideoQualityPolicy.step(1600, VideoQualityPolicy.LEGACY_EDGE, bad(), high, 0).targetEdge)
    }

    @Test
    fun `旧版1280档位保持兼容并可继续降档`() {
        assertEquals(VideoQualityPolicy.LEGACY_EDGE, VideoQualityPolicy.normalizeEdge(1280))
        var high = 0
        repeat(2) {
            high = VideoQualityPolicy.step(1280, 1280, bad(), high, 0).highStreak
        }
        assertEquals(VideoQualityPolicy.DEGRADED_EDGE,
            VideoQualityPolicy.step(1280, 1280, bad(), high, 0).targetEdge)
    }

    @Test
    fun `网络恢复十次后回到用户选择档位`() {
        var high = 0
        var low = 0
        repeat(9) {
            val d = VideoQualityPolicy.step(1920, 960, good(), high, low)
            high = d.highStreak
            low = d.lowStreak
            assertNull(d.targetEdge)
        }
        assertEquals(VideoQualityPolicy.HD_EDGE,
            VideoQualityPolicy.step(1920, 960, good(), high, low).targetEdge)
    }

    @Test
    fun `未知带宽不阻止低延迟恢复`() {
        val metrics = VideoQualityPolicy.Metrics(80L, -1.0, -1L)
        var high = 0
        var low = 0
        repeat(9) {
            val d = VideoQualityPolicy.step(1600, 960, metrics, high, low)
            high = d.highStreak
            low = d.lowStreak
        }
        assertEquals(VideoQualityPolicy.DEFAULT_EDGE,
            VideoQualityPolicy.step(1600, 960, metrics, high, low).targetEdge)
    }

    @Test
    fun `编码帧率过低时即使RTT很好也降档`() {
        val metrics = VideoQualityPolicy.Metrics(60L, 0.2, 8_000_000L, outboundFps = 14.0)
        var high = 0
        repeat(2) {
            high = VideoQualityPolicy.step(1920, 1920, metrics, high, 0).highStreak
        }
        assertEquals(VideoQualityPolicy.DEFAULT_EDGE,
            VideoQualityPolicy.step(1920, 1920, metrics, high, 0).targetEdge)
    }

    @Test
    fun `边界和码率档位归一化`() {
        assertEquals(VideoQualityPolicy.DEGRADED_EDGE, VideoQualityPolicy.normalizeEdge(720))
        assertEquals(VideoQualityPolicy.LEGACY_EDGE, VideoQualityPolicy.normalizeEdge(1280))
        assertEquals(VideoQualityPolicy.DEFAULT_EDGE, VideoQualityPolicy.normalizeEdge(1600))
        assertEquals(VideoQualityPolicy.HD_EDGE, VideoQualityPolicy.normalizeEdge(2000))
        assertEquals(4_500_000L, VideoQualityPolicy.requiredBitrateBps(1920))
    }

    @Test
    fun `丢包率按相邻采样窗口计算而不是累计整场`() {
        assertEquals(
            2.0,
            VideoQualityPolicy.intervalLossPercent(10, 100, 12, 198),
            0.001,
        )
    }

    @Test
    fun `缺少远端received时用发送增量估算丢包`() {
        assertEquals(
            5.0,
            VideoQualityPolicy.intervalLossPercent(10, -1, 15, -1, 100, 200),
            0.001,
        )
    }

    @Test
    fun `首个采样和计数器回绕不参与自适应`() {
        assertEquals(
            -1.0,
            VideoQualityPolicy.intervalLossPercent(-1, -1, 3, 97),
            0.001,
        )
        assertEquals(
            -1.0,
            VideoQualityPolicy.intervalLossPercent(10, 100, 2, 120),
            0.001,
        )
    }
}
