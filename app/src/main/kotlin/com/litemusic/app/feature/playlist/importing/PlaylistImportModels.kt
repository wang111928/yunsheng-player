package com.litemusic.app.feature.playlist.importing

import com.litemusic.shared.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A user-supplied song reference. It remains only a query until the preview matches it. */
data class ImportedSongMetadataAlternative(
    val artist: String = "",
    val album: String = "",
)

data class ImportedSongQuery(
    val title: String,
    val artist: String = "",
    /** Optional source metadata. It is evidence for matching, never written by previewing. */
    val album: String = "",
    /** OCR refinements are matching evidence; the primary text remains what the user can edit. */
    val titleAlternatives: List<String> = emptyList(),
    val artistAlternatives: List<String> = emptyList(),
    val albumAlternatives: List<String> = emptyList(),
    /** Artist and album from one OCR reading; do not cross-combine separate readings. */
    val metadataAlternatives: List<ImportedSongMetadataAlternative> = emptyList(),
    val fromScreenshot: Boolean = false,
) {
    val displayName: String get() = if (artist.isBlank()) title else "$title · $artist"
}

data class ImportedSongMatch(
    val query: ImportedSongQuery,
    val song: Song? = null,
    val confidence: Int = 0,
    /** A recoverable explanation for rows that need the user's attention. */
    val reason: String = "",
    /** Network failure is retryable work, not an OCR row the user must correct. */
    val searchIncomplete: Boolean = false,
) {
    /** A title-only search can select the wrong version; require artist evidence for auto-select. */
    val selectedByDefault: Boolean get() = song != null && query.artist.isNotBlank() && confidence == 100
}

/** Only a real, publicly readable source may produce this value. */
data class ExternalPlaylistSource(
    val sourceLabel: String,
    val title: String = "",
    val songs: List<ImportedSongQuery>,
    val totalCount: Int = songs.size,
)

/**
 * Platform adapters live outside the UI so unsupported links never become fake
 * song names. They must return a failure when the public playlist is unreadable.
 */
interface ExternalPlaylistReader {
    suspend fun read(url: String): com.litemusic.shared.util.AppResult<ExternalPlaylistSource>
}

/** Official public playlist URLs and share text have a stable numeric `id` parameter. */
internal fun extractedImportUrl(raw: String): String? = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    .find(raw)
    ?.value
    ?.trimEnd('。', '，', '！', '？', '）', ')', '】', ']', '》', '”', '"', '\'', '；', ';')
    ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

