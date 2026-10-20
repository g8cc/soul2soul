package com.soul2soul.app.session

/**
 * 交棒延迟账本（纯逻辑，假钟注入，JVM 可单测）。
 *
 * 量的是"观看端收笔 → 共享端把这一笔落墨并回 ack"这一跳，**不含**采集/编码/网络/解码/渲染，
 * 所以叫 echo_ack 而不是回显总延迟。它的用途有二：给观看端的交棒淡出时长一个下界校验
 * （ack 都到不了，视频更到不了），以及把"她等成品"这条产品口径的实际代价变成有读数的东西。
 *
 * ack 走的是容忍丢包的 anno 通道（与笔迹同一条队列，要测的就是这条路的排队+往返），
 * 所以账本必须能处理三种残缺：ack 永远不来、ack 迟到、同一 id 重复 ack（收笔补发）。
 * 两个时刻都取自观看端自己的 uptimeMillis，同一台设备可直接相减，不需要时钟同步。
 */
class EchoLatency(private val clock: () -> Long) {

    data class Sample(val upMs: Long)

    /** id → 收笔时刻；LinkedHashMap 保序＝淘汰时丢的就是最旧那条 */
    private val sent = LinkedHashMap<String, Long>()
    /**
     * 已经配过对的 id（有界，只防 300ms 补发）。
     * 少了这一张表：ack 到达时条目已被 remove，补发的那一条 `e` 会在 putIfAbsent 上"重新登记成功"，
     * 而共享端那一侧早已提交（end 幂等返回 null，不会再回 ack）⇒ 这笔永远配不上，
     * p 每画一笔 +1、到 32 条后开始把真正还在等的笔迹挤出账本——遥测自己造出积压。
     */
    private val acked = LinkedHashSet<String>()
    private var lastMs = -1L
    private var maxMs = -1L
    private var miss = 0
    // 哨兵取"窗口刚好过"：fresh 账本的第一条样本必须能出，不许被当成"刚报过"而哑掉
    private var lastLogAt = -LOG_THROTTLE_MS

    /** 收笔时登记。重复 id（300ms 补发的那一条）不覆盖首次时刻——首尾差会把样本读小 */
    fun onStrokeSent(id: String, penUpAt: Long = clock()) {
        if (id.isEmpty() || id in acked) return
        if (sent.putIfAbsent(id, penUpAt) != null) return
        while (sent.size > CAP) {
            val oldest = sent.keys.first()
            sent.remove(oldest)
            miss += 1 // 账本装不下＝这一条永远配不上了，必须计入，不许悄悄缩小样本集
        }
    }

    /** @return 配上了才给样本；重复 ack 幂等（只算第一次），配不上的 id（已被淘汰/非本端笔迹）直接丢弃 */
    fun onAck(id: String, at: Long = clock()): Sample? {
        val penUpAt = sent.remove(id) ?: return null
        acked.add(id)
        while (acked.size > CAP) acked.remove(acked.iterator().next())
        val upMs = (at - penUpAt).coerceAtLeast(0L).coerceAtMost(MAX_MS)
        lastMs = upMs
        if (upMs > maxMs) maxMs = upMs
        return Sample(upMs)
    }

    /** 节流：一笔一条会把 P1 配额灌满，p50/p90 由整条序列给出，单条丢掉不丢结论 */
    fun shouldLog(now: Long = clock()): Boolean {
        if (now - lastLogAt < LOG_THROTTLE_MS) return false
        lastLogAt = now
        return true
    }

    /** diag.detail：e=最近一次、x=本轮最大、m=配不上数、p=还在等 */
    fun detail(): String =
        "e=${clamped(lastMs)},x=${clamped(maxMs)},m=$miss,p=${sent.size}".take(DETAIL_MAX)

    fun reset() {
        sent.clear()
        acked.clear()
        lastMs = -1L
        maxMs = -1L
        miss = 0
        lastLogAt = -LOG_THROTTLE_MS
    }

    private fun clamped(v: Long) = if (v < 0L) 0L else v.coerceAtMost(MAX_MS)

    companion object {
        const val CAP = 32
        const val LOG_THROTTLE_MS = 5000L
        const val MAX_MS = 9999L
        const val DETAIL_MAX = 64
    }
}
