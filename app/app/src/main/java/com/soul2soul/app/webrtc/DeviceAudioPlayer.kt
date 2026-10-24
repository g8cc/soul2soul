package com.soul2soul.app.webrtc

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 观看端设备内录播放器（FR-16）：`aud` 二进制帧 → AAC 解码 → `AudioTrack` 放音。
 *
 * 它与 libwebrtc 自己的语音轨**是两条独立的路**：语音由 libwebrtc 的 AudioDeviceModule 渲染，
 * 这一条由本类渲染，两股声音在手机音频混合器里叠加 ⇒ 她才能"同时听到视频声 + 他的声音"。
 * 之所以不做成"把 PCM 灌进 WebRTC 音轨"：stream-webrtc-android 1.1.1 从 Java 侧拿不到
 * 自定义 AudioRecord/PCM 注入口（javap 实核，见 DeviceAudioTap 文件头）。
 *
 * 三条与听感直接相关的判定全部取自 [DeviceAudioPolicy]，不在这里另写一套：
 * 放音延迟（把 21.33ms 一帧的抖动拉平）、抖动硬上限（越界就扔最旧那一帧，400ms 后的声音对上口型已无意义）、
 * 以及"这条通道只可能有 AAC"由 [AudioFrameCodec.unwrap] 的校验承担。
 */
