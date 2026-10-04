package com.litemusic.app.ui

/**
 * Tracks a MainActivity instance only for the current process.
 *
 * A process recreation starts with a fresh object, so restored Android state cannot suppress the
 * first themed startup frame. During a configuration recreation the old Activity marks itself as
 * changing configuration, keeping the marker until the replacement is created.
 */
internal object StartupPresentationTracker {
    private var mainActivityActive = false

    @Synchronized
    fun beginMainActivity(): Boolean {
        if (mainActivityActive) return false
        mainActivityActive = true
        return true
    }

    @Synchronized
    fun endMainActivity(changingConfigurations: Boolean) {
        if (!changingConfigurations) mainActivityActive = false
    }

    @Synchronized
    internal fun resetForTest() {
        mainActivityActive = false
    }
}
