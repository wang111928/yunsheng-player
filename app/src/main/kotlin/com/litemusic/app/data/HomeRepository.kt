package com.litemusic.app.data

import com.litemusic.data.cache.ContentCache
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.network.ApiGateway
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.DailyStyleCategory
import com.litemusic.shared.model.DailyStyleSongsData
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.asSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fresh personal-FM tracks lead the home list; daily songs fill the rest without duplicates. */
fun mergeHomeSongs(fresh: List<Song>, daily: List<Song>, limit: Int = 30): List<Song> =
    (fresh + daily).distinctBy { it.id }.take(limit)

/** Appended recommendation pages must retain the visible feed and never repeat tracks. */
fun mergeHomeSongPage(current: List<Song>, incoming: List<Song>): List<Song> =
    (current + incoming).filter { it.id > 0L }.distinctBy { it.id }

data class HomeSongPage(
    val songs: List<Song>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

/**
 * Some playlist-catalog responses omit both `more` and `total` while still returning a valid
 * page.  Treat that shape as cursor-backed until an empty/repeated page proves otherwise; using
 * `total == 0` as an immediate terminal signal made the home feed stop after its first append.
 */
internal fun catalogPageHasMore(
    received: Int,
    nextOffset: Int,
    total: Int,
    serverMore: Boolean,
): Boolean = received > 0 && (serverMore || total <= 0 || nextOffset < total)

/** A same offset page repeated during one scan means the backend is ignoring its cursor. */
internal fun catalogPageSignature(playlists: List<Playlist>): List<Long> =
    playlists.map { it.id }.filter { it > 0L }

/** Non-negative cursors page hot playlists; negative cursors page the new-playlist catalog. */
internal fun homeSongCatalogOrder(cursor: Int): String = if (cursor < 0) "new" else "hot"
internal fun homeSongCatalogOffset(cursor: Int): Int = if (cursor < 0) -cursor - 1 else cursor
internal fun homeSongNewCatalogCursor(offset: Int): Int = -offset - 1

/** Do not silently advance the feed when every song-detail request on a page failed. */
internal fun playlistDetailPageError(results: List<Pair<List<Song>, String?>>): String? =
    if (results.isNotEmpty() && results.all { it.second != null }) {
        "歌单歌曲读取失败：${results.first().second.orEmpty()}"
    } else null

/** Picks a different window from the latest real recommendation pool when enough rows exist. */
fun rotateRecommendationWindow(
    candidates: List<Playlist>,
    previousVisible: List<Playlist>,
    limit: Int,
): List<Playlist> {
    val unique = candidates.distinctBy { it.id }.filter { it.id > 0L }
    if (unique.size <= limit) return unique
    val lastVisibleId = previousVisible.lastOrNull()?.id
    val lastIndex = unique.indexOfLast { it.id == lastVisibleId }
    if (lastIndex >= 0) {
        return (unique.drop(lastIndex + 1) + unique.take(lastIndex + 1)).take(limit)
    }
    val previousIds = previousVisible.map { it.id }.toSet()
    val unseen = unique.filterNot { it.id in previousIds }
    return (unseen + unique.filter { it.id in previousIds }).take(limit)
}

/** A refresh is meaningful only when it changes the IDs actually shown on screen. */
internal fun recommendationWindowChanged(window: List<Playlist>, previous: List<Playlist>): Boolean =
    window.map { it.id } != previous.map { it.id }

/** Refresh feedback must describe the artist cards that are actually visible. */
internal fun artistWindowChanged(window: List<Artist>, previous: List<Artist>): Boolean =
    window.map { it.id } != previous.map { it.id }

/** Disk state keeps only IDs, and malformed cache contents must never block a fresh response. */
internal fun recommendationWindowIds(encoded: String?): List<Long> = encoded
    .orEmpty()
    .split(',')
    .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }
    .distinct()

/**
 * Resolves independently refreshed home sources without allowing an empty or failed playlist
 * response to erase content the user could already see.  A null playlist value represents a
 * failed request; an empty one is an unusable successful payload and is handled the same way.
 */
data class HomeRefreshContent(
    val homeSongs: List<Song>,
    val playlists: List<Playlist>,
) {
    val hasUsableContent: Boolean get() = homeSongs.isNotEmpty() || playlists.isNotEmpty()
}

