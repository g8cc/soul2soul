package com.soul2soul.app.msg

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 留言箱纯逻辑锁定：解析容错 / 按 id 去重 / 未读口径 / 已读不删（聊天窗口化） */
class InboxStoreTest {

    private fun item(id: String, from: String, kind: String = "text", ts: Long = 1000): JSONObject =
        JSONObject().put("id", id).put("from", from).put("kind", kind)
            .put("text", "hi").put("ts", ts)

    @Before
    fun reset() {
        InboxStore.clear()
    }

    @Test
    fun `fromJson 解析完整条目并保留语音字段`() {
        val m = Msg.fromJson(
            JSONObject().put("id", "x1").put("from", "a")
                .put("kind", "voice").put("voiceId", "v-1").put("durMs", 4200).put("ts", 77),
            mine = false,
        )!!
        assertEquals("x1", m.id)
        assertEquals("voice", m.kind)
        assertEquals("v-1", m.voiceId)
        assertEquals(4200, m.durMs)
        assertEquals(77, m.ts)
        assertFalse(m.mine)
    }

    @Test
    fun `fromJson 对缺 id 的畸形条目返回 null 且 kind 缺省为 text`() {
        assertNull(Msg.fromJson(JSONObject().put("from", "a"), mine = false))
        val m = Msg.fromJson(item("ok", "a", kind = "weird"), mine = false)!!
        assertEquals("text", m.kind)
    }

    @Test
    fun `add 按 id 去重不重复入箱`() {
        InboxStore.add(Msg.fromJson(item("d1", "a"), mine = false)!!)
        InboxStore.add(Msg.fromJson(item("d1", "a"), mine = false)!!)
        assertEquals(1, InboxStore.received().size)
    }

    @Test
    fun `混合留言箱跳过非对象且保留后续有效条目`() {
        val entries = JSONArray().put(JSONObject.NULL).put("bad").put(7).put(item("ok", "a"))
        val parsed = (0 until entries.length()).mapNotNull {
            Msg.fromJson(entries.optJSONObject(it), mine = false)
        }
        assertEquals(listOf("ok"), parsed.map { it.id })
    }

    @Test
    fun `批量重复 id 与单条入库一致保留首条`() {
        val first = Msg.fromJson(item("same", "a"), mine = false)!!
        InboxStore.addAll(listOf(first, first.copy(text = "changed")))
        assertEquals(listOf(first), InboxStore.conversation())
    }

    @Test
    fun `received 只含收到的且按时间升序`() {
        InboxStore.add(Msg.fromJson(item("n1", "me", ts = 300), mine = true)!!)
        InboxStore.add(Msg.fromJson(item("r2", "a", ts = 200), mine = false)!!)
        InboxStore.add(Msg.fromJson(item("r1", "a", ts = 100), mine = false)!!)
        assertEquals(listOf("r1", "r2"), InboxStore.received().map { it.id })
        assertEquals(listOf("r1", "r2", "n1"), InboxStore.conversation().map { it.id })
    }

    @Test
    fun `markRead 已读不删条目_聊天记录保留但不再计未读_v0_2_38聊天窗口化锁定`() {
        InboxStore.add(Msg.fromJson(item("m1", "me", ts = 100), mine = true)!!)
        InboxStore.add(Msg.fromJson(item("r1", "a", ts = 200), mine = false)!!)
        InboxStore.markRead(listOf("m1", "r1", "ghost"))
        assertEquals(0, InboxStore.received().size)
        assertEquals(listOf("m1", "r1"), InboxStore.conversation().map { it.id })
        assertTrue(InboxStore.conversation().first { it.id == "r1" }.read)
        // 已读再 mark 不重复回调
        var hits = 0
        val l: () -> Unit = { hits++ }
        InboxStore.addListener(l)
        InboxStore.markRead(listOf("r1"))
        assertEquals(0, hits)
        InboxStore.removeListener(l)
    }

