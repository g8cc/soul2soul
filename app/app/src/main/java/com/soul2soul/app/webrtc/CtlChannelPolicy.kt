package com.soul2soul.app.webrtc

/**
 * 操控消息的通道选择策略（纯逻辑，JVM 可单测）。
 * 手势整笔自包含，走 unordered 的 ctlg 专用道，弱网丢包时不再被
 * 可靠有序 ctl 的队头阻塞拖成"一顿一顿"；
 * 授权开关/拒绝/错误必须保持因果顺序（"允许""收回"绝不能乱序），留在 ctl。
 */
object CtlChannelPolicy {

    const val CTLG = "ctlg"
    const val CTL = "ctl"
    const val ANNO = "anno"

    /**
     * 设备内录音频（FR-16）：唯一一条**二进制**通道，载荷是 `[codec id][ADTS][AU]`。
     * 它不走 channelOrder——JSON 信令永远留在 ctl/ctlg/anno，音频帧只在 aud 上，
     * 这样背压时丢掉的是音频，不会挤到笔迹或授权信令。
     */
    const val AUD = "aud"

    /** 手势消息的 k 值：只有整笔轨迹走快道，其余一律有序道 */
    fun isGesture(k: String): Boolean = k == "g"

    /** 返回尝试顺序（优先已 OPEN 的通道）；旧版共享端没有 ctlg，回退顺序保证兼容 */
    fun channelOrder(k: String): List<String> =
        if (isGesture(k)) listOf(CTLG, CTL, ANNO) else listOf(CTL, ANNO)
}
