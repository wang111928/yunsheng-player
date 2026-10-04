package com.litemusic.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNames

@Serializable
data class RadioStation(val id: Long = 0, val name: String = "", val picUrl: String = "", val desc: String = "", val category: String = "") {
    val coverUrl: String get() = normalizeCover(picUrl)
}

@Serializable
data class RadioStationResponse(val code: Int = 0, val djRadios: List<RadioStation> = emptyList(), val radios: List<RadioStation> = emptyList()) {
    val stations: List<RadioStation> get() = djRadios.ifEmpty { radios }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RadioProgram(
    val id: Long = 0,
    val name: String = "",
    val mainSong: Song? = null,
    /** List responses may omit [mainSong] but retain this playable song id. */
    val mainTrackId: Long = 0,
    val description: String = "",
    val desc: String = "",
    @Serializable(with = ProgramDescriptionSerializer::class)
    val programDesc: String = "",
    /** Program detail sometimes owns a separate artwork URL from its playable song. */
    @SerialName("coverUrl") @JsonNames("picUrl", "coverImgUrl") val coverUrl: String = "",
    /** Detail/list payloads may embed the station; retain it as artwork fallback. */
    val radio: RadioStation? = null,
    /** The station sheet supplies this when the program payload omits [radio]. */
    @Transient val stationCoverUrl: String = "",
) {
    /** Server supplied program text, retained for the player when timed lyrics are unavailable. */
    val text: String get() = description.ifBlank { desc }.ifBlank { programDesc }

    /** The list endpoint may omit song metadata, but never requires a fabricated track id. */
    val playableSongId: Long
        get() = mainSong?.id?.takeIf { it > 0L } ?: mainTrackId.takeIf { it > 0L } ?: 0L

    /** Prefer playable-song/program art and fall back to the station's art. */
    val artworkUrl: String
        get() = sequenceOf(mainSong?.coverUrl, normalizeCover(coverUrl), radio?.coverUrl, normalizeCover(stationCoverUrl))
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
}

/**
 * Radio responses ship `programDesc` as a string, null, object or an array of
 * rich-text blocks.  Normalise those valid shapes so one response cannot make
 * the entire station sheet undecodable.
 */
object ProgramDescriptionSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProgramDescription", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        require(decoder is JsonDecoder) { "Program description must be decoded from JSON" }
        return programDescriptionText(decoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: String) {
        require(encoder is JsonEncoder) { "Program description must be encoded as JSON" }
        encoder.encodeJsonElement(JsonPrimitive(value))
    }
}

internal fun programDescriptionText(element: JsonElement): String = when (element) {
    JsonNull -> ""
    is JsonPrimitive -> element.content
    is JsonArray -> element.map(::programDescriptionText).filter { it.isNotBlank() }.joinToString("\n")
    is JsonObject -> {
        val direct = listOf("content", "text", "description", "desc", "programDesc")
            .asSequence()
            .mapNotNull { element[it] }
            .map(::programDescriptionText)
            .firstOrNull { it.isNotBlank() }
        direct ?: element.values.asSequence()
            .map(::programDescriptionText)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }
}

/**
 * The radio endpoint occasionally returns rich-text blocks for `programDesc`.
 * Normalise that field at the response boundary as well as on the model so the
 * page remains readable even when a generated serializer is out of date in an
 * installed client.
 */
internal fun normalizeRadioProgramDescriptions(raw: String): String {
    val root = runCatching { Json.parseToJsonElement(raw) }.getOrElse { return raw }
    val response = root as? JsonObject ?: return raw
    val programs = response["programs"] as? JsonArray ?: return raw
    val normalizedPrograms = programs.map { value ->
        val program = value as? JsonObject ?: return@map value
        val description = program["programDesc"] ?: return@map value
        JsonObject(program + ("programDesc" to JsonPrimitive(programDescriptionText(description))))
    }
    return Json.encodeToString(JsonElement.serializer(), JsonObject(response + ("programs" to JsonArray(normalizedPrograms))))
}

/** Detail responses carry one program rather than the list endpoint's `programs` array. */
internal fun normalizeRadioProgramDescription(raw: String): String {
    val root = runCatching { Json.parseToJsonElement(raw) }.getOrElse { return raw }
    val response = root as? JsonObject ?: return raw
    val program = response["program"] as? JsonObject ?: return raw
    val description = program["programDesc"] ?: return raw
    val normalizedProgram = JsonObject(
        program + ("programDesc" to JsonPrimitive(programDescriptionText(description))),
    )
    return Json.encodeToString(
        JsonElement.serializer(),
        JsonObject(response + ("program" to normalizedProgram)),
    )
}

@Serializable
data class RadioProgramResponse(val code: Int = 0, val programs: List<RadioProgram> = emptyList())

/** Official `/api/dj/program/detail` response used to hydrate a list row before playback. */
@Serializable
data class RadioProgramDetailResponse(val code: Int = 0, val program: RadioProgram = RadioProgram())

/** Paginated public playlist catalog used by the Music home channel. */
@Serializable
data class PlaylistCatalogResponse(
    val code: Int = 0,
    val playlists: List<Playlist> = emptyList(),
    val total: Int = 0,
    val more: Boolean = false,
)
