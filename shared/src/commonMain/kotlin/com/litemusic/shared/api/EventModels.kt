package com.litemusic.shared.api

import com.litemusic.shared.model.Profile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
data class EventResponse(
    val code: Int = 0,
    @SerialName("event") val events: List<EventItem> = emptyList(),
    @SerialName("events") val alternateEvents: List<EventItem> = emptyList(),
    val lasttime: Long = 0,
    @SerialName("lastTime") val alternateLastTime: Long = 0,
    /** The RN square feed uses an opaque cursor rather than an event timestamp. */
    val cursor: JsonElement? = null,
    val more: Boolean = false,
) {
    val allEvents: List<EventItem> get() = if (events.isNotEmpty()) events else alternateEvents
    val nextLastTime: Long get() = if (lasttime > 0L) lasttime else alternateLastTime
    val nextSquareCursor: String get() = (cursor as? JsonPrimitive)?.content.orEmpty()
}

@Serializable
data class EventItem(
    val id: Long = 0,
    val user: Profile? = null,
    val json: String = "",
    @SerialName("eventTime") val eventTime: Long = 0,
    val pics: List<EventPicture> = emptyList(),
    val liked: Boolean = false,
    @SerialName("likedCount") val likedCount: Int = 0,
    @SerialName("commentCount") val commentCount: Int = 0,
    val info: EventInfo? = null,
)

/**
 * Dynamic engagement is returned below `info` by the event feeds.  Older
 * responses still expose the fields at the event root, so the mapper keeps a
 * fallback for those payloads.
 */
@Serializable
data class EventInfo(
    /** These fields are absent on part of the event feed; null must fall back to the root event. */
    val liked: Boolean? = null,
    @SerialName("likedCount") val likedCount: Int? = null,
    @SerialName("commentCount") val commentCount: Int? = null,
    @SerialName("threadId") val threadId: String = "",
    @SerialName("commentThread") val commentThread: EventCommentThread? = null,
)

@Serializable
data class EventCommentThread(
    val id: String = "",
)

@Serializable
data class EventPicture(
    @SerialName("originUrl") val originUrl: String = "",
    @SerialName("squareUrl") val squareUrl: String = "",
    @SerialName("smallUrl") val smallUrl: String = "",
)

data class EventPost(
    val id: Long,
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    val content: String,
    val tags: List<String>,
    val imageUrls: List<String>,
    val songId: Long? = null,
    val songTitle: String? = null,
    val songArtist: String? = null,
    val songCoverUrl: String? = null,
    val createdAt: Long,
    val followed: Boolean = false,
    val liked: Boolean,
    val likeCount: Int,
    val commentCount: Int,
    /** The event comment target, never the attached song's comment target. */
    val commentThreadId: String = "",
)

internal fun eventCommentThreadId(eventId: Long, userId: Long, serverThreadId: String): String =
    serverThreadId.ifBlank {
        if (eventId > 0L && userId > 0L) "A_EV_2_${userId}_${eventId}" else ""
    }

object EventMapper {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun map(event: EventItem): EventPost? = runCatching {
        val payload = json.parseToJsonElement(event.json).jsonObject
        val song = payload["song"]?.jsonObject
        val content = payload.string("msg").orEmpty().trim()
        val tags = payload["tags"]?.asStrings().orEmpty().ifEmpty {
            Regex("#([^#]+)#").findAll(content).map { it.groupValues[1] }.toList()
        }
        val imageUrls = event.pics.mapNotNull { picture ->
            picture.originUrl.ifBlank {
                picture.squareUrl.ifBlank { picture.smallUrl }
            }.ifBlank { null }
        }
        val songId = song?.long("id")
        val userId = event.user?.userId ?: 0L
        val info = event.info
        val serverThreadId = info?.threadId.orEmpty().ifBlank { info?.commentThread?.id.orEmpty() }
        if (content.isBlank() && songId == null && imageUrls.isEmpty()) {
            null
        } else {
            EventPost(
                id = event.id,
                userId = userId,
                nickname = event.user?.nickname.orEmpty().ifBlank { "云村用户" },
                avatarUrl = event.user?.avatarUrl.orEmpty(),
                content = content,
                tags = tags,
                imageUrls = imageUrls,
                songId = songId,
                songTitle = song?.string("name"),
                songArtist = song?.get("ar")?.jsonArray?.firstOrNull()?.jsonObject?.string("name")
                    ?: song?.get("artists")?.jsonArray?.firstOrNull()?.jsonObject?.string("name"),
                songCoverUrl = song?.get("al")?.jsonObject?.string("picUrl")
                    ?: song?.get("album")?.jsonObject?.string("picUrl")
                    ?: song?.string("picUrl"),
                createdAt = event.eventTime,
                followed = event.user?.followed == true,
                liked = info?.liked ?: event.liked,
                likeCount = info?.likedCount ?: event.likedCount,
                commentCount = info?.commentCount ?: event.commentCount,
                commentThreadId = eventCommentThreadId(event.id, userId, serverThreadId),
            )
        }
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private fun kotlinx.serialization.json.JsonElement.asStrings(): List<String> =
        (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null } }
            ?: emptyList()
}
