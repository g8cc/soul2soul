package com.soul2soul.app.session

import com.soul2soul.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 共享中通知正文的优先级（v0.2.64 新增"画面已停"这一档后提取成纯逻辑）。
 * 通知是她在锁屏后唯一看得见的东西，这一格写错就是"状态条说陪玩中、画面其实早死了"。
 */
class ShareNoticePolicyTest {

    @Test
    fun `画面已停压过其余一切状态`() {
        // 四个组合都要赢：静音/无麦/两者兼具时，唯一能补救的动作仍然是"点这里重新开启"
        assertEquals(R.string.sharing_video_paused, ShareNoticePolicy.statusTextRes(noMic = false, muted = false, videoPaused = true))
        assertEquals(R.string.sharing_video_paused, ShareNoticePolicy.statusTextRes(noMic = true, muted = false, videoPaused = true))
        assertEquals(R.string.sharing_video_paused, ShareNoticePolicy.statusTextRes(noMic = false, muted = true, videoPaused = true))
        assertEquals(R.string.sharing_video_paused, ShareNoticePolicy.statusTextRes(noMic = true, muted = true, videoPaused = true))
    }

    @Test
    fun `无麦克风权限先于静音：根本没有上行音频，说静音是误导`() {
        assertEquals(R.string.sharing_no_mic, ShareNoticePolicy.statusTextRes(noMic = true, muted = false, videoPaused = false))
        assertEquals(R.string.sharing_no_mic, ShareNoticePolicy.statusTextRes(noMic = true, muted = true, videoPaused = false))
    }

    @Test
    fun `有麦且未暂停时静音才说得出"对方听不到你"`() {
        assertEquals(R.string.sharing_muted, ShareNoticePolicy.statusTextRes(noMic = false, muted = true, videoPaused = false))
    }

    @Test
    fun `三态全否才是正常的陪玩中`() {
        assertEquals(R.string.sharing_live, ShareNoticePolicy.statusTextRes(noMic = false, muted = false, videoPaused = false))
    }

    @Test
    fun `四个状态必须给出四条互不相同的文案`() {
        val texts = listOf(
            ShareNoticePolicy.statusTextRes(noMic = false, muted = false, videoPaused = false),
            ShareNoticePolicy.statusTextRes(noMic = false, muted = true, videoPaused = false),
            ShareNoticePolicy.statusTextRes(noMic = true, muted = false, videoPaused = false),
            ShareNoticePolicy.statusTextRes(noMic = false, muted = false, videoPaused = true),
        )
        assertEquals("文案撞车就意味着有一格根本没在说它声称的事", 4, texts.toSet().size)
    }
}
