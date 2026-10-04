package com.litemusic.app.feature.player

import com.litemusic.shared.player.QueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Identifies the lyric source while allowing playback quality changes to reuse loaded lyrics. */
internal data class LyricSourceKey(
    val songId: Long,
    val localPath: String?,
)

internal fun QueueItem.lyricSourceKey(): LyricSourceKey = LyricSourceKey(id, localPath)

/**
 * Keeps a single lyric request associated with the currently playing queue item.
 *
 * A request sequence is retained alongside the source key so an earlier request for a song cannot
 * publish after playback has left that song and returned to it.
 */
internal class LyricLoadCoordinator<T>(
    private val scope: CoroutineScope,
    private val load: suspend (QueueItem) -> T?,
    private val show: (QueueItem?, T?) -> Unit,
) {
    private data class Request(
        val source: LyricSourceKey,
        val item: QueueItem,
        val sequence: Long,
    )

    private var currentSource: LyricSourceKey? = null
    private var activeRequest: Request? = null
    private var activeJob: Job? = null
    private var nextSequence = 0L

    fun updateCurrent(item: QueueItem?) {
        val source = item?.lyricSourceKey()
        if (source == currentSource) return

        currentSource = source
        request(item, source)
    }

    /** Re-reads the current source after a successful manual local-lyric import. */
    fun reloadIfCurrent(item: QueueItem): Boolean {
        val source = item.lyricSourceKey()
        if (source != currentSource) return false
        request(item, source)
        return true
    }

    fun isCurrent(item: QueueItem): Boolean = item.lyricSourceKey() == currentSource

    private fun request(item: QueueItem?, source: LyricSourceKey?) {
        activeJob?.cancel()
        activeJob = null
        activeRequest = null
        show(item, null)

        if (item == null) return

        val request = Request(source = source ?: return, item = item, sequence = ++nextSequence)
        activeRequest = request
        activeJob = scope.launch {
            val value = try {
                load(request.item)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (activeRequest == request) show(request.item, value)
        }
    }
}
