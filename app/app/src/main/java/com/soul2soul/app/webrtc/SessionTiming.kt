package com.soul2soul.app.webrtc

/**
 * 会话时长记账（纯函数，时钟由调用方注入）。
 *
 * 为什么单独拎出来：v0.2.57 及之前，`connectedSinceMs` 同时承担两件事——
 * "当前是否连着"（ICE 闪断要清零它）和"什么时候连上的"（上报时长要用它）。
 * 清零那一下把历史也抹了，于是 [WebRtcClient.reportSessionEnd] 在
 * DISCONNECTED/FAILED 之后调用时只能得到 liveMs=0、setupMs=整场会话年龄，
 * 服务器日志里"活了 18 分钟被自己拆掉"和"根本没连上"长得一模一样。
 * 这里把两个语义拆开：连通时刻永不清零，失去连通单独记一格。
 */
object SessionTiming {

    /**
     * 建链耗时（ms）。
     * 从未连通返回 -1（不能用 now-started 顶替：那是会话年龄，不是建链耗时）。
     */
    fun setupMs(startedAtMs: Long, connectedAtMs: Long): Long =
        if (startedAtMs <= 0L || connectedAtMs <= 0L) -1L else connectedAtMs - startedAtMs

    /**
     * 媒体真实存活时长（ms）：从首次连通到失去连通（仍连着则到 now）。
     * 从未连通返回 0——和"连上就断"区分开的依据是 setupMs。
     */
    fun liveMs(connectedAtMs: Long, lostAtMs: Long, nowMs: Long): Long {
        if (connectedAtMs <= 0L) return 0L
        val endAt = if (lostAtMs > connectedAtMs) lostAtMs else nowMs
        return (endAt - connectedAtMs).coerceAtLeast(0L)
    }
}
