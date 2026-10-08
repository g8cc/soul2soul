package com.soul2soul.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DoodleBallPolicyTest {

    @Test
    fun `in-bounds position unchanged`() {
        assertEquals(100 to 200, DoodleBallPolicy.clamp(100, 200, 120, 1080, 2340))
    }

    @Test
    fun `drag past right and bottom clamps to edge not off-screen`() {
        assertEquals(960 to 2220, DoodleBallPolicy.clamp(5000, 5000, 120, 1080, 2340))
    }

    @Test
    fun `drag past top-left clamps to zero`() {
        assertEquals(0 to 0, DoodleBallPolicy.clamp(-300, -300, 120, 1080, 2340))
    }

    @Test
    fun `ball larger than screen still pins at origin`() {
        assertEquals(0 to 0, DoodleBallPolicy.clamp(50, 50, 200, 100, 100))
    }
}
