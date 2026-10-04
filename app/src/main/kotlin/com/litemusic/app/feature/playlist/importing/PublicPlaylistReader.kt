package com.litemusic.app.feature.playlist.importing

import com.litemusic.app.data.PlaylistRepository
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PublicPlaylistReference(val provider: String, val id: String)

private val publicMusicDomains = setOf("music.163.com", "y.qq.com", "kugou.com", "kuwo.cn", "music.apple.com", "open.spotify.com", "music.migu.cn")
private fun allowedMusicHost(host: String): Boolean = publicMusicDomains.any { host == it || host.endsWith(".$it") }

internal fun allowedPublicMusicUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 && allowedMusicHost(it.host)
} == true

internal fun publicPlaylistReference(url: String): PublicPlaylistReference? {
    val link = url.toHttpUrlOrNull() ?: return null
    if (!allowedPublicMusicUrl(url)) return null
    if (link.host == "y.qq.com" || link.host.endsWith(".y.qq.com")) {
        val id = link.queryParameter("disstid") ?: link.queryParameter("dissid") ?: link.queryParameter("id")
            ?.takeIf { link.encodedPath.contains("taoge") || link.encodedPath.contains("playlist") }
            ?: Regex("/(?:playlist|taoge)/(\\d+)(?:/|$)").find(link.encodedPath)?.groupValues?.get(1)
        if (id?.matches(Regex("[1-9]\\d{0,18}")) == true) return PublicPlaylistReference("qq", id)
    }
    return null
}

