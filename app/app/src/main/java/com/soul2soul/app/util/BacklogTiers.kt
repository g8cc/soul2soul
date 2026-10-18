package com.soul2soul.app.util

import org.json.JSONObject

/**
 * 补报队列的分级淘汰（纯函数，可注入时钟——所以能在 JVM 里测）。
 *
 * 为什么必须分级：断连积压里**最旧**的那几条正是死亡瞬间的现场（`ice_failed`/`net_down`/
 * `session_end`），而 v0.2.59 之后待机和通话期也在产读数（`heartbeat` 300s、`qos_tick` 10s），
 * 量比原来大一个数量级。原来的"超了就 takeLast 保最新"会把这些现场先挤掉，等于
 * 在最需要证据的时刻把证据扔了。
 *
 * 顺序保持不变（补报必须按时序），所以是"从最新往最旧按配额标记保留，再按原序过滤"。
 */
object BacklogTiers {

    const val MAX_ENTRIES = 200
    const val QUOTA_DEATH = 150 // P0：终止/掉线/断网/权限类
    const val QUOTA_BEAT = 25   // P1：待机心跳（价值在"缺口"，中间几条可丢）
    const val QUOTA_QOS = 25    // P2：通话期 QoS（量最大，最先丢）

    const val TIER_DEATH = 0
    const val TIER_BEAT = 1
    const val TIER_QOS = 2

    /** 解析不出事件名的行按最廉价处理：它本来也重放不出去 */
    fun tierOf(line: String): Int {
        val event = runCatching {
            JSONObject(line).optJSONObject("b")?.optString("event")
        }.getOrNull() ?: return TIER_QOS
        return when (event) {
            "heartbeat" -> TIER_BEAT
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
        val quotas = intArrayOf(QUOTA_DEATH, QUOTA_BEAT, QUOTA_QOS)
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
