package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观看端入口球可拖动锁定（v0.2.55）：她反馈"右下角小铅笔拖不动"——这颗球原先只挂了点击，
 * 挡住对方画面里的字幕/笔迹就挪不开。回滚任何一条，投诉必然复发：
 * 摘掉 touch listener / 不做 slop 判断（点一下也乱跑）/ 不钳制（拖出屏幕再也找不回来）。
 */
class ViewerBallDragTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    private val src = source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")

    private fun block(anchor: String, size: Int): List<String> {
        val i = src.indexOfFirst { it.contains(anchor) }
        assertTrue("找不到锚点 $anchor", i >= 0)
        return src.drop(i).take(size)
    }

    // B1：初始化时必须给球装上手势（只有点击 = 拖不动）
    @Test
    fun `ball gets a touch handler next to its click handler`() {
        val init = block("btnBall.setOnClickListener", 4)
        assertTrue("btnBall 必须同时有点击与拖动入口",
            init.any { it.contains("setupBallDrag()") })
        assertTrue("setupBallDrag 必须注册 OnTouchListener",
            block("private fun setupBallDrag()", 45).any { it.contains("setOnTouchListener") })
    }

    // B2：位移不足 touchSlop 仍算点击，否则"想点开菜单"会把手指抖动当成拖动
    @Test
    fun `tap is distinguished from drag by slop`() {
        val body = block("private fun setupBallDrag()", 45)
        assertTrue("必须用 scaledTouchSlop 判阈值",
            body.any { it.contains("scaledTouchSlop") } && body.any { it.contains("< slop") })
        assertTrue("未拖动时必须 performClick()，保持展开/收起菜单与无障碍点击",
            body.any { it.contains("btnBall.performClick()") })
    }

    // B3：拖拽范围钳制在父容器内，且父尺寸变化（转屏/系统栏）时也要拉回来
    @Test
    fun `ball cannot be dragged off screen`() {
        val clamp = block("private fun clampBallIntoParent()", 22)
        assertTrue("必须钳制 translationX/translationY 到父容器",
            clamp.containsAllTwo("translationX.coerceIn", "translationY.coerceIn"))
        assertTrue("父容器宽度未就绪时不得钳制（会把球弹到负坐标）",
            clamp.any { it.contains("parent.width == 0") })
        assertTrue("父容器重布局时必须重新钳制，否则转屏后球可能留在屏外",
            block("(renderer.parent as? View)?.addOnLayoutChangeListener", 4)
                .any { it.contains("clampBallIntoParent()") })
    }

    private fun List<String>.containsAllTwo(a: String, b: String) =
        any { it.contains(a) } && any { it.contains(b) }

    // B4（v0.2.65 重影/双铅笔根治的物理前提）：观看端这颗球必须是 Activity 内的 View。
    // 她的屏幕就是镜头——只要改成 WindowManager 挂出去，它就进了采集画面，
    // 共享时又会看见两个铅笔（共享端那颗已经为此改成闲置即隐形）。整文件扫描，不用行数窗口。
    @Test
    fun `viewer ball stays an in-activity view so it is never captured`() {
        val activity = source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")
            .joinToString("\n")
        val layout = source("app/src/main/res/layout/activity_session.xml").joinToString("\n")
        assertTrue("btnBall 必须写在 Activity 布局里（不被采集的唯一形态）",
            layout.contains("btnBall") || layout.contains("btn_ball"))
        assertFalse("观看端球不许改用 WindowManager 挂出（那会被采进视频=第二个铅笔）",
            Regex("""addView\s*\([^)]*(ball|Ball)""").containsMatchIn(activity))
    }
}
