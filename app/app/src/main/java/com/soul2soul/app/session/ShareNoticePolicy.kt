package com.soul2soul.app.session

import com.soul2soul.app.R

/**
 * 共享中通知正文的优先级（纯逻辑，JVM 可单测）。
 *
 * 画面已停排第一：那是她此刻唯一能补救的事，其余三条都只是"当前状态描述"。
 * 这一格特意不写"语音继续"——无麦克风权限时根本没有上行音频，说了就是谎。
 */
object ShareNoticePolicy {
    fun statusTextRes(noMic: Boolean, muted: Boolean, videoPaused: Boolean): Int = when {
        videoPaused -> R.string.sharing_video_paused
        noMic -> R.string.sharing_no_mic
        muted -> R.string.sharing_muted
        else -> R.string.sharing_live
    }
}
