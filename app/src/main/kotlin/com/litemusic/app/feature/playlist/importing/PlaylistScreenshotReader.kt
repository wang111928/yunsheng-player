package com.litemusic.app.feature.playlist.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.litemusic.shared.util.AppResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

private const val MAX_SCREENSHOT_IMPORTS = 8
private const val MAX_OCR_PIXELS = 4_000_000L
private const val OCR_TILE_OVERLAP_PX = 200
private const val OCR_ROW_TOLERANCE_PX = 14

/** A text line plus its image-space origin, kept Android-free for deterministic ordering tests. */
internal data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Orders ML Kit's geometric lines into editable playlist text. Text blocks are deliberately not
 * joined as-is because screenshot layouts frequently return the artist column before the title.
 */
internal fun orderedOcrText(lines: List<OcrLine>): String {
    qualityBadgePlaylistText(lines)?.let { return it }
    val sorted = lines.asSequence()
        .map { it.copy(text = it.text.trim()) }
        .filter { it.text.isNotEmpty() && !isOcrInterfaceText(it.text) }
        .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        .toList()
    if (sorted.isEmpty()) return ""

    val heights = sorted.map { it.height }.filter { it > 0 }.sorted()
    val lineHeight = heights.getOrNull(heights.size / 2) ?: (OCR_ROW_TOLERANCE_PX * 2)
    val horizontalTolerance = if (heights.isEmpty()) OCR_ROW_TOLERANCE_PX else (lineHeight * 0.4).toInt().coerceAtLeast(1)
    val columnTolerance = lineHeight.coerceAtLeast(1)
    fun center(line: OcrLine) = line.top + line.height / 2

    val rows = mutableListOf<MutableList<OcrLine>>()
    sorted.forEach { line ->
        val row = rows.lastOrNull()
        if (row != null && abs(center(line) - center(row.first())) <= horizontalTolerance) {
            row += line
        } else {
            rows += mutableListOf(line)
        }
    }
    // Consecutive numbers in the same separate column are evidence of ranking. Absolute pixel
    // thresholds are unreliable across screenshot sizes, and a lone "25" can be a song title.
    val rankCandidates = rows.mapIndexedNotNull { index, row ->
        val ordered = row.sortedBy { it.left }
        val next = rows.getOrNull(index + 1)
        val stackedSubtitle = ordered.size == 2 && next?.size == 1 &&
            abs(next.first().left - ordered[1].left) <= columnTolerance &&
            next.first().top - ordered[1].top in 1..(lineHeight * 2)
        val separateRank = ordered.size == 1 && rows.any { other ->
            other.any { cell -> cell.left - ordered.first().left > lineHeight * 2 &&
                abs(cell.top - ordered.first().top) <= lineHeight * 2 && cell.text.toIntOrNull() == null }
        }
        ordered.firstOrNull()?.text?.toIntOrNull()?.takeIf { ordered.size >= 3 || stackedSubtitle || separateRank }?.let {
            Triple(index, it, ordered.first().left)
        }
    }
    val rankedRows = rankCandidates.zipWithNext().flatMap { (a, b) ->
        if (b.second == a.second + 1 && abs(a.third - b.third) <= horizontalTolerance) listOf(a.first, b.first) else emptyList()
    }.toSet()
    val contentRows = rows.mapIndexed { index, row ->
        val cells = row.sortedWith(compareBy<OcrLine> { it.left }.thenBy { it.top })
        val ranked = cells.first().text.matches(Regex("\\d{1,3}[.、)]")) ||
            index in rankedRows
        if (ranked) cells.drop(1) else cells
    }.filter { it.isNotEmpty() }

    // Repeated compact vertical blocks distinguish title/subtitle rows from separate songs.
    // A uniform title-only list must remain one candidate per line.
    val groups = mutableListOf<MutableList<List<OcrLine>>>()
    contentRows.forEach { row ->
        val group = groups.lastOrNull()
        val previous = group?.lastOrNull()
        val close = previous?.size == 1 && row.size == 1 &&
            abs(previous.first().left - row.first().left) <= columnTolerance &&
            row.first().top - previous.first().top in 1..(lineHeight * 2)
        if (close) group?.add(row) else groups += mutableListOf(row)
    }
    val compact = groups.filter { it.size in 2..4 }
    fun startsSubtitle(line: OcrLine, titleHeight: Int): Boolean =
        if (titleHeight > 0 && line.height > 0) line.height < titleHeight * 0.9
        else OCR_EXPLICIT_METADATA_SEPARATOR.containsMatchIn(line.text)
    fun hasSubtitleEvidence(group: List<List<OcrLine>>): Boolean = group.drop(1).any { row ->
        startsSubtitle(row.single(), group.first().single().height)
    }
    val confirmed = compact.filter(::hasSubtitleEvidence)
    fun metadataKey(text: String): Pair<String, String>? =
        text.takeIf { OCR_METADATA_SEPARATOR.containsMatchIn(it) }
            ?.let { OCR_METADATA_SEPARATOR.split(it, limit = 2).map(String::trim) }
            ?.takeIf { it.size == 2 && it.all(String::isNotEmpty) }
            ?.let { it[0] to it[1] }
    val confirmedMetadata = confirmed.mapNotNull { group ->
        val subtitle = group.drop(1).firstOrNull { row -> startsSubtitle(row.single(), group.first().single().height) }
        subtitle?.let { metadataKey(it.single().text) }
            ?.let { key -> group.first().single().left to key }
    }
    // Bounding boxes measure glyphs, not font sizes. A same-height subtitle needs two
    // independently confirmed occurrences of the same artist AND album in this column. Repeated
    // compact blocks or a dash in another song title are not sufficient on their own.
    val paired = compact.filter { group ->
        group in confirmed || (group.size == 2 && metadataKey(group.last().single().text)?.let { key ->
            confirmedMetadata.count { (left, knownKey) ->
                knownKey == key && abs(left - group.first().single().left) <= columnTolerance
            } >= 2
        } == true)
    }
    val dominant = paired.maxByOrNull { group -> paired.count { other ->
        abs(other.first().single().left - group.first().single().left) <= columnTolerance
    } }
    val listLeft = dominant?.first()?.single()?.left?.takeIf { left ->
        paired.count { abs(it.first().single().left - left) <= columnTolerance } >= 2
    }
    return groups.flatMap { group ->
        val aligned = listLeft == null || abs(group.first().first().left - listLeft) <= columnTolerance
        when {
            !aligned -> emptyList()
            group in paired -> {
                // Title wrapping keeps the title's font size. Artist/album wrapping belongs
                // to the subtitle from its first smaller or explicitly separated line.
                val titleHeight = group.first().single().height
                val subtitleStart = (1 until group.size).firstOrNull { index ->
                    startsSubtitle(group[index].single(), titleHeight)
                } ?: group.lastIndex
                val title = group.take(subtitleStart).joinToString(" ") { it.single().text }
                val subtitle = group.drop(subtitleStart).joinToString(" ") { it.single().text }
                val artist = OCR_METADATA_SEPARATOR.split(subtitle, limit = 2).first().trim()
                listOf("$title - $artist")
            }
            else -> group.map { row -> row.joinToString(" - ") { it.text } }
        }
    }.joinToString("\n")
}

