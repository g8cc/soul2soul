package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计第二轮（docs/AUDIT.md L2）：恢复对称性不变式的源码级闸门。
 * 这些位置 JVM 起不了 Activity/Service，用源码结构断言锁定——回滚任何一条，
 * 对应用例必须变红（v0.2.45 批次：操控假横幅、锁屏横幅卡死、来电通知残留、文字静默丢信）。
 */
class AuditRound2WiringTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun sessionSource() =
        source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")

    private fun linesAfter(lines: List<String>, predicate: (String) -> Boolean, n: Int): List<String> {
        val i = lines.indexOfFirst(predicate)
        assertTrue("源码里找不到目标行", i >= 0)
        return lines.drop(i).take(n)
    }

    /**
     * 取两个锚点之间的整段（含起锚、不含止锚）。
     * 行数窗口在长方法里会腐烂：往 onCreate 里加一行埋点就能让"锁必须在 startForeground 之后"
     * 这条真成立的不变式假红，反过来也会假绿（窗口切在关键行之前）。
     */
    private fun between(lines: List<String>, start: String, end: String): List<String> {
        val i = lines.indexOfFirst { it.contains(start) }
        val j = lines.indexOfFirst { it.contains(end) }
        assertTrue("找不到起锚 $start", i >= 0)
        assertTrue("找不到止锚 $end", j > i)
        return lines.subList(i, j)
    }

    @Test
    fun `controlMode writes go only through applyControlMode`() {
        val lines = sessionSource()
        val writes = lines.filter { it.contains("overlay.controlMode =") }
        assertTrue("overlay.controlMode 赋值必须收敛到 applyControlMode 一处，实际 ${writes.size} 处",
            writes.size == 1)
        assertTrue(writes.first().contains("overlay.controlMode = on"))
    }

    @Test
    fun `revoked banner exits control mode immediately`() {
        val lines = sessionSource()
        val block = linesAfter(
            lines, { it.contains("DataAction.RevokedBanner ->") }, 12
        )
        assertTrue("收到 ctl_denied 必须先 applyControlMode(false)，否则横幅5秒后谎称操控中",
            block.any { it.contains("applyControlMode(false)") })
    }

    @Test
    fun `banner restore hides when control mode already off`() {
        val lines = sessionSource()
        val block = linesAfter(lines, { it.contains("private val restoreCtlBanner") }, 5)
        assertTrue("退出操控后 restoreCtlBanner 必须收起横幅（else GONE），不能留着假横幅",
            block.any { it.contains("View.GONE") })
    }

    @Test
    fun `screen status is dual-channel and reliable`() {
        val lines = source(
            "app/src/main/java/com/soul2soul/app/session/ScreenShareService.kt"
        )
        val body = linesAfter(lines, { it.contains("private fun sendScreenStatus") }, 6)
        assertTrue("sendScreenStatus 必须 anno+ctl 双通道补发",
            body.any { it.contains("sendAnnotation") } && body.any { it.contains("sendControl") })
        val callers = lines.filter {
            it.contains("sendScreenStatus(\"screenoff\")") || it.contains("sendScreenStatus(\"screenon\")")
        }
        assertTrue("锁屏/解锁两条状态都必须走 sendScreenStatus，实际 ${callers.size} 条", callers.size == 2)
    }

    @Test
    fun `incoming call notification is cancelled on cancel`() {
        val lines = source(
            "app/src/main/java/com/soul2soul/app/session/PresenceService.kt"
        )
        val block = linesAfter(lines, { it.contains("\"call.canceled\" ->") }, 5)
        assertTrue("对方取消呼叫必须撤销 ID_CALL 来电通知",
            block.any { it.contains("cancel(Notif.ID_CALL)") })
        val recall = linesAfter(lines, { it.contains("fun showRecallNotification") }, 25)
        assertTrue("找回通知必须有过期自动撤销（服务端 pendingTtl 60s）",
            recall.any { it.contains("postDelayed") && it.contains("ID_RECALL") })
    }

    @Test
    fun `text send checks wire result before committing`() {
        val lines = source("app/src/main/java/com/soul2soul/app/msg/MessageActivity.kt")
        val body = linesAfter(lines, { it.contains("private fun sendText") }, 30)
        assertTrue("sendText 必须消费 msg.post 的发送结果（val sent = …）",
            body.any { it.contains("val sent = Presence.client.send") })
        assertTrue("发送失败必须保留草稿并提示，不得先清输入框",
            body.any { it.contains("if (!sent)") })
    }

    @Test
    fun `back press closes emoji panel before leaving`() {
        val src = source("app/src/main/java/com/soul2soul/app/msg/MessageActivity.kt").joinToString("\n")
        assertTrue("必须注册 OnBackPressedCallback 先收表情面板",
            src.contains("OnBackPressedCallback") && src.contains("if (emojiOpen) setEmojiPanel(false)"))
    }

    @Test
    fun `tapping input closes emoji panel like wechat`() {
        val lines = source("app/src/main/java/com/soul2soul/app/msg/MessageActivity.kt")
        val i = lines.indexOfFirst { it.contains("etInput.setOnClickListener") }
        assertTrue("点输入框必须收表情面板（微信同款，否则面板和键盘叠着）", i >= 0)
        val block = lines.drop(i).take(4)
        assertTrue(block.any { it.contains("if (emojiOpen) setEmojiPanel(false)") })
    }

    @Test
    fun `send success collapses emoji panel like wechat`() {
        // 微信实锤行为：表情墙开着发完消息，面板必须收掉；但只能在 send() 成功之后收
        // （断网/失败路径先 return，草稿和面板都留着）
        val lines = source("app/src/main/java/com/soul2soul/app/msg/MessageActivity.kt")
        val i = lines.indexOfFirst { it.contains("private fun sendText") }
        assertTrue("找不到 sendText", i >= 0)
        val body = lines.drop(i).take(35)
        val sentFail = body.indexOfFirst { it.contains("if (!sent)") }
        val close = body.indexOfFirst { it.contains("setEmojiPanel(false)") }
        assertTrue("发送成功必须收表情墙（v0.2.50）", close >= 0)
        assertTrue("必须在校验 send() 成功之后才收面板", sentFail in 0 until close)
    }

    @Test
    fun `opening emoji panel must drop input focus before hiding IME`() {
        // Mi5/Android7 真机实锤（v0.2.49）：键盘开着时点 😊，只调 hideSoftInputFromWindow
        // 会被系统弹回（EditText 仍有焦点），面板开在键盘背后——用户看到"点了没反应"
        val lines = source("app/src/main/java/com/soul2soul/app/msg/MessageActivity.kt")
        val i = lines.indexOfFirst { it.contains("private fun setEmojiPanel") }
        assertTrue("找不到 setEmojiPanel", i >= 0)
        val body = lines.drop(i).take(16)
        val hide = body.indexOfFirst { it.contains("hideSoftInputFromWindow") }
        assertTrue("开面板必须收键盘", hide >= 0)
        val clear = body.indexOfFirst { it.contains("etInput.clearFocus()") }
        assertTrue("收键盘前必须先 clearFocus，否则 MIUI/Android7 键盘弹回盖住面板", clear in 0 until hide)
    }

    // ==== v0.2.51 息屏不断连（ wakelock + specialUse）：锁结构配对，回滚即变红 ====

    private fun presenceSource() =
        source("app/src/main/java/com/soul2soul/app/session/PresenceService.kt")

    @Test
    fun `presence wake lock acquired only after service is foreground`() {
        // 防 FGS 起失败却先持锁导致永久漏锁：onCreate 里 acquire 收口到 updateWakeLock，
        // 且必须在 startForeground 之后调用
        val lines = presenceSource()
        val body = between(lines, "override fun onCreate", "override fun onStartCommand")
        val fg = body.indexOfFirst { it.contains("startForeground(") }
        val lock = body.indexOfFirst { it.contains("updateWakeLock(") }
        assertTrue("onCreate 必须按配对状态收口 wakelock", lock >= 0)
        assertTrue("wakelock 必须排在 startForeground 之后", fg in 0 until lock)
    }

    @Test
    fun `presence wake lock acquire carries a timeout`() {
        // MIUI 强杀进程时 onDestroy 不执行，无超时=永久漏锁耗电，acquire 必须带超时兜底
        val lines = presenceSource()
        val body = linesAfter(lines, { it.contains("private fun updateWakeLock") }, 15)
        assertTrue("acquire 必须带超时 WAKELOCK_TIMEOUT_MS",
            body.any { it.contains("wakeLock.acquire(WAKELOCK_TIMEOUT_MS)") })
    }

    @Test
    fun `presence wake lock released on destroy and unpair`() {
        val lines = presenceSource()
        val destroy = linesAfter(lines, { it.contains("override fun onDestroy") }, 8)
        assertTrue("onDestroy 必须 release 锁（acquire 的对偶出口）",
            destroy.any { it.contains("wakeLock.release()") })
        val unpaired = between(lines, "\"unpaired\" ->", "\"pair.recall_ask\" ->")
        assertTrue("解绑后立即释放 wakelock（hideX 必穷举 restoreX）",
            unpaired.any { it.contains("updateWakeLock(false)") })
        assertTrue("解绑必须同时关掉待机遥测（start 的对偶出口，否则未配对期攒一堆发不出去的事件）",
            unpaired.any { it.contains("StandbyProbe.stop(") })
    }

    @Test
    fun `presence service uses specialUse not dataSync`() {
        // Android14 对 dataSync 前台服务有每日时长上限，会定时停掉常驻连接
        val text = source("app/src/main/AndroidManifest.xml").joinToString("\n")
        assertTrue("PresenceService 必须用 specialUse",
            Regex("PresenceService\"[^>]*foregroundServiceType=\"specialUse\"").containsMatchIn(text) ||
                text.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue("禁止再用 dataSync 类型", !text.contains("foregroundServiceType=\"dataSync\""))
        assertTrue("specialUse 需 FOREGROUND_SERVICE_SPECIAL_USE 权限",
            text.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    @Test
    fun `battery exemption guide throttles by time not once-ever`() {
        // 旧版 askedBattery 拒绝后永不再问，而息屏保活依赖豁免——必须改按时间戳节流
        val text = source("app/src/main/java/com/soul2soul/app/MainActivity.kt").joinToString("\n")
        assertTrue("电池引导必须用 batteryPromptAt 时间戳", text.contains("batteryPromptAt(this)"))
        assertTrue("不得再走只问一次的 askedBattery", !text.contains("askedBattery"))
    }

    // ==== v0.2.52 全屏主叫页：peer.offline 温柔漏斗 + 呼叫态收口，回滚即变红 ====

    private fun mainSource() = source("app/src/main/java/com/soul2soul/app/MainActivity.kt")

    @Test
    fun `peer offline uses ringback not instant message dialog`() {
        // L4：冷冰冰的"TA不在线要留言吗"弹窗必须整体移除，改为呼叫页假振铃后再出温柔引导
        val text = mainSource().joinToString("\n")
        assertTrue("showLeaveMessageDialog 冷弹窗必须删除", !text.contains("showLeaveMessageDialog"))
        val block = linesAfter(mainSource(), { it.contains("\"peer.offline\" ->") }, 24)
        assertTrue("真离线必须走 ringback 假振铃再转 OFFLINE_PROMPT",
            block.any { it.contains("ringback.postDelayed") } &&
                block.any { it.contains("OutgoingPhase.OFFLINE_PROMPT") })
        assertTrue("必须按 reason 分流（空=TA离线，非空=主叫侧问题）",
            block.any { it.contains("reason.isEmpty()") })
    }

    @Test
    fun `offline reason errors do not enter message funnel`() {
        // L5：too_fast/not_authed 属主叫侧问题，只 toast，绝不进留言漏斗
        val block = linesAfter(mainSource(), { it.contains("\"peer.offline\" ->") }, 24)
        val elseArm = block.dropWhile { !it.contains("} else {") }
        assertTrue("非空 reason 分支必须落回 NONE + 轻量 toast",
            elseArm.any { it.contains("OutgoingPhase.NONE") } &&
                elseArm.any { it.contains("toast(R.string.peer_offline)") })
        assertTrue("peer.offline 处理内不得直接跳留言页（只有离线子态的按钮可以）",
            block.none { it.contains("MessageActivity") })
    }

    @Test
    fun `outgoing overlay visibility funnels through updateUi only`() {
        // L6：overlay 显隐单点收口——散落赋值迟早漏一条（v0.2.44 丢球同款根因）
        val lines = mainSource()
        val assigns = lines.count { it.contains("outgoingOverlay.visibility =") }
        assertTrue("outgoingOverlay 可见性必须只有一处赋值，实际 ${assigns} 处", assigns == 1)
        val i = lines.indexOfFirst { it.contains("private fun updateUi") }
        val j = lines.indexOfFirst { it.contains("outgoingOverlay.visibility =") }
        assertTrue("且必须位于 updateUi 内", i >= 0 && j > i)
    }

    @Test
    fun `every outgoing exit clears handlers and resets phase`() {
        // L6：六路出口全清——任何一条漏撤 Handler 或漏复位，呼叫页就会挂死
        val lines = mainSource()
        val exits = listOf(
            "\"declined\" -> {", "\"bye\" -> {", "\"call.canceled\" -> {",
            "\"unpaired\" -> {", "\"local.sessionEnded\" -> {", "\"local.sessionLive\" -> {",
        )
        for (marker in exits) {
            val block = linesAfter(lines, { it.contains(marker) }, 12)
            assertTrue("$marker 必须经 endOutgoing 统一复位",
                block.any { it.contains("endOutgoing()") })
        }
        val accepted = linesAfter(lines, { it.contains("\"accepted\" -> {") }, 10)
        assertTrue("accepted 必须收起呼叫页（置 NONE）并撤 ringback",
            accepted.any { it.contains("outgoingPhase = OutgoingPhase.NONE") } &&
                accepted.any { it.contains("ringback.removeCallbacksAndMessages") })
        val helper = linesAfter(lines, { it.contains("private fun endOutgoing") }, 8)
        assertTrue("endOutgoing 必须同时撤 callTimeout 与 ringback 两把 Handler",
            helper.any { it.contains("callTimeout.removeCallbacksAndMessages") } &&
                helper.any { it.contains("ringback.removeCallbacksAndMessages") })
        val timeout = linesAfter(lines, { it.contains("if (outgoingPending && gen == callGen)") }, 5)
        assertTrue("呼叫超时必须经 endOutgoing 收口", timeout.any { it.contains("endOutgoing()") })
        val denied = linesAfter(lines, { it.contains("toast(R.string.projection_denied)") }, 3)
        assertTrue("授权拒绝必须经 endOutgoing 收口（呼叫页不能留在屏上）",
            denied.any { it.contains("endOutgoing()") })
    }
}
