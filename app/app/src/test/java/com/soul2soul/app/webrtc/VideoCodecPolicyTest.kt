package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecPolicyTest {
    @Test
    fun `硬件 VP9 优先于硬件 H264`() {
        val selection = VideoCodecPolicy.select(
            allCodecNames = listOf("VP8", "H264", "VP9"),
            hardwareCodecNames = listOf("H264", "VP9"),
        )

        assertEquals(VideoCodecPolicy.VP9, selection.preferredName)
        assertTrue(selection.preferredHardware)
        assertEquals(listOf("VP9", "H264", "VP8"), selection.orderedNames)
    }

    @Test
    fun `无硬件编码器时不强行选择软件 VP9`() {
        val selection = VideoCodecPolicy.select(
            allCodecNames = listOf("VP8", "VP9"),
            hardwareCodecNames = emptyList(),
        )

        assertEquals(null, selection.preferredName)
        assertFalse(selection.preferredHardware)
        assertEquals(listOf("VP9", "VP8"), selection.orderedNames)
    }

    @Test
    fun `编码器名称规范化并去重`() {
        val selection = VideoCodecPolicy.select(
            allCodecNames = listOf("video/VP8", "H264", "video/VP8"),
            hardwareCodecNames = listOf("h264", "AV1"),
        )

        assertEquals(VideoCodecPolicy.H264, selection.preferredName)
        assertEquals(listOf("H264", "VP8"), selection.orderedNames)
        assertEquals(listOf("H264"), selection.hardwareNames)
    }

    @Test
    fun `未知硬件 codec 不强行改写屏幕共享首选`() {
        val selection = VideoCodecPolicy.select(
            allCodecNames = listOf("AV1", "VP8"),
            hardwareCodecNames = listOf("AV1"),
        )

        assertEquals(null, selection.preferredName)
        assertFalse(selection.preferredHardware)
        assertEquals(listOf("VP8", "AV1"), selection.orderedNames)
    }
}
