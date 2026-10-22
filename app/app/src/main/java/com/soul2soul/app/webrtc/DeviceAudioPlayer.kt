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
) {

    /** 停止的全部出口：`aud_stopped r=<出口名>` 只能出自这份枚举（与共享端同名事件，role 区分是谁停的） */
    enum class Exit(val tag: String) {
        CHANNEL_CLOSED("channel_closed"),
        SESSION_ENDED("session_ended"),
        ACTIVITY_DESTROY("activity_destroy"),
        CODEC_ERROR("codec_error"),
    }

    /** 一帧的入队时刻与裸 AU（已过 unwrap 校验） */
    private class Item(val enqueuedAtMs: Long, val au: ByteArray)

    private val queue = ArrayDeque<Item>()
    private var droppedLate = 0L
    private var droppedBadFormat = 0L

    @Volatile private var running = false
    private var thread: Thread? = null

    /** 队尾入队时刻（pacing 与"最新一帧"的锚），跨线程读写 ⇒ volatile */
    @Volatile private var lastFrameAtMs = 0L

    /**
     * 停机原因由 [stop] 传进来，**不**由线程自己猜：中断是 stop() 叫醒自己的手段，
     * 线程看到的是"被 interrupt 了"这一件事，回答不了"是她关的通道还是这通结束了"。
     * 让它回答，等于给 `aud_stopped` 写一个编出来的 detail。
     */
    @Volatile private var pendingExit: Exit? = null

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

    /** 幂等：没在跑就什么都不发（一次停止只配一行 `aud_stopped`，与采集侧同一条纪律） */
    @Synchronized
    fun stop(exit: Exit) {
        if (!running) return
        pendingExit = exit
        running = false
        synchronized(queue) { queue.clear() }
        thread?.let { t ->
            runCatching { t.interrupt() }
            runCatching { t.join(JOIN_TIMEOUT_MS) }
        }
        thread = null
    }

    @Synchronized
    private fun start() {
        if (running) return
        pendingExit = null
        running = true
        thread = Thread({ pump() }, "DeviceAudioPlayer").also { it.isDaemon = true; it.start() }
    }

    /**
     * 解码+放音的唯一循环。整体包在 try/finally：`aud_stopped` 只有一个发出点（finally），
     * 任何一条出口——关通道、会话结束、解码器抛异常、线程被叫醒——都必然带出一行读数。
     */
    private fun pump() {
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
            var tickStartedAt = SystemClock.elapsedRealtime()

            while (running) {
                val item = nextPlayable()
                if (item != null) {
                    if (!feedInput(codec, item, frameIndex * SAMPLES_PER_FRAME_US)) return
                    frameIndex++
                }
                // 没帧可播也要 drain：解码器有几帧流水线延迟，最后一帧的 PCM 常常晚一拍才出来，
                // 只在"刚喂进一帧"之后取输出会把它永远留在缓冲区里
                drainOutputs(codec, audioTrack, info)
                if (SystemClock.elapsedRealtime() - tickStartedAt >= TICK_INTERVAL_MS) {
                    emitTick(SystemClock.elapsedRealtime() - tickStartedAt)
                    tickStartedAt = SystemClock.elapsedRealtime()
                }
                if (item == null) sleepQuietly(POLL_MS)
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "player interrupted (stop)")
        } catch (e: Exception) {
            Log.w(TAG, "device audio player crashed", e)
        } finally {
            running = false
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            // pendingExit 为空＝线程自己撞墙死的（解码器配不起来/AudioTrack 起不来/异常），
            // 这一档只有 codec_error 一个诚实的读数，别替它编一个"她关了"
            audEvent("aud_stopped", "r_${(pendingExit ?: Exit.CODEC_ERROR).tag}")
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

    private fun feedInput(decoder: MediaCodec, item: Item, ptsUs: Long): Boolean {
        val idx = runCatching { decoder.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (idx < 0) return true // 暂时无输入缓冲＝这一拍先播后面的，不算解码器坏了
        val buf = decoder.getInputBuffer(idx) ?: return false
        buf.clear()
        buf.put(item.au)
        decoder.queueInputBuffer(idx, 0, item.au.size, ptsUs, 0)
        return true
    }

    /** 把已就绪的 PCM 一次取空写进 AudioTrack；不等下一帧（等＝把解码延迟再叠进放音延迟） */
    private fun drainOutputs(decoder: MediaCodec, track: AudioTrack, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = runCatching { decoder.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
            if (idx < 0) return // TRY_AGAIN_LATER / 格式变化通知都不是缓冲区下标
            val buf = decoder.getOutputBuffer(idx)
            val playable = buf != null && info.size > 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            if (playable) {
                buf!!.position(info.offset)
                buf.limit(info.offset + info.size)
                runCatching { track.write(buf, info.size, AudioTrack.WRITE_BLOCKING) }
                    .onFailure { Log.w(TAG, "AudioTrack.write failed", it) }
            }
            runCatching { decoder.releaseOutputBuffer(idx, false) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun emitTick(windowMs: Long) {
        audEvent(
            "aud_play",
            "dl_${DeviceAudioPolicy.AUD_PLAYOUT_DELAY_MS}" +
                "_q_${queue.size}" +
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
        private const val SAMPLES_PER_FRAME_US =
            1_000_000L * AudioFrameCodec.SAMPLES_PER_FRAME / AudioFrameCodec.SAMPLE_RATE
    }
}
