package com.litemusic.shared.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNames

// ---------- 用户 / 账号 ----------

@Serializable
@Immutable
data class Profile(
    @SerialName("userId") val userId: Long = 0,
    @SerialName("nickname") val nickname: String = "",
    @SerialName("avatarUrl") val avatarUrl: String = "",
    @SerialName("signature") val signature: String = "",
    // 官方接口大数用科学计数法（如 6.3651476E7），Long 解析会失败，故用 Double
    @SerialName("followeds") val followeds: Double = 0.0,
    @SerialName("follows") val follows: Double = 0.0,
    @SerialName("eventCount") val eventCount: Int = 0,
    @SerialName("gender") val gender: Int = 0,
    @SerialName("birthday") val birthday: Long = 0,
    /** Decorative profile cover supplied by user/detail and follow-list responses. */
    @SerialName("backgroundUrl") val backgroundUrl: String = "",
    @SerialName("playlistCount") val playlistCount: Int = 0,
    @SerialName("description") val description: String = "",
    // 关注列表接口会带 followed（当前登录用户是否已关注对方）
    @SerialName("followed") val followed: Boolean = false,
)

@Serializable
@Immutable
data class Account(
    @SerialName("id") val id: Long = 0,
    @SerialName("userName") val userName: String = "",
    @SerialName("vipType") val vipType: Int = 0,
    @SerialName("vipLevel") val vipLevel: Int = 0,
    @SerialName("anonimousUser") val anonimousUser: Boolean = false,
)

@Serializable
@Immutable
data class AccountResponse(
    val code: Int = 0,
    val account: Account? = null,
    val profile: Profile? = null,
)

@Serializable
@Immutable
data class UserDetail(
    val code: Int = 0,
    val level: Int = 0,
    @SerialName("listenSongs") val listenSongs: Int = 0,
    val profile: Profile? = null,
)

@Serializable
@Immutable
data class SubCount(
    val code: Int = 0,
    @SerialName("createdPlaylistCount") val createdPlaylistCount: Int = 0,
    @SerialName("subPlaylistCount") val subPlaylistCount: Int = 0,
)

// ---------- 歌曲 / 专辑 / 歌手 ----------

@Serializable
@Immutable
data class Artist(
    val id: Long = 0,
    val name: String = "",
    @SerialName("picUrl") val picUrl: String = "",
    val alias: List<String> = emptyList(),
)

/** Payload returned by the public artist page.  These fields are deliberately
 * optional: search rows only contain the small [Artist] shape. */
@Serializable
@Immutable
data class ArtistDetailResponse(
    val code: Int = 0,
    val artist: Artist? = null,
    val hotSongs: List<Song> = emptyList(),
    val more: Boolean = false,
)

/** Real discovery artists used by the home page; no local placeholders. */
@Serializable
@Immutable
data class TopArtistsResponse(
    val code: Int = 0,
    val artists: List<Artist> = emptyList(),
    val more: Boolean = false,
)

@Serializable
@Immutable
@OptIn(ExperimentalSerializationApi::class)
data class Album(
    val id: Long = 0,
    val name: String = "",
    @SerialName("picUrl") @JsonNames("blurPicUrl", "coverUrl") val picUrl: String = "",
    val artist: Artist? = null,
)

@Serializable
@Immutable
@OptIn(ExperimentalSerializationApi::class)
data class Song(
    val id: Long = 0,
    val name: String = "",
    @JsonNames("artists") val ar: List<Artist> = emptyList(),
    @JsonNames("album") val al: Album? = null,
    /** Some radio/search payloads put artwork directly on the song. */
    @SerialName("picUrl") @JsonNames("coverUrl", "cover") val picUrl: String = "",
    val dt: Long = 0,
    val fee: Int = 0,
    @JsonNames("mvid") val mv: Long = 0,
    @JsonNames("alias") val alia: List<String> = emptyList(),
    val tns: List<String> = emptyList(),
    /** Official recording metadata: 1 original, 2 cover; 0 means unspecified. */
    val originCoverType: Int = 0,
    val privilege: Privilege? = null,
    val reason: String? = null,
) {
    val artistNames: String get() = ar.joinToString(" / ") { it.name }
    // 专辑 picUrl 常为 http://，部分 ROM 明文策略会拦掉 → 统一升级 https
    val coverUrl: String get() = normalizeCover(al?.picUrl.orEmpty().ifBlank { picUrl })
    /** 列表缩略图（240×240，网易 CDN 服务端裁剪，降低解码开销） */
    val coverThumbUrl: String get() = coverThumb(coverUrl, 240)
    val albumName: String get() = al?.name ?: ""
}

