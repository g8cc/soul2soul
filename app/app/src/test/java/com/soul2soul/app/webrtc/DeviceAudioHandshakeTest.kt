package com.soul2soul.app.webrtc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-16 的能力应答：aud 通道由共享端自己创建，所以"通道在"证明不了对面认得它。
 * 这一组用例锁的是**开闸依据的严格性**——参数差一项就不许起采集。
 */
class DeviceAudioHandshakeTest {

    @Test
    fun `ackJson_carriesExactCodec`() {
        val json = DeviceAudioHandshake.ackJson()
        assertEquals("audok", json.getString("k"))
        assertEquals("aac", json.getString("codec"))
        assertEquals(48_000, json.getInt("sr"))
        assertEquals(2, json.getInt("ch"))
    }

    @Test
    fun `parseAck_acceptsOnlyAac48kStereo`() {
        assertTrue(DeviceAudioHandshake.parseAck(DeviceAudioHandshake.ackJson()))
        listOf(
            JSONObject().put("k", "audok").put("codec", "opus").put("sr", 48_000).put("ch", 2),
            JSONObject().put("k", "audok").put("codec", "aac").put("sr", 44_100).put("ch", 2),
            JSONObject().put("k", "audok").put("codec", "aac").put("sr", 48_000).put("ch", 1),
            JSONObject().put("k", "audok"),
        ).forEach { rejected ->
            assertFalse("参数不匹配的 ack 必须拒收（开了就是送一路对面解不出的帧）：$rejected",
                DeviceAudioHandshake.parseAck(rejected))
        }
    }

    /** 空串/缺字段不能碰巧等于某个真实档位 */
    @Test
    fun `parseAck_rejectsDefaultsThatWouldComeFromAnOldPeer`() {
        assertFalse(DeviceAudioHandshake.parseAck(JSONObject().put("k", "audok")))
        assertFalse(DeviceAudioHandshake.parseAck(JSONObject()))
    }

    @Test
    fun `stateJson_roundTripsOnAndState`() {
        val live = DeviceAudioHandshake.stateJson(on = true, state = DeviceAudioPolicy.STATE_LIVE)
        assertEquals("aud", live.getString("k"))
        assertEquals(1, live.getInt("on"))
        assertEquals("live", live.getString("state"))
        assertEquals("new shared endpoint must advertise playback feedback support",
            1, live.getInt(DeviceAudioHandshake.CAP_PLAYBACK))
        assertTrue(DeviceAudioHandshake.playbackCapability(live))
        val off = DeviceAudioHandshake.stateJson(on = false, state = DeviceAudioPolicy.STATE_LIVE)
        assertFalse("关开关必须报 on=0（她那边横幅据此区分\"没开\"与\"开了但静音\"）",
            off.getInt("on") != 0)
    }

    @Test
    fun `playbackCapability_requiresExplicitNewPeerDeclaration`() {
        assertFalse(DeviceAudioHandshake.playbackCapability(
            JSONObject().put("k", "aud").put("on", 1).put("state", "live"),
        ))
        assertFalse(DeviceAudioHandshake.playbackCapability(
            JSONObject().put("k", "aud").put("playback", 2),
        ))
        assertFalse(DeviceAudioHandshake.playbackCapability(
            JSONObject().put("k", "other").put("playback", 1),
        ))
        assertTrue(DeviceAudioHandshake.playbackCapability(DeviceAudioHandshake.stateJson(false, "silent")))
    }

    @Test
    fun `playbackJson_reportsOnlyPlaybackFailureEdge`() {
        val failed = DeviceAudioHandshake.playbackJson(true)
        assertEquals("audplay", failed.getString("k"))
        assertEquals(1, failed.getInt("failed"))
        val recovered = DeviceAudioHandshake.playbackJson(false)
        assertEquals("audplay", recovered.getString("k"))
        assertEquals(0, recovered.getInt("failed"))
        assertEquals(true, DeviceAudioHandshake.parsePlayback(failed))
        assertEquals(false, DeviceAudioHandshake.parsePlayback(recovered))
        assertEquals(null, DeviceAudioHandshake.parsePlayback(JSONObject().put("k", "audplay")))
        assertEquals(null, DeviceAudioHandshake.parsePlayback(JSONObject().put("k", "audplay").put("failed", 2)))
    }
}
