package com.litemusic.app.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubReleaseHtmlParserTest {
    @Test
    fun selectsHighestVersionCodeWhenAReleaseHasSeveralCompatibleAssets() {
        val html = listOf(238, 239).joinToString("\n") { code ->
            """<li><a href="/wang111928/yunsheng-player/releases/download/v0.2.39/Yunsheng-full-v0.2.39-vc$code-arm64-v8a.apk">APK</a></li>"""
        }
        assertEquals(239L, GithubReleaseHtmlParser.parseAsset(html, "v0.2.39")?.let(GithubUpdatePolicy::assetVersionCode))
    }

    @Test
    fun extractsOnlyApkAssetAndDigestFromExpandedAssetsMarkup() {
        val html = """
            <li class="Box-row"><a href="/wang111928/yunsheng-player/releases/download/v0.2.39/Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk"><span>Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk</span></a><clipboard-copy value="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></clipboard-copy></li>
            <li class="Box-row"><a href="/wang111928/yunsheng-player/archive/refs/tags/v0.2.39.zip">Source code</a></li>
        """.trimIndent()

        val asset = GithubReleaseHtmlParser.parseAsset(html, "v0.2.39")

        assertEquals("Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk", asset?.name)
        assertEquals("https://github.com/wang111928/yunsheng-player/releases/download/v0.2.39/Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk", asset?.downloadUrl)
        assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", asset?.digest)
        assertNull(asset?.size)
    }
}
