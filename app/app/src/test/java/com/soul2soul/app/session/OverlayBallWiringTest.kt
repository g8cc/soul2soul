package com.soul2soul.app.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.42 真机实锤：自画工具条「完成」只拆绘制层，没把入口球还回去，
 * 球整场共享消失（只有旋转屏幕/重开会话才回来）。
 * Service 的窗口接线 JVM 跑不了，用源码级断言锁定不变式：
 * 每个 exitDoodle() 调用点后面必须紧跟球位恢复（showBall/hideBall）。
 */
class OverlayBallWiringTest {

    private fun source(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/com/soul2soul/app/session/AnnotationOverlayService.kt")
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 AnnotationOverlayService.kt（工作目录: ${File("").absolutePath}）")
    }

    @Test
    fun `every exitDoodle call is followed by ball restore`() {
        val lines = source()
        val offenders = lines.indices.filter { i ->
            val t = lines[i].trim()
            t.contains("exitDoodle()") && !t.startsWith("private fun") &&
                !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") &&
                lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.contains("Ball()") != true
        }
        assertTrue("exitDoodle() 调用点缺球位恢复（行号）: $offenders", offenders.isEmpty())
    }

    @Test
    fun `done button exits through finishDoodle not raw exitDoodle`() {
        val src = source().joinToString("\n")
        assertTrue("「完成」按钮必须走 finishDoodle()（拆层+还球一体）",
            src.contains("setOnClickListener { finishDoodle() }"))
    }
}
