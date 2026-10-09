package com.litemusic.app.feature.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

sealed interface UpdateCheckResult {
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object LocalVersionNewer : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
}

class GithubUpdateRepository(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadMutex = Mutex()

    suspend fun check(installedVersionCode: Long): UpdateCheckResult {
        try {
            checkFromApi(installedVersionCode)?.let { return it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The same public release page remains usable when the API is unavailable.
        }
        return try {
            checkFromHtml(installedVersionCode)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            UpdateCheckResult.Failed("检查更新失败，请检查网络后重试")
        }
    }

    suspend fun download(update: AvailableUpdate, onProgress: (Long, Long?) -> Unit): Result<File> {
        if (!downloadMutex.tryLock()) return Result.failure(IllegalStateException("已有更新正在下载"))
        return try {
            require(GithubUpdatePolicy.isTrustedAssetUrl(update.asset.downloadUrl)) { "更新地址不可信" }
            val call = client.newCall(Request.Builder().url(update.asset.downloadUrl).header("User-Agent", USER_AGENT).build())
            val apk = call.downloadVerifiedUpdate(
                File(context.cacheDir, "updates"), update.versionCode, update.asset.size,
                update.asset.digest, onProgress,
            ) { checkArchive(it, update.versionCode) }
            Result.success(apk)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            downloadMutex.unlock()
        }
    }

    fun installerUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)

    private suspend fun checkFromApi(installedVersionCode: Long): UpdateCheckResult? =
        client.newCall(Request.Builder().url(API_URL)
            .header("Accept", "application/vnd.github+json").header("User-Agent", USER_AGENT).build()).consumeCancellable { it, ensureActive ->
            if (it.code == 403 || it.code == 429) return@consumeCancellable null
            check(it.isSuccessful) { "GitHub 返回 HTTP ${it.code}" }
            val root = json.parseToJsonElement(requireNotNull(it.body) { "GitHub 响应为空" }.string()).jsonObject
            ensureActive()
            val release = GithubRelease(
                tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                body = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                draft = root["draft"]?.jsonPrimitive?.booleanOrNull ?: false,
                prerelease = root["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
                assets = root["assets"]?.jsonArray.orEmpty().map { node ->
                    val asset = node.jsonObject
                    GithubReleaseAsset(
                        name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        size = asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        state = asset["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        digest = asset["digest"]?.jsonPrimitive?.contentOrNull,
                    )
                },
            )
            updateResult(release, installedVersionCode)
        }

    private suspend fun checkFromHtml(installedVersionCode: Long): UpdateCheckResult {
        val tag = client.newCall(Request.Builder().url(RELEASES_LATEST_URL).header("User-Agent", USER_AGENT).build()).consumeCancellable { response, ensureActive ->
            ensureActive()
            check(response.isSuccessful) { "GitHub 返回 HTTP ${response.code}" }
            GithubReleaseHtmlParser.tagFromReleaseUrl(response.request.url.toString()) ?: error("无法识别 GitHub 发布版本")
        }
        val html = client.newCall(Request.Builder().url("$RELEASES_EXPANDED_ASSETS_URL/$tag").header("User-Agent", USER_AGENT).build()).consumeCancellable { response, ensureActive ->
            check(response.isSuccessful) { "GitHub 返回 HTTP ${response.code}" }
            requireNotNull(response.body) { "GitHub 响应为空" }.string().also { ensureActive() }
        }
        val asset = GithubReleaseHtmlParser.parseAsset(html, tag)
        val release = GithubRelease(tag, tag.removePrefix("v"), "请查看 GitHub 发布说明", false, false, listOfNotNull(asset))
        return updateResult(release, installedVersionCode)
    }

    private fun checkArchive(file: File, expectedVersionCode: Long) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("下载文件不是可安装的 APK")
        check(archive.packageName == context.packageName) { "更新包与当前应用不匹配" }
        check(versionCode(archive) == expectedVersionCode && versionCode(archive) > versionCode(currentPackage())) { "更新版本无效或不是新版本" }
        check(signers(archive) == signers(currentPackage())) { "更新包签名与当前应用不一致" }
    }

    private fun updateResult(release: GithubRelease, installedVersionCode: Long): UpdateCheckResult =
        GithubUpdatePolicy.selectUpdate(release, installedVersionCode)?.let(UpdateCheckResult::Available)
            ?: if (GithubUpdatePolicy.qualifiedVersionCode(release) == null) {
                UpdateCheckResult.Failed("未找到适用于完整版 arm64 的正式安装包")
            } else if (GithubUpdatePolicy.qualifiedVersionCode(release)!! < installedVersionCode) {
                UpdateCheckResult.LocalVersionNewer
            } else UpdateCheckResult.UpToDate

    @Suppress("DEPRECATION") private fun currentPackage(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    @Suppress("DEPRECATION") private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    private fun signers(info: PackageInfo): Set<String> = requireNotNull(info.signingInfo) { "无法读取 APK 签名" }
        .apkContentsSigners.map { it.toCharsString() }.toSet()

    companion object {
        private const val USER_AGENT = "Yunsheng-Player-Updater"
        private const val API_URL = "https://api.github.com/repos/wang111928/yunsheng-player/releases/latest"
        private const val RELEASES_LATEST_URL = "https://github.com/wang111928/yunsheng-player/releases/latest"
        private const val RELEASES_EXPANDED_ASSETS_URL = "https://github.com/wang111928/yunsheng-player/releases/expanded_assets"
    }
}
