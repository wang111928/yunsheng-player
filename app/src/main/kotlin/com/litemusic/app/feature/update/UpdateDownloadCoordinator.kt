package com.litemusic.app.feature.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** App-scope owner; each generation exclusively owns progress, completion and cancellation. */
data class UpdateDownloadState(
    val restoring: Boolean = true,
    val downloading: Boolean = false,
    val update: AvailableUpdate? = null,
    val downloadedFile: File? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
)

class UpdateDownloadCoordinator(context: Context, private val updater: GithubUpdateRepository, private val scope: CoroutineScope) {
    private val directory = File(context.cacheDir, "updates")
    private val store = UpdateDownloadStore(File(context.filesDir, "update-download-v2.json"))
    private val _state = MutableStateFlow(UpdateDownloadState())
    val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private val installedVersionCode = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    init { scope.launch { restore() } }

    private suspend fun restore() {
        val owner = 0L
        // A killed process cannot run the HTTP callback's cleanup. Remove only its private
        // update fragments before a new generation starts; audio caches are unrelated.
        synchronized(this) {
            if (generation == owner && job?.isActive != true) directory.listFiles()?.forEach { file ->
                if (file.isFile && file.name.matches(Regex("update-vc[0-9]+-[a-fA-F0-9-]+\\.apk\\.part"))) file.delete()
            }
        }
        val record = store.load()
        val restored = when {
            record == null || !isSavedUpdateIdentityTrusted(record) -> UpdateDownloadState(restoring = false)
            record.update.versionCode <= installedVersionCode -> {
                synchronized(this) { if (generation == owner) store.clear() }
                UpdateDownloadState(restoring = false)
            }
            record.completedName == null -> UpdateDownloadState(restoring = false, update = record.update,
                error = "上次更新下载已中断，可点击下载更新重新开始")
            runCatching { isRestoredUpdateComplete(record, directory) }.getOrDefault(false) && updater.validateDownloadedUpdate(File(directory, record.completedName), record.update.versionCode) ->
                UpdateDownloadState(restoring = false, update = record.update, downloadedFile = File(directory, record.completedName),
                    downloadedBytes = record.completedSize ?: 0, totalBytes = record.completedSize)
            else -> UpdateDownloadState(restoring = false, error = "此前更新文件已失效，请重新检查最新版")
        }
        publish(owner, restored)
    }

    @Synchronized fun start(update: AvailableUpdate) {
        if (job?.isActive == true) return
        val owner = ++generation
        _state.value = UpdateDownloadState(restoring = false, downloading = true, update = update, totalBytes = update.asset.size)
        job = scope.launch {
            try {
                check(isSavedUpdateIdentityTrusted(SavedUpdateDownload(update = update))) { "更新地址或版本不匹配" }
                persistIfOwned(owner, SavedUpdateDownload(update = update))
                val file = updater.download(update) { bytes, total ->
                    synchronized(this@UpdateDownloadCoordinator) {
                        if (generation == owner) _state.value = _state.value.copy(downloadedBytes = bytes, totalBytes = total)
                    }
                }.getOrThrow()
                val complete = SavedUpdateDownload(update = update, completedName = file.name,
                    completedSize = file.length(), completedDigest = completedUpdateDigest(file))
                if (persistIfOwned(owner, complete)) publish(owner, UpdateDownloadState(restoring = false, update = update,
                    downloadedFile = file, downloadedBytes = file.length(), totalBytes = file.length()))
                else file.delete()
            } catch (_: CancellationException) {
                // cancel() already published the new generation. An old response cannot overwrite it.
            } catch (error: Exception) {
                synchronized(this@UpdateDownloadCoordinator) {
                    if (generation == owner) _state.value = _state.value.copy(downloading = false, restoring = false, error = updateDownloadFailureMessage(error))
                }
            }
        }
    }
    @Synchronized fun cancel() {
        ++generation
        job?.cancel()
        _state.value = _state.value.copy(downloading = false, restoring = false, error = null)
        // Keep the cancelling job as owner until its HTTP call releases the repository mutex.
    }
    @Synchronized fun discardMissing() {
        val owner = ++generation
        job?.cancel()
        scope.launch { synchronized(this@UpdateDownloadCoordinator) { if (generation == owner) store.clear() } }
        _state.value = UpdateDownloadState(restoring = false, error = "更新文件已失效，请重新下载")
    }
    @Synchronized private fun publish(owner: Long, value: UpdateDownloadState) {
        if (generation == owner) _state.value = value
    }
    @Synchronized private fun persistIfOwned(owner: Long, value: SavedUpdateDownload): Boolean {
        if (generation != owner) return false
        store.save(value)
        return true
    }
}
