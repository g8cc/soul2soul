package com.soul2soul.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本清单解析/判定回归：锁定 0.2.12 内联事故防线（判定必须显式传入运行期 local） */
class VersionManifestTest {

    @Test
    fun parse_fullManifest() {
        val info = VersionManifest.parse(
            """{"versionCode":23,"versionName":"0.2.23","url":"https://x/apk/a.apk"}"""
        )
        assertEquals(23, info?.versionCode)
        assertEquals("0.2.23", info?.versionName)
        assertEquals("https://x/apk/a.apk", info?.url)
    }

    @Test
    fun parse_missingFields_defaultZero() {
        val info = VersionManifest.parse("""{"versionName":"0.2.23"}""")
        assertEquals(0, info?.versionCode)
        assertEquals("", info?.url)
    }

    @Test
    fun parse_nonJson_returnsNull() {
        assertNull(VersionManifest.parse("not json at all"))
        assertNull(VersionManifest.parse(""))
        assertNull(VersionManifest.parse("<html>502 bad gateway</html>"))
    }

    @Test
    fun parse_wrongFieldTypes_defaultZero() {
        // optInt 对非数值字段给默认值而不是抛异常
        val info = VersionManifest.parse("""{"versionCode":"abc"}""")
        assertEquals(0, info?.versionCode)
    }

    @Test
    fun hasUpdate_newerRemote_true() {
        assertTrue(VersionManifest.hasUpdate(22, Updater.Info(23, "0.2.23", "u")))
    }

    @Test
    fun hasUpdate_equal_false() {
        assertFalse(VersionManifest.hasUpdate(23, Updater.Info(23, "0.2.23", "u")))
    }

    @Test
    fun hasUpdate_downgrade_false() {
        assertFalse(VersionManifest.hasUpdate(23, Updater.Info(22, "0.2.22", "u")))
    }

    @Test
    fun hasUpdate_nullInfo_false() {
        assertFalse(VersionManifest.hasUpdate(23, null))
    }

    @Test
    fun hasUpdate_zeroCodeManifest_false() {
        // 清单缺 versionCode（=0）不得触发更新提示——防"全员永久提示更新"类事故
        assertFalse(VersionManifest.hasUpdate(0, Updater.Info(0, "", "")))
        assertFalse(VersionManifest.hasUpdate(8, Updater.Info(0, "", "")))
    }
}
