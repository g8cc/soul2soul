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
    fun `videooff 与 screenoff 一样要横幅 但文案是画面已停`() {
        assertEquals(
            SignalRouter.DataAction.PeerVideoOff,
            SignalRouter.routeData("videooff", controlMode = false, live = true),
        )
        // 与 live 无关：撤销授权那一刻会话当然是活的，而迟到的 videooff 也不该被吞掉
        assertEquals(
            SignalRouter.DataAction.PeerVideoOff,
            SignalRouter.routeData("videooff", controlMode = true, live = false),
        )
    }

    @Test
    fun `解锁不许替对方撒谎：画面仍停着时 screenon 不收横幅`() {
        // 0.2.64 保活语义：她锁屏 → 系统撤授权 → 画面停、通话续。她解锁后如果一直没点
        // 「重新开画面」，观看端只收到 screenon；此时收起横幅＝谎称画面回来了
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData(
                "screenon", controlMode = false, live = true, remoteVideoPaused = true
            ),
        )
        // Android 12 及以下锁屏不撤授权：没有 videooff 在前，解锁照常收横幅
        assertEquals(
            SignalRouter.DataAction.HideBanner,
            SignalRouter.routeData(
                "screenon", controlMode = false, live = true, remoteVideoPaused = false
            ),
        )
        // 会话已收口：不许把状态条从"已结束"文案上抢走
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData(
                "screenon", controlMode = false, live = false, remoteVideoPaused = true
            ),
        )
    }

    @Test
    fun `videoon 是画面回来的唯一收横幅入口 未连通时不碰状态条`() {
        assertEquals(
            SignalRouter.DataAction.HideBanner,
            SignalRouter.routeData("videoon", controlMode = false, live = true),
        )
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData("videoon", controlMode = false, live = false),
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

    @Test
    fun `k=a 交棒 ack 只按 id 认`() {
        assertEquals(
            SignalRouter.DataAction.EchoAck("s1"),
            SignalRouter.routeData("a", controlMode = false, live = true, id = "s1"),
        )
        // 空 id 的 ack 什么都不认：认了就等于把"猜最新一笔"写进协议——
        // 交棒是按 id 命中的，猜错会把还没落墨的那条本地副本提前淡掉
        assertEquals(
            SignalRouter.DataAction.None,
            SignalRouter.routeData("a", controlMode = true, live = true, id = ""),
        )
    }
}
