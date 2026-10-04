package com.litemusic.shared.api

import com.litemusic.shared.model.*
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.asFailure
import com.litemusic.shared.util.asSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 网易云音乐 API 服务（全部端点基于公开逆向资料整理，见 README 风险清单）
 */
internal fun eventPath(recommended: Boolean): String =
    if (recommended) "/api/v2/event/get" else "/api/v4/event/get"

/** Public profile-event route used by the official web profile page. */
internal fun userEventsPath(userId: Long): String = "/api/event/get/$userId"

internal fun userEventsQuery(time: Long, limit: Int): Map<String, String> = mapOf(
    "time" to time.toString(),
    "limit" to limit.toString(),
    "getcounts" to "true",
)

/** Official web private-message routes use weapi despite their `/api/` prefix. */
internal const val privateHistoryPath = "/weapi/msg/private/history"
internal const val privateMessageSendPath = "/weapi/msg/private/send"
internal const val playlistCatalogPath = "/weapi/playlist/list"
internal const val mvUrlPath = "/weapi/song/enhance/play/mv/url"
internal const val podcastStationPath = "/weapi/djradio/hot/v1"
internal const val audiobookStationPath = "/weapi/djradio/hot"
/** First-party Android 9.5.80 routes, observed in the original APK. */
internal const val dailyStyleConfigPath = "/api/homepage/daily/song/config/get"
internal const val dailyStyleSongsPath = "/api/homepage/category/daily/song/list"
internal const val dailyStyleSavePath = "/api/homepage/daily/song/tag/save"

internal fun mvUrlPayload(id: Long, resolution: Int): Map<String, Any> = mapOf(
    "id" to id,
    "r" to resolution,
)

internal fun privateHistoryPayload(userId: Long, limit: Int, time: Long): Map<String, Any> =
    mapOf("userId" to userId, "limit" to limit, "time" to time, "total" to "true")

internal fun privateMessageSendPayload(userIds: List<Long>, msg: String): Map<String, Any> =
    mapOf(
        "type" to "text",
        "msg" to msg,
        "userIds" to userIds.joinToString(",", prefix = "[", postfix = "]"),
    )

internal fun playlistCatalogPayload(offset: Int, limit: Int, order: String = "hot"): Map<String, Any> = mapOf(
    "cat" to "全部",
    "order" to order,
    "limit" to limit,
    "offset" to offset,
    "total" to true,
)

/**
 * The old recommend endpoints return one static window and silently ignore paging
 * arguments.  These two hot-radio endpoints are the corresponding paged feeds.
 */
internal fun radioStationPayload(audiobook: Boolean, offset: Int, limit: Int): Map<String, Any> = buildMap {
    put("offset", offset)
    put("limit", limit)
    if (audiobook) put("cateId", 10001)
}

internal fun dailyStyleSongsPayload(
    categoryId: Long? = null,
    tagIds: List<Long>,
    source: String? = null,
    songId: Long? = null,
): Map<String, Any> = buildMap {
    source?.takeIf { it.isNotBlank() }?.let { put("source", it) }
    categoryId?.takeIf { it > 0L }?.let { put("categoryId", it) }
    songId?.takeIf { it > 0L }?.let { put("songId", it) }
    // The client endpoint accepts one selected tag per category. Keep the comma-separated
    // representation used by official request builders rather than serialising a Kotlin list.
    tagIds.takeIf { it.isNotEmpty() }?.let { put("tagId", it.joinToString(",")) }
}

/** The official style chooser submits one category at a time as JSON in `tags`. */
internal fun dailyStyleSavePayload(categoryId: Long, tagIds: List<Long>): Map<String, Any> =
    mapOf("tags" to "{\"categoryId\":$categoryId,\"tagIds\":[${tagIds.distinct().joinToString(",")}]}")

/** Playlist detail defaults to the complete list; lightweight discovery callers can opt in. */
internal fun playlistDetailPayload(id: Long, n: Int): Map<String, Any> = mapOf(
    "id" to id,
    "n" to n,
    "s" to 8,
)

/** The detail endpoint's `c` field is JSON text before the enclosing request is encrypted. */
internal fun songDetailPayload(ids: List<Long>): Map<String, Any> = mapOf(
    "c" to ids.joinToString(",", prefix = "[", postfix = "]") { "{\"id\":$it}" },
)

