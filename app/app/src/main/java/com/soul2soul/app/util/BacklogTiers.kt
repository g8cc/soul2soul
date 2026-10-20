package com.soul2soul.app.util

import org.json.JSONObject

/**
 * 补报队列的分级淘汰（纯函数，可注入时钟——所以能在 JVM 里测）。
 *
 * 为什么必须分级：断连积压里**最旧**的那几条正是死亡瞬间的现场（`ice_failed`/`net_down`/
 * `session_end`），而 v0.2.59 之后待机和通话期也在产读数（`heartbeat` 300s、`qos_tick` 10s、
 * 静止画面下的 `video_stall` 实测最短 40s），量比原来大一个数量级。原来的"超了就 takeLast 保最新"会把这些现场先挤掉，等于
 * 在最需要证据的时刻把证据扔了。
 *
 * 顺序保持不变（补报必须按时序），所以是"从最新往最旧按配额标记保留，再按原序过滤"。
 */
object BacklogTiers {

    const val MAX_ENTRIES = 200
    const val QUOTA_DEATH = 150 // P0：终止/掉线/断网/权限类
    const val QUOTA_GAP = 25   // P1：自证型（价值在"缺口/发生过"，同一件事的重复几条可丢）
    const val QUOTA_QOS = 25    // P2：通话期 QoS（量最大，最先丢）

    const val TIER_DEATH = 0
    const val TIER_GAP = 1
    const val TIER_QOS = 2

    /**
     * 解析不出事件名的行按最廉价处理：它本来也重放不出去。
     *
     * `video_stall` 是 10-19 双真机那轮加进来的第三个降级名字，依据是实测密度而不是猜测：
     * 观看端在一通 21 分钟的静态共享里报了 **9 条、最短间隔 40.0s**（通知、时钟秒针、MIUI 任何一闪
     * 都会让帧回来一次、streak 清零后 30s 又凑满 3 个窗口），所以"一次静止期只发一条"在真机上不成立。
     * 把它留在 P0 的后果不是判错而是**丢别的证据**：`trim` 保留的是各档里最新的那些，P0 满 150 条时
     * 被丢掉的是死亡现场（`ice_failed`/`net_down`/`aexit`），而 stall 的时间线本来就能从
     * `qos_tick` 第 7 格（原始帧率）复原，重复那几条不携带新信息。档内仍按"保最新"淘汰，
     * 于是断连前最后一次 stall 一定活着——那才是需要它的那一条。
     *
     * `echo_ack`（0.2.65 交棒遥测）降级的理由与 stall 同一条口径：**它是重复采样**。
     * 每条只报"最近一次 e / 本轮最大 x / miss m / 还在等 p"四个标量，客户端本身已经 5s 节流；
     * p50/p90 由整条序列给出，断连时丢掉其中几条不丢结论（这一列的用途是给观看端 150ms 的
     * 交棒淡出做下界校验，不是现场证据）。留在 P0 反而会把死亡现场挤出配额。
     */
    fun tierOf(line: String): Int {
        val event = runCatching {
            JSONObject(line).optJSONObject("b")?.optString("event")
        }.getOrNull() ?: return TIER_QOS
        return when (event) {
            "heartbeat", "video_stall", "echo_ack" -> TIER_GAP
            "qos_tick" -> TIER_QOS
            else -> TIER_DEATH
        }
    }

    /**
     * 保留下来的一条条按原序返回；P0 自身超额被丢时，头部插一条 `backlog_drop` 标记——
     * "队列满过"必须和"队列从没满过"在服务器日志里长得不一样，否则静默会被读成"没发生"。
     */
    fun trim(lines: List<String>, nowMs: Long = System.currentTimeMillis()): List<String> {
        if (lines.size <= MAX_ENTRIES) return lines
        val quotas = intArrayOf(QUOTA_DEATH, QUOTA_GAP, QUOTA_QOS)
        val keep = BooleanArray(lines.size)
        val seen = IntArray(quotas.size)
        var droppedDeath = 0
        for (i in lines.indices.reversed()) {
            val tier = tierOf(lines[i])
            if (seen[tier] < quotas[tier]) {
                seen[tier] += 1
                keep[i] = true
            } else if (tier == TIER_DEATH) {
                droppedDeath += 1
            }
        }
        val survivors = lines.filterIndexed { i, _ -> keep[i] }
        return if (droppedDeath > 0) listOf(dropMarker(droppedDeath, nowMs)) + survivors else survivors
    }

    fun dropMarker(dropped: Int, nowMs: Long): String {
        val body = JSONObject()
            .put("role", "-").put("event", "backlog_drop").put("ice", "-")
            .put("detail", "dropped=$dropped").put("at", nowMs)
        return JSONObject().put("t", "diag.event").put("b", body).toString()
    }
}
