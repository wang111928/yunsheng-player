package com.litemusic.app.feature.update

import java.io.File
import java.util.UUID
import okhttp3.Call

/** The callback owns its unique files until the validated APK is delivered to the caller. */
internal suspend fun Call.downloadVerifiedUpdate(
    directory: File,
    versionCode: Long,
    expectedSize: Long?,
    digest: String?,
    onProgress: (Long, Long?) -> Unit,
    validateArchive: (File) -> Unit,
): File = consumeCancellable(onDiscard = { it.delete() }) { response, ensureActive ->
    ensureActive()
    check(response.isSuccessful) { "下载更新失败（HTTP ${response.code}）" }
    val body = requireNotNull(response.body) { "下载响应为空" }
    check(directory.isDirectory || directory.mkdirs()) { "无法创建更新目录" }
    val prefix = "update-vc$versionCode-${UUID.randomUUID()}"
    val partial = File(directory, "$prefix.apk.part")
    val apk = File(directory, "$prefix.apk")
    var completed = false
    try {
        val size = expectedSize ?: body.contentLength().takeIf { it >= 0L }
        body.byteStream().use { input ->
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    ensureActive()
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, size)
                }
            }
        }
        ensureActive()
        check(DownloadedApkIntegrity.isComplete(partial, size, digest)) { "下载文件不完整或校验失败" }
        ensureActive()
        check(partial.renameTo(apk)) { "保存更新文件失败" }
        validateArchive(apk)
        ensureActive()
        completed = true
        apk
    } finally {
        if (!completed) {
            partial.delete()
            apk.delete()
        }
    }
}