@Serializable
@Immutable
data class Privilege(
    val fee: Int = 0,
    @SerialName("pl") val pl: Int = 0,
    @SerialName("dl") val dl: Int = 0,
    @SerialName("st") val st: Int = 0,
)

@Serializable
@Immutable
data class SongDetailResponse(
    val code: Int = 0,
    val songs: List<Song> = emptyList(),
)

// ---------- 歌单 ----------

@Serializable
@Immutable
data class Playlist(
    val id: Long = 0,
    val name: String = "",
    @SerialName("coverImgUrl") val coverImgUrl: String = "",
    // /weapi/personalized/playlist、/weapi/toplist 等端点返回的是 picUrl 而不是 coverImgUrl，
    // 只解 coverImgUrl 会导致「推荐歌单没有封面图」。两者都解，取非空的那个。
    @SerialName("picUrl") val picUrl: String = "",
    @SerialName("trackCount") val trackCount: Int = 0,
    @SerialName("playCount") val playCountRaw: Double = 0.0,
    /** /weapi/v1/discovery/recommend/resource 收的是小写 playcount */
    @SerialName("playcount") val playCountLower: Double = 0.0,
    val description: String = "",
    val creator: Profile? = null,
    @SerialName("subscribed") val subscribed: Boolean = false,
    val tracks: List<Song> = emptyList(),
    val trackIds: List<PlaylistTrackId> = emptyList(),
    @SerialName("shareCount") val shareCount: Double = 0.0,
    @SerialName("commentCount") val commentCount: Double = 0.0,
    @SerialName("userId") val userId: Long = 0,
    /** 榜单接口返回的「更新频率」文案 */
    @SerialName("updateFrequency") val updateFrequency: String = "",
) {
    /** 播放量（兼容 playCount / playcount 两种字段） */
    val playCount: Double get() = if (playCountRaw > 0) playCountRaw else playCountLower
    /** 封面地址（兼容 coverImgUrl / picUrl 两种字段，并统一走 https） */
    val cover: String get() = normalizeCover(coverImgUrl.ifBlank { picUrl })
    /** 封面缩略图（列表卡片用，服务端裁剪到 240×240） */
    val coverThumb: String get() = coverThumb(cover, 240)
}

@Serializable
@Immutable
data class PlaylistTrackId(val id: Long = 0)

/** 网易 CDN 同时支持 http/https；http 在部分 ROM 上会被明文策略拦掉，统一升级为 https */
fun normalizeCover(url: String): String = when {
    url.isBlank() -> ""
    url.startsWith("//") -> "https:" + url
    url.startsWith("http://") -> "https://" + url.removePrefix("http://")
    else -> url
}

/** 网易图片 CDN 支持 /xxx.jpg?param=WxH 按需裁剪，列表页用小图能显著降低解码开销 */
fun coverThumb(url: String, size: Int = 240): String {
    val u = normalizeCover(url)
    if (u.isBlank()) return ""
    if (!u.contains("music.126.net")) return u
    return if (u.contains("?param=")) u else u + "?param=" + size + "y" + size
}

@Serializable
@Immutable
data class PlaylistDetailResponse(
    val code: Int = 0,
    val playlist: Playlist? = null,
)

@Serializable
@Immutable
data class UserPlaylistResponse(
    val code: Int = 0,
    val playlist: List<Playlist> = emptyList(),
    val more: Boolean = false,
)

@Serializable
@Immutable
data class PlaylistTrackIdsResponse(
    val code: Int = 0,
    @SerialName("trackIds") val trackIds: List<TrackIdRef> = emptyList(),
)

@Serializable
@Immutable
data class TrackIdRef(@SerialName("id") val id: Long)

// ---------- 推荐 / 搜索 ----------

@Serializable
@Immutable
data class DailyRecommend(
    val code: Int = 0,
    @SerialName("data") val data: DailyRecommendData? = null,
)

