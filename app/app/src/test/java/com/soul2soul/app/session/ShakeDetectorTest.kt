package com.soul2soul.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 摇一摇入口判定锁定用例（v0.2.28 双向涂鸦入口）：换向达标才触发，冷却期不连发 */
class ShakeDetectorTest {

    private fun fakeClock(): Pair<() -> Long, (Long) -> Unit> {
        var t = 0L
        return { t } to { t = it }
    }

    @Test
    fun `往复摇动在窗口期内达标即触发`() {
        val (clock, setTime) = fakeClock()
        val d = ShakeDetector(clock)
        var fired = false
        // 3 次换向（+,-,+），每 100ms 一个尖峰，总时长 200ms < 600ms 窗口
        val samples = listOf(Triple(20f, 0f, 0f), Triple(-20f, 0f, 0f), Triple(20f, 0f, 0f))
        for (s in samples) {
            setTime(clock() + 100)
            fired = d.onSample(s.first, s.second, s.third) || fired
        }
        assertTrue(fired)
    }

    @Test
    fun `单次磕碰不触发`() {
        val (clock, setTime) = fakeClock()
        val d = ShakeDetector(clock)
        setTime(100)
        assertFalse(d.onSample(20f, 0f, 0f))
        // 之后静止（重力大小 9.8 在阈值下）
        for (i in 0 until 10) {
            setTime(clock() + 50)
            assertFalse(d.onSample(0f, 0f, 9.8f))
        }
    }

    @Test
    fun `换向间隔超窗则重新计数不触发`() {
        val (clock, setTime) = fakeClock()
        val d = ShakeDetector(clock, windowMs = 300)
        setTime(400)
        assertFalse(d.onSample(20f, 0f, 0f))
        setTime(clock() + 400)
        assertFalse(d.onSample(-20f, 0f, 0f))
        setTime(clock() + 400)
        assertFalse(d.onSample(20f, 0f, 0f))
    }

    @Test
    fun `触发后冷却期内继续摇不再连发`() {
        val (clock, setTime) = fakeClock()
        val d = ShakeDetector(clock, cooldownMs = 1500)
        var firedCount = 0
        // 持续猛摇 2 秒：只允许在冷却边界各触发一次
        var sign = 1
        for (i in 0 until 20) {
            setTime(clock() + 100)
            val x = 20f * sign
            sign = -sign
            if (d.onSample(x, 0f, 0f)) firedCount++
            if (d.onSample(-x, 0f, 0f)) firedCount++
        }
        assertTrue(firedCount >= 1)
        assertTrue("冷却期内不得连发: $firedCount", firedCount <= 2)
    }
}
