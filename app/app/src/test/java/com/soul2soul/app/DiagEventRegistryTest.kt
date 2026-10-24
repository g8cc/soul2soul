package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `diag.event` 事件名注册表锁（v0.2.59）。
 *
 * 为什么要有这一条：0.2.55~0.2.58 每一版都新增事件名，而 PROTOCOL §2.3 的清单是**散文**——
 * 写了 `sink_attached` 却没人规定代码必须发，改了名字文档也不会变红。结果是读日志的人
 * （包括几星期后的我自己）按文档 grep 到一个从来没响过的名字，然后把"名字不存在"读成
 * "这件事没发生"。`PeerSignalProbeTest` 里那种"只检查一个名字在不在文档里"的断言就是这个漏洞的样本。
 *
 * 做法：从 main 源码扫出**代码真的会发**的全部事件名（含 `reportEvent` 的各种本地包装），
 * 与 PROTOCOL 里 `DIAG-EVENT-NAMES` 那一行逐一比对，**两个方向都不许有差**。
 */
class DiagEventRegistryTest {

    private class Wrapper(val index: Int, val declaringFile: String, val isPrivate: Boolean)

    /** 事件名 -> 出处文件名（保序，报错时能指出第几个是哪来的） */
    private val emitted = linkedMapOf<String, String>()
    private val nameRe = Regex("^[a-z][a-z0-9_]*$")

