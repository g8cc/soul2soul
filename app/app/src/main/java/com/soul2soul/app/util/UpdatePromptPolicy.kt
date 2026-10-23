package com.soul2soul.app.util

/**
 * Decide whether an update prompt may be shown for a manifest version.
 * Kept pure so lifecycle and repeated onResume behaviour stay testable.
 */
object UpdatePromptPolicy {
    fun shouldPrompt(remoteCode: Int, promptedCode: Int, canPrompt: Boolean): Boolean =
        canPrompt && remoteCode > 0 && remoteCode > promptedCode
}
