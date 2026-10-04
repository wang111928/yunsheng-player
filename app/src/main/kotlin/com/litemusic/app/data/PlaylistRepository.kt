package com.litemusic.app.data

import com.litemusic.data.cache.ContentCache
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Song
import com.litemusic.shared.model.SongDetailResponse
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.asSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Embedded tracks can be only a preview; trackIds provide the playlist's ordered membership. */
internal suspend fun resolvePlaylistTracks(
    api: NMApi,
    playlist: Playlist,
    limit: Int = Int.MAX_VALUE,
): AppResult<List<Song>> = resolvePlaylistTracks(playlist, limit, api::getSongDetail)

internal suspend fun resolvePlaylistTracks(
    playlist: Playlist,
    limit: Int,
    loadDetails: suspend (List<Long>) -> AppResult<SongDetailResponse>,
): AppResult<List<Song>> {
    val ids = playlist.trackIds.map { it.id }.filter { it > 0L }.distinct().take(limit)
    if (ids.isEmpty()) {
        if (playlist.tracks.isNotEmpty()) return playlist.tracks.distinctBy { it.id }.take(limit).asSuccess()
        return if (playlist.trackCount > 0) {
            AppResult.Failure(-1, "歌单显示有 ${playlist.trackCount} 首歌，但歌曲详情暂未返回")
        } else emptyList<Song>().asSuccess()
    }
    val songs = playlist.tracks.filter { it.id > 0L }.associateByTo(mutableMapOf()) { it.id }
    val missing = ids.filterNot(songs::containsKey)
    for (batch in missing.chunked(SONG_DETAIL_BATCH_SIZE)) {
        when (val result = loadDetails(batch)) {
            is AppResult.Failure -> return result
            is AppResult.Success -> {
                if (result.data.code != 200) {
                    return AppResult.Failure(result.data.code, "歌曲详情读取失败(${result.data.code})")
                }
                result.data.songs.forEach { song -> songs[song.id] = song }
            }
        }
    }
    val ordered = ids.mapNotNull(songs::get)
    return if (ordered.isEmpty()) AppResult.Failure(-1, "歌单歌曲详情暂未返回")
    else ordered.asSuccess()
}

/**
 * Resolve an ordered list of song IDs in bounded detail requests.  A non-empty
 * source list must never be presented as an empty collection just because the
 * detail endpoint returned a business error or no matching songs.
 */
internal suspend fun resolveSongIds(
    ids: List<Long>,
    loadDetails: suspend (List<Long>) -> AppResult<SongDetailResponse>,
): AppResult<List<Song>> {
    val requested = ids.filter { it > 0L }.distinct()
    if (requested.isEmpty()) return emptyList<Song>().asSuccess()
    val byId = mutableMapOf<Long, Song>()
    for (batch in requested.chunked(SONG_DETAIL_BATCH_SIZE)) {
        when (val result = loadDetails(batch)) {
            is AppResult.Failure -> return result
            is AppResult.Success -> {
                if (result.data.code != 200) {
                    return AppResult.Failure(result.data.code, "歌曲详情读取失败(${result.data.code})")
                }
                result.data.songs.forEach { song -> byId[song.id] = song }
            }
        }
    }
    val ordered = requested.mapNotNull(byId::get)
    return if (ordered.isEmpty()) AppResult.Failure(-1, "红心歌曲详情暂未返回") else ordered.asSuccess()
}

private const val SONG_DETAIL_BATCH_SIZE = 100

/** Membership checks need IDs, not playable metadata for every existing song. */
internal fun playlistMembershipForImport(playlist: Playlist): AppResult<Set<Long>> {
    val ids = (playlist.trackIds.map { it.id } + playlist.tracks.map { it.id })
        .filter { it > 0L }.toSet()
    return if (playlist.trackCount > ids.size) {
        AppResult.Failure(-1, "目标歌单歌曲 ID 未完整返回，请刷新后重试")
    } else ids.asSuccess()
}