    @Test
    fun `restore 按 id 先占先得合并且不覆盖内存已有条目`() {
        val kept = Msg.fromJson(item("s1", "a"), mine = false)!!.copy(read = true)
        InboxStore.add(kept)
        InboxStore.restore(listOf(kept.copy(read = false), Msg.fromJson(item("s2", "a"), mine = false)!!))
        assertEquals(listOf("s1", "s2"), InboxStore.conversation().map { it.id })
        assertTrue(InboxStore.conversation().first { it.id == "s1" }.read)
    }

    @Test
    fun `落盘回读：mine与read状态不丢，坏文件当空`() {
        InboxStore.add(Msg.fromJson(item("p1", "me", ts = 100), mine = true)!!)
        InboxStore.add(Msg.fromJson(item("p2", "a", ts = 200), mine = false)!!.copy(read = true))
        val f = java.io.File.createTempFile("chat", ".json")
        try {
            ChatPersistence.save(f, InboxStore.snapshot())
            InboxStore.clear()
            InboxStore.restore(ChatPersistence.load(f))
            val conv = InboxStore.conversation()
            assertEquals(listOf("p1", "p2"), conv.map { it.id })
            assertTrue(conv.first { it.id == "p1" }.mine)
            assertTrue(conv.first { it.id == "p2" }.read)
            assertEquals(0, InboxStore.received().size)
            // 坏 JSON 不崩、返回空
            f.writeText("{not a json")
            assertEquals(0, ChatPersistence.load(f).size)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `recall 打墓碑：不计未读但会话留提示行，不存在的 id 静默无回调`() {
        InboxStore.add(Msg.fromJson(item("rc1", "a", ts = 100), mine = false)!!)
        InboxStore.add(Msg.fromJson(item("rc2", "me", ts = 200), mine = true)!!)
        var hits = 0
        val l: () -> Unit = { hits++ }
        InboxStore.addListener(l)
        InboxStore.recall("ghost")
        assertEquals(0, hits)
        InboxStore.recall("rc1")
        assertEquals(1, hits)
        InboxStore.recall("rc1") // 已墓碑再撤不重复回调
        assertEquals(1, hits)
        InboxStore.removeListener(l)
        assertEquals(0, InboxStore.received().size)
        assertEquals(listOf("rc1", "rc2"), InboxStore.conversation().map { it.id })
        assertTrue(InboxStore.conversation().first { it.id == "rc1" }.recalled)
    }

    @Test
    fun `recalled 状态落盘回读不丢`() {
        InboxStore.add(Msg.fromJson(item("rs1", "a"), mine = false)!!.copy(recalled = true))
        InboxStore.add(Msg.fromJson(item("rs2", "me"), mine = true)!!)
        val f = java.io.File.createTempFile("chat", ".json")
        try {
            ChatPersistence.save(f, InboxStore.snapshot())
            InboxStore.clear()
            InboxStore.restore(ChatPersistence.load(f))
            assertTrue(InboxStore.conversation().first { it.id == "rs1" }.recalled)
            assertFalse(InboxStore.conversation().first { it.id == "rs2" }.recalled)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `add markRead 与 clear 都触发变更回调且重复 addAll 不触发`() {
        var hits = 0
        val l: () -> Unit = { hits++ }
        InboxStore.addListener(l)
        InboxStore.add(Msg.fromJson(item("c1", "a"), mine = false)!!)
        InboxStore.addAll(listOf(Msg.fromJson(item("c2", "a"), mine = false)!!))
        InboxStore.markRead(listOf("c1"))
        InboxStore.clear()
        assertEquals(4, hits)
        InboxStore.add(Msg.fromJson(item("z", "a"), mine = false)!!)
        InboxStore.addAll(listOf(Msg.fromJson(item("z", "a"), mine = false)!!)) // 全重复 → 不回调
        assertEquals(5, hits)
        InboxStore.removeListener(l)
    }
}
