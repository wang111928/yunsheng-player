package com.litemusic.app.feature.playlist.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.data.SearchRepository
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Song
import com.litemusic.shared.model.Album
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers

internal fun matchImportedSong(query: ImportedSongQuery, candidates: List<Song>): ImportedSongMatch {
    val queryTitles = query.textEvidence(query.title, query.titleAlternatives, ::importSearchBaseTitle)
    val queryCoreTitles = query.textEvidence(query.title, query.titleAlternatives, ::importCoreTitle)
    val queryArtists = query.artistEvidence()
    val queryAlbums = query.textEvidence(query.album, query.albumAlternatives) { it }
    val queryMetadataPairs = query.metadataEvidencePairs()
    if (queryTitles.isEmpty()) {
        return ImportedSongMatch(query, reason = "请补充歌名后再匹配")
    }
    if (queryArtists.isEmpty()) {
        val exactTitles = candidates.filter {
            normalizeImportText(importSearchBaseTitle(it.name)) in queryTitles
        }
        return when (exactTitles.size) {
            1 -> ImportedSongMatch(query, exactTitles.single(), 50, "缺少歌手，请手动确认")
            else -> ImportedSongMatch(query, reason = "缺少歌手，无法安全自动匹配")
        }
    }

    val evidence = candidates.map { song ->
        CandidateEvidence(
            song = song,
            artistExact = song.artistKeys().any { it in queryArtists },
            titleExact = normalizeImportText(importSearchBaseTitle(song.name)) in queryTitles,
            coreTitleExact = normalizeImportText(importCoreTitle(song.name)) in queryCoreTitles,
            aliasExact = song.alia.any { normalizeImportText(importSearchBaseTitle(it)) in queryTitles },
            versionCompatible = query.versionCompatibleWith(song),
            albumExact = query.albumMatches(song.albumName, queryAlbums),
            metadataPairExact = queryMetadataPairs.any { pair -> pair.matches(song) },
        )
    }

    // Only an exact title (or a declared alias) plus exact artist can be selected
    // automatically. A title which merely contains another title remains manual.
    val titleAndArtist = evidence.filter { it.artistExact && (it.titleExact || it.aliasExact || it.coreTitleExact) }
    val safe = titleAndArtist.filter { it.versionCompatible }
    if (safe.isNotEmpty()) {
        val exactTitles = safe.filter { it.titleExact || it.aliasExact }
        if (queryAlbums.isNotEmpty()) {
            val exactAlbumMatches = exactTitles.filter { it.metadataPairExact }
            if (exactAlbumMatches.size == 1) return exactImportMatch(query, exactAlbumMatches.single().song)
            if (exactAlbumMatches.size > 1) return ImportedSongMatch(query, reason = "同名同歌手同专辑存在多个结果，请手动确认")
            val albumMatches = safe.filter { it.metadataPairExact }
            if (albumMatches.size == 1) return exactImportMatch(query, albumMatches.single().song)
            if (albumMatches.size > 1) return ImportedSongMatch(query, reason = "同名同歌手同专辑存在多个结果，请手动确认")
            val correctedAlbum = safe.filter { candidate ->
                (candidate.titleExact || candidate.aliasExact) && query.fromScreenshot &&
                    queryMetadataPairs.any { pair -> pair.matchesWithAlbumCorrection(candidate.song) }
            }
            if (correctedAlbum.size == 1) return exactImportMatch(query, correctedAlbum.single().song)
            return ImportedSongMatch(query, safe.first().song, 70, "歌名和歌手相同，但专辑不一致，请确认")
        }
        if (exactTitles.size == 1) return exactImportMatch(query, exactTitles.single().song)
        if (exactTitles.size > 1) return ImportedSongMatch(query, reason = "同名同歌手存在不同专辑，请选择正确版本")
        if (safe.size == 1) return exactImportMatch(query, safe.single().song)
        return ImportedSongMatch(query, reason = "同名同歌手存在不同专辑，请选择正确版本")
    }

    // Version labels are semantic: never substitute a Cover/Live/Remix/语言版 for
    // the requested recording. Show the closest row for an explicit manual choice.
    titleAndArtist.firstOrNull { it.coreTitleExact }?.let {
        return ImportedSongMatch(query, it.song, 60, "版本信息不一致，请手动确认")
    }

    // OCR can make a one-character title mistake. It is safe only when both the
    // artist and album independently agree and there is exactly one such candidate.
    if (queryAlbums.isNotEmpty()) {
        val ocrCorrections = evidence.filter {
            it.metadataPairExact && it.versionCompatible &&
                queryCoreTitles.any { title -> areTitlesOneEditApart(title, normalizeImportText(importCoreTitle(it.song.name))) }
        }
        if (ocrCorrections.size == 1) return exactImportMatch(query, ocrCorrections.single().song)
        val artistCorrections = evidence.filter {
            query.fromScreenshot && (it.titleExact || it.coreTitleExact || it.aliasExact) && it.versionCompatible &&
                queryMetadataPairs.any { pair -> pair.matchesWithAnchoredArtistCorrection(it.song) }
        }
        if (artistCorrections.size == 1) return exactImportMatch(query, artistCorrections.single().song)
    }

    if (queryCoreTitles.all { it.length < 3 }) return ImportedSongMatch(query, reason = "歌名过短，未自动匹配")
    val partial = evidence.firstOrNull {
        it.artistExact && it.versionCompatible &&
            normalizeImportText(importCoreTitle(it.song.name)).let { candidateTitle ->
                queryCoreTitles.any { title -> candidateTitle.contains(title) || title.contains(candidateTitle) }
            }
    }
    return partial?.let { ImportedSongMatch(query, it.song, 45, "歌名可能不完整，请手动确认") }
        ?: ImportedSongMatch(query, reason = "未找到可确认的网易云歌曲")
}

