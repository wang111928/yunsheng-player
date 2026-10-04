package com.litemusic.data.prefs

import android.content.Context

/**
 * A small synchronous mirror used only before DataStore is available during a cold start.
 *
 * DataStore remains the setting source of truth. The mirror lets the first Compose frame use
 * the previously selected skin instead of briefly drawing the generic launcher background.
 */
object StartupThemePreferences {
    private const val FILE_NAME = "startup_theme"
    private const val KEY_THEME = "theme"
    private const val DEFAULT_THEME = "light"

    fun read(context: Context): String = sanitize(runCatching {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
    }.getOrNull())

    fun hasSnapshot(context: Context): Boolean = runCatching {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .contains(KEY_THEME)
    }.getOrDefault(false)

    fun mirror(context: Context, theme: String) {
        val value = sanitize(theme)
        runCatching {
            val preferences = context.applicationContext
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            if (preferences.getString(KEY_THEME, null) != value) {
                preferences.edit().putString(KEY_THEME, value).apply()
            }
        }
    }

    internal fun sanitize(value: String?): String = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 64 }
        ?: DEFAULT_THEME
}
