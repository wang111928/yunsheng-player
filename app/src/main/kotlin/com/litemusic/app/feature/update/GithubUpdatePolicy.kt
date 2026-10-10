package com.litemusic.app.feature.update

import java.net.URI
import kotlinx.serialization.Serializable

data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GithubReleaseAsset>,
)

@Serializable
data class GithubReleaseAsset(
    val name: String,
    val size: Long?,
    val state: String,
    val downloadUrl: String,
    val digest: String?,
)

@Serializable
data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val asset: GithubReleaseAsset,
)

object GithubUpdatePolicy {
    private const val owner = "wang111928"
    private const val repository = "yunsheng-player"
    private val assetName = Regex("^Yunsheng-full-v(\\d+(?:\\.\\d+){1,3})-vc(\\d+)-arm64-v8a\\.apk$")

    fun selectUpdate(release: GithubRelease, installedVersionCode: Long): AvailableUpdate? {
        if (release.draft || release.prerelease) return null
        val asset = release.assets.mapNotNull { asset ->
            val versionCode = assetVersionCode(asset) ?: return@mapNotNull null
            if (versionCode > installedVersionCode && asset.state == "uploaded" && isTrustedAssetUrl(asset.downloadUrl, release.tagName, asset.name)) {
                asset to versionCode
            } else null
        }.maxByOrNull { it.second } ?: return null
        return AvailableUpdate(
            versionCode = asset.second,
            versionName = assetVersionName(asset.first) ?: release.tagName.removePrefix("v"),
            releaseNotes = release.body,
            asset = asset.first,
        )
    }

    fun assetVersionCode(asset: GithubReleaseAsset): Long? =
        assetName.matchEntire(asset.name)?.groupValues?.get(2)?.toLongOrNull()

    fun qualifiedVersionCode(release: GithubRelease): Long? =
        if (release.draft || release.prerelease) null else release.assets
            .filter { it.state == "uploaded" && isTrustedAssetUrl(it.downloadUrl, release.tagName, it.name) }
            .mapNotNull(::assetVersionCode)
            .maxOrNull()

    private fun assetVersionName(asset: GithubReleaseAsset): String? =
        assetName.matchEntire(asset.name)?.groupValues?.get(1)

    fun isTrustedAssetUrl(value: String, tag: String? = null, name: String? = null): Boolean = runCatching {
        val uri = URI(value)
        val path = uri.path
        uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            path.startsWith("/$owner/$repository/releases/download/") &&
            !path.contains("..") &&
            (tag == null || name == null || path == "/$owner/$repository/releases/download/$tag/$name") &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
}
