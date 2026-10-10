package com.litemusic.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.AuthRepository
import com.litemusic.app.feature.update.AvailableUpdate
import com.litemusic.app.feature.update.GithubUpdateRepository
import com.litemusic.app.feature.update.UpdateCheckResult
import com.litemusic.app.feature.update.UpdateDownloadCoordinator
import com.litemusic.data.auth.CredentialBackup
import com.litemusic.data.cache.ContentCache
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.shared.util.Quality
import com.litemusic.design.theme.canonicalNmlThemeKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File
import android.content.Context
import com.litemusic.player.AudioCacheUsage
import com.litemusic.player.OfflinePlaybackAvailability

class SettingsViewModel(
    private val settings: SettingsStore,
    private val cache: ContentCache,
    private val auth: AuthRepository,
    private val credentialBackup: CredentialBackup,
    private val updater: GithubUpdateRepository,
    private val downloads: UpdateDownloadCoordinator,
    private val context: Context,
) : ViewModel() {

    data class UiState(
        val loaded: Boolean = false,
        val quality: Quality = Quality.EXHIGH,
        val autoDegrade: Boolean = true,
        val theme: String = "light",
        val glassBlur: Boolean = true,
        val backgroundStrength: Int = 1,
        val lock60Hz: Boolean = false,
        val minLocalSec: Int = 30,
        val cacheUsed: Long = 0,
        val audioCache: AudioCacheUsage? = null,
        val songCheckin: Boolean = false,
        val toast: String? = null,
        val checkingUpdate: Boolean = false,
        val downloadingUpdate: Boolean = false,
        val update: AvailableUpdate? = null,
        val downloadedUpdate: File? = null,
        val downloadedBytes: Long = 0,
        val totalUpdateBytes: Long? = null,
        val updateError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    init {
        viewModelScope.launch {
            downloads.state.collect { download ->
                _state.update { old -> old.copy(
                    downloadingUpdate = download.downloading,
                    downloadedUpdate = download.downloadedFile,
                    downloadedBytes = download.downloadedBytes,
                    totalUpdateBytes = download.totalBytes,
                    update = download.update ?: old.update,
                    updateError = download.error,
                ) }
            }
        }
    }

    suspend fun load() {
        val quality = settings.quality.first()
        val autoDegrade = settings.autoDegrade.first()
        val theme = canonicalNmlThemeKind(settings.theme.first())
        val glassBlur = settings.glassBlur.first()
        val backgroundStrength = settings.backgroundStrength.first()
        val lock60Hz = settings.lock60Hz.first()
        val minLocalSec = settings.minLocalSec.first()
        val cacheUsed = cache.currentDiskSize()
        val audioCache = OfflinePlaybackAvailability.usage(context)
        val songCheckin = settings.songCheckin.first()
        _state.update { old -> old.copy(
            loaded = true,
            quality = quality,
            autoDegrade = autoDegrade,
            theme = theme,
            glassBlur = glassBlur,
            backgroundStrength = backgroundStrength,
            lock60Hz = lock60Hz,
            minLocalSec = minLocalSec,
            cacheUsed = cacheUsed,
            audioCache = audioCache,
            songCheckin = songCheckin,
        ) }
    }

    fun setQuality(q: Quality) = viewModelScope.launch {
        settings.setQuality(q)
        _state.update { it.copy(quality = q) }
    }

    fun setAutoDegrade(v: Boolean) = viewModelScope.launch {
        settings.setAutoDegrade(v)
        _state.update { it.copy(autoDegrade = v) }
    }

    fun setTheme(v: String) = viewModelScope.launch {
        val canonical = canonicalNmlThemeKind(v)
        settings.setTheme(canonical)
        _state.update { it.copy(theme = canonical) }
    }

    fun setGlassBlur(v: Boolean) = viewModelScope.launch {
        settings.setGlassBlur(v)
        _state.update { it.copy(glassBlur = v) }
    }

    fun setBackgroundStrength(v: Int) = viewModelScope.launch {
        settings.setBackgroundStrength(v)
        _state.update { it.copy(backgroundStrength = v.coerceIn(0, 2)) }
    }

    fun setLock60Hz(v: Boolean) = viewModelScope.launch {
        settings.setLock60Hz(v)
        _state.update { it.copy(lock60Hz = v) }
    }

    fun setMinLocalSec(v: Int) = viewModelScope.launch {
        settings.setMinLocalSec(v)
        _state.update { it.copy(minLocalSec = v) }
    }

    fun setSongCheckin(v: Boolean) = viewModelScope.launch {
        settings.setSongCheckin(v)
        _state.update { it.copy(songCheckin = v) }
    }

    fun clearCache() = viewModelScope.launch {
        cache.clear()
        val remaining = cache.currentDiskSize()
        _state.update { it.copy(cacheUsed = remaining, toast = if (remaining == 0L) "内容缓存已清理，离线歌单目录和音频已保留" else "部分缓存未能清理，请稍后重试") }
    }

    /** 凭证导出（AES-256-GCM 加密文件，FuoEvolve 优点） */
    fun exportCredential(file: File, passphrase: String) = viewModelScope.launch {
        val ok = credentialBackup.exportTo(file, passphrase)
        _state.update { it.copy(toast = if (ok) "凭证已导出到 " + file.absolutePath else "导出失败") }
    }

    fun importCredential(file: File, passphrase: String) = viewModelScope.launch {
        val session = credentialBackup.importFrom(file, passphrase)
        _state.update { it.copy(toast = if (session != null) "凭证恢复成功，欢迎回来 " + session.nickname else "恢复失败，口令可能错误") }
    }

    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        auth.logout()
        onDone()
    }

    fun checkForUpdate(currentVersionCode: Long) {
        if (_state.value.checkingUpdate || _state.value.downloadingUpdate) return
        viewModelScope.launch {
            _state.update { it.copy(checkingUpdate = true, update = null, updateError = null) }
            when (val result = updater.check(currentVersionCode)) {
                is UpdateCheckResult.Available -> {
                    val completed = downloads.state.value
                    val file = completed.downloadedFile?.takeIf { completed.update?.let { ready -> sameUpdateAsset(ready, result.update) } == true }
                    _state.update { it.copy(checkingUpdate = false, update = result.update, downloadedUpdate = file) }
                }
                UpdateCheckResult.UpToDate -> _state.update { it.copy(checkingUpdate = false, downloadedUpdate = null, toast = "已是最新版本" ) }
                UpdateCheckResult.LocalVersionNewer -> _state.update { it.copy(checkingUpdate = false, downloadedUpdate = null, toast = "当前本机版本高于 GitHub 已发布版本" ) }
                is UpdateCheckResult.Failed -> _state.update { it.copy(checkingUpdate = false, updateError = result.reason) }
            }
        }
    }

    fun downloadUpdate() {
        val update = _state.value.update ?: return
        downloads.start(update)
    }

    fun cancelUpdateDownload() = downloads.cancel()

    fun showToast(message: String) = _state.update { it.copy(toast = message) }

    fun discardMissingUpdate() = downloads.discardMissing()

    fun toastShown() = _state.update { it.copy(toast = null) }
}

/** A completed older APK must not be offered as the newly checked release. */
internal fun sameUpdateAsset(ready: AvailableUpdate, available: AvailableUpdate): Boolean =
    ready.versionCode == available.versionCode && ready.asset == available.asset
