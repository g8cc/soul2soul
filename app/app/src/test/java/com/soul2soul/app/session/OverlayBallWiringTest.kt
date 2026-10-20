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

    // v0.2.65 双铅笔根治：这颗球是 TYPE_APPLICATION_OVERLAY 窗口 ⇒ 只要它可见就会被采进视频，
    // 观看端那颗是 Activity 内的 View（不被采），于是共享时屏上出现两个铅笔。
    // 唯一出路是"闲置即完全隐形"（Android 没有按窗口排除采集的能力，FLAG_SECURE 只会出黑块）。
    @Test
    fun `idle ball is fully transparent so it never reaches the captured frame`() {
        val src = source().joinToString("\n")
        assertTrue("BALL_DIM_ALPHA 必须是 0f：0.15f 的「淡化」在她那边仍是看得见的第二个铅笔",
            Regex("""BALL_DIM_ALPHA\s*=\s*0f""").containsMatchIn(src))
        // 隐形不等于消失：alpha=0 的 View 仍参与触摸命中，所以"醒过来"的路径必须在按下的那一刻
        val down = linesBetween("override fun onDown(", 2)
        assertTrue("手势 onDown 必须 wakeBall()，否则她点一块看不见的东西没有任何反馈",
            down.any { it.contains("wakeBall") })
    }

    @Test
    fun `invisible ball remembers where she left it`() {
        val src = source().joinToString("\n")
        assertTrue("命中区看不见 ⇒ 位置必须停在她的老地方（每次回默认值＝让人猜）",
            src.contains("Prefs.ballSpot(") && src.contains("Prefs.setBallSpot("))
        assertTrue("恢复要重新夹一次（转屏/换分辨率后旧坐标可能已出屏）",
            Regex("""clamp\(\s*spot\.first""").containsMatchIn(src))
    }

    // 通知栏「画笔」是球隐形后她唯一看得见的按钮：分派只能认 action，绝不能在服务重启时顺手开画笔
    @Test
    fun `doodle notification action is dispatched by action only`() {
        val body = functionBody("override fun onStartCommand(")
        val gate = body.indexOfFirst { it.contains("intent?.action == ACTION_TOGGLE_DOODLE") }
        assertTrue("onStartCommand 里要有 ACTION_TOGGLE_DOODLE 分派", gate >= 0)
        val toggles = body.withIndex().filter { it.value.contains("toggleDoodle()") }.map { it.index }
        assertTrue("toggle 在整个 onStartCommand 里只能有一处，且必须在 action 判定之后（实际 $toggles，gate=$gate）",
            toggles.size == 1 && toggles[0] > gate)
        assertTrue("action 判定之前不许出现 showBall——重启分支（intent==null）与开画笔必须互不相干",
            body.take(gate).none { it.contains("showBall()") })
        val toggle = toggles[0]
        assertTrue("权限没给不许静默失败", toggle > gate &&
            body.subList(gate, toggle).any { it.contains("overlay_permission_missing") })
    }

    private fun functionBody(anchor: String): List<String> {
        val lines = source()
        val hits = lines.indices.filter { lines[it].contains(anchor) }
        assertTrue("锚点应恰好出现一次，实际 ${hits.size} 次：$anchor", hits.size == 1)
        val header = hits[0]
        val pad = " ".repeat(lines[header].takeWhile { it == ' ' }.length)
        val nextFun = Regex("""^$pad(\w+\s+)*fun\s""")
        val end = ((header + 1)..lines.lastIndex).firstOrNull { i ->
            lines[i] == "${pad}}" || nextFun.containsMatchIn(lines[i])
        } ?: lines.lastIndex
        return lines.subList(header, end)
    }

    private fun linesBetween(anchor: String, size: Int): List<String> {
        val lines = source()
        val i = lines.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return lines.drop(i).take(size)
    }
}
