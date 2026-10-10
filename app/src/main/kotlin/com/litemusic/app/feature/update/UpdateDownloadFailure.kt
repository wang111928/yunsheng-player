package com.litemusic.app.feature.update

import java.io.IOException

/** A partial file is reusable only when the immutable asset URL and server identity agree. */
internal fun canResumeUpdateDownload(
    savedUrl: String,
    currentUrl: String,
    savedEtag: String?,
    savedLastModified: String?,
): Boolean = savedUrl == currentUrl && (!savedEtag.isNullOrBlank() || !savedLastModified.isNullOrBlank())

/** Keep raw transport exceptions out of the settings UI while retaining actionable causes. */
internal fun updateDownloadFailureMessage(error: Throwable): String = when {
    error.message?.contains("空间") == true || error.message?.contains("ENOSPC", ignoreCase = true) == true || error.message?.contains("No space", ignoreCase = true) == true ->
        "存储空间不足或文件无法保存，请清理空间后重试"
    Regex("HTTP\\s+(\\d{3})").find(error.message.orEmpty())?.let { it.groupValues[1] } != null ->
        "更新下载失败（HTTP ${Regex("HTTP\\s+(\\d{3})").find(error.message.orEmpty())!!.groupValues[1]}）"
    error is IOException -> "更新下载网络中断，请检查网络后重试"
    error.message?.contains("无法创建") == true || error.message?.contains("保存") == true ->
        "存储空间不足或文件无法保存，请清理空间后重试"
    error.message?.contains("校验") == true || error.message?.contains("不完整") == true -> "更新文件校验失败，请重新下载"
    error.message?.contains("签名") == true -> "更新包签名与当前应用不一致"
    error.message?.contains("不匹配") == true || error.message?.contains("版本") == true -> "更新包版本或应用身份不匹配"
    else -> "更新下载失败，请稍后重试"
}