// A repeated quality badge is strong evidence of an actual title/subtitle song list. Glyph
// bounding-box heights are unreliable for user fonts, so this path uses the repeated row spacing.
private val OCR_QUALITY_BADGE = Regex("(?:超[清漬濟消请].{0,1}[带市]|沉浸声|臻品母带)")
private val OCR_LIST_FOOTER = Regex("(?:你可能.*喜欢|推荐歌单|\\d+\\s*人已收藏)")

internal fun qualityBadgePlaylistText(lines: List<OcrLine>): String? = qualityBadgePlaylistRows(lines)?.joinToString("\n") {
    if (it.artist.isBlank()) it.title else "${it.title} - ${it.artist}"
}

internal fun qualityBadgePlaylistRows(lines: List<OcrLine>): List<ImportedSongQuery>? {
    return qualityBadgeSongRows(lines)?.map { row ->
        screenshotRowQuery(row.title, row.metadata, emptyList(), emptyList())
    }
}

internal data class ScreenshotSongRow(val title: OcrLine, val metadata: OcrLine?)

internal fun qualityBadgeSongRows(lines: List<OcrLine>): List<ScreenshotSongRow>? {
    val markers = lines.filter { OCR_QUALITY_BADGE.containsMatchIn(it.text) && it.height > 0 }
    if (markers.size < 3) return null
    val heights = markers.map { it.height }.sorted()
    val height = heights[heights.size / 2].coerceAtLeast(1)
    val listLeft = markers.map { it.left }.sorted().let { it[it.size / 2] }
    val column = lines.filter { abs(it.left - listLeft) <= height * 1.5 && it.height > 0 }
        .sortedBy { it.top }
    // A native row can be reported by both overlapping tiles. Collapse the same baseline before
    // pairing; separate rows with identical words must remain separate.
    val rows = mutableListOf<OcrLine>()
    column.forEach { line ->
        val previous = rows.lastOrNull()
        if (previous != null && abs((previous.top + previous.height / 2) - (line.top + line.height / 2)) <= height / 3) {
            if (line.text.length > previous.text.length) rows[rows.lastIndex] = line
        } else rows += line
    }
    val anchors = rows.mapIndexedNotNull { index, line ->
        if (OCR_QUALITY_BADGE.containsMatchIn(line.text)) rows.getOrNull(index - 1)?.takeIf { previous ->
            line.top - previous.top in height..(height * 4) &&
                !OCR_QUALITY_BADGE.containsMatchIn(previous.text) && !isOcrInterfaceText(previous.text)
        } else null
    }
    if (anchors.size < 3) return null
    val pitch = anchors.zipWithNext().map { (a, b) -> b.top - a.top }.filter { it > height * 4 }.sorted()
        .let { it.getOrNull(it.size / 2) } ?: return null
    val first = anchors.minOf { it.top }
    val footer = lines.filter { it.top > first && OCR_LIST_FOOTER.containsMatchIn(it.text) }.minOfOrNull { it.top } ?: Int.MAX_VALUE
    val listRows = rows.filter { it.top >= first && it.top < footer && !isOcrInterfaceText(it.text) }
    val result = mutableListOf<ScreenshotSongRow>()
    var title: OcrLine? = null
    listRows.forEach { line ->
        val previous = title
        if (previous == null) {
            if (!OCR_QUALITY_BADGE.containsMatchIn(line.text)) title = line
        } else if (line.top - previous.top < pitch / 2) {
            result += ScreenshotSongRow(previous, line)
            title = null
        } else {
            result += ScreenshotSongRow(previous, null)
            title = line
        }
    }
    title?.let { result += ScreenshotSongRow(it, null) }
    return result
}

