package com.soul2soul.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-16 判定层的锁定用例。方法名与批准计划里的清单一一对应，便于日后按名字回查"这条是谁锁的"。
 */
class DeviceAudioPolicyTest {

    private val allYes = mapOf(
        "sdk" to 34, "hasProjection" to true, "toggleWanted" to true,
        "peerAcked" to true, "hasRecordPermission" to true,
    )

    private fun gateWith(overrides: Map<String, Any>) = DeviceAudioPolicy.gate(
        sdk = overrides["sdk"] as Int,
        hasProjection = overrides["hasProjection"] as Boolean,
        toggleWanted = overrides["toggleWanted"] as Boolean,
        peerAcked = overrides["peerAcked"] as Boolean,
        hasRecordPermission = overrides["hasRecordPermission"] as Boolean,
    )

    @Test
    fun gate_requiresAllFiveIncludingPeerAck() {
        assertEquals(DeviceAudioPolicy.Reason.START, gateWith(allYes))
        // 五项里任何一项不满足都不许开——尤其 peerAcked：她版本旧时开了只是白烧 140kbps 上行
        assertEquals(DeviceAudioPolicy.Reason.PEER_NOT_ACKED, gateWith(allYes + ("peerAcked" to false)))
        assertEquals(DeviceAudioPolicy.Reason.TOGGLE_OFF, gateWith(allYes + ("toggleWanted" to false)))
        assertEquals(DeviceAudioPolicy.Reason.NO_PROJECTION, gateWith(allYes + ("hasProjection" to false)))
        assertEquals(
            DeviceAudioPolicy.Reason.NO_RECORD_PERMISSION,
            gateWith(allYes + ("hasRecordPermission" to false)),
        )
        assertEquals(DeviceAudioPolicy.Reason.SDK_TOO_OLD, gateWith(allYes + ("sdk" to 28)))
    }

    @Test
    fun sdk28_neverStarts_evenWithToggle() {
        // AudioPlaybackCapture 是 API 29 引入的；开关打开、授权齐全也变不出这个 API（Mi5 就是这一格）
        assertEquals(DeviceAudioPolicy.Reason.SDK_TOO_OLD, gateWith(allYes + ("sdk" to 28)))
        assertEquals(29, DeviceAudioPolicy.MIN_SDK)
    }

    @Test
    fun silentNeedsNConsecutiveFrames_notOne() {
        val n = DeviceAudioPolicy.SILENT_STREAK_FRAMES
        assertEquals(1, DeviceAudioPolicy.nextSilentStreak(0, 0))
        assertEquals(n - 1, DeviceAudioPolicy.nextSilentStreak(n - 2, 0))
        // 差一帧不算静音：一次气口/一瞬停顿就把状态打成"没声音"是假故障
        assertEquals(DeviceAudioPolicy.STATE_LIVE, DeviceAudioPolicy.stateOf(n - 1))
        assertEquals(DeviceAudioPolicy.STATE_SILENT, DeviceAudioPolicy.stateOf(n))
    }

    @Test
    fun oneNonZeroSampleResetsSilentStreak() {
        val streak = DeviceAudioPolicy.SILENT_STREAK_FRAMES
        assertEquals(0, DeviceAudioPolicy.nextSilentStreak(streak, 1))
        assertEquals(DeviceAudioPolicy.STATE_LIVE, DeviceAudioPolicy.stateOf(0))
    }

    /**
     * 状态词汇表只有两态，且这是一条**反向**锁：谁想不加可信信号就回补一个 "blocked"，
     * 这里会先红，逼他回到"公开 API 拿不到播放器状态"这个已核过的事实上
     * （文件头记着两条候选路与各自的死因）。
     */
    @Test
    fun statesAreLiveAndSilentOnly() {
        assertEquals(setOf("live", "silent"), DeviceAudioPolicy.STATES)
        (0..DeviceAudioPolicy.SILENT_STREAK_FRAMES * 4).forEach { s ->
            assertTrue("stateOf($s) 越界：${DeviceAudioPolicy.stateOf(s)}",
                DeviceAudioPolicy.stateOf(s) in DeviceAudioPolicy.STATES)
        }
    }