@Serializable
@Immutable
data class DailyRecommendData(@SerialName("dailySongs") val dailySongs: List<Song> = emptyList())

@Serializable
@Immutable
data class PersonalizedPlaylist(
    val code: Int = 0,
    val result: List<Playlist> = emptyList(),
)

/**
 * 推荐歌单（/weapi/v1/discovery/recommend/resource）。
 *
 * 旧端点 /weapi/personalized/playlist 已不返回任何封面字段（实测只回 id/name/trackCount/playCount），
 * 首页会「只有歌单名没有封面」。该端点带 picUrl（封面）+ playcount（播放量）。
 */
@Serializable
@Immutable
data class RecommendResource(
    val code: Int = 0,
    val recommend: List<Playlist> = emptyList(),
)

/** 排行榜列表（/weapi/toplist/detail + /weapi/toplist） */
@Serializable
@Immutable
data class ToplistResponse(
    val code: Int = 0,
    val list: List<Playlist> = emptyList(),
)

/** 私人 FM（/weapi/v1/radio/get） */
@Serializable
@Immutable
data class RadioFmResponse(
    val code: Int = 0,
    val data: List<Song> = emptyList(),
)

/** 心动模式每一行将歌曲放在 songInfo 内，行顶层 id 仅用于推荐元数据。 */
@Serializable
@Immutable
data class IntelligencePlayEntry(
    val songInfo: Song? = null,
)

/** 心动模式（/weapi/playmode/intelligence/list）返回的智能续播歌曲。 */
@Serializable
@Immutable
data class IntelligencePlayResponse(
    val code: Int = 0,
    val data: List<IntelligencePlayEntry> = emptyList(),
)

@Serializable
@Immutable
data class SearchResult(
    val code: Int = 0,
    val result: SearchPayload? = null,
)

