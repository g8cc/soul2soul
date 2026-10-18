package com.soul2soul.app.webrtc

import com.soul2soul.app.util.DiagDetail

/**
 * QoS 异常判据（纯函数，无 Android 依赖 ⇒ 边界能在 JVM 里逐条测）。
 *
 * `qos_tick` 每 10s 一条是背景，这几条形码才是现场：等到 10s 后的例行那条，
 * "卡那一下"早就被下一条正常读数盖掉了。一次 tick 只插**一条**（顺序即严重度），
 * 免得弱网时把补报队列的 P0 配额一次性灌满。
 */
object QosAnomaly {

    /** 丢包 ≥5% 才算异常：偶发 1~2 个包重传是常态，天天报就等于没有判据 */
    const val LOSS_BAD_PCT = 5.0

    /** rtt 相对上一条涨到 3 倍才算，且基线至少 60ms —— 20→61ms 也是 3 倍，但那不是故障 */
    const val RTT_JUMP_TIMES = 3.0
    const val RTT_JUMP_FLOOR_MS = 60L

    /**
     * @param framesBlockedByUs 本端自己把帧停了（surface 没了），与对端无关
     * @param peerPaused 对方锁屏 = 画面暂停是设计行为（语音继续），不许报成卡顿
     */
    fun pick(
        prev: DiagDetail.Qos?,
        cur: DiagDetail.Qos,
        framesBlockedByUs: Boolean,
        peerPaused: Boolean,
    ): String? {
        // 只认"确实 0 帧"：负数=这一格 stats 里没有读数，报出来就是把"没数据"洗成"卡死了"
        if (cur.videoFps == 0.0 && !framesBlockedByUs && !peerPaused) return "b=fps0"
        val before = prev ?: return null
        if (cur.path != before.path && cur.path != "unknown" && before.path != "unknown") {
            return "b=pth,${DiagDetail.pathCode(before.path)}>${DiagDetail.pathCode(cur.path)}"
        }
        if (cur.videoLossPct >= LOSS_BAD_PCT) return "b=vl,${DiagDetail.pctTenths(cur.videoLossPct)}"
        if (cur.audioLossPct >= LOSS_BAD_PCT) return "b=al,${DiagDetail.pctTenths(cur.audioLossPct)}"
        if (before.rttMs >= RTT_JUMP_FLOOR_MS && cur.rttMs >= before.rttMs * RTT_JUMP_TIMES) {
            return "b=rtt,${DiagDetail.msCap(before.rttMs)}>${DiagDetail.msCap(cur.rttMs)}"
        }
        return null
    }
}
