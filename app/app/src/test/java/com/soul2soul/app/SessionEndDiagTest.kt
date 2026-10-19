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

    /** 只留代码行：注释里提到某个字段名正是为了解释"为什么不能用它"，不该被扫描判成违规 */
    private fun code(lines: List<String>): List<String> = lines.filterNot {
        val t = it.trim()
        t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
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

    // D6：自报只能走信令通道的枚举/标量；发不出去时只许落本机文件（不许在收尾路径里拖出重连）
    @Test
    fun `diag upload is guarded and content-free`() {
        val body = window(selfCheck(), "private fun sendDiag(", 16)
        assertTrue("发送前必须判 Presence.client.isConnected",
            body.any { it.contains("Presence.client.isConnected") })
        assertTrue("上报字段只能是标量：detail 必须截断",
            window(selfCheck(), "fun reportEvent(", 12).any { it.contains(".take(64)") })
    }

    // D7：时长上报不得复用被闪断清零的字段（0.2.55 生产日志 liveMs 失真的根因）
    @Test
    fun `session end timing ignores the outage-cleared field`() {
        val body = code(window(webrtc(), "private fun reportSessionEnd()", 18))
        assertTrue("reportSessionEnd 不许再用 connectedSinceMs：ICE 闪断会把它清零，时长就归 0",
            body.none { it.contains("connectedSinceMs") })
        assertTrue("setupMs/liveMs 必须走 SessionTiming（纯函数，有单测锁）",
            body.any { it.contains("SessionTiming.setupMs(") } &&
                body.any { it.contains("SessionTiming.liveMs(") })
    }

    // D8：失去连通必须单独记一格，首次连通时刻不得被抹掉
    @Test
    fun `outage branches stamp the lost instant`() {
        for (anchor in listOf(
            "PeerConnection.IceConnectionState.DISCONNECTED -> {",
            "PeerConnection.IceConnectionState.FAILED -> {",
        )) {
            val branch = window(webrtc(), anchor, 12)
            assertTrue("$anchor 清零 connectedSinceMs 时必须记下 mediaLostAtMs",
                branch.any { it.contains("mediaLostAtMs = android.os.SystemClock.elapsedRealtime()") })
        }
        assertTrue("首次连通必须记下会话内永不清零的 mediaConnectedAtMs",
            window(webrtc(), "if (!wasConnected) {", 8).any { it.contains("mediaConnectedAtMs = now") })
    }

    // D9：断连期埋点必须能在复活后到达服务器——米15 接不了 adb，这是它唯一的远程取证通路
    @Test
    fun `blind window events are queued locally and drained on reconnect`() {
        val body = window(selfCheck(), "private fun sendDiag(", 16)
        assertTrue("发不出去必须入队，否则断连那一刻的证据永久丢失",
            body.any { it.contains("DiagBacklog.enqueue(") })
        val backlog = source("app/src/main/java/com/soul2soul/app/util/DiagBacklog.kt")
        assertTrue("补报队列不得自己发起连接（收尾路径拖出重连会放大断连）",
            backlog.none { it.contains(".connect(") })
        assertTrue("重放必须复用线上已认识的 diag.event 通道（服务器零改动）",
            backlog.any { it.contains("Presence.client.send(\"diag.event\")") })
        assertTrue("信令复活必须补报",
            source("app/src/main/java/com/soul2soul/app/session/PresenceService.kt")
                .any { it.contains("DiagBacklog.flush(") })
    }

    // D10：系统撤销录屏 ≠ 她按了停止。10-18 生产日志（app=0.2.58）里两次 14~16 秒的
    // "连上就断"都报成 reason=local_stop，根因是 onStop 只交出去不占位，收尾那条
    // local_stop 把真因盖掉了。
    @Test
    fun `revoked projection claims its own end reason`() {
        val cb = window(webrtc(), "MediaProjection.Callback()", 12)
        val claimed = cb.indexOfFirst { it.contains("noteEnd(\"projection_stopped\")") }
        val handed = cb.indexOfFirst { it.contains("listener.onEnded(\"projection_stopped\")") }
        assertTrue("撤销授权必须先 noteEnd 占位，否则 diag.session.reason 只能是 local_stop", claimed >= 0)
        assertTrue("noteEnd 必须早于交出去（first-wins，晚了就白写）", handed > claimed)
        // 覆盖方仍在——去掉上面那条占位，local_stop 就会重新赢，这条锁才会真的变红
        assertTrue("收尾仍会补 local_stop（这是本锁存在的理由，不许顺手删掉它）",
            window(share(), "private fun stopSession(", 40)
                .any { it.contains("webRtc?.noteEnd(\"local_stop\")") })
    }

    // D11：D10 是同一类缺陷的第一个实例，所以锁整类——任何"绕开 notifyEnded 直接交出去"
    // 的新路径都必须自己占 first-wins 槽，否则它的原因永远不会出现在 diag.session.reason 里。
    @Test
    fun `no hand-off skips the first-wins slot`() {
        val lines = code(webrtc())
        val notifyStart = lines.indexOfFirst { it.contains("private fun notifyEnded(") }
        assertTrue("找不到 notifyEnded（本锁的前提是它仍然是唯一收口）", notifyStart >= 0)
        val offenders = lines.mapIndexedNotNull { i, l ->
            if (!l.contains("listener.onEnded(")) return@mapIndexedNotNull null
            if (i in notifyStart until notifyStart + 20) return@mapIndexedNotNull null
            val ahead = lines.drop(maxOf(0, i - 3)).take(3)
            if (ahead.any { it.contains("noteEnd(") }) null else i to l.trim()
        }
        assertTrue("交出去却没占位的结束路径（真因会被 local_stop/local_close 覆盖）: " + offenders,
            offenders.isEmpty())
    }
}
