package com.soul2soul.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已确认的运行时长/超时路径不得被墙钟校时影响。 */
class MonotonicDurationGuardTest {

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val file = File(dir, rel)
            if (file.exists()) return file.readLines()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $rel（工作目录: ${File("").absolutePath}）")
    }

    @Test
    fun webRtcSurfaceRecoveryUsesMonotonicClock() {
        val lines = source("app/src/main/java/com/soul2soul/app/webrtc/WebRtcClient.kt")
        assertTrue(lines.any { it.contains("resumedSurfaceAtMs = android.os.SystemClock.elapsedRealtime()") })
        assertTrue(lines.any { it.contains("android.os.SystemClock.elapsedRealtime() - since") })
        assertTrue(lines.none { it.contains("System.currentTimeMillis() - since") })
    }

    @Test
    fun sessionAndRecordingDurationsUseMonotonicClock() {
        val session = source("app/src/main/java/com/soul2soul/app/session/SessionActivity.kt")
        assertTrue(session.any { it.contains("android.os.SystemClock.elapsedRealtime() - elapsedBase") })
        assertTrue(session.any { it.contains("elapsedBase = android.os.SystemClock.elapsedRealtime()") })
        assertTrue(session.none { it.contains("System.currentTimeMillis() - elapsedBase") })

        val message = source("app/src/main/java/com/soul2soul/app/msg/MessageActivity.kt")
        assertTrue(message.any { it.contains("recStartAt = android.os.SystemClock.elapsedRealtime()") })
        assertTrue(message.count { it.contains("android.os.SystemClock.elapsedRealtime() - recStartAt") } >= 3)
        assertTrue(message.none { it.contains("System.currentTimeMillis() - recStartAt") })
    }

    @Test
    fun recallTimeoutAndShakeThrottleUseMonotonicClock() {
        val presence = source("app/src/main/java/com/soul2soul/app/session/PresenceService.kt")
        assertTrue(presence.any { it.contains("pendingRecallAt = SystemClock.elapsedRealtime()") })
        assertTrue(presence.any { it.contains("SystemClock.elapsedRealtime() - pendingRecallAt") })
        assertTrue(presence.none { it.contains("System.currentTimeMillis() - pendingRecallAt") })

        val main = source("app/src/main/java/com/soul2soul/app/MainActivity.kt")
        assertTrue(main.any { it.contains("ShakeDetector(clock = { android.os.SystemClock.elapsedRealtime() })") })
    }
}
