package com.soul2soul.app.msg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 v0.2.34 真机踩坑：切语音模式时预请求麦克风权限，
 * 授权弹窗回来后绝不能凭空开始录音（用户没按任何按钮）。
 * 只有"按住说话"发起的请求才自动续录。
 */
class MicGatePolicyTest {

    private fun shouldStart(
        granted: Boolean = true,
        fromHold: Boolean = true,
        voiceMode: Boolean = true,
        holdStarted: Boolean = false,
        recording: Boolean = false,
    ) = MicGatePolicy.shouldAutoStartRecording(granted, fromHold, voiceMode, holdStarted, recording)

    @Test
    fun `按住说话授权通过后续上这次录音`() {
        assertTrue(shouldStart())
    }

    @Test
    fun `切语音模式的预请求授权回来后不凭空开录`() {
        assertFalse(shouldStart(fromHold = false))
    }

    @Test
    fun `授权被拒不开录`() {
        assertFalse(shouldStart(granted = false))
    }

    @Test
    fun `已退回文字模式不开录`() {
        assertFalse(shouldStart(voiceMode = false))
    }

    @Test
    fun `按住流程已自己开录则回调不重复开`() {
        // holdStarted=true： DOWN 时已有权限走的是即时开录路径
        assertFalse(shouldStart(holdStarted = true))
    }

    @Test
    fun `上一段还在录不开第二段`() {
        assertFalse(shouldStart(recording = true))
    }
}
