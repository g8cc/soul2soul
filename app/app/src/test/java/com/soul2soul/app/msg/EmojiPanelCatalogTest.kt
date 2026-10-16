package com.soul2soul.app.msg

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 留言表情面板清单的机器锁（v0.2.48）：
 * 1) 已知 Android 7（Mi5 NotoColorEmoji cmap 实测缺字形）码位禁入任何表情数组——混进去
 *    老机型就是方框（v0.2.43 真机踩坑，🥰🥺🫶 那批）；
 * 2) 文本呈现默认码位（❤☀❄✌❣）必须带 U+FE0F 变体选择符，否则部分系统显示黑白字符。
 * 加新表情跑不过这两条=老机型用户看到方框/黑白字，直接红。
 */
class EmojiPanelCatalogTest {

    private val banned = listOf("\uD83E\uDD70", "\uD83E\uDD7A", "\uD83E\uDEF6", "\uD83E\uDEC2", "\uD83E\uDDE1") // 🥰🥺🫶🫂🧡
    private val textDefault = listOf("\u2764", "\u2600", "\u2744", "\u270C", "\u2763", "\u261D") // ❤☀❄✌❣☝

    private fun emojiArrays(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/res/values/strings.xml")
            if (f.exists()) {
                val src = f.readText()
                return Regex(
                    "<string-array name=\"(?:msg_emoji_faces|msg_emoji_hands|msg_emoji_hearts|msg_emoji_misc|call_emojis)\"[^>]*>(.*?)</string-array>",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(src).map { m ->
                    Regex("<item>(.*?)</item>").findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] }
                }.toList()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 strings.xml（工作目录: ${File("").absolutePath}）")
    }

    @Test
    fun `no glyphs missing on android 7 font`() {
        val all = emojiArrays()
        assertTrue("表情数组解析为空？", all.isNotEmpty())
        for (b in banned) {
            assertTrue("Android7 缺字形 $b 混进了表情数组（Mi5 cmap 实测方框）",
                all.none { it.contains(b) })
        }
    }

    @Test
    fun `text-default codepoints carry variation selector`() {
        val all = emojiArrays()
        for (t in textDefault) {
            assertTrue("文本默认码位 $t 必须带 FE0F（黑白字符回退）",
                all.none { s -> Regex(t + "(?!\uFE0F)").containsMatchIn(s) })
        }
    }

    @Test
    fun `four groups exist`() {
        val names = listOf("msg_emoji_faces", "msg_emoji_hands", "msg_emoji_hearts", "msg_emoji_misc")
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/res/values/strings.xml")
            if (f.exists()) {
                val src = f.readText()
                for (n in names) assertTrue("缺分组数组 $n", src.contains("name=\"$n\""))
                return
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 strings.xml")
    }
}
