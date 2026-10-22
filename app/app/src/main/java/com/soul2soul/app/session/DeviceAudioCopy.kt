package com.soul2soul.app.session

import com.soul2soul.app.R
import com.soul2soul.app.webrtc.DeviceAudioPolicy

/**
 * 「让TA听到我手机里的声音」在**共享端自己屏幕上**的那一行文案（纯映射，JVM 可单测）。
 *
 * 判定不在这里——那份真值只有 [DeviceAudioPolicy.status] 一处（由服务算好送到
 * `ScreenShareService.audStatusForUi`）。这里只回答"每个态各说什么"，写成对枚举穷举的
 * `when`：以后加一个态而忘了给文案，编译期就红，而不是让它落进一条"没开"的谎话里。
 *
 * 文案纪律（AGENTS「错误必须解释原因+下一步」）：五个否决理由的出路完全不同，
 * 塌成两句等于没解释——"TA 版本太旧"和"这个 App 不让录制"是两件事，用户能做的也只有两件事。
 */
object DeviceAudioCopy {

    fun sharerStatusRes(status: DeviceAudioPolicy.AudStatus): Int = when (status) {
        DeviceAudioPolicy.AudStatus.OFF -> R.string.aud_state_off
        DeviceAudioPolicy.AudStatus.SDK_TOO_OLD -> R.string.aud_state_sdk_too_old
        DeviceAudioPolicy.AudStatus.NO_MIC_PERMISSION -> R.string.aud_state_no_mic
        DeviceAudioPolicy.AudStatus.WAITING_PEER -> R.string.aud_state_waiting_peer
        DeviceAudioPolicy.AudStatus.WAITING_VIDEO -> R.string.aud_state_waiting_video
        DeviceAudioPolicy.AudStatus.HEARD -> R.string.aud_state_heard
        DeviceAudioPolicy.AudStatus.NOT_HEARD -> R.string.aud_state_not_heard
    }
}
