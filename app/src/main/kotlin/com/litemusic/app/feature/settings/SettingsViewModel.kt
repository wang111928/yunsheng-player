package com.litemusic.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.AuthRepository
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
import java.io.File

class SettingsViewModel(
    private val settings: SettingsStore,
    private val cache: ContentCache,
    private val auth: AuthRepository,
    private val credentialBackup: CredentialBackup,
) : ViewModel() {

    data class UiState(
        val loaded: Boolean = false,
        val quality: Quality = Quality.EXHIGH,
        val autoDegrade: Boolean = true,
        val theme: String = "light",
        val glassBlur: Boolean = true,
        val lock60Hz: Boolean = false,
        val minLocalSec: Int = 30,
        val cacheUsed: Long = 0,
        val songCheckin: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    suspend fun load() {
        _state.value = UiState(
            loaded = true,
            quality = settings.quality.first(),
            autoDegrade = settings.autoDegrade.first(),
            theme = canonicalNmlThemeKind(settings.theme.first()),
            glassBlur = settings.glassBlur.first(),
            lock60Hz = settings.lock60Hz.first(),
            minLocalSec = settings.minLocalSec.first(),
            cacheUsed = cache.currentDiskSize(),
            songCheckin = settings.songCheckin.first(),
        )
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
        _state.update { it.copy(cacheUsed = 0, toast = "缓存已清理") }
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

    fun toastShown() = _state.update { it.copy(toast = null) }
}
