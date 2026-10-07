package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class IceRecoveryPolicyTest {
    @Test
    fun `已连通的发起方立即重启并等待恢复`() {
        assertEquals(
            IceRecoveryPolicy.Decision(restartNow = true, armRecoveryTimeout = true),
            IceRecoveryPolicy.onFailed(established = true, isOfferer = true),
        )
    }

    @Test
    fun `已连通的应答方只等待对端重启`() {
        assertEquals(
            IceRecoveryPolicy.Decision(restartNow = false, armRecoveryTimeout = true),
            IceRecoveryPolicy.onFailed(established = true, isOfferer = false),
        )
    }

    @Test
    fun `初次连接失败只沿用总超时`() {
        assertEquals(
            IceRecoveryPolicy.Decision(restartNow = false, armRecoveryTimeout = false),
            IceRecoveryPolicy.onFailed(established = false, isOfferer = true),
        )
        assertEquals(
            IceRecoveryPolicy.Decision(restartNow = false, armRecoveryTimeout = false),
            IceRecoveryPolicy.onFailed(established = false, isOfferer = false),
        )
    }
}
