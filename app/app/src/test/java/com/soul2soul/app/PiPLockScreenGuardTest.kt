package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.56 F/G 采集盲点锁定。
 * F（被控端几秒即断）此前在服务器日志里是一个"没有原因的 bye"：两处启动失败的 catch
 * 只发 bye，其中一处还被 noteEnd 的 first-wins 记成 local_stop（谎报用户主动停止）。
 * G（观看端解锁后黑屏）的头号嫌疑是"锁屏触发 onUserLeaveHint → 自动进小窗 → surface
 * 销毁重建、无人重挂"，而这条路径整条没有埋点，且小窗本身在锁屏时毫无意义。
 * 回滚任一条，用例必须变红。
 */
class PiPLockScreenGuardTest {

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
    private fun presence() = source("app/src/main/java/com/soul2soul/app/session/PresenceService.kt")

    private fun window(lines: List<String>, anchor: String, size: Int): List<String> {
        val i = lines.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return lines.drop(i).take(size)
    }

    // L1：锁屏/息屏引起的 onUserLeaveHint 不许进小窗（进小窗会拆 surface，且没人重挂）
    @Test
    fun `leave hint does not enter PiP while screen is off`() {
        val body = window(session(), "override fun onUserLeaveHint()", 26)
        val guard = body.indexOfFirst { it.contains("isInteractive") }
        val enter = body.indexOfFirst { it.contains("enterPictureInPictureMode(") }
        assertTrue("onUserLeaveHint 必须先判屏幕是否亮着（isInteractive）：${body.joinToString("\n")}",
            guard >= 0)
        assertTrue("isInteractive 判断必须排在进小窗之前",
            enter >= 0 && guard < enter)
    }

    // L2：小窗进/出两个方向都得有事件，否则"是否走过这条路"永远只能猜
    @Test
    fun `both pip transitions are reported`() {
        val src = session()
        assertTrue("进小窗必须报 pip_enter", src.any { it.contains("\"pip_enter\"") })
        assertTrue("出小窗必须报 pip_exit", src.any { it.contains("\"pip_exit\"") })
        val changed = window(src, "override fun onPictureInPictureModeChanged(", 10)
        assertTrue("两条事件必须都在 onPictureInPictureModeChanged 里发（单一收口）",
            changed.any { it.contains("pip_enter") } && changed.any { it.contains("pip_exit") })
    }

    // L3：两处启动失败各配一条自报，且真因必须抢在 local_stop 之前进 first-wins 槽
    @Test
    fun `startup failures name themselves instead of silently bye-ing`() {
        val fgs = window(share(), "start foreground failed", 12)
        assertTrue("FGS 起不来必须自报 fgs_start_failed（Android12+ 后台起服务被拒是 F 的头号嫌疑）",
            fgs.any { it.contains("\"fgs_start_failed\"") })
        assertTrue("异常详情只能进类名，不得把堆栈/业务数据塞进上报",
            fgs.any { it.contains("e.javaClass.simpleName") })
    }

    @Test
    fun `webrtc startup failure is not mislabeled as user stop`() {
        val webrtcCatch = window(share(), "start WebRTC failed", 12)
        assertTrue("起流失败必须自报 webrtc_start_failed",
            webrtcCatch.any { it.contains("\"webrtc_start_failed\"") })
        assertTrue("必须抢在 stopSession 的 local_stop 前 noteEnd，否则服务器收到一句谎报",
            webrtcCatch.any { it.contains("noteEnd(\"webrtc_start_failed\")") })
    }

    // L4：信令断/续要能区分"媒体还活着只是信令掉了"，且无会话时不产噪声
    @Test
    fun `signaling drop and recovery are reported only during a session`() {
        val src = presence()
        assertTrue("断线必须报 signaling_down", src.any { it.contains("\"signaling_down\"") })
        assertTrue("重连必须报 signaling_up", src.any { it.contains("\"signaling_up\"") })
        val onState = window(src, "override fun onState(connected: Boolean)", 24)
        assertTrue("上报必须以 Presence.sessionBusy 为门（待机期每次重连都报就是噪声）",
            onState.any { it.contains("Presence.sessionBusy") })
    }
}
