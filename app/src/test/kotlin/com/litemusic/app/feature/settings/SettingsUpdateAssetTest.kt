package com.litemusic.app.feature.settings

import com.litemusic.app.feature.update.AvailableUpdate
import com.litemusic.app.feature.update.GithubReleaseAsset
import org.junit.Assert.*
import org.junit.Test

class SettingsUpdateAssetTest {
    private val ready = AvailableUpdate(240, "0.2.40", "旧说明", GithubReleaseAsset(
        "Yunsheng-full-v0.2.40-vc240-arm64-v8a.apk", 100, "uploaded", "https://github.com/example/240.apk", "sha256:a"))
    @Test fun releaseNotesChangesDoNotInvalidateTheSameCompletedFile() {
        assertTrue(sameUpdateAsset(ready, ready.copy(releaseNotes = "新说明")))
    }
    @Test fun aNewVersionOrChangedAssetCannotReuseTheOlderCompletedFile() {
        assertFalse(sameUpdateAsset(ready, ready.copy(versionCode = 241)))
        assertFalse(sameUpdateAsset(ready, ready.copy(asset = ready.asset.copy(downloadUrl = "https://github.com/example/241.apk"))))
        assertFalse(sameUpdateAsset(ready, ready.copy(asset = ready.asset.copy(digest = "sha256:b"))))
    }
}
