package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `aud` 线格式的字节级锁定用例。这些断言的意义不在"能不能编出字节"，而在
 * **帧长字段是 13 bit、横跨三个字节**这一处最容易写错、错了又只会表现为"对端整段解不出来"的地方。
 */
class AudioFrameCodecTest {

    @Test
    fun adtsHeader_exactBytes_48kStereo128k() {
        // 128kbps CBR 下 1024 样本 = 21.33ms ⇒ AU 341B ⇒ 含头帧长 348
        val header = AudioFrameCodec.adtsHeader(AudioFrameCodec.ADTS_HEADER_BYTES + 341)
        assertEquals(
            listOf(0xFF, 0xF1, 0x4C, 0x80, 0x2B, 0x9F, 0xFC),
            header.map { it.toInt() and 0xFF },
        )
        // 逐字段复核（字节写死了但字段口径也要能单独红：只改表不改码的情况要能被抓住）
        assertEquals(AudioFrameCodec.SAMPLING_INDEX, AudioFrameCodec.parseSamplingIndex(header))
        assertEquals(AudioFrameCodec.CHANNELS, AudioFrameCodec.parseChannels(header))
        assertEquals(348, AudioFrameCodec.parseFrameLength(header))
    }

    @Test
    fun adtsFrameLengthField_roundTrips() {
        // 13 bit 字段的全部边界：7（只有头）、2047/2048（跨 byte3→byte4 的进位）、8191（上限）
        for (frameLength in listOf(7, 8, 255, 2047, 2048, 4096, 8191)) {
            val header = AudioFrameCodec.adtsHeader(frameLength)
            assertEquals("帧长 $frameLength 回读不符", frameLength, AudioFrameCodec.parseFrameLength(header))
        }
        // 越界必须是异常，不能被截断成一个"看起来合法"的头
        runCatching { AudioFrameCodec.adtsHeader(8192) }.onSuccess {
            throw AssertionError("ADTS 帧长字段只有 13 bit，8192 必须被拒")
        }
        runCatching { AudioFrameCodec.adtsHeader(6) }.onSuccess {
            throw AssertionError("帧长必须含 7 字节头，6 是自相矛盾的头")
        }
    }

    @Test
    fun frameIs1024Samples() {
        // 这三者是**一套**：改采样率/帧长/码率中任何一个，128kbps 下的字节预算就得重算
        assertEquals(1024, AudioFrameCodec.SAMPLES_PER_FRAME)
        assertEquals(48_000, AudioFrameCodec.SAMPLE_RATE)
        assertEquals(128_000, AudioFrameCodec.BIT_RATE)
        assertEquals(341, AudioFrameCodec.EXPECTED_AU_BYTES)
        // 21.33ms/帧：这是"aud 是丢包通道也不觉得断"的前提（比视频路径的 33~66ms 密得多）
        val frameMs = AudioFrameCodec.SAMPLES_PER_FRAME * 1000.0 / AudioFrameCodec.SAMPLE_RATE
        assertTrue("一帧时长 $frameMs ms 落在 20~22ms 之外", frameMs > 20.0 && frameMs < 22.0)
    }

    @Test
    fun audioSpecificConfig_isLocalAndExact() {
        // csd-0 是本地按常量拼的，**不经通道传输** ⇒ 不存在"配置帧丢了"这种死法
        assertEquals(
            listOf(0x11, 0x90),
            AudioFrameCodec.audioSpecificConfig().map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun wrapUnwrap_roundTripAndRejectsCorruption() {
        val au = ByteArray(341) { (it % 251).toByte() }
        val packet = AudioFrameCodec.wrap(au)
        assertEquals(1 + 7 + 341, packet.size)
        assertEquals(0, packet[0].toInt())
        assertTrue(au.contentEquals(AudioFrameCodec.unwrap(packet)))

        // 残缺帧（不可靠通道上的常态）必须被拒而不是喂给解码器——半帧换来的是爆音
        assertNull(AudioFrameCodec.unwrap(packet.copyOfRange(0, packet.size - 1)))
        assertNull(AudioFrameCodec.unwrap(packet.copyOfRange(0, 4)))
        // 帧长字段被人改成不相符 ⇒ 拒（这正是保留 ADTS 头的用处：格式自带身份证）。
        // packet[5] 的 bit5 是 13 bit 帧长的最低位，翻它一位 = 声明长度与实际差 1 字节
        val tampered = packet.copyOf()
        tampered[5] = (tampered[5].toInt() xor 0x20).toByte()
        assertNull(AudioFrameCodec.unwrap(tampered))
        // 别的 codec id（将来 Opus）在旧客户端上必须解不出，而不是硬按 AAC 解出噪声
        val otherCodec = packet.copyOf()
        otherCodec[0] = 1
        assertNull(AudioFrameCodec.unwrap(otherCodec))
        // 声道数不一致也拒：两端参数漂移要落在日志上，不能悄悄变成一段变速录音
        assertNull(AudioFrameCodec.unwrap(packet, expectChannels = 1))
    }
}
