package com.soul2soul.app.webrtc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioChannelWiringTest {
    private val clientPath = "app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt"

    private fun file(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, clientPath)
            if (candidate.exists()) return candidate.readLines()
            dir = dir.parentFile
        }
        error("找不到 $clientPath")
    }

    private fun functionBody(lines: List<String>, headerIndex: Int): List<String> {
        require(headerIndex >= 0) { "函数声明未找到" }
        val pad = " ".repeat(lines[headerIndex].takeWhile { it == ' ' }.length)
        val nextFun = Regex("""^$pad(\w+\s+)*fun\s""")
        val end = ((headerIndex + 1)..lines.lastIndex).firstOrNull { i ->
            lines[i] == "${pad}}" || nextFun.containsMatchIn(lines[i])
        } ?: error("找不到函数体结束位置: ${lines[headerIndex]}")
        return lines.subList(headerIndex, end)
    }

    @Test
    fun `closed audio channel revokes ack and start gate`() {
        val lines = file()
        val supported = functionBody(
            lines,
            lines.indexOfFirst { it.contains("val deviceAudioPeerSupported") },
        )
        assertTrue("ACK 不能跨 aud 通道关闭存活",
            supported.any { it.contains("deviceAudioPeerAcked && deviceAudioChannelOpen()") })

        val observer = functionBody(
            lines,
            lines.indexOfFirst { it.contains("private fun audObserverFor(") },
        )
        val closeGate = observer.indexOfFirst {
            it.contains("state == DataChannel.State.CLOSED.name && dc === audChannel")
        }
        val revokeAck = observer.indexOfFirst { it.contains("deviceAudioPeerAcked = false") }
        val resetSent = observer.indexOfFirst { it.contains("audAckSent = false") }
        assertTrue("只能撤销当前 aud 通道的 ACK：gate@$closeGate", closeGate >= 0)
        assertTrue("CLOSED 必须撤销 ACK 和发送锁存，允许新通道重新握手",
            revokeAck > closeGate && resetSent > revokeAck)
    }

    @Test
    fun `ack retries when ctl opens after aud and channel replacement resets latch`() {
        val lines = file()
        val ctlChangeIndex = lines.indexOfLast { it.trim() == "override fun onStateChange() {" }
        val ctlChange = functionBody(lines, ctlChangeIndex)
        assertTrue("aud 先 OPEN、ctl 后 OPEN 时必须补发握手",
            ctlChange.any { it.contains("audChannel?.let { sendAudAckOnce(it) }") })

        val assignment = lines.indexOfFirst { it.contains("audChannel = dc") }
        assertTrue("替换 aud channel 必须存在", assignment >= 0)
        val receive = functionBody(
            lines,
            lines.indexOfFirst { it.contains("override fun onDataChannel(dc: DataChannel)") },
        )
        assertTrue("接收新 aud channel 时重置 ACK 发送锁存",
            receive.any { it.contains("audChannel = dc") } && receive.any { it.contains("audAckSent = false") })
    }
}
