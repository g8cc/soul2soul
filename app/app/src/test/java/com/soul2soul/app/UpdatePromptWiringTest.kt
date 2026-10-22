package com.soul2soul.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The update check must keep an automatic prompt path, not only a hidden button. */
class UpdatePromptWiringTest {
    private fun source(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val file = File(dir, "app/src/main/java/com/soul2soul/app/MainActivity.kt")
            if (file.exists()) return file.readText()
            dir = dir.parentFile
        }
        error("MainActivity.kt not found from ${File("").absolutePath}")
    }

    @Test
    fun checkForUpdateUsesOneShotPromptPolicyAndKeepsButtonFallback() {
        val body = source().substringAfter("private fun checkForUpdate()")
            .substringBefore("/**\n     * 更新判定自检")
        assertTrue(body.contains("VersionManifest.hasUpdate(local, info)"))
        assertTrue(body.contains("UpdatePromptPolicy.shouldPrompt"))
        assertTrue(body.contains("promptPendingUpdateIfReady()"))
        assertTrue(body.contains("btn.visibility = View.VISIBLE"))
    }

    @Test
    fun automaticPromptDoesNotRunWhileOutgoingCallIsActive() {
        val body = source().substringAfter("private fun checkForUpdate()")
            .substringAfter("private fun promptPendingUpdateIfReady()")
            .substringBefore("/**\n     * 更新判定自检")
        assertTrue(body.contains("!outgoingPending"))
        assertTrue(body.contains("!ScreenShareService.isRunning()"))
    }

    @Test
    fun pendingPromptIsReevaluatedOnResumeAndPromptedVersionSurvivesRecreation() {
        val main = source()
        val resume = main.substringAfter("override fun onResume()")
            .substringBefore("private fun maybePromptAccReset()")
        assertTrue(resume.contains("promptPendingUpdateIfReady()"))
        assertTrue(main.substringAfter("private fun updateUi()")
            .substringBefore("private fun refreshPermissionRows()")
            .contains("promptPendingUpdateIfReady()"))
        assertTrue(main.contains("savedInstanceState?.getInt(STATE_UPDATE_PROMPTED_CODE)"))
        assertTrue(main.contains("outState.putInt(STATE_UPDATE_PROMPTED_CODE, updatePromptedCode)"))
    }
}
