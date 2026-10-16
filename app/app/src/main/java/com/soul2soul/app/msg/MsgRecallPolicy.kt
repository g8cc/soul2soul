package com.soul2soul.app.msg

/** 撤回资格判定（与服务端 msg.recall 的 5 分钟窗口同规则；客户端预检只为少跑一趟） */
object MsgRecallPolicy {
    const val WINDOW_MS = 5 * 60 * 1000L

    fun canRecall(m: Msg, now: Long): Boolean =
        m.mine && !m.recalled && now - m.ts <= WINDOW_MS

    /** 超窗点击撤回时的提示选择（不直接发请求，省一次失败往返） */
    fun tooLate(m: Msg, now: Long): Boolean = m.mine && !m.recalled && now - m.ts > WINDOW_MS
}
