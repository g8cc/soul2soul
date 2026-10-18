package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话时长记账锁定（v0.2.58）：0.2.55/0.2.56 生产日志里 liveMs 全部失真——
 * ICE 闪断把 connectedSinceMs 清零，随后 close 路径再算时长就只能得到 0，
 * "聊了 18.9 分钟被自己的看门狗拆掉"（1134232ms 那条）与"根本没连上"在日志里同形。
 * 下面每条都是那个缺陷的一种形状，回滚 SessionTiming 即红。
 */
class SessionTimingTest {

    // T1：从未连通——setupMs 必须是 -1（不能拿会话年龄冒充建链耗时）
    @Test
    fun `never connected reports -1 setup instead of session age`() {
        assertEquals(-1L, SessionTiming.setupMs(startedAtMs = 1000L, connectedAtMs = 0L))
        assertEquals(0L, SessionTiming.liveMs(connectedAtMs = 0L, lostAtMs = 0L, nowMs = 99_000L))
    }

    // T2：连上后断了——liveMs 停在断开那一刻，不受"什么时候才上报"影响
    @Test
    fun `live duration stops at the outage instant not at report time`() {
        // 生产样本：1.2s 起呼、2.4s 连通、活了 18.9 分钟后 ICE 掉、20 分钟才走到 close
        val started = 1_200L
        val connected = 2_400L
        val lost = connected + 1_134_232L
        val reportAt = 1_250_000L
        assertEquals(1200L, SessionTiming.setupMs(started, connected))
        assertEquals(1_134_232L, SessionTiming.liveMs(connected, lost, reportAt))
    }

    // T3：闪断后又恢复——恢复侧把 lostAt 清零，时长继续算到 now，首次连通时刻不许被抹掉
    @Test
    fun `recovered outage keeps the original connected instant`() {
        val connected = 5000L
        // 断了 2 秒又活过来：mediaLostAtMs 恢复时归零
        assertEquals(60_000L, SessionTiming.liveMs(connected, lostAtMs = 0L, nowMs = 65_000L))
        // 再次断掉：时长停在第二次失去连通的时刻
        assertEquals(62_000L, SessionTiming.liveMs(connected, lostAtMs = 67_000L, nowMs = 90_000L))
    }

    // T4：时钟倒退等异常不能产出负数
    @Test
    fun `live duration never goes negative`() {
        assertEquals(0L, SessionTiming.liveMs(connectedAtMs = 9000L, lostAtMs = 8000L, nowMs = 8500L))
    }

    // T5：setupMs 的 -1 语义不能退化成 0（0 会被读成"秒连"）
    @Test
    fun `missing start clock also reports -1`() {
        assertEquals(-1L, SessionTiming.setupMs(startedAtMs = 0L, connectedAtMs = 5000L))
    }
}