private data class CandidateEvidence(
    val song: Song,
    val artistExact: Boolean,
    val titleExact: Boolean,
    val coreTitleExact: Boolean,
    val aliasExact: Boolean,
    val versionCompatible: Boolean,
    val albumExact: Boolean,
    val metadataPairExact: Boolean,
)

private fun exactImportMatch(query: ImportedSongQuery, song: Song) = ImportedSongMatch(query, song, 100)

private fun Song.artistKeys(): List<String> = (ar.flatMap { artist -> listOf(artist.name) + artist.alias } + artistNames)
    .flatMap(::splitImportArtists)
    .map(::normalizeImportText)
    .filter(String::isNotBlank)

private fun ImportedSongQuery.textEvidence(
    primary: String,
    alternatives: List<String>,
    transform: (String) -> String,
): Set<String> = (listOf(primary) + alternatives)
    .map(transform)
    .map(::normalizeImportText)
    .filter(String::isNotBlank)
    .toSet()

private fun ImportedSongQuery.artistEvidence(): Set<String> = (listOf(artist) + artistAlternatives)
    .flatMap(::splitImportArtists)
    .map(::normalizeImportText)
    .filter(String::isNotBlank)
    .toSet()

private fun ImportedSongQuery.versionCompatibleWith(song: Song): Boolean {
    val expected = titleVersionSignatures()
    if (expected.size > 1) return false
    val requested = expected.singleOrNull().orEmpty()
    // Numeric cover metadata supplies positive evidence when Cover is requested. It does
    // not add a hidden display-version label to an otherwise exact artist/album reference.
    val candidate = importVersionSignature(song.name + " " + song.alia.joinToString(" ") + " " + song.tns.joinToString(" ")) +
        if ("cover" in requested && song.originCoverType == 2) setOf("cover") else emptySet()
    return when {
        requested.isNotEmpty() -> candidate == requested
        else -> candidate.isEmpty()
    }
}

private fun ImportedSongQuery.titleVersionSignatures(): Set<Set<String>> =
    (listOf(title) + titleAlternatives)
        .map(::importVersionSignature)
        .filter(Set<String>::isNotEmpty)
        .toSet()

private fun ImportedSongQuery.albumMatches(candidateAlbum: String, queryAlbums: Set<String>): Boolean {
    val normalizedCandidate = normalizeImportText(candidateAlbum)
    if (normalizedCandidate in queryAlbums) return true
    return fromScreenshot && hasTruncatedAlbumEvidence() && queryAlbums.any { album ->
        album.length >= 3 && (normalizedCandidate.startsWith(album) || album.startsWith(normalizedCandidate))
    }
}

private fun ImportedSongQuery.hasTruncatedAlbumEvidence(): Boolean = (listOf(album) + albumAlternatives)
    .any(::isTruncatedImportAlbum)

private fun isTruncatedImportAlbum(value: String): Boolean = value.trimEnd().let {
    it.trimEnd('.').endsWith("…") || it.endsWith("...")
}

private data class ImportMetadataEvidence(val artist: String, val album: String, val albumTruncated: Boolean) {
    fun matches(song: Song): Boolean = song.artistKeys().any { it in splitImportArtists(artist).map(::normalizeImportText) } &&
        albumMatches(song.albumName)

    fun matchesWithAlbumCorrection(song: Song): Boolean = !albumTruncated &&
        song.artistKeys().any { it in splitImportArtists(artist).map(::normalizeImportText) } &&
        normalizeImportText(album).let { expected ->
            expected.length >= 4 && areTitlesOneEditApart(expected, normalizeImportText(song.albumName))
        }

    fun matchesWithAnchoredArtistCorrection(song: Song): Boolean = !albumTruncated && albumMatches(song.albumName) &&
        splitImportArtists(artist).map(::normalizeImportText).any { expected ->
            val anchor = expected.takeWhile { it in 'a'..'z' }
            anchor.length >= 6 && expected.length > anchor.length && song.artistKeys().any { candidate ->
                candidate.takeWhile { it in 'a'..'z' } == anchor && areTitlesOneEditApart(expected, candidate)
            }
        }

    private fun albumMatches(candidateAlbum: String): Boolean {
        val expected = normalizeImportText(if (albumTruncated) album.trimEnd().trimEnd('…', '.') else album)
        val candidate = normalizeImportText(candidateAlbum)
        return candidate == expected || (albumTruncated && expected.length >= 2 && candidate.startsWith(expected))
    }
}

