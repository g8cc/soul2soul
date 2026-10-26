package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTrafficPolicyTest {
    @Test
    fun `未拿到真实 bytesSent 不调整传输预算`() {
        assertEquals(
            3_500_000L,
            VideoTrafficPolicy.targetMaxBitrateBps(
                profileMaxBps = 3_500_000L,
                currentBps = 3_500_000L,
                availableBps = 1_000_000L,
                actualBps = -1L,
                outboundFps = 30.0,
                bandwidthSettled = true,
                staticMode = false,
            ),
        )
    }

    @Test
    fun `真实发送码率和可用带宽稳定后收紧预算`() {
        assertEquals(
            1_700_000L,
            VideoTrafficPolicy.targetMaxBitrateBps(
                profileMaxBps = 3_500_000L,
                currentBps = 3_500_000L,
                availableBps = 2_000_000L,
                actualBps = 1_900_000L,
                outboundFps = 30.0,
                bandwidthSettled = true,
                staticMode = false,
            ),
        )
        assertEquals(
            3_500_000L,
            VideoTrafficPolicy.targetMaxBitrateBps(
                profileMaxBps = 3_500_000L,
                currentBps = 1_700_000L,
                availableBps = 6_000_000L,
                actualBps = 1_800_000L,
                outboundFps = 30.0,
                bandwidthSettled = true,
                staticMode = false,
            ),
        )
    }

    @Test
    fun `静态画面不因瞬时带宽估计改变预算`() {
        assertEquals(
            1_700_000L,
            VideoTrafficPolicy.targetMaxBitrateBps(
                profileMaxBps = 3_500_000L,
                currentBps = 1_700_000L,
                availableBps = 6_000_000L,
                actualBps = 1_800_000L,
                outboundFps = 8.0,
                bandwidthSettled = true,
                staticMode = true,
            ),
        )
    }

    @Test
    fun `只有达到阈值才更新避免频繁改参数`() {
        assertFalse(VideoTrafficPolicy.shouldUpdate(3_500_000L, 3_300_000L))
        assertTrue(VideoTrafficPolicy.shouldUpdate(3_500_000L, 2_900_000L))
        assertTrue(VideoTrafficPolicy.shouldUpdate(-1L, 1_000_000L))
    }
}
