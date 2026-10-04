package com.litemusic.app.feature.together

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherPollingLifecycleTest {
    @Test
    fun hiddenOwnerSessionChangeRequestsASafeClearButNotAPoll() {
        val lifecycle = TogetherPollingLifecycle()

        lifecycle.onPageVisible()
        lifecycle.onSessionChanged(7L)
        lifecycle.onPageHidden()

        val update = lifecycle.onSessionChanged(8L)

        assertTrue(update.clearAccountState)
        assertNull(update.pollUserId)
    }
}
