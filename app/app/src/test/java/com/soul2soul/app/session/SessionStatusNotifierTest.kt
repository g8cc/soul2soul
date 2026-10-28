package com.soul2soul.app.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusNotifierTest {
    private fun source(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val file = File(dir, "app/src/main/java/com/soul2soul/app/session/SessionStatusNotifier.kt")
            if (file.exists()) return file.readLines()
            dir = dir.parentFile
        }
        error("找不到 SessionStatusNotifier.kt")
    }

    @Test
    fun `terminal status uses a new notification id so recovery update can alert`() {
        val lines = source()
        val ended = lines.dropWhile { !it.contains("fun ended(") }.take(18)
        assertTrue(ended.any { it.contains("cancel(Notif.ID_SESSION_STATUS)") })
        assertTrue(ended.any { it.contains("cancel(Notif.ID_SESSION_STATUS_ENDED)") })
        assertTrue(ended.any { it.contains("ID_SESSION_STATUS_ENDED") })
        assertTrue(ended.any { it.contains("openSession = false") })
    }

    @Test
    fun `clear removes both recovery and terminal notifications`() {
        val lines = source()
        val clear = lines.dropWhile { !it.contains("fun clear(") }.take(10)
        assertTrue(clear.count { it.contains("cancel(Notif.ID_SESSION_STATUS") } >= 2)
    }

    @Test
    fun `pre channel devices receive the default notification sound`() {
        val lines = source()
        assertTrue(lines.any { it.contains("Build.VERSION.SDK_INT < 26") })
        assertTrue(lines.any { it.contains("builder.setSound(it)") })
    }
}
