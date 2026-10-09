package com.litemusic.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusMonitorTest {
    @Test
    fun lostDefaultIsOfflineEvenWhenItsOldCapabilitiesAreStillReported() {
        assertFalse(
            networkIsOnline(
                activeNetworkIsLost = true,
                hasInternetCapability = true,
                hasValidatedCapability = true,
            ),
        )
    }

    @Test
    fun validatedReplacementNetworkStaysOnlineDuringHandoff() {
        assertTrue(
            networkIsOnline(
                activeNetworkIsLost = false,
                hasInternetCapability = true,
                hasValidatedCapability = true,
            ),
        )
    }

    @Test
    fun unvalidatedOrMissingInternetCapabilityIsOffline() {
        assertFalse(networkIsOnline(false, hasInternetCapability = true, hasValidatedCapability = false))
        assertFalse(networkIsOnline(false, hasInternetCapability = false, hasValidatedCapability = true))
    }
}
