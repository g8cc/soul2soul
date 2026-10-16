package com.soul2soul.app.session

/**
 * 远程操控延迟遥测（纯逻辑，假钟注入，JVM 可单测）。
 * 一笔手势拆成三段：网络+主线程排队（recv→dispatch）、系统回放（dur）、
 * 系统排队溢出（完成时刻超出回放时长的部分——前一笔没做完就被串行化压队）。
 * Service 只负责把 Sample 写进日志，判定全在这里。
 */
class CtlLatencyTelemetry(private val clock: () -> Long) {

    data class Sample(
        val queueWaitMs: Long,   // 收到消息 → 真正调 dispatchGesture
        val totalMs: Long,       // 收到消息 → 系统回放完成（端到端）
        val playMs: Long,        // 手势本身回放时长
        val overflowMs: Long,    // total − queueWait − play：系统侧排队/注入溢出
        val inflight: Int,       // 完成时仍在系统队列里的手势数
    )

    private var inflight = 0
    private var lastLogAt = 0L

    /** dispatchGesture 已接受一笔：计数+1（与 onStrokeSettled 严格配对） */
    fun onStrokeAccepted() {
        inflight += 1
    }

    /**
     * @param recvAt 消息在 signaling 线程收到的时刻
     * @param dispatchedAt 调用 dispatchGesture 的时刻
     * @param doneAt 系统 onCompleted/onCancelled 回调时刻
     * @param playMs 手势回放时长（已钳制后的值）
     */
    fun onStrokeSettled(recvAt: Long, dispatchedAt: Long, doneAt: Long, playMs: Long): Sample {
        if (inflight > 0) inflight -= 1
        val queueWait = (dispatchedAt - recvAt).coerceAtLeast(0L)
        val total = (doneAt - recvAt).coerceAtLeast(0L)
        val overflow = (total - queueWait - playMs).coerceAtLeast(0L)
        return Sample(queueWait, total, playMs, overflow, inflight)
    }

    /** 日志节流：手势连发时别把 logcat 糊满，1s 一条即够看趋势 */
    fun shouldLog(now: Long = clock()): Boolean {
        if (now - lastLogAt < LOG_THROTTLE_MS) return false
        lastLogAt = now
        return true
    }

    /** 新会话归零：残留的 inflight 计数绝不能带进下一通 */
    fun reset() {
        inflight = 0
        lastLogAt = 0L
    }

    companion object {
        const val LOG_THROTTLE_MS = 1000L
    }
}
