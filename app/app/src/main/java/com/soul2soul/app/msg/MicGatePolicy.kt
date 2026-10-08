package com.soul2soul.app.msg

/**
 * 语音权限授权返回后的续录决策（纯函数，JVM 锁定 v0.2.34 踩坑）：
 * 只有"按住说话"发起的请求，授权通过才自动续上这次录音；
 * 切进语音模式时的预请求授权回来后绝不能凭空开录（用户没按任何按钮）。
 */
internal object MicGatePolicy {
    /** 缺权限时的处置：NONE=不用管；REQUEST=直接问系统；EXPLAIN=先人话解释再问系统；GUIDE=只能去设置 */
    enum class MicGateAction { NONE, REQUEST, EXPLAIN, GUIDE }

    fun shouldAutoStartRecording(
        granted: Boolean,
        fromHold: Boolean,
        voiceMode: Boolean,
        holdStarted: Boolean,
        recording: Boolean,
    ): Boolean = granted && fromHold && voiceMode && !holdStarted && !recording

    /**
     * 进语音/按住说话时缺权限的处置决策（对齐主流 App 的弹窗礼仪）：
     * 从没授权过 → 直接系统弹窗（首次不啰嗦）；
     * 曾授权又没了 → 十有八九是国产 ROM 覆盖安装把运行时权限重置了，
     * 先弹人话解释，别让用户以为"权限设置又灵异失效"（v0.2.34 真机踩坑）。
     */
    fun onMicMissing(granted: Boolean, everGranted: Boolean): MicGateAction =
        if (granted) MicGateAction.NONE
        else if (everGranted) MicGateAction.EXPLAIN
        else MicGateAction.REQUEST

    /**
     * 系统弹窗拒绝后的出路：系统还愿意再问 → 一句提示即可；
     * 不再愿意问（勾选了"禁止后再询问"/MIUI 静默拦截）→ 只有"去设置"直达才是出路，
     * 光 toast 会让用户卡在原地（"说人话、给出路"约定）。
     */
    fun onDenied(rationale: Boolean): MicGateAction =
        if (rationale) MicGateAction.NONE else MicGateAction.GUIDE
}
