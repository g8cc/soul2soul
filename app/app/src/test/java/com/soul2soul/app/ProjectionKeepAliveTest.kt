package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享期锁屏保活的源码锁（v0.2.64）。
 *
 * 这一版改的是**语义而不是参数**：0.2.63 及以前，系统撤销录屏授权＝会话结束
 * （`reason=projection_stopped`），于是"她锁屏省电"在对端读成"她把我挂了"。
 * 现在改成画面停、通话续、一键重开。这种"从终止改成存续"的改动最怕的就是
 * 日后有人以"清理黑屏挂着的会话"为名把 `onEnded` 写回去——所以下面每一条都用
 * **全会数体/全分支块**的否定式判据钉住（docs/TESTING.md ㉚：窗口比函数短，禁令只贴前半段）。
 */
class ProjectionKeepAliveTest {

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
    private fun shareSvc() = source("app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt")
    private fun sessionAct() = source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")
    private fun reauthAct() = source("app/src/main/java/com/soul2soul/app/session/CaptureReauthActivity.kt")
    private fun manifest() = source("app/src/main/AndroidManifest.xml")

    /**
     * 清单里某个组件的完整标签块：从它的 name 行取到**该标签自身**收尾。
     * 不能用"下一个 theme/属性"当终点锚——上一个组件的属性会先被找到，窗口一旦为负
     * 就直接 error；更糟的是它可能反过来把别人的属性算进来（㉚：终锚必须在起锚之后且属于同一段）。
     */
    private fun manifestEntry(name: String): List<String> {
        val lines = manifest()
        val a = lines.indexOfFirst { it.contains("android:name=\"$name\"") }
        assertTrue("清单里没有 $name —— Activity 没登记时 startActivity 静默失败，点了没反应", a >= 0)
        if (lines[a].contains("/>") || lines[a].contains("</activity>")) return listOf(lines[a])
        val end = ((a + 1)..lines.lastIndex).firstOrNull {
            lines[it].contains("/>") || lines[it].contains("</activity>")
        } ?: error("$name 的标签找不到收尾，扫描口径需重看")
        return lines.subList(a, end + 1)
    }

    private fun between(lines: List<String>, from: String, to: String): List<String> {
        val a = lines.indexOfFirst { it.contains(from) }
        assertTrue("找不到起点锚点 $from", a >= 0)
        val b = ((a + 1)..lines.lastIndex).firstOrNull { lines[it].contains(to) }
            ?: error("锚点 $to 在 $from（行 ${a + 1}）之后不存在，窗口取不出来")
        return lines.subList(a, b)
    }

    private fun code(lines: List<String>): List<String> = lines.filterNot {
        val t = it.trim()
        t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
    }

    /** 从锚点行取到"下一个同级 fun/私有函数头或同级收尾 brace"，覆盖整个函数含嵌套 */
    private fun functionBody(lines: List<String>, anchor: String): List<String> {
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertEquals("锚点应恰好出现一次，实际 ${hits.size} 次：$anchor", 1, hits.size)
        val callIdx = hits[0]
        val funHead = Regex("""^\s*(\w+\s+)*fun\s[\w`<(]""")
        val header = (0..callIdx).reversed().firstOrNull { funHead.containsMatchIn(lines[it]) }
            ?: error("行 ${callIdx + 1} 不在任何函数内，扫描口径需重看")
        val pad = " ".repeat(lines[header].takeWhile { it == ' ' }.length)
        val nextFun = Regex("""^$pad(\w+\s+)*fun\s""")
        val end = ((header + 1)..lines.lastIndex).firstOrNull { i ->
            lines[i] == "${pad}}" || nextFun.containsMatchIn(lines[i])
        } ?: lines.lastIndex
        return lines.subList(header, end)
    }

    // P1：撤销授权不再是终止原因。这条是整版改动的核心，也是最容易被写回去的一格
    @Test
    fun `projection onStop no longer ends the session`() {
        val body = code(functionBody(webrtc(), "private fun projectionCallback()"))
        assertTrue(
            "onStop 必须交给上层保活，不许自己结束会话",
            body.any { it.contains("listener.onProjectionRevoked()") },
        )
        assertTrue(
            "撤销授权要留读数（服务器看不到 DataChannel 上的 videooff）",
            body.any { it.contains("\"projection_revoked\"") },
        )
        // 否定式判据取整个函数体：这两句一旦写回来，会话就又死于锁屏了
        assertTrue(
            "projection_stopped 作为终止原因已废：再写 noteEnd 就是回到 0.2.63 的挂断行为",
            body.none { it.contains("noteEnd(") },
        )
        assertTrue(
            "不许再走 onEnded 拆会话",
            body.none { it.contains("listener.onEnded(") },
        )
        // close() 里 capturer.stopCapture() 会主动回调 onStop，那一刻不是系统撤销
        assertTrue(
            "必须先判 closed，否则每通电话收尾都假报一条 projection_revoked",
            body.any { it.contains("if (closed) return") },
        )
    }

