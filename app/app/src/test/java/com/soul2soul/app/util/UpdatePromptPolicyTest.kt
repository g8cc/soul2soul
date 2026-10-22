package com.soul2soul.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptPolicyTest {

    @Test
    fun newerVersionPromptsOnlyWhenActivityCanShowUi() {
        assertTrue(UpdatePromptPolicy.shouldPrompt(66, 0, canPrompt = true))
        assertFalse(UpdatePromptPolicy.shouldPrompt(66, 0, canPrompt = false))
    }

    @Test
    fun sameOrOlderVersionDoesNotPromptAgain() {
        assertFalse(UpdatePromptPolicy.shouldPrompt(66, 66, canPrompt = true))
        assertFalse(UpdatePromptPolicy.shouldPrompt(65, 66, canPrompt = true))
    }

    @Test
    fun invalidManifestVersionNeverPrompts() {
        assertFalse(UpdatePromptPolicy.shouldPrompt(0, 0, canPrompt = true))
        assertFalse(UpdatePromptPolicy.shouldPrompt(-1, 0, canPrompt = true))
    }
}
