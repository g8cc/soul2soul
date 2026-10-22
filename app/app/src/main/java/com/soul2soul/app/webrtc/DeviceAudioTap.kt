package com.soul2soul.app.webrtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import java.nio.ByteOrder

/**
 * 共享端设备内录（FR-16）：`AudioPlaybackCapture` → AAC-LC 编码 → `aud` 二进制通道。
 *
 * 这是**独立于 libwebrtc 语音路**的第二条通路——不是没试过往语音轨里灌：
 * stream-webrtc-android 1.1.1 的 `AudioSource`/`JavaAudioDeviceModule` 从 Java 侧拿不到
 * 自定义 `AudioRecord` 注入口（javap 实核），所以只能另开一条通道，两路声音在手机音频
 * 混合器里自然叠加成"她同时听到视频声 + 我的声音"。
 *
 * 生命周期纪律（AGENTS「hideX 必穷举 restoreX」）：**[stop] 与线程自杀都收口到同一个出口**
 * ——`running=false` ⇒ 循环 finally 释放 + 发一条 `aud_stopped r=<出口名>`。上层任何一处
 * "以为停了但没走这里"都会留下一个还在烧 140kbps 上行的僵尸线程。
 */
class DeviceAudioTap(
    private val projectionOf: () -> MediaProjection?,
    /** 线格式帧出口（[WebRtcClient.sendDeviceAudioFrame]）；false = 这一帧被丢掉（通道没开或背压） */
    private val sink: (ByteArray) -> Boolean,
    /** SCTP 当前积压字节，只进遥测 `q_`，不参与判定 */
    private val bufferedBytesOf: () -> Int,
    /** 遥测出口（宿主接 `SelfCheck.reportEvent`） */
    private val onEvent: (name: String, detail: String) -> Unit,
) {

    /** 七条出口 + 一条重绑：`aud_stopped` 的 detail 只能出自这份枚举。 */
    enum class Exit(val tag: String) {
        TOGGLE_OFF("toggle_off"),
        PROJECTION_STOPPED("projection_stopped"),
        SESSION_CLOSE("session_close"),
        CHANNEL_CLOSED("channel_closed"),
        RECORD_ERROR("record_error"),
        CODEC_ERROR("codec_error"),
        SERVICE_DESTROY("service_destroy"),

        /** 换采集器（重新授权成功）：先停旧线程，宿主紧接着再 start 一次 */
        REBIND("rebind"),
    }

    private enum class Frame { SENT, DROPPED, BROKEN }

    /**
     * 遥测的唯一出口。名字必须写成 `fun X(event: String, …)` 这个形状：
     * `DiagEventRegistryTest` 只认这种包装函数，靠它把"代码真的会发的事件名"与 PROTOCOL
     * 的 DIAG-EVENT-NAMES 对账——直接调 `onEvent`  lambda 会让这四个名字扫不出来，
     * 于是文档登记了也锁不住（正是那条反向断言要防的事）。
     */
    private fun audEvent(event: String, detail: String) = onEvent(event, detail)

    @Volatile private var running = false
    private var thread: Thread? = null

    /** stop() 要叫醒阻塞中的 read()，所以采集句柄得跨线程可见（release 仍只在线程里做） */
    @Volatile private var record: AudioRecord? = null

    /** 采集侧实时状态（live/silent），宿主据此发 `k=aud` 与更新横幅 */
    @Volatile var state: String = DeviceAudioPolicy.STATE_LIVE
        private set

    val isRunning: Boolean get() = running

    /**
     * @param startReason 进 `aud_started` 的 detail，回答"这次采集是谁叫起来的"
     * @return false = 没起来（已经在跑，或拿不到 MediaProjection）。**不许**把 false 读成"停一下再试"
     */
    @Synchronized
    fun start(startReason: String): Boolean {
        if (running) return false
        if (projectionOf() == null) return false
        running = true
        state = DeviceAudioPolicy.STATE_LIVE
        thread = Thread({ loop(startReason) }, "DeviceAudioTap").also { it.isDaemon = true; it.start() }
        return true
    }

    /** 幂等：没在跑就什么都不做（不补发事件，否则一次停止会配出两行 `aud_stopped`） */
    @Synchronized
    fun stop(exit: Exit) {
        if (!running) return
        running = false
        // 阻塞式 read() 不会自己醒：stop() 是唯一能把它叫醒的动作。
        // 只 stop 不 release——跨线程 release 是竞态，释放由线程 finally 负责。
        runCatching { record?.stop() }
        runCatching { thread?.join(JOIN_TIMEOUT_MS) }
        thread = null
    }

    private fun loop(startReason: String) {
        var exit = Exit.SESSION_CLOSE
        var encoder: MediaCodec? = null
        var localRecord: AudioRecord? = null
        try {
            // MediaProjection 每次现取：restartCapture 换掉的是采集器内部那个对象，重绑必须拿到新的
            val projection = projectionOf() ?: run {
                exit = Exit.RECORD_ERROR
                return
            }
            val minBuf = AudioRecord.getMinBufferSize(
                AudioFrameCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                exit = Exit.RECORD_ERROR
                return
            }
            val captureCfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                // usage 白名单就是这条通路的隐私边界。**故意不含 USAGE_VOICE_COMMUNICATION**：
                // 她的语音由 libwebrtc 以该 usage 外放，收进来就是她自己的声音绕回她耳朵（自激回环）。
                // 这一条是 load-bearing 的，注释与代码不许分家。
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                // USAGE_UNKNOWN：不少国产播放器/浏览器把媒体标成 UNKNOWN，漏了它＝对她们注定静音
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            localRecord = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AudioFrameCodec.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    // 4 帧 ≈ 85ms：比一帧的 21.3ms 宽，容得住一次 GC；再大就是自己在制造延迟
                    .setBufferSizeInBytes(maxOf(minBuf, BYTES_PER_FRAME * 4))
                    .setAudioPlaybackCaptureConfig(captureCfg)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "playback capture AudioRecord failed", e)
                exit = Exit.RECORD_ERROR
                return
            }
            if (localRecord.state != AudioRecord.STATE_INITIALIZED) {
                exit = Exit.RECORD_ERROR
                return
            }
            encoder = try {
                newEncoder().also { it.start() }
            } catch (e: Exception) {
                Log.w(TAG, "AAC encoder init failed", e)
                exit = Exit.CODEC_ERROR
                return
            }
            localRecord.startRecording()
            record = localRecord
            audEvent(
                "aud_started",
                "sr_${AudioFrameCodec.SAMPLE_RATE}_ch_${AudioFrameCodec.CHANNELS}_c_aac_r_$startReason",
            )

            val pcm = ShortArray(AudioFrameCodec.SAMPLES_PER_FRAME * AudioFrameCodec.CHANNELS)
            val info = MediaCodec.BufferInfo()
            var fill = 0
            var silentStreak = 0
            var droppedFrames = 0
            var frameIndex = 0L
            var silentReported = false
            var lastFrameAt = SystemClock.elapsedRealtime()
            var tickStartedAt = lastFrameAt
            var pacingMs = 0L
            var framesInTick = 0
            var bytesInTick = 0L

            while (running) {
                // 攒满一整帧（1024 样本/声道）再喂编码器：AAC 的输入粒度就是这个数
                val n = localRecord.read(pcm, fill, pcm.size - fill)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read failed rc=$n")
                    exit = Exit.RECORD_ERROR
                    break
                }
                if (n == 0) continue
                fill += n
                if (fill < pcm.size) continue
                fill = 0

                val now = SystemClock.elapsedRealtime()
                pacingMs += now - lastFrameAt
                lastFrameAt = now
                framesInTick++

                val maxAbs = DeviceAudioPolicy.maxAbsSample(pcm, pcm.size)
                silentStreak = DeviceAudioPolicy.nextSilentStreak(silentStreak, maxAbs)
                state = DeviceAudioPolicy.stateOf(silentStreak)
                // 沿触发：一段长静音只报一条（这是 P0 事件，重复报会吃补报配额）
                if (silentStreak == DeviceAudioPolicy.SILENT_STREAK_FRAMES && !silentReported) {
                    silentReported = true
                    audEvent("aud_silent", "f_$silentStreak")
                }
                if (silentStreak == 0) silentReported = false

                when (encodeOneFrame(encoder, pcm, info, frameIndex)) {
                    Frame.SENT -> {
                        frameIndex++
                        bytesInTick += FRAME_BYTES_TYPICAL
                    }
                    Frame.DROPPED -> {
                        frameIndex++
                        droppedFrames++
                    }
                    Frame.BROKEN -> {
                        exit = Exit.CODEC_ERROR
                        break
                    }
                }

                if (now - tickStartedAt >= TICK_INTERVAL_MS) {
                    audEvent(
                        "aud_tick",
                        "d_${pacingMs / maxOf(1, framesInTick)}" +
                            "_q_${bufferedBytesOf()}" +
                            "_l_$droppedFrames" +
                            "_b_$bytesInTick",
                    )
                    tickStartedAt = now
                    pacingMs = 0L
                    framesInTick = 0
                    bytesInTick = 0L
                    droppedFrames = 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "device audio tap crashed", e)
            exit = Exit.RECORD_ERROR
        } finally {
            running = false
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { localRecord?.stop() }
            runCatching { localRecord?.release() }
            record = null
            state = DeviceAudioPolicy.STATE_SILENT
            audEvent("aud_stopped", "r_${exit.tag}")
        }
    }

    /**
     * 一帧 PCM → 一帧 AAC → `aud` 通道。
     * 超时/拿不到缓冲一律算 [Frame.DROPPED] 而不是重试：音频是"过期即无用"的数据，
     * 在采集线程里等编码器只会让后面的活帧一起迟到。
     */
    private fun encodeOneFrame(encoder: MediaCodec, pcm: ShortArray, info: MediaCodec.BufferInfo, frameIndex: Long): Frame {
        val inIdx = runCatching { encoder.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (inIdx < 0) return if (inIdx == MediaCodec.INFO_TRY_AGAIN_LATER) Frame.DROPPED else Frame.BROKEN
        val inBuf = encoder.getInputBuffer(inIdx) ?: return Frame.BROKEN
        inBuf.clear()
        // ByteBuffer 默认大端，PCM16 是小端——写反的现象是"能播，全是噪音"
        inBuf.order(ByteOrder.LITTLE_ENDIAN)
        inBuf.asShortBuffer().put(pcm, 0, pcm.size)
        val ptsUs = frameIndex * 1_000_000L * AudioFrameCodec.SAMPLES_PER_FRAME / AudioFrameCodec.SAMPLE_RATE
        encoder.queueInputBuffer(inIdx, 0, pcm.size * 2, ptsUs, 0)

        val outIdx = runCatching { encoder.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (outIdx < 0) return Frame.DROPPED  // 没凑出一帧：21ms 的粒度上这就是"下一拍再说"
        val outBuf = encoder.getOutputBuffer(outIdx)
        // csd-0 不上通道：`aud` 是不可靠通道，"唯一一次配置帧可能丢"的设计本来就是错的，
        // 对端按同一组常量本地拼（见 AudioFrameCodec.audioSpecificConfig）
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (outBuf == null || isConfig || info.size <= 0) {
            runCatching { encoder.releaseOutputBuffer(outIdx, false) }
            return Frame.DROPPED
        }
        val au = ByteArray(info.size)
        outBuf.position(info.offset)
        outBuf.limit(info.offset + info.size)
        outBuf.get(au)
        runCatching { encoder.releaseOutputBuffer(outIdx, false) }
        if (au.size > AudioFrameCodec.MAX_AU_BYTES) return Frame.DROPPED
        return if (sink(AudioFrameCodec.wrap(au))) Frame.SENT else Frame.DROPPED
    }

    private fun newEncoder(): MediaCodec {
        val fmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AudioFrameCodec.SAMPLE_RATE,
            AudioFrameCodec.CHANNELS,
        )
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, AudioFrameCodec.BIT_RATE)
        // CBR 而非 VBR：不可靠通道上没有"等一等"，VBR 的突发只会和 SCTP 排队打架
        fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    companion object {
        private const val TAG = "S2S-Aud"
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val OUTPUT_TIMEOUT_US = 5_000L
        private const val JOIN_TIMEOUT_MS = 500L

        /** 一帧 PCM 字节数：1024 样本 × 2 声道 × 2 字节 */
        private const val BYTES_PER_FRAME =
            AudioFrameCodec.SAMPLES_PER_FRAME * AudioFrameCodec.CHANNELS * 2

        /** 线格式一帧的标称大小（AU 341 + ADTS 7 + codec id 1），只用于遥测计数分母 */
        private const val FRAME_BYTES_TYPICAL =
            AudioFrameCodec.EXPECTED_AU_BYTES + AudioFrameCodec.ADTS_HEADER_BYTES + AudioFrameCodec.CODEC_ID_BYTES

        /** 与 `qos_tick` 同节奏（10s）：重复标量，补报队列里已降到 P2，别再密 */
        private const val TICK_INTERVAL_MS = 10_000L
    }
}
