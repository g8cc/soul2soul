package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.54 操控启动无感化：对方接听（accepted）后，被控端不再被强拉回 Soul2Soul 主页，
 * 停在用户当前界面（采集/注入/悬浮均不要求前台）。回滚任一条，用例必须变红：
 * 把跳页加回来 / 把起流一起删了 / 删了兜底导致回前台卡"呼叫中"。
 */
class SeamlessControlStartTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun mainSource() = source("app/src/main/java/com/soul2soul/app/MainActivity.kt")
    private fun presenceSource() = source("app/src/main/java/com/soul2soul/app/session/PresenceService.kt")

    /** 取 accepted 分支体：从 "accepted" -> { 到下一个 case 标签 "call.canceled" 之前 */
    private fun acceptedBlock(): List<String> {
        val lines = presenceSource()
        val start = lines.indexOfFirst { it.contains("\"accepted\" -> {") }
        assertTrue("找不到 accepted 分支", start >= 0)
        val end = lines.withIndex().drop(start + 1)
            .firstOrNull { it.value.contains("\"call.canceled\"") }?.index
            ?: error("找不到终点锚点 \"call.canceled\"：分支要是改名了，这里的判据就落在别处，不许沉默地退化成 30 行窗口")
        return lines.subList(start, end)
    }

    // S1：accepted 分支绝不能有 startActivity（跳页 = 有感知，正是本次要去掉的）
    @Test
    fun `accepted branch does not force MainActivity to foreground`() {
        val block = acceptedBlock()
        assertTrue("accepted 不得再 startActivity 把主页顶到前台（会盖住被控端真实界面），实际命中：\n" +
            block.filter { it.contains("startActivity") }.joinToString("\n"),
            block.none { it.contains("startActivity") })
    }

    // S2：去掉的是跳页，不是起流——服务侧仍必须自启采集并落 acceptedReceived
    @Test
    fun `accepted branch still starts capture without relying on Activity`() {
        val block = acceptedBlock()
        assertTrue("accepted 必须保留 ScreenShareService.start（后台错过事件时服务自启采集）",
            block.any { it.contains("ScreenShareService.start(") })
        assertTrue("accepted 必须保留 MainActivity.acceptedReceived = true（后台权威标记）",
            block.any { it.contains("MainActivity.acceptedReceived = true") })
    }

    // S3：无感化后回前台兜底——会话已 live 却仍 outgoingPending，须收到即 endOutgoing 防卡呼叫中
    @Test
    fun `onResume reconciles stuck outgoing using service isLive`() {
        val lines = mainSource()
        val resume = lines.indexOfFirst { it.contains("override fun onResume") }
        assertTrue("找不到 onResume", resume >= 0)
        val window = lines.drop(resume).take(30)
        assertTrue("onResume 必须用 ScreenShareService.isLive() 作权威兜底",
            window.any { it.contains("ScreenShareService.isLive()") })
        assertTrue("兜底命中后必须 endOutgoing()，否则回前台停在呼叫页",
            window.any { it.contains("endOutgoing()") })
    }
}
