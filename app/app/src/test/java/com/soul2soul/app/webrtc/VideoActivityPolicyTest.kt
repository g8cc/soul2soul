package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoActivityPolicyTest {
    private fun metrics(
        nowMs: Long,
        fps: Double = 0.0,
        actualBps: Long = -1L,
        constrained: Boolean = false,
        controlMode: Boolean = false,
    ) = VideoActivityPolicy.Metrics(nowMs, fps, actualBps, constrained, controlMode)

    @Test
    fun `连续静止窗口后降到低帧率`() {
        var state = VideoActivityPolicy.State()
        repeat(2) { index ->
            val decision = VideoActivityPolicy.step(state, metrics((index + 1) * 2_000L))
            state = decision.state
            assertNull(decision.targetFps)
        }

        val decision = VideoActivityPolicy.step(state, metrics(6_000L))
        assertEquals(VideoActivityPolicy.STATIC_FPS, decision.targetFps)
        assertEquals(VideoActivityPolicy.Mode.STATIC, decision.state.mode)
    }

    @Test
    fun `未知帧率不累计成静止画面`() {
        val decision = VideoActivityPolicy.step(
            VideoActivityPolicy.State(quietWindows = 2),
            metrics(2_000L, fps = -1.0),
        )

        assertNull(decision.targetFps)
        assertEquals(2, decision.state.quietWindows)
    }

    @Test
    fun `持续出帧但实际码率很低也识别为低活动`() {
        var state = VideoActivityPolicy.State()
        repeat(2) { index ->
            state = VideoActivityPolicy.step(
                state,
                metrics((index + 1) * 2_000L, fps = 30.0, actualBps = 100_000L),
            ).state
        }

        val decision = VideoActivityPolicy.step(
            state,
            metrics(6_000L, fps = 30.0, actualBps = 100_000L),
        )
        assertEquals(VideoActivityPolicy.STATIC_FPS, decision.targetFps)
    }

    @Test
    fun `静态状态定期探测并在运动后恢复`() {
        val static = VideoActivityPolicy.State(
            mode = VideoActivityPolicy.Mode.STATIC,
            nextProbeAtMs = 8_000L,
        )
        val probe = VideoActivityPolicy.step(static, metrics(8_000L))
        assertEquals(VideoActivityPolicy.ACTIVE_FPS, probe.targetFps)
        assertEquals(VideoActivityPolicy.Mode.PROBING, probe.state.mode)

        val moving = VideoActivityPolicy.step(
            probe.state,
            metrics(10_000L, fps = VideoActivityPolicy.MOTION_FPS_THRESHOLD),
        )
        assertNull(moving.targetFps)
        assertEquals(VideoActivityPolicy.Mode.ACTIVE, moving.state.mode)
    }

    @Test
    fun `受限网络或远控立即退出省电模式`() {
        val static = VideoActivityPolicy.State(
            mode = VideoActivityPolicy.Mode.STATIC,
            nextProbeAtMs = 20_000L,
        )
        val constrained = VideoActivityPolicy.step(static, metrics(2_000L, constrained = true))
        assertEquals(VideoActivityPolicy.ACTIVE_FPS, constrained.targetFps)
        assertEquals(VideoActivityPolicy.Mode.ACTIVE, constrained.state.mode)

        val controlled = VideoActivityPolicy.step(static, metrics(2_000L, controlMode = true))
        assertEquals(VideoActivityPolicy.ACTIVE_FPS, controlled.targetFps)
        assertEquals(VideoActivityPolicy.Mode.ACTIVE, controlled.state.mode)
    }
}
