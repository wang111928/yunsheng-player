package com.litemusic.app.feature.playlist.importing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small process-local handoff for ACTION_SEND/VIEW. It intentionally contains
 * only user supplied text/URI, never cookies or an authorization result.
 */
object PlaylistImportRequestStore {
    private val _pendingText = MutableStateFlow<String?>(null)
    val pendingText = _pendingText.asStateFlow()

    fun submit(text: String?) {
        _pendingText.value = text?.trim()?.takeIf { it.isNotBlank() }
    }

    fun consume(): String? = _pendingText.value.also { _pendingText.value = null }
}
