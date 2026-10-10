package com.litemusic.app.feature.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateDownloadStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val update = AvailableUpdate(240, "0.2.40", "说明", GithubReleaseAsset(
        "Yunsheng-full-v0.2.40-vc240-arm64-v8a.apk", null, "uploaded",
        "https://github.com/wang111928/yunsheng-player/releases/download/v0.2.40/Yunsheng-full-v0.2.40-vc240-arm64-v8a.apk", null))

    @Test fun interruptedAndCompletedJobsSurviveStoreRecreation() {
        val directory = folder.newFolder()
        val metadata = File(directory, "task.json")
        UpdateDownloadStore(metadata).save(SavedUpdateDownload(update = update))
        assertEquals(update, UpdateDownloadStore(metadata).load()!!.update)
        val apk = File(directory, "update-vc240-aabb-ccdd.apk").apply { writeText("complete-test-file") }
        val record = SavedUpdateDownload(update = update, completedName = apk.name, completedSize = apk.length(), completedDigest = completedUpdateDigest(apk))
        UpdateDownloadStore(metadata).save(record)
        assertTrue(isRestoredUpdateComplete(UpdateDownloadStore(metadata).load()!!, directory))
        apk.appendText("corrupted")
        assertFalse(isRestoredUpdateComplete(record, directory))
    }

    @Test fun sameSizeCorruptionUntrustedAssetAndParentTraversalAreRejected() {
        val directory = folder.newFolder()
        val apk = File(directory, "update-vc240-aabb-ccdd.apk").apply { writeText("aaaa") }
        val record = SavedUpdateDownload(update = update, completedName = apk.name, completedSize = apk.length(), completedDigest = completedUpdateDigest(apk))
        apk.writeText("bbbb")
        assertFalse(isRestoredUpdateComplete(record, directory))
        assertFalse(isRestoredUpdateComplete(record.copy(completedName = "../${apk.name}"), directory))
        assertFalse(isSavedUpdateIdentityTrusted(record.copy(update = update.copy(asset = update.asset.copy(downloadUrl = "https://example.com/asset.apk")))))
        assertFalse(isSavedUpdateIdentityTrusted(record.copy(update = update.copy(versionCode = 241))))
    }
}
