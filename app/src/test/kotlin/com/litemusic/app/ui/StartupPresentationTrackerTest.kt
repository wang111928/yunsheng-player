package com.litemusic.app.ui

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPresentationTrackerTest {
    @After
    fun reset() = StartupPresentationTracker.resetForTest()

    @Test
    fun `first MainActivity in a process shows startup artwork`() {
        assertTrue(StartupPresentationTracker.beginMainActivity())
    }

    @Test
    fun `configuration recreation does not replay startup artwork`() {
        assertTrue(StartupPresentationTracker.beginMainActivity())
        StartupPresentationTracker.endMainActivity(changingConfigurations = true)

        assertFalse(StartupPresentationTracker.beginMainActivity())
    }

    @Test
    fun `a fully destroyed MainActivity allows a later cold creation`() {
        assertTrue(StartupPresentationTracker.beginMainActivity())
        StartupPresentationTracker.endMainActivity(changingConfigurations = false)

        assertTrue(StartupPresentationTracker.beginMainActivity())
    }
}
