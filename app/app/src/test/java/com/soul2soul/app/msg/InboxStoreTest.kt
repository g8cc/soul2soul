package com.soul2soul.app.msg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 留言箱纯逻辑锁定：解析容错 / 按 id 去重 / 未读口径 / 阅后即删只删收到的 */
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
    fun `received 只含收到的且按时间升序`() {
        InboxStore.add(Msg.fromJson(item("n1", "me", ts = 300), mine = true)!!)
        InboxStore.add(Msg.fromJson(item("r2", "a", ts = 200), mine = false)!!)
        InboxStore.add(Msg.fromJson(item("r1", "a", ts = 100), mine = false)!!)
        assertEquals(listOf("r1", "r2"), InboxStore.received().map { it.id })
        assertEquals(listOf("r1", "r2", "n1"), InboxStore.conversation().map { it.id })
    }

    @Test
    fun `remove 只删收到的条目，本地发出的不受影响`() {
        InboxStore.add(Msg.fromJson(item("m1", "me", ts = 100), mine = true)!!)
        InboxStore.add(Msg.fromJson(item("r1", "a", ts = 200), mine = false)!!)
        InboxStore.remove(listOf("m1", "r1", "ghost"))
        assertEquals(0, InboxStore.received().size)
        assertEquals(listOf("m1"), InboxStore.conversation().map { it.id })
    }

    @Test
    fun `add removeAll 与 clear 都触发变更回调且重复 addAll 不触发`() {
        var hits = 0
        val l: () -> Unit = { hits++ }
        InboxStore.addListener(l)
        InboxStore.add(Msg.fromJson(item("c1", "a"), mine = false)!!)
        InboxStore.addAll(listOf(Msg.fromJson(item("c2", "a"), mine = false)!!))
        InboxStore.remove(listOf("c1"))
        InboxStore.clear()
        assertEquals(4, hits)
        InboxStore.add(Msg.fromJson(item("z", "a"), mine = false)!!)
        InboxStore.addAll(listOf(Msg.fromJson(item("z", "a"), mine = false)!!)) // 全重复 → 不回调
        assertEquals(5, hits)
        InboxStore.removeListener(l)
    }
}
