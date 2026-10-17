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
}
