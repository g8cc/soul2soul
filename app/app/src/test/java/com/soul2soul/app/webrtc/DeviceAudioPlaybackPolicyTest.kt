package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioPlaybackPolicyTest {
    @Test
    fun `input dequeue distinguishes retry from exception and unknown error`() {
        assertEquals(
            DeviceAudioPlaybackPolicy.Dequeue.RETRY,
            DeviceAudioPlaybackPolicy.inputDequeueResult(-1, -1),
        )
        assertEquals(
            DeviceAudioPlaybackPolicy.Dequeue.READY,
            DeviceAudioPlaybackPolicy.inputDequeueResult(0, -1),
        )
        assertEquals(
            DeviceAudioPlaybackPolicy.Dequeue.FAILURE,
            DeviceAudioPlaybackPolicy.inputDequeueResult(null, -1),
        )
        assertEquals(
            DeviceAudioPlaybackPolicy.Dequeue.FAILURE,
            DeviceAudioPlaybackPolicy.inputDequeueResult(-3, -1),
        )
    }

    @Test
    fun `output dequeue recognizes only documented events`() {
        fun result(index: Int?) = DeviceAudioPlaybackPolicy.outputDequeueResult(
            index,
            tryAgain = -1,
            formatChanged = -2,
            buffersChanged = -3,
        )
        assertEquals(DeviceAudioPlaybackPolicy.Dequeue.READY, result(2))
        assertEquals(DeviceAudioPlaybackPolicy.Dequeue.RETRY, result(-1))
        assertEquals(DeviceAudioPlaybackPolicy.Dequeue.OUTPUT_EVENT, result(-2))
        assertEquals(DeviceAudioPlaybackPolicy.Dequeue.OUTPUT_EVENT, result(-3))
        assertEquals(DeviceAudioPlaybackPolicy.Dequeue.FAILURE, result(null))
        assertEquals(DeviceAudioPlaybackPolicy.Dequeue.FAILURE, result(-4))
    }

    @Test
    fun `write must make forward progress including partial writes`() {
        assertTrue(DeviceAudioPlaybackPolicy.writeMadeProgress(1024, 1024))
        assertTrue(DeviceAudioPlaybackPolicy.writeMadeProgress(1, 1024))
        assertFalse(DeviceAudioPlaybackPolicy.writeMadeProgress(0, 1024))
        assertFalse(DeviceAudioPlaybackPolicy.writeMadeProgress(-6, 1024))
        assertFalse(DeviceAudioPlaybackPolicy.writeMadeProgress(1025, 1024))
        assertFalse("零长度请求不能把异常的正返回误判为进展",
            DeviceAudioPlaybackPolicy.writeMadeProgress(1, 0))
    }

    @Test
    fun `repeated player startup failures use bounded backoff`() {
        assertEquals(250L, DeviceAudioPlaybackPolicy.retryDelayMs(0))
        assertEquals(500L, DeviceAudioPlaybackPolicy.retryDelayMs(2))
        assertEquals(4_000L, DeviceAudioPlaybackPolicy.retryDelayMs(5))
        assertEquals(4_000L, DeviceAudioPlaybackPolicy.retryDelayMs(50))
    }
}
