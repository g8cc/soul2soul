package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.56 锁屏保活：被控端锁屏**不再结束会话**（推翻 PRD FR-8 的"15 秒自动结束"）。
 * 场景是两人边语音边各玩各的，手机不动就会锁屏，会话必须存续——画面冻在最后一帧、语音继续。
 * 回滚任一条（把看门狗加回来 / 删掉 screenoff 通知 / 横幅文案还写着自动结束 /
 * 锁屏零帧又被当成卡死告警），用例必须变红。
 */
class LockScreenKeepAliveTest {

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
    private fun webrtc() = source("app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt")
    private fun session() = source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")
    private fun strings() = source("app/src/main/res/values/strings.xml")

    private fun window(lines: List<String>, anchor: String, size: Int): List<String> {
        val i = lines.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return lines.drop(i).take(size)
    }

    // L1：旧的锁屏看门狗必须连根拔掉——留着常量就是留给下一次"顺手加回 postDelayed"的诱惑
    @Test
    fun `lock screen watchdog is gone`() {
        val src = share()
        assertTrue("screenOffStop 必须删除，锁屏不再自动结束会话",
            src.none { it.contains("screenOffStop") })
        assertTrue("SCREEN_OFF_TIMEOUT_MS 必须删除",
            src.none { it.contains("SCREEN_OFF_TIMEOUT_MS") })
        assertTrue("screen_off_timeout 这个终止原因不得再出现在共享端",
            src.none { it.contains("screen_off_timeout") })
    }

    // L2：暂停/恢复对称（AGENTS 恢复对称性）——两条通道各自都还在
    @Test
    fun `pause and resume notifications stay symmetric`() {
        val off = window(share(), "ACTION_SCREEN_OFF -> if (live)", 12)
        assertTrue("锁屏仍须立即通知观看端（画面暂停），否则对方以为卡死",
            off.any { it.contains("sendScreenStatus(\"screenoff\")") })
        assertTrue("锁屏仍须收回操控授权（隐私模型不变：清醒时给的授权不跨锁屏）",
            off.any { it.contains("revokeOnScreenOff()") })
        val on = window(share(), "ACTION_SCREEN_ON -> {", 7)
        assertTrue("解锁必须补发 screenon（ anno+ctl 双通道），否则对方横幅永久卡住",
            on.any { it.contains("sendScreenStatus(\"screenon\")") })
    }

    // L3：横幅文案不能继续谎称会自动结束
    @Test
    fun `banner tells the truth`() {
        val line = strings().first { it.contains("name=\"peer_screen_off\"") }
        assertTrue("锁屏已不再自动结束会话，文案不得再写「自动结束」：$line",
            !line.contains("自动结束"))
        assertTrue("文案要给出路（语音还在），让用户知道不用重拨：$line",
            line.contains("语音"))
    }

    // L4：被控端锁屏期间零帧是设计行为，观看端不得把它报成"画面卡死"
    @Test
    fun `video_stall detection is gated by remote screen pause`() {
        assertTrue("video_stall 判定必须被 remoteScreenPaused 门控",
            webrtc().any { it.contains("established && !remoteScreenPaused") })
        assertTrue("暂停位必须有唯一写入口（恢复时顺带清 streak，否则解锁瞬间沿用旧计数误报）",
            window(webrtc(), "fun setRemoteScreenPaused(", 6).any { it.contains("viewerZeroFpsStreak = 0") })
        assertTrue("screenoff 到达时必须置位",
            window(session(), "SignalRouter.DataAction.PeerScreenOff -> {", 8)
                .any { it.contains("setRemoteScreenPaused(true)") })
        assertTrue("screenon（HideBanner）到达时必须复位——与横幅收起同一条路径",
            window(session(), "SignalRouter.DataAction.HideBanner -> {", 8)
                .any { it.contains("setRemoteScreenPaused(false)") })
    }
}
