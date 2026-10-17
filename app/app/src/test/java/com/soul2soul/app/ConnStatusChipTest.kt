package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.53 连接状态醒目胶囊：把"信令连接态×对方在线态"合并成一枚带底色的胶囊。
 * 这些位置 JVM 起不了 Activity，用源码结构断言锁定接线——回滚任一条，
 * 对应用例必须变红（尤其：连接态从不出现在主页 / 胶囊取色多处散写导致某态漏刷）。
 */
class ConnStatusChipTest {

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

    private fun linesAfter(lines: List<String>, predicate: (String) -> Boolean, n: Int): List<String> {
        val i = lines.indexOfFirst(predicate)
        assertTrue("源码里找不到目标行", i >= 0)
        return lines.drop(i).take(n)
    }

    // L1：Presence 连接态必须落可读源，且 onState 两分支都广播 local.connState
    @Test
    fun `presence exposes connState and broadcasts onState both branches`() {
        val presence = presenceSource()
        val body = linesAfter(presence, { it.contains("override fun onState") }, 6)
        assertTrue("onState 必须更新 Presence.connState（Activity 错过事件的权威兜底源）",
            body.any { it.contains("Presence.connState =") })
        assertTrue("onState 必须广播 local.connState，主页才能实时刷新胶囊",
            body.any { it.contains("\"local.connState\"") })
        val obj = presenceSource()
        assertTrue("Presence 必须定义 CONN_CONNECTING/CONNECTED/OFFLINE 三态常量",
            obj.any { it.contains("CONN_CONNECTING") } &&
                obj.any { it.contains("CONN_CONNECTED") } &&
                obj.any { it.contains("CONN_OFFLINE") })
    }

    // L2：MainActivity 必须消费 local.connState 事件
    @Test
    fun `main subscribes to connState events`() {
        val block = linesAfter(mainSource(), { it.contains("\"local.connState\" ->") }, 5)
        assertTrue("收到连接态事件必须 updateUi，否则断线/重连胶囊不动",
            block.any { it.contains("updateUi()") })
    }

    // L3：胶囊取色只在 updateUi 一处收口（多入口散写=某态漏刷真机踩坑的同款风险）
    @Test
    fun `chip color painting funnels through updateUi only`() {
        val lines = mainSource()
        val ui = lines.indexOfFirst { it.contains("private fun updateUi") }
        assertTrue("找不到 updateUi", ui >= 0)
        val chipSets = lines.withIndex().filter {
            it.value.contains("connChip.background") || it.value.contains("connDot.background")
        }
        assertTrue("胶囊底色+圆点取色必须各一处、且都在 updateUi 内，实际 ${chipSets.size} 处",
            chipSets.size == 2)
        assertTrue(chipSets.all { it.index > ui })
    }

    // L4：五态文案全被引用（漏一态=该状态下显示不出正确话术）
    @Test
    fun `all five conn states have text wired`() {
        val src = mainSource().joinToString("\n")
        for (key in listOf(
                "conn_connecting", "conn_offline", "conn_connected_unpaired",
                "conn_connected_online", "conn_connected_offline")) {
            assertTrue("缺少连接态文案引用：$key", src.contains("R.string.$key"))
        }
    }
}
