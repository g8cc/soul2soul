package com.soul2soul.app.session

import com.soul2soul.app.R

/**
 * 观看端会话页的信令/数据路由决策（纯逻辑，JVM 可单测）。
 * 自 SessionActivity.handleSignal/onDataMessage 逐字提取：
 * SessionActivity 只做「决策 → 执行 UI/清理」的胶水，判定规则集中在此。
 */
object SignalRouter {

    /** WS 信令路由：未接听时迟到的 bye/取消是「上一轮呼叫的回声」，必须忽略以免误杀新页面 */
    sealed class SignalAction {
        object None : SignalAction()
        object BufferMedia : SignalAction()               // client 未就绪：缓存 offer/ice
        object DispatchMedia : SignalAction()             // 转发给 WebRtcClient
        data class PeerEnded(val noticeRes: Int) : SignalAction() // 已接听：endedRemotely + finish(sendBye=false)
        object CallerCanceledBeforeAccept : SignalAction() // 未接听就取消：停铃 + 提示 + finish（不发 bye）
        object CanceledAfterAccept : SignalAction()        // 授权阶段主叫反悔：别让对方对着"连接中"数 30 秒
    }

    fun routeSignal(type: String, accepted: Boolean, clientReady: Boolean): SignalAction = when (type) {
        "sdp", "ice" -> if (clientReady) SignalAction.DispatchMedia else SignalAction.BufferMedia
        "bye" -> if (accepted) SignalAction.PeerEnded(R.string.peer_ended) else SignalAction.None
        "peer.gone" -> if (accepted) SignalAction.PeerEnded(R.string.peer_lost) else SignalAction.None
        "call.canceled" -> if (accepted) SignalAction.CanceledAfterAccept else SignalAction.CallerCanceledBeforeAccept
        else -> SignalAction.None
    }

    /** DataChannel 状态通知路由 */
    sealed class DataAction {
        object None : DataAction()
        object PeerScreenOff : DataAction()  // 显示「对方锁屏了」
        object HideBanner : DataAction()     // 解锁且仍连通 → 收起状态条
        object RevokedBanner : DataAction()  // 操控中被收回授权 → 横幅说明原因
    }

    fun routeData(k: String, controlMode: Boolean, live: Boolean): DataAction = when (k) {
        "screenoff" -> DataAction.PeerScreenOff
        "screenon" -> if (live) DataAction.HideBanner else DataAction.None
        "ctl_denied" -> if (controlMode) DataAction.RevokedBanner else DataAction.None
        else -> DataAction.None
    }
}
