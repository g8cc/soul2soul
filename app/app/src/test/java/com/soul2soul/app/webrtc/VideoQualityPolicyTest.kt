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

    @Test
    fun `进入操控档_非最低档都降到九六零`() {
        assertEquals(
            VideoQualityPolicy.DEGRADED_EDGE,
            VideoQualityPolicy.controlEnterEdge(VideoQualityPolicy.HD_EDGE),
        )
        assertEquals(
            VideoQualityPolicy.DEGRADED_EDGE,
            VideoQualityPolicy.controlEnterEdge(VideoQualityPolicy.DEFAULT_EDGE),
        )
        assertEquals(
            VideoQualityPolicy.DEGRADED_EDGE,
            VideoQualityPolicy.controlEnterEdge(VideoQualityPolicy.LEGACY_EDGE),
        )
    }

    @Test
    fun `已在最低档进入操控不必再切`() {
        assertNull(VideoQualityPolicy.controlEnterEdge(VideoQualityPolicy.DEGRADED_EDGE))
        // 非法值先归一再判：低于 960 的请求归到最低档 → 无需切换
        assertNull(VideoQualityPolicy.controlEnterEdge(600))
    }

    // v0.2.62：带宽估计器爬坡期的 300kbps 是初值不是实测，不许拿它动画质（双模拟器实测：
    // 建会头两拍都读 300，触发降档那一刻同一列已是 5.38Mbps）
    @Test
    fun `爬坡期低带宽不累计降档连拍`() {
        val ramping = VideoQualityPolicy.Metrics(
            rttMs = 18, lossPercent = -1.0, availableBitrateBps = 300_000,
            outboundFps = -1.0, bandwidthSettled = false,
        )
        val d = VideoQualityPolicy.step(1600, 1600, ramping, highStreak = 2, lowStreak = 0)
        assertNull("爬坡期读到 300kbps 不能凑够 3 连拍触发降档", d.targetEdge)
        assertEquals("爬坡期不判坏，连拍必须清零而不是继续累加", 0, d.highStreak)
    }

    @Test
    fun `带宽已稳后同样的读数照常判坏`() {
        val settled = rampMetrics(bandwidthSettled = true)
        val d = VideoQualityPolicy.step(1600, 1600, settled, highStreak = 2, lowStreak = 0)
        assertEquals("门槛放开后判据不能被一起删掉", 1280, d.targetEdge)
    }

    @Test
    fun `爬坡期低带宽也不许被当成回升条件`() {
        // available>0 又不是"未知"，isGood 里那条 available<=0 的放行不能顺手放过它
        val d = VideoQualityPolicy.step(
            1600, 1280, rampMetrics(bandwidthSettled = false), highStreak = 0, lowStreak = 9,
        )
        assertNull(d.targetEdge)
        assertEquals("爬坡期不计回升连拍", 0, d.lowStreak)

        val good = VideoQualityPolicy.step(
            1600, 1280,
            VideoQualityPolicy.Metrics(20, -1.0, 4_100_000, -1.0, bandwidthSettled = true),
            highStreak = 0, lowStreak = 9,
        )
        assertEquals("带宽真够且已稳时照常回升", 1600, good.targetEdge)
    }

    @Test
    fun `rtt与丢包在爬坡期照常参与判坏`() {
        val hot = VideoQualityPolicy.Metrics(
            rttMs = 400, lossPercent = -1.0, availableBitrateBps = 300_000,
            outboundFps = -1.0, bandwidthSettled = false,
        )
        assertEquals("只挡带宽这一路，别把 rtt/丢包一起挡了", 1280,
            VideoQualityPolicy.step(1600, 1600, hot, highStreak = 2, lowStreak = 0).targetEdge)
    }

    private fun rampMetrics(bandwidthSettled: Boolean) = VideoQualityPolicy.Metrics(
        rttMs = 18, lossPercent = -1.0, availableBitrateBps = 300_000,
        outboundFps = -1.0, bandwidthSettled = bandwidthSettled,
    )
}