/**
 * A recommendation response with a business error or an empty list is not a successful fallback.
 * Keeping this separate makes callers unable to mistake a 5xx/empty payload for usable content.
 */
fun resolveRecommendedPlaylists(
    primary: RecommendResource,
    fallback: PersonalizedPlaylist,
    limit: Int,
): AppResult<List<Playlist>> {
    if (primary.code == 200 && primary.recommend.isNotEmpty()) {
        return primary.recommend.take(limit).asSuccess()
    }
    if (fallback.code == 200 && fallback.result.isNotEmpty()) {
        return fallback.result.take(limit).asSuccess()
    }
    val code = when {
        fallback.code != 200 -> fallback.code
        primary.code != 200 -> primary.code
        else -> -1
    }
    return AppResult.Failure(code, "推荐歌单读取失败(${if (code > 0) code else "空结果"})")
}

/** Validates the business code before exposing intelligent-play songs to feature code. */
fun intelligenceSongs(response: IntelligencePlayResponse): AppResult<List<Song>> = when {
    response.code != 200 -> AppResult.Failure(response.code, "心动模式读取失败(${response.code})")
    response.data.none { it.songInfo?.id?.let { id -> id > 0L } == true } ->
        AppResult.Failure(-1, "心动模式暂无推荐歌曲")
    else -> response.data.mapNotNull { it.songInfo?.takeIf { song -> song.id > 0L } }.asSuccess()
}

internal fun intelligencePlayPayload(id: Long, pid: Long, sid: Long, count: Int): Map<String, Any> = mapOf(
    "songId" to id,
    "type" to "fromPlayOne",
    "playlistId" to pid,
    "startMusicId" to sid,
    "count" to count,
)

class NMApi(private val api: ApiClient) {

