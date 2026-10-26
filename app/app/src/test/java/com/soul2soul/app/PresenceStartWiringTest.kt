package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceStartWiringTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val file = File(dir, rel)
            if (file.exists()) return file.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private fun functionBody(lines: List<String>, anchor: String): List<String> {
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertTrue("锚点应恰好出现一次，实际 ${hits.size} 次：$anchor", hits.size == 1)
        val header = hits.single()
        val indent = lines[header].takeWhile { it == ' ' }
        val nextFunction = Regex("^$indent(\\w+\\s+)*fun\\s")
        val end = ((header + 1)..lines.lastIndex).firstOrNull { index ->
            lines[index] == "$indent}" || nextFunction.containsMatchIn(lines[index])
        } ?: lines.lastIndex
        return lines.subList(header, end)
    }

    @Test
    fun `presence start is deferred until activity is interactive`() {
        val main = source("app/src/main/java/com/soul2soul/app/MainActivity.kt")
        val onCreate = functionBody(main, "override fun onCreate(savedInstanceState: Bundle?)")
        val onResume = functionBody(main, "override fun onResume()")
        val focus = functionBody(main, "override fun onWindowFocusChanged(hasFocus: Boolean)")

        assertFalse(
            "onCreate 可能在 MIUI 锁屏态执行，不得直接启动前台服务",
            onCreate.any { it.contains("Presence.ensureStarted(this)") },
        )
        assertTrue(
            "回到前台时必须重试信令服务",
            onResume.any { it.contains("Presence.ensureStarted(this)") },
        )
        assertTrue(
            "获得窗口焦点后必须重试锁屏期间被拒绝的启动",
            focus.any { it.contains("Presence.ensureStarted(this)") },
        )
    }

    @Test
    fun `presence service readiness suppresses duplicate starts and resets on destroy`() {
        val presence = source("app/src/main/java/com/soul2soul/app/session/PresenceService.kt")
        val objectBody = presence.joinToString("\n")
        val create = functionBody(presence, "override fun onCreate()")
        val destroy = functionBody(presence, "override fun onDestroy()")

        assertTrue("重复生命周期回调不应重复启动服务", objectBody.contains("if (serviceReady) return"))
        assertTrue("服务真正进入前台后才置为 ready", create.any { it.contains("Presence.markServiceReady()") })
        assertTrue("服务销毁后必须允许下一次前台重试", destroy.any { it.contains("Presence.markServiceStopped()") })
    }
}
