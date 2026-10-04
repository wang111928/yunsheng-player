package com.litemusic.app.data

import com.litemusic.data.db.SearchHistoryDao
import com.litemusic.data.db.SearchHistoryEntity
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Album
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Profile
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.asSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Some cloud-search responses expose only a total count and omit `hasMore`. */
internal fun pageHasMore(
    offset: Int,
    received: Int,
    total: Int,
    serverHasMore: Boolean,
    pageSize: Int = 20,
): Boolean = serverHasMore || when {
    total > 0 -> offset + received < total
    else -> received >= pageSize
}

/** 搜索：歌曲/歌单/歌手/用户 + 热搜 + 历史记录 */
class SearchRepository(
    private val api: NMApi,
    private val historyDao: SearchHistoryDao,
) {
    /** 网易云 cloudsearch 的 type 编码 */
    object Type {
        const val SONG = 1
        const val ARTIST = 100
        const val PLAYLIST = 1000
        const val USER = 1002
    }

    data class SearchBundle(
        val songs: List<Song> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val users: List<Profile> = emptyList(),
        val hasMore: Boolean = false,
        val songHasMore: Boolean = false,
        val playlistHasMore: Boolean = false,
        val artistHasMore: Boolean = false,
        val userHasMore: Boolean = false,
        val songTotal: Int = 0,
        val playlistTotal: Int = 0,
    )

    suspend fun hot() = api.hotSearch()

    suspend fun albumsForImport(keyword: String): AppResult<List<Album>> =
        api.search(keyword, type = 10, offset = 0, limit = 10).map { it.result?.albums.orEmpty() }

    suspend fun albumSongsForImport(id: Long) = api.albumSongsForImport(id)

    suspend fun search(keyword: String, type: Int, offset: Int, limit: Int = 20): AppResult<SearchBundle> {
        val r = api.search(keyword, type, offset, limit)
        return r.map { s ->
            val payload = s.result
            val songs = payload?.songs.orEmpty()
            val playlists = payload?.playlists.orEmpty()
            val artists = payload?.artists.orEmpty()
            val users = payload?.users.orEmpty()
            val serverHasMore = payload?.hasMore == true
            val songHasMore = type == Type.SONG && pageHasMore(
                offset, songs.size, payload?.songCount ?: 0, serverHasMore, limit,
            )
            val playlistHasMore = type == Type.PLAYLIST && pageHasMore(
                offset, playlists.size, payload?.playlistCount ?: 0, serverHasMore, limit,
            )
            val artistHasMore = type == Type.ARTIST && pageHasMore(
                offset = offset,
                received = artists.size,
                total = 0,
                serverHasMore = serverHasMore,
                pageSize = limit,
            )
            val userHasMore = type == Type.USER && pageHasMore(
                offset = offset,
                received = users.size,
                total = 0,
                serverHasMore = serverHasMore,
                pageSize = limit,
            )
            SearchBundle(
                songs = songs,
                playlists = playlists,
                artists = artists,
                users = users,
                hasMore = songHasMore || playlistHasMore || artistHasMore || userHasMore,
                songHasMore = songHasMore,
                playlistHasMore = playlistHasMore,
                artistHasMore = artistHasMore,
                userHasMore = userHasMore,
                songTotal = payload?.songCount ?: 0,
                playlistTotal = payload?.playlistCount ?: 0,
            )
        }
    }

    /**
     * 结果页分组搜索：并发拉取单曲 / 歌手 / 歌单 / 用户四类，合并成一个 [SearchBundle]。
     * 只有四类全部失败才返回失败；只要有一类成功就返回成功（其余分组为空）。
     */
    suspend fun searchAll(keyword: String, limit: Int = 20): AppResult<SearchBundle> = coroutineScope {
        val songJob = async { search(keyword, Type.SONG, 0, limit) }
        val artistJob = async { search(keyword, Type.ARTIST, 0, limit) }
        val playlistJob = async { search(keyword, Type.PLAYLIST, 0, limit) }
        val userJob = async { search(keyword, Type.USER, 0, limit) }

        val song = songJob.await()
        val artist = artistJob.await()
        val playlist = playlistJob.await()
        val user = userJob.await()

        if (song is AppResult.Failure && artist is AppResult.Failure &&
            playlist is AppResult.Failure && user is AppResult.Failure
        ) {
            return@coroutineScope song
        }

        SearchBundle(
            songs = (song as? AppResult.Success)?.data?.songs.orEmpty(),
            playlists = (playlist as? AppResult.Success)?.data?.playlists.orEmpty(),
            artists = (artist as? AppResult.Success)?.data?.artists.orEmpty(),
            users = (user as? AppResult.Success)?.data?.users.orEmpty(),
            hasMore = listOf(song, artist, playlist, user).any { (it as? AppResult.Success)?.data?.hasMore == true },
            songHasMore = (song as? AppResult.Success)?.data?.songHasMore == true,
            playlistHasMore = (playlist as? AppResult.Success)?.data?.playlistHasMore == true,
            artistHasMore = (artist as? AppResult.Success)?.data?.artistHasMore == true,
            userHasMore = (user as? AppResult.Success)?.data?.userHasMore == true,
            songTotal = (song as? AppResult.Success)?.data?.songTotal ?: 0,
            playlistTotal = (playlist as? AppResult.Success)?.data?.playlistTotal ?: 0,
        ).asSuccess()
    }

    suspend fun history(): List<String> = historyDao.recent(10).map { it.keyword }

    suspend fun addHistory(keyword: String) {
        if (keyword.isBlank()) return
        historyDao.insert(SearchHistoryEntity(keyword = keyword, ts = System.currentTimeMillis()))
    }

    suspend fun removeHistory(keyword: String) = historyDao.delete(keyword)
}
