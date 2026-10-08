package com.soul2soul.app.msg

import org.junit.Assert.assertEquals
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

    // ---------- 缺权限处置（v0.2.34 后补的"弹窗礼仪"） ----------


    @Test
    fun `已授权什么都不做`() {
        assertEquals(MicGatePolicy.MicGateAction.NONE, MicGatePolicy.onMicMissing(granted = true, everGranted = false))
        assertEquals(MicGatePolicy.MicGateAction.NONE, MicGatePolicy.onMicMissing(granted = true, everGranted = true))
    }

    @Test
    fun `从没授权过首次直接问系统不啰嗦`() {
        assertEquals(MicGatePolicy.MicGateAction.REQUEST, MicGatePolicy.onMicMissing(granted = false, everGranted = false))
    }

    @Test
    fun `曾授权又消失_先人话解释再问_防权限灵异失效误会`() {
        // MIUI/HyperOS 覆盖安装会把运行时权限重置回"询问"，无障碍却保留——用户最怕这一刻
        assertEquals(MicGatePolicy.MicGateAction.EXPLAIN, MicGatePolicy.onMicMissing(granted = false, everGranted = true))
    }

    @Test
    fun `系统拒绝后还肯再问_一句提示即可`() {
        assertEquals(MicGatePolicy.MicGateAction.NONE, MicGatePolicy.onDenied(rationale = true))
    }

    @Test
    fun `系统不肯再问_永久拒绝_给去设置直达`() {
        assertEquals(MicGatePolicy.MicGateAction.GUIDE, MicGatePolicy.onDenied(rationale = false))
    }
}
