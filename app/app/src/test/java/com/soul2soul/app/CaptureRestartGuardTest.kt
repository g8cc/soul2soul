package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「连上几秒就自动断开」的源码锁（v0.2.62）。
 *
 * 双模拟器实测出的死法：带宽估计器爬坡期的 300kbps 初值被当成实测 → 3 连拍触发降档 →
 * `applyCaptureLongEdge` 在同一个 `ScreenCapturerAndroid` 上 stop+start →
 * Android 14+ 判为「第二次开采集」抛 `SecurityException: Don't re-use the resultData` →
 * `MediaProjection.onStop` → `projection_stopped` → 会话 4.5s 死。
 * 米15（Android 15）作共享端那两通 14.1s/16.1s 就是这个形状；Mi10（Android 12）容忍 token
 * 复用所以能撑 155s——设备差异也因此一直被读成「她那台手机事多」。
 *
 * 这里锁的是「这条链子每一环都不许再自己接回去」。回滚任何一条，用例必须变红。
 */
class CaptureRestartGuardTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun webrtc() = source("app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt")
    private fun policy() = source("app/src/main/java/com/soul2soul/app/webrtc/VideoQualityPolicy.kt")

    private fun indexOf(lines: List<String>, anchor: String): Int {
        val i = lines.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return i
    }

    /** 锚点起往后取 size 行 */
    private fun window(lines: List<String>, anchor: String, size: Int): List<String> =
        lines.drop(indexOf(lines, anchor)).take(size)

    /** 两个锚点之间的片段（终点锚点必须唯一且真在起点之后） */
    private fun between(lines: List<String>, from: String, to: String): List<String> {
        val a = indexOf(lines, from)
        val b = lines.indexOfFirst { it.contains(to) }
        assertTrue("找不到终点锚点 $to（需在 $from 之后）", b > a)
        return lines.subList(a, b)
    }

    /** 只留代码行：注释里提到某个符号正是为了解释「为什么不能用它」，不该被判成违规 */
    private fun code(lines: List<String>): List<String> = lines.filterNot {
        val t = it.trim()
        t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
    }

    private fun functionBody(lines: List<String>, anchor: String): List<String> {
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertEquals("锚点应恰好出现一次：$anchor", 1, hits.size)
        val anchorIndex = hits.single()
        val functionHeader = (0..anchorIndex).reversed().firstOrNull { lineIndex ->
            lines[lineIndex].trimStart().let { it.startsWith("fun ") || it.contains(" fun ") }
        } ?: error("锚点不在函数内：$anchor")
        val indentation = lines[functionHeader].takeWhile { it == ' ' }.length
        val closing = "}".padStart(indentation + 1)
        val end = ((functionHeader + 1)..lines.lastIndex).firstOrNull { lineIndex ->
            lines[lineIndex] == closing
        } ?: error("函数找不到收尾：$anchor")
        return lines.subList(functionHeader, end)
    }

    // C1：Android 14+ 不能重建投影，旧机型只改同一 VirtualDisplay 的格式
    @Test
    fun capture_restart_is_gated_by_the_android_14_token_rule() {
        val body = between(webrtc(), "private fun applyCaptureLongEdge(", "private fun captureSize(")
        val formatChange = body.indexOfFirst { it.contains("capturer.changeCaptureFormat(w, h, targetVideoFps)") }
        val gate = body.indexOfFirst { it.contains("if (!captureFormatChangeSupported)") }
        assertTrue("旧机型必须保留同一投影的格式切换路径", formatChange >= 0)
        assertTrue("必须有 Android 14+ 的输出适配分支：不能再重建投影", gate >= 0)
        assertTrue("平台门槛必须在旧机型格式切换之前 return", gate in 0 until formatChange)
        assertTrue("14+ 分支必须改输出尺寸与质量档，而不是触碰 MediaProjection",
            body.subList(gate, formatChange).any { it.contains("adaptOutputFormat(") } &&
                body.subList(gate, formatChange).any { it.contains("qualityLongEdge = normalized") })
        assertTrue("门槛只能由 SDK_INT 决定，不许是可被远端消息左右的开关",
            webrtc().any { it.contains("android.os.Build.VERSION.SDK_INT < ANDROID_14") })
    }

    // C2：画质档与采集尺寸是两个字段，码率跟前者、阶梯也跟前者
    @Test
    fun quality_edge_is_a_separate_field_from_capture_size() {
        val lines = webrtc()
        val decl = indexOf(lines, "private var qualityLongEdge = VideoQualityPolicy.STARTUP_EDGE")
        assertTrue("画质档必须 @Volatile（rttHandler 线程与调用线程都会写）",
            lines[decl - 1].contains("@Volatile"))
        val bitrate = window(lines, "private fun applyBitrate(sender: org.webrtc.RtpSender)", 20)
        assertTrue("码率上限跟画质档走；跟采集尺寸走的话，14+ 降档等于什么都没做",
            bitrate.any { it.contains("transportBitrateBps(qualityLongEdge)") })
        val ladder = between(lines, "val decision = VideoQualityPolicy.step(", "highRttStreak = decision.highStreak")
        assertTrue("自适应阶梯作用在画质档上；作用在采集尺寸上的话 14+ 永远停在同一档、反复决策",
            ladder.any { it.contains("qualityLongEdge,") })
    }

    // C3：爬坡期的带宽既不许判坏，也不许被当成回升条件
    @Test
    fun unsettled_bandwidth_estimate_cannot_move_quality() {
        val lines = policy()
        assertTrue("Metrics 必须有「带宽估计是否已稳」这一维",
            lines.any { it.contains("val bandwidthSettled: Boolean = true,") })
        val isBad = between(lines, "private fun isBad(", "private fun isGood(")
        assertTrue("isBad 必须先让爬坡期整体不判坏，再比阈值（写在阈值之后就是已经误判了）",
            indexOf(isBad, "if (!metrics.bandwidthSettled) return false") <
                indexOf(isBad, "return available > 0 && available <"))
        assertTrue("爬坡期读到 300kbps 不许走「available<=0 当作未知」那条放行成回升条件",
            lines.any { it.contains("if (!metrics.bandwidthSettled) return available <= 0") })
        assertTrue("调用点必须真的把拍数换算成这个标记；不传就等于恒 true，标记白加",
            webrtc().any { it.contains("bandwidthSettled = statsPollCount > BANDWIDTH_SETTLE_POLLS") })
    }

    // C4：帧率是区间相减的真读数，「没读数」不参与卡死判定
    @Test
    fun fps_is_interval_derived_and_unknown_fps_is_not_a_stall() {
        val lines = webrtc()
        val poll = window(lines, "outboundVideoFps = RtpQualityWindow.framesFps(", 8)
        assertTrue("两端帧率都必须由累计帧计数按区间算出",
            lines.any { it.contains("outboundVideoFps = RtpQualityWindow.framesFps(") } &&
                lines.any { it.contains("inboundVideoFps = RtpQualityWindow.framesFps(") })
        assertTrue("算完必须把本拍计数存成下一拍的基准，否则永远只有第一拍能算",
            poll.any { it.contains("previousOutboundVideoFrames = outboundVideoFrames") } &&
                poll.any { it.contains("previousInboundVideoFrames = inboundVideoFrames") })
        assertTrue("不许再直接读 framesPerSecond（这一版 libwebrtc 根本没有该字段，读出来是 -1，" +
            "而 -1 曾被判成 0 帧，产出 6 条假 video_stall）",
            code(lines).none { it.contains("stats.members[\"framesPerSecond\"]") })
        val gate = window(lines, "if (diagDue && !isSharer && established && !remoteScreenPaused)", 14)
        assertTrue("零帧判定必须用 == 0.0", gate.any { it.contains("inboundVideoFps == 0.0") })
        assertTrue("必须有「没读数：不判也不清零」的分支", gate.any { it.contains("inboundVideoFps < 0.0") })
    }

    // C5：API 34+ 不重建 MediaProjection，但必须真正改变 VideoSource 的编码输出尺寸
    @Test
    fun api14_quality_switch_adapts_video_source_without_restarting_projection() {
        val lines = webrtc()
        assertTrue("共享端初始采集必须以高清档启动，不能先把高分辨率屏幕压成 1600",
            lines.any { it.contains("var captureLongEdge = VideoQualityPolicy.STARTUP_EDGE") })
        val body = between(lines, "private fun applyCaptureLongEdge(", "private fun captureSize(")
        val formatChange = body.indexOfFirst { it.contains("capturer.changeCaptureFormat(w, h, targetVideoFps)") }
        val gate = body.indexOfFirst { it.contains("if (!captureFormatChangeSupported)") }
        assertTrue("API 14+ 分支必须先于旧机型格式切换路径", gate in 0 until formatChange)
        val branch = body.subList(gate, formatChange)
        assertTrue("API 14+ 画质切换必须调用 VideoSource.adaptOutputFormat，否则只改码率仍会糊",
            branch.any { it.contains("adaptOutputFormat(") })
        assertTrue("API 14+ 动态切档不得 stopCapture/startCapture",
            code(branch).none { it.contains("stopCapture(") || it.contains("startCapture(") })
    }

    @Test
    fun api14_failed_output_adaptation_keeps_the_previous_quality_state() {
        val lines = webrtc()
        val body = between(lines, "private fun applyCaptureLongEdge(", "private fun captureSize(")
        val formatChange = body.indexOfFirst { it.contains("capturer.changeCaptureFormat(w, h, targetVideoFps)") }
        val gate = body.indexOfFirst { it.contains("if (!captureFormatChangeSupported)") }
        val branch = code(body.subList(gate, formatChange))
        assertTrue(
            "API 14+ 失败时不能无条件写入新档位，否则码率与真实输出尺寸会失配",
            branch.count { it.contains("qualityLongEdge = normalized") } == 1 &&
                branch.any { it.contains("if (adapted)") } &&
                between(branch, "if (adapted) {", "} else {")
                    .any { it.contains("qualityLongEdge = normalized") },
        )
    }

    @Test
    fun encoder_limitation_reason_reaches_quality_policy() {
        val lines = webrtc()
        val stats = between(lines, "var encoderLimitation = \"unknown\"", "val audioWindow =")
        assertTrue(
            "编码器限制原因必须从 outbound-rtp stats 读取，静止画面低帧率才能与带宽/CPU瓶颈区分",
            stats.any { it.contains("qualityLimitationReason") },
        )
        assertTrue(
            "读取到的限制原因必须传入 VideoQualityPolicy.Metrics",
            lines.any { it.contains("encoderLimitation = encoderLimitation") },
        )
    }

    @Test
    fun outbound_quality_stats_must_not_be_overwritten_by_rtx_streams() {
        val lines = webrtc()
        val stats = between(lines, "val primaryOutboundVideoStats =", "val audioWindow =")
        assertTrue(
            "必须先锁定带 framesSent 的主 outbound 视频统计，不能让 RTX 覆盖帧率/限制原因",
            stats.any { it.contains("framesSent") } &&
                stats.any { it.contains("primaryOutboundVideoStats") },
        )
        assertTrue(
            "编码器限制原因必须从选中的主视频统计读取",
            stats.any { it.contains("qualityLimitationReason") } &&
                stats.any { it.contains("primaryOutboundVideoStats") },
        )
        assertTrue(
            "ENC 诊断也必须只打印主视频统计，避免 RTX 行污染现场判断",
            stats.any { it.contains("stats.id == primaryOutboundVideoStats?.id") },
        )
    }

    @Test
    fun failed_legacy_resolution_switch_cannot_leave_a_fake_live_projection() {
        val lines = webrtc()
        val body = between(lines, "private fun applyCaptureLongEdge(", "private fun captureSize(")
        val branch = code(body)
        assertTrue(
            "旧机型切档必须调用同一采集器的格式切换，不能 stop/start 触发投影回调竞态",
            branch.any { it.contains("capturer.changeCaptureFormat(w, h, targetVideoFps)") },
        )
        assertTrue(
            "格式切换失败必须保留旧尺寸并走一次回滚",
            branch.any { it.contains("restoring ${'$'}{oldWidth}x${'$'}{oldHeight}") } &&
                branch.any { it.contains("capturer.changeCaptureFormat(oldWidth, oldHeight, targetVideoFps)") },
        )
        assertTrue(
            "旧机型切档不许 stop/start，避免 onStop 把活跃投影误报 stopped",
            branch.none { it.contains("stopCapture(") || it.contains("startCapture(") },
        )
    }

    @Test
    fun empty_selected_candidate_pair_id_uses_the_nominated_pair_fallback() {
        val lines = webrtc()
        val poll = between(lines, "var selectedPairId: String?", "val audioWindow =")
        assertTrue(
            "空的 selectedCandidatePairId 等价于缺失，必须允许 nominated 回退",
            poll.any { it.contains("takeIf { it.isNotBlank() }") },
        )
        assertTrue(
            "没有有效 selected id 时仍必须选择 nominated candidate pair",
            poll.any { it.contains("usableSelectedPairId == null && stats.members[\"nominated\"] == true") },
        )
    }

    @Test
    fun stale_selected_candidate_pair_id_also_uses_the_nominated_pair_fallback() {
        val lines = webrtc()
        val poll = between(lines, "var selectedPairId: String?", "val audioWindow =")
        assertTrue(
            "transport 指向不存在或未 succeeded 的 pair 时，必须先归一成不可用 id",
            poll.any { it.contains("usableSelectedPairId") },
        )
        assertTrue(
            "候选对选择必须使用归一后的 id，不能让 stale id 阻断 nominated 回退",
            poll.any { it.contains("id == usableSelectedPairId") },
        )
    }

    @Test
    fun dynamic_fps_switch_has_both_platform_paths_without_projection_restart() {
        val body = between(webrtc(), "private fun applyFrameRate(", "private fun captureSize(")
        assertTrue("Android 14+ 动态帧率必须只适配 VideoSource 输出",
            body.any { it.contains("source.adaptOutputFormat(outputWidth, outputHeight, normalized)") })
        assertTrue("旧机型动态帧率必须复用同一采集器",
            body.any { it.contains("capturer.changeCaptureFormat(captureWidth, captureHeight, normalized)") })
        assertTrue("动态帧率切换不许重启投影",
            code(body).none { it.contains("stopCapture(") || it.contains("startCapture(") })
    }

    @Test
    fun outbound_bytes_are_interval_sampled_and_reach_bitrate_and_activity_policies() {
        val lines = webrtc()
        val poll = between(lines, "var outboundVideoBytes = -1L", "val audioWindow =")
        assertTrue("真实 bytesSent 必须来自主 outbound 视频统计",
            poll.any { it.contains("stats.members[\"bytesSent\"]") })
        assertTrue("bytesSent 必须按真实时间窗口换算码率",
            poll.any { it.contains("RtpQualityWindow.bitrateBps(") } &&
                poll.any { it.contains("previousOutboundVideoBytes") })
        assertTrue("静态/动态策略必须使用实际上行码率",
            lines.any { it.contains("actualBitrateBps = outboundVideoBitrateBps") } &&
                lines.any { it.contains("actualBps = outboundVideoBitrateBps") })
    }

    @Test
    fun remote_control_does_not_fake_fps_state_before_restoring_capture() {
        val body = between(webrtc(), "fun setControlMode(on: Boolean)", "private fun applyCaptureLongEdge(")
        assertTrue("远控切换前不能把 targetVideoFps 直接写成 30，否则恢复调用会被短路",
            code(body).none { it.contains("targetVideoFps = VideoActivityPolicy.ACTIVE_FPS") })
        assertTrue("远控切换后必须通过统一入口恢复 30 FPS",
            body.any { it.contains("applyFrameRate(VideoActivityPolicy.ACTIVE_FPS)") })
    }

    @Test
    fun `codec 探测失败不能阻断会话构造且配置切换要清空旧的质量连拍`() {
        val lines = webrtc()
        assertTrue(
            "额外硬件 codec 探测必须在 lazy/runCatching 内，不能在字段初始化阶段抛出",
            lines.any { it.contains("private val hardwareVideoCodecNames: List<String> by lazy") } &&
                lines.any { it.contains("HardwareVideoEncoderFactory(eglContext, true, true)") } &&
                lines.any { it.contains("hardware video codec capability probe failed") },
        )
        val quality = functionBody(lines, "fun setCaptureLongEdge(edge: Int)")
        assertTrue("用户切换画质后必须清除旧的降档/回升连拍",
            quality.any { it.contains("resetQualityAdaptation()") })
        val control = functionBody(lines, "fun setControlMode(on: Boolean)")
        assertTrue("远控切换后必须从新的网络样本重新累计质量连拍",
            control.any { it.contains("resetQualityAdaptation()") })
        val polling = functionBody(lines, "private fun startRttPolling()")
        assertTrue("重启质量统计时必须清除上一次连拍状态",
            polling.any { it.contains("resetQualityAdaptation()") })
    }
}
