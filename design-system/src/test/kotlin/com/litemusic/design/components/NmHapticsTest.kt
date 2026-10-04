package com.litemusic.design.components

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class NmHapticsTest {
    @Test
    fun confirmUsesLongPressBeforeAndroidR() {
        assertEquals(
            HapticFeedbackConstants.LONG_PRESS,
            hapticFeedbackCode(NmHaptic.CONFIRM, sdkInt = 29),
        )
    }

    @Test
    fun confirmUsesConfirmOnAndroidRAndLater() {
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            hapticFeedbackCode(NmHaptic.CONFIRM, sdkInt = 30),
        )
    }
}
