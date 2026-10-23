package com.soul2soul.app.session

import com.soul2soul.app.R
import com.soul2soul.app.webrtc.DeviceAudioPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享端自己那一行文案的锁定用例。
 *
 * 存在的理由不是"有没有文案"（`when` 穷举已经保证编译期给全），而是**塌成两句**照样能编译：
 * 把 SDK_TOO_OLD 和 NO_MIC_PERMISSION 都指向 `aud_state_off`，整张状态表当场退化成一句谎话，
 * 而用户能做的两件完全不同的事（换手机 / 去给权限）就都没了。
 */
class DeviceAudioCopyTest {

    @Test
    fun `每个状态都有自己的文案 态数不许共用`() {
        val res = DeviceAudioPolicy.AudStatus.entries.map { DeviceAudioCopy.sharerStatusRes(it) }
        assertEquals("每个态必须各自一条文案（去重后数量掉了=有两条塌成同一句）",
            DeviceAudioPolicy.AudStatus.entries.size, res.distinct().size)
        res.forEach { assertTrue("不许出现 0（未分配的 R 常量）", it != 0) }
    }

    @Test
    fun `正在听到与听不到不是同一句`() {
        // 这两句是用户唯一能据此判断"要不要再等一会儿"的依据，混用等于没报
        assertEquals(R.string.aud_state_heard,
            DeviceAudioCopy.sharerStatusRes(DeviceAudioPolicy.AudStatus.HEARD))
        assertEquals(R.string.aud_state_not_heard,
            DeviceAudioCopy.sharerStatusRes(DeviceAudioPolicy.AudStatus.NOT_HEARD))
    }
}