private val metadataJson = Json { ignoreUnknownKeys = true }
private fun JsonElement?.stringValue(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.firstText(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    this[key].stringValue().trim().takeIf { it.isNotBlank() }
}.orEmpty()
private fun JsonElement?.artistText(): String = when (this) {
    is JsonArray -> map { it.artistText() }.filter { it.isNotBlank() }.joinToString(" / ")
    is JsonObject -> firstText("name", "title")
    else -> this.stringValue()
}

internal fun parseQqPlaylistMetadata(raw: String): ExternalPlaylistSource? = runCatching {
    val payload = metadataJson.parseToJsonElement(raw).jsonObject
    if (payload["code"]?.jsonPrimitive?.intOrNull != 0) return null
    val list = (payload["cdlist"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
    val songs = (list["songlist"] as? JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val name = obj.firstText("songname", "title", "name")
        name.takeIf { it.isNotBlank() }?.let { ImportedSongQuery(it, obj["singer"].artistText()) }
    }.distinctBy { it.title.lowercase() to it.artist.lowercase() }
    songs.takeIf { it.isNotEmpty() }?.let { ExternalPlaylistSource("QQ音乐", list.firstText("dissname", "name"), it) }
}.getOrNull()

/** Only actual MusicPlaylist track metadata is accepted, never a page title or login screen. */
internal fun parseMusicPlaylistJsonLd(raw: String): ExternalPlaylistSource? {
    fun findPlaylist(node: JsonElement): JsonObject? = when (node) {
        is JsonArray -> node.firstNotNullOfOrNull(::findPlaylist)
        is JsonObject -> {
            val type = node["@type"]
            if (type.stringValue() == "MusicPlaylist" || (type as? JsonArray)?.any { it.stringValue() == "MusicPlaylist" } == true) node
            else node["@graph"]?.let(::findPlaylist)
        }
        else -> null
    }
    val scripts = Regex("<script\\b[^>]*type\\s*=\\s*['\"]application/ld\\+json['\"][^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
    for (script in scripts.findAll(raw)) {
        val playlist = runCatching { findPlaylist(metadataJson.parseToJsonElement(script.groupValues[1])) }.getOrNull() ?: continue
        val tracks = playlist["track"] ?: playlist["tracks"] ?: playlist["itemListElement"]
        val rows = when (tracks) {
            is JsonArray -> tracks
            is JsonObject -> (tracks["itemListElement"] as? JsonArray) ?: JsonArray(listOf(tracks))
            else -> continue
        }
        val songs = rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val item = (obj["item"] as? JsonObject) ?: obj
            val name = item.firstText("name", "title")
            name.takeIf { it.isNotBlank() }?.let { ImportedSongQuery(it, (item["byArtist"] ?: item["artist"]).artistText()) }
        }.distinctBy { it.title.lowercase() to it.artist.lowercase() }
        if (songs.isNotEmpty()) return ExternalPlaylistSource("公开歌单", playlist.firstText("name"), songs)
    }
    return null
}

/** An independent no-cookie client prevents the project's login data reaching other platforms. */
class PublicPlaylistReader(private val playlists: PlaylistRepository) : ExternalPlaylistReader {
    private val http = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun read(url: String): AppResult<ExternalPlaylistSource> = withContext(Dispatchers.IO) {
        try {
            var current = url.trim().toHttpUrlOrNull() ?: return@withContext AppResult.Failure(-1, "请粘贴完整歌单链接")
            if (current.scheme == "http") current = current.newBuilder().scheme("https").build()
            repeat(6) { hop ->
                ensureActive()
                if (!allowedPublicMusicUrl(current.toString())) return@withContext AppResult.Failure(-1, "此链接暂不能读取，请使用文字、截图或歌单文件导入")
                officialPlaylistId(current.toString())?.let { id ->
                    return@withContext when (val result = playlists.previewForImport(id, MAX_IMPORT_SONGS)) {
                        is AppResult.Failure -> result
                        is AppResult.Success -> AppResult.Success(ExternalPlaylistSource("网易云音乐", result.data.name,
                            result.data.tracks.map { ImportedSongQuery(it.name, it.artistNames) },
                            maxOf(result.data.trackCount, result.data.trackIds.size, result.data.tracks.size)))
                    }
                }
                val reference = publicPlaylistReference(current.toString())
                val requestUrl = if (reference?.provider == "qq") {
                    "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg".toHttpUrlOrNull()!!.newBuilder()
                        .addQueryParameter("disstid", reference.id).addQueryParameter("type", "1")
                        .addQueryParameter("json", "1").addQueryParameter("utf8", "1").addQueryParameter("onlysong", "0")
                        .addQueryParameter("format", "json").addQueryParameter("g_tk", "5381")
                        .addQueryParameter("loginUin", "0").addQueryParameter("hostUin", "0")
                        .addQueryParameter("inCharset", "utf8").addQueryParameter("outCharset", "utf-8")
                        .addQueryParameter("platform", "yqq").addQueryParameter("needNewCode", "0").build()
                } else current
                val request = Request.Builder().url(requestUrl)
                    .header("Referer", if (reference?.provider == "qq") "https://y.qq.com/" else "https://${current.host}/")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                    .build()
                http.newCall(request).awaitPublicResponse().use { response ->
                    if (response.isRedirect) {
                        val next = response.header("Location")?.let { current.resolve(it) }
                            ?: return@withContext AppResult.Failure(-1, "分享链接已失效")
                        if (hop == 5) return@withContext AppResult.Failure(-1, "分享链接跳转过多，请粘贴完整歌单链接")
                        current = next
                    } else {
                        if (!response.isSuccessful) return@withContext AppResult.Failure(response.code, "歌单暂不可读取，请检查公开设置，或使用文字、截图导入")
                        val body = response.body ?: return@withContext AppResult.Failure(-1, "歌单没有返回内容")
                        val bytes = body.byteStream().use { readImportBytes(it, 4_194_304) }
                            ?: return@withContext AppResult.Failure(-1, "此歌单页面过大，请使用歌单文件导入")
                        ensureActive()
                        val raw = bytes.toString(Charsets.UTF_8)
                        val source = if (reference?.provider == "qq") parseQqPlaylistMetadata(raw) else parseMusicPlaylistJsonLd(raw)
                        return@withContext source?.let { AppResult.Success(it) }
                            ?: AppResult.Failure(-1, "此平台未返回可识别的公开歌单，请改用文字、截图或文件导入")
                    }
                }
            }
            AppResult.Failure(-1, "分享链接无法解析")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppResult.Failure(-1, "读取歌单失败，请检查网络后重试，或使用文字、截图导入", error)
        }
    }
}

private suspend fun Call.awaitPublicResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
            else response.close()
        }
    })
}