fun resolveHomeRefreshContent(
    freshHomeSongs: List<Song>,
    dailySongs: List<Song>,
    freshPlaylists: List<Playlist>?,
    previousPlaylists: List<Playlist>,
): HomeRefreshContent = HomeRefreshContent(
    homeSongs = mergeHomeSongs(freshHomeSongs, dailySongs),
    playlists = freshPlaylists?.takeIf { it.isNotEmpty() } ?: previousPlaylists,
)

private data class HomeSourceResults(
    val daily: AppResult<List<Song>>,
    val toplists: AppResult<List<Playlist>>,
    val homeSongs: AppResult<List<Song>>,
    val playlists: AppResult<List<Playlist>>,
    val artists: AppResult<List<Artist>>,
)

/** Prefer the account's liked playlist, then another owned playlist that can seed heart-throb. */
fun selectHeartThrobPlaylist(playlists: List<Playlist>, userId: Long): Playlist? =
    playlists
        .asSequence()
        .filter { playlist ->
            playlist.id > 0L && playlist.trackCount > 0 &&
                (playlist.name.contains("喜欢的音乐") || playlist.userId == userId || playlist.creator?.userId == userId)
        }
        .sortedBy { playlist -> if (playlist.name.contains("喜欢的音乐")) 0 else 1 }
        .firstOrNull()

/**
 * 首页数据源：每日推荐 + 推荐歌单 + 排行榜（ContentCache 双层缓存 + 网关去重）。
 *
 * 缓存策略：
 * - 每日推荐 / 排行榜：按天缓存，二次进入秒开；
 * - 推荐歌单：**不写当天磁盘缓存**，每次进入首页（含 App 重启）和下拉刷新都重新请求
 *   真实接口；网络失败时沿用本进程内上一次真实结果，绝不使用写死的兜底数据冒充成功。
 */
