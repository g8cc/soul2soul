package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「米15 发起后几秒即断」的取证锁（v0.2.57 候选）。
 *
 * 服务器每 30s 探一次心跳，pong 不回就 `terminate()` 并给对端广播 `peer.gone`
 * （10-17 一晚就有 6 次 pong 超时）。可两端都把它当"对方结束了会话"直接拆（
 * ScreenShareService 的 bye/peer.gone 分支、SignalRouter 的 PeerEnded），而 WebRTC 媒体
 * 是 P2P 的、跟信令 socket 死活无关——于是"画面还在、语音还在"的通话会被一句信令离线拆掉。
 * 在拿到真机日志判定之前**不改这个行为**，但必须先把"到达瞬间的 ICE 态"取到证据：
 * 拆完之后 ICE 只会是 CLOSED，再问"是不是信令假死拆的"就永远没有答案。
 *
 * 回滚顺序（把上报挪到 stopSession/finishWithCleanup 之后）用例必须变红。
 */
class PeerSignalProbeTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun share() = source("app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt")
    private fun session() = source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")
    private fun protocol() = source("docs/PROTOCOL.md")

    /** 锚点起往后取 size 行；锚点找不到直接红，避免"文件改了但断言落在别处"的假绿 */
    private fun window(lines: List<String>, anchor: String, size: Int): List<String> {
        val i = lines.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return lines.drop(i).take(size)
    }

    // P1：共享端收到 bye/peer.gone 时必须先取证再拆会话，且带上 ICE 实况
    @Test
    fun `sharer records ice state before tearing the session down`() {
        val branch = window(share(), "\"bye\", \"peer.gone\" -> {", 12)
        val report = branch.indexOfFirst { it.contains("\"peer_signal\"") }
        val teardown = branch.indexOfFirst { it.contains("stopSession(sendBye = false)") }
        assertTrue("分支里必须上报 peer_signal（信令离线被当成挂断的唯一取证点）", report >= 0)
        assertTrue("peer_signal 上报必须排在 stopSession 之前（之后 ice 只会是 CLOSED，证据没了）",
            report < teardown)
        val probe = branch.drop(report).take(4)
        assertTrue("必须带 webRtc 的 ICE 实况而不是写死占位",
            probe.any { it.contains("webRtc?.iceStateName") })
        assertTrue("必须区分 bye（真挂断）与 peer.gone（只是信令掉线）",
            probe.any { it.contains("sig=") && it.contains("peer.gone") })
    }

    // P2：观看端对称——PeerEnded 拆页面之前同样取证，且按 notice 区分两种来源
    @Test
    fun `viewer records ice state before finishing the activity`() {
        val branch = window(session(), "is SignalRouter.SignalAction.PeerEnded -> {", 12)
        val report = branch.indexOfFirst { it.contains("\"peer_signal\"") }
        val teardown = branch.indexOfFirst { it.contains("finishWithCleanup(sendBye = false") }
        assertTrue("PeerEnded 分支必须上报 peer_signal", report >= 0)
        assertTrue("peer_signal 上报必须排在 finishWithCleanup 之前", report < teardown)
        assertTrue("必须用 peer_lost 区分「信令掉线」与「对方挂断」",
            branch.any { it.contains("R.string.peer_lost") })
    }

    // P3：事件名必须进协议文档的事件表，否则服务端日志出现了也没人知道怎么读
    @Test
    fun `event is registered in the protocol`() {
        assertTrue("PROTOCOL §2.3 的 diag.event 枚举必须含 peer_signal",
            protocol().any { it.contains("`peer_signal`") })
    }
}
