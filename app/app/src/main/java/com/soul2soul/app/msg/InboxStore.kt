package com.soul2soul.app.msg

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一条留言（字段与 docs/PROTOCOL.md §2.2 的留言条目一一对应）。
 * mine=true 表示本机本次进程内发出的（只在内存，不落盘——隐私最小存储）。
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
) {
    companion object {
        /** 服务端条目/回执 → Msg；缺 id 的畸形条目返回 null（宁可少显示，不可崩） */
        fun fromJson(o: JSONObject, mine: Boolean): Msg? {
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
    }
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
        val fresh = msgs.filter { !items.containsKey(it.id) }
        if (fresh.isEmpty()) return
        for (m in fresh) items[m.id] = m
        notifyChanged()
    }

    /** 已读删除：只动未读（自己发出去的不该被 ack 清掉） */
    fun remove(ids: Collection<String>) {
        val want = ids.toSet()
        var changed = false
        for (id in want) if (items[id]?.let { !it.mine } == true && items.remove(id) != null) changed = true
        if (changed) notifyChanged()
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        notifyChanged()
    }

    /** 收到但未读的（主页角标数量以此为准） */
    fun received(): List<Msg> =
        items.values.filter { !it.mine }.sortedWith(compareBy({ it.ts }, { it.id }))

    /** 会话页展示的全部（收+发，按时间） */
    fun conversation(): List<Msg> =
        items.values.sortedWith(compareBy({ it.ts }, { it.id }))

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyChanged() {
        for (l in listeners) runCatching { l() }
    }
}
