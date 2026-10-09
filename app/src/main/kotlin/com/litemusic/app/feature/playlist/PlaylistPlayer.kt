package com.litemusic.app.feature.playlist

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavController
import com.litemusic.app.data.HomeRepository
import com.litemusic.app.data.SongRepository
import com.litemusic.app.feature.player.enqueueNext
import com.litemusic.app.feature.player.canEnqueueNext
import com.litemusic.app.feature.player.offlineEnqueueNextMessage
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.app.ui.Routes
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.player.PlaybackController
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.Song
import com.litemusic.shared.model.RadioProgram
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

internal fun mergeSongDetails(original: List<Song>, details: List<Song>): List<Song> {
    val byId = details.associateBy { it.id }
    return original.map { song ->
        val detail = byId[song.id]
        if (song.coverUrl.isBlank() && detail?.coverUrl?.isNotBlank() == true) {
            detail.copy(reason = song.reason ?: detail.reason)
        } else {
            song
        }
    }
}

/** Program rows often carry a song id but no song art.  Preserve actual song art, then use program/station art. */
internal fun programSongWithArtwork(program: RadioProgram, song: Song): Song =
    if (song.coverUrl.isBlank() && program.artworkUrl.isNotBlank()) song.copy(picUrl = program.artworkUrl) else song

/** A queued program request may only surface an error while it is still the latest request. */
internal fun canReportProgramFailure(requestGeneration: Long, currentGeneration: Long): Boolean =
    requestGeneration == currentGeneration

/** Radio-program comments are stored independently from their attached playable song. */
internal fun programCommentThreadId(programId: Long): String = "A_DJ_1_$programId"

/** New queues always use the saved default. A per-song choice only rewrites the current item. */
internal fun queueItemsForQuality(songs: List<Song>, quality: Quality, builder: QueueBuilder = QueueBuilder()) =
    songs.map { builder.toQueueItem(it, quality) }

internal data class PlaybackQueueSelection(val items: List<QueueItem>, val startIndex: Int)

/** Keep the tapped occurrence when filtering, even if the queue contains duplicate song IDs. */
internal fun selectOfflinePlaybackQueue(
    cachedItems: List<QueueItem?>,
    sourceIndex: Int,
    allowFirstAvailable: Boolean = false,
): PlaybackQueueSelection? {
    if (sourceIndex !in cachedItems.indices) return null
    val available = cachedItems.filterNotNull()
    if (available.isEmpty()) return null
    if (cachedItems[sourceIndex] == null && !allowFirstAvailable) return null
    val start = if (cachedItems[sourceIndex] == null) 0 else cachedItems.take(sourceIndex).count { it != null }
    return PlaybackQueueSelection(available, start)
}

/** Metadata enrichment must retain the filtered queue's order, occurrences and cached quality. */
internal fun queueMetadataForSelection(
    selectedItems: List<QueueItem>,
    enrichedSongs: List<Song>,
    builder: QueueBuilder = QueueBuilder(),
): List<QueueItem> {
    val byId = enrichedSongs.associateBy { it.id }
    return selectedItems.map { item ->
        byId[item.id]?.let { builder.toQueueItem(it, item.quality) } ?: item
    }
}

/**
 * 歌单/列表 → 队列 → 播放 的通用入口（点击即播）。
 */