internal fun officialPlaylistId(input: String): Long? {
    val value = extractedImportUrl(input) ?: input.trim()
    value.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
    val host = Regex("^https?://(?:[\\w-]+\\.)?music\\.163\\.com(?:/|$)", RegexOption.IGNORE_CASE)
    if (!host.containsMatchIn(value)) return null
    val pathIsPlaylist = Regex("(?:/|/#/)(?:m/)?playlist(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(value)
    if (!pathIsPlaylist) return null
    return Regex("(?:[?&])id=(\\d{1,19})(?:[&#]|$)")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
}

internal fun parseImportedText(raw: String, limit: Int = MAX_IMPORT_SONGS): List<ImportedSongQuery> = when {
    raw.contains("#EXTM3U", ignoreCase = true) || raw.contains("#EXTINF:", ignoreCase = true) -> parseM3u(raw, limit)
    raw.trimStart().startsWith("[") || raw.trimStart().startsWith("{") -> parseJson(raw, limit)
    raw.lineSequence().firstOrNull()?.contains(",") == true -> parseCsv(raw, limit)
    else -> parseImportedSongText(raw, limit)
}

/** Keep the preview bounded; callers can present the overflow rather than silently importing it. */
internal fun importOverflowCount(raw: String): Int = (parseImportedText(raw, Int.MAX_VALUE).size - MAX_IMPORT_SONGS).coerceAtLeast(0)

private fun parseJson(raw: String, limit: Int): List<ImportedSongQuery> = runCatching {
    val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw)
    val rows = when {
        root is kotlinx.serialization.json.JsonArray -> root
        root.jsonObject["songs"] != null -> root.jsonObject.getValue("songs").jsonArray
        root.jsonObject["tracks"] != null -> root.jsonObject.getValue("tracks").jsonArray
        else -> emptyList()
    }
    rows.mapNotNull { item ->
        val obj = item.jsonObject
        val title = listOf("title", "name", "songName").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }
        val artist = jsonArtist(obj)
        title?.let {
            ImportedSongQuery(
                title = it,
                artist = artist,
                album = jsonAlbum(obj),
                titleAlternatives = jsonStringList(obj, "titleAlternatives"),
                artistAlternatives = jsonStringList(obj, "artistAlternatives"),
                albumAlternatives = jsonStringList(obj, "albumAlternatives"),
                metadataAlternatives = jsonMetadataAlternatives(obj),
                fromScreenshot = obj["fromScreenshot"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }.distinctBy { Triple(it.title.lowercase(), it.artist.lowercase(), it.album.lowercase()) }.take(limit)
}.getOrElse { emptyList() }

private fun jsonStringList(obj: JsonObject, key: String): List<String> = (obj[key] as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
    ?.distinct()
    .orEmpty()

private fun jsonMetadataAlternatives(obj: JsonObject): List<ImportedSongMetadataAlternative> =
    (obj["metadataAlternatives"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { item ->
            val metadata = item as? JsonObject ?: return@mapNotNull null
            ImportedSongMetadataAlternative(
                artist = (metadata["artist"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                album = (metadata["album"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            ).takeIf { it.artist.isNotBlank() && it.album.isNotBlank() }
        }
        ?.distinct()
        .orEmpty()

private fun jsonArtist(obj: kotlinx.serialization.json.JsonObject): String {
    listOf("artist", "artists", "singer").firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }?.let { return it }
    return listOf("ar", "artists").firstNotNullOfOrNull { key ->
        obj[key]?.jsonArray?.mapNotNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }?.joinToString(" / ")?.takeIf(String::isNotBlank)
    }.orEmpty()
}

/** Accept the common flat exports as well as NetEase-style `{ "al": { "name": ... } }`. */
private fun jsonAlbum(obj: kotlinx.serialization.json.JsonObject): String {
    listOf("album", "albumName", "album_name").firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }?.let { return it }
    return listOf("al", "album").firstNotNullOfOrNull { key ->
        (obj[key] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }.orEmpty()
}

internal fun parseImportedSongText(raw: String, limit: Int = MAX_IMPORT_SONGS): List<ImportedSongQuery> = raw
    .lineSequence()
    .mapNotNull(::parseSongLine)
    .distinctBy { it.title.lowercase() to it.artist.lowercase() }
    .take(limit)
    .toList()

private fun parseCsv(raw: String, limit: Int): List<ImportedSongQuery> {
    val rows = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    if (rows.isEmpty()) return emptyList()
    val first = splitCsvRow(rows.first())
    val header = first.map { it.lowercase() }
    val titleIndex = header.indexOfFirst { it in setOf("title", "name", "歌曲", "歌名") }
    val artistIndex = header.indexOfFirst { it in setOf("artist", "artists", "歌手") }
    val data = if (titleIndex >= 0) rows.drop(1) else rows
    return data.mapNotNull { row ->
        val cells = splitCsvRow(row)
        val title = cells.getOrNull(if (titleIndex >= 0) titleIndex else 0).orEmpty().trim()
        val artist = cells.getOrNull(if (artistIndex >= 0) artistIndex else 1).orEmpty().trim()
        title.takeIf { it.isNotBlank() }?.let { ImportedSongQuery(it, artist) }
    }.distinctBy { it.title.lowercase() to it.artist.lowercase() }.take(limit)
}

private fun splitCsvRow(row: String): List<String> {
    val values = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    row.forEach { char ->
        when (char) {
            '"' -> quoted = !quoted
            ',' -> if (quoted) cell.append(char) else {
                values += cell.toString().trim().trim('"')
                cell.clear()
            }
            else -> cell.append(char)
        }
    }
    values += cell.toString().trim().trim('"')
    return values
}

private fun parseM3u(raw: String, limit: Int): List<ImportedSongQuery> = raw
    .lineSequence()
    .filter { it.startsWith("#EXTINF:", ignoreCase = true) }
    .map { it.substringAfter(',', "").trim() }
    .mapNotNull { summary ->
        // M3U's EXTINF convention is usually "artist - title", unlike the
        // hand-entered list convention used by [parseSongLine].
        val separator = listOf(" - ", " – ", " — ").firstOrNull { summary.contains(it) }
        if (separator == null) parseSongLine(summary) else {
            val (artist, title) = summary.split(separator, limit = 2)
            title.trim().takeIf { it.isNotBlank() }?.let { ImportedSongQuery(it, artist.trim()) }
        }
    }
    .distinctBy { it.title.lowercase() to it.artist.lowercase() }
    .take(limit)
    .toList()

private fun parseSongLine(rawLine: String): ImportedSongQuery? {
    val line = rawLine.trim()
        .replace(Regex("^\\s*\\d+[.、)]\\s*"), "")
        .trim()
    if (line.isBlank() || line.startsWith("#")) return null
    val separators = listOf(" - ", " – ", " — ", " / ")
    val split = separators.firstNotNullOfOrNull { separator ->
        line.indexOf(separator).takeIf { it > 0 }?.let { index ->
            line.substring(0, index).trim() to line.substring(index + separator.length).trim()
        }
    }
    return when {
        split == null -> ImportedSongQuery(line)
        split.first.isBlank() -> null
        else -> ImportedSongQuery(split.first, split.second)
    }
}

internal const val MAX_IMPORT_SONGS = 500

internal fun importBatches(songIds: List<Long>, batchSize: Int = 100): List<List<Long>> =
    songIds.filter { it > 0L }.distinct().chunked(batchSize)

internal fun defaultSelectedSongIds(matches: List<ImportedSongMatch>): Set<Long> =
    matches.filter { it.selectedByDefault }.mapNotNull { it.song?.id }.toSet()

/** Do not re-submit already present tracks, and keep every mutation within the server batch limit. */
internal fun importBatchPlan(
    matches: List<ImportedSongMatch>,
    selectedSongIds: Set<Long>,
    alreadyInTarget: Set<Long>,
): List<List<Long>> = importBatches(
    matches.mapNotNull { match -> match.song?.id?.takeIf { it in selectedSongIds && it !in alreadyInTarget } },
)
