package com.soul2soul.app.session

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 摇一摇判定（纯逻辑，时钟可注入，JVM 可单测）。
 * 为什么走摇动而不是悬浮球/通知栏：共享端的屏幕本身就是视频——悬浮球会被对方
 * 全程看见；老 MIUI 的通知面板不是安全窗口，下拉入口会连通知内容一起泄进画面。
 * 摇动是唯一"本机可感、采集不可见"的入口。
 *
 * 判定：以主导轴符号翻转计一次"换向"，窗口期内换向次数达标才算摇（放桌上磕一下
 * 只有一两个尖峰，不会触发）；触发后进冷却期，防止一次摇晃连开带关。
 */
class ShakeDetector(
    private val clock: () -> Long,
    private val threshold: Float = 14f,
    private val minReversals: Int = 3,
    private val windowMs: Long = 600L,
    private val cooldownMs: Long = 1500L,
) {
    // 不能用 Long.MIN_VALUE：now - lastFireAt 会溢出成负数，冷却判定永远为真
    private var lastFireAt = Long.MIN_VALUE / 2
    private var lastSign = 0
    private var reversals = 0
    private var windowStart = 0L

    /** @return true = 本次采样让这一下"摇"成立了 */
    fun onSample(x: Float, y: Float, z: Float): Boolean {
        val now = clock()
        if (now - lastFireAt < cooldownMs) return false
        val m = sqrt(x * x + y * y + z * z)
        if (m <= threshold) return false
        val dominant = if (abs(x) >= abs(y) && abs(x) >= abs(z)) x
        else if (abs(y) >= abs(z)) y else z
        val sign = if (dominant > 0) 1 else -1
        if (sign == lastSign) return false
        lastSign = sign
        if (reversals == 0 || now - windowStart > windowMs) {
            reversals = 1
            windowStart = now
        } else {
            reversals++
        }
        if (reversals >= minReversals) {
            lastFireAt = now
            reversals = 0
            return true
        }
        return false
    }
}
