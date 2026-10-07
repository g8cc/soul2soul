package com.soul2soul.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackPolicyTest {
    @Test
    fun `active remote control routes back away from the local activity`() {
        assertTrue(LocalBackPolicy.shouldRouteBackToRemote(live = true, controlMode = true))
    }

    @Test
    fun `normal call keeps back as hangup`() {
        assertFalse(LocalBackPolicy.shouldRouteBackToRemote(live = true, controlMode = false))
    }

    @Test
    fun `back outside a live call is not consumed`() {
        assertFalse(LocalBackPolicy.shouldRouteBackToRemote(live = false, controlMode = true))
    }
}
