package com.soul2soul.app.webrtc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观看端的通道分派：共享端每加一条新 DataChannel，老版本这里若是 else 兜底，
 * 就会拿新通道顶掉 anno 并 unregisterObserver——涂鸦随即**静默**失效
 * （不崩、无日志、通道对象还在，JVM 里跑不出来）。
 * 所以「onDataChannel 不许有吞掉 label 的兜底赋值」必须是机器可查的。
 */
class AudWiringTest {

    private fun source(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt")
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 WebRtcClient.kt（工作目录: ${File("").absolutePath}）")
    }

    private fun functionBody(anchor: String): List<String> {
        val lines = source()
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertTrue("锚点应恰好出现一次，实际 ${hits.size} 次：$anchor", hits.size == 1)
        val header = hits[0]
        val pad = " ".repeat(lines[header].takeWhile { it == ' ' }.length)
        val nextFun = Regex("""^$pad(\w+\s+)*fun\s""")
        val end = ((header + 1)..lines.lastIndex).firstOrNull { i ->
            lines[i] == "${pad}}" || nextFun.containsMatchIn(lines[i])
        } ?: error("找不到 $anchor 的收尾（锚点缩进被改了？）")
        return lines.subList(header, end)
    }

    @Test
    fun `onDataChannel_hasNoCatchAllElse`() {
        val body = functionBody("override fun onDataChannel(")
        // 三个已知 label 必须逐个显式接管，漏一个＝那条通道永远接不上
        listOf("CtlChannelPolicy.CTL", "CtlChannelPolicy.CTLG", "CtlChannelPolicy.ANNO").forEach { label ->
            assertTrue("onDataChannel 必须显式处理 $label", body.any { it.contains("$label ->") })
        }
        val elseIdx = body.indexOfFirst { it.trim().startsWith("else ->") }
        assertTrue("onDataChannel 里必须只有一个分支出口，找到 else 才算穷举写完了", elseIdx >= 0)
        val tail = body.drop(elseIdx)
        listOf("dataChannel", "ctlChannel", "ctlgChannel").forEach { field ->
            assertTrue("else 分支不许给 $field 赋值（顶掉它＝涂鸦静默失效）：$tail",
                tail.none { it.contains("$field =") })
        }
        assertTrue("else 必须在不 registerObserver 之前就返回，未知通道不能挂上本端 observer",
            tail.indexOfFirst { it.contains("return") } in 0 until tail.indexOfFirst { it.contains("registerObserver") })
    }
}
