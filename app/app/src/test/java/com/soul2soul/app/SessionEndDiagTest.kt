package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断连可解释性锁定（v0.2.55）：服务器只看得到信令帧，WebRTC 媒体与界面生命周期在它眼里是黑的。
 * "米15 发起后 4~6 秒即断""观看端解锁后黑屏"这两类问题，此前只能靠猜，根因就是本端没有自报终止原因。
 * 回滚下面任何一条，服务器日志会重新退化成"没有原因的 local_close"，用例必须变红。
 */
class SessionEndDiagTest {

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
    private fun share() = source("app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt")
    private fun session() = source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")
    private fun selfCheck() = source("app/src/main/java/com/soul2soul/app/util/SelfCheck.kt")

    private fun window(lines: List<String>, anchor: String, size: Int, back: Int = 0): List<String> {
        val i = lines.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return lines.drop(i - back).take(size)
    }

    // D1：close() 必须把终态报出去——所有本地/远端结束路径都汇流到这里
    @Test
    fun `close reports session end to server`() {
        val body = window(webrtc(), "fun close()", 6)
        assertTrue("close() 必须调用 reportSessionEnd()，否则断连原因永远只留在 logcat",
            body.any { it.contains("reportSessionEnd()") })
        assertTrue("reportSessionEnd 必须走 SelfCheck.reportSession",
            window(webrtc(), "private fun reportSessionEnd()", 20)
                .any { it.contains("SelfCheck.reportSession(") })
    }

    // D2：真·终止原因必须先记下来，first-wins 才不会被收尾 close 的 local_close 覆盖
    @Test
    fun `notifyEnded records the reason before handing off`() {
        val body = window(webrtc(), "private fun notifyEnded(", 8)
        assertTrue("notifyEnded 必须 noteEnd(reason)，否则 close 只能报 local_close",
            body.any { it.contains("noteEnd(reason)") })
    }

    // D3：锁屏只走 DataChannel（服务器看不见），且 v0.2.56 起不再结束会话——必须自报事件、不得自杀
    @Test
    fun `sharer lock screen is reported without ending the session`() {
        val branch = window(share(), "ACTION_SCREEN_OFF -> if (live)", 12)
        assertTrue("锁屏必须上报 screen_off 事件",
            branch.any { it.contains("\"screen_off\"") })
        assertTrue("锁屏不得结束会话（语音要继续）：SCREEN_OFF 分支里不能出现 stopSession",
            branch.none { it.contains("stopSession") })
        assertTrue("锁屏不得挂任何延时看门狗（旧 15 秒自动结束就是这么死的）",
            branch.none { it.contains("postDelayed") })
    }

    // D4：观看端挂断/被对方挂断也要有名分（否则服务器看到 local_close 无从区分谁关的）
    @Test
    fun `viewer hangup names its end reason`() {
        assertTrue("finishWithCleanup 必须按 sendBye 区分 user_hangup / remote_bye",
            window(session(), "private fun finishWithCleanup(", 16)
                .any { it.contains("noteEnd(if (sendBye) \"user_hangup\" else \"remote_bye\")") })
    }

    // D5：黑屏排查依赖 surface 生命周期自报，不能只靠视频轨回调
    @Test
    fun `viewer surface lifecycle is observable`() {
        val src = session()
        assertTrue("renderer 必须挂 SurfaceHolder.Callback 上报 surface_destroyed/created",
            src.any { it.contains("holder.addCallback") } &&
                src.any { it.contains("\"surface_destroyed\"") } &&
                src.any { it.contains("\"surface_created\"") })
        assertTrue("远端视频轨挂上时必须上报 sink_attached（与 surface 事件对起来才能判定黑屏是没挂还是没帧）",
            window(src, "track.addSink(renderer)", 4).any { it.contains("viewerEvent(\"sink_attached\")") })
        assertTrue("观看端零帧卡死必须自报 video_stall（ICE 活着但画面黑 = 共享端采集问题）",
            window(webrtc(), "viewerZeroFpsStreak++", 10).any { it.contains("\"video_stall\"") })
    }

    // D6：自报只能走信令通道的枚举/标量，且断线时不得排队重试（否则收尾路径会拖出重连）
    @Test
    fun `diag upload is guarded and content-free`() {
        val body = window(selfCheck(), "private fun sendDiag(", 12)
        assertTrue("发送前必须判 Presence.client.isConnected，断线期间不投递",
            body.any { it.contains("Presence.client.isConnected") })
        assertTrue("上报字段只能是标量：detail 必须截断",
            window(selfCheck(), "fun reportEvent(", 12).any { it.contains(".take(64)") })
    }
}
