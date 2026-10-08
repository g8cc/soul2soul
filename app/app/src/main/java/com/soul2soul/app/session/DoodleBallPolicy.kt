package com.soul2soul.app.session

/** 悬浮球拖动位置钳制：纯函数，锁定"球怎么拖都不会离开屏幕" */
internal object DoodleBallPolicy {
    fun clamp(x: Int, y: Int, size: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val maxX = (screenW - size).coerceAtLeast(0)
        val maxY = (screenH - size).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }
}
