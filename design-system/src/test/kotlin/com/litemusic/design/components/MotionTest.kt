package com.litemusic.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun disabledPressKeepsTheIdentityTransform() {
        assertEquals(PressTransform(1f, 1f), nmlPressTransform(pressed = true, enabled = false, pressScale = 0.975f))
    }

    @Test
    fun pressUsesASubtleVerticalSquash() {
        val transform = nmlPressTransform(pressed = true, enabled = true, pressScale = 0.975f)

        assertEquals(0.975f, transform.scaleX, 0.0001f)
        assertTrue(transform.scaleY < transform.scaleX)
        assertTrue(transform.scaleY >= 0.95f)
    }
}
