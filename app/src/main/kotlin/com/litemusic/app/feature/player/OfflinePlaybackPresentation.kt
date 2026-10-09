package com.litemusic.app.feature.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep the visual rule pure so list and queue behavior cannot drift. */
internal fun isOfflineUnavailable(isOnline: Boolean, hasCompleteCache: Boolean): Boolean =
    !isOnline && !hasCompleteCache

/**
 * Gives every disabled library row a visible, truthful reason. A pending cache scan is also
 * intentionally non-playable: otherwise a tap could create a queue before availability is
 * known.
 */
internal fun offlineUnavailableLabel(
    isOnline: Boolean,
    unavailableIds: Set<Long>?,
    songId: Long,
): String? = when {
    isOnline -> null
    unavailableIds == null -> "正在检查离线缓存"
    songId in unavailableIds -> "离线不可播"
    else -> null
}

/**
 * Runs complete-cache checks off the frame thread and returns null while an offline scan is
 * pending. The same state feeds every visible song list so an offline row cannot look playable
 * in search or recommendations while library rows are disabled.
 */
@Composable
internal fun rememberOfflineUnavailableIds(
    songs: List<Song>,
    isOnline: Boolean,
    quality: Quality,
    cacheRevision: Int,
    context: Context,
    queueBuilder: QueueBuilder,
): Set<Long>? {
    val unavailableIds by key(songs, quality, isOnline, cacheRevision) {
        produceState<Set<Long>?>(
            initialValue = if (isOnline) emptySet() else null,
            songs,
            quality,
            isOnline,
            cacheRevision,
        ) {
            value = withContext(Dispatchers.IO) {
                if (isOnline) emptySet() else songs
                    .filter { song ->
                        isOfflineUnavailable(
                            isOnline,
                            OfflinePlaybackAvailability.canPlay(context, queueBuilder.toQueueItem(song, quality)),
                        )
                    }
                    .mapTo(mutableSetOf()) { it.id }
            }
        }
    }
    return unavailableIds
}

/**
 * An offline queue must contain only complete-cache tracks.  The player deliberately keeps
 * partial bytes for an already-playing stream, but a new selection cannot promise those bytes
 * will cover an entire song after process death.
 */
internal fun offlinePlayableQueue(
    songs: List<Song>,
    isOnline: Boolean,
    unavailableIds: Set<Long>?,
): List<Song> = when {
    isOnline -> songs
    unavailableIds == null -> emptyList()
    else -> songs.filterNot { it.id in unavailableIds }
}

/** Maps an original list row into the filtered offline queue, or rejects an unavailable row. */
internal fun offlineQueueStartIndex(
    songs: List<Song>,
    sourceIndex: Int,
    isOnline: Boolean,
    unavailableIds: Set<Long>?,
): Int? {
    if (sourceIndex !in songs.indices || (!isOnline && unavailableIds == null)) return null
    if (isOnline) return sourceIndex
    val unavailable = unavailableIds.orEmpty()
    if (songs[sourceIndex].id in unavailable) return null
    return songs.take(sourceIndex).count { it.id !in unavailable }
}
