package com.litemusic.app.feature.update

import com.litemusic.app.util.writeAtomicText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
internal data class SavedUpdateDownload(
    val schema: Int = 1,
    val update: AvailableUpdate,
    val completedName: String? = null,
    val completedSize: Long? = null,
    val completedDigest: String? = null,
)

internal class UpdateDownloadStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Synchronized fun save(value: SavedUpdateDownload) = writeAtomicText(file, json.encodeToString(value))
    @Synchronized fun load(): SavedUpdateDownload? {
        if (!file.isFile || file.length() > 2L * 1024L * 1024L) return null
        return runCatching { json.decodeFromString<SavedUpdateDownload>(file.readText()).takeIf { it.schema == 1 } }.getOrNull()
    }
    @Synchronized fun clear() { file.delete() }
}

internal fun completedUpdateDigest(file: File): String {
    val sha = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sha.update(buffer, 0, read)
        }
    }
    return "sha256:" + sha.digest().joinToString("") { "%02x".format(it) }
}

internal fun isSavedUpdateIdentityTrusted(record: SavedUpdateDownload): Boolean {
    val update = record.update
    return GithubUpdatePolicy.assetVersionCode(update.asset) == update.versionCode &&
        GithubUpdatePolicy.isTrustedAssetUrl(update.asset.downloadUrl, "v${update.versionName}", update.asset.name)
}

internal fun isRestoredUpdateComplete(record: SavedUpdateDownload, directory: File): Boolean {
    val name = record.completedName ?: return false
    if (name != File(name).name || !name.matches(Regex("update-vc[0-9]+-[a-fA-F0-9-]+\\.apk"))) return false
    if (record.completedSize == null || record.completedDigest == null || !isSavedUpdateIdentityTrusted(record)) return false
    val file = File(directory, name)
    return DownloadedApkIntegrity.isComplete(file, record.completedSize, record.completedDigest) &&
        DownloadedApkIntegrity.isComplete(file, record.update.asset.size, record.update.asset.digest)
}
