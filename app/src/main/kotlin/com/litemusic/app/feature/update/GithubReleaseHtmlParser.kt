package com.litemusic.app.feature.update

object GithubReleaseHtmlParser {
    private val row = Regex("(?is)<li\\b[^>]*>.*?</li>")
    private val assetHref = Regex("""href=[\"'](/wang111928/yunsheng-player/releases/download/[^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val digest = Regex("""(?:value|class=[\"']Truncate-text[\"'][^>]*>)=[\"']?(sha256:[0-9a-f]{64})|>(sha256:[0-9a-f]{64})<""", RegexOption.IGNORE_CASE)

    fun parseAsset(html: String, tag: String): GithubReleaseAsset? {
        val expectedPrefix = "/wang111928/yunsheng-player/releases/download/$tag/"
        return row.findAll(html).mapNotNull { match ->
            val content = match.value
            val relativeUrl = assetHref.find(content)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!relativeUrl.startsWith(expectedPrefix)) return@mapNotNull null
            val name = relativeUrl.substringAfterLast('/').replace("&amp;", "&")
            val asset = GithubReleaseAsset(
                name = name,
                size = null,
                state = "uploaded",
                downloadUrl = "https://github.com$relativeUrl",
                digest = digest.find(content)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() },
            )
            if (GithubUpdatePolicy.assetVersionCode(asset) != null && GithubUpdatePolicy.isTrustedAssetUrl(asset.downloadUrl)) asset else null
        }.maxByOrNull { GithubUpdatePolicy.assetVersionCode(it)!! }
    }

    fun tagFromReleaseUrl(url: String): String? = runCatching {
        val prefix = "https://github.com/wang111928/yunsheng-player/releases/tag/"
        if (!url.startsWith(prefix)) null else url.removePrefix(prefix).takeIf { it.matches(Regex("v[0-9A-Za-z._-]+")) }
    }.getOrNull()
}