    private fun repoFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun ktFiles(): List<File> =
        repoFile("app/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun docNames(): List<String> {
        val line = repoFile("docs/PROTOCOL.md").readLines()
            .firstOrNull { it.contains("DIAG-EVENT-NAMES:") }
        assertTrue("PROTOCOL §2.3 必须有 DIAG-EVENT-NAMES 那一行——它是文档与代码之间唯一的机器连接", line != null)
        return line!!.substringAfter("DIAG-EVENT-NAMES:").substringBefore("-->")
            .split('/').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 注释掩码：长度与换行位置不变，注释内容换成空格；字符串原样保留。
     * 结构扫描必须看掩码版，否则注释里提到的 `emit(...)` 会被当成调用点。
     */
    private fun mask(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var line = false
        var block = false
        var str = false
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                line -> if (c == '\n') { line = false; out.append('\n') } else out.append(' ')
                block -> if (c == '*' && n == '/') { block = false; out.append("  "); i += 1 } else out.append(if (c == '\n') '\n' else ' ')
                str -> {
                    out.append(c)
                    if (c == '\\') { out.append(n); i += 1 } else if (c == '"') str = false
                }
                c == '/' && n == '/' -> { line = true; out.append("  ") }
                c == '/' && n == '*' -> { block = true; out.append("  ") }
                c == '"' -> { str = true; out.append(c) }
                else -> out.append(c)
            }
            i += 1
        }
        return out.toString()
    }

    /** `open` 指向调用的左括号；返回第 index 个实参的原文（括号嵌套与字符串都算清楚） */
    private fun argAt(s: String, open: Int, index: Int): String? {
        var depth = 0
        var comma = 0
        var start = open + 1
        var i = open
        var str = false
        var chr = false
        while (i < s.length) {
            val c = s[i]
            if (str) {
                if (c == '\\') i += 1 else if (c == '"') str = false
            } else if (chr) {
                if (c == '\\') i += 1 else if (c == '\'') chr = false
            } else when (c) {
                '"' -> str = true
                '\'' -> chr = true
                '(' -> depth += 1
                ')' -> if (depth == 1) {
                    return if (comma == index) s.substring(start, i) else null
                } else depth -= 1
                ',' -> if (depth == 1) {
                    if (comma == index) return s.substring(start, i)
                    comma += 1
                    start = i + 1
                }
            }
            i += 1
        }
        return null
    }

    /**
     * 只收实参**顶层**的字面量：`if (x) "a" else b.optString("event", "-")` 里的 `"event"`
     * 是嵌套调用的键名而不是事件名，深度 >0 的一律不收。
     */
    private fun topLevelNames(arg: String): List<String> {
        val found = mutableListOf<String>()
        var i = 0
        var depth = 0
        var str = false
        var start = -1
        while (i < arg.length) {
            val c = arg[i]
            if (str) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') {
                    str = false
                    if (depth == 0) {
                        val body = arg.substring(start, i)
                        if (nameRe.matches(body)) found += body
                    }
                }
                i += 1
            } else {
                when (c) {
                    '"' -> { str = true; start = i + 1 }
                    '(' -> depth += 1
                    ')' -> depth -= 1
                }
                i += 1
            }
        }
        return found
    }

    private fun take(names: List<String>, file: String) = names.forEach { emitted.putIfAbsent(it, file) }

    @Test
    fun `code and PROTOCOL agree on the exact set of diag event names`() {
        val files = ktFiles().map { it.name to mask(it.readText()) }

        // 1) 找出所有"接收 event: String 形参"的函数，记下事件位下标与可见性
        //    ⚠️ 按 (名字) 归**多份**而不是覆盖：`Map<String, Wrapper>` 会让同名私有包装函数只留下
        //    最后扫到的那个文件，其它文件里的同名包装被当成"别人的私有函数"整体跳过
        //    ——10-22 实测踩过：DeviceAudioTap 与 DeviceAudioPlayer 各有一个 `audEvent(event: String, …)`，
        //    观看端的 `aud_play` 因此扫不出来。这类失效是静默的（少收名字 ⇒ 反向差集里冒出"文档登记了代码从不发"，
        //    看着像代码漏埋），所以键必须带出处。
        val decl = Regex("""fun\s+(\w+)\s*\(([^)]*)\)""")
        val wrappers = mutableMapOf<String, MutableList<Wrapper>>()
        files.forEach { (fileName, src) ->
            decl.findAll(src).forEach { m ->
                val params = m.groupValues[2]
                if (!params.contains("event: String")) return@forEach
                val idx = params.split(',').indexOfFirst { it.trim().startsWith("event:") }
                if (idx < 0) return@forEach
                val lineStart = src.lastIndexOf('\n', m.range.first) + 1
                val isPrivate = src.substring(lineStart, m.range.first).contains("private")
                val candidate = Wrapper(idx, fileName, isPrivate)
                val list = wrappers.getOrPut(m.groupValues[1]) { mutableListOf() }
                if (list.none { it.declaringFile == fileName && it.index == idx }) list.add(candidate)
            }
        }
        // 扫描器自己坏了必须被发现：这四个是已知的出口，少一个就说明形状对不上源码了
        listOf("reportEvent", "emit", "viewerEvent", "reportSignaling").forEach {
            assertTrue("没扫到包装函数 $it（调用点扫描已失效，这条锁等于没锁）", wrappers.containsKey(it))
        }

        // 2) 扫调用点，取事件位实参的顶层字面量
        files.forEach { (fileName, src) ->
            wrappers.forEach { (fn, defs) ->
                defs.forEach { w ->
                    if (w.isPrivate && w.declaringFile != fileName) return@forEach
                    var from = 0
                    while (true) {
                        val hit = src.indexOf("$fn(", from)
                        if (hit < 0) break
                        from = hit + 1
                        val before = if (hit == 0) ' ' else src[hit - 1]
                        if (before.isLetterOrDigit() || before == '_') continue // transmit( 之类不算
                        val lineStart = src.lastIndexOf('\n', hit) + 1
                        if (src.substring(lineStart, hit).contains("fun ")) continue // 声明不是调用
                        argAt(src, hit + fn.length, w.index)?.let { take(topLevelNames(it), fileName) }
                    }
                }
            }
            // 补报重放路径不经 reportEvent（会递归），它直接 put("event", …)
            var from = 0
            while (true) {
                val hit = src.indexOf("put(\"event\"", from)
                if (hit < 0) break
                from = hit + 1
                argAt(src, src.indexOf('(', hit), 1)?.let { take(topLevelNames(it), fileName) }
            }
        }

        val documented = docNames().toSet()
        val neverEmitted = (documented - emitted.keys).toSortedSet()
        val undocumented = (emitted.keys - documented).toSortedSet()
        // 文档登记"计划中"的名字就等于这条锁永远不会红，所以反向也要断言
        assertTrue("注册表里登记了代码从不发的事件名（读日志会把「名字不存在」读成「这件事没发生」）: $neverEmitted", neverEmitted.isEmpty())
        assertTrue(
            "代码在发、文档没登记（读日志的人 grep 不到，比缺埋点更糟）: " +
                undocumented.joinToString { "$it→${emitted[it]}" },
            undocumented.isEmpty(),
        )
        // 本轮埋点存在的理由，一个都不许从扫描结果里漏掉
        listOf("heartbeat", "qos_tick", "qos_bad", "gather_done", "ice_failed", "aexit", "app_exit",
            "backlog_drop", "session_end", "bucket_", "net_up", "doze_on", "wake_fail", "peer_signal",
            "aud_started", "aud_stopped", "aud_silent", "aud_tick", "aud_play", "aud_playback_error")
            .forEach { assertTrue("扫描器没抓到 $it", emitted.containsKey(it)) }
        // 同名私有包装函数分处两个文件时，两边的调用点都必须被收到
        //（10-22 的失效形状：Map 覆盖 ⇒ DeviceAudioPlayer 的 `audEvent` 被当成"别人的私有函数"整体跳过，
        //  观看端唯一发出的 `aud_play` 扫不出来 ⇒ 报成"文档登记了代码从不发的事件名"，看着像漏埋）
        assertTrue("aud_play 的出处必须是播放器（归错文件＝包装函数登记表又塌回一份）",
            emitted["aud_play"] == "DeviceAudioPlayer.kt")
        assertTrue("aud_tick 的出处必须是采集线程",
            emitted["aud_tick"] == "DeviceAudioTap.kt")
    }
}