internal fun screenshotRowQuery(
    title: OcrLine, metadata: OcrLine?, refinedTitles: List<String>, refinedMetadata: List<String>,
): ImportedSongQuery {
    val titles = (listOf(title.text) + refinedTitles).map(::cleanScreenshotTitle).filter(String::isNotBlank).distinct()
    val metadataReadings = (listOf(metadata?.text.orEmpty()) + refinedMetadata)
        .map(::cleanScreenshotMetadata).filter(String::isNotBlank).map(::splitOcrMetadata).distinct()
    val primary = metadataReadings.firstOrNull() ?: ("" to "")
    return ImportedSongQuery(
        title = titles.firstOrNull().orEmpty(), artist = primary.first, album = primary.second,
        titleAlternatives = titles.drop(1),
        artistAlternatives = metadataReadings.map { it.first }.filter(String::isNotBlank).distinct().filter { it != primary.first },
        albumAlternatives = metadataReadings.map { it.second }.filter(String::isNotBlank).distinct().filter { it != primary.second },
        metadataAlternatives = metadataReadings.drop(1).filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .map { ImportedSongMetadataAlternative(it.first, it.second) },
        fromScreenshot = true,
    )
}

private fun cleanScreenshotTitle(value: String): String = value.trim().trimStart('|', '「', '」', ' ')

private fun cleanScreenshotMetadata(value: String): String {
    val marker = OCR_QUALITY_BADGE.find(value)
    return (marker?.let { value.substring(it.range.last + 1) } ?: value)
        .trim().trimStart('|', ']', '[', '「', '」', '(', ')', '）', '（', ' ')
        .replace(Regex("^[0-9]+[A-Z]\\s*"), "")
}

