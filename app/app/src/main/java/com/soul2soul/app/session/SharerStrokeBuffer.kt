package com.soul2soul.app.session

/**
 * 共享端笔迹缓冲（纯逻辑，假钟注入，JVM 可单测）。
 *
 * 为什么存在：她的屏幕就是镜头。这里每收到一个笔点就立刻落墨，观看端就会同时看见
 * "手指当前真位置的本地笔迹"和"视频里落后几百毫秒的副本"——两者一错位就是重影。
 * 所以笔点只攒不上屏，等收笔（或静默兜底）才提交成一条完整笔迹：屏幕上任何时刻
 * 都只有一份墨，而"看她等成品"是产品口径（v0.2.65）。
 */
class SharerStrokeBuffer(
    private val clock: () -> Long,
    private val silenceMs: Long = DEFAULT_SILENCE_MS,
) {

    class Pending(
        val id: String,
        val color: Int,
        val bornAt: Long,
    ) {
        /** 归一化坐标 0..1，Pair(x, y) */
        val points = mutableListOf<Pair<Float, Float>>()
        var lastPointAt = 0L
    }

    // 到达顺序 = 落墨顺序（多笔交叠时后画的压在上面，与观看端所见一致）
    private val pending = LinkedHashMap<String, Pending>()

    /** 落笔。幂等：同 id 重复"s"不产生第二条 pending */
    fun begin(id: String, color: Int): Boolean {
        if (id.isEmpty() || pending.containsKey(id)) return false
        pending[id] = Pending(id, color, clock())
        return true
    }

    /**
     * 笔点。丢了落笔"s"也认（容忍丢包通道的自愈：凭笔点现场建笔，整笔不再凭空消失）。
     * 坐标在此钳到 0..1，坏值不许把墨甩出屏幕。
     */
    fun point(id: String, x: Float, y: Float) {
        val now = clock()
        val p = pending.getOrPut(id) { Pending(id, 0, now) }
        p.points.add(x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f))
        p.lastPointAt = now
    }

    /** 收笔信号到达：取出这笔去落墨。null = 没这笔（重复收笔/迟到消息），幂等 */
    fun end(id: String): Pending? = pending.remove(id)

    /** 到龄的 pending 出仓：有点=提交落墨；只收到"s"而无点=直接丢弃（隐形内容不许永久钉住窗口） */
    fun flushDue(now: Long): List<Pending> {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList<Pending>(2)
        val iter = pending.values.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            if (deadlineOf(p) > now) continue
            iter.remove()
            if (p.points.isNotEmpty()) out.add(p)
        }
        return out
    }

    /**
     * 最早一个兜底出仓时刻，View 据此安排**一次**重绘——书写期间绝不容许逐帧空转
     * （空转 + 全屏悬浮窗 = 笔迹"一直飘"，这条坑已经踩过一次）。
     */
    fun nextDeadline(now: Long): Long? =
        pending.values.minOfOrNull { deadlineOf(it) }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun clear() = pending.clear()

    private fun deadlineOf(p: Pending): Long =
        (if (p.points.isEmpty()) p.bornAt else p.lastPointAt) + silenceMs

    companion object {
        /** 收笔信号丢失的兜底：最后笔点后静默此时长即视为已收笔 */
        const val DEFAULT_SILENCE_MS = 1200L
    }
}
