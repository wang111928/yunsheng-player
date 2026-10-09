package com.litemusic.app.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubUpdatePolicyTest {
    @Test
    fun selectsNewerFullArm64ReleaseAsset() {
        val asset = GithubReleaseAsset(
            name = "Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk",
            size = 24_000_000,
            state = "uploaded",
            downloadUrl = "https://github.com/wang111928/yunsheng-player/releases/download/v0.2.39/Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk",
            digest = "sha256:abc123",
        )

        val result = GithubUpdatePolicy.selectUpdate(
            release = GithubRelease("v0.2.39", "0.2.39", "修复播放", false, false, listOf(asset)),
            installedVersionCode = 238,
        )

        assertEquals(239L, result?.versionCode)
        assertEquals("0.2.39", result?.versionName)
        assertEquals(asset.downloadUrl, result?.asset?.downloadUrl)
    }

    @Test
    fun rejectsDraftPreReleaseAndNonProductionAssets() {
        val valid = GithubReleaseAsset("Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk", 1, "uploaded", trustedUrl("v0.2.39", "Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk"), null)
        val invalidNames = listOf(
            "Yunsheng-min-v0.2.40-vc240-arm64-v8a.apk",
            "Yunsheng-full-v0.2.40-vc240-x86_64.apk",
            "Yunsheng-full-v0.2.40-vc240-arm64-v8a-debug.apk",
            "Yunsheng-full-v0.2.40-vc240-arm64-v8a-login-experiment.apk",
        ).map { GithubReleaseAsset(it, 1, "uploaded", trustedUrl("v0.2.40", it), null) }

        assertNull(GithubUpdatePolicy.selectUpdate(GithubRelease("v0.2.39", "", "", true, false, listOf(valid)), 238))
        assertNull(GithubUpdatePolicy.selectUpdate(GithubRelease("v0.2.39", "", "", false, true, listOf(valid)), 238))
        assertNull(GithubUpdatePolicy.selectUpdate(GithubRelease("v0.2.40", "", "", false, false, invalidNames), 238))
    }

    @Test
    fun preventsDowngradeEvenWhenTagLooksNewerLexically() {
        val asset = GithubReleaseAsset("Yunsheng-full-v0.10.0-vc237-arm64-v8a.apk", 1, "uploaded", trustedUrl("v0.10.0", "Yunsheng-full-v0.10.0-vc237-arm64-v8a.apk"), null)

        assertNull(GithubUpdatePolicy.selectUpdate(GithubRelease("v0.10.0", "", "", false, false, listOf(asset)), 238))
    }

    @Test
    fun reportsQualifiedReleaseVersionForLocalNewerMessage() {
        val asset = GithubReleaseAsset("Yunsheng-full-v0.2.29-vc229-arm64-v8a.apk", 1, "uploaded", trustedUrl("v0.2.29", "Yunsheng-full-v0.2.29-vc229-arm64-v8a.apk"), null)

        assertEquals(229L, GithubUpdatePolicy.qualifiedVersionCode(GithubRelease("v0.2.29", "", "", false, false, listOf(asset))))
    }

    @Test
    fun acceptsOnlyCanonicalGithubReleaseAssetUrls() {
        assertTrue(GithubUpdatePolicy.isTrustedAssetUrl(trustedUrl("v0.2.39", "Yunsheng-full-v0.2.39-vc239-arm64-v8a.apk")))
        assertFalse(GithubUpdatePolicy.isTrustedAssetUrl("http://github.com/wang111928/yunsheng-player/releases/download/v0.2.39/update.apk"))
        assertFalse(GithubUpdatePolicy.isTrustedAssetUrl("https://github.com/other/repo/releases/download/v0.2.39/update.apk"))
        assertFalse(GithubUpdatePolicy.isTrustedAssetUrl("https://example.com/update.apk"))
    }

    private fun trustedUrl(tag: String, name: String) =
        "https://github.com/wang111928/yunsheng-player/releases/download/$tag/$name"
}
