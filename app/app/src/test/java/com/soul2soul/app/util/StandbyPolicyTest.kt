package com.soul2soul.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待机探针的判定层锁定（v0.2.59）。
 *
 * 这一层决定的是"日志里到底有没有这条线"，而米15 接不了 adb ⇒ 一旦判定写错，
 * 我们只能等下一轮复现才发现（= 用户明确不再接受的"二次埋点"）。所以逐条锁死。
 */
class StandbyPolicyTest {

    // 未配对期 diag.* 会被服务器在未鉴权 socket 上丢掉；会话期已有 10s 的 qos_tick
    @Test
    fun `heartbeat only exists in paired standby`() {
        assertTrue(StandbyPolicy.shouldHeartbeat(paired = true, sessionBusy = false))
        assertFalse("未配对时发心跳=发进黑洞，还会占补报配额",
            StandbyPolicy.shouldHeartbeat(paired = false, sessionBusy = false))
        assertFalse("会话中再叠心跳是噪声，qos_tick 已经是活的证据",
            StandbyPolicy.shouldHeartbeat(paired = true, sessionBusy = true))
        assertFalse(StandbyPolicy.shouldHeartbeat(paired = false, sessionBusy = true))
    }

    @Test
    fun `probe does not double report what the sharer already reported`() {
        assertTrue("共享端会话里的 screen_off 由 ScreenShareService 报（role=sharer）",
            StandbyPolicy.skipScreenEvent(isSharerLive = true))
        assertFalse("待机与观看端会话必须仍然出数——这正是今天 0 条的那个洞",
            StandbyPolicy.skipScreenEvent(isSharerLive = false))
    }

    // 单调锚点：只有 elapsedRealtime 能用，wall-clock 相减会被设备时钟漂移（Mi5 慢 ~10.5h）彻底污染
    @Test
    fun `gap uses the monotonic clock and refuses to go backwards`() {
        assertEquals(300L, StandbyPolicy.gapSec(1000L, 1300L))
        assertEquals("本进程首条心跳没有可比对象，必须是 x 而不是一个大数",
            -1L, StandbyPolicy.gapSec(0L, 1300L))
        assertEquals("elapsedRealtime 倒退只可能是重启后另起进程，不许给出负缺口",
            -1L, StandbyPolicy.gapSec(5000L, 1300L))
    }

    @Test
    fun `gap suspect needs more than one missed period`() {
        assertFalse(StandbyPolicy.gapSuspect(300, 300))
        assertFalse("恰好错过一轮不判死（Doze 下定时器晚几秒很正常）",
            StandbyPolicy.gapSuspect(600, 300))
        assertTrue(StandbyPolicy.gapSuspect(601, 300))
        assertFalse("首条心跳（-1）不构成缺口判据", StandbyPolicy.gapSuspect(-1, 300))
    }

    @Test
    fun `bucket names match the framework constants`() {
        // 10/20/30/40/50 = ACTIVE/WORKING_SET/FREQUENT/RARE/RESTRICTED
        // 单测跑在 mockable android.jar 上，那里的 STANDBY_BUCKET_* 全被置 0，
        // 所以策略层用字面量映射；这里锁的是映射本身
        assertEquals("act", StandbyPolicy.bucketName(10))
        assertEquals("wset", StandbyPolicy.bucketName(20))
        assertEquals("freq", StandbyPolicy.bucketName(30))
        assertEquals("rare", StandbyPolicy.bucketName(40))
        assertEquals("rst", StandbyPolicy.bucketName(50))
        assertEquals("x0", StandbyPolicy.bucketName(0))
        assertEquals("bucket_rst", "bucket_" + StandbyPolicy.bucketName(50))
    }
}