    private suspend fun <T> run(block: suspend () -> String, decode: (String) -> T): AppResult<T> =
        try {
            val raw = block()
            if (raw.contains("\"code\":-460") || raw.contains("\"code\":-462")) {
                // 风控响应里带 blockText 时优先展示（例如「绑定手机号或短信验证成功后，可进行下一步操作哦~」）
                val blockText = Regex("\"blockText\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
                AppResult.Failure(-460, blockText?.ifBlank { null } ?: "触发风控，请稍后再试")
            } else {
                decode(raw).asSuccess()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.asFailure()
        }

    private inline fun <reified T> dec(raw: String, x: ApiClient): T = x.decode<T>(raw)

    // ---------- 账号 ----------
    suspend fun getAccount(cookieOverrides: Map<String, String> = emptyMap()) =
        run({ api.weapiPost("/weapi/nuser/account/get", cookieOverrides = cookieOverrides) }) { api.decode<AccountResponse>(it) }
    suspend fun vipInfo(uid: Long) = run({ api.weapiPost("/weapi/music-vip-membership/client/vip/info", mapOf("userId" to uid)) }) { api.decode<kotlinx.serialization.json.JsonObject>(it) }
    suspend fun radioStations(audiobook: Boolean, offset: Int = 0, limit: Int = 30) = run({
        api.weapiPost(
            if (audiobook) audiobookStationPath else podcastStationPath,
            radioStationPayload(audiobook, offset, limit),
        )
    }) { api.decode<RadioStationResponse>(it) }
    /**
     * The web player reads a station program list from the public GET endpoint.
     * Sending it through weapi turns this read into an unsupported POST and the
     * service rejects it with HTTP 405 for many stations.
     */
    suspend fun radioPrograms(radioId: Long, limit: Int = 30, offset: Int = 0) = run({
        api.plainGet(
            "/api/dj/program/byradio",
            mapOf(
                "radioId" to radioId.toString(),
                "asc" to "false",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )
    }) { raw ->
        api.decode<RadioProgramResponse>(normalizeRadioProgramDescriptions(raw))
    }
    /**
     * The station list is intentionally lightweight and can omit mainSong.  The
     * official web player hydrates that row through this detail route before it
     * starts playback, so use the same public resource instead of inventing a
     * song from the program id.
     */
    suspend fun radioProgramDetail(programId: Long) = run({
        api.plainGet("/api/dj/program/detail", mapOf("id" to programId.toString()))
    }) { raw ->
        api.decode<RadioProgramDetailResponse>(normalizeRadioProgramDescription(raw))
    }
    suspend fun getUserDetail(uid: Long) = run({ api.weapiPost("/weapi/v1/user/detail/" + uid) }) { api.decode<UserDetail>(it) }
    suspend fun getSubCount() = run({ api.weapiPost("/weapi/subcount") }) { api.decode<SubCount>(it) }

    // ---------- 关注 / 粉丝 ----------
    suspend fun getFollows(uid: Long, offset: Int = 0, limit: Int = 30) =
        run({ api.weapiPost("/weapi/user/getfollows/" + uid, mapOf("offset" to offset, "limit" to limit, "order" to true)) }) { r ->
            api.decode<FollowListResponse>(r)
        }

    suspend fun getFolloweds(uid: Long, offset: Int = 0, limit: Int = 30) =
        run({ api.weapiPost("/weapi/user/getfolloweds/" + uid, mapOf("offset" to offset, "limit" to limit)) }) { r ->
            api.decode<FollowListResponse>(r)
        }

    // 关注/取关同一端点，用 t 区分（t=1 关注 / t=0 取关）。
    // 不能带 e_r=true：服务器会返回 AES-ECB 密文，而 decodeBody 只支持 hex / base64+gzip 两种封装，无法还原。
    suspend fun follow(uid: Long, follow: Boolean) =
        run({ api.eapiPost("/api/user/follow/" + uid, mapOf("t" to (if (follow) 1 else 0))) }) { api.decode<StatusResponse>(it) }

    // 云村动态：v4 关注流，v2 推荐流。正文 json 由 EventMapper 容错解析。
    suspend fun getEvents(recommended: Boolean, lastTime: Long = -1L, limit: Int = 20) =
        run({
            api.eapiPost(
                eventPath(recommended),
                mapOf(
                    "pagesize" to limit,
                    "lasttime" to lastTime,
                    "getcounts" to 1,
                ),
            )
        }) { api.decode<EventResponse>(it) }

    /** First-party RN community page source, separate from the following event timeline. */
    suspend fun getSquareEvents(cursor: String = "") = run({
        api.eapiPost(
            "/api/event/square/dual/feed/get",
            mapOf(
                "cursor" to cursor,
                "style" to "DUAL_COLUMN",
                "fromRN" to "true",
                "options" to "{\"ignoreBanner\":true}",
                "playingSongStr" to "",
            ),
        )
    }) { api.decode<EventResponse>(it) }

    /**
     * User-profile events are a public GET feed. Its cursor is named `time`
     * rather than the following/recommended feeds' `lasttime`.
     */
    suspend fun userEvents(userId: Long, time: Long = -1L, limit: Int = 20) = run({
        api.plainGet(userEventsPath(userId), userEventsQuery(time, limit))
    }) { api.decode<EventResponse>(it) }

    // ---------- 歌单 ----------
    suspend fun getUserPlaylists(uid: Long, offset: Int = 0, limit: Int = 30) =
        run({ api.weapiPost("/weapi/user/playlist", mapOf("uid" to uid, "offset" to offset, "limit" to limit)) }) { api.decode<UserPlaylistResponse>(it) }

    suspend fun getPlaylistDetail(id: Long, n: Int = 100000) =
        run({ api.weapiPost("/weapi/v6/playlist/detail", playlistDetailPayload(id, n)) }) { api.decode<PlaylistDetailResponse>(it) }

    suspend fun createPlaylist(name: String) =
        run({ api.eapiPost("/api/playlist/create", mapOf("name" to name, "privacy" to 0)) }) { api.decode<PlaylistDetailResponse>(it) }

    suspend fun updatePlaylist(id: Long, name: String?, desc: String?) =
        run({
            val p = mutableMapOf<String, Any?>("id" to id)
            name?.let { p["name"] = it }
            desc?.let { p["description"] = it }
            api.eapiPost("/api/playlist/update", p)
        }) { api.decode<StatusResponse>(it) }

    suspend fun deletePlaylist(ids: List<Long>) =
        run({ api.eapiPost("/api/playlist/remove", mapOf("ids" to ids.joinToString(",", "[", "]"))) }) { r ->
            api.decode<StatusResponse>(r).also {
                if (it.code != 200) throw IllegalStateException(it.message.ifBlank { "删除失败(" + it.code + ")" })
            }
        }

    suspend fun addTracks(pid: Long, ids: List<Long>) =
        run({
            api.eapiPost(
                "/api/playlist/manipulate/tracks",
                mapOf("pid" to pid, "trackIds" to ids.joinToString(",", "[", "]"), "op" to "add", "imme" to true)
            )
        }) { api.decode<PlaylistManipulateResponse>(it) }

    suspend fun delTracks(pid: Long, ids: List<Long>) =
        run({
            api.eapiPost(
                "/api/playlist/manipulate/tracks",
                mapOf("pid" to pid, "trackIds" to ids.joinToString(",", "[", "]"), "op" to "del", "imme" to true)
            )
        }) { api.decode<PlaylistManipulateResponse>(it) }

    suspend fun subscribePlaylist(id: Long, subscribe: Boolean) =
        run({
            // 收藏与取消收藏是两个不同端点（取消收藏用 /api/playlist/unsubscribe，t=2 会返回 501）
            if (subscribe) api.eapiPost("/api/playlist/subscribe", mapOf("id" to id, "t" to 1))
            else api.eapiPost("/api/playlist/unsubscribe", mapOf("id" to id))
        }) { r ->
            api.decode<StatusResponse>(r).also {
                if (it.code != 200) throw IllegalStateException(it.message.ifBlank { "操作失败(" + it.code + ")" })
            }
        }

    // ---------- 红心 / 歌单收藏 ----------
    suspend fun likeSong(songId: Long, like: Boolean) =
        run({ api.eapiPost("/api/radio/like", mapOf("trackId" to songId, "like" to like)) }) { api.decode<LikeResponse>(it) }

    suspend fun getLikeList(uid: Long) =
        run({ api.weapiPost("/weapi/song/like/get", mapOf("uid" to uid)) }) { api.decode<LikeListResponse>(it) }

    // ---------- 歌曲 ----------
    suspend fun getSongDetail(ids: List<Long>) =
        run({
            api.weapiPost("/weapi/v3/song/detail", songDetailPayload(ids))
        }) { api.decode<SongDetailResponse>(it) }

    suspend fun getSongUrl(ids: List<Long>, br: Int = 320000) =
        run({
            api.eapiPost(
                "/api/song/enhance/player/url",
                mapOf("ids" to ids.joinToString(",", prefix = "[", postfix = "]"), "br" to br)
            )
        }) { api.decode<SongUrlResponse>(it) }

    suspend fun getLyric(id: Long) =
        run({ api.weapiPost("/weapi/song/lyric", mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1, "rv" to -1)) }) { api.decode<LyricResponse>(it) }

    /** This URL is short-lived, so retrieve it every time the video screen opens. */
    suspend fun getMvUrl(id: Long, resolution: Int = 720) =
        run({ api.weapiPost(mvUrlPath, mvUrlPayload(id, resolution)) }) { api.decode<MvUrlResponse>(it) }

    // ---------- 推荐 / 搜索 ----------
    suspend fun dailyRecommend(limit: Int = 30, afresh: Boolean = false) =
        run({ api.weapiPost("/weapi/v3/discovery/recommend/songs", mapOf("limit" to limit, "total" to true, "afresh" to afresh)) }) { api.decode<DailyRecommend>(it) }

    /** Daily style settings and tracks are independent from ordinary daily recommendations. */
    suspend fun dailyStyleConfig() = run({
        api.eapiPost(dailyStyleConfigPath)
    }) { api.decode<DailyStyleConfigResponse>(it) }

    suspend fun dailyStyleSongs(categoryId: Long?, tagIds: List<Long>) = run({
        api.eapiPost(dailyStyleSongsPath, dailyStyleSongsPayload(categoryId, tagIds))
    }) { api.decode<DailyStyleSongsResponse>(it) }

    suspend fun saveDailyStyleTags(categoryId: Long, tagIds: List<Long>) = run({
        api.eapiPost(dailyStyleSavePath, dailyStyleSavePayload(categoryId, tagIds))
    }) { api.decode<DailyStyleSaveResponse>(it) }

    suspend fun dailyHistoryDates() = run({
        api.weapiPost(
            "/api/discovery/recommend/songs/history/recent",
            cookieOverrides = mapOf("os" to "ios"),
        )
    }) { api.decode<kotlinx.serialization.json.JsonObject>(it) }

    suspend fun dailyHistoryDetail(date: String): AppResult<kotlinx.serialization.json.JsonObject> {
        val payload = mapOf("date" to date)
        val iosCookie = mapOf("os" to "ios")
        val primary = run({
            api.weapiPost("/api/discovery/recommend/songs/history/detail", payload, iosCookie)
        }) { api.decode<kotlinx.serialization.json.JsonObject>(it) }
        if (primary is AppResult.Success && primary.data["code"]?.toString() == "200") return primary
        if (primary is AppResult.Failure && primary.code == -460) return primary
        return run({
            api.weapiPost("/weapi/discovery/recommend/songs/history/detail", payload, iosCookie)
        }) { api.decode<kotlinx.serialization.json.JsonObject>(it) }
    }

    suspend fun personalized(limit: Int = 6) =
        run({ api.weapiPost("/weapi/personalized/playlist", mapOf("limit" to limit, "n" to limit)) }) { api.decode<PersonalizedPlaylist>(it) }

    /**
     * 推荐歌单（带封面）。
     *
     * 旧端点 /weapi/personalized/playlist 实测已不返回封面字段（只回 id/name/trackCount/playCount），
     * 首页会出现「推荐歌单只有名字没有图」。/weapi/v1/discovery/recommend/resource 带 picUrl，优先用它；
     * 万一它抽风拿不到数据，再回落到旧端点保证首页不空白。
     */
    suspend fun recommendPlaylists(limit: Int = 6): AppResult<List<Playlist>> {
        val primary = run({ api.weapiPost("/weapi/v1/discovery/recommend/resource") }) {
            api.decode<RecommendResource>(it)
        }
        val fallback = personalized(limit)
        return when (fallback) {
            is AppResult.Success -> {
                val primaryData = (primary as? AppResult.Success<RecommendResource>)?.data
                    ?: RecommendResource(code = -1)
                val realPool = (primaryData.recommend + fallback.data.result)
                    .distinctBy { it.id }
                    .take(limit)
                if (primaryData.code == 200 && realPool.isNotEmpty()) realPool.asSuccess()
                else resolveRecommendedPlaylists(primaryData, fallback.data, limit)
            }
            is AppResult.Failure -> when (primary) {
                is AppResult.Success -> resolveRecommendedPlaylists(
                    primary = primary.data,
                    fallback = PersonalizedPlaylist(code = -1),
                    limit = limit,
                )
                is AppResult.Failure -> fallback
            }
        }
    }

    /** 排行榜列表（raw 返回 coverImgUrl/updateFrequency） */
    suspend fun toplistDetail() =
        run({ api.weapiPost("/weapi/toplist/detail") }) { api.decode<ToplistResponse>(it) }

    /** Hot playlist catalog. Unlike the toplist endpoint this supports real offset pagination. */
    suspend fun playlistCatalog(offset: Int = 0, limit: Int = 30, order: String = "hot") =
        run({
            api.weapiPost(
                playlistCatalogPath,
                playlistCatalogPayload(offset, limit, order),
            )
        }) { api.decode<PlaylistCatalogResponse>(it) }

    /** 私人 FM：返回一批推荐歌曲，供「私人FM」金刚区直接起播 */
    suspend fun personalFm() =
        run({ api.weapiPost("/weapi/v1/radio/get") }) { api.decode<RadioFmResponse>(it) }

    /** 网易云「心动模式」：从指定歌单和种子歌曲生成智能播放队列。 */
    suspend fun intelligencePlay(
        id: Long,
        pid: Long,
        sid: Long = id,
        count: Int = 30,
    ): AppResult<List<Song>> = when (val result = run({
        api.weapiPost(
            "/weapi/playmode/intelligence/list",
            intelligencePlayPayload(id, pid, sid, count),
        )
    }) { api.decode<IntelligencePlayResponse>(it) }) {
        is AppResult.Success -> intelligenceSongs(result.data)
        is AppResult.Failure -> result
    }

    suspend fun search(keyword: String, type: Int = 1, offset: Int = 0, limit: Int = 20): AppResult<SearchResult> =
        when (val result = run({
            api.weapiPost(
                "/weapi/cloudsearch/get/web",
                mapOf("s" to keyword, "type" to type, "limit" to limit, "offset" to offset, "total" to true)
            )
        }) { raw ->
            api.decode<SearchResult>(raw).also { decoded ->
                if (decoded.code == 200 && type in setOf(1, 10)) {
                    val root = api.json.parseToJsonElement(raw) as? JsonObject
                    val payload = root?.get("result") as? JsonObject
                    val rows = payload?.get(if (type == 1) "songs" else "albums")
                    val count = (payload?.get(if (type == 1) "songCount" else "albumCount") as? JsonPrimitive)?.intOrNull
                    require(rows is JsonArray || (rows == null && count == 0)) { "搜索返回内容不完整，请稍后重试" }
                }
            }
        }) {
            is AppResult.Failure -> result
            is AppResult.Success -> when {
                result.data.code != 200 -> AppResult.Failure(result.data.code, "搜索暂不可用(${result.data.code})")
                result.data.result == null -> AppResult.Failure(-1, "搜索返回内容不完整，请稍后重试")
                else -> result
            }
        }

    /** Official album track-list route; complements song-search recall during import. */
    suspend fun albumSongsForImport(id: Long): AppResult<List<Song>> =
        when (val result = run({ api.weapiPost("/weapi/v1/album/$id") }) { raw ->
            api.decode<AlbumSongsResponse>(raw).also { decoded ->
                if (decoded.code == 200) {
                    val root = api.json.parseToJsonElement(raw) as? JsonObject
                    require(root?.get("songs") is JsonArray) { "专辑歌曲返回内容不完整" }
                }
            }
        }) {
            is AppResult.Failure -> result
            is AppResult.Success -> if (result.data.code == 200) AppResult.Success(result.data.songs)
                else AppResult.Failure(result.data.code, "专辑歌曲暂不可用")
        }

    /** Artist IDs use the artist page route, never a user-profile route. */
    suspend fun artistDetail(id: Long) = run({
        api.weapiPost("/weapi/v1/artist/" + id)
    }) { api.decode<ArtistDetailResponse>(it) }

    /** Home discovery artists. A failed endpoint is exposed to UI as a failure. */
    suspend fun topArtists(limit: Int = 8, offset: Int = 0) = run({
        api.weapiPost(
            "/weapi/artist/top",
            mapOf("limit" to limit, "offset" to offset, "total" to true),
        )
    }) { api.decode<TopArtistsResponse>(it) }

    // 注意：/weapi/search/hot 必须带 type=1111，否则服务端返回 code=400「参数错误」→ 热门搜索空白
    suspend fun hotSearch() = run({ api.weapiPost("/weapi/search/hot", mapOf("type" to 1111)) }) { api.decode<HotSearchResponse>(it) }

    // ---------- 评论 ----------
    private fun songThreadId(songId: Long) = "R_SO_4_" + songId

    /** Reads comments for any resource thread, including song and radio-program threads. */
    suspend fun getCommentsForThread(threadId: String, offset: Int = 0, limit: Int = 20) =
        run({
            api.weapiPost(
                "/weapi/v1/resource/comments/" + threadId,
                commentPagePayload(threadId, offset, limit),
            )
        }) { api.decode<CommentResponse>(it) }

    suspend fun getComments(songId: Long, offset: Int = 0, limit: Int = 20) =
        getCommentsForThread(songThreadId(songId), offset, limit)

    /** Dynamic comments use an A_EV_2_* target, never an attached song ID. */
    suspend fun getEventComments(threadId: String, offset: Int = 0, limit: Int = 20) =
        getCommentsForThread(threadId, offset, limit)

    suspend fun getHotComments(songId: Long, limit: Int = 20) =
        getHotCommentsForThread(songThreadId(songId), limit)

    suspend fun getHotCommentsForThread(threadId: String, limit: Int = 20) =
        run({ api.weapiPost("/weapi/v1/resource/hotcomments/" + threadId, mapOf("rid" to threadId, "limit" to limit)) }) { api.decode<CommentResponse>(it) }

    suspend fun postComment(songId: Long, content: String) =
        run({
            api.eapiPost(
                "/api/resource/comments/add",
                mapOf("threadId" to songThreadId(songId), "content" to content)
            )
        }) { api.decode<CommentActionResponse>(it) }

    suspend fun postEventComment(threadId: String, content: String) = run({
        api.eapiPost(
            "/api/resource/comments/add",
            mapOf("threadId" to threadId, "content" to content),
        )
    }) { api.decode<CommentActionResponse>(it) }

    /** Event replies use the event's A_EV_2_* comment thread, not an attached song id. */
    suspend fun replyEventComment(threadId: String, commentId: Long, content: String) = run({
        api.eapiPost(
            "/api/v1/resource/comments/reply",
            mapOf("threadId" to threadId, "commentId" to commentId, "content" to content),
        )
    }) { api.decode<CommentActionResponse>(it) }

    /** Comment likes are scoped by their resource thread; the same route supports event threads. */
    suspend fun likeEventComment(threadId: String, commentId: Long, like: Boolean) = run({
        api.eapiPost(
            "/api/v1/comment/like",
            mapOf("threadId" to threadId, "commentId" to commentId, "like" to like),
        )
    }) { api.decode<CommentActionResponse>(it) }

    suspend fun likeEvent(threadId: String, like: Boolean) = run({
        api.weapiPost(
            if (like) "/weapi/resource/like" else "/weapi/resource/unlike",
            mapOf("threadId" to threadId),
        )
    }) { api.decode<CommentActionResponse>(it) }

    suspend fun replyComment(songId: Long, commentId: Long, content: String) =
        run({
            api.eapiPost(
                "/api/v1/resource/comments/reply",
                mapOf("threadId" to songThreadId(songId), "commentId" to commentId, "content" to content)
            )
        }) { api.decode<CommentActionResponse>(it) }

    suspend fun likeComment(songId: Long, commentId: Long, like: Boolean) =
        run({
            api.eapiPost(
                "/api/v1/comment/like",
                mapOf("threadId" to songThreadId(songId), "commentId" to commentId, "like" to like)
            )
        }) { api.decode<StatusResponse>(it) }

    // ---------- 签到 / 云贝 ----------
    suspend fun signin(type: Int) = // 0=PC 1=移动端
        run({ api.weapiPost("/weapi/point/dailyTask", mapOf("type" to type)) }) { api.decode<SigninResult>(it) }

    /**
     * Current cloud-shell sign-in.  This endpoint is a web `/api` endpoint, not
     * an eapi alias: routing it through eapiPost would rewrite it to
     * `/eapi/pointmall/user/sign` (or its `/weapi` fallback), which is a
     * different route.  Keep the exact production path and explicit cookies.
     */
    suspend fun modernYunbeiSignin() =
        run({ api.plainPost("/api/pointmall/user/sign", csrfForm()) }) { api.decode<YunbeiSigninResponse>(it) }

    /** Current cloud-shell account snapshot used to confirm a sign-in write. */
    suspend fun yunbeiUserInfo() =
        run({ api.plainPost("/api/v1/user/info", csrfForm()) }) { api.decode<YunbeiUserInfoResponse>(it) }

    /** Current cloud-shell task snapshot used to confirm a completed task. */
    suspend fun yunbeiTaskListAll() =
        run({ api.plainPost("/api/usertool/task/list/all", csrfForm()) }) { api.decode<YunbeiTaskListResponse>(it) }

    suspend fun yunbeiBalance() = run({ api.weapiPost("/weapi/point") }) { api.decode<YunbeiBalance>(it) }

    suspend fun yunbeiTasks() = run({ api.weapiPost("/weapi/yunbei/tasks") }) { api.decode<YunbeiTaskResponse>(it) }

    suspend fun finishYunbeiTask(userTaskId: Long) =
        run({ api.weapiPost("/weapi/yunbei/task/finish", mapOf("userTaskId" to userTaskId)) }) { api.decode<YunbeiTaskFinish>(it) }

    private fun csrfForm(): Map<String, String> =
        mapOf("csrf_token" to api.cookie("__csrf"))

    // ---------- 私信 ----------
    suspend fun privateMsgs(offset: Int = 0, limit: Int = 20) =
        run({ api.weapiPost("/weapi/msg/private/users", mapOf("offset" to offset, "limit" to limit, "total" to true)) }) { api.decode<MsgSessionResponse>(it) }

    suspend fun privateHistory(userId: Long, limit: Int = 30, time: Long = 0) =
        run({ api.weapiPost(privateHistoryPath, privateHistoryPayload(userId, limit, time)) }) { api.decode<MsgHistoryResponse>(it) }

    /** Text messages use the web client's WEAPI route and payload. */
    suspend fun sendPrivateMsg(userIds: List<Long>, msg: String) =
        run({
            api.weapiPost(privateMessageSendPath, privateMessageSendPayload(userIds, msg))
        }) { api.decode<StatusResponse>(it) }

    // ---------- 一起听（官方 REST 状态与邀请/离开动作） ----------
    private fun togetherPath(path: String): String =
        path + "?csrf_token=" + api.cookie("__csrf")

    suspend fun togetherStatus() =
        run({
            api.plainPost(
                togetherPath("/api/listen/together/status/get"),
                mapOf("csrf_token" to api.cookie("__csrf")),
            )
        }) { api.decode<TogetherStatusResponse>(it) }

    suspend fun sendTogetherInvite(roomId: String, acceptorId: Long) =
        run({
            api.plainPost(
                togetherPath("/api/listen/together/invite/message/send"),
                mapOf(
                    "roomId" to roomId,
                    "acceptorId" to acceptorId.toString(),
                    "csrf_token" to api.cookie("__csrf"),
                ),
            )
        }) { api.decode<TogetherActionResponse>(it) }

    suspend fun leaveTogether(roomId: String) =
        run({
            api.plainPost(
                togetherPath("/api/listen/together/end/v2"),
                mapOf(
                    "roomId" to roomId,
                    "shareInfo" to "{}",
                    "csrf_token" to api.cookie("__csrf"),
                ),
            )
        }) { api.decode<TogetherActionResponse>(it) }

    // ---------- 扫码登录 ----------
    /**
     * 申请二维码 key：返回体顶层字段 unikey。
     * 二维码内容需客户端自行生成：https://music.163.com/login?codekey=<unikey>
     */
    suspend fun qrKey() =
        run({ api.plainPost("/api/login/qrcode/unikey", mapOf("type" to "3")) }) { api.decode<QrKeyResponse>(it) }

    /** 轮询扫码状态：800 已过期 / 801 待扫码 / 802 已扫码待确认 / 803 成功（返回 cookie） */
    suspend fun qrCheck(key: String) =
        run({ api.plainPost("/api/login/qrcode/client/login", mapOf("key" to key, "type" to "3")) }) { api.decode<QrCheckResponse>(it) }
}

/**
 * `/weapi/v1/resource/comments` is offset-paginated.  Supplying a synthetic
 * cursor selects a different server paging branch and can repeat the first
 * page, so keep this request to the documented offset contract.
 */
internal fun commentPagePayload(threadId: String, offset: Int, limit: Int): Map<String, Any> =
    mapOf("rid" to threadId, "limit" to limit, "offset" to offset)

// ---------- 响应补充模型 ----------

@kotlinx.serialization.Serializable
data class SongUrlResponse(
    val code: Int = 0,
    val data: List<SongUrlItem> = emptyList(),
)

@kotlinx.serialization.Serializable
data class SongUrlItem(
    val id: Long = 0,
    val url: String = "",
    val br: Int = 0,
    val size: Long = 0,
    val md5: String = "",
    val type: String = "",
    val level: String = "",
    val code: Int = 0,
)

/** `/weapi/song/enhance/play/mv/url` response. */
@kotlinx.serialization.Serializable
data class MvUrlResponse(
    val code: Int = 0,
    val data: MvUrlData? = null,
)

@kotlinx.serialization.Serializable
data class MvUrlData(
    val id: Long = 0,
    val url: String = "",
    val r: Int = 0,
    val size: Long = 0,
)

@kotlinx.serialization.Serializable
data class FollowListResponse(
    val code: Int = 0,
    val follow: List<Profile> = emptyList(),
    val followeds: List<Profile> = emptyList(),
    val more: Boolean = false,
)
