package com.soul2soul.app.webrtc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备内录（FR-16）的接线锁。
 *
 * 采集线程本体跑不了 JVM（AudioRecord/MediaCodec 是设备对象），所以这里锁的是**结构**：
 * 七条出口是否都有调用点、静音与内录是否互不干涉、重绑是否真的换了 projection 柄、
 * usage 白名单里是否混进了她自己的语音。这些一旦写歪都是"不崩、无日志、只是没声音"那一类，
 * 只有源码扫描能拦住。
 */
class DeviceAudioTapWiringTest {

    private val servicePath = "app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt"
    private val tapPath = "app/src/main/java/com/soul2soul/app/webrtc/DeviceAudioTap.kt"
    private val clientPath = "app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt"

    private fun file(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    /** 从锚点行起，到第一个 `stop` 锚点之前（不含）；两个锚点都必须恰好一次命中 */
    private fun between(lines: List<String>, start: String, stop: String): List<String> {
        val s = lines.indices.filter { lines[it].contains(start) }
        assertTrue("起始锚点应恰好一次，实际 ${s.size}：$start", s.size == 1)
        val e = ((s[0] + 1)..lines.lastIndex).firstOrNull { lines[it].contains(stop) }
            ?: error("找不到收尾锚点：$stop")
        return lines.subList(s[0], e)
    }

    /** 整段 `override fun X(` / `private fun X(`：按声明缩进找收尾 `}`，不用魔法行数 */
    private fun functionBody(lines: List<String>, anchor: String): List<String> {
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertTrue("锚点应恰好一次，实际 ${hits.size}：$anchor", hits.size == 1)
        val header = hits[0]
        val pad = " ".repeat(lines[header].takeWhile { it == ' ' }.length)
        val nextFun = Regex("""^$pad(\w+\s+)*fun\s""")
        val end = ((header + 1)..lines.lastIndex).firstOrNull { i ->
            lines[i] == "${pad}}" || nextFun.containsMatchIn(lines[i])
        } ?: error("找不到 $anchor 的收尾（锚点缩进被改了？）")
        return lines.subList(header, end)
    }

    /**
     * 计划里钉死的七条出口：每条都必须有一个 `Exit.X` 的调用点。
     * 少一条＝那条路径上采集线程照跑，表现是"她关了开关，上行还在烧 140kbps"。
     */
    @Test
    fun `audTapStopCoversAllSevenExits`() {
        val service = file(servicePath)
        val tap = file(tapPath)
        val hostExits = listOf(
            "TOGGLE_OFF" to service,
            "PROJECTION_STOPPED" to service,
            "SESSION_CLOSE" to service,
            "CHANNEL_CLOSED" to service,
            "SERVICE_DESTROY" to service,
            "REBIND" to service,
            // 这两条只有线程自己能发现（读写出错/编码器坏了），所以调用点在 tap 里
            "RECORD_ERROR" to tap,
            "CODEC_ERROR" to tap,
        )
        hostExits.forEach { (name, lines) ->
            val uses = lines.filter { it.contains("Exit.$name") && !it.contains("$name(") }
            assertTrue("出口 $name 没有调用点（枚举里声明了却没人用=那条路径不会停表）", uses.isNotEmpty())
        }
    }

    /**
     * `aud_stopped` 只能有一个发出点，且必须在循环的 finally 里——
     * 分成"宿主停的发一条、线程自杀的发另一条"迟早漏掉一条路径，而漏掉的那条没有读数。
     */
    @Test
    fun `audStoppedEmittedOnceInFinally`() {
        val tap = file(tapPath)
        val hits = tap.indices.filter { tap[it].contains("\"aud_stopped\"") }
        assertTrue("aud_stopped 必须只发一次，实际 ${hits.size} 次", hits.size == 1)
        val finallyIdx = tap.indexOfFirst { it.contains("} finally {") }
        assertTrue("采集线程必须有 finally 收尾", finallyIdx >= 0)
        assertTrue("aud_stopped 必须发在 finally 里：${tap[hits[0]]}", hits[0] > finallyIdx)
    }

    /**
     * 静音按钮只关 libwebrtc 的上行音轨，与内录是两条独立的通路。
     * 把它们连起来的话，"他按了静音"会顺手把视频声也关掉——两条功能互相吃掉对方。
     */
    @Test
    fun `muteToggleDoesNotTouchDeviceAudio`() {
        val branch = between(
            file(servicePath),
            "ACTION_TOGGLE_MUTE -> {",
            "ACTION_TOGGLE_CTL -> {",
        )
        listOf("audTap", "stopAudTap", "startAudTap", "audWanted").forEach { sym ->
            assertTrue("静音分支不许碰内录（$sym）：$branch", branch.none { it.contains(sym) })
        }
        assertTrue("静音分支仍须翻 micMuted 并调 muteLocalAudio", branch.any { it.contains("micMuted = !micMuted") })
    }

    /**
     * 重开授权成功后必须重绑——内录用的就是采集器手里那个 MediaProjection，换了采集器不换柄，
     * 下一帧读到的是已 dispose 的对象。**重开失败时只停不启**（旧柄已死，起不来也别留线程）。
     */
    @Test
    fun `rebindReturnsRestartOnlyForNewProjectionHandle`() {
        val service = file(servicePath)
        val branch = between(service, "ACTION_RESTART_CAPTURE -> {", "ACTION_TOGGLE_AUD -> {")
        val okGate = branch.indexOfFirst { it.contains("if (ok) {") }
        val stopIdx = branch.indexOfFirst { it.contains("Exit.REBIND") }
        val startIdx = branch.indexOfFirst { it.contains("startAudTap(\"rebind\")") }
        // 收尾那一行在 if (ok) 块之外，用它当"块结束"的界：两条重绑调用都必须落在块内
        val tailIdx = branch.indexOfFirst { it.contains("updateNotification(statusText())") }
        assertTrue("重开成功必须重绑内录（stop 旧线程）：okGate@$okGate", okGate in 0 until stopIdx)
        assertTrue("重绑必须先停后起：stop@$stopIdx start@$startIdx", stopIdx in 0 until startIdx)
        assertTrue("重绑的两条调用都必须在 if (ok) 里（重开失败只停不启）：tail@$tailIdx",
            startIdx in 0 until tailIdx)
        val failDetail = branch.indexOfFirst { it.contains("\"startCapture\"") }
        assertTrue(
            "重开失败分支只许报读数，不许起采集：${branch.subList(failDetail, okGate)}",
            branch.subList(failDetail, okGate).none { it.contains("startAudTap") },
        )
        // 柄必须现取，不许在字段里缓存旧 projection
        val tap = file(tapPath)
        assertTrue("内录不许自己再 getMediaProjection 一次（Android 14 拒复用 resultData）",
            tap.none { it.contains("getMediaProjection(") })
        assertTrue("宿主必须经采集器现取 projection",
            service.any { it.contains("projectionOf = { webRtc?.sharerMediaProjection() }") })
    }

    /**
     * usage 白名单就是隐私边界。VOICE_COMMUNICATION 一旦进去，她的语音（libwebrtc 外放）
     * 会被采回来再送回去＝自激回环；这一条是 load-bearing 的，注释里也写了。
     */
    @Test
    fun `usageWhitelistExcludesVoiceCommunication`() {
        val tap = file(tapPath)
        val addLines = tap.filter { it.contains("addMatchingUsage(") }
        listOf("USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN").forEach {
            assertTrue("usage 白名单少了 $it（漏 UNKNOWN＝对国产播放器注定静音）",
                addLines.any { l -> l.contains(it) })
        }
        assertTrue("usage 白名单不许含 VOICE_COMMUNICATION（她自己的语音绕回她耳朵）",
            addLines.none { it.contains("VOICE_COMMUNICATION") })
    }

    /** 采集线程的帧只有一个出口：sendDeviceAudioFrame（那里才有背压门） */
    @Test
    fun `tapSendsThroughDeviceAudioFrameOnly`() {
        val service = file(servicePath)
        val body = functionBody(service, "private fun startAudTap(")
        assertTrue("sink 必须走 sendDeviceAudioFrame", body.any { it.contains("sendDeviceAudioFrame(packet)") })
        assertTrue("内录不许借用 anno/ctl 通道（JSON 与二进制混一条道=背压传染给笔迹）",
            body.none { it.contains("sendAnnotation") || it.contains("sendControl(") })
    }

    /**
     * 重新协商 / ICE 重启 / 画质切档都**不许**动 aud 通道或内录：
     * 它们只重发 offer，通道还在，重绑采集＝白白打断一次她正在听的声音。
     */
    @Test
    fun `renegoAndQualitySwitchDoNotTouchAudChannel`() {
        val client = file(clientPath)
        listOf(
            "private fun applyCaptureLongEdge(",
            "override fun onRenegotiationNeeded(",
            "fun restartCapture(",
        ).forEach { anchor ->
            val body = functionBody(client, anchor)
            assertTrue("$anchor 不许碰 aud 通道/内录", body.none { it.contains("audChannel") })
        }
    }

    /** 收尾顺序：先停采集再 close（close 会 release audioModule 并关掉通道） */
    @Test
    fun `stopTapPrecedesClientClose`() {
        val body = functionBody(file(servicePath), "private fun stopSession(")
        val stop = body.indexOfFirst { it.contains("stopAudTap(DeviceAudioTap.Exit.SESSION_CLOSE)") }
        val close = body.indexOfFirst { it.contains("client?.close()") }
        assertTrue("stopSession 必须停内录", stop >= 0)
        assertTrue("必须先停采集再关 client（反过来=线程往已关闭的通道里塞帧）stop@$stop close@$close",
            stop in 0 until close)
    }

    /** 五条件门不许在 Activity 里再抄一遍：判定只有 DeviceAudioPolicy 一处真值 */
    @Test
    fun `gateIsCalledOnlyFromService`() {
        val service = file(servicePath)
        assertTrue("服务必须过五条件门", service.any { it.contains("DeviceAudioPolicy.gate(") })
        val main = file("app/src/main/java/com/soul2soul/app/MainActivity.kt")
        assertTrue("MainActivity 不许自己判门（会与服务不一致）",
            main.none { it.contains("DeviceAudioPolicy") })
    }

    /**
     * AudioRecord 构造不许设 audio source（10-22 双模拟器实测踩的坑）。
     * 框架在 `setAudioPlaybackCaptureConfig` 里要求源仍是 DEFAULT，设过 MIC 直接抛
     * `IllegalStateException: Cannot both set audio source and set playback capture config`，
     * 采集线程连一帧都没起来——表现是"开关打开了、横幅也变了，但她就是听不到"，
     * 不崩也没人看 logcat。禁令扫描范围＝整个 loop 函数体（含 builder 链的每一环）。
     */
    @Test
    fun `recordBuilderDoesNotSetAudioSource`() {
        val tap = file(tapPath)
        val body = functionBody(tap, "private fun loop(")
        assertTrue("必须真的用 playback capture 配置建 AudioRecord",
            body.any { it.contains("setAudioPlaybackCaptureConfig(") })
        assertTrue("AudioRecord.Builder 不许调 setAudioSource（与 capture config 互斥，实测抛异常）",
            body.none { it.contains(".setAudioSource(") })
        assertTrue("连带 import 也不能留着（留着＝有人还想设源）",
            tap.none { it.contains("android.media.MediaRecorder") })
    }

    /**
     * 采集线程自杀必须让上层知道（10-22 那条谎的另一半）：
     * `lastFailure` 在 finally 里、且在 `onStateEdge` 之前落位，快照才有读数；
     * `start()` 必须清零，否则重绑成功那一刻还挂着上一次的失败。
     */
    @Test
    fun `lastFailureIsSetBeforeStateEdgeAndClearedOnStart`() {
        val tap = file(tapPath)
        val body = functionBody(tap, "private fun loop(")
        val set = body.indexOfFirst { it.contains("lastFailure = exit") }
        val edge = body.indexOfFirst { it.contains("onStateEdge(DeviceAudioPolicy.STATE_SILENT)") }
        val stopped = body.indexOfFirst { it.contains("\"aud_stopped\"") }
        assertTrue("finally 必须记失败原因", set >= 0)
        assertTrue("只许记 record/codec 两类自杀（人为停止不是失败）",
            tap.any { it.contains("exit == Exit.RECORD_ERROR || exit == Exit.CODEC_ERROR") })
        assertTrue("lastFailure 必须在状态沿之前落位，否则快照读到的还是旧值 set@$set edge@$edge",
            set in 0 until edge)
        assertTrue("aud_stopped 必须在记完失败之后发 stopped@$set", set < stopped)
        val start = functionBody(tap, "    fun start(")
        assertTrue("start 必须清零上一次的失败", start.any { it.contains("lastFailure = null") })
        val publisher = functionBody(file(servicePath), "private fun publishAudStatus(")
        assertTrue("快照必须带 captureFailed（不带＝失败又被读成\"还在等\"）",
            publisher.any { it.contains("captureFailed = audTap?.lastFailure != null") })
    }
}
