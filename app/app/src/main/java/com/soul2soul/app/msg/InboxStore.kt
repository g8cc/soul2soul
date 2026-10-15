package com.soul2soul.app.msg

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一条留言（字段与 docs/PROTOCOL.md §2.2 的留言条目一一对应）。
 * mine=true 表示本机发出的。会话记录会本地落盘（微信式聊天窗口：已读不删、重启仍在），
 * 服务端仍是阅后即删——隐私边界是"不过云端存储"，不是"本机也失忆"。
 */
data class Msg(
    val id: String,
    val from: String,
    val kind: String, // text | voice
    val text: String,
    val voiceId: String,
    val durMs: Int,
    val ts: Long,
    val mine: Boolean = false,
    val read: Boolean = false,
) {
    companion object {
        /** 服务端条目/回执 → Msg；缺 id 的畸形条目返回 null（宁可少显示，不可崩） */
        fun fromJson(o: JSONObject?, mine: Boolean): Msg? {
            if (o == null) return null
            val id = o.optString("id")
            if (id.isEmpty()) return null
            return Msg(
                id = id,
                from = o.optString("from"),
                kind = if (o.optString("kind") == "voice") "voice" else "text",
                text = o.optString("text"),
                voiceId = o.optString("voiceId"),
                durMs = o.optInt("durMs"),
                ts = o.optLong("ts"),
                mine = mine,
            )
        }

        /** 本地落盘条目 → Msg（mine/read 存在 JSON 里）；缺 id 返回 null */
        fun fromStoredJson(o: JSONObject?): Msg? {
            val base = fromJson(o, mine = false) ?: return null
            return base.copy(
                mine = o!!.optBoolean("mine"),
                read = o.optBoolean("read"),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("from", from).put("kind", kind).put("text", text)
        .put("voiceId", voiceId).put("durMs", durMs).put("ts", ts)
        .put("mine", mine).put("read", read)
}

/**
 * 进程级留言箱：服务端下发的未读 + 本地已发的会话记忆。
 * 由 PresenceService 写入（连接存活期间任何时刻都可能来消息），UI 通过监听器同步。
 * 纯逻辑无 Android 依赖，JVM 单测覆盖（去重/排序/计数）。
 */
object InboxStore {

    private val items = LinkedHashMap<String, Msg>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(msg: Msg) {
        if (items.containsKey(msg.id)) return
        items[msg.id] = msg
        notifyChanged()
    }

    fun addAll(msgs: List<Msg>) {
        var changed = false
        for (msg in msgs) {
            if (items.containsKey(msg.id)) continue
            items[msg.id] = msg
            changed = true
        }
        if (changed) notifyChanged()
    }

    /** 已读打标：条目留在本地当聊天记录（微信式），只是不再计入未读。
     *  自己发出去的不受 ack 影响（v0.2.37 之前是删除，导致回主界面再进只剩自己的） */
    fun markRead(ids: Collection<String>) {
        val want = ids.toSet()
        var changed = false
        for (id in want) {
            val m = items[id] ?: continue
            if (!m.mine && !m.read) {
                items[id] = m.copy(read = true)
                changed = true
            }
        }
        if (changed) notifyChanged()
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        notifyChanged()
    }

    /** 收到但未读的（主页角标数量以此为准） */
    fun received(): List<Msg> =
        items.values.filter { !it.mine && !it.read }.sortedWith(compareBy({ it.ts }, { it.id }))

    /** 会话页展示的全部（收+发，按时间） */
    fun conversation(): List<Msg> =
        items.values.sortedWith(compareBy({ it.ts }, { it.id }))

    /** 落盘快照 */
    fun snapshot(): List<Msg> = items.values.toList()

    /** 启动恢复：按 id 先占先得合并（服务端重发的同 id 未读不覆盖本地已读态） */
    fun restore(msgs: List<Msg>) {
        var changed = false
        for (m in msgs) {
            if (items.containsKey(m.id)) continue
            items[m.id] = m
            changed = true
        }
        if (changed) notifyChanged()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyChanged() {
        for (l in listeners) runCatching { l() }
    }
}
