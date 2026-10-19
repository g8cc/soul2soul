package com.soul2soul.app.msg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知栏留言条数的决策表（锁定 v0.2.31 需求：静音常驻、变化才刷新、读完消失），
 * 以及 #82 的判据：签名从"条数"换成"是哪几条"——只比条数会停在上一条的预览上。
 */
class UnreadNotifierTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun notifier() = source("app/src/main/java/com/soul2soul/app/msg/UnreadNotifier.kt")

    /** 从起锚行取到终锚行之前（不含终锚）；两个锚都必须恰好找得到，取不出来就 error */
    private fun between(lines: List<String>, from: String, to: String): List<String> {
        val a = lines.indexOfFirst { it.contains(from) }
        assertTrue("找不到起点锚点 $from", a >= 0)
        val b = ((a + 1)..lines.lastIndex).firstOrNull { lines[it].contains(to) }
            ?: error("锚点 $to 在 $from（行 ${a + 1}）之后不存在，窗口取不出来")
        return lines.subList(a, b + 1)
    }

    private fun msg(id: String, kind: String = "text", text: String = "在吗", durMs: Int = 0) =
        Msg(id = id, from = "peer", kind = kind, text = text, voiceId = "", durMs = durMs,
            ts = id.toLong(), mine = false, read = false, recalled = false)

    @Test
    fun `没显示且没未读-不动作`() {
        assertEquals(UnreadNotifier.Action.Keep, UnreadNotifier.plan(null, emptyList()))
    }

    @Test
    fun `首次出现未读-显示`() {
        assertEquals(UnreadNotifier.Action.Show, UnreadNotifier.plan(null, listOf(msg("1"))))
        assertEquals(UnreadNotifier.Action.Show,
            UnreadNotifier.plan(null, listOf(msg("1"), msg("2"), msg("3"), msg("4"), msg("5"))))
    }

    @Test
    fun `条数增加-刷新`() {
        assertEquals(UnreadNotifier.Action.Show, UnreadNotifier.plan("2", listOf(msg("2", text = "a"), msg("3"))))
    }

    @Test
    fun `同一批未读-不重复刷`() {
        val unread = listOf(msg("3"), msg("4"))
        assertEquals(UnreadNotifier.Action.Keep,
            UnreadNotifier.plan(UnreadNotifier.signature(unread), unread))
    }

    @Test
    fun `条数相同但换了内容-必须刷新（#82 的核心判据）`() {
        // 读完 a 之后 b 还没播完又来一条 c：未读仍是 2 条，预览却是旧的那条
        val before = UnreadNotifier.signature(listOf(msg("10"), msg("11")))
        val after = listOf(msg("11"), msg("12"))
        assertEquals(2, after.size)
        assertEquals(UnreadNotifier.Action.Show, UnreadNotifier.plan(before, after))
    }

    @Test
    fun `读完-收起通知`() {
        assertEquals(UnreadNotifier.Action.Hide, UnreadNotifier.plan("3,4", emptyList()))
    }

    @Test
    fun `显示归零后再次清空-不动作`() {
        // Hide 之后签名已复位为 null，重复的空→空不得再触发 cancel
        assertEquals(UnreadNotifier.Action.Keep, UnreadNotifier.plan(null, emptyList()))
    }

    @Test
    fun `空箱的签名是 null 不是空串`() {
        assertNull(UnreadNotifier.signature(emptyList()))
        assertEquals("10,11", UnreadNotifier.signature(listOf(msg("10"), msg("11"))))
    }

    @Test
    fun `语音只报秒-向上取整且不造假读数`() {
        assertEquals(0, UnreadNotifier.voiceSec(0))
        assertEquals(0, UnreadNotifier.voiceSec(-500))
        assertEquals(1, UnreadNotifier.voiceSec(1))
        assertEquals(1, UnreadNotifier.voiceSec(1000))
        assertEquals(2, UnreadNotifier.voiceSec(1001))
    }

    @Test
    fun `正文压成一行-换行与连续空白不留进通知`() {
        assertEquals("宝贝 我想你", UnreadNotifier.flatten("宝贝\n  我想你\n\n"))
        assertEquals("", UnreadNotifier.flatten("  \n\t "))
    }

    @Test
    fun `展开态只留最近 5 条-顺序仍是时间升序`() {
        val seven = (1..7).map { msg("$it") }
        val lines = UnreadNotifier.bodyLines(seven)
        assertEquals(listOf("3", "4", "5", "6", "7"), lines.map { it.id })
        val five = (1..5).map { msg("$it") }
        assertEquals(five, UnreadNotifier.bodyLines(five))
    }

    // ── 源码锁：#82 改的是"通知栏里放的是留言本身"，最容易烂回去的就是这两处 ──

    @Test
    fun `M1 折叠态正文必须是留言本身-不许退回固定的点我查看`() {
        val chain = between(notifier(), ".setContentText(newest", "if (count > 1)")
        assertTrue("折叠正文要读 newest（最新一条留言）", chain.any { it.contains("newest") })
        assertTrue("msg_unread_tap 在这一行里只许出现一次（多处＝预览又被盖掉了）",
            chain.count { it.contains("msg_unread_tap") } == 1)
        assertTrue("兜底必须挂在 newest 为空这一支上（ifEmpty），否则有内容也看不见",
            chain.any { it.contains("newest.ifEmpty") })
    }

    @Test
    fun `M2 刷不刷看的是哪几条-不许烂回只比条数`() {
        val text = notifier().joinToString("\n")
        assertTrue("计数签名 shownCount 是 #82 之前的形态，回来即等于预览永不更新",
            !text.contains("shownCount"))
        val sync = between(notifier(), "fun sync(ctx: Context)", "private fun lineOf")
        assertTrue("plan 的入参必须是未读列表本身（签名在里面算）",
            sync.any { it.contains("plan(shownSig, unread)") })
        assertTrue("刚记下的签名必须与 plan 用的同源，否则一边比内容一边记条数",
            sync.any { it.contains("signature(unread)") })
    }

    @Test
    fun `M3 通知只报未读-自己发的与被撤回的不进栏`() {
        val sync = between(notifier(), "fun sync(ctx: Context)", "private fun lineOf")
        assertTrue("未读口径必须是 InboxStore.received()（已滤掉 mine/read/recalled）",
            sync.any { it.contains("InboxStore.received()") })
        assertTrue("conversation() 含自己发出去的，拿它当未读会把回复也通知一遍",
            sync.none { it.contains("conversation()") })
    }

    @Test
    fun `M4 留言通知安静躺着-不响不横幅不震动（用户 10-20 定口径）`() {
        val text = notifier().joinToString("\n")
        for (noisy in listOf("setPriority", "setDefaults", "setVibrate", "setSound")) {
            assertTrue("留言通知用了 $noisy＝会响或会横幅，与\"静静躺在通知里\"相反",
                !text.contains(noisy))
        }
        val ch = msgChannel(source("app/src/main/java/com/soul2soul/app/session/Notif.kt"))
        assertTrue("msg 渠道必须真的是 IMPORTANCE_LOW", ch.any { it.contains("IMPORTANCE_LOW") })
        assertTrue("同一条语句里不许再出现别的档位（HIGH/MAX＝横幅+声音）",
            ch.none { it.contains("IMPORTANCE_HIGH") || it.contains("IMPORTANCE_MAX") || it.contains("IMPORTANCE_DEFAULT") })
    }

    /** CH_MSG 那条渠道语句：同一行收尾就取一行，跨行则取到收尾括号（不许只取半句） */
    private fun msgChannel(lines: List<String>): List<String> {
        val a = lines.indexOfFirst { it.contains("NotificationChannel(CH_MSG") }
        assertTrue("找不到 msg 渠道——渠道被改名/删除时这条锁必须先红，不许静默跳过", a >= 0)
        if (lines[a].contains(")")) return listOf(lines[a])
        val b = ((a + 1)..lines.lastIndex).firstOrNull { lines[it].contains(")") }
            ?: error("msg 渠道语句取不出收尾，扫描口径需重看")
        return lines.subList(a, b + 1)
    }
}
