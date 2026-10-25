package com.soul2soul.app.webrtc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpStatsSelectionGuardTest {
    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val file = File(dir, rel)
            if (file.exists()) return file.readLines()
            dir = dir.parentFile
        }
        error("找不到 $rel")
    }

    private fun between(lines: List<String>, from: String, to: String): List<String> {
        val start = lines.indexOfFirst { it.contains(from) }
        val end = lines.indexOfFirst { it.contains(to) }
        assertTrue("找不到起点 $from", start >= 0)
        assertTrue("找不到终点 $to（必须在起点之后）", end > start)
        return lines.subList(start, end)
    }

    @Test
    fun `remote inbound video stats must follow the primary outbound stream`() {
        val body = between(
            source("app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt"),
            "var remoteVideoLost = -1L",
            "val audioWindow =",
        )
        assertTrue(
            "远端回报必须按 primary outbound 的 localId 关联，不能被 RTX 的遍历顺序覆盖",
            body.any { it.contains("selectRemoteInboundVideoStats") } &&
                body.any { it.contains("localId") },
        )
    }

    @Test
    fun `primary video stats must exclude RTX before choosing a stream`() {
        val client = source("app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt")
        val selector = source("app/src/main/java/com/soul2soul/app/webrtc/RtpStatsSelector.kt")
        assertTrue("主视频统计选择必须识别 codec mimeType", selector.any { it.contains("codecMimeType") })
        assertTrue("主视频统计选择必须排除 video/rtx", selector.any { it.contains("/rtx") })
        assertTrue(
            "主视频与观看端收流都必须经过统一选择器",
            client.count { it.contains("RtpStatsSelector.selectPrimaryVideoStats") } >= 2,
        )
    }

    @Test
    fun `rtx is excluded and remote inbound follows local outbound id`() {
        val mainOutbound = RtpStatsSelector.VideoStatRef(
            id = "out-main",
            ssrc = 101L,
            codecMimeType = "video/VP9",
            frameCount = 300L,
        )
        val rtxOutbound = RtpStatsSelector.VideoStatRef(
            id = "out-rtx",
            ssrc = 202L,
            codecMimeType = "video/rtx",
            frameCount = -1L,
        )
        assertEquals(mainOutbound, RtpStatsSelector.selectPrimaryVideoStats(listOf(rtxOutbound, mainOutbound)))

        val rtxRemote = RtpStatsSelector.VideoStatRef(
            id = "remote-rtx",
            localId = "out-rtx",
            ssrc = 202L,
        )
        val mainRemote = RtpStatsSelector.VideoStatRef(
            id = "remote-main",
            localId = "out-main",
            ssrc = 101L,
        )
        assertEquals(
            mainRemote,
            RtpStatsSelector.selectRemoteInboundVideoStats(
                listOf(rtxRemote, mainRemote),
                mainOutbound,
            ),
        )
    }

    @Test
    fun `only RTX stats do not become the primary video stream`() {
        val rtx = RtpStatsSelector.VideoStatRef(
            id = "out-rtx",
            codecMimeType = "video/rtx",
            packetCount = 400L,
            byteCount = 20_000L,
        )
        assertEquals(null, RtpStatsSelector.selectPrimaryVideoStats(listOf(rtx)))
    }

    @Test
    fun `active primary stream wins over an inactive stream with a larger stale counter`() {
        val inactive = RtpStatsSelector.VideoStatRef(
            id = "out-old",
            codecMimeType = "video/VP9",
            frameCount = 10_000L,
            active = false,
        )
        val active = RtpStatsSelector.VideoStatRef(
            id = "out-live",
            codecMimeType = "video/VP9",
            frameCount = 10L,
            active = true,
        )
        assertEquals(active, RtpStatsSelector.selectPrimaryVideoStats(listOf(inactive, active)))
    }
}