private fun ImportedSongQuery.metadataEvidencePairs(): List<ImportMetadataEvidence> = buildList {
    addMetadataEvidence(artist, album)
    metadataAlternatives.forEach { metadata -> addMetadataEvidence(metadata.artist, metadata.album) }
}

private fun MutableList<ImportMetadataEvidence>.addMetadataEvidence(artist: String, album: String) {
    if (artist.isBlank() || album.isBlank()) return
    add(ImportMetadataEvidence(
        artist = artist,
        album = album,
        albumTruncated = isTruncatedImportAlbum(album),
    ))
}

private fun splitImportArtists(value: String): List<String> = value
    .split(Regex("""\s*(?:/|、|&|＆|,|，|;|；|\bfeat\.?\b|\bft\.?\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
    .map(String::trim)
    .filter(String::isNotBlank)
    .flatMap(::expandParenthesizedArtistAliases)

private fun expandParenthesizedArtistAliases(value: String): List<String> = buildList {
    add(value)
    val aliases = Regex("""[（(]([^（）()]+)[）)]""").findAll(value)
        .map { it.groupValues[1].trim() }
        .filter(String::isNotBlank)
        .toList()
    addAll(aliases)
    value.replace(Regex("""[（(][^（）()]*[）)]"""), "").trim()
        .takeIf(String::isNotBlank)
        ?.let(::add)
}

/** Try each independent OCR title reading, prioritising corroboration by album text. */
internal fun importSearchKeywords(query: ImportedSongQuery): List<String> {
    val albumTitles = (listOf(query.album) + query.albumAlternatives).map(::importCoreTitle).map(::normalizeImportText).toSet()
    val titles = (listOf(query.title) + query.titleAlternatives)
        .map(::importCoreTitle).filter(String::isNotBlank).distinctBy(::normalizeImportText)
        .sortedByDescending { normalizeImportText(it) in albumTitles }
        .take(3)
    val artists = (listOf(query.artist) + query.artistAlternatives)
        .flatMap(::splitImportArtists)
        .distinctBy(::normalizeImportText).take(3)
    return buildList {
        titles.forEach { title ->
            artists.firstOrNull()?.let { add("$title $it") }
            add(title)
        }
        titles.forEach { title -> artists.drop(1).forEach { add("$title $it") } }
    }.distinct().take(9)
}

internal fun importAlbumSearchKeyword(query: ImportedSongQuery): String? {
    val titles = query.textEvidence(query.title, query.titleAlternatives, ::importCoreTitle)
    val album = (listOf(query.album) + query.albumAlternatives)
        .filter { it.isNotBlank() && !isTruncatedImportAlbum(it) }
        .sortedByDescending { normalizeImportText(it) in titles }.firstOrNull() ?: return null
    val artist = (listOf(query.artist) + query.artistAlternatives).flatMap(::splitImportArtists).firstOrNull() ?: return null
    return "$album $artist"
}

internal fun importAlbumMatches(query: ImportedSongQuery, album: Album): Boolean = album.id > 0 &&
    normalizeImportText(album.name) in query.textEvidence(query.album, query.albumAlternatives) { it } &&
    album.artist?.let { artist -> (listOf(artist.name) + artist.alias).map(::normalizeImportText).any { it in query.artistEvidence() } } == true

internal fun importSearchBaseTitle(value: String): String = value
    .replace(IMPORT_ANNOTATION) { annotation ->
        if (PROMOTIONAL_MARKERS.any { marker -> annotation.value.contains(marker, ignoreCase = true) }) "" else annotation.value
    }
    .replace(TRAILING_PROMOTIONAL_ANNOTATION, "")
    .replace(TRAILING_UNCLOSED_PROMOTIONAL_ANNOTATION, "")
    .trimEnd('…', '.', '。')
    .trim()

private fun importCoreTitle(value: String): String = importSearchBaseTitle(value)
    .replace(IMPORT_ANNOTATION, "")
    .replace(Regex("[（(【\\[].*$"), "")
    .trim()

private fun importVersionSignature(value: String): Set<String> = STRICT_VERSION_MARKERS
    .filter { marker -> value.contains(marker, ignoreCase = true) }
    .mapTo(linkedSetOf()) { if (it == "翻唱") "cover" else it }

private fun areTitlesOneEditApart(first: String, second: String): Boolean {
    if (first == second || first.length < 3 || second.length < 3 || kotlin.math.abs(first.length - second.length) > 1) return false
    var firstIndex = 0
    var secondIndex = 0
    var edits = 0
    while (firstIndex < first.length && secondIndex < second.length) {
        if (first[firstIndex] == second[secondIndex]) {
            firstIndex++
            secondIndex++
        } else {
            if (++edits > 1) return false
            if (first.length > second.length) firstIndex++
            else if (second.length > first.length) secondIndex++
            else {
                firstIndex++
                secondIndex++
            }
        }
    }
    return true
}

private fun normalizeImportText(value: String): String = value
    .lowercase()
    .replace('，', ',')
    .replace(Regex("[\\s·•/\\-—–_()（）【】\\[\\]]+"), "")

private val IMPORT_ANNOTATION = Regex("""[（(【\[][^）)】\]]*[）)】\]]""")
private val TRAILING_PROMOTIONAL_ANNOTATION = Regex(
    "\\s*[-—–]\\s*(?:电视剧|电影|动画|综艺|主题曲|片尾曲|插曲|原曲|ost).*$",
    RegexOption.IGNORE_CASE,
)
private val PROMOTIONAL_MARKERS = setOf("企划", "电视剧", "电影", "动画", "综艺", "主题曲", "片尾曲", "插曲", "原曲", "ost", "官方", "网易云音乐", "听歌报告")
private val TRAILING_UNCLOSED_PROMOTIONAL_ANNOTATION = Regex(
    """[（(【\[].*(?:企划|电视剧|电影|动画|综艺|主题曲|片尾曲|插曲|原曲|ost|官方|网易云音乐|听歌报告).*$""",
    RegexOption.IGNORE_CASE,
)
private val STRICT_VERSION_MARKERS = setOf(
    "live", "现场", "cover", "翻唱", "remix", "mix", "伴奏", "demo", "女声", "男声", "国语", "粤语", "日语", "英文", "acoustic", "sped", "slowed", "dj",
)

/** A cancelled/stale preview may never overwrite the newer input's results. */
internal fun shouldApplyImportPreview(responseGeneration: Long, activeGeneration: Long): Boolean =
    responseGeneration == activeGeneration

internal fun finishImportSearch(query: ImportedSongQuery, candidates: List<Song>, incomplete: Boolean): ImportedSongMatch {
    val match = matchImportedSong(query, candidates)
    return if (incomplete && !match.selectedByDefault) match.copy(
        searchIncomplete = true, reason = "搜索暂未完成，请稍后重试") else match
}

internal fun preserveRetriedImportChoice(previous: ImportedSongMatch, refreshed: ImportedSongMatch, selectedIds: Set<Long>): ImportedSongMatch =
    if (previous.song?.id in selectedIds) previous.copy(searchIncomplete = refreshed.searchIncomplete) else refreshed

internal class ImportSearchCoordinator(
    private val minimumIntervalMillis: Long = 1_500L,
    private val maximumCachedQueries: Int = 512,
) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<Pair<String, Int>, AppResult<List<Song>>>()
    private var requested = false
    var limited = false
        private set

    fun clearLimited() { limited = false }

    suspend fun search(keyword: String, limit: Int, request: suspend () -> AppResult<List<Song>>): AppResult<List<Song>> = mutex.withLock {
        cache[keyword to limit]?.let { return it }
        if (limited) return AppResult.Failure(405, "搜索受限，请稍后重试")
        if (requested) delay(minimumIntervalMillis)
        requested = true
        request().also { result ->
            if (result is AppResult.Success) {
                cache[keyword to limit] = result
                while (cache.size > maximumCachedQueries.coerceAtLeast(1)) cache.remove(cache.keys.first())
            }
            if (result is AppResult.Failure && result.code in setOf(405, -460, -462)) limited = true
        }
    }
}

class PlaylistImportViewModel(
    private val playlists: PlaylistRepository,
    private val search: SearchRepository,
    private val externalReader: ExternalPlaylistReader,
    private val draftStore: PlaylistImportDraftStore? = null,
) : ViewModel() {
    data class UiState(
        val input: String = "",
        val sourceTitle: String = "",
        val sourceTotalCount: Int = 0,
        val sourceReadCompleteness: ExternalPlaylistReadCompleteness = ExternalPlaylistReadCompleteness.UNKNOWN,
        val queries: List<ImportedSongQuery> = emptyList(),
        val matches: List<ImportedSongMatch> = emptyList(),
        val selectedSongIds: Set<Long> = emptySet(),
        val overflowCount: Int = 0,
        val loading: Boolean = false,
        val processedQueries: Int = 0,
        val totalQueries: Int = 0,
        val importing: Boolean = false,
        val creatingDestination: Boolean = false,
        val creationUnconfirmed: Boolean = false,
        val destinationPlaylists: List<Playlist> = emptyList(),
        val loadingDestinations: Boolean = false,
        val destinationError: String? = null,
        val selectedDestinationId: Long? = null,
        val newPlaylistName: String = "导入的歌单",
        val error: String? = null,
        val result: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var previewGeneration = 0L
    private var previewJob: Job? = null
    private var importJob: Job? = null
    private var searchCoordinator = ImportSearchCoordinator()
    private val completedSongIds = mutableSetOf<Long>()
    private var draftActive = true
    private var draftOwnerUserId = 0L
    private val draftMutex = Mutex()

    init { draftStore?.let { store -> viewModelScope.launch { restoreDraft(store) } } }

    private suspend fun restoreDraft(store: PlaylistImportDraftStore) {
        if (_state.value.input.isNotBlank() || previewGeneration != 0L) return
        val userId = playlists.currentUserId()
        draftOwnerUserId = userId
        val initialGeneration = previewGeneration
        val draft = withContext(Dispatchers.IO) { store.load(userId) } ?: return
        val targetUnavailable = draft.destination != null && playlists.membershipForImport(draft.destination) is AppResult.Failure
        if (playlists.currentUserId() != userId || previewGeneration != initialGeneration || _state.value.input.isNotBlank()) return
        completedSongIds += draft.completed
        _state.value = UiState(input = draft.input, sourceTitle = draft.title, sourceTotalCount = draft.total,
            sourceReadCompleteness = draft.completeness, queries = draft.queries, matches = draft.matches,
            selectedSongIds = draft.selected, overflowCount = draft.overflow, selectedDestinationId = draft.destination,
            newPlaylistName = draft.newName, creationUnconfirmed = draft.creationPending && draft.destination == null,
            result = if (draft.creationPending && draft.destination == null) "已恢复草稿；上次创建结果未确认，请从已有歌单中选择目标，避免重复创建"
                else if (targetUnavailable) "已恢复导入草稿；目标歌单暂不可读取，重试前请重新选择" else "已恢复未完成的导入草稿")
        loadDestinations()
    }

    private suspend fun persistDraft(expectedUserId: Long? = null): Boolean {
        val store = draftStore ?: return true
        return draftMutex.withLock {
            if (!draftActive) return@withLock true
            val userId = playlists.currentUserId()
            if (draftOwnerUserId == 0L) draftOwnerUserId = userId
            if (userId <= 0L || draftOwnerUserId != userId || (expectedUserId != null && expectedUserId != userId)) return@withLock false
            val snapshot = _state.value
            val completed = completedSongIds.toSet()
            try { withContext(Dispatchers.IO) { store.save(userId, snapshot, completed) }; true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(error = "导入草稿无法保存，请检查设备空间") }; false }
        }
    }

    fun setInput(value: String) {
        if (_state.value.importing) return
        draftActive = true
        completedSongIds.clear()
        ++previewGeneration
        previewJob?.cancel()
        val overflow = value.length > MAX_IMPORT_FILE_BYTES
        _state.update {
            it.copy(
                input = value.take(MAX_IMPORT_FILE_BYTES),
                sourceTitle = "",
                sourceTotalCount = 0,
                sourceReadCompleteness = ExternalPlaylistReadCompleteness.UNKNOWN,
                queries = emptyList(),
                overflowCount = 0,
                matches = emptyList(),
                selectedSongIds = emptySet(),
                loading = false,
                error = if (overflow) "输入超过 1 MiB 限制，请删减后再匹配" else null,
                result = null,
            )
        }
        viewModelScope.launch { persistDraft() }
    }
    fun setDestination(id: Long?) {
        if (_state.value.importing) return
        draftActive = true
        _state.update { it.copy(selectedDestinationId = id, creationUnconfirmed = if (id != null) false else it.creationUnconfirmed) }
        viewModelScope.launch { persistDraft() }
    }
    fun setNewPlaylistName(value: String) {
        if (_state.value.importing) return
        draftActive = true
        _state.update { it.copy(newPlaylistName = value) }
        viewModelScope.launch { persistDraft() }
    }
    fun clearResult() = _state.update { it.copy(result = null) }
    fun reportError(message: String) = _state.update { it.copy(error = message, loading = false) }
    fun cancelImport() {
        if (_state.value.creatingDestination) {
            _state.update { it.copy(result = "正在创建目标歌单，请等待结果，避免重复创建") }
            return
        }
        importJob?.cancel()
        _state.update { it.copy(result = "已请求停止；已发出的批次可能已写入，重试前会重新读取目标歌单") }
    }
    fun cancelPreview() {
        ++previewGeneration
        previewJob?.cancel()
        _state.update { it.copy(loading = false) }
    }
    fun toggleSong(songId: Long) { _state.update { current ->
        if (current.importing || current.loading) current else current.copy(selectedSongIds = if (songId in current.selectedSongIds) current.selectedSongIds - songId else current.selectedSongIds + songId)
    }; viewModelScope.launch { persistDraft() } }

    fun loadText(raw: String = _state.value.input, sourceLabel: String = "文字导入") {
        if (_state.value.importing) return
        val queries = parseImportedText(raw)
        if (queries.isEmpty()) {
            previewJob?.cancel()
            ++previewGeneration
            _state.update { it.copy(loading = false, error = "没有识别到歌曲，请按“歌名 - 歌手”每行一首粘贴", matches = emptyList(), selectedSongIds = emptySet()) }
            return
        }
        resolveQueries(queries, sourceLabel, importOverflowCount(raw))
    }

    fun loadLink(urlOrId: String = _state.value.input) {
        if (_state.value.importing) return
        val linkOrId = extractedImportUrl(urlOrId) ?: urlOrId.trim()
        val id = officialPlaylistId(linkOrId)
        if (id == null) {
            loadExternalPlaylist(linkOrId)
            return
        }
        previewJob?.cancel()
        val generation = ++previewGeneration
        previewJob = viewModelScope.launch {
            searchCoordinator = ImportSearchCoordinator()
            _state.update { it.copy(loading = true, processedQueries = 0, totalQueries = 0, matches = emptyList(), selectedSongIds = emptySet(), error = null, result = null) }
            when (val detail = playlists.previewForImport(id, MAX_IMPORT_SONGS)) {
                is AppResult.Success -> {
                    if (!shouldApplyImportPreview(generation, previewGeneration)) return@launch
                    val source = detail.data
                    val matches = source.tracks.take(MAX_IMPORT_SONGS).map { song ->
                        ImportedSongMatch(ImportedSongQuery(song.name, song.artistNames, song.albumName), song, 100)
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            sourceTitle = source.name,
                            sourceTotalCount = maxOf(source.trackCount, source.trackIds.size, source.tracks.size),
                            sourceReadCompleteness = if (source.tracks.size >= maxOf(source.trackCount, source.trackIds.size, source.tracks.size))
                                ExternalPlaylistReadCompleteness.COMPLETE else ExternalPlaylistReadCompleteness.INCOMPLETE,
                            queries = source.tracks.take(MAX_IMPORT_SONGS).map { song -> ImportedSongQuery(song.name, song.artistNames, song.albumName) },
                            matches = matches,
                            selectedSongIds = defaultSelectedSongIds(matches),
                            overflowCount = (maxOf(source.trackCount, source.trackIds.size, source.tracks.size) - MAX_IMPORT_SONGS).coerceAtLeast(0),
                            newPlaylistName = source.name.ifBlank { "导入的歌单" },
                        )
                    }
                    loadDestinations()
                    persistDraft()
                }
                is AppResult.Failure -> if (shouldApplyImportPreview(generation, previewGeneration)) {
                    _state.update { it.copy(loading = false, error = detail.message) }
                }
            }
        }
    }

    private fun loadExternalPlaylist(url: String) {
        if (!url.trim().startsWith("https://", ignoreCase = true)) {
            _state.update { it.copy(error = "请粘贴完整的公开歌单链接；普通歌曲文字请使用“文字导入”") }
            return
        }
        previewJob?.cancel()
        val generation = ++previewGeneration
        previewJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, matches = emptyList(), selectedSongIds = emptySet(), error = null, result = null) }
            when (val result = externalReader.read(url)) {
                is AppResult.Success -> {
                    if (!shouldApplyImportPreview(generation, previewGeneration)) return@launch
                    previewJob = null
                    resolveQueries(result.data.songs.take(MAX_IMPORT_SONGS), result.data.title.ifBlank { result.data.sourceLabel },
                        (result.data.totalCount - MAX_IMPORT_SONGS).coerceAtLeast(0), result.data.totalCount,
                        result.data.readCompleteness)
                }
                is AppResult.Failure -> if (shouldApplyImportPreview(generation, previewGeneration)) {
                    _state.update { it.copy(loading = false, error = result.message) }
                }
            }
        }
    }

    fun resolveQueries(queries: List<ImportedSongQuery>, sourceTitle: String, overflowCount: Int = 0, sourceTotalCount: Int = 0,
        sourceReadCompleteness: ExternalPlaylistReadCompleteness = ExternalPlaylistReadCompleteness.UNKNOWN) =
        startResolution(queries, sourceTitle, overflowCount, sourceTotalCount, sourceReadCompleteness)

    private fun startResolution(queries: List<ImportedSongQuery>, sourceTitle: String, overflowCount: Int, sourceTotalCount: Int,
        sourceReadCompleteness: ExternalPlaylistReadCompleteness = ExternalPlaylistReadCompleteness.UNKNOWN, previous: UiState? = null) {
        if (_state.value.importing) return
        previewJob?.cancel()
        draftActive = true
        val generation = ++previewGeneration
        previewJob = viewModelScope.launch {
            searchCoordinator.clearLimited()
            val pendingIndices = queries.indices.filter { previous == null || previous.matches.getOrNull(it)?.searchIncomplete != false }.toSet()
            _state.update { it.copy(loading = true, processedQueries = 0, totalQueries = pendingIndices.size,
                sourceTitle = sourceTitle, sourceTotalCount = sourceTotalCount, sourceReadCompleteness = sourceReadCompleteness,
                queries = queries, overflowCount = overflowCount,
                matches = previous?.matches ?: queries.map { query -> ImportedSongMatch(query, reason = "等待匹配", searchIncomplete = true) },
                selectedSongIds = previous?.selectedSongIds.orEmpty(), error = null, result = null) }
            persistDraft()
            val semaphore = Semaphore(4)
            val matches = coroutineScope {
                queries.mapIndexed { index, query -> async {
                    if (index !in pendingIndices) return@async previous!!.matches[index]
                    semaphore.withPermit {
                        resolveImportQuery(query).let { refreshed ->
                            val old = previous?.matches?.getOrNull(index)
                            if (old == null) refreshed else preserveRetriedImportChoice(old, refreshed, previous?.selectedSongIds.orEmpty())
                        }.also { resolved ->
                            if (shouldApplyImportPreview(generation, previewGeneration)) {
                                _state.update { current -> current.copy(processedQueries = current.processedQueries + 1,
                                    matches = current.matches.mapIndexed { matchIndex, match -> if (matchIndex == index) resolved else match },
                                    selectedSongIds = if (resolved.selectedByDefault) current.selectedSongIds + resolved.song!!.id else current.selectedSongIds) }
                                // Persist bounded checkpoints, retaining already matched songs if the
                                // process ends during a large import without syncing on every row.
                                if (_state.value.processedQueries % 8 == 0) persistDraft()
                            }
                        }
                    }
                } }.awaitAll()
            }
            if (!shouldApplyImportPreview(generation, previewGeneration)) return@launch
            _state.update {
                it.copy(
                    loading = false,
                    sourceTitle = sourceTitle,
                    sourceTotalCount = sourceTotalCount,
                    sourceReadCompleteness = sourceReadCompleteness,
                    queries = queries,
                    matches = matches,
                    selectedSongIds = if (previous == null) defaultSelectedSongIds(matches) else
                        (previous.selectedSongIds + defaultSelectedSongIds(pendingIndices.map { index -> matches[index] }))
                            .intersect(matches.mapNotNull { match -> match.song?.id }.toSet()),
                    overflowCount = overflowCount,
                    error = if (searchCoordinator.limited) "搜索受限，已保留可确认的结果；请稍后重试剩余歌曲" else null,
                    newPlaylistName = if (it.newPlaylistName == "导入的歌单") sourceTitle else it.newPlaylistName,
                )
            }
            loadDestinations()
            persistDraft()
        }
    }

    private suspend fun resolveImportQuery(query: ImportedSongQuery): ImportedSongMatch {
        var lastFailure: AppResult.Failure? = null
        val candidates = mutableListOf<Song>()
        for ((index, keyword) in importSearchKeywords(query).withIndex()) {
            when (val result = searchImportSongs(keyword, limit = if (index == 0) 20 else 30)) {
                is AppResult.Failure -> {
                    lastFailure = result
                }
                is AppResult.Success -> {
                    candidates += result.data
                    val match = matchImportedSong(query, candidates.distinctImportSongs())
                    if (match.selectedByDefault) {
                        return match
                    }
                }
            }
        }
        // Some recordings are absent from song-search results but remain in the official
        // album catalogue. Fetch only albums whose title and artist agree with source evidence.
        val albumKeyword = importAlbumSearchKeyword(query)
        if (albumKeyword != null && !searchCoordinator.limited) {
            val albumResult = searchCoordinator.search("album:$albumKeyword", 10) {
                withTimeoutOrNull(15_000) {
                    when (val albums = search.albumsForImport(albumKeyword)) {
                        is AppResult.Failure -> albums
                        is AppResult.Success -> {
                            val songs = mutableListOf<Song>()
                            var failure: AppResult.Failure? = null
                            for (album in albums.data.filter { importAlbumMatches(query, it) }.take(2)) {
                                delay(1_500)
                                when (val tracks = search.albumSongsForImport(album.id)) {
                                    is AppResult.Failure -> failure = tracks
                                    is AppResult.Success -> songs += tracks.data
                                }
                            }
                            failure ?: AppResult.Success(songs)
                        }
                    }
                } ?: AppResult.Failure(-1, "专辑搜索超时")
            }
            when (albumResult) {
                is AppResult.Failure -> lastFailure = albumResult
                is AppResult.Success -> {
                    candidates += albumResult.data
                }
            }
        }
        val combined = candidates.distinctImportSongs()
        val final = finishImportSearch(query, combined, lastFailure != null)
        return final
    }

    fun retrySearch() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.importing || snapshot.queries.isEmpty()) return
        startResolution(snapshot.queries, snapshot.sourceTitle, snapshot.overflowCount, snapshot.sourceTotalCount,
            snapshot.sourceReadCompleteness, previous = snapshot)
    }

    private fun List<Song>.distinctImportSongs(): List<Song> = distinctBy { song ->
            if (song.id > 0L) "id:${song.id}" else "metadata:${song.name}\u0000${song.artistNames}\u0000${song.albumName}"
        }

    private suspend fun searchImportSongs(keyword: String, limit: Int): AppResult<List<Song>> {
        if (keyword.isBlank()) return AppResult.Failure(-1, "缺少歌曲关键词")
        return searchCoordinator.search(keyword, limit) { searchImportSongsWithRetry(keyword, limit) }
    }

    private suspend fun searchImportSongsWithRetry(keyword: String, limit: Int): AppResult<List<Song>> {
        var failure: AppResult.Failure? = null
        repeat(2) {
            when (val result = withTimeoutOrNull(7_000) {
                search.search(keyword, SearchRepository.Type.SONG, 0, limit)
            }) {
                is AppResult.Success -> return AppResult.Success(result.data.songs)
                is AppResult.Failure -> {
                    failure = result
                    if (result.code in setOf(405, -460, -462)) return result
                    delay(1_500)
                }
                null -> failure = AppResult.Failure(-1, "搜索超时")
            }
        }
        return failure ?: AppResult.Failure(-1, "搜索失败")
    }

    fun loadDestinations() {
        if (_state.value.loadingDestinations) return
        _state.update { it.copy(loadingDestinations = true, destinationError = null) }
        viewModelScope.launch {
            val uid = playlists.currentUserId()
            when (val result = playlists.myPlaylists()) {
                is AppResult.Success -> _state.update { state ->
                    val sameOwner = playlists.currentUserId() == uid && (draftOwnerUserId == 0L || draftOwnerUserId == uid)
                    state.copy(loadingDestinations = false, destinationPlaylists = if (sameOwner) result.data.filter { uid > 0 && (it.userId == uid || it.creator?.userId == uid) } else emptyList(),
                        destinationError = if (sameOwner) null else "账号已变化，请重新打开导入页")
                }
                is AppResult.Failure -> _state.update { it.copy(loadingDestinations = false, destinationError = "目标歌单读取失败：${result.message}") }
            }
        }
    }

    fun importSelected(onSuccess: (Long) -> Unit) {
        val snapshot = _state.value
        if (importJob?.isActive == true || snapshot.loading) return
        if (snapshot.selectedSongIds.isEmpty()) {
            _state.update { it.copy(error = "没有高置信度的匹配歌曲，请调整导入内容后重试") }
            return
        }
        importJob = viewModelScope.launch {
            val ownerUserId = playlists.currentUserId()
            if (ownerUserId <= 0L) { _state.update { it.copy(error = "登录状态已变化，请重新导入") }; return@launch }
            if (snapshot.selectedDestinationId == null && snapshot.creationUnconfirmed) {
                _state.update { it.copy(error = "上次创建结果未确认，请选择已有歌单作为目标后继续") }
                return@launch
            }
            draftActive = true
            _state.update { it.copy(importing = true, creatingDestination = snapshot.selectedDestinationId == null, error = null, result = null) }
            var writtenTarget: Long? = null
            try {
                if (snapshot.selectedDestinationId == null && !persistDraft(ownerUserId)) return@launch
                val target = snapshot.selectedDestinationId ?: when (val create = playlists.create(snapshot.newPlaylistName.trim().ifBlank { "导入的歌单" })) {
                    is AppResult.Success -> create.data.playlist?.id?.takeIf { it > 0L }
                    is AppResult.Failure -> null
                }
                if (target == null) {
                    _state.update { it.copy(creationUnconfirmed = true, error = "创建结果未确认，请先刷新“我的歌单”检查是否已创建，再选择目标重试") }
                    return@launch
                }
                if (playlists.currentUserId() != ownerUserId) {
                    _state.update { it.copy(error = "登录状态已变化，未向目标歌单写入歌曲") }
                    return@launch
                }
                // Persist a just-created target before any batch write. A retry then
                // continues in that list rather than creating a duplicate playlist.
                _state.update { it.copy(selectedDestinationId = target, creatingDestination = false) }
                if (!persistDraft(ownerUserId)) return@launch
                writtenTarget = target
                val report = when (val result = writePlaylistImport(snapshot.matches, snapshot.selectedSongIds,
                    readExisting = {
                        if (playlists.currentUserId() != ownerUserId) return@writePlaylistImport AppResult.Failure(-1, "登录状态已变化，已停止写入")
                        playlists.membershipForImport(target)
                    },
                    writeBatch = { batch ->
                        if (playlists.currentUserId() != ownerUserId) return@writePlaylistImport AppResult.Failure(-1, "登录状态已变化，已停止写入")
                        when (val response = playlists.addTracks(target, batch, ownerUserId)) {
                            is AppResult.Failure -> response
                            is AppResult.Success -> if (response.data.code == 200) AppResult.Success(Unit)
                                else AppResult.Failure(response.data.code, "批次写入失败")
                        }
                    },
                    onBatchWritten = { batch ->
                        completedSongIds += batch
                        persistDraft(ownerUserId)
                    },
                )) {
                    is AppResult.Success -> result.data
                    is AppResult.Failure -> {
                        _state.update { it.copy(error = "无法读取目标歌单，已停止写入：${result.message}") }
                        return@launch
                    }
                }
                val added = report.added
                val failed = report.failed
                playlists.detailCacheInvalidate(target)
                _state.update {
                    it.copy(
                        importing = false,
                        result = if (failed == 0) "已写入 $added 首歌曲" else "已写入 $added 首，$failed 首未写入；可留在此页重试",
                    )
                }
                // Stay on the preview so a partial batch can be retried deliberately.
                if (failed == 0) {
                    draftMutex.withLock {
                        draftActive = false
                        withContext(Dispatchers.IO) { draftStore?.clear(ownerUserId) }
                    }
                } else persistDraft(ownerUserId)
                // Navigation may dispose this ViewModel immediately. Clear the completed
                // draft first, so reopening the importer cannot restore a finished task.
                if (failed == 0 && added > 0) onSuccess(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "导入失败，请重新读取目标歌单后重试") }
            } finally {
                withContext(NonCancellable) {
                    try { writtenTarget?.let { playlists.detailCacheInvalidate(it) } }
                    finally { _state.update { it.copy(importing = false, creatingDestination = false) } }
                }
            }
        }
    }
}