    // P2：重开画面只换采集器——轨道挂在 VideoSource 上，重建 PC 会把音频也拆了
    @Test
    fun `restartCapture swaps only the capturer and never touches the peer connection`() {
        val body = code(functionBody(webrtc(), "fun restartCapture(projectionData: Intent)"))
        assertTrue("必须复用既有 VideoSource", body.any { it.contains("videoSource ?: return false") })
        assertTrue("必须复用既有 SurfaceTextureHelper", body.any { it.contains("surfaceHelper ?: return false") })
        assertTrue(
            "重启路径的观察者必须标 restart=true，否则一次重试失败会 notifyEnded 拆掉整通话",
            body.any { it.contains("captureObserver(source, restart = true)") },
        )
        for (forbidden in listOf("createPeerConnection(", "createVideoSource(", "createVideoTrack(")) {
            assertTrue("重开画面不许建立 $forbidden", body.none { it.contains(forbidden) })
        }
        // 起不来只回退投影状态；拆会话是调用方的决定，而调用方（服务）刻意不拆
        assertTrue(
            "成功才许把 proj 写回 live，失败必须退回 stopped，读数不许说谎",
            body.count { it.contains("projectionState = ") } >= 2,
        )
        // 旧采集器只 dispose、不 stopCapture：ScreenCapturerAndroid.dispose() 只置一个标志位，
        // 而 stopCapture() 会去 MediaProjection.stop()，那会**异步**再回调一次旧 callback 的 onStop，
        // 时机落在 proj=live 写回之后 ⇒ 通知栏重新显示"画面已停"，而画面其实活着。
        assertTrue("旧采集器必须走 dispose 收口", body.any { it.contains("old?.dispose()") })
        assertTrue(
            "重开路径里不许出现 stopCapture（那是第二个 onStop 来源，会把 proj 改回 stopped）",
            body.none { it.contains(".stopCapture(") },
        )
    }

    // P3：首次采集失败仍然必须响亮结束（没有画面就没有这通共享），这条不许被保活顺手改掉
    @Test
    fun `first capture failure still ends the session while a retry does not`() {
        val body = code(functionBody(webrtc(), "private fun captureObserver("))
        assertTrue(
            "restart=false（首次）起不来要结束会话",
            body.any { it.contains("if (!success && !restart) notifyEnded(") },
        )
        assertEquals(
            "notifyEnded 只许出现在这一处判断里，多一处就是给保活开了后门",
            1,
            body.count { it.contains("notifyEnded(") },
        )
    }

    // P4：服务侧保活的三件事必须同时做到——置位、告诉对方、刷新通知；且不许拆会话
    @Test
    fun `service keeps the session alive and tells the peer`() {
        val body = code(functionBody(shareSvc(), "override fun onProjectionRevoked()"))
        assertTrue(body.any { it.contains("videoPaused = true") })
        assertTrue("必须把画面停这件事同步给观看端", body.any { it.contains("sendScreenStatus(\"videooff\")") })
        assertTrue("通知正文必须改道", body.any { it.contains("updateNotification(statusText())") })
        // 迟到的回调：收尾后 webRtc 已置空，再发 videooff 就是一条永远等不到 videoon 的横幅
        assertTrue("必须挡住 stopping/无会话的迟到回调", body.any { it.contains("if (stopping || webRtc == null || !live) return@post") })
        assertTrue("边沿触发，不许重复刷通知", body.any { it.contains("if (videoPaused) return@post") })
        for (forbidden in listOf("stopSession(", "Presence.client.send(\"bye\")")) {
            assertTrue("保活路径里不许出现 $forbidden", body.none { it.contains(forbidden) })
        }
    }

    // P5：hideX 必穷举 restoreX——videoPaused 的每一处置位都要有对应的复位，按条数判（㉘②）
    @Test
    fun `videoPaused flag is set once and reset on both exit paths`() {
        // 声明行的初值 `var videoPaused = false` 也是一条赋值，把它算进来就变成"三处复位"，
        // 判据失去意义；这里要数的是**运行期**的置位与复位。
        val assigns = code(shareSvc())
            .filter { it.contains("videoPaused = ") && !it.contains("var videoPaused") }
        assertEquals(
            "置位只许在保活回调里出现一次",
            1,
            assigns.count { it.contains("videoPaused = true") },
        )
        assertEquals(
            "复位必须有两处：重开成功 + 新会话从零开始（少一处就会把上一通的\"画面已停\"带进下一通）",
            2,
            assigns.count { it.contains("videoPaused = false") },
        )
        assertTrue(
            "重开成功必须同步告诉观看端（videooff 有了对偶，横幅才收得回去）",
            shareSvc().any { it.contains("sendScreenStatus(\"videoon\")") },
        )
    }

