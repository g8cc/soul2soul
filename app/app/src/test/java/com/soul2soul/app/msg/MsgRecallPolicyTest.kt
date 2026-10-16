package com.soul2soul.app.msg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 撤回预检锁定：窗口边界与服务端 recallWindowMs 同规则（恰好 5 分钟可撤，过 1ms 太晚） */
class MsgRecallPolicyTest {

    private fun msg(mine: Boolean, recalled: Boolean, ts: Long) =
        Msg("id", "a", "text", "", "", 0, ts, mine = mine, recalled = recalled)

    @Test
    fun `自己发的在窗口内可撤`() {
        val m = msg(mine = true, recalled = false, ts = 1000)
        assertTrue(MsgRecallPolicy.canRecall(m, 1000 + MsgRecallPolicy.WINDOW_MS))
        assertFalse(MsgRecallPolicy.tooLate(m, 1000 + MsgRecallPolicy.WINDOW_MS))
    }

    @Test
    fun `过窗口 1 毫秒：不可撤且提示超时`() {
        val m = msg(mine = true, recalled = false, ts = 1000)
        val now = 1001 + MsgRecallPolicy.WINDOW_MS
        assertFalse(MsgRecallPolicy.canRecall(m, now))
        assertTrue(MsgRecallPolicy.tooLate(m, now))
    }

    @Test
    fun `对方消息与已墓碑消息不可撤也不提示超时`() {
        val now = 5_000L
        val peer = msg(mine = false, recalled = false, ts = 1000)
        val tomb = msg(mine = true, recalled = true, ts = 1000)
        assertFalse(MsgRecallPolicy.canRecall(peer, now))
        assertFalse(MsgRecallPolicy.tooLate(peer, now))
        assertFalse(MsgRecallPolicy.canRecall(tomb, now))
        assertFalse(MsgRecallPolicy.tooLate(tomb, now))
    }
}
