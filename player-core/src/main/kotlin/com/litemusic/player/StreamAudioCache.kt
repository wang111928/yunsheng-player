package com.litemusic.player

import android.content.Context
import android.os.StatFs
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only summary for storage UI; completed and partial song IDs are disjoint. */
data class AudioCacheUsage(
    val bytes: Long = 0L,
    val completeSongIds: Set<Long> = emptySet(),
    val partialSongIds: Set<Long> = emptySet(),
    val freeBytes: Long = 0L,
)

internal data class CachedAudioResource(
    val key: String,
    val cachedBytes: Long,
    val complete: Boolean,
)

internal fun audioCacheUsageForResources(
    resources: List<CachedAudioResource>,
    freeBytes: Long,
): AudioCacheUsage {
    val streamResources = resources.filter { streamSongId(it.key) != null }
    val complete = streamResources.asSequence()
        .filter { it.complete }
        .mapNotNull { streamSongId(it.key) }
        .toSet()
    val partial = streamResources.asSequence()
        .filter { !it.complete && it.cachedBytes > 0L }
        .mapNotNull { streamSongId(it.key) }
        .filterNot { it in complete }
        .toSet()
    return AudioCacheUsage(
        bytes = streamResources.sumOf { it.cachedBytes.coerceAtLeast(0L) },
        completeSongIds = complete,
        partialSongIds = partial,
        freeBytes = freeBytes.coerceAtLeast(0L),
    )
}

private fun streamSongId(cacheKey: String): Long? = cacheKey
    .takeIf { it.startsWith("stream:") }
    ?.split(':')
    ?.takeIf { it.size == 3 }
    ?.get(1)
    ?.toLongOrNull()
    ?.takeIf { it > 0L }

internal const val STREAM_CACHE_PLACEHOLDER_ORIGIN = "https://cache.invalid/stream/"

/**
 * An on-the-fly cache for streams the user has started.
 *
 * Completed and partial stream bytes persist without a size cap or automatic eviction, so a
 * later offline selection can reuse every fully cached track.
 */
internal class StreamAudioCache private constructor(private val context: Context) {
    private val cache = SimpleCache(
        StreamCacheStorage.resolveDirectory(context.noBackupFilesDir, context.cacheDir),
        NoOpCacheEvictor(),
        StandaloneDatabaseProvider(context.applicationContext),
    )

    /**
     * Playback reads completed spans but never owns cache write locks.  The background writer is
     * consequently free to fill the same resource while foreground playback continues through
     * its upstream connection.
     */
    private val playbackHttpFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        .setCacheWriteDataSinkFactory(null)

    /** The only writer. CacheWriter's downloading source may safely take hole locks. */
    private val completionHttpFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        // CacheWriter commits at fragment boundaries.  Smaller fragments preserve more of an
        // in-progress stream when Android kills the process before its writer can close.
        .setCacheWriteDataSinkFactory(
            CacheDataSink.Factory()
                .setCache(cache)
                .setFragmentSize(STREAM_FRAGMENT_BYTES),
        )

    /** Handles content/file URIs directly and wraps only remote sources in the stream cache. */
    val playbackDataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
        context.applicationContext,
        playbackHttpFactory,
    )

    fun prefetchDataSource(): CacheDataSource = completionHttpFactory.createDataSourceForDownloading()

    fun hasAnyBytes(cacheKey: String): Boolean = cache.getCachedSpans(cacheKey).isNotEmpty()

    /** A known content length lets a restarted player avoid resolving an expired signed URL. */
    fun isComplete(cacheKey: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        return length != C.LENGTH_UNSET.toLong() && length > 0 && cache.isCached(cacheKey, 0, length)
    }

    fun usage(): AudioCacheUsage {
        val resources = cache.keys.map { key ->
            val spans = cache.getCachedSpans(key)
            CachedAudioResource(
                key = key,
                cachedBytes = spans.sumOf { it.length },
                complete = isComplete(key),
            )
        }
        val directory = StreamCacheStorage.resolveDirectory(context.noBackupFilesDir, context.cacheDir)
        return audioCacheUsageForResources(resources, StatFs(directory.absolutePath).availableBytes)
    }

    companion object {
        const val CACHE_DIRECTORY = "stream-audio-v1"
        private const val STREAM_FRAGMENT_BYTES = 2L * 1024L * 1024L

        internal fun create(context: Context): StreamAudioCache = StreamAudioCache(context)
    }
}

