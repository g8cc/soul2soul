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

    /** 取 `val X = …apply {` 起、到第一个同样缩进的 `}` 为止的整段（不用魔法行数窗口） */
    private fun applyBlock(startAnchor: String): List<String> {
        val lines = source()
        val hits = lines.indices.filter { lines[it].contains(startAnchor) }
        assertTrue("$startAnchor 应恰好出现一次，实际 ${hits.size} 次", hits.size == 1)
        val start = hits[0]
        val pad = " ".repeat(lines[start].takeWhile { it == ' ' }.length)
        val end = ((start + 1)..lines.lastIndex).firstOrNull { lines[it] == "${pad}}" }
            ?: error("找不到 $startAnchor 的收尾 }")
        return lines.subList(start, end)
    }

    /**
     * 共享端建通道的顺序不是风格问题：旧观看端（≤0.2.65）的 onDataChannel 是后到者覆盖，
     * anno 必须最后建，否则新通道会把笔迹通道顶掉——涂鸦静默失效。
     */
    @Test
    fun `audCreatedBeforeAnno`() {
        val body = functionBody("fun startSharer(")
        val aud = body.indexOfFirst { it.contains("createDataChannel(CtlChannelPolicy.AUD") }
        val anno = body.indexOfFirst { it.contains("createDataChannel(\"anno\"") }
        assertTrue("startSharer 里必须建 aud 通道", aud >= 0)
        assertTrue("anno 必须仍然最后建（旧观看端后到者覆盖）：aud@$aud anno@$anno", aud < anno)
    }

    /** aud 是"过期即无用"的载荷：有序会造队头阻塞，重传会送旧帧，两者都必须关着 */
    @Test
    fun `audInitIsUnorderedAndUnretransmitting`() {
        val block = applyBlock("val audInit = DataChannel.Init().apply {")
        assertTrue("aud 必须 unordered", block.any { it.contains("ordered = false") })
        assertTrue("aud 必须不重传", block.any { it.contains("maxRetransmits = 0") })
    }

    /**
     * WebRtcClient 自己的 onMessage 起手就是 `if (buffer.binary) return`，
     * 复用 this 当 aud 的 observer＝音频帧全部被丢掉（不崩、无日志、通道 OPEN），
     * 顺带每次音频通道状态变化还会重发一次画质请求。
     */
    @Test
    fun `audUsesDedicatedObserver`() {
        val body = functionBody("private fun audObserverFor(")
        assertTrue("aud 的 observer 必须是独立对象（不许复用 this）",
            body.first().contains("object : DataChannel.Observer"))
        val onMessage = body.indexOfFirst { it.contains("override fun onMessage(") }
        assertTrue("aud observer 必须实现 onMessage", onMessage >= 0)
        assertTrue("aud 的文本载荷必须显式拒绝（这条通道只走二进制）",
            body.drop(onMessage).take(3).any { it.contains("if (!buffer.binary)") })
        val dispatch = functionBody("override fun onDataChannel(")
        val audCase = dispatch.indexOfFirst { it.contains("CtlChannelPolicy.AUD ->") }
        assertTrue("onDataChannel 必须显式接管 aud", audCase >= 0)
        assertTrue("aud 分支必须注册专属 observer，不能落到结尾的 registerObserver(this)",
            dispatch.subList(audCase, dispatch.size).any { it.contains("audObserverFor(") })
        val created = functionBody("fun startSharer(")
        assertTrue("共享端自己建的 aud 也必须挂专属 observer",
            created.any { it.contains("registerObserver(audObserverFor(") })
    }

    /**
     * ack 只能压在"通道真的 OPEN"之后：通道没通就 ack，对面会起一个帧送不出去的采集
     * （表现是"她开了开关、我这边啥也没有"，正是最难查的那类假成功）。
     */
    @Test
    fun `audAckWaitsForChannelOpen`() {
        val body = functionBody("private fun sendAudAckOnce(")
        val open = body.indexOfFirst { it.contains("DataChannel.State.OPEN") }
        val send = body.indexOfFirst { it.contains("sendControlNow(") }
        assertTrue("sendAudAckOnce 必须先判 OPEN 再发 ack（open@$open send@$send）", open in 0 until send)
        assertTrue("audok 必须走 ctl（有序可靠），不许图省事发到刚建的 aud 上",
            body.any { it.contains("DeviceAudioHandshake.ackJson()") })
    }

    /** 通道选择表也不许 else 兜底：未知 label 悄悄落到 anno＝把背压传染给笔迹 */
    @Test
    fun `channelByLabel_hasNoCatchAllToAnno`() {
        val body = functionBody("private fun channelByLabel(")
        val elseIdx = body.indexOfFirst { it.trim().startsWith("else ->") }
        assertTrue("channelByLabel 必须穷举（找不到 else）", elseIdx >= 0)
        val tail = body.drop(elseIdx)
        assertTrue("else 分支不许返回 dataChannel：$tail", tail.none { it.contains("dataChannel") })
        assertTrue("anno 必须有自己的显式分支", body.any { it.contains("CtlChannelPolicy.ANNO ->") })
    }
}