internal fun splitOcrMetadata(metadata: String): Pair<String, String> {
    var depth = 0
    val separator = metadata.indices.firstOrNull { index ->
        val c = metadata[index]
        if (c in "(（[【") depth++
        if (c in ")）]】") depth = (depth - 1).coerceAtLeast(0)
        c in "-–—" && depth == 0 && index > 0 && index < metadata.lastIndex &&
            !(metadata[index - 1].isLetter() && metadata[index - 1].code < 128 &&
                metadata[index + 1].isLetter() && metadata[index + 1].code < 128)
    }
    return if (separator == null) metadata.trim() to "" else
        metadata.substring(0, separator).trim() to metadata.substring(separator + 1).trim()
}

internal fun encodeScreenshotSongs(queries: List<ImportedSongQuery>): String = buildJsonArray {
    queries.forEach { song -> add(buildJsonObject {
        put("title", song.title); put("artist", song.artist); put("album", song.album)
        put("fromScreenshot", song.fromScreenshot)
        put("titleAlternatives", buildJsonArray { song.titleAlternatives.forEach { add(it) } })
        put("artistAlternatives", buildJsonArray { song.artistAlternatives.forEach { add(it) } })
        put("albumAlternatives", buildJsonArray { song.albumAlternatives.forEach { add(it) } })
        put("metadataAlternatives", buildJsonArray {
            song.metadataAlternatives.forEach { metadata -> add(buildJsonObject {
                put("artist", metadata.artist); put("album", metadata.album)
            }) }
        })
    }) }
}.toString()

// OCR often omits whitespace on either side of the artist/album separator. Keep internal
// Latin artist-name hyphens (G-DRAGON, A-Lin), but also accept adjacent Chinese metadata.
private val OCR_METADATA_SEPARATOR = Regex("(?:\\s+[-–—]\\s*|\\s*[-–—]\\s+|(?<=\\p{IsHan})[-–—](?=\\p{IsHan}))")
private val OCR_EXPLICIT_METADATA_SEPARATOR = Regex("\\s+[-–—]\\s+")
private val OCR_INTERFACE_LABELS = setOf("收藏歌单", "播放全部", "全部播放", "添加歌曲", "下载全部", "搜索歌单内歌曲", "选择歌单", "歌单导入")
private val OCR_TRACK_SUMMARY = Regex("^\\d+\\s*首(?:\\s*[·•].*)?$")
private fun isOcrInterfaceText(text: String): Boolean =
    text in OCR_INTERFACE_LABELS || OCR_TRACK_SUMMARY.matches(text)

internal fun joinOcrScreenshots(texts: List<String>): String =
    texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

/**
 * Offline image-to-text adapter for the image import tab. Returns structured physical song rows
 * for automatic matching; reading an image never changes a playlist.
 */
