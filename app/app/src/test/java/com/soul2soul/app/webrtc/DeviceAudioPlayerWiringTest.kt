package com.soul2soul.app.webrtc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观看端内录播放（FR-16）的接线锁。
 *
 * 与 [DeviceAudioTapWiringTest] 同一条理由：解码器与 `AudioTrack` 是设备对象，JVM 里跑不起来，
 * 所以这里锁的是**结构**——停机出口是否都有调用点、`aud_stopped` 是否只有一个发出点、
 * 迟到帧会不会把已停的表重新叫起来、遥测出口会不会被 `callInvalidated` 吞掉。
 * 这一族写歪的表现全都是"不崩、无日志、只是没声音"或"她已经挂了这边还在放"，只有源码扫描拦得住。
 */
class DeviceAudioPlayerWiringTest {

    private val playerPath = "app/src/main/java/com/soul2soul/app/webrtc/DeviceAudioPlayer.kt"
    private val sessionPath = "app/src/main/java/com/soul2soul/app/session/SessionActivity.kt"
    private val servicePath = "app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt"

    private fun file(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    /** 从锚点行起，到第一个 `stop` 锚点之前（不含）；起始锚点必须恰好一次命中 */
    private fun between(lines: List<String>, start: String, stop: String): List<String> {
        val s = lines.indices.filter { lines[it].contains(start) }
        assertTrue("起始锚点应恰好一次，实际 ${s.size}：$start", s.size == 1)
        val e = ((s[0] + 1)..lines.lastIndex).firstOrNull { lines[it].contains(stop) }
            ?: error("找不到收尾锚点：$stop")
        return lines.subList(s[0], e)
    }

    /** 整段函数：按声明缩进找收尾 `}`，不用魔法行数 */
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
     * 三条宿主出口都必须有调用点，线程自撞那一档（CODEC_ERROR）由 finally 的 fallback 兜住。
     * 少一条＝那条路径上播放线程照跑，表现是"会话结束了，她手机还占着媒体音量在放 TA 的视频声"。
     */
    @Test
    fun `playerStopCoversEveryExit`() {
        val session = file(sessionPath)
        val player = file(playerPath)
        listOf(
            "CHANNEL_CLOSED" to session,
            "SESSION_ENDED" to session,
            "ACTIVITY_DESTROY" to session,
        ).forEach { (name, lines) ->
            val uses = lines.filter { it.contains("Exit.$name") && !it.contains("$name(") }
            assertTrue("出口 $name 没有调用点（枚举里声明了却没人用=那条路径不会停表）", uses.isNotEmpty())
        }
        val fallback = player.filter { it.contains("pendingExit ?: threadFailure ?: Exit.CODEC_ERROR") }
        assertTrue("CODEC_ERROR 只能是 finally 里的 fallback（线程自己撞墙时没有别的诚实读数）",
            fallback.isNotEmpty())
    }

    /**
     * `aud_stopped` 只有一个发出点且在 finally：与采集侧同一条纪律。
     * 拆成"宿主发一条 + 线程自杀发另一条"迟早漏掉一条路径，而漏掉的那条没有读数。
     */
    @Test
    fun `playerStoppedEmittedOnceInFinally`() {
        val player = file(playerPath)
        val hits = player.indices.filter { player[it].contains("\"aud_stopped\"") }
        assertTrue("aud_stopped 必须只发一次，实际 ${hits.size} 次", hits.size == 1)
        val finallyIdx = player.indexOfFirst { it.contains("} finally {") }
        assertTrue("播放线程必须有 finally 收尾", finallyIdx >= 0)
        assertTrue("aud_stopped 必须发在 finally 里：${player[hits[0]]}", hits[0] > finallyIdx)
    }

    /** 停机由宿主在关连接之前调用：事件走的是同一条信令通道，close() 之后发的帧会躺在缓冲里被关掉 */
    @Test
    fun `playerStopsBeforeClientClose`() {
        val body = functionBody(file(sessionPath), "override fun onDestroy(")
        val stop = body.indexOfFirst { it.contains("audPlayer.stop(DeviceAudioPlayer.Exit.ACTIVITY_DESTROY)") }
        val close = body.indexOfFirst { it.contains("client?.close()") }
        assertTrue("onDestroy 必须停内录播放", stop >= 0)
        assertTrue("必须先停播放再关 client（stop 会 join 线程，反过来=事件发不出去且 AudioTrack 晚死）stop@$stop close@$close",
            stop in 0 until close)
        val ended = functionBody(file(sessionPath), "private fun finishWithCleanup(")
        val stop2 = ended.indexOfFirst { it.contains("Exit.SESSION_ENDED") }
        // 收尾锚点必须是那一行**调用**本身：注释里也会写 finish()，按 contains 会先命中注释（10-22 实测）
        val finish = ended.indexOfFirst { it.trim() == "finish()" }
        assertTrue("会话结束出口也要停表（onDestroy 只是兜底，不是唯一出口）", stop2 >= 0)
        assertTrue("停表必须排在 finish() 之前：stop@$stop2 finish@$finish", stop2 in 0 until finish)
    }

    /**
     * 收尾之后迟到的帧必须丢掉。`finishWithCleanup` 已经把表停了，而 offer() 是懒启动的——
     * 不加这道门，通道上还在排的最后一批帧会把播放线程重新叫起来。
     */
    @Test
    fun `lateFrameAfterCleanupIsDropped`() {
        val body = functionBody(file(sessionPath), "override fun onDeviceAudioFrame(")
        val guard = body.indexOfFirst { it.contains("callInvalidated") && it.contains("audioChannelOpen") }
        val offer = body.indexOfFirst { it.contains("audPlayer.offer(") }
        assertTrue("帧入口必须先挡收尾后的迟到帧：guard@$guard", guard >= 0)
        assertTrue("收尾/通道关闭门都必须挡在 offer 之前：guard@$guard offer@$offer", guard in 0 until offer)
    }

    /**
     * `stop()` 会 interrupt + join（最多 500ms），而 `onDeviceAudioChannelState` 回调在
     * libwebrtc 的 sigthread 上——在那儿阻塞等于把涂鸦/操控的入站消息一起卡住。
     */
    @Test
    fun `channelClosedStopIsNotExecutedOnSigthread`() {
        val body = functionBody(file(sessionPath), "override fun onDeviceAudioChannelState(")
        val post = body.indexOfFirst { it.contains("mainHandler.post") }
        val stop = body.indexOfFirst { it.contains("audPlayer.stop(") }
        assertTrue("CLOSED 的停表必须挪回主线程", post >= 0)
        assertTrue("stop 调用必须在 post 块里：post@$post stop@$stop", post in 0 until stop)
        assertTrue("只认 CLOSED（CONNECTING 等中间态什么都不做）", body.any { it.contains("\"CLOSED\"") })
    }

    /**
     * 遥测出口不许走 `viewerEvent`：它起手就是 `if (callInvalidated) return`，
     * 而 `SESSION_ENDED`/`ACTIVITY_DESTROY` 这两条出口的事件恰恰是在那之后发的——
     * 用它等于"停机永远没有读数"，而且是一条永远不红的埋点。
     */
    @Test
    fun `telemetrySinkBypassesCallInvalidatedGuard`() {
        val sink = between(file(sessionPath), "private val audPlayer = DeviceAudioPlayer", "mainHandler")
        assertTrue("播放器出口不许复用 viewerEvent（会被 callInvalidated 吞掉）",
            sink.none { it.contains("viewerEvent(") })
        assertTrue("出口必须直接落 SelfCheck.reportEvent",
            sink.any { it.contains("SelfCheck.reportEvent(") })
        assertTrue("role 必须是 viewer（与共享端同名事件靠它分辨是谁停的）",
            sink.any { it.contains("\"viewer\"") })
        assertTrue("后台线程取 Activity 上下文有风险，出口必须用 applicationContext",
            sink.any { it.contains("applicationContext") })
    }

    /**
     * 放音走 USAGE_MEDIA：挂进通话流会与 libwebrtc 自己的语音轨抢同一条流，
     * 而"她的麦克风把外放声收回去"这条代价已经写在注释里（观察项，不许装看不见）。
     */
    @Test
    fun `playbackTrackIsMediaUsage`() {
        val body = functionBody(file(playerPath), "private fun newTrack(")
        assertTrue("放音必须是 USAGE_MEDIA", body.any { it.contains("USAGE_MEDIA") })
        assertTrue("放音不许挂进通话流（抢 libwebrtc 语音轨那条流）",
            body.none { it.contains("USAGE_VOICE_COMMUNICATION") })
        assertTrue("放音延迟档位必须取自 Policy 常量，不许在播放器里另写一个数",
            body.any { it.contains("DeviceAudioPolicy.AUD_PLAYOUT_DELAY_MS") })
    }

    /**
     * 抖动判定只有 [DeviceAudioPolicy.jitter] 一处真值：播放器里不许出现第二套阈值，
     * 否则单测锁的是策略、跑的是另一份，读日志的人会按策略那套解释读数。
     */
    @Test
    fun `jitterDecisionLivesOnlyInPolicy`() {
        val player = file(playerPath)
        val calls = player.filter { it.contains("DeviceAudioPolicy.jitter(") }
        assertTrue("取帧必须走 Policy 的抖动判定", calls.isNotEmpty())
        val offerSide = between(player, "while (queue.size > 1)", "if (!running) start()")
        assertTrue("入队侧的夹上限也走同一个判定（两处各写一套阈值＝只有一处被单测覆盖）",
            offerSide.any { it.contains("DeviceAudioPolicy.jitter(") })
        assertTrue("丢弃只扔最旧一帧（pollFirst），不许清整队",
            player.filter { it.contains("queue.pollFirst()") }.isNotEmpty() &&
                player.none { it.contains("queue.clear()") && !it.contains("synchronized(queue) { queue.clear() }") })
    }

    /** 帧的唯一校验口是 AudioFrameCodec.unwrap：线格式不对就不入队，不许"猜个偏移凑一帧" */
    @Test
    fun `offerValidatesOnlyViaUnwrap`() {
        val body = functionBody(file(playerPath), "fun offer(packet: ByteArray)")
        assertTrue("入队前必须过 unwrap", body.any { it.contains("AudioFrameCodec.unwrap(packet)") })
        assertTrue("unwrap 失败即丢弃并计数，不许重试（不可靠通道上补发上一帧＝重复半句）",
            body.any { it.contains("droppedBadFormat++") } && body.none { it.contains("Thread.sleep") })
    }

    /**
     * 一个观看端会话只允许有一个播放器实例，且它是 `val` 字段：`offer()` 在 WebRTC 的 binder 线程上，
     * 懒建那一下的竞态会造出两个线程、两条 AudioTrack 抢同一个媒体流（现象是声音忽大忽小/一半时间没声）。
     * 停表的调用点也穷举在这三条（通道 CLOSED / 会话结束 / 页面销毁），第四条就是策略自己撞墙的 fallback。
     */
    @Test
    fun `viewerPlayerIsASingleValField`() {
        val session = file(sessionPath)
        assertTrue("播放器必须是 val 字段，不许懒建（binder 线程竞态=两条 AudioTrack）",
            session.any { it.contains("private val audPlayer = DeviceAudioPlayer") })
        assertTrue("构造点只许一处",
            session.count { it.contains("private val audPlayer = DeviceAudioPlayer(") } == 1)
        val stops = session.filter { it.contains("audPlayer.stop(") }
        assertTrue(
            "停表只许出自对端关音/通道 CLOSED/会话结束/页面销毁四处，实际 ${stops.size} 处：$stops",
            stops.size == 4,
        )
    }

    @Test
    fun `decoderFailuresAreNotCollapsedIntoTryAgain`() {
        val player = file(playerPath)
        val feed = functionBody(player, "private fun feedInput(")
        val drain = functionBody(player, "private fun drainOutputs(")
        assertTrue("输入 dequeue 必须把异常和未知负值交给失败分类器",
            feed.any { it.contains("inputDequeueResult(idx") } && feed.any { it.contains("InputResult.FAILURE") })
        assertTrue("输出 dequeue 必须把异常和未知负值交给失败分类器",
            drain.any { it.contains("outputDequeueResult(") } &&
                drain.any { it.contains("Dequeue.FAILURE -> return false") })
        assertTrue("格式/缓冲通知只能作为明确事件继续 drain",
            drain.any { it.contains("Dequeue.OUTPUT_EVENT -> continue") })
    }

    @Test
    fun `audioTrackWriteErrorsAndPartialWritesAreHandled`() {
        val player = file(playerPath)
        val write = functionBody(player, "private fun writeFully(")
        assertTrue("播放器必须判写入进度，负数和零字节不可被当作成功",
            write.any { it.contains("writeMadeProgress(result, requested)") })
        assertTrue("部分写入必须继续处理剩余缓冲区",
            write.any { it.contains("while (buffer.hasRemaining())") })
        val request = write.indexOfFirst { it.contains("val requested = buffer.remaining()") }
        val call = write.indexOfFirst { it.contains("track.write(buffer, requested") }
        assertTrue("校验基准必须在 write 前快照，否则完整写后 remaining=0 会误报失败",
            request >= 0 && request < call && write.any { it.contains("writeMadeProgress(result, requested)") })
        assertTrue("write API 的缓冲区 position 自动前进语义必须写明，不能手动二次推进",
            write.any { it.contains("会把 position 前进 result 字节") } &&
                write.none { it.contains("buffer.position(buffer.position() + result)") })
        assertTrue("AudioTrack 错误必须返回失败给 finally 收尾",
            write.any { it.contains("return false") })
        assertTrue("写错误要保留为独立受保护的诊断事件",
            write.any { it.contains("audEvent(\"aud_playback_error\"") })
        val pump = functionBody(player, "private fun pump(")
        assertTrue("write 错误出口不能被误记成普通 codec_error",
            pump.any { it.contains("pendingExit ?: threadFailure ?: Exit.CODEC_ERROR") } &&
                write.any { it.contains("threadFailure = Exit.PLAYBACK_ERROR") })
    }

    @Test
    fun `playbackStateReportsOnlyAfterPcmAndChannelClosureBlocksLateFrames`() {
        val player = file(playerPath)
        val drain = functionBody(player, "private fun drainOutputs(")
        val pump = functionBody(player, "private fun pump(")
        assertTrue("只有 PCM 成功写入后才报告播放开始", drain.any { it.contains("onPcmWritten()") })
        assertTrue("连续创建 decoder/AudioTrack 失败时必须限速重试", pump.any { it.contains("retryAfterMs = SystemClock.elapsedRealtime() +") })
        val session = file(sessionPath)
        val frame = functionBody(session, "override fun onDeviceAudioFrame(")
        val gate = frame.indexOfFirst { it.contains("!audioChannelOpen") }
        val offer = frame.indexOfFirst { it.contains("audPlayer.offer(") }
        assertTrue("会话结束、通道 CLOSED 或共享关闭后迟到帧必须在 offer 前被挡住",
            gate >= 0 && gate < offer && frame[gate].contains("peerAudioEnabled"))
        val channel = functionBody(session, "override fun onDeviceAudioChannelState(")
        val stateEdge = channel.indexOfFirst { it.contains("audioChannelOpen =") }
        val post = channel.indexOfFirst { it.contains("mainHandler.post") }
        val currentStateGuard = channel.indexOfFirst { it.contains("if (!audioChannelOpen)") }
        assertTrue("通道状态必须先更新门再异步 stop", stateEdge >= 0 && post > stateEdge)
        assertTrue("排队中的旧 CLOSED 沿必须核对最新状态，不能停掉已重开的播放器",
            currentStateGuard > post)
        val onLive = functionBody(session, "override fun onLive(")
        assertTrue("LIVE 沿不能清掉可能已提前到达的 aud 状态/首帧门控",
            onLive.none {
                it.contains("peerAudioEnabled = false") ||
                    it.contains("peerAudOn = null") ||
                    it.contains("peerAudState = null")
            })
    }

    @Test
    fun `playbackFailureFeedbackIsRoutedWithoutChangingCaptureTruth`() {
        val session = file(sessionPath)
        val callback = functionBody(session, "private fun onPlaybackState(")
        assertTrue("失败与恢复沿必须经过统一能力门控发布",
            callback.any { it.contains("publishPlaybackStateIfSupported()") })
        val publisher = functionBody(session, "private fun publishPlaybackStateIfSupported(")
        assertTrue("只向明确声明能力且仍 OPEN 的新对端发 audplay",
            publisher.any { it.contains("peerPlaybackCapable") } &&
                publisher.any { it.contains("deviceAudioChannelOpen()") } &&
                publisher.any { it.contains("DeviceAudioHandshake.playbackJson(playbackFailed)") })
        val state = functionBody(session, "override fun onDataMessage(")
        assertTrue("k=aud 状态到达时要记录反馈能力", state.any { it.contains("peerPlaybackCapable = action.playbackSupported") })
        assertTrue("通道先后顺序不确定时 OPEN 沿要重发最新状态",
            functionBody(session, "override fun onDeviceAudioChannelState(")
                .any { it.contains("publishPlaybackStateIfSupported()") })
        val service = file(servicePath)
        val inbound = functionBody(service, "override fun onDataMessage(")
        assertTrue("共享端必须显式消费 audplay，不能落到涂鸦未知消息 hook",
            inbound.any { it.contains("\"audplay\" ->") })
        assertTrue("共享端对端播放故障只更新展示状态，不得改本端采集状态",
            inbound.any { it.contains("peerPlaybackFailed = failed") } &&
                inbound.none { it.contains("audStatusForUi =") || it.contains("audWanted =") })
        assertTrue("故障/恢复必须触发主页状态行及通知刷新",
            inbound.any { it.contains("local.deviceAudioState") } &&
                inbound.any { it.contains("updateNotification(statusText())") })
        val status = functionBody(service, "private fun statusText(")
        assertTrue("通知正文要提示对端的播放故障", status.any { it.contains("aud_remote_playback_failed") })
    }
}