/**
 * SimpleCache owns a directory lock.  Keep exactly one instance for the application process so
 * a Service restart never races a cancelling CacheWriter by trying to open the same directory.
 * The operating system releases the lock when the process actually exits.
 */
internal object StreamAudioCacheStore {
    @Volatile private var instance: StreamAudioCache? = null
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun get(context: Context): StreamAudioCache = instance ?: synchronized(this) {
        instance ?: StreamAudioCache.create(context.applicationContext).also { instance = it }
    }

    fun notifyContentsChanged() {
        _revision.value += 1
    }
}

/**
 * Read-only availability check for UI surfaces.
 *
 * A track is shown as available offline only after the cache contains its whole stream at one
 * of the stored quality variants. Partial spans are deliberately excluded: they can keep a
 * currently-playing track alive briefly, but cannot reliably survive a later offline selection
 * or a process restart.
 */
object OfflinePlaybackAvailability {
    /** Increments when the background writer commits a complete stream. */
    val cacheRevision: StateFlow<Int> = StreamAudioCacheStore.revision

    fun canPlay(context: Context, item: QueueItem): Boolean {
        return playableCachedItem(context, item) != null
    }

    /** Cache metadata and filesystem statistics may touch disk; callers get an IO-safe API. */
    suspend fun usage(context: Context): AudioCacheUsage = withContext(Dispatchers.IO) {
        StreamAudioCacheStore.get(context).usage()
    }

    /**
     * Returns the exact queue item whose complete cached stream can be read offline.
     *
     * A stream may have completed at a lower quality after the player degraded its request, or
     * at a higher quality before the user later changed the saved default.  Cache availability
     * is therefore song-based rather than tied forever to the current setting.  The returned
     * item carries the matching quality so its media/cache key remains consistent.
     */
    fun playableCachedItem(context: Context, item: QueueItem): QueueItem? {
        if (item.isLocal) return item
        val cache = StreamAudioCacheStore.get(context)
        val quality = cachedQualityForOfflinePlayback(item) { candidate ->
            streamCacheKey(item.copy(quality = candidate))?.let(cache::isComplete) == true
        } ?: return null
        return item.copy(quality = quality)
    }
}

/**
 * Chooses a complete cached variant in a deterministic order: requested quality first, then
 * lower-bitrate variants, then higher-bitrate variants.  The final branch keeps a song heard
 * before a later settings change available offline without claiming an incomplete stream works.
 */
internal fun cachedQualityForOfflinePlayback(
    item: QueueItem,
    isComplete: (Quality) -> Boolean,
): Quality? {
    if (item.isLocal) return item.quality
    val ordered = Quality.entries.sortedWith(
        compareBy<Quality> { candidate ->
            when {
                candidate == item.quality -> 0
                candidate.br < item.quality.br -> 1
                else -> 2
            }
        }.thenByDescending { candidate ->
            if (candidate.br < item.quality.br) candidate.br else -candidate.br
        },
    )
    return ordered.firstOrNull(isComplete)
}

/** Avoid cache IO for ordinary progress state updates, but re-check after an explicit reselect. */
internal fun shouldCheckOfflineCachedVariant(
    mediaKeyMatches: Boolean,
    isNewExplicitSelection: Boolean,
): Boolean = !mediaKeyMatches || isNewExplicitSelection

/** Signed URLs rotate, so their URI cannot identify cached data. */
internal fun streamCacheKey(item: QueueItem): String? =
    item.takeUnless(QueueItem::isLocal)?.let { "stream:${it.id}:${it.quality.br}" }

/** Only remote songs may be completed in the current-track stream cache, with no size cap. */
internal fun shouldPrefetchCurrentStream(item: QueueItem): Boolean = !item.isLocal && item.id > 0L

/** A deterministic URI used only to let Media3 read a stable custom cache key while offline. */
internal fun offlineStreamCacheUri(item: QueueItem): String =
    "$STREAM_CACHE_PLACEHOLDER_ORIGIN${item.id}-${item.quality.br}.mp3"
