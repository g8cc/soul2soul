package com.soul2soul.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `echo_ack` 账本回归（v0.2.65 交棒遥测）。
 * 这一列量的是"我收笔 → 她画布落墨"这一跳，ack 走的是容忍丢包且**不保序**的 anno 通道，
 * 所以乱序、重复、永远不来都是正常输入而不是异常——账本必须在这些输入下仍然给出可核对的数字。
 * 假钟驱动，无 Android 依赖。
 */
class EchoLatencyTest {

    private val ledger = EchoLatency { 0L }

    // ---------- 基本测量 ----------

    @Test
    fun ack_minusPenUp_isTheSample() {
        ledger.onStrokeSent("a1", 2000)
        val s = ledger.onAck("a1", 2300)
        assertNotNull(s)
        assertEquals(300L, s!!.upMs)
        assertEquals("e=300,x=300,m=0,p=0", ledger.detail())
    }

    @Test
    fun negativeAndOversizedDiffsAreClampedNotCredited() {
        // 同设备 uptimeMillis 不会倒流，但时钟注入方一旦被换就会；负数写进 detail 会变成
        // `e=-5`，safeTag 磨平后与缺格无法区分 ⇒ 下钳到 0
        ledger.onStrokeSent("a1", 5000)
        assertEquals(0L, ledger.onAck("a1", 4000)!!.upMs)
        // 上钳到 MAX_MS：弱网+进程被冻一整晚再复活，不该报出 8 位数的"延迟"
        ledger.onStrokeSent("a2", 6000)
        assertEquals(
            EchoLatency.MAX_MS,
            ledger.onAck("a2", 6000 + EchoLatency.MAX_MS + 999_999L)!!.upMs
        )
        assertEquals("e=9999,x=9999,m=0,p=0", ledger.detail())
    }

    // ---------- 丢包通道的三种残缺 ----------

    @Test
    fun outOfOrderAcksMatchById() {
        ledger.onStrokeSent("first", 2000)
        ledger.onStrokeSent("second", 2100)
        // anno 不保序：第二笔的 ack 先到
        assertEquals(500L, ledger.onAck("second", 2600)!!.upMs)
        assertEquals(1000L, ledger.onAck("first", 3000)!!.upMs)
        assertEquals("e=1000,x=1000,m=0,p=0", ledger.detail())
    }

    @Test
    fun duplicateOrUnknownAckYieldsNoSample() {
        ledger.onStrokeSent("a1", 2000)
        assertNotNull(ledger.onAck("a1", 2100))
        // 收笔在 300ms 处幂等补发 ⇒ ack 也可能来两次
        assertNull("重复 ack 只算第一次", ledger.onAck("a1", 2200))
        assertNull("配不上的 id 不许造出样本", ledger.onAck("nope", 2300))
        assertEquals("e=100,x=100,m=0,p=0", ledger.detail())
    }

    @Test
    fun penUpResendDoesNotMoveTheBaseline() {
        // `e` 会补发一次；若第二次登记覆盖了首次时刻，样本会被系统性读小——
        // 那正是这条遥测唯一要防的读反
        ledger.onStrokeSent("a1", 2000)
        ledger.onStrokeSent("a1", 9000)
        assertEquals(100L, ledger.onAck("a1", 2100)!!.upMs)
    }

    @Test
    fun resendAfterAckDoesNotReopenTheEntry() {
        // v0.2.65 真机（双模拟器，20261020-182939）实测：p 每画一笔 +1、m 恒 0，
        // 而 e 每笔都是新鲜值。根因＝ack 已经把条目 remove 掉，300ms 后的补发 `e`
        // 在 putIfAbsent 上"重新登记成功"，而共享端那一笔早已提交（end 幂等返回 null，
        // 不会再回第二个 ack）⇒ 这一条永远配不上。遥测自己造出积压，
        // 且到 32 条后会把真正还在等的笔迹挤出账本
        ledger.onStrokeSent("a1", 2000)
        assertNotNull(ledger.onAck("a1", 2100))
        ledger.onStrokeSent("a1", 2300) // 补发落在 ack 之后
        assertEquals("补发不许把已交棒的笔迹重新算成在等：" + ledger.detail(),
            "e=100,x=100,m=0,p=0", ledger.detail())
        assertNull("补发也配不出第二个样本", ledger.onAck("a1", 2400))
        assertEquals("e=100,x=100,m=0,p=0", ledger.detail())
    }

    @Test
    fun consecutiveStrokesLeaveNothingWaiting() {
        // 连画 40 笔（>CAP）：真实链路每笔都回了 ack 的话，p 与 m 都必须停在 0。
        // 这条是上一条的规模化版本——旧实现到这里会报 p=32、m=8（假积压撑爆账本）
        for (i in 1..40) {
            val id = "s$i"
            ledger.onStrokeSent(id, 2000L + i * 1000)
            assertNotNull(ledger.onAck(id, 2100L + i * 1000))
            ledger.onStrokeSent(id, 2300L + i * 1000) // 补发
        }
        val d = ledger.detail()
        assertTrue("已 ack 的笔迹不许堆进在等：$d", d.contains("p=0"))
        assertTrue("已 ack 的笔迹不许算成配不上：$d", d.contains("m=0"))
    }

    @Test
    fun emptyIdIsIgnoredBothWays() {
        ledger.onStrokeSent("", 2000)
        assertNull(ledger.onAck("", 2100))
        assertEquals("e=0,x=0,m=0,p=0", ledger.detail())
    }

    // ---------- 有界账本：超限必须计入 miss，不许悄悄缩小样本集 ----------

    @Test
    fun overflowCountsMiss() {
        for (i in 0 until EchoLatency.CAP + 5) {
            ledger.onStrokeSent("id$i", 2000L + i)
        }
        val d = ledger.detail()
        assertTrue("溢出 5 条必须数出来：$d", d.contains("m=5"))
        assertTrue("还在等的=${EchoLatency.CAP}：$d", d.contains("p=${EchoLatency.CAP}"))
        // 被淘汰的那几条（最旧）之后再来 ack，只会得到 null，不会补出一个样本
        assertNull(ledger.onAck("id0", 9000))
    }

    @Test
    fun detailStaysWithinProtocolCap() {
        for (i in 0 until EchoLatency.CAP + 100) ledger.onStrokeSent("id$i", 2000L + i)
        ledger.onStrokeSent("x", 2000)
        ledger.onAck("x", 2000 + EchoLatency.MAX_MS)
        val d = ledger.detail()
        assertTrue("detail 必须 ≤64（服务器那一列的宽度）：$d", d.length <= EchoLatency.DETAIL_MAX)
    }

    // ---------- 节流与复位 ----------

    @Test
    fun throttleBoundaries_4999_5000_5001() {
        assertTrue("fresh 账本的第一条必出（不许被当成「刚报过」而哑掉）", ledger.shouldLog(0))
        assertFalse("4999 还在窗口内", ledger.shouldLog(4999))
        assertTrue("恰 5000 出", ledger.shouldLog(5000))
        assertFalse("5001 距上一条只差 1ms", ledger.shouldLog(5001))
    }

    @Test
    fun resetLeavesNothingForNextCall() {
        for (i in 0 until EchoLatency.CAP + 3) ledger.onStrokeSent("id$i", 2000L + i)
        ledger.onStrokeSent("z", 2000)
        ledger.onAck("z", 2500)
        ledger.reset()
        assertEquals("e=0,x=0,m=0,p=0", ledger.detail())
        assertTrue("reset 后第一条必须能出", ledger.shouldLog(0))
    }
}
