package com.litemusic.app.data

import com.litemusic.data.cache.ContentCache
import com.litemusic.shared.api.NMApi
import com.litemusic.app.util.DbgLog
import com.litemusic.shared.model.LyricResponse
import com.litemusic.shared.model.Song
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.AppResult

/**
 * 歌曲资源：详情 / 播放地址（音质档位）/ 歌词（双层缓存）。
 */
class SongRepository(
    private val api: NMApi,
    private val cache: ContentCache,
) {

    suspend fun detail(id: Long): AppResult<Song> {
        val songs = api.getSongDetail(listOf(id))
        return songs.map { it.songs.firstOrNull() ?: throw IllegalStateException("歌曲不存在") }
    }

    suspend fun details(ids: List<Long>): AppResult<List<Song>> {
        val valid = ids.filter { it > 0L }.distinct()
        if (valid.isEmpty()) return AppResult.Success(emptyList())
        return api.getSongDetail(valid).map { it.songs }
    }

    /**
     * 解析播放地址：本地歌曲直接返回路径，在线歌曲按音质请求（会员无损不会被暗降）。
     *  - 网易播放地址带有效期（路径内嵌时间戳，过期后 CDN 直接回 403），
     *    因此缓存值写入「过期时间|url」，读时校验；
     *  - [forceRefresh] 用于播放失败后强制重取（清掉旧缓存）。
     */
    suspend fun resolveUrl(item: QueueItem, forceRefresh: Boolean = false): String {
        item.localPath?.let { return it }
        val cacheKey = "url:" + item.id + ":" + item.quality.br
        if (forceRefresh) {
            DbgLog.w("SongRepo", "resolveUrl forceRefresh id=${item.id} br=${item.quality.br}")
            cache.remove(cacheKey)
        } else {
            val cached = cache.getString(cacheKey)
            if (cached != null) {
                val sep = cached.indexOf('|')
                if (sep > 0) {
                    val exp = cached.substring(0, sep).toLongOrNull()
                    val url = cached.substring(sep + 1)
                    if (exp != null && exp > System.currentTimeMillis() && url.isNotBlank()) {
                        DbgLog.w("SongRepo", "resolveUrl HIT id=${item.id} br=${item.quality.br} left=${(exp - System.currentTimeMillis()) / 1000}s url=${url.substringBefore('?')} authSecret=${url.contains("authSecret")}")
                        return url
                    }
                }
            }
        }
        val r = api.getSongUrl(listOf(item.id), item.quality.br)
        if (r is AppResult.Success) {
            val url = r.data.data.firstOrNull { it.url.isNotBlank() }?.url
            if (!url.isNullOrBlank()) {
                DbgLog.w("SongRepo", "resolveUrl MISS id=${item.id} br=${item.quality.br} level=${r.data.data.firstOrNull()?.level} url=${url.substringBefore('?')} authSecret=${url.contains("authSecret")} cdntag=${url.contains("cdntag")}")
                cache.putString(cacheKey, (System.currentTimeMillis() + URL_TTL_MS).toString() + "|" + url)
                return url
            }
        }
        DbgLog.e("SongRepo", "resolveUrl FAIL id=${item.id} br=${item.quality.br} result=$r")
        return ""
    }

    /** 歌词：原生 lrc/tlyric/yrc + 缓存 */
    suspend fun lyric(id: Long): AppResult<LyricResponse> {
        val cacheKey = "lyric:$id"
        val cached = cache.getString(cacheKey)
        if (cached != null) {
            runCatching {
                return AppResult.Success(
                    kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    }.decodeFromString<LyricResponse>(cached)
                )
            }
        }
        val r = api.getLyric(id)
        if (r is AppResult.Success) {
            cache.putString(cacheKey, kotlinx.serialization.json.Json.encodeToString(LyricResponse.serializer(), r.data))
        }
        return r
    }

    private companion object {
        /** 在线播放地址缓存有效期：超期即重取，避免把已失效的 URL 交给播放器（403） */
        const val URL_TTL_MS = 20 * 60 * 1000L
    }
}