class HomeRepository(
    private val api: NMApi,
    private val cache: ContentCache,
    private val gateway: ApiGateway,
    private val auth: AuthRepository,
    private val settingsStore: SettingsStore,
) {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val vipRefreshMutex = Mutex()

    /** 本进程内最后一次成功拿到的推荐歌单（真实数据，仅供网络失败时兜底展示） */
    @Volatile
    private var lastPlaylists: List<Playlist> = emptyList()

    /**
     * Only the IDs of the previous visible recommendation window are persisted.  The actual
     * playlist rows still come from a fresh server response on every new process, but a server
     * that returns the same ordered pool no longer makes every cold launch begin at its first six
     * entries.
     */
    private var persistedPlaylistWindowLoaded = false
    private var persistedPlaylistWindowIds: List<Long> = emptyList()
    /** Prevent alternating between two already displayed windows in the same app session. */
    private val seenRecommendationIds = mutableSetOf<Long>()
    private var playlistCatalogCursorLoaded = false
    private var playlistCatalogCursor = 0

    /** The discovery endpoint is ordered, so refresh advances one deterministic page. */
    private var artistPage = 0

    /** The live home stream is held only in-process; disk cache must never make it look refreshed. */
    @Volatile
    private var lastHomeSongs: List<Song> = emptyList()

    @Serializable
    data class HomeData(
        val homeSongs: List<Song> = emptyList(),
        val daily: List<Song> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val toplists: List<Playlist> = emptyList(),
        /** Homepage artists come only from the discovery endpoint. */
        val artists: List<Artist> = emptyList(),
        /** Do not fabricate artist cards if the discovery endpoint is unavailable. */
        val artistsError: String? = null,
        /** Non-fatal source failures shown above retained content instead of being silently hidden. */
        val warning: String? = null,
    )

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * 首页聚合数据。
     *
     * 推荐歌单每次都会重新请求（见 [refreshPlaylists]），所以 App 重启 / 下拉刷新后拿到的
     * 一定是最新真实歌单，而不是当天旧缓存。
     */
    suspend fun load(forceRefresh: Boolean = false): AppResult<HomeData> {
        val cacheKey = "home:" + today()
        val cached = if (!forceRefresh) readCache(cacheKey) else null

        // These sources do not depend on each other. Waiting for them serially made both the
        // first recommendation screen and pull-to-refresh feel much slower than the network.
        val sources = coroutineScope {
            val dailyJob = async {
                cached?.daily?.takeIf { it.isNotEmpty() }?.asSuccess() ?: daily(forceRefresh)
            }
            val toplistJob = async {
                cached?.toplists?.takeIf { it.isNotEmpty() }?.asSuccess() ?: toplistDetail(forceRefresh)
            }
            val homeSongsJob = async { refreshHomeSongs(forceRefresh) }
            val playlistsJob = async { refreshPlaylists(forceRefresh = forceRefresh) }
            val artistsJob = async { topArtists(refresh = forceRefresh) }
            HomeSourceResults(
                daily = dailyJob.await(),
                toplists = toplistJob.await(),
                homeSongs = homeSongsJob.await(),
                playlists = playlistsJob.await(),
                artists = artistsJob.await(),
            )
        }
        val dailyResult = sources.daily
        val toplistResult = sources.toplists
        val dailySongs = when (dailyResult) {
            is AppResult.Success -> dailyResult.data
            is AppResult.Failure -> emptyList()
        }
        val homeSongsResult = sources.homeSongs
        val freshHomeSongs = when (homeSongsResult) {
            is AppResult.Success -> homeSongsResult.data
            is AppResult.Failure -> lastHomeSongs
        }

        val playlistResult = sources.playlists
        val artistsResult = sources.artists
        val content = resolveHomeRefreshContent(
            freshHomeSongs = freshHomeSongs,
            dailySongs = dailySongs,
            freshPlaylists = (playlistResult as? AppResult.Success)?.data,
            previousPlaylists = lastPlaylists,
        )
        // A transient failure of either independent source must not hide fresh content from
        // the other.  The page fails only when neither songs nor playlists can be shown.
        if (!content.hasUsableContent) {
            return sequenceOf(homeSongsResult, dailyResult, playlistResult)
                .filterIsInstance<AppResult.Failure>()
                .firstOrNull()
                ?: AppResult.Failure(-1, "首页暂无可展示内容")
        }
        val warning = sequenceOf(dailyResult, toplistResult, playlistResult, homeSongsResult)
            .filterIsInstance<AppResult.Failure>()
            .map { it.message }
            .firstOrNull()

        val data = HomeData(
            homeSongs = content.homeSongs,
            daily = dailySongs,
            playlists = content.playlists,
            toplists = when (toplistResult) {
                is AppResult.Success -> toplistResult.data.take(10)
                is AppResult.Failure -> emptyList()
            },
            artists = (artistsResult as? AppResult.Success)?.data.orEmpty(),
            artistsError = (artistsResult as? AppResult.Failure)?.message,
            warning = warning,
        )
        // 磁盘缓存剔除推荐歌单和实时首页歌曲：否则重开会把旧内容伪装为刚刷新。
        cache.putString(
            cacheKey,
            json.encodeToString(
                HomeData.serializer(),
                data.copy(homeSongs = emptyList(), playlists = emptyList(), artists = emptyList(), artistsError = null, warning = null),
            ),
        )
        return data.asSuccess()
    }

    /**
     * 只刷新推荐歌单，不触碰每日推荐 / 排行榜缓存。
     * 5 秒内重复触发时复用上一次结果，避免“进入首页 + 下拉刷新”叠加发两次请求。
     */
    suspend fun refreshPlaylists(
        limit: Int = PLAYLIST_LIMIT,
        forceRefresh: Boolean = false,
    ): AppResult<List<Playlist>> {
        if (!forceRefresh && lastPlaylists.isNotEmpty() &&
            gateway.shouldDedupe("playlists:inflight", System.currentTimeMillis())
        ) {
            return lastPlaylists.asSuccess()
        }
        val previousWindow = previousPlaylistWindow()
        seenRecommendationIds += previousWindow.map { it.id }
        return when (val r = api.recommendPlaylists(PLAYLIST_POOL_LIMIT)) {
            is AppResult.Success -> {
                val recommended = r.data.filter { it.id > 0L }
                var fresh = recommended.filterNot { it.id in seenRecommendationIds }
                if (fresh.size < limit) {
                    when (val catalog = nextRecommendationCatalogPage(
                        excludedIds = seenRecommendationIds + recommended.map { it.id },
                        required = limit,
                    )) {
                        is AppResult.Success -> {
                            fresh = (fresh + catalog.data.filterNot { it.id in seenRecommendationIds })
                                .distinctBy { it.id }
                        }
                        is AppResult.Failure -> {
                            if (fresh.isEmpty()) return catalog
                        }
                    }
                }
                if (fresh.isEmpty()) {
                    return AppResult.Failure(-1, "暂时没有新的推荐歌单，请稍后重试")
                }
                // A short real page is preferable to padding it with playlists the user already
                // saw; padding recreated the reported A/B recommendation loop.
                val window = fresh.distinctBy { it.id }.take(limit)
                lastPlaylists = window
                seenRecommendationIds += window.map { it.id }
                persistedPlaylistWindowIds = window.map { it.id }
                persistedPlaylistWindowLoaded = true
                cache.putString(PLAYLIST_WINDOW_CACHE_KEY, persistedPlaylistWindowIds.joinToString(","))
                window.asSuccess()
            }
            is AppResult.Failure -> r
        }
    }

    /** Continue through real catalog pages when the personalised source repeats a short pool. */
    private suspend fun nextRecommendationCatalogPage(
        excludedIds: Set<Long>,
        required: Int,
    ): AppResult<List<Playlist>> {
        if (!playlistCatalogCursorLoaded) {
            playlistCatalogCursor = cache.getString(PLAYLIST_CATALOG_CURSOR_CACHE_KEY)
                ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            playlistCatalogCursorLoaded = true
        }
        var offset = playlistCatalogCursor
        val fresh = mutableListOf<Playlist>()
        val signatures = mutableSetOf<List<Long>>()
        repeat(HOME_SONG_EMPTY_PAGE_SCAN_LIMIT) {
            when (val response = api.playlistCatalog(offset = offset, limit = PLAYLIST_POOL_LIMIT)) {
                is AppResult.Failure -> return response
                is AppResult.Success -> {
                    val page = response.data
                    if (page.code != 200) {
                        return AppResult.Failure(page.code, "推荐歌单目录读取失败(${page.code})")
                    }
                    val signature = catalogPageSignature(page.playlists)
                    if (signature.isEmpty() || !signatures.add(signature)) {
                        playlistCatalogCursor = 0
                        cache.putString(PLAYLIST_CATALOG_CURSOR_CACHE_KEY, "0")
                        return fresh.distinctBy { it.id }.asSuccess()
                    }
                    fresh += page.playlists.filter { it.id > 0L && it.id !in excludedIds }
                    val nextOffset = offset + page.playlists.size
                    offset = if (catalogPageHasMore(
                            received = page.playlists.size,
                            nextOffset = nextOffset,
                            total = page.total,
                            serverMore = page.more,
                        )) nextOffset else 0
                    if (fresh.distinctBy { it.id }.size >= required || offset == 0) {
                        playlistCatalogCursor = offset
                        cache.putString(PLAYLIST_CATALOG_CURSOR_CACHE_KEY, offset.toString())
                        return fresh.distinctBy { it.id }.asSuccess()
                    }
                }
            }
        }
        playlistCatalogCursor = offset
        cache.putString(PLAYLIST_CATALOG_CURSOR_CACHE_KEY, offset.toString())
        return fresh.distinctBy { it.id }.asSuccess()
    }

    private suspend fun previousPlaylistWindow(): List<Playlist> {
        if (lastPlaylists.isNotEmpty()) return lastPlaylists
        if (!persistedPlaylistWindowLoaded) {
            persistedPlaylistWindowIds = recommendationWindowIds(
                cache.getString(PLAYLIST_WINDOW_CACHE_KEY),
            )
            persistedPlaylistWindowLoaded = true
        }
        return persistedPlaylistWindowIds.map { id -> Playlist(id = id) }
    }

    /**
     * Home's live song source. Pull-to-refresh bypasses dedupe and fetches Personal FM again;
     * a failure deliberately retains the prior live batch and falls back to daily in [load].
     */
    suspend fun refreshHomeSongs(forceRefresh: Boolean = false): AppResult<List<Song>> {
        if (!forceRefresh && lastHomeSongs.isNotEmpty() &&
            gateway.shouldDedupe("home-songs:inflight", System.currentTimeMillis())
        ) {
            return lastHomeSongs.asSuccess()
        }
        return when (val r = api.personalFm()) {
            is AppResult.Success -> when {
                r.data.code != 200 -> AppResult.Failure(r.data.code, "首页实时推荐读取失败(${r.data.code})")
                r.data.data.isEmpty() -> AppResult.Failure(200, "首页实时推荐暂无曲目")
                else -> r.data.data.also { lastHomeSongs = it }.asSuccess()
            }
            is AppResult.Failure -> r
        }
    }

    /**
     * Personal FM is intentionally only used for the first live batch: it has
     * no cursor and repeatedly calling it makes the feed look frozen.  Later
     * pages take real hot-playlist rows and resolve their tracks, then the VM
     * removes any song already visible to the user.
     */
    suspend fun moreHomeSongs(offset: Int, excludedIds: Set<Long>): AppResult<HomeSongPage> {
        var cursor = offset
        var serverHasMore = true
        val scannedCatalogPages = mutableSetOf<Pair<String, List<Long>>>()
        repeat(HOME_SONG_EMPTY_PAGE_SCAN_LIMIT) {
            val order = homeSongCatalogOrder(cursor)
            val requestedOffset = homeSongCatalogOffset(cursor)
            when (val catalog = api.playlistCatalog(
                offset = requestedOffset,
                limit = HOME_SONG_CATALOG_PAGE_SIZE,
                order = order,
            )) {
                is AppResult.Failure -> return catalog
                is AppResult.Success -> {
                    if (catalog.data.code != 200) {
                        return AppResult.Failure(catalog.data.code, "更多推荐歌曲读取失败(${catalog.data.code})")
                    }
                    val received = catalog.data.playlists
                    val playlists = received.filter { it.id > 0L }
                    val signature = catalogPageSignature(playlists)
                    val nextOffset = requestedOffset + received.size
                    // Never turn an ignored offset into unbounded background polling. A repeated
                    // non-empty catalog page cannot produce a new recommendation page.
                    if (received.isEmpty() || signature.isEmpty() || !scannedCatalogPages.add(order to signature)) {
                        if (order == "hot") {
                            cursor = homeSongNewCatalogCursor(0)
                            return@repeat
                        }
                        return HomeSongPage(
                            songs = emptyList(),
                            nextOffset = cursor,
                            hasMore = false,
                        ).asSuccess()
                    }
                    val detailResults = playlists.chunked(HOME_SONG_DETAIL_CONCURRENCY).flatMap { batch ->
                        coroutineScope {
                            batch.map { playlist ->
                                async {
                                    when (val detail = api.getPlaylistDetail(
                                        id = playlist.id,
                                        n = HOME_SONG_DETAIL_TRACK_LIMIT,
                                    )) {
                                        is AppResult.Success -> {
                                            if (detail.data.code != 200) {
                                                emptyList<Song>() to "歌单读取失败(${detail.data.code})"
                                            } else {
                                                val full = detail.data.playlist
                                                if (full == null) {
                                                    emptyList<Song>() to "歌单不存在"
                                                } else {
                                                    when (val songs = resolvePlaylistTracks(api, full, HOME_SONG_DETAIL_TRACK_LIMIT)) {
                                                        is AppResult.Success -> songs.data to null
                                                        is AppResult.Failure -> emptyList<Song>() to songs.message
                                                    }
                                                }
                                            }
                                        }
                                        is AppResult.Failure -> emptyList<Song>() to detail.message
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                    playlistDetailPageError(detailResults)?.let { return AppResult.Failure(-1, it) }
                    val tracks = detailResults.flatMap { it.first }
                    val currentOrderHasMore = catalogPageHasMore(
                        received = received.size,
                        nextOffset = nextOffset,
                        total = catalog.data.total,
                        serverMore = catalog.data.more,
                    )
                    serverHasMore = currentOrderHasMore || order == "hot"
                    val nextCursor = when {
                        currentOrderHasMore && order == "hot" -> nextOffset
                        currentOrderHasMore -> homeSongNewCatalogCursor(nextOffset)
                        order == "hot" -> homeSongNewCatalogCursor(0)
                        else -> cursor
                    }
                    val fresh = tracks.filterNot { it.id in excludedIds }.distinctBy { it.id }
                    if (fresh.isNotEmpty() || !serverHasMore) {
                        return HomeSongPage(
                            songs = fresh,
                            nextOffset = nextCursor,
                            hasMore = serverHasMore,
                        ).asSuccess()
                    }
                    // This catalog page only repeated songs already visible. Advance the real
                    // cursor a few times, then return the advanced cursor so the next load can
                    // continue from the following server page instead of falsely reaching end.
                    cursor = nextCursor
                }
            }
        }
        return HomeSongPage(
            songs = emptyList(),
            nextOffset = cursor,
            hasMore = serverHasMore,
        ).asSuccess()
    }

    /**
     * 心动模式必须由网易云智能续播接口生成，不能用私人 FM 冒充。
     * Seed is taken from the logged-in account's liked playlist first, then another owned playlist.
     */
    suspend fun heartThrob(): AppResult<List<Song>> {
        val uid = auth.ensureUserId()
        if (uid <= 0L) return AppResult.Failure(-1, "登录后才能使用心动模式")
        val playlists = when (val response = api.getUserPlaylists(uid, limit = HEART_THROB_PLAYLIST_LIMIT)) {
            is AppResult.Success -> {
                if (response.data.code != 200) {
                    return AppResult.Failure(response.data.code, "心动模式歌单读取失败(${response.data.code})")
                }
                response.data.playlist
            }
            is AppResult.Failure -> return response
        }
        val playlist = selectHeartThrobPlaylist(playlists, uid)
            ?: return AppResult.Failure(-1, "没有可用于心动模式的喜欢或自建歌单")
        val detail = when (val response = api.getPlaylistDetail(playlist.id)) {
            is AppResult.Success -> {
                if (response.data.code != 200) {
                    return AppResult.Failure(response.data.code, "心动模式种子歌单读取失败(${response.data.code})")
                }
                response.data.playlist
            }
            is AppResult.Failure -> return response
        } ?: return AppResult.Failure(-1, "心动模式种子歌单不存在")
        val seed = detail.tracks.firstOrNull { it.id > 0L }
            ?: return AppResult.Failure(-1, "心动模式种子歌单没有可播放歌曲")
        return api.intelligencePlay(id = seed.id, pid = playlist.id, sid = seed.id, count = HEART_THROB_SONG_LIMIT)
    }

    /** 每日推荐：独立缓存，供首页与「每日推荐」二级页共用，二级页秒开不再整页转圈 */
    suspend fun daily(forceRefresh: Boolean = false): AppResult<List<Song>> {
        val cacheKey = "daily:" + today()
        if (!forceRefresh) {
            readCacheSongs(cacheKey)?.takeIf { it.isNotEmpty() }?.let { return it.asSuccess() }
        }
        return when (val r = api.dailyRecommend()) {
            is AppResult.Success -> {
                if (r.data.code != 200) {
                    return AppResult.Failure(r.data.code, "每日推荐读取失败(${r.data.code})")
                }
                val list = r.data.data?.dailySongs ?: emptyList()
                if (list.isNotEmpty()) {
                    cache.putString(cacheKey, json.encodeToString(ListSerializer(Song.serializer()), list))
                }
                list.asSuccess()
            }
            is AppResult.Failure -> r
        }
    }

    /**
     * Style daily recommendations are a separate first-party endpoint. Do not fall back to the
     * ordinary daily playlist here: showing unrelated tracks would make a selected style look as
     * if it had been applied when it had not.
     */
    suspend fun dailyStyleConfig(): AppResult<List<DailyStyleCategory>> = when (val response = api.dailyStyleConfig()) {
        is AppResult.Failure -> response
        is AppResult.Success -> {
            val payload = response.data
            val categories = payload.data?.categories.orEmpty()
                .filter { it.categoryId >= 0L && it.categoryName.isNotBlank() }
                .map { category ->
                    category.copy(tags = category.tags.filter { it.tagId > 0L && it.tagName.isNotBlank() })
                }
                .filter { it.tags.isNotEmpty() }
            when {
                payload.code != 200 -> AppResult.Failure(payload.code, "风格推荐配置读取失败(${payload.code})")
                categories.isEmpty() -> AppResult.Failure(-1, "风格推荐暂未返回可选标签")
                else -> categories.asSuccess()
            }
        }
    }

    suspend fun dailyStyleSongs(categoryId: Long?, tagIds: List<Long>): AppResult<DailyStyleSongsData> {
        if (categoryId?.let { it <= 0L } == true || tagIds.any { it <= 0L }) {
            return AppResult.Failure(-1, "风格筛选参数无效")
        }
        return when (val response = api.dailyStyleSongs(categoryId, tagIds.distinct())) {
            is AppResult.Failure -> response
            is AppResult.Success -> {
                val payload = response.data
                val data = payload.data
                val songs = data?.dailySongs.orEmpty().filter { it.id > 0L }.distinctBy { it.id }
                when {
                    payload.code != 200 -> AppResult.Failure(payload.code, "风格推荐读取失败(${payload.code})")
                    data == null -> AppResult.Failure(-1, "风格推荐响应缺少数据")
                    else -> data.copy(dailySongs = songs).asSuccess()
                }
            }
        }
    }

    suspend fun saveDailyStyleTags(categoryId: Long, tagIds: List<Long>): AppResult<Unit> {
        if (categoryId <= 0L || tagIds.any { it <= 0L }) {
            return AppResult.Failure(-1, "风格筛选参数无效")
        }
        return when (val response = api.saveDailyStyleTags(categoryId, tagIds)) {
            is AppResult.Failure -> response
            is AppResult.Success -> {
                val payload = response.data
                if (payload.code == 200 && payload.data?.saveSuccess == true) Unit.asSuccess()
                else AppResult.Failure(payload.code, "风格筛选保存失败(${payload.code})")
            }
        }
    }

    private fun vipRefreshKey(userId: Long) = "daily-vip-refresh:$userId:${today()}"

    suspend fun hasUsedVipDailyRefresh(): Boolean {
        val session = auth.currentSession()
        return session.userId > 0L && (
            settingsStore.hasUsedVipDailyRefresh(session.userId, today()) ||
                cache.getString(vipRefreshKey(session.userId)) == "1"
            )
    }

    /** The server's afresh request is sent only after membership and the local daily limit pass. */
    suspend fun refreshDailyForVip(): AppResult<List<Song>> = vipRefreshMutex.withLock {
        val session = auth.currentSession()
        if (!session.loggedIn || !session.vip || session.userId <= 0L) {
            return@withLock AppResult.Failure(-1, "仅会员可刷新每日推荐")
        }
        if (hasUsedVipDailyRefresh()) return@withLock AppResult.Failure(-1, "今天的会员刷新机会已使用")
        when (val response = api.dailyRecommend(afresh = true)) {
            is AppResult.Failure -> response
            is AppResult.Success -> {
                val songs = response.data.data?.dailySongs.orEmpty()
                if (response.data.code != 200 || songs.isEmpty()) {
                    AppResult.Failure(response.data.code, "会员刷新未返回新歌单，请稍后重试")
                } else {
                    // Persist the one-use marker outside ContentCache before optional caching:
                    // clearing downloaded data must not grant another daily refresh.
                    settingsStore.markVipDailyRefresh(session.userId, today())
                    try {
                        cache.putString("daily:${today()}", json.encodeToString(ListSerializer(Song.serializer()), songs))
                        cache.remove("home:${today()}")
                        cache.putString(vipRefreshKey(session.userId), "1")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The new list is still valid and shown immediately; disk cache is optional.
                    }
                    songs.asSuccess()
                }
            }
        }
    }

    suspend fun dailyHistoryDates(): AppResult<List<String>> {
        return when (val response = api.dailyHistoryDates()) {
        is AppResult.Failure -> response
        is AppResult.Success -> {
            val root = response.data
            if (root["code"]?.toString() != "200") return AppResult.Failure(-1, "历史日推暂不可用")
            val data = root["data"]
            val entries = when (data) {
                is JsonArray -> data
                is JsonObject -> (data["dates"] ?: data["days"] ?: data["list"]) as? JsonArray
                else -> null
            } ?: (root["dates"] as? JsonArray)
            val dates = entries.orEmpty().mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.contentOrNull
                    is JsonObject -> (entry["date"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }.filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }.distinct()
            dates.asSuccess()
        }
    }
    }

    suspend fun dailyHistoryDetail(date: String): AppResult<List<Song>> {
        if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return AppResult.Failure(-1, "历史日推日期无效")
        }
        return when (val response = api.dailyHistoryDetail(date)) {
            is AppResult.Failure -> AppResult.Failure(response.code, "这一天的历史日推暂不可用，请稍后重试")
            is AppResult.Success -> {
                val root = response.data
                if (root["code"]?.toString() != "200") return AppResult.Failure(-1, "历史日推读取失败")
                val data = root["data"]
                val songs: JsonElement? = when (data) {
                    is JsonArray -> data
                    is JsonObject -> data["songs"] ?: data["dailySongs"] ?: data["recommend"]
                    else -> root["songs"] ?: root["recommend"]
                }
                val list = try {
                    songs?.let { json.decodeFromJsonElement(ListSerializer(Song.serializer()), it) }.orEmpty()
                } catch (_: Exception) { emptyList() }
                if (list.isEmpty()) AppResult.Failure(-1, "这一天没有可读取的推荐歌曲") else list.asSuccess()
            }
        }
    }

    /** 排行榜（带缓存，供首页横滑与排行榜页共用） */
    suspend fun toplistDetail(forceRefresh: Boolean = false): AppResult<List<Playlist>> {
        val cacheKey = "toplist:detail"
        if (!forceRefresh) {
            readCachePlaylists(cacheKey)?.takeIf { it.isNotEmpty() }?.let { return it.asSuccess() }
        }
        return when (val r = api.toplistDetail()) {
            is AppResult.Success -> {
                if (r.data.code != 200) {
                    return AppResult.Failure(r.data.code, "排行榜读取失败(${r.data.code})")
                }
                val list = r.data.list
                if (list.isNotEmpty()) {
                    cache.putString(cacheKey, json.encodeToString(ListSerializer(Playlist.serializer()), list))
                }
                list.asSuccess()
            }
            is AppResult.Failure -> r
        }
    }

    /**
     * Discovery artists are not derived from followed users or search history.  The endpoint is
     * ordered, therefore a manual refresh advances to its next page instead of requesting the
     * same first eight artists and claiming the screen changed.
     */
    suspend fun topArtists(limit: Int = 8, refresh: Boolean = false): AppResult<List<Artist>> {
        val requestedPage = if (refresh) artistPage + 1 else artistPage
        return when (val result = api.topArtists(limit = limit, offset = requestedPage * limit)) {
        is AppResult.Success -> when {
            result.data.code != 200 -> AppResult.Failure(result.data.code, "推荐歌手读取失败(${result.data.code})")
            result.data.artists.none { it.id > 0L } -> AppResult.Failure(-1, "推荐歌手暂无结果")
            else -> {
                artistPage = requestedPage
                result.data.artists.filter { it.id > 0L }.take(limit).asSuccess()
            }
        }
        is AppResult.Failure -> result
        }
    }

    /** 私人 FM：金刚区「私人 FM」点击后直接起播 */
    suspend fun personalFm(): AppResult<List<Song>> =
        when (val r = api.personalFm()) {
            is AppResult.Success -> r.data.data.asSuccess()
            is AppResult.Failure -> r
        }

    /** 只读缓存（不触发网络），供二级页秒开 */
    suspend fun cached(): HomeData? = readCache("home:" + today())

    private suspend fun readCache(cacheKey: String): HomeData? {
        val cached = cache.getString(cacheKey) ?: return null
        return runCatching { json.decodeFromString(HomeData.serializer(), cached) }.getOrNull()
    }

    private suspend fun readCacheSongs(cacheKey: String): List<Song>? {
        val cached = cache.getString(cacheKey) ?: return null
        return runCatching {
            json.decodeFromString(ListSerializer(Song.serializer()), cached)
        }.getOrNull()
    }

    private suspend fun readCachePlaylists(cacheKey: String): List<Playlist>? {
        val cached = cache.getString(cacheKey) ?: return null
        return runCatching {
            json.decodeFromString(ListSerializer(Playlist.serializer()), cached)
        }.getOrNull()
    }

    private companion object {
        const val PLAYLIST_LIMIT = 6
        const val PLAYLIST_POOL_LIMIT = 30
        const val PLAYLIST_WINDOW_CACHE_KEY = "home:recommendation-window:v1"
        const val PLAYLIST_CATALOG_CURSOR_CACHE_KEY = "home:recommendation-catalog-cursor:v1"
        const val HEART_THROB_PLAYLIST_LIMIT = 100
        const val HEART_THROB_SONG_LIMIT = 30
        const val HOME_SONG_CATALOG_PAGE_SIZE = 6
        const val HOME_SONG_EMPTY_PAGE_SCAN_LIMIT = 3
        const val HOME_SONG_DETAIL_TRACK_LIMIT = 30
        const val HOME_SONG_DETAIL_CONCURRENCY = 3
    }
}