    // P6：通知改道与它的落地分支必须配对——只加 PendingIntent 不加 when 分支＝点了没反应
    @Test
    fun `notification reroute has its matching service action`() {
        val notif = code(functionBody(shareSvc(), "private fun buildNotification(text: String)"))
        assertTrue(
            "画面已停时整条通知的点按必须改道到重开入口",
            notif.any { it.contains(".setContentIntent(if (videoPaused) reauthIntent else consentIntent)") },
        )
        assertTrue(
            "重开入口指向 CaptureReauthActivity（授权对话框只能由 Activity 发起）",
            notif.any { it.contains("CaptureReauthActivity::class.java") },
        )
        val started = code(functionBody(shareSvc(), "override fun onStartCommand("))
        assertTrue(
            "服务必须有 ACTION_RESTART_CAPTURE 分支，否则 PendingIntent 投递到一条没人处理的 action",
            started.any { it.contains("ACTION_RESTART_CAPTURE ->") },
        )
        assertTrue(
            "重开动作必须挡住无会话的迟到投递（同 ACTION_TOGGLE_MUTE 的口径）",
            started.any { it.contains("if (webRtc == null) { stopSelf(); return START_NOT_STICKY }") },
        )
        assertEquals(
            "ACTION_RESTART_CAPTURE 常量声明与消费各一次以外不许散落第三处",
            3,
            shareSvc().count { it.contains("ACTION_RESTART_CAPTURE") },
        )
    }

    // P7：Activity 必须在清单里，否则 startActivity 静默失败（Android 上表现就是"点了没反应"）
    @Test
    fun `reauth activity is declared in the manifest and not exported`() {
        val entry = manifestEntry(".session.CaptureReauthActivity")
        assertTrue("未导出的入口不许 exported=true", entry.none { it.contains("android:exported=\"true\"") })
        assertTrue(
            "必须显式写 exported=false（缺省值随 intent-filter 变化，不能靠默认）",
            entry.any { it.contains("android:exported=\"false\"") },
        )
        assertTrue(
            "必须声明 excludeFromRecents：一次性的授权对话框不该留在最近任务里，留了她会以为还有一通电话",
            entry.any { it.contains("android:excludeFromRecents=\"true\"") },
        )
        assertTrue(
            "必须是 Dialog 主题：会话页在后台时，全屏白底会让重开授权看起来像崩溃",
            entry.any { it.contains("android:theme=\"@style/Theme.AppCompat.DayNight.Dialog\"") },
        )
        // 授权流程本身：没有 createScreenCaptureIntent 就只是一句提示，没有出路
        val act = code(reauthAct())
        assertTrue(act.any { it.contains("createScreenCaptureIntent()") })
        assertTrue(act.any { it.contains("ScreenShareService.reopenCapture(") })
        assertTrue(
            "无会话时必须先退场再谈授权（通知是至少一次投递，可能早已挂断）",
            act.any { it.contains("if (!ScreenShareService.isRunning())") },
        )
        assertTrue(
            "她拒绝授权要什么都不做——会话本来就活着，通知停在\"画面已停\"可以再点",
            act.none { it.contains("stopSession") || it.contains("send(\"bye\")") },
        )
    }

    // P8：文字必须跟着状态走。通知正文还写"陪玩中"而画面已停＝读数撒谎
    @Test
    fun `notification text reads the paused flag through the policy`() {
        val body = code(functionBody(shareSvc(), "private fun statusText()"))
        assertTrue(body.any { it.contains("ShareNoticePolicy.statusTextRes(") })
        assertTrue(body.any { it.contains("videoPaused = videoPaused") })
        val policy = source("app/src/main/java/com/soul2soul/app/session/ShareNoticePolicy.kt")
        assertTrue(
            "画面已停排在最前：那是她此刻唯一能补救的事",
            policy.any { it.contains("videoPaused -> R.string.sharing_video_paused") },
        )
    }

    // P9：观看端必须把"画面停"也纳入 video_stall 的抑制位，否则保活后每 30s 一条假告警
    @Test
    fun `viewer gates the fake stall on peer video off too`() {
        val branch = code(
            between(
                sessionAct(),
                "SignalRouter.DataAction.PeerVideoOff ->",
                "SignalRouter.DataAction.HideBanner ->",
            )
        )
        assertTrue(
            "PeerVideoOff 必须同步 setRemoteScreenPaused(true)，否则观看端连续 0 帧会刷成假 video_stall",
            branch.any { it.contains("setRemoteScreenPaused(true)") },
        )
        assertTrue(branch.any { it.contains("remoteVideoPaused = true") })
        // 恢复侧：只有 videoon 能清这个位（screenon 不算画面回来了）
        assertTrue(
            "清位必须钉在 videoon 上（screenon 不算画面回来了）",
            sessionAct().any { it.contains("if (json.optString(\"k\") == \"videoon\") remoteVideoPaused = false") },
        )
    }
}
