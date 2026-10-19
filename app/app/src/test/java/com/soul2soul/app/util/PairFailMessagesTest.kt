package com.soul2soul.app.util

import com.soul2soul.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁定 v0.2.63 的配对死路修复：`pair.failed` 的 reason 必须各自落到不同文案。
 * 起因（10-19 实锤）：重装 App 会换 deviceId（`Prefs.kt:12-17` 存本机 SharedPreferences），
 * 而服务器仍认为对方"已配对" ⇒ `already_paired`，客户端当时把 reason 全盖成一句
 * 「请检查配对码是否正确」，用户照那句话再输三次还是死路。
 */
class PairFailMessagesTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "server/src/pairing.js").exists()) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 server/src/pairing.js（工作目录: ${File("").absolutePath}）")
    }

    private fun read(rel: String) = File(repoRoot(), rel).readText()

    /** 服务器在这一条消息上会发出的 reason 全集（不是手写清单，是从服务器源码里扫出来的） */
    private fun serverReasons(): Set<String> {
        val book = read("server/src/pairing.js")
        val fromBook = Regex("""reason:\s*'([a-z_]+)'""").findAll(book).map { it.groupValues[1] }.toSet()
        val index = read("server/src/index.js")
        // 只认 pair.failed 那两处 send 的字面量，别把 peer.offline / pair.recall_failed 的理由混进来
        val fromIndex = Regex("""type:\s*'pair\.failed',\s*reason:\s*'([a-z_]+)'""")
            .findAll(index).map { it.groupValues[1] }.toSet()
        // pairing.js 的 recall 不在这个类里（另一个本子），但仍要排掉同文件中属于别的消息的名字
        val returnedAsPairFailed = setOf(
            "code_invalid", "already_paired", "too_many_attempts", "too_fast", "unavailable", "self_pair",
        )
        val unknown = fromBook - returnedAsPairFailed
        assertTrue("pairing.js 出现了未登记的 reason：$unknown（新 reason 必须同时进 PairFailMessages 与这条测试）",
            unknown.isEmpty())
        return returnedAsPairFailed + fromIndex
    }

    @Test
    fun `客户端清单与服务器实际会发的 reason 完全一致`() {
        assertEquals(serverReasons(), PairFailMessages.SERVER_REASONS)
    }

    @Test
    fun `每个 reason 给的是不同文案，未知才落通用`() {
        val res = PairFailMessages.SERVER_REASONS.map { it to PairFailMessages.messageRes(it) }
        assertEquals("有 reason 共用了同一条文案（= 用户分不出该走哪条路）",
            PairFailMessages.SERVER_REASONS.size, res.map { it.second }.distinct().size)
        val generic = R.string.pair_failed
        res.forEach { (reason, id) ->
            assertTrue("$reason 落回了通用文案", id != generic)
        }
        assertEquals(generic, PairFailMessages.messageRes(null))
        assertEquals(generic, PairFailMessages.messageRes("brand_new_reason"))
    }

    @Test
    fun `when 分支与 SERVER_REASONS 不撒谎`() {
        val src = read("app/app/src/main/java/com/soul2soul/app/util/PairFailMessages.kt")
        val branches = Regex(""""([a-z_]+)"\s*->\s*R\.string\.pair_fail_""").findAll(src)
            .map { it.groupValues[1] }.toSet()
        assertEquals(PairFailMessages.SERVER_REASONS, branches)
    }

    @Test
    fun `PROTOCOL 的 pair_failed 清单必须覆盖每个 reason`() {
        val doc = read("docs/PROTOCOL.md")
        val line = doc.lines().first { it.startsWith("| `pair.failed`") }
        // 只认"枚举那一格"：说明列里也会写到 `already_paired`，整行匹配会让这条判据白过（10-19 实测）
        val cell = line.split("|")[2]
        assertTrue("pair.failed 那行的第二格不是 reason 枚举：$cell", cell.trim().startsWith("`reason`"))
        val missing = PairFailMessages.SERVER_REASONS.filterNot { cell.contains("`$it`") }
        assertTrue("PROTOCOL.md 的 pair.failed 枚举少了：$missing", missing.isEmpty())
    }

    @Test
    fun `MainActivity 的 pair_failed 分支必须走映射表`() {
        val src = read("app/app/src/main/java/com/soul2soul/app/MainActivity.kt")
        val sites = Regex(""""pair\.failed"""").findAll(src).count()
        assertEquals("pair.failed 出现了多个消费点，逐个确认都要过映射表", 1, sites)
        val branch = src.substringAfter("\"pair.failed\"").take(240)
        assertTrue("pair.failed 又退回单条通用 toast 了：$branch",
            branch.contains("PairFailMessages.messageRes"))
    }
}