@Serializable
@Immutable
data class SearchPayload(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val users: List<Profile> = emptyList(),
    @SerialName("songCount") val songCount: Int = 0,
    @SerialName("playlistCount") val playlistCount: Int = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class AlbumSongsResponse(
    val code: Int = 0,
    val songs: List<Song> = emptyList(),
)

@Serializable
@Immutable
data class HotSearchResponse(
    val code: Int = 0,
    val result: HotSearchResult? = null,
)

@Serializable
@Immutable
data class HotSearchResult(
    val hots: List<HotWord> = emptyList(),
)

@Serializable
@Immutable
data class HotWord(
    val first: String = "",
    val second: Int = 0,
    val iconUrl: String = "",
)

// ---------- 红心 / 收藏 ----------

@Serializable
@Immutable
data class LikeResponse(
    val code: Int = 0,
    @SerialName("playlistId") val playlistId: Long = 0,
)

@Serializable
@Immutable
data class LikeListResponse(
    val code: Int = 0,
    val ids: List<Long> = emptyList(),
)

// ---------- 歌单操作 ----------

@Serializable
@Immutable
data class PlaylistManipulateResponse(
    val code: Int = 0,
    val message: String = "",
)

// ---------- 歌词 ----------

@Serializable
@Immutable
data class LyricContent(
    val version: Int = 0,
    val lyric: String = "",
)

@Serializable
@Immutable
data class LyricResponse(
    val code: Int = 0,
    val lrc: LyricContent? = null,
    val tlyric: LyricContent? = null,
    val yrc: LyricContent? = null,
    val ytlrc: LyricContent? = null,
)

// ---------- 评论 ----------

@Serializable
@Immutable
data class NeteaseUser(
    @SerialName("userId") val userId: Long = 0,
    @SerialName("nickname") val nickname: String = "",
    @SerialName("avatarUrl") val avatarUrl: String = "",
    @SerialName("vipType") val vipType: Int = 0,
    val expert: Boolean = false,
)

@Serializable
@Immutable
data class BeReplied(
    val user: NeteaseUser? = null,
    val content: String = "",
)

@Serializable
@Immutable
data class Comment(
    @SerialName("commentId") val commentId: Long = 0,
    val user: NeteaseUser? = null,
    val content: String = "",
    val time: Long = 0,
    @SerialName("likedCount") val likedCount: Int = 0,
    @SerialName("liked") val liked: Boolean = false,
    @SerialName("beReplied") val beReplied: List<BeReplied> = emptyList(),
    @SerialName("ipLocation") val ipLocation: IpLocation? = null,
    val showFloorComment: Boolean = false,
    val status: Int = 0,
)

@Serializable
@Immutable
data class IpLocation(@SerialName("location") val location: String = "")

@Serializable
@Immutable
@OptIn(ExperimentalSerializationApi::class)
data class CommentResponse(
    val code: Int = 0,
    val comments: List<Comment> = emptyList(),
    val hotComments: List<Comment> = emptyList(),
    val total: Long = 0,
    @JsonNames("more") val hasMore: Boolean = false,
)

@Serializable
@Immutable
data class CommentActionResponse(
    val code: Int = 0,
    val comment: Comment? = null,
)

// ---------- 私信 ----------

@Serializable
@Immutable
@OptIn(ExperimentalSerializationApi::class)
data class MsgSession(
    @SerialName("id") val id: Long = 0,
    @SerialName("fromUser") val fromUser: NeteaseUser? = null,
    @SerialName("toUser") val toUser: NeteaseUser? = null,
    @SerialName("lastMsg") val lastMsg: String = "",
    @SerialName("lastMsgTime") val lastMsgTime: Long = 0,
    @JsonNames("newMsgCount") val unread: Int = 0,
)

@Serializable
@Immutable
@OptIn(ExperimentalSerializationApi::class)
data class MsgSessionResponse(
    val code: Int = 0,
    val message: String = "",
    val msgs: List<MsgSession> = emptyList(),
    @JsonNames("more") val hasMore: Boolean = false,
)

@Serializable
@Immutable
@OptIn(ExperimentalSerializationApi::class)
data class MsgItem(
    @JsonNames("id") val msgId: Long = 0,
    val fromUser: NeteaseUser? = null,
    val toUser: NeteaseUser? = null,
    val msg: String = "",
    val time: Long = 0,
    val type: Int = 0,
)

@Serializable
@Immutable
@OptIn(ExperimentalSerializationApi::class)
data class MsgHistoryResponse(
    val code: Int = 0,
    val message: String = "",
    val msgs: List<MsgItem> = emptyList(),
    @JsonNames("more") val hasMore: Boolean = false,
)

// ---------- 签到 / 云贝 ----------

@Serializable
@Immutable
data class SigninResult(
    val code: Int = 0,
    val message: String = "",
    val point: Int = 0,
)

@Serializable
@Immutable
data class YunbeiBalance(
    val code: Int = 0,
    @SerialName("yunbei") val yunbei: Int = 0,
    val message: String = "",
)

@Serializable
@Immutable
data class YunbeiTask(
    @SerialName("userTaskId") val userTaskId: Long = 0,
    /** The task catalog ID returned by /api/usertool/task/list/all. */
    @SerialName("taskId") val taskId: Long = 0,
    val name: String = "",
    @SerialName("taskName") val taskName: String = "",
    val status: Int = 0,
    val done: Boolean = false,
    val yunbei: Int = 0,
    /** Explicit modern completion flag.  Null means this response did not include it. */
    val completed: Boolean? = null,
    /** Modern task reward and its completed portion, respectively. */
    @SerialName("taskPoint") val taskPoint: Int? = null,
    @SerialName("completedPoint") val completedPoint: Int? = null,
)

@Serializable
@Immutable
data class YunbeiTaskResponse(
    val code: Int = 0,
    val tasks: List<YunbeiTask> = emptyList(),
)

@Serializable
@Immutable
data class YunbeiTaskFinish(
    val code: Int = 0,
    val message: String = "",
)

// ---------- 登录 ----------

@Serializable
@Immutable
data class QrKeyResponse(
    val code: Int = 0,
    @SerialName("unikey") val unikey: String = "",
    val data: QrKeyData? = null,
)

/** Response from the current point-mall cloud sign-in endpoint. */
@Serializable
@Immutable
data class YunbeiSigninResponse(
    val code: Int = 0,
    val data: YunbeiSigninData? = null,
    val message: String = "",
)

@Serializable
@Immutable
data class YunbeiSigninData(
    /** null means this server revision did not return a confirmation flag. */
    val sign: Boolean? = null,
    @SerialName("yunbeiNum") val yunbeiNum: Int? = null,
)

/** Read-back response for /api/v1/user/info.  Field names vary by server revision. */
@Serializable
@Immutable
data class YunbeiUserInfoResponse(
    val code: Int = 0,
    /** Current endpoint returns these fields at the response root. */
    @SerialName("mobileSign") val mobileSign: Boolean? = null,
    @SerialName("pcSign") val pcSign: Boolean? = null,
    @SerialName("userPoint") val userPoint: YunbeiUserPoint? = null,
    /** Kept for older server revisions which wrap the fields in data. */
    val data: YunbeiUserInfo? = null,
    val message: String = "",
)

@Serializable
@Immutable
data class YunbeiUserPoint(
    val balance: Int? = null,
)

@Serializable
@Immutable
data class YunbeiUserInfo(
    val sign: Boolean? = null,
    @SerialName("yunbeiNum") val yunbeiNum: Int? = null,
    val yunbei: Int? = null,
)

/** Read-back response for /api/usertool/task/list/all. */
@Serializable
@Immutable
data class YunbeiTaskListResponse(
    val code: Int = 0,
    val data: YunbeiTaskListData? = null,
    val message: String = "",
)

@Serializable(with = YunbeiTaskListDataSerializer::class)
@Immutable
data class YunbeiTaskListData(
    @SerialName("userTaskList") val userTaskList: List<YunbeiTask> = emptyList(),
    val tasks: List<YunbeiTask> = emptyList(),
    /** Current endpoint returns the task list directly as data: [...]. */
    val directTasks: List<YunbeiTask> = emptyList(),
) {
    val allTasks: List<YunbeiTask>
        get() = directTasks.ifEmpty { userTaskList.ifEmpty { tasks } }
}

/**
 * `/api/usertool/task/list/all` has shipped both `data: [...]` and legacy
 * object envelopes.  Keep a single typed model so callers do not silently see
 * an empty list when the endpoint switches between those valid shapes.
 */
object YunbeiTaskListDataSerializer : KSerializer<YunbeiTaskListData> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("YunbeiTaskListData")

    override fun deserialize(decoder: Decoder): YunbeiTaskListData {
        require(decoder is JsonDecoder) { "Yunbei task data must be decoded from JSON" }
        val element = decoder.decodeJsonElement()
        return when (element) {
            is JsonArray -> YunbeiTaskListData(
                directTasks = decoder.json.decodeFromJsonElement(ListSerializer(YunbeiTask.serializer()), element),
            )
            is JsonObject -> decoder.json.decodeFromJsonElement(YunbeiTaskListObject.serializer(), element).let {
                YunbeiTaskListData(it.userTaskList, it.tasks)
            }
            else -> YunbeiTaskListData()
        }
    }

    override fun serialize(encoder: Encoder, value: YunbeiTaskListData) {
        require(encoder is JsonEncoder) { "Yunbei task data must be encoded as JSON" }
        val payload = if (value.directTasks.isNotEmpty()) {
            encoder.json.encodeToJsonElement(ListSerializer(YunbeiTask.serializer()), value.directTasks)
        } else {
            encoder.json.encodeToJsonElement(
                YunbeiTaskListObject.serializer(),
                YunbeiTaskListObject(value.userTaskList, value.tasks),
            )
        }
        encoder.encodeJsonElement(payload)
    }
}

@Serializable
private data class YunbeiTaskListObject(
    @SerialName("userTaskList") val userTaskList: List<YunbeiTask> = emptyList(),
    val tasks: List<YunbeiTask> = emptyList(),
)

@Serializable
@Immutable
data class QrKeyData(@SerialName("unikey") val unikey: String = "")

@Serializable
@Immutable
data class QrCreateResponse(
    val code: Int = 0,
    val data: QrCreateData? = null,
)

@Serializable
@Immutable
data class QrCreateData(
    @SerialName("qrimg") val qrimg: String = "",
    val url: String = "",
)

@Serializable
@Immutable
data class QrCheckResponse(
    val code: Int = 0,
    val message: String = "",
    val cookie: String = "",
)

// ---------- 通用 ----------

@Serializable
@Immutable
data class ApiError(
    val code: Int = 0,
    val message: String = "",
)

@Serializable
@Immutable
data class StatusResponse(
    val code: Int = 0,
    val message: String = "",
)
