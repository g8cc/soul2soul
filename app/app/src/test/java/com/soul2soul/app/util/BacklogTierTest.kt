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
        assertEquals(BacklogTiers.QUOTA_GAP, countKeeping(kept, "heartbeat"))
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
        assertEquals(BacklogTiers.QUOTA_GAP, countKeeping(kept, "heartbeat"))
    }

    // T7：解析不出事件名的行归到最廉价档（它本来也重放不出去，不该占死亡配额）
    @Test
    fun `unparseable line is cheapest tier`() {
        assertEquals(BacklogTiers.TIER_QOS, BacklogTiers.tierOf("{not json"))
        assertEquals(BacklogTiers.TIER_DEATH, BacklogTiers.tierOf(line("net_down")))
        assertEquals(BacklogTiers.TIER_GAP, BacklogTiers.tierOf(line("heartbeat")))
        assertEquals(BacklogTiers.TIER_GAP, BacklogTiers.tierOf(line("video_stall")))
        assertEquals(BacklogTiers.TIER_QOS, BacklogTiers.tierOf(line("qos_tick")))
    }

    // T7b（#78，10-19 双真机实测后新增）：静止画面的 stall 洪水不许挤掉死亡现场。
    //      判据来自真实密度——21 分钟静态共享 9 条、最短 40.0s，且它的时间线能从
    //      qos_tick 第 7 格复原，所以重复那几条不是证据；而"最后一次 stall"必须活着（保最新）。
    @Test
    fun `stall flood cannot evict death rows`() {
        val death = (1..100).map { line("ice_failed", it.toLong()) }
        val stalls = (200..399L).map { line("video_stall", it) }
        val kept = BacklogTiers.trim(death + stalls)
        assertEquals("死亡现场一条都不能少", 100, countKeeping(kept, "ice_failed"))
        assertEquals("stall 只留自己那份配额", BacklogTiers.QUOTA_GAP, countKeeping(kept, "video_stall"))
        assertEquals(
            "淘汰的是最旧的那些：断连前最后一条 stall 必须在（它是唯一还有判据意义的一条）",
            399L,
            kept.filter { JSONObject(it).getJSONObject("b").getString("event") == "video_stall" }
                .maxOf { JSONObject(it).getJSONObject("b").getLong("at") },
        )
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

    private fun repoFile(rel: String): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val f = java.io.File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${java.io.File("").absolutePath}）")
    }

    // T9：分级是"默认保护 + 只降级少数高频自证名字"，不是 P0 白名单。
    //     白名单写法会让新加的埋点默认落进最廉价档、在断连积压时被静默丢掉，
    //     而 signaling_down/screen_off 这些本来就不在任何手写清单里（10-19 复核 PROTOCOL 时撞见文档写成了白名单）。
    @Test
    fun `unregistered event names default to the protected tier`() {
        val notInAnyHandList = listOf(
            "signaling_down", "signaling_up", "screen_off", "screen_on",
            "probe_start", "video_resumed", "peer_signal", "stop_tap",
        )
        notInAnyHandList.forEach {
            assertEquals("$it 被漏出死亡档", BacklogTiers.TIER_DEATH, BacklogTiers.tierOf(line(it)))
        }
        assertEquals(BacklogTiers.TIER_DEATH, BacklogTiers.tierOf(line("some_future_probe_event")))
        // 降级名单＝"高频且重复几条不携带新信息"的那几个名字，加一个都必须先在这里交代理由。
        // 按行取：一个分支可以挂多个名字（`"heartbeat", "video_stall" -> TIER_GAP`），
        // 只抓紧邻 `->` 的那个会把前面的名字静默漏出对账名单（10-19 加 video_stall 时踩到）
        val text = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(repoFile("app/src/main/java/com/soul2soul/app/util/BacklogTiers.kt").readText(), "")
        val demoted = text.lines()
            .filter { Regex("""" -> TIER_(?!DEATH)""").containsMatchIn(it) }
            .flatMap { Regex(""""([a-z_]+)"""").findAll(it).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue("扫描器没抓到 heartbeat 的降级分支＝它坏了，这条对账等于没对", "heartbeat" in demoted)
        assertEquals(setOf("heartbeat", "video_stall", "echo_ack", "qos_tick"), demoted)
        assertTrue("默认分支不见了", Regex("""else\s*->\s*TIER_DEATH""").containsMatchIn(text))
    }

    // T10：文档不许把上面那条规则写成白名单（读日志的人按白名单读，会以为 signaling_down 可丢）
    @Test
    fun `PROTOCOL 写的是默认保护而不是 P0 清单`() {
        val doc = repoFile("docs/PROTOCOL.md").readText()
        val rule = doc.lines().first { it.contains("淘汰是分级而不是") }
        assertTrue("PROTOCOL 的淘汰规则没写「默认 P0」：$rule", rule.contains("默认 P0"))
        assertTrue("PROTOCOL 没说明只降级两个名字", rule.contains("只有") && rule.contains("被降级"))
        assertTrue("PROTOCOL 又退回 P0 白名单写法", !Regex("""P0（`aexit`""").containsMatchIn(rule))
    }
}
