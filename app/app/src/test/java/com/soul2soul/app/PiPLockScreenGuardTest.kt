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

    /** 两个锚点之间的整段：onState 里每加一条埋点就会把行数窗口顶穿，改成名字锚 */
    private fun between(lines: List<String>, start: String, end: String): List<String> {
        val i = lines.indexOfFirst { it.contains(start) }
        val j = lines.indexOfFirst { it.contains(end) }
        assertTrue("找不到起锚 $start", i >= 0)
        assertTrue("找不到止锚 $end", j > i)
        return lines.subList(i, j)
    }

    /** 锚点行之前的 n 行。用来判"这条上报挂在哪个分支里"——整文件 any 判不出分支（见 L4 注释） */
    private fun before(lines: List<String>, anchor: String, n: Int): List<String> {
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertTrue("锚点应恰好出现一次，实际 ${hits.size} 次：$anchor", hits.size == 1)
        return lines.drop(hits[0] - n).take(n)
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

    // L4：信令断/续在**会话期与待机期都要报**。历史顺序是反的：0.2.58 之前只有会话期报
    // ⇒「待机 socket 死了多久」在服务器侧是整格空白（10-18 那 9 次 pong 超时只能从服务端日志反推）。
    // 而这条用例的名字与判据是那个旧世界留下的，成了**反向的假锁**：
    // `src.any { it.contains("signaling_down") }` 会被待机那一条满足 ⇒ 把会话期那一条整个删掉照样绿。
    // 现在按分支钉死：会话期那条必须挂在 `Presence.sessionBusy` 下且**无门**（会话中每次断都要看见），
    // 待机那条必须挂在 `standbySigDown` 边沿门下（Doze 里每 30s 一次退避重连不许刷成噪声）。
    @Test
    fun `signaling drop and recovery are reported on both branches, standby edge-gated`() {
        val src = presence()
        val onState = between(src, "override fun onState(connected: Boolean)", "先建立总线订阅")
        mapOf(
            "会话期·复活" to "reportSignaling(\"signaling_up\", reconnectAttempt, down, signalRole())",
            "会话期·掉线" to "reportSignaling(\"signaling_down\", reconnectAttempt, 0L, signalRole())",
            "待机期·复活" to "reportSignaling(\"signaling_up\", reconnectAttempt, down, \"standby\")",
            "待机期·掉线" to "reportSignaling(\"signaling_down\", reconnectAttempt, 0L, \"standby\")",
        ).forEach { (name, anchor) ->
            val guarded = name.startsWith("待机期·")
            val block = before(src, anchor, 2)
            assertTrue(
                "$name 的上报必须挂在 ${if (guarded) "边沿门" else "sessionBusy"} 分支里（整文件 any 判不出分支，见本条注释）",
                block.any {
                    if (guarded) it.contains("} else if (${if (name.endsWith("掉线")) "!" else ""}standbySigDown)")
                    else it.contains("Presence.sessionBusy")
                },
            )
            // 名字锚必须在 onState 内：挪到别处（比如只在新会话建立时报）就不再是 socket 状态的自报
            assertTrue("$name 的锚点落在 onState 之外", onState.any { it.contains(anchor) })
        }
    }

    // L4c：signaling_down 按定义发生在 socket 已断时，SelfCheck.sendDiag 要求 isConnected 才发，
    // 所以那条只能进本机 logcat——服务器要看到"断了多久"，必须由复活那一条把 downMs 带上来。
    // 没这一格的话，0.2.56 的 B4 在服务器侧是恒不成立的判据（和 sink_attached 同一类坑）。
    // v0.2.59 起断口起点改用 elapsedRealtime：复活重连同时会触发一次 NTP 校时，
    // wall-clock 相减会把长度打成负数或几十小时（AGENTS：设备时钟不可信）。
    @Test
    fun `outage length reaches the server via the recovery event`() {
        val src = presence()
        val onState = between(src, "override fun onState(connected: Boolean)", "先建立总线订阅")
        assertTrue("断线侧必须用单调钟记下断口起点",
            onState.any { it.contains("signalingDownSinceMonoMs = SystemClock.elapsedRealtime()") })
        assertTrue("复活侧必须读一次并清零（否则下一次断口会把上一次的长度算进来）",
            onState.any { it.contains("signalingDownSinceMonoMs = 0L") })
        assertTrue("复活上报必须走收口方法（锚点按名字找，不再靠 onCreate 的行数窗口）",
            onState.any { it.contains("reportSignaling(\"signaling_up\"") })
        val rep = window(src, "private fun reportSignaling(", 10)
        assertTrue("detail 必须带 downMs（服务器看不见 signaling_down）",
            rep.any { it.contains("downMs=") })
        assertTrue("只有真有断口才拼 downMs（首连不许报 downMs=0 的假长度）",
            rep.any { it.contains("if (downStartMonoMs > 0L)") })
    }
}
