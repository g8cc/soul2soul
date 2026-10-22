package com.soul2soul.app.webrtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
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
    /**
     * live↔silent 翻转的沿口（宿主接 `k=aud` 推送 + 本地状态行刷新）。
     * 只在**沿**上叫：它是 ctl 上的一条 JSON，每帧一报会把有序信令队列当音频通道用——
     * 而 `aud` 帧丢掉的那些时刻，信令反而在排队。静音期长达几分钟也只报一次。
     */
    private val onStateEdge: (state: String) -> Unit,
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

    /**
     * 一个遥测窗口内的账本。**谁把这一帧丢了，只有那一段自己知道**：
     * 10-22 双模拟器只有 `l_469_b_0` 一个数时，"编码器不吃输入"与"`aud` 通道没开"在日志里长得一模一样，
     * 只能靠关开关重开来试（本轮就是这样把根因钉在编码器上的）。
     */
    private class DropLedger {
        var sentFrames = 0
        var stalledInput = 0
        var stalledOutput = 0
        var refusedSend = 0
        val dropped: Int get() = stalledInput + stalledOutput + refusedSend
        fun reset() {
            sentFrames = 0
            stalledInput = 0
            stalledOutput = 0
            refusedSend = 0
        }
    }

    /**
     * 遥测的唯一出口。名字必须写成 `fun X(event: String, …)` 这个形状：
     * `DiagEventRegistryTest` 只认这种包装函数，靠它把"代码真的会发的事件名"与 PROTOCOL
     * 的 DIAG-EVENT-NAMES 对账——直接调 `onEvent`  lambda 会让这四个名字扫不出来，
     * 于是文档登记了也锁不住（正是那条反向断言要防的事）。
     */
    private fun audEvent(event: String, detail: String) = onEvent(event, detail)

    @Volatile private var running = false
    private var thread: Thread? = null

    /**
     * 停机原因由 [stop] 传进来，**不**由线程自己猜（与 [DeviceAudioPlayer] 的 pendingExit 同一条纪律）：
     * 采集线程被叫醒时只看见"running 变了"这一件事，回答不了"是她关的开关、系统撤的授权、
     * 还是这通结束了"。
     * ⚠️ 10-22 双模拟器实测：这个字段不存在的那版，`stop(exit)` 把参数直接丢了，
     * 六条外因出口的 `aud_stopped` 全打成 `r_session_close`——遥测把"谁叫它停的"这唯一的问题
     * 答成了一个常量，矩阵里那一整列都是假的。
     */
    @Volatile private var pendingExit: Exit? = null

    /** stop() 要叫醒阻塞中的 read()，所以采集句柄得跨线程可见（release 仍只在线程里做） */
    @Volatile private var record: AudioRecord? = null

    /** 采集侧实时状态（live/silent），宿主据此发 `k=aud` 与更新横幅 */
    @Volatile var state: String = DeviceAudioPolicy.STATE_LIVE
        private set

    /**
     * 线程**自己死掉**的原因（record/codec 错），由 [start] 清零、finally 落一次。
     * 上层靠它把"永远在等"与"起不来"分开：这两句的出路完全不同，前者是等画面，后者是外放。
     * 人为停止（关开关/撤授权/会话收尾）不算失败——那时用户知道发生了什么。
     */
    @Volatile var lastFailure: Exit? = null
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
        lastFailure = null
        pendingExit = null
        thread = Thread({ loop(startReason) }, "DeviceAudioTap").also { it.isDaemon = true; it.start() }
        return true
    }

    /** 幂等：没在跑就什么都不做（不补发事件，否则一次停止会配出两行 `aud_stopped`） */
    @Synchronized
    fun stop(exit: Exit) {
        if (!running) return
        // 先记原因再叫线程：反过来的话线程可能已经带着"没有外因"的默认值把这条报了
        pendingExit = exit
        running = false
        // 阻塞式 read() 不会自己醒：stop() 是唯一能把它叫醒的动作。
        // 只 stop 不 release——跨线程 release 是竞态，释放由线程 finally 负责。
        runCatching { record?.stop() }
        runCatching { thread?.join(JOIN_TIMEOUT_MS) }
        thread = null
    }

    private fun loop(startReason: String) {
        // 线程**自己**的死因；没死在自己手上时它是 RECORD_ERROR——采集线程"没人叫它停却退出了"
        // 只可能是读不出声，这一格不许写成 session_close（那是替别人编原因）。
        var exit = Exit.RECORD_ERROR
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
                    // 不许调 setAudioSource：框架在 setAudioPlaybackCaptureConfig 里要求
                    // 源仍是 DEFAULT（或 REMOTE_SUBMIX），设过 MIC 直接抛
                    // "Cannot both set audio source and set playback capture config"
                    // ——10-22 双模拟器就是这样只剩一条 aud_stopped r_record_error，一帧没采到。
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
            val ledger = DropLedger()
            var fill = 0
            var silentStreak = 0
            var frameIndex = 0L
            var silentReported = false
            var lastFrameAt = SystemClock.elapsedRealtime()
            var tickStartedAt = lastFrameAt
            var pacingMs = 0L
            var framesInTick = 0

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
                val nextState = DeviceAudioPolicy.stateOf(silentStreak)
                if (nextState != state) {
                    state = nextState
                    onStateEdge(nextState)
                }
                // 沿触发：一段长静音只报一条（这是 P0 事件，重复报会吃补报配额）
                if (silentStreak == DeviceAudioPolicy.SILENT_STREAK_FRAMES && !silentReported) {
                    silentReported = true
                    audEvent("aud_silent", "f_$silentStreak")
                }
                if (silentStreak == 0) silentReported = false

                if (encodeOneFrame(encoder, pcm, info, frameIndex, ledger)) {
                    exit = Exit.CODEC_ERROR
                    break
                }
                frameIndex++

                if (now - tickStartedAt >= TICK_INTERVAL_MS) {
                    audEvent(
                        "aud_tick",
                        DeviceAudioPolicy.audTickDetail(
                            avgPacingMs = pacingMs / maxOf(1, framesInTick),
                            bufferedBytes = bufferedBytesOf(),
                            dropped = ledger.dropped,
                            stalledInput = ledger.stalledInput,
                            stalledOutput = ledger.stalledOutput,
                            refusedSend = ledger.refusedSend,
                            sentFrames = ledger.sentFrames,
                            nominalFrameBytes = FRAME_BYTES_TYPICAL,
                        ),
                    )
                    tickStartedAt = now
                    pacingMs = 0L
                    framesInTick = 0
                    ledger.reset()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "device audio tap crashed", e)
            exit = Exit.RECORD_ERROR
        } finally {
            running = false
            // 外因优先：stop(出口名) 说得出"谁叫它停的"，线程自己说不出来（见 pendingExit）
            val cause = pendingExit ?: exit
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (cause == Exit.RECORD_ERROR || cause == Exit.CODEC_ERROR) lastFailure = cause
            runCatching { localRecord?.stop() }
            runCatching { localRecord?.release() }
            record = null
            if (state != DeviceAudioPolicy.STATE_SILENT) {
                state = DeviceAudioPolicy.STATE_SILENT
                // 线程自杀（record/codec 错）没有人会再调 stopAudTap——这条沿是宿主
                // 知道"采集已经不在了"的唯一机会，少了它状态行会停在"正在听"上撒谎
                onStateEdge(DeviceAudioPolicy.STATE_SILENT)
            }
            audEvent("aud_stopped", "r_${cause.tag}")
        }
    }

    /**
     * 一帧 PCM 投喂给编码器，并把编码器**此刻能给的输出全收走**（上限 [MAX_DRAIN_PER_FRAME]）。
     * 返回 true = 编码器状态不明（调用抛异常），线程必须立刻收摊。
     *
     * 为什么是"收干"而不是"收一帧"：只取一帧时，只要编码器某次一次吐两帧（起流头几拍、
     * CBR 的填充帧），输出队列就永久多囤一个；囤满之后它不再吃新输入，`dequeueInputBuffer`
     * 从此恒返 TRY_AGAIN_LATER。10-22 双模拟器实测：内录正常跑约 80s 后 `b_0` 一帧都发不出去，
     * 而 PCM 明明还在读到非零（状态行报"正在听"）——只有重新 start()（换一台编码器）才恢复。
     *
     * 拿不到缓冲一律算丢掉而不是重试：音频是"过期即无用"的数据，
     * 在采集线程里等编码器只会让后面的活帧一起迟到。
     */
    private fun encodeOneFrame(
        encoder: MediaCodec,
        pcm: ShortArray,
        info: MediaCodec.BufferInfo,
        frameIndex: Long,
        ledger: DropLedger,
    ): Boolean {
        val inIdx = runCatching { encoder.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(EXCEPTION)
        if (inIdx < 0) {
            if (inIdx == MediaCodec.INFO_TRY_AGAIN_LATER) ledger.stalledInput++
            return inIdx != MediaCodec.INFO_TRY_AGAIN_LATER
        }
        val inBuf = encoder.getInputBuffer(inIdx) ?: return true
        inBuf.clear()
        // ByteBuffer 默认大端，PCM16 是小端——写反的现象是"能播，全是噪音"
        inBuf.order(ByteOrder.LITTLE_ENDIAN)
        inBuf.asShortBuffer().put(pcm, 0, pcm.size)
        val ptsUs = frameIndex * 1_000_000L * AudioFrameCodec.SAMPLES_PER_FRAME / AudioFrameCodec.SAMPLE_RATE
        encoder.queueInputBuffer(inIdx, 0, pcm.size * 2, ptsUs, 0)

        var produced = 0
        for (attempt in 0 until MAX_DRAIN_PER_FRAME) {
            // 第一帧按原节奏等，后面几帧只探一下：憋着的输出要拿走，但不许拿成一次长阻塞
            val outIdx = runCatching {
                encoder.dequeueOutputBuffer(info, if (attempt == 0) OUTPUT_TIMEOUT_US else 0L)
            }.getOrDefault(EXCEPTION)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIdx < 0) {
                // 负数里只有这两个不是故障（10-22 实测：把它们当致命 ⇒ 起采 300ms 就 self-kill
                // `aud_stopped r_codec_error`，一条声音都出不去；AAC 编码器起来时必定先报一次 format changed）
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                ) continue
                return true
            }
            produced++
            val outBuf = encoder.getOutputBuffer(outIdx)
            // csd-0 不上通道：`aud` 是不可靠通道，"唯一一次配置帧可能丢"的设计本来就是错的，
            // 对端按同一组常量本地拼（见 AudioFrameCodec.audioSpecificConfig）
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (outBuf == null || isConfig || info.size <= 0) {
                runCatching { encoder.releaseOutputBuffer(outIdx, false) }
                continue
            }
            val au = ByteArray(info.size)
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            outBuf.get(au)
            runCatching { encoder.releaseOutputBuffer(outIdx, false) }
            if (au.size > AudioFrameCodec.MAX_AU_BYTES) {
                ledger.refusedSend++
                continue
            }
            if (sink(AudioFrameCodec.wrap(au))) ledger.sentFrames++ else ledger.refusedSend++
        }
        // "投喂了一帧却一个输出缓冲都没拿到"——单独计数，别混进"通道拒收"
        if (produced == 0) ledger.stalledOutput++
        return false
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

        /**
         * runCatching 失败时的哨兵值。**刻意不用 -1**：`INFO_TRY_AGAIN_LATER` 就是 -1，
         * 拿它当"异常"会把抛异常读成"编码器忙"（线程继续空转、故障被藏起来）。
         */
        private const val EXCEPTION = Int.MIN_VALUE

        /**
         * 一次投喂最多**探**几拍输出（正常比例就是 1:1，多出来的只发生在编码器憋了一拍之后）。
         * 封顶是因为：不封顶的话一次突发会把 21ms 粒度的采集线程按住几十毫秒，反而制造迟到。
         * 数的是"尝试次数"而不是"拿到的帧数"——`INFO_OUTPUT_FORMAT_CHANGED` 这类负数码不占产出，
         * 但也必须有上界，否则编码器一直回报就会在这里空转。
         */
        private const val MAX_DRAIN_PER_FRAME = 4

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