class PlaylistScreenshotReader(private val context: Context) {
    suspend fun read(uris: List<Uri>): AppResult<String> = withContext(Dispatchers.IO) {
        when {
            uris.isEmpty() -> return@withContext AppResult.Failure(-1, "请先选择歌单截图")
            uris.size > MAX_SCREENSHOT_IMPORTS -> return@withContext AppResult.Failure(-1, "图片导入最多支持 8 张截图")
        }

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val pages = buildList {
                uris.forEachIndexed { index, uri ->
                    add(recognizeScreenshot(uri, recognizer, index))
                }
            }
            val queries = pages.flatten().distinctBy { Triple(it.title.lowercase(), it.artist.lowercase(), it.album.lowercase()) }
            if (queries.isEmpty()) AppResult.Failure(-1, "未识别到歌曲文字，请换一张更清晰的截图")
            else AppResult.Success(encodeScreenshotSongs(queries))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppResult.Failure(-1, error.message ?: "图片识别失败", error)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognizeScreenshot(uri: Uri, recognizer: TextRecognizer, index: Int): List<ImportedSongQuery> {
        val decoder = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapRegionDecoder.newInstance(stream, false)
        } ?: throw IllegalArgumentException("第 ${index + 1} 张图片无法读取")
        try {
            val lines = buildList<TiledOcrLine> {
                ocrTilePlan(decoder.width, decoder.height).forEachIndexed { tileIndex, tile ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = decoder.decodeRegion(
                        Rect(0, tile.top, decoder.width, tile.bottom),
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                    ) ?: throw IllegalArgumentException("第 ${index + 1} 张图片无法读取")
                    try {
                        val recognized = recognizer.processAwait(InputImage.fromBitmap(bitmap, 0))
                        currentCoroutineContext().ensureActive()
                        recognized.textBlocks.flatMap { block ->
                            block.lines.map { line ->
                                val bounds = line.boundingBox
                                TiledOcrLine(
                                    OcrLine(
                                        line.text,
                                        bounds?.left ?: 0,
                                        (bounds?.top ?: 0) + tile.top,
                                        bounds?.width() ?: 0,
                                        bounds?.height() ?: 0,
                                    ),
                                    tileIndex,
                                    tile,
                                )
                            }
                        }.forEach(::add)
                    } finally {
                        // processAwait deliberately completes its native task before cancellation
                        // is observed, so this bitmap cannot be recycled while ML Kit still reads it.
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
            val merged = mergeOverlappingTileLines(lines)
            val rows = qualityBadgeSongRows(merged)
            if (rows == null) return parseImportedSongText(orderedOcrText(merged)).map { it.copy(fromScreenshot = true) }
            val typicalHeight = rows.map { it.title.height }.sorted().let { it[it.size / 2] }.coerceAtLeast(16)
            val left = rows.map { it.title.left }.sorted().let { it[it.size / 2] }
            val offsets = rows.mapNotNull { row -> row.metadata?.let { it.top - row.title.top } }.sorted()
            val subtitleOffset = offsets.getOrNull(offsets.size / 2) ?: typicalHeight * 3 / 2
            return rows.map { row ->
                currentCoroutineContext().ensureActive()
                val metadata = row.metadata ?: OcrLine("", left, row.title.top + subtitleOffset, 0, typicalHeight)
                val titles = refineLine(decoder, recognizer, row.title, left, false)
                val subtitles = refineLine(decoder, recognizer, metadata, left, true)
                screenshotRowQuery(row.title, row.metadata, titles, subtitles)
            }
        } finally {
            decoder.recycle()
        }
    }

    /** Re-read each physical field without neighbouring covers, menus, or coloured quality badges. */
    private suspend fun refineLine(
        decoder: BitmapRegionDecoder, recognizer: TextRecognizer, line: OcrLine, listLeft: Int, metadata: Boolean,
    ): List<String> {
        val padding = (line.height / 3).coerceAtLeast(8)
        val crop = Rect(
            (listLeft - padding).coerceAtLeast(0), (line.top - padding).coerceAtLeast(0),
            minOf(decoder.width - padding * 3, decoder.width * 92 / 100).coerceAtLeast(listLeft + 1),
            (line.top + line.height + padding).coerceAtMost(decoder.height),
        )
        if (crop.width() <= 0 || crop.height() <= 0) return emptyList()
        val region = decoder.decodeRegion(crop, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return emptyList()
        try {
            var badgeEnd = 0
            if (metadata) {
                // Restrict colour removal to the badge area. The grey artist/album glyphs remain.
                val scanWidth = minOf(region.width / 3, line.height * 9)
                for (x in 0 until scanWidth) {
                    var colourPixels = 0
                    for (y in 0 until region.height) {
                        val pixel = region.getPixel(x, y)
                        if (Color.red(pixel) > 140 && Color.red(pixel) - Color.green(pixel) > 20) colourPixels++
                    }
                    if (colourPixels >= 3) badgeEnd = (x + padding / 2).coerceAtMost(region.width - 1)
                }
            }
            val readings = mutableListOf<String>()
            for (scale in listOf(2, 3)) {
                currentCoroutineContext().ensureActive()
                val usefulWidth = region.width - badgeEnd
                if ((usefulWidth + padding * 2).toLong() * (region.height + padding * 2) * scale * scale > MAX_OCR_PIXELS) continue
                // White margins help ML Kit detect thin calligraphy at the edge of the cropped row.
                val margin = padding * scale
                val bitmap = Bitmap.createBitmap(usefulWidth * scale + margin * 2, region.height * scale + margin * 2, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(region, Rect(badgeEnd, 0, region.width, region.height),
                        Rect(margin, margin, margin + usefulWidth * scale, margin + region.height * scale),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    val text = recognizer.processAwait(InputImage.fromBitmap(bitmap, 0))
                    currentCoroutineContext().ensureActive()
                    val ordered = text.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.left ?: 0 }
                    val reading = ordered.joinToString(" ") { it.text }.trim()
                    if (reading.isNotBlank()) readings += reading
                } finally {
                    bitmap.recycle()
                }
            }
            return readings.distinct()
        } finally {
            region.recycle()
        }
    }
}

/** A vertical, original-width OCR region. Bottom is exclusive. */
internal data class OcrTile(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** An OCR line with its source crop, used only to make tile-boundary de-duplication safe. */
internal data class TiledOcrLine(
    val line: OcrLine,
    val tileIndex: Int,
    val tile: OcrTile,
)

/**
 * Plans original-resolution vertical regions so one long screenshot never makes ML Kit receive a
 * downsampled page. Neighbouring regions overlap so a row at a boundary is recognised intact.
 */
internal fun ocrTilePlan(width: Int, height: Int): List<OcrTile> {
    require(width > 0 && height > 0) { "图片尺寸无效" }
    val maxTileHeight = (MAX_OCR_PIXELS / width).toInt()
    require(maxTileHeight > 0) { "图片宽度过大，无法在识别内存限制内处理" }
    if (height <= maxTileHeight) return listOf(OcrTile(0, height))
    val tileHeight = minOf(maxTileHeight, 1600)
    require(maxTileHeight > OCR_TILE_OVERLAP_PX) {
        "图片宽度过大，无法保留识别所需的行重叠"
    }

    val tiles = mutableListOf<OcrTile>()
    var top = 0
    while (top < height) {
        val bottom = (top.toLong() + tileHeight.toLong()).coerceAtMost(height.toLong()).toInt()
        tiles += OcrTile(top, bottom)
        if (bottom == height) break
        top = bottom - OCR_TILE_OVERLAP_PX
    }
    return tiles
}

/**
 * Only tile-boundary geometry may suppress a duplicate. Equal words elsewhere in a playlist are
 * distinct songs, so text alone must never make a line disappear.
 */
internal fun mergeOverlappingTileLines(lines: List<TiledOcrLine>): List<OcrLine> =
    lines.sortedWith(compareBy<TiledOcrLine> { it.line.top }.thenBy { it.line.left })
        .fold(mutableListOf<TiledOcrLine>()) { kept: MutableList<TiledOcrLine>, candidate: TiledOcrLine ->
        val duplicateIndex = kept.indexOfFirst { existing ->
            existing.tileIndex != candidate.tileIndex && sameTileBoundaryLine(existing.line, candidate.line)
        }
        if (duplicateIndex < 0) {
            kept += candidate
        } else if (distanceFromTileEdge(candidate) > distanceFromTileEdge(kept[duplicateIndex])) {
            kept[duplicateIndex] = candidate
        }
        kept
    }.map(TiledOcrLine::line)

private fun distanceFromTileEdge(line: TiledOcrLine): Int =
    minOf(
        line.line.top - line.tile.top,
        line.tile.bottom - (line.line.top + line.line.height),
    )

private fun sameTileBoundaryLine(first: OcrLine, second: OcrLine): Boolean {
    if (first.width <= 0 || first.height <= 0 || second.width <= 0 || second.height <= 0) return false
    val verticalTolerance = (minOf(first.height, second.height) / 2).coerceAtLeast(6)
    val horizontalTolerance = (minOf(first.width, second.width) / 2).coerceAtLeast(12)
    return kotlin.math.abs(first.top - second.top) <= verticalTolerance &&
        kotlin.math.abs(first.left - second.left) <= horizontalTolerance
}

private suspend fun TextRecognizer.processAwait(image: InputImage): Text =
    // A Task cannot be cancelled. Finish the current native read before the caller's finally
    // recycles its bitmap/closes the recognizer, then honour cancellation between images.
    suspendCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { text -> continuation.resume(text) }
            .addOnFailureListener { error -> continuation.resumeWithException(error) }
    }