class DeviceAudioPlayer(
    private val onEvent: (name: String, detail: String) -> Unit,
    private val onPlaybackState: (PlaybackState) -> Unit = {},
) {

    /** 播放状态给本地 UI/共享端的反馈；不要把采集端 HEARD 当成观看端已放音。 */
    enum class PlaybackState { STARTED, FAILED, STOPPED }

    /** 停止的全部出口：`aud_stopped r=<出口名>` 只能出自这份枚举（与共享端同名事件，role 区分是谁停的） */
    enum class Exit(val tag: String) {
        CHANNEL_CLOSED("channel_closed"),
        PEER_DISABLED("peer_disabled"),
        SESSION_ENDED("session_ended"),
        ACTIVITY_DESTROY("activity_destroy"),
        PLAYBACK_ERROR("playback_error"),
        CODEC_ERROR("codec_error"),
    }

    /** 一帧的入队时刻与裸 AU（已过 unwrap 校验） */
    private class Item(val enqueuedAtMs: Long, val au: ByteArray)

    private val queue = ArrayDeque<Item>()
    private var droppedLate = 0L
    private var droppedBadFormat = 0L

    @Volatile private var running = false
    @Volatile private var generation = 0L
    @Volatile private var consecutiveFailures = 0
    @Volatile private var retryAfterMs = 0L
    @Volatile private var thread: Thread? = null

    /** 队尾入队时刻（pacing 与"最新一帧"的锚），跨线程读写 ⇒ volatile */
    @Volatile private var lastFrameAtMs = 0L

    /**
     * 停机原因由 [stop] 传进来，**不**由线程自己猜：中断是 stop() 叫醒自己的手段，
     * 线程看到的是"被 interrupt 了"这一件事，回答不了"是她关的通道还是这通结束了"。
     * 让它回答，等于给 `aud_stopped` 写一个编出来的 detail。
     */
    @Volatile private var pendingExit: Exit? = null
    @Volatile private var threadFailure: Exit? = null

    val isRunning: Boolean get() = running

    /**
     * 收到一帧：解出线格式、入队，并在需要时把播放线程叫起来。
     * 返回 false = 这一帧被丢掉（线格式不自证），调用方**不许**重试——
     * 不可靠通道上"补发上一帧"只会让她听到重复的半句。
     */
    fun offer(packet: ByteArray): Boolean {
        val au = AudioFrameCodec.unwrap(packet)
        if (au == null) {
            droppedBadFormat++
            return false
        }
        val now = SystemClock.elapsedRealtime()
        lastFrameAtMs = now
        synchronized(queue) {
            queue.addLast(Item(now, au))
            // 入队侧就先夹一次硬上限：播放线程可能正阻塞在 write() 里，
            // 只靠它自己减的话一次突发会把最新几帧也挤掉（丢的应该是最旧的那几帧）
            while (queue.size > 1) {
                val head = queue.peekFirst() ?: break
                if (DeviceAudioPolicy.jitter(head.enqueuedAtMs, lastFrameAtMs, now) !=
                    DeviceAudioPolicy.JitterAction.DROP_OLDEST
                ) break
                queue.pollFirst()
                droppedLate++
            }
        }
        if (!running) start()
        return true
    }

    /** 幂等：遥测只由活跃线程的 finally 发一次；状态回调可重复，宿主按边沿发送。 */
    @Synchronized
    fun stop(exit: Exit) {
        if (running || thread?.isAlive == true) {
            pendingExit = exit
            running = false
            generation++
            thread?.let { t ->
                runCatching { t.interrupt() }
                runCatching { t.join(JOIN_TIMEOUT_MS) }
            }
            if (thread?.isAlive != true) thread = null
        }
        // 即使播放器线程刚好已失败退出，也不能把那一轮积压音频带进快速重开。
        synchronized(queue) { queue.clear() }
        onPlaybackState(PlaybackState.STOPPED)
    }

    @Synchronized
    private fun start() {
        if (running) return
        // pump 的 finally 已释放 decoder/track 后可能只差最后几条语句就退出；若刚好有新帧
        // 入队，允许该线程在 finally 内交接到新代，避免 offer 看到旧线程仍 alive 后丢掉唤醒。
        if (thread?.isAlive == true && thread !== Thread.currentThread()) return
        if (SystemClock.elapsedRealtime() < retryAfterMs) return
        pendingExit = null
        threadFailure = null
        running = true
        val thisGeneration = ++generation
        val playerThread = Thread({ pump(thisGeneration) }, "DeviceAudioPlayer").apply { isDaemon = true }
        thread = playerThread
        playerThread.start()
    }

    /**
     * 解码+放音的唯一循环。整体包在 try/finally：`aud_stopped` 只有一个发出点（finally），
     * 任何一条出口——关通道、会话结束、解码器抛异常、线程被叫醒——都必然带出一行读数。
     */
    private fun pump(thisGeneration: Long) {
        var decoder: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val codec = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            } catch (e: Exception) {
                Log.w(TAG, "no AAC decoder on this device", e)
                return
            }
            decoder = codec
            // csd-0 本地按同一组常量拼、**不经通道传**：aud 是不可靠通道，
            // "唯一一次配置帧可能丢"的设计本来就是错的（发送端也从不发它）
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AudioFrameCodec.SAMPLE_RATE,
                AudioFrameCodec.CHANNELS,
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(AudioFrameCodec.audioSpecificConfig()))
            }
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                Log.w(TAG, "AAC decoder setup failed", e)
                return
            }
            val audioTrack = newTrack()
            track = audioTrack
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { audioTrack.release() }
                track = null
                return
            }
            audioTrack.play()
            val info = MediaCodec.BufferInfo()
            var frameIndex = 0L
            var playbackStarted = false
            var tickStartedAt = SystemClock.elapsedRealtime()

            while (running) {
                val item = nextPlayable()
                var retryInput = false
                if (item != null) {
                    when (feedInput(codec, item, frameIndex * SAMPLES_PER_FRAME_US)) {
                        InputResult.FAILURE -> return
                        InputResult.RETRY -> {
                            synchronized(queue) { queue.addFirst(item) }
                            retryInput = true
                        }
                        InputResult.QUEUED -> frameIndex++
                    }
                }
                // 没帧可播也要 drain：解码器有几帧流水线延迟，最后一帧的 PCM 常常晚一拍才出来，
                // 只在"刚喂进一帧"之后取输出会把它永远留在缓冲区里
                val drained = drainOutputs(codec, audioTrack, info) {
                        if (!playbackStarted && generation == thisGeneration && running) {
                            playbackStarted = true
                            consecutiveFailures = 0
                            retryAfterMs = 0L
                            onPlaybackState(PlaybackState.STARTED)
                        }
                    }
                if (!drained) return
                if (SystemClock.elapsedRealtime() - tickStartedAt >= TICK_INTERVAL_MS) {
                    emitTick(SystemClock.elapsedRealtime() - tickStartedAt)
                    tickStartedAt = SystemClock.elapsedRealtime()
                }
                if (item == null || retryInput) sleepQuietly(POLL_MS)
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "player interrupted (stop)")
        } catch (e: Exception) {
            Log.w(TAG, "device audio player crashed", e)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            // pendingExit 为空＝线程自己撞墙死的（解码器配不起来/AudioTrack 起不来/异常），
            // 这一档只有 codec_error 一个诚实的读数，别替它编一个"她关了"
            audEvent("aud_stopped", "r_${(pendingExit ?: threadFailure ?: Exit.CODEC_ERROR).tag}")
            if (generation == thisGeneration) {
                running = false
                if (pendingExit == null) {
                    consecutiveFailures++
                    retryAfterMs = SystemClock.elapsedRealtime() +
                        DeviceAudioPlaybackPolicy.retryDelayMs(consecutiveFailures)
                    onPlaybackState(PlaybackState.FAILED)
                }
            }
            if (thread === Thread.currentThread()) thread = null
            if (!running && synchronized(queue) { queue.isNotEmpty() }) {
                start()
            }
        }
    }

    /**
     * 取下一帧该不该播。判定全在 [DeviceAudioPolicy.jitter]：没到放音期限就等，越过硬上限就扔——
     * **扔的是最旧那一帧，不是整段**，否则一次网络抖动会把她正在听的那句整个吞掉。
     */
    private fun nextPlayable(): Item? {
        val now = SystemClock.elapsedRealtime()
        synchronized(queue) {
            while (true) {
                val head = queue.peekFirst() ?: return null
                when (DeviceAudioPolicy.jitter(head.enqueuedAtMs, lastFrameAtMs, now)) {
                    DeviceAudioPolicy.JitterAction.PLAY -> return queue.pollFirst()
                    DeviceAudioPolicy.JitterAction.DROP_OLDEST -> {
                        queue.pollFirst()
                        droppedLate++
                    }
                    DeviceAudioPolicy.JitterAction.WAIT -> return null
                }
            }
        }
    }

    private enum class InputResult { QUEUED, RETRY, FAILURE }

    private fun feedInput(decoder: MediaCodec, item: Item, ptsUs: Long): InputResult {
        val idx = runCatching { decoder.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrNull()
        when (DeviceAudioPlaybackPolicy.inputDequeueResult(idx, MediaCodec.INFO_TRY_AGAIN_LATER)) {
            DeviceAudioPlaybackPolicy.Dequeue.RETRY -> return InputResult.RETRY
            DeviceAudioPlaybackPolicy.Dequeue.READY -> Unit
            else -> return InputResult.FAILURE
        }
        if (idx == null || idx < 0) return InputResult.FAILURE
        val buf = decoder.getInputBuffer(idx) ?: return InputResult.FAILURE
        buf.clear()
        buf.put(item.au)
        decoder.queueInputBuffer(idx, 0, item.au.size, ptsUs, 0)
        return InputResult.QUEUED
    }

    /** 把已就绪的 PCM 一次取空写进 AudioTrack；不等下一帧（等＝把解码延迟再叠进放音延迟） */
    private fun drainOutputs(
        decoder: MediaCodec,
        track: AudioTrack,
        info: MediaCodec.BufferInfo,
        onPcmWritten: () -> Unit,
    ): Boolean {
        while (true) {
            val idx = runCatching { decoder.dequeueOutputBuffer(info, 0) }.getOrNull()
            when (
                DeviceAudioPlaybackPolicy.outputDequeueResult(
                    idx,
                    tryAgain = MediaCodec.INFO_TRY_AGAIN_LATER,
                    formatChanged = MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    buffersChanged = MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                )
            ) {
                DeviceAudioPlaybackPolicy.Dequeue.FAILURE -> return false
                DeviceAudioPlaybackPolicy.Dequeue.RETRY -> return true
                DeviceAudioPlaybackPolicy.Dequeue.OUTPUT_EVENT -> continue
                DeviceAudioPlaybackPolicy.Dequeue.READY -> Unit
            }
            if (idx == null || idx < 0) return false
            val buf = decoder.getOutputBuffer(idx)
            val playable = buf != null && info.size > 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            if (playable) {
                buf!!.position(info.offset)
                buf.limit(info.offset + info.size)
                if (!writeFully(track, buf)) {
                    runCatching { decoder.releaseOutputBuffer(idx, false) }
                    return false
                }
                onPcmWritten()
            }
            if (runCatching { decoder.releaseOutputBuffer(idx, false) }.isFailure) {
                return false
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
        }
    }

    /** 部分写继续推进；零字节/负错误码/异常都结束当前播放器，不能吞掉 PCM 尾段或空转。 */
    private fun writeFully(track: AudioTrack, buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            // ByteBuffer 重载会在成功写入后自动推进 position；校验必须和写入前
            // 的请求长度比较，否则完整写入后 remaining==0 会被误判成失败。
            val requested = buffer.remaining()
            val result = runCatching {
                track.write(buffer, requested, AudioTrack.WRITE_BLOCKING)
            }.getOrDefault(WRITE_EXCEPTION)
            if (!DeviceAudioPlaybackPolicy.writeMadeProgress(result, requested)) {
                Log.w(TAG, "AudioTrack.write failed rc=$result")
                threadFailure = Exit.PLAYBACK_ERROR
                audEvent("aud_playback_error", "r_write_error")
                return false
            }
            // AudioTrack.write(ByteBuffer, ...) 会把 position 前进 result 字节；下一轮只写剩余 PCM。
        }
        return true
    }

    private fun emitTick(windowMs: Long) {
        val queued = synchronized(queue) { queue.size }
        audEvent(
            "aud_play",
            "dl_${DeviceAudioPolicy.AUD_PLAYOUT_DELAY_MS}" +
                "_q_$queued" +
                "_l_$droppedLate" +
                "_w_$droppedBadFormat" +
                "_t_$windowMs",
        )
    }

    /**
     * USAGE_MEDIA 而不是 VOICE_COMMUNICATION：这是**媒体声**，要走媒体音量与外放路由；
     * 挂进通话流会被电信侧按通话音量处理，还与 libwebrtc 自己的语音轨抢同一条流。
     * 代价如实写：她的麦克风（通话上行）会把这股外放声再收回去送给他，而软件 AEC 对**我们自己的
     * AudioTrack** 没有参考信号 ⇒ 回环是观察项，出现了就压她那边上行，不许装看不见
     * （读法与判据见 docs/AUDIT-MATRIX.md 的「设备音频共享」行）。
     */
    private fun newTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioFrameCodec.SAMPLE_RATE)
                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        // 缓冲给到"放音延迟 + 抖动硬上限"那一档，且不低于系统最小值：
        // write() 阻塞时还有余量，不至于把解码线程卡成新的抖动源
        .setBufferSizeInBytes(
            maxOf(
                minBufferSize().toLong(),
                AudioFrameCodec.SAMPLE_RATE / 1000L *
                    (DeviceAudioPolicy.AUD_PLAYOUT_DELAY_MS + DeviceAudioPolicy.JITTER_HARD_CAP_MS) *
                    AudioFrameCodec.CHANNELS * 2L,
            ).toInt(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private fun minBufferSize(): Int =
        AudioTrack.getMinBufferSize(
            AudioFrameCodec.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_OUT_STEREO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else 4096 }

    /** 睡眠被打断必须把中断位继续往上抛：finally 与 while(running) 都靠它判断"是谁叫我停的" */
    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * 遥测唯一出口。名字必须写成 `fun X(event: String, …)` 这个形状，
     * DiagEventRegistryTest 才扫得到（与 DeviceAudioTap 同一条理由：直接调 lambda 会登记了也锁不住）。
     */
    private fun audEvent(event: String, detail: String) = onEvent(event, detail)

    companion object {
        private const val TAG = "S2S-Aud"
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val POLL_MS = 10L
        private const val JOIN_TIMEOUT_MS = 500L
        private const val TICK_INTERVAL_MS = 10_000L
        /** 不与 AudioTrack 错误码重叠。 */
        private const val WRITE_EXCEPTION = Int.MIN_VALUE
        private const val SAMPLES_PER_FRAME_US =
            1_000_000L * AudioFrameCodec.SAMPLES_PER_FRAME / AudioFrameCodec.SAMPLE_RATE
    }
}