/** 歌单与收藏：详情/CRUD/加删歌/红心 */
class PlaylistRepository(
    private val api: NMApi,
    private val cache: ContentCache,
    private val auth: com.litemusic.app.data.AuthRepository,
) {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val _likedIds = MutableStateFlow<Set<Long>>(emptySet())
    private var likedSession: com.litemusic.data.prefs.AuthStore.Session? = null

    private suspend fun belongsToCurrentLogin(expected: com.litemusic.data.prefs.AuthStore.Session): Boolean {
        val current = auth.currentSession()
        return current.userId == expected.userId && current.sameLoginAs(expected)
    }

    private fun prepareLikedCache(expected: com.litemusic.data.prefs.AuthStore.Session) {
        val previous = likedSession
        if (previous == null || previous.userId != expected.userId || !previous.sameLoginAs(expected)) {
            _likedIds.value = emptySet()
            likedSession = expected
        }
    }

    /** 全局共享的红心集合：播放页 / 歌单页 / 我的页共用同一份状态 */
    val likedIds: StateFlow<Set<Long>> = _likedIds.asStateFlow()

    suspend fun currentUserId(): Long = auth.ensureUserId()

    suspend fun myPlaylists(): AppResult<List<Playlist>> {
        val uid = auth.ensureUserId()
        if (uid == 0L) return AppResult.Failure(-1, "未登录")
        val expected = auth.currentSession()
        val unique = LinkedHashMap<Long, Playlist>()
        var offset = 0
        repeat(MAX_PLAYLIST_PAGES) {
            when (val result = api.getUserPlaylists(uid, offset, PLAYLIST_PAGE_SIZE)) {
                is AppResult.Failure -> return result
                is AppResult.Success -> {
                    if (!belongsToCurrentLogin(expected)) return AppResult.Failure(-1, "登录状态已变化，请重新刷新")
                    if (result.data.code != 200) {
                        return AppResult.Failure(result.data.code, "歌单读取失败(${result.data.code})")
                    }
                    val page = result.data.playlist
                    val before = unique.size
                    page.forEach { playlist -> if (playlist.id > 0L) unique.putIfAbsent(playlist.id, playlist) }
                    if (!result.data.more) {
                        return AppResult.Success(unique.values.toList())
                    }
                    if (page.isEmpty() || unique.size == before) return AppResult.Failure(-1, "歌单分页返回重复或空数据，请重试")
                    offset += page.size
                }
            }
        }
        return AppResult.Failure(-1, "歌单数量超过分页上限，请重试")
    }

    private companion object {
        const val PLAYLIST_PAGE_SIZE = 30
        const val MAX_PLAYLIST_PAGES = 100
        // v2 could cache only the endpoint's embedded preview instead of the complete ID list.
        const val PLAYLIST_CACHE_SCHEMA = "playlist:v3:"
    }

    suspend fun detail(id: Long, force: Boolean = false): AppResult<Playlist> {
        val cacheKey = PLAYLIST_CACHE_SCHEMA + id
        if (!force) {
            val cached = cache.getString(cacheKey)
            if (cached != null) {
                runCatching { return json.decodeFromString<Playlist>(cached).asSuccess() }
            }
        }
        val r = api.getPlaylistDetail(id)
        if (r is AppResult.Success) {
            if (r.data.code != 200) return AppResult.Failure(r.data.code, "歌单读取失败(${r.data.code})")
            val pl = r.data.playlist
            if (pl != null) {
                val tracks = when (val resolved = resolvePlaylistTracks(api, pl)) {
                    is AppResult.Success -> resolved.data
                    is AppResult.Failure -> return resolved
                }
                val complete = pl.copy(tracks = tracks)
                cache.putString(cacheKey, json.encodeToString(Playlist.serializer(), complete))
                return complete.asSuccess()
            }
            return AppResult.Failure(-1, "歌单不存在")
        }
        return r as AppResult.Failure
    }

    suspend fun create(name: String) = api.createPlaylist(name)

    /** Import previews deliberately bypass the full-detail cache and resolve only this prefix. */
    suspend fun previewForImport(id: Long, limit: Int): AppResult<Playlist> {
        require(limit in 1..500)
        return when (val result = api.getPlaylistDetail(id, n = limit)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val playlist = result.data.playlist
                if (result.data.code != 200 || playlist == null) {
                    AppResult.Failure(result.data.code, "歌单读取失败")
                } else when (val resolved = resolvePlaylistTracks(api, playlist, limit)) {
                    is AppResult.Failure -> resolved
                    is AppResult.Success -> playlist.copy(tracks = resolved.data).asSuccess()
                }
            }
        }
    }

    /** Always read fresh membership before writing; no full-song expansion or detail cache. */
    suspend fun membershipForImport(id: Long): AppResult<Set<Long>> = when (val result = api.getPlaylistDetail(id)) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val playlist = result.data.playlist
            when {
                result.data.code != 200 -> AppResult.Failure(result.data.code, "目标歌单读取失败")
                playlist == null -> AppResult.Failure(-1, "目标歌单不存在")
                else -> playlistMembershipForImport(playlist)
            }
        }
    }

    suspend fun update(id: Long, name: String?, desc: String?) = api.updatePlaylist(id, name, desc)

    suspend fun delete(ids: List<Long>) = api.deletePlaylist(ids)

    suspend fun addTracks(pid: Long, ids: List<Long>) = api.addTracks(pid, ids)

    suspend fun removeTracks(pid: Long, ids: List<Long>) = api.delTracks(pid, ids)

    suspend fun subscribe(pid: Long, subscribe: Boolean): AppResult<*> {
        val r = api.subscribePlaylist(pid, subscribe)
        if (r is AppResult.Success) detailCacheInvalidate(pid)
        return r
    }

    /** 失效歌单详情缓存（收藏/取消收藏/删歌后调用） */
    suspend fun detailCacheInvalidate(id: Long) {
        cache.remove(PLAYLIST_CACHE_SCHEMA + id)
        cache.remove("playlist:$id")
    }

    // ---- 红心收藏 ----
    suspend fun like(songId: Long, like: Boolean): AppResult<*> {
        if (auth.ensureUserId() <= 0L) return AppResult.Failure(-1, "未登录")
        val expected = auth.currentSession()
        prepareLikedCache(expected)
        val r = api.likeSong(songId, like)
        if (r is AppResult.Success) {
            if (r.data.code != 200) return AppResult.Failure(r.data.code, "收藏操作失败(${r.data.code})")
            if (!belongsToCurrentLogin(expected)) return AppResult.Failure(-1, "登录状态已变化，收藏结果未写入")
            _likedIds.update { if (like) it + songId else it - songId }
        }
        return r
    }

    /**
     * Fetch the current login's red-heart IDs.  This exposes failures to the
     * liked-song screen; the legacy set-returning method below remains for
     * badges that can safely retain their last known value.
     */
    suspend fun loadLikedIdsResult(force: Boolean = false): AppResult<List<Long>> {
        // 注意：uid 仅作为参数透传，服务端在 uid 缺省/为 0 时返回当前登录用户的红心列表，
        // 所以这里绝不能再因 uid==0 提前返回（历史 bug：本地 uid 未落库 → 该页永远空白）。
        val uid = auth.ensureUserId()
        val expected = auth.currentSession()
        prepareLikedCache(expected)
        if (uid <= 0L) return AppResult.Failure(-1, "未登录")
        if (!force && _likedIds.value.isNotEmpty()) return AppResult.Success(_likedIds.value.toList())
        val r = api.getLikeList(uid)
        when (r) {
            is AppResult.Success -> {
                if (!belongsToCurrentLogin(expected)) return AppResult.Failure(-1, "登录状态已变化，请重新刷新")
                if (r.data.code != 200) return AppResult.Failure(r.data.code, "红心列表读取失败(${r.data.code})")
                val orderedIds = r.data.ids.filter { it > 0L }.distinct()
                _likedIds.value = orderedIds.toSet()
                com.litemusic.app.util.DbgLog.w("Liked", "loadLikedIds n=" + r.data.ids.size)
                return AppResult.Success(orderedIds)
            }
            is AppResult.Failure -> {
                com.litemusic.app.util.DbgLog.w("Liked", "loadLikedIds FAIL code=" + r.code)
                return r
            }
        }
    }

    suspend fun loadLikedIds(force: Boolean = false): Set<Long> = when (val result = loadLikedIdsResult(force)) {
        is AppResult.Success -> result.data.toSet()
        is AppResult.Failure -> _likedIds.value
    }

    suspend fun likedSongs(forceRefresh: Boolean = false): AppResult<List<Song>> {
        val expected = auth.currentSession()
        val ids = when (val result = loadLikedIdsResult(forceRefresh)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }
        val resolved = resolveSongIds(ids, api::getSongDetail)
        if (!belongsToCurrentLogin(expected)) return AppResult.Failure(-1, "登录状态已变化，请重新刷新")
        if (resolved is AppResult.Success) {
            com.litemusic.app.util.DbgLog.w("Liked", "likedSongs ids=" + ids.size + " detail=" + resolved.data.size)
        }
        return resolved
    }
}
