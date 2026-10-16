package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ctlg 快道选路锁定（v0.2.40 流畅度批次）：
 * 手势必须优先无序道（消队头阻塞），授权类信令绝不能上无序道（乱序=隐私事故）。
 */
class CtlChannelPolicyTest {

    @Test
    fun `手势优先ctlg并保留旧对端回退链`() {
        assertEquals(listOf("ctlg", "ctl", "anno"), CtlChannelPolicy.channelOrder("g"))
    }

    @Test
    fun `非手势一律有序道打头`() {
        for (k in listOf("ctl_denied", "ctl_error", "res", "acc", "")) {
            assertEquals(listOf("ctl", "anno"), CtlChannelPolicy.channelOrder(k))
        }
    }

    @Test
    fun `只有k等于g算手势`() {
        assertEquals(true, CtlChannelPolicy.isGesture("g"))
        assertEquals(false, CtlChannelPolicy.isGesture("go"))
        assertEquals(false, CtlChannelPolicy.isGesture("G"))
    }
}
