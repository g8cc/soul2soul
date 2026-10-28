package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「米15 发起后几秒即断」的取证锁（v0.2.57 候选）。
 *
 * 服务器每 30s 探一次心跳，pong 不回就 `terminate()` 并给对端广播 `peer.gone`
 * （10-17 一晚就有 6 次 pong 超时）。如果两端都把它当"对方结束了会话"直接拆，
 * WebRTC 媒体又与信令 socket 独立，就会把"画面还在、语音还在"的通话被一句信令离线拆掉。
 * 现在 peer.gone 只进入短暂恢复窗口，只有媒体终态或恢复超时才结束；bye 仍然立即结束。
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

    // P1：共享端收到 bye 时必须先取证再拆；peer.gone 只能进入恢复路径
    @Test
    fun `sharer records ice state before tearing the session down`() {
        val branch = window(share(), "\"bye\" -> {", 16)
        val report = branch.indexOfFirst { it.contains("\"peer_signal\"") }
        val teardown = branch.indexOfFirst { it.contains("stopSession(sendBye = false") }
        assertTrue("bye 分支里必须上报 peer_signal", report >= 0)
        assertTrue("窗口里找不到 stopSession（要么被删，要么窗口窄了——这时下面那条顺序断言会假绿）",
            teardown >= 0)
        assertTrue("peer_signal 上报必须排在 stopSession 之前",
            report < teardown)
        val probe = branch.drop(report).take(4)
        assertTrue("必须带 webRtc 的 ICE 实况而不是写死占位",
            probe.any { it.contains("webRtc?.iceStateName") })
        assertTrue("bye 必须标明真实挂断来源", probe.any { it.contains("sig=bye") })
        val gone = window(share(), "\"peer.gone\" -> handleSignalLost", 2)
        assertTrue("peer.gone 必须进入信令恢复路径而不是立即拆会话",
            gone.any { it.contains("Source.PEER") })
    }

    // P2：观看端对称——bye 拆页面之前同样取证；peer.gone 由独立恢复动作处理
    @Test
    fun `viewer records ice state before finishing the activity`() {
        val branch = window(session(), "is SignalRouter.SignalAction.PeerEnded -> {", 16)
        val report = branch.indexOfFirst { it.contains("\"peer_signal\"") }
        val teardown = branch.indexOfFirst { it.contains("finishWithCleanup(") }
        assertTrue("PeerEnded 分支必须上报 peer_signal", report >= 0)
        assertTrue("窗口里找不到 finishWithCleanup（断行方式变了会让下面那条顺序断言假绿）",
            teardown >= 0)
        assertTrue("peer_signal 上报必须排在 finishWithCleanup 之前", report < teardown)
        assertTrue("bye 必须沿用对端主动结束的文案来源",
            branch.any { it.contains("notice = action.noticeRes") })
        assertTrue("peer.gone 必须走 SignalLost 而不是 PeerEnded",
            session().any { it.contains("is SignalRouter.SignalAction.SignalLost -> handleSignalLost(action.source)") })
    }

    // P3：事件名必须进协议文档的事件表，否则服务端日志出现了也没人知道怎么读
    @Test
    fun `event is registered in the protocol`() {
        assertTrue("PROTOCOL §2.3 的 diag.event 枚举必须含 peer_signal",
            protocol().any { it.contains("`peer_signal`") })
    }
}