class PlaylistPlayer(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val controller: PlaybackController,
    private val songRepository: SongRepository,
    private val homeRepository: HomeRepository,
    private val api: NMApi,
    private val settings: SettingsStore,
    private val context: Context,
    private val network: NetworkStatusMonitor,
    private val builder: QueueBuilder = QueueBuilder(),
) {
    private var playGeneration = 0L

    fun playSongs(
        navController: NavController,
        songs: List<Song>,
        startIndex: Int = 0,
        allowOfflineStartFallback: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        val generation = ++playGeneration
        scope.launch {
            val quality = settings.quality.first()
            if (generation != playGeneration) return@launch
            val requestedItems = queueItemsForQuality(songs, quality, builder)
            val requestedIndex = startIndex.coerceIn(requestedItems.indices)
            network.refresh()
            val selection = if (network.isOnline.value) {
                PlaybackQueueSelection(requestedItems, requestedIndex)
            } else {
                val cachedItems = withContext(Dispatchers.IO) {
                    requestedItems.map { OfflinePlaybackAvailability.playableCachedItem(context, it) }
                }
                if (generation != playGeneration) return@launch
                network.refresh()
                if (network.isOnline.value) PlaybackQueueSelection(requestedItems, requestedIndex)
                else selectOfflinePlaybackQueue(cachedItems, requestedIndex, allowOfflineStartFallback)
            }
            if (selection == null) {
                Toast.makeText(context, "当前无网络，所选歌曲未完整缓存，暂时无法播放", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val missingCoverIds = songs.filter { it.coverUrl.isBlank() }.map { it.id }.filter { it > 0L }
            // Resolve the saved default before creating the queue: this prevents the
            // former hard-coded 320K from briefly winning a DataStore race.
            controller.setQueueAndPlay(selection.items, selection.startIndex)
            navController.navigate(Routes.PLAYER)
            if (missingCoverIds.isEmpty()) return@launch
            val enriched = when (val result = songRepository.details(missingCoverIds)) {
                is AppResult.Success -> mergeSongDetails(songs, result.data)
                is AppResult.Failure -> songs
            }
            if (generation == playGeneration) {
                controller.replaceQueueMetadata(queueMetadataForSelection(selection.items, enriched, builder))
            }
        }
    }

    /** Add a later song using the persisted default, independent of a manual current-song choice. */
    fun enqueueNext(song: Song, onUnavailable: (String) -> Unit = {}) {
        val queueGeneration = playGeneration
        scope.launch {
            val quality = settings.quality.first()
            // A tap for an old queue must not append to a queue that was replaced while
            // DataStore was being read.
            if (queueGeneration != playGeneration) return@launch
            val item = builder.toQueueItem(song, quality)
            val cachedItem = withContext(Dispatchers.IO) {
                OfflinePlaybackAvailability.playableCachedItem(context, item)
            }
            if (queueGeneration != playGeneration) return@launch
            // Recheck after disk work: connectivity or the queue can change while
            // checking cache. Use the cached quality when inserting offline.
            network.refresh()
            val isOnline = network.isOnline.value
            if (!canEnqueueNext(isOnline, hasCompleteCache = cachedItem != null)) {
                onUnavailable(offlineEnqueueNextMessage())
                return@launch
            }
            controller.enqueueNext(if (isOnline) item else cachedItem ?: return@launch)
        }
    }

    /** Program summaries are metadata, not timed lyrics or a transcript. */
    fun playProgram(navController: NavController, program: RadioProgram, onError: (String) -> Unit = {}) {
        val generation = ++playGeneration
        program.mainSong?.takeIf { it.id > 0L }?.let { song ->
            startProgram(navController, program, song, generation)
            return
        }
        if (program.id <= 0L) {
            onError("节目不可播放")
            return
        }
        scope.launch {
            // The list already supplies the main track id for many programs. Skip
            // the program-detail round trip in that case and hydrate only the song.
            val detailResult = if (program.playableSongId > 0L) null else api.radioProgramDetail(program.id)
            val detailed = when (detailResult) {
                is AppResult.Success -> detailResult.data.takeIf { it.code == 200 }?.program
                is AppResult.Failure -> null
                null -> null
            }
            val displayProgram = detailed?.let { detail ->
                detail.copy(
                    name = detail.name.ifBlank { program.name },
                    description = detail.description.ifBlank { program.description },
                    desc = detail.desc.ifBlank { program.desc },
                    programDesc = detail.programDesc.ifBlank { program.programDesc },
                    coverUrl = detail.coverUrl.ifBlank { program.coverUrl },
                    radio = detail.radio ?: program.radio,
                    stationCoverUrl = detail.stationCoverUrl.ifBlank { program.stationCoverUrl },
                )
            } ?: program
            val embeddedSong = displayProgram.mainSong?.takeIf { it.id > 0L }
                ?: program.mainSong?.takeIf { it.id > 0L }
            val trackId = displayProgram.playableSongId.takeIf { it > 0L } ?: program.playableSongId
            val song = embeddedSong ?: if (trackId > 0L) {
                when (val result = songRepository.detail(trackId)) {
                    is AppResult.Success -> result.data.takeIf { it.id > 0L }
                    is AppResult.Failure -> {
                        if (canReportProgramFailure(generation, playGeneration)) {
                            onError("节目歌曲读取失败：${result.message.ifBlank { "请稍后重试" }}")
                        }
                        return@launch
                    }
                }
            } else null
            if (generation != playGeneration) return@launch
            if (song == null) {
                val detailMessage = (detailResult as? AppResult.Failure)?.message
                onError(detailMessage?.let { "节目详情读取失败：$it" } ?: "节目暂时无法播放")
            } else {
                startProgram(navController, displayProgram, song, generation)
            }
        }
    }

    private fun startProgram(navController: NavController, program: RadioProgram, song: Song, generation: Long) {
        scope.launch {
            val quality = settings.quality.first()
            if (generation != playGeneration) return@launch
            val item = builder.toQueueItem(
                song = programSongWithArtwork(program, song).copy(name = program.name.ifBlank { song.name }),
                quality = quality,
                // This is an episode introduction, not timed lyrics. PlayerViewModel renders
                // it as such when the song endpoint has no lyric payload.
                description = program.text,
                commentThreadId = program.id.takeIf { it > 0L }?.let(::programCommentThreadId),
            )
            controller.setQueueAndPlay(listOf(item), 0)
            navController.navigate(Routes.PLAYER)
        }
    }

    fun playDaily(navController: NavController, onError: (String) -> Unit = {}) {
        scope.launch {
            val r = homeRepository.load(forceRefresh = true)
            if (r is AppResult.Success) {
                playSongs(navController, r.data.daily, 0)
            } else {
                onError((r as? AppResult.Failure)?.message ?: "获取每日推荐失败")
            }
        }
    }

    /** 私人 FM：金刚区入口，取回一批推荐后直接起播 */
    fun playFm(navController: NavController, onError: (String) -> Unit = {}) {
        scope.launch {
            when (val r = homeRepository.personalFm()) {
                is AppResult.Success ->
                    if (r.data.isEmpty()) onError("私人 FM 暂无推荐")
                    else playSongs(navController, r.data, 0)
                is AppResult.Failure -> onError(r.message)
            }
        }
    }
}

@Composable
fun rememberPlaylistPlayer(
    controller: PlaybackController = koinInject(),
    songRepository: SongRepository = koinInject(),
    homeRepository: HomeRepository = koinInject(),
    api: NMApi = koinInject(),
    settings: SettingsStore = koinInject(),
    context: Context = androidx.compose.ui.platform.LocalContext.current,
    network: NetworkStatusMonitor = koinInject(),
): PlaylistPlayer {
    val scope = rememberCoroutineScope()
    return remember(controller, songRepository, homeRepository, api, settings, context, network, scope) {
        PlaylistPlayer(scope, controller, songRepository, homeRepository, api, settings, context.applicationContext, network)
    }
}