    /**
     * `maxAbsSample` 是静音判定的唯一读数来源：判错一次＝整段被读成"对方 App 禁止录制"。
     * `len` 的口径是**样本个数**且必须夹到数组长度——采集线程给半帧时不许读到上一帧的尾巴，
     * 那条尾巴会让"全零"变成"非零"（或反过来），正是最容易在真机上复现不了的错。
     */
    @Test
    fun maxAbsSample_measuresAbsoluteAmplitude() {
        assertEquals(0, DeviceAudioPolicy.maxAbsSample(ShortArray(4), 4))          // 全零 ⇒ 0
        assertEquals(7, DeviceAudioPolicy.maxAbsSample(shortArrayOf(1, -7, 3, 2), 4)) // 负样本取绝对值
        assertEquals(32768, DeviceAudioPolicy.maxAbsSample(shortArrayOf(0, Short.MIN_VALUE), 2)) // 取反不许溢出成负数
        assertEquals(5, DeviceAudioPolicy.maxAbsSample(shortArrayOf(0, 5, 9, 9), 2))  // len 之后的样本不许看见
        assertEquals(5, DeviceAudioPolicy.maxAbsSample(shortArrayOf(0, 5), 99))       // len 越界不许数组越界
        assertEquals(0, DeviceAudioPolicy.maxAbsSample(shortArrayOf(5), -1))          // 负 len ⇒ 无样本可读
    }

    @Test
    fun jitterBuffer_dropsOldestAtHardCap() {
        val delay = DeviceAudioPolicy.AUD_PLAYOUT_DELAY_MS
        val cap = DeviceAudioPolicy.JITTER_HARD_CAP_MS
        val t0 = 1_000_000L
        // 空队列没东西可播
        assertEquals(DeviceAudioPolicy.JitterAction.WAIT, DeviceAudioPolicy.jitter(-1L, t0, t0))
        // 没到放音期限 ⇒ 等（这段缓冲就是把 21.33ms 一帧的抖动拉平的那件事）
        assertEquals(DeviceAudioPolicy.JitterAction.WAIT, DeviceAudioPolicy.jitter(t0, t0, t0 + delay - 1))
        assertEquals(DeviceAudioPolicy.JitterAction.PLAY, DeviceAudioPolicy.jitter(t0, t0, t0 + delay))
        // 正好卡在硬上限 ⇒ 还在预算内；越界一格就扔最旧的
        assertEquals(DeviceAudioPolicy.JitterAction.PLAY, DeviceAudioPolicy.jitter(t0, t0 + cap, t0 + cap))
        assertEquals(DeviceAudioPolicy.JitterAction.DROP_OLDEST, DeviceAudioPolicy.jitter(t0, t0 + cap + 1, t0 + cap + 1))
        // 上限看的是"队首到队尾的跨度"，不是"队首自己等了多久"：追赶式突发里后者会误杀整段
        assertEquals(DeviceAudioPolicy.JitterAction.DROP_OLDEST, DeviceAudioPolicy.jitter(t0, t0 + cap + 50, t0 + delay))
    }

    @Test
    fun backpressure_dropsAudioNeverVideo() {
        // 9600B 的来路：(放音 200ms + 抖动 400ms) × 128kbps ÷ 8 —— 超预算的帧只会迟到，塞进去没有意义
        assertEquals(9_600, DeviceAudioPolicy.SEND_MAX_BUFFERED_BYTES)
        assertEquals(DeviceAudioPolicy.SendAdmission.SEND, DeviceAudioPolicy.sendAdmission(0))
        assertEquals(DeviceAudioPolicy.SendAdmission.SEND, DeviceAudioPolicy.sendAdmission(9_600))
        assertEquals(DeviceAudioPolicy.SendAdmission.DROP, DeviceAudioPolicy.sendAdmission(9_601))
        // 一帧的量级必须远小于预算，否则这个门会变成"每帧都丢"的空锁
        assertTrue(AudioFrameCodec.EXPECTED_AU_BYTES * 8 < DeviceAudioPolicy.SEND_MAX_BUFFERED_BYTES)
    }

