package com.soul2soul.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补报队列分级淘汰（v0.2.59）锁定。
 *
 * 为什么这条必须在：断连积压里**最旧**的几行正是死亡瞬间的现场，而旧实现 `takeLast` 保最新，
 * 会把它们第一个挤掉；v0.2.59 之后待机心跳与通话 QoS 也在入队，量再大一档，
 * "挤掉现场"就从隐患变成必然。把队列改回单一 `takeLast(MAX_ENTRIES)`，本文件必须变红。
 */
class BacklogTierTest {

    private fun line(event: String, at: Long = 1L): String {
        val body = JSONObject().put("event", event).put("at", at)
        return JSONObject().put("t", "diag.event").put("b", body).toString()
    }

    private fun countKeeping(kept: List<String>, event: String): Int =
        kept.count { JSONObject(it).getJSONObject("b").getString("event") == event }

    // T1：没到上限就一条都不动（淘汰不该在日常低频时改变任何内容）
    @Test
    fun `under cap is a no-op`() {
        val lines = (1..BacklogTiers.MAX_ENTRIES).map { line("net_down") }
        assertEquals(lines, BacklogTiers.trim(lines))
    }

    // T2：QoS 洪水不能挤掉死亡现场——这是本文件存在的理由
    @Test
    fun `qos flood cannot evict death rows`() {
        val death = (1..100).map { line("ice_failed", it.toLong()) }
        val qos = (1..150).map { line("qos_tick", it.toLong()) }
        val kept = BacklogTiers.trim(death + qos)
        assertEquals("死亡现场的行数一条都不能少", 100, countKeeping(kept, "ice_failed"))
        assertEquals("QoS 只留自己那份配额", BacklogTiers.QUOTA_QOS, countKeeping(kept, "qos_tick"))
    }

    // T3：心跳洪水先丢 QoS，再伤心跳，死亡行仍然全在
    @Test
    fun `heartbeat flood evicts qos before death rows`() {
        val death = (1..50).map { line("net_down") }
        val beat = (1..90).map { line("heartbeat") }
        val qos = (1..90).map { line("qos_tick") }
        val kept = BacklogTiers.trim(death + beat + qos)
        assertEquals(50, countKeeping(kept, "net_down"))
        assertEquals(BacklogTiers.QUOTA_BEAT, countKeeping(kept, "heartbeat"))
        assertEquals(BacklogTiers.QUOTA_QOS, countKeeping(kept, "qos_tick"))
    }

    // T4：死亡行自己超额被丢时，必须留一条可 grep 的标记（静默≠没满过）
    @Test
    fun `death over quota emits a visible drop marker`() {
        val lines = (1..300).map { line("ice_failed", it.toLong()) }
        val kept = BacklogTiers.trim(lines, nowMs = 777L)
        assertEquals(BacklogTiers.QUOTA_DEATH, countKeeping(kept, "ice_failed"))
        val marker = kept.firstOrNull { it.contains("backlog_drop") }
        assertTrue("P0 被丢弃却不留标记=把丢证据洗成\"没发生\"", marker != null)
        assertTrue("标记必须说明丢了几条", marker!!.contains("dropped=150"))
    }

    // T5：保留下来的必须还是原时序（补报乱序会把"锁屏→掉线"读成"掉线→锁屏"）
    @Test
    fun `survivors keep chronological order`() {
        val death = (1..80).map { line("net_down", it.toLong()) }
        val qos = (1..200).map { line("qos_tick", it.toLong()) }
        val kept = BacklogTiers.trim(death + qos)
        val deaths = kept.filter { !it.contains("qos_tick") }.map {
            JSONObject(it).getJSONObject("b").getLong("at")
        }
        assertEquals(80, deaths.size)
        assertEquals(deaths.sorted(), deaths)
    }

    // T6：总量不会被标记撑爆（三档配额相加正好等于上限，标记只多一条）
    @Test
    fun `total stays within quota plus marker`() {
        val kept = BacklogTiers.trim((1..500).map { line("heartbeat") })
        assertTrue("保留数不得超过配额合计再加一条标记", kept.size <= BacklogTiers.MAX_ENTRIES + 1)
        assertEquals(BacklogTiers.QUOTA_BEAT, countKeeping(kept, "heartbeat"))
    }

    // T7：解析不出事件名的行归到最廉价档（它本来也重放不出去，不该占死亡配额）
    @Test
    fun `unparseable line is cheapest tier`() {
        assertEquals(BacklogTiers.TIER_QOS, BacklogTiers.tierOf("{not json"))
        assertEquals(BacklogTiers.TIER_DEATH, BacklogTiers.tierOf(line("net_down")))
        assertEquals(BacklogTiers.TIER_BEAT, BacklogTiers.tierOf(line("heartbeat")))
        assertEquals(BacklogTiers.TIER_QOS, BacklogTiers.tierOf(line("qos_tick")))
    }

    // T8：标记补报时拼出的 detail 必须过服务器 safeTag 的 64 字符预算，
    //     超了会被静默截断（没有溢出标记），"丢了几条"就再也读不出来。
    @Test
    fun `marker detail survives the server budget`() {
        val marker = BacklogTiers.dropMarker(999_999, 1_700_000_000_000L)
        val body = JSONObject(marker).getJSONObject("b")
        val replayedDetail = "at=${body.getLong("at")} ${body.getString("detail")}"
        assertEquals(
            "detail 超 64 会被 safeTag 从尾部裁掉：$replayedDetail",
            true,
            replayedDetail.length <= 64,
        )
    }
}
