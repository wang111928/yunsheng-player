package com.litemusic.app.ui

import com.litemusic.shared.player.PlayerUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Static playback controls do not need to redraw their surrounding pages for progress ticks. */
internal fun Flow<PlayerUiState>.playbackMetadata(): Flow<PlayerUiState> =
    map { it.copy(positionMs = 0L, bufferedMs = 0L) }.distinctUntilChanged()
