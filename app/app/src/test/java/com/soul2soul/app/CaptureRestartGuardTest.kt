package com.soul2soul.app

import java.io.File
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

    // C1：重启采集必须被平台门槛挡住，而且门槛在它之前就 return
    @Test
    fun capture_restart_is_gated_by_the_android_14_token_rule() {
        val body = between(webrtc(), "private fun applyCaptureLongEdge(", "private fun captureSize(")
        val restart = body.indexOfFirst { it.contains("capturer.stopCapture()") }
        val gate = body.indexOfFirst { it.contains("if (!captureRestartSupported)") }
        assertTrue("必须保留 stopCapture 这条路径（Android 13- 还要能真切分辨率）", restart >= 0)
        assertTrue("必须有「14+ 不重启采集」的分支：少了它就是重现 token 被撤销→会话几秒死", gate >= 0)
        assertTrue("门槛必须在 stopCapture 之前 return，否则 14+ 照样走到致命那一步", gate in 0 until restart)
        assertTrue("14+ 分支改的是画质档不是采集尺寸（尺寸一改就得重启采集，等于没挡）",
            body.subList(gate, restart).any { it.contains("qualityLongEdge = normalized") })
        assertTrue("门槛只能由 SDK_INT 决定，不许是可被远端消息左右的开关",
            webrtc().any { it.contains("android.os.Build.VERSION.SDK_INT < ANDROID_14") })
    }

    // C2：画质档与采集尺寸是两个字段，码率跟前者、阶梯也跟前者
    @Test
    fun quality_edge_is_a_separate_field_from_capture_size() {
        val lines = webrtc()
        val decl = indexOf(lines, "private var qualityLongEdge = VideoQualityPolicy.DEFAULT_EDGE")
        assertTrue("画质档必须 @Volatile（rttHandler 线程与调用线程都会写）",
            lines[decl - 1].contains("@Volatile"))
        val bitrate = window(lines, "private fun applyBitrate(sender: org.webrtc.RtpSender)", 20)
        assertTrue("码率上限跟画质档走；跟采集尺寸走的话，14+ 降档等于什么都没做",
            bitrate.any { it.contains("requiredBitrateBps(qualityLongEdge)") })
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
        val isGood = window(lines, "private fun isGood(", 10)
        assertTrue("爬坡期读到 300kbps 不许走「available<=0 当作未知」那条放行成回升条件",
            isGood.any { it.contains("if (!metrics.bandwidthSettled) return available <= 0") })
        val call = between(webrtc(), "val decision = VideoQualityPolicy.step(", "highRttStreak = decision.highStreak")
        assertTrue("调用点必须真的把拍数换算成这个标记；不传就等于恒 true，标记白加",
            call.any { it.contains("bandwidthSettled = statsPollCount > BANDWIDTH_SETTLE_POLLS") })
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
}