    @Test
    fun playoutDelayConstantIs200() {
        // 计划钉死的值。要动它得先有 aud_play detail=dl_… 的读数，不许顺手调参
        assertEquals(200L, DeviceAudioPolicy.AUD_PLAYOUT_DELAY_MS)
        assertEquals(400L, DeviceAudioPolicy.JITTER_HARD_CAP_MS)
        assertEquals(25, DeviceAudioPolicy.SILENT_STREAK_FRAMES)
    }

    /**
     * 五个否决理由必须落到**不同**的本地状态：它们各自的出路完全不同
     * （换手机/给权限/升级对面/等画面），塌成"没开"就等于没解释。
     */
    @Test
    fun status_separatesEveryGateRejection() {
        assertEquals(
            DeviceAudioPolicy.AudStatus.OFF,
            DeviceAudioPolicy.status(DeviceAudioPolicy.Reason.TOGGLE_OFF, false, DeviceAudioPolicy.STATE_SILENT),
        )
        assertEquals(
            DeviceAudioPolicy.AudStatus.SDK_TOO_OLD,
            DeviceAudioPolicy.status(DeviceAudioPolicy.Reason.SDK_TOO_OLD, false, DeviceAudioPolicy.STATE_SILENT),
        )
        assertEquals(
            DeviceAudioPolicy.AudStatus.NO_MIC_PERMISSION,
            DeviceAudioPolicy.status(
                DeviceAudioPolicy.Reason.NO_RECORD_PERMISSION, false, DeviceAudioPolicy.STATE_SILENT,
            ),
        )
        assertEquals(
            DeviceAudioPolicy.AudStatus.WAITING_PEER,
            DeviceAudioPolicy.status(DeviceAudioPolicy.Reason.PEER_NOT_ACKED, false, DeviceAudioPolicy.STATE_SILENT),
        )
        assertEquals(
            DeviceAudioPolicy.AudStatus.WAITING_VIDEO,
            DeviceAudioPolicy.status(DeviceAudioPolicy.Reason.NO_PROJECTION, false, DeviceAudioPolicy.STATE_SILENT),
        )
    }

    /**
     * `gate()==START` 而线程没在跑，是真实存在的一瞬（`tap.start()` 返回 false：投影刚好在那一步被撤）。
     * 这一格必须落在 WAITING_VIDEO——报 HEARD 是撒谎，报 NOT_HEARD 则把"没起来"说成"起了但没声音"。
     */
    @Test
    fun status_startButNotRunning_isWaitingVideo_neverHeard() {
        assertEquals(
            DeviceAudioPolicy.AudStatus.WAITING_VIDEO,
            DeviceAudioPolicy.status(DeviceAudioPolicy.Reason.START, false, DeviceAudioPolicy.STATE_LIVE),
        )
        // 真在跑之后，live/silent 才各自有意义
        assertEquals(
            DeviceAudioPolicy.AudStatus.HEARD,
            DeviceAudioPolicy.status(DeviceAudioPolicy.Reason.START, true, DeviceAudioPolicy.STATE_LIVE),
        )
        assertEquals(
            DeviceAudioPolicy.AudStatus.NOT_HEARD,
            DeviceAudioPolicy.status(DeviceAudioPolicy.Reason.START, true, DeviceAudioPolicy.STATE_SILENT),
        )
    }

    /**
     * 协议侧只有两态（本地七态不许外推）：她那边能做的只有"知道有没有声音"。
     * 反向锁：谁把 WAITING_* 之类折成 live，这条先红。
     */
    @Test
    fun peerState_foldsToTwoStates_andOnlyHeardIsLive() {
        DeviceAudioPolicy.AudStatus.entries.forEach { s ->
            assertTrue(
                "$s 折出来的 state 越界",
                DeviceAudioPolicy.peerStateOf(s) in DeviceAudioPolicy.STATES,
            )
            assertEquals(
                if (s == DeviceAudioPolicy.AudStatus.HEARD) DeviceAudioPolicy.STATE_LIVE
                else DeviceAudioPolicy.STATE_SILENT,
                DeviceAudioPolicy.peerStateOf(s),
            )
        }
    }
}
