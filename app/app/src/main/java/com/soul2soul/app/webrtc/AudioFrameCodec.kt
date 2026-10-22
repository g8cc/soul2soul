package com.soul2soul.app.webrtc

/**
 * `aud` 通道的线格式（纯字节，JVM 可单测）：`[1 字节 codec id][7 字节 ADTS 头][AAC AU]`。
 *
 * 参数是**定值**，不是协商出来的：AAC-LC / 48000Hz / 立体声 / 1024 样本=21.33ms / CBR 128kbps。
 * 用 CBR 而不用 VBR 的理由：VBR 的突发会和 SCTP 的排队打架（不可靠通道上没有"等一等"这回事，
 * 只有"迟到所以被扔"）；128kbps 对 4Mbps 视频只占 ~3%，换不来画质却换来抖动。
 *
 * 为什么保留 ADTS 头（尽管解码器用的 csd-0 是本地按常量拼的、根本不会丢）：
 * 头里的帧长/采样率/声道数是一路**自证**——对端解开时对一下，不匹配就说明两端参数漂移了，
 * 这种"格式自己带着身份证"比只传裸 AU 多一条能落到日志上的判据。
 * 首字节 codec id（0=aac-adts）是为了将来上 Opus 时是**新值**而不是新框架。
 */
object AudioFrameCodec {

    const val CODEC_AAC_ADTS = 0
    const val CODEC_ID_BYTES = 1
    const val ADTS_HEADER_BYTES = 7

    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val SAMPLES_PER_FRAME = 1024
    const val BIT_RATE = 128_000

    /** 采样率索引 3 = 48kHz（ISO/IEC 14496-3 Table 1.21：0=96k 1=88.2k 2=64k **3=48k** 4=44.1k）。 */
    const val SAMPLING_INDEX = 3

    /** ADTS 的 profile 字段 = audioObjectType − 1；ASC 里传的是 objectType 本身，两者别混。 */
    const val OBJECT_TYPE_AAC_LC = 2
    const val PROFILE_AAC_LC = OBJECT_TYPE_AAC_LC - 1

    /** 一帧标称字节数：128000bit/s ÷ 8 × (1024/48000 s) = 341.33 ⇒ AU 341B + 头 7B + id 1B ≈ 140kbps。 */
    const val EXPECTED_AU_BYTES = 341

    /** AAC 单帧 AU 的规范上限（max_frame_len − 头），超过它的"帧"一定不是合法 AAC。 */
    const val MAX_AU_BYTES = 768

    /** ADTS 里 number_of_raw_data_blocks=0 ⇒ 1 个 block；buffer_fullness 取 0x1FFF（未知/不限）。 */
    private const val BUFFER_FULLNESS = 0x1FFF

    /**
     * 构造 ADTS 头。`frameLength` 是**含头**的整帧字节数（规范就是这么定义这个 13 bit 字段的），
     * 传裸 AU 长度会得到一个自相矛盾的头——所以这里强制校验下界。
     */
    fun adtsHeader(frameLength: Int, samplingIndex: Int = SAMPLING_INDEX, channels: Int = CHANNELS): ByteArray {
        require(frameLength in ADTS_HEADER_BYTES..0x1FFF) { "ADTS 帧长（含头）越界：$frameLength" }
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),  // MPEG-4 / layer 0 / protection_absent=1（无 CRC ⇒ 头是 7 字节不是 9）
            ((PROFILE_AAC_LC shl 6) or (samplingIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 0x03) shl 6) or (frameLength shr 11)).toByte(),
            ((frameLength and 0x07FF) shr 3).toByte(),
            ((((frameLength and 0x0007) shl 5) or ((BUFFER_FULLNESS shr 6) and 0x1F))).toByte(),
            (((BUFFER_FULLNESS and 0x3F) shl 2)).toByte(),  // 低 2 位 = raw block 数 − 1 = 0
        )
    }

    /** 从 ADTS 头读回含头的帧长（13 bit，横跨 byte3/4/5）。 */
    fun parseFrameLength(header: ByteArray): Int {
        require(header.size >= ADTS_HEADER_BYTES) { "不是完整 ADTS 头：${header.size}" }
        return ((header[3].toInt() and 0x07) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xFF) shr 5)
    }

    fun parseSamplingIndex(header: ByteArray): Int = (header[2].toInt() shr 2) and 0x07

    fun parseChannels(header: ByteArray): Int = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() shr 6) and 0x03)

    /**
     * csd-0 = AudioSpecificConfig（2 字节，本地按常量拼，**不经通道传输**）。
     * AAC-LC + 48kHz + 立体声 ⇒ `0x11 0x90`（bit 流：objectType5 | freqIdx4 | chanCfg4 | 3 bit 未用）。
     * 之所以不靠通道传配置帧：`aud` 是不可靠通道，"唯一一次 csd-0 可能丢"这种设计本来就是错的——
     * 能本地推出来的东西别指望它一次送达。
     */
    fun audioSpecificConfig(
        objectType: Int = OBJECT_TYPE_AAC_LC,
        samplingIndex: Int = SAMPLING_INDEX,
        channels: Int = CHANNELS,
    ): ByteArray = byteArrayOf(
        ((objectType shl 3) or (samplingIndex shr 1)).toByte(),
        (((samplingIndex and 0x01) shl 7) or (channels shl 3)).toByte(),
    )

    /** 打包成线格式：`[id][adts][au]`。 */
    fun wrap(au: ByteArray, samplingIndex: Int = SAMPLING_INDEX, channels: Int = CHANNELS): ByteArray {
        require(au.size in 1..MAX_AU_BYTES) { "AU 长度非法：${au.size}" }
        val frameLength = ADTS_HEADER_BYTES + au.size
        val out = ByteArray(CODEC_ID_BYTES + ADTS_HEADER_BYTES + au.size)
        out[0] = CODEC_AAC_ADTS.toByte()
        adtsHeader(frameLength, samplingIndex, channels).copyInto(out, CODEC_ID_BYTES)
        au.copyInto(out, CODEC_ID_BYTES + ADTS_HEADER_BYTES)
        return out
    }

    /**
     * 解包并**校验**（返回裸 AU），不匹配一律返回 null 让调用方丢帧：
     * 不可靠通道上残缺的半帧喂给解码器，换来的是爆音而不是"少一句"。
     */
    fun unwrap(packet: ByteArray, expectChannels: Int = CHANNELS): ByteArray? {
        if (packet.size < CODEC_ID_BYTES + ADTS_HEADER_BYTES) return null
        if (packet[0].toInt() != CODEC_AAC_ADTS) return null
        val header = packet.copyOfRange(CODEC_ID_BYTES, CODEC_ID_BYTES + ADTS_HEADER_BYTES)
        if (header[0].toInt() and 0xFF != 0xFF || header[1].toInt() and 0xF0 != 0xF0) return null
        val frameLength = parseFrameLength(header)
        if (frameLength != packet.size - CODEC_ID_BYTES) return null
        if (parseSamplingIndex(header) != SAMPLING_INDEX) return null
        if (parseChannels(header) != expectChannels) return null
        return packet.copyOfRange(CODEC_ID_BYTES + ADTS_HEADER_BYTES, packet.size)
    }
}
