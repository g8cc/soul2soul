package com.soul2soul.app.session

import com.soul2soul.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观看端路由决策用例：锁定"迟到回声误杀新页面"与"授权阶段反悔卡在连接中"两类真机事故。
 */
class SignalRouterTest {

    // ---------- WS 信令 ----------

    @Test
    fun `sdp ice 在 client 未就绪时缓存 就绪后转发`() {
        assertEquals(
            SignalRouter.SignalAction.BufferMedia,
            SignalRouter.routeSignal("sdp", accepted = true, clientReady = false),
        )
        assertEquals(
            SignalRouter.SignalAction.DispatchMedia,
            SignalRouter.routeSignal("sdp", accepted = true, clientReady = true),
        )
        assertEquals(
            SignalRouter.SignalAction.BufferMedia,
            SignalRouter.routeSignal("ice", accepted = true, clientReady = false),
        )
        assertEquals(
            SignalRouter.SignalAction.DispatchMedia,
            SignalRouter.routeSignal("ice", accepted = true, clientReady = true),
        )
    }

    @Test
    fun `迟到的 bye 在未接听时被忽略 不新页面误杀`() {
        assertEquals(
            SignalRouter.SignalAction.None,
            SignalRouter.routeSignal("bye", accepted = false, clientReady = false),
        )
        assertEquals(
            SignalRouter.SignalAction.None,
            SignalRouter.routeSignal("peer.gone", accepted = false, clientReady = true),
        )
    }

    @Test
    fun `已接听后 bye 与 peer离线 给出不同的告别文案`() {
        val bye = SignalRouter.routeSignal("bye", accepted = true, clientReady = true)
        assertTrue(bye is SignalRouter.SignalAction.PeerEnded)
        assertEquals(R.string.peer_ended, (bye as SignalRouter.SignalAction.PeerEnded).noticeRes)
        val gone = SignalRouter.routeSignal("peer.gone", accepted = true, clientReady = true)
        assertTrue(gone is SignalRouter.SignalAction.PeerEnded)
        assertEquals(R.string.peer_lost, (gone as SignalRouter.SignalAction.PeerEnded).noticeRes)
    }

    @Test
    fun `call取消 双语义：未接听停铃即走 已接听标记远端结束并清理`() {
        assertEquals(
            SignalRouter.SignalAction.CallerCanceledBeforeAccept,
            SignalRouter.routeSignal("call.canceled", accepted = false, clientReady = false),
        )
        assertEquals(
            SignalRouter.SignalAction.CanceledAfterAccept,
            SignalRouter.routeSignal("call.canceled", accepted = true, clientReady = true),
        )
    }

    @Test
    fun `未知信令类型一律忽略`() {
        assertEquals(
            SignalRouter.SignalAction.None,
            SignalRouter.routeSignal("incoming", accepted = true, clientReady = true),
        )
        assertEquals(
            SignalRouter.SignalAction.None,
            SignalRouter.routeSignal("", accepted = false, clientReady = false),
        )
    }

    // ---------- DataChannel ----------

    @Test
    fun `screenoff 总是提示对方锁屏`() {
        assertEquals(
            SignalRouter.DataAction.PeerScreenOff,
            SignalRouter.routeData("screenoff", controlMode = false, live = true),
        )
        assertEquals(
            SignalRouter.DataAction.PeerScreenOff,
            SignalRouter.routeData("screenoff", controlMode = true, live = false),
        )
    }

    @Test
    fun `screenon 仅在仍连通时收起横幅`() {
        assertEquals(
            SignalRouter.DataAction.HideBanner,
            SignalRouter.routeData("screenon", controlMode = false, live = true),
        )
        // !live：会话已在收口，不能把状态条从"已结束"文案上抢走
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData("screenon", controlMode = false, live = false),
        )
    }

    @Test
    fun `ctl_denied 只在操控模式内生效 非操控端静默`() {
        assertEquals(
            SignalRouter.DataAction.RevokedBanner,
            SignalRouter.routeData("ctl_denied", controlMode = true, live = true),
        )
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData("ctl_denied", controlMode = false, live = true),
        )
    }

    @Test
    fun `远程注入失败只在操控模式显示诊断`() {
        assertEquals(
            SignalRouter.DataAction.ControlFailure(R.string.ctl_error_service),
            SignalRouter.routeData("ctl_error", controlMode = true, live = true, reason = "service_unavailable"),
        )
        assertEquals(
            SignalRouter.DataAction.ControlFailure(R.string.ctl_error_capability),
            SignalRouter.routeData("ctl_error", controlMode = true, live = true, reason = "gesture_capability_missing"),
        )
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData("ctl_error", controlMode = false, live = true, reason = "service_unavailable"),
        )
    }

    @Test
    fun `未知 k 走静默 由悬浮窗层自行消费`() {
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData("emoji", controlMode = false, live = true),
        )
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData("", controlMode = true, live = true),
        )
    }
}
