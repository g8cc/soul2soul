package com.soul2soul.app.webrtc

/** MediaCodec/AudioTrack 结果分类；纯逻辑部分供 JVM 回归测试锁定。 */
internal object DeviceAudioPlaybackPolicy {
    enum class Dequeue { READY, RETRY, OUTPUT_EVENT, FAILURE }

    fun inputDequeueResult(index: Int?, tryAgain: Int): Dequeue = when {
        index == null -> Dequeue.FAILURE
        index >= 0 -> Dequeue.READY
        index == tryAgain -> Dequeue.RETRY
        else -> Dequeue.FAILURE
    }

    fun outputDequeueResult(
        index: Int?,
        tryAgain: Int,
        formatChanged: Int,
        buffersChanged: Int,
    ): Dequeue = when {
        index == null -> Dequeue.FAILURE
        index >= 0 -> Dequeue.READY
        index == tryAgain -> Dequeue.RETRY
        index == formatChanged || index == buffersChanged -> Dequeue.OUTPUT_EVENT
        else -> Dequeue.FAILURE
    }

    /** ByteBuffer 写允许部分写；返回值必须在写前请求长度范围内，拒绝 0 与错误码。 */
    fun writeMadeProgress(bytesWritten: Int, requested: Int): Boolean =
        requested > 0 && bytesWritten > 0 && bytesWritten <= requested

    /** 播放器构造 AAC decoder/AudioTrack 失败时限速重试，避免每个 21ms 媒体包都重建硬件对象。 */
    fun retryDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(4)
        return (250L shl exponent).coerceAtMost(4_000L)
    }
}
