package com.soul2soul.app.session

import com.soul2soul.app.R
import com.soul2soul.app.webrtc.DeviceAudioPolicy

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
        data class PeerEnded(val noticeRes: Int) : SignalAction() // bye：已接听后对端明确结束
        data class SignalLost(val source: SignalRecoveryTracker.Source) : SignalAction()
        data class SignalRestored(val source: SignalRecoveryTracker.Source) : SignalAction()
        object CallerCanceledBeforeAccept : SignalAction() // 未接听就取消：停铃 + 提示 + finish（不发 bye）
        object CanceledAfterAccept : SignalAction()        // 授权阶段主叫反悔：别让对方对着"连接中"数 30 秒
    }

    fun routeSignal(
        type: String,
        accepted: Boolean,
        clientReady: Boolean,
        connected: Boolean? = null,
        peerOnline: Boolean? = null,
    ): SignalAction = when (type) {
        "sdp", "ice" -> if (clientReady) SignalAction.DispatchMedia else SignalAction.BufferMedia
        "bye" -> if (accepted) SignalAction.PeerEnded(R.string.peer_ended) else SignalAction.None
        "peer.gone" -> if (accepted) SignalAction.SignalLost(SignalRecoveryTracker.Source.PEER) else SignalAction.None
        "peer.online" -> if (accepted) SignalAction.SignalRestored(SignalRecoveryTracker.Source.PEER) else SignalAction.None
        "local.connState" -> if (!accepted) SignalAction.None else when (connected) {
            true -> SignalAction.SignalRestored(SignalRecoveryTracker.Source.LOCAL)
            false -> SignalAction.SignalLost(SignalRecoveryTracker.Source.LOCAL)
            null -> SignalAction.None
        }
        "registered" -> if (!accepted) SignalAction.None else when (peerOnline) {
            true -> SignalAction.SignalRestored(SignalRecoveryTracker.Source.PEER)
            false -> SignalAction.SignalLost(SignalRecoveryTracker.Source.PEER)
            null -> SignalAction.None
        }
        "call.canceled" -> if (accepted) SignalAction.CanceledAfterAccept else SignalAction.CallerCanceledBeforeAccept
        else -> SignalAction.None
    }

    /** DataChannel 状态通知路由 */
    sealed class DataAction {
        object None : DataAction()
        object PeerScreenOff : DataAction()  // 显示「对方锁屏了」
        object PeerVideoOff : DataAction()   // 显示「对方画面已停」：系统撤销录屏授权，会话存续
        object HideBanner : DataAction()     // 解锁且仍连通 → 收起状态条
        object RevokedBanner : DataAction()  // 操控中被收回授权 → 横幅说明原因
        data class ControlFailure(val messageRes: Int) : DataAction()

        /** 共享端把这一笔落墨了（0.2.65 起笔迹缓冲到收笔）→ 观看端的本地副本可以交棒淡掉了 */
        data class EchoAck(val id: String) : DataAction()

        /**
         * 共享端报来它自己那台手机的内录状况（FR-16）。这里只做**载荷规范化**，
         * 文案判定收在 [deviceAudioBannerRes] 一处——横幅要在"收到 k=aud"和"会话连上"两个时刻
         * 重算，把判定写进路由就等于写两遍。
         */
        data class PeerDeviceAudio(
            val on: Boolean,
            val state: String?,
            /** 对端是否明确声明能消费 `audplay`；缺失=旧版本，不发未知 k。 */
            val playbackSupported: Boolean = false,
        ) : DataAction()

    }

    fun routeData(
        k: String,
        controlMode: Boolean,
        live: Boolean,
        reason: String = "",
        remoteVideoPaused: Boolean = false,
        id: String = "",
        audOn: Int = -1,
        audState: String = "",
        audPlayback: Int = -1,
    ): DataAction = when (k) {
        "screenoff" -> DataAction.PeerScreenOff
        "videooff" -> DataAction.PeerVideoOff
        // 空 id 的 ack 什么都不认：交棒是按 id 命中的，认了一条空 id 就等于把"最新一笔"
        // 这种猜测写进协议
        "a" -> if (id.isEmpty()) DataAction.None else DataAction.EchoAck(id)
        // `on` 只认 0/1，别的值（旧版乱发、字段缺失）一律当"没读数"：不许把猜出来的 false
        // 报成"TA 没开"——那会把一个还在起采集的会话读成一条故障
        "aud" -> when {
            audOn != 0 && audOn != 1 -> DataAction.None
            else -> DataAction.PeerDeviceAudio(
                on = audOn == 1,
                state = audStateOrNull(audState),
                playbackSupported = audPlayback == 1,
            )
        }
        // 解锁 ≠ 画面回来了：她可能一直不点「重新开画面」。这一路必须什么都不做，
        // 否则 0.2.64 的保活会让横幅在画面仍然停着的时候先收起来，对方读成"好了"
        "screenon" -> if (live && !remoteVideoPaused) DataAction.HideBanner else DataAction.None
        "videoon" -> if (live) DataAction.HideBanner else DataAction.None
        "ctl_denied" -> if (controlMode) DataAction.RevokedBanner else DataAction.None
        "ctl_error" -> if (controlMode) {
            DataAction.ControlFailure(
                when (reason) {
                    "service_unavailable" -> R.string.ctl_error_service
                    "gesture_capability_missing" -> R.string.ctl_error_capability
                    else -> R.string.ctl_error_generic
                }
            )
        } else DataAction.None
        else -> DataAction.None
    }

    /** 认识的 state 才返回，其余一律 null=无读数（协议里加第三态必须先有一条可信的第二路信号） */
    fun audStateOrNull(raw: String): String? = raw.takeIf { it in DeviceAudioPolicy.STATES }

    /**
     * 观看端「我到底听不听得到他手机里的声音」这一行的**唯一**判定。
     *
     * 三个输入各有各的"没读数"，混起来就会把不知道说成故障：
     * @param peerOn 最近一条 `k=aud` 的 on；从没收到过 = null
     * @param peerState 那条消息里的 state，认不出来 = null
     * @param channelSeen 本端见过 `aud` 通道吗——只有新版共享端会创建它，
     *   这是"对面版本太旧"的唯一机器判据（旧共享端既不会创建通道，也不会发这条消息）
     * @param live 会话已连通：没连通之前不许挂那条"版本太旧"，否则正常通话中途会闪一句无关提示
     * @return null = 这一行不显示
     */
    fun deviceAudioBannerRes(
        peerOn: Boolean?,
        peerState: String?,
        channelSeen: Boolean,
        live: Boolean,
        playbackFailed: Boolean = false,
    ): Int? = when {
        playbackFailed -> R.string.aud_playback_failed
        peerOn == false -> R.string.aud_peer_off
        peerOn == true && peerState == DeviceAudioPolicy.STATE_LIVE -> R.string.aud_peer_live
        peerOn == true && peerState == DeviceAudioPolicy.STATE_SILENT -> R.string.aud_peer_silent
        // 开了但 state 读不出来：什么都不判。宁可这一行空着，也不把"我解不了这条消息"
        // 报成"TA 那边没声音"（与 maxAbsSample 那条"没读到东西不许当静音"同一个规矩）
        peerOn == true -> null
        // 从没收到过 k=aud，而通道也没出现过 ⇒ 对面那台还没有这套功能
        live && !channelSeen -> R.string.aud_peer_too_old
        else -> null
    }
}
