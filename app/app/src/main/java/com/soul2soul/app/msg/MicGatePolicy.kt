package com.soul2soul.app.msg

/**
 * 语音权限授权返回后的续录决策（纯函数，JVM 锁定 v0.2.34 踩坑）：
 * 只有"按住说话"发起的请求，授权通过才自动续上这次录音；
 * 切进语音模式时的预请求授权回来后绝不能凭空开录（用户没按任何按钮）。
 */
internal object MicGatePolicy {
    fun shouldAutoStartRecording(
        granted: Boolean,
        fromHold: Boolean,
        voiceMode: Boolean,
        holdStarted: Boolean,
        recording: Boolean,
    ): Boolean = granted && fromHold && voiceMode && !holdStarted && !recording
}
