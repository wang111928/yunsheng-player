package com.litemusic.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.litemusic.shared.util.Quality
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
private val Context.authDataStore by preferencesDataStore(name = "auth")

/** 应用设置（DataStore） */
class SettingsStore(private val context: Context) {
    private object Keys {
        val QUALITY = stringPreferencesKey("default_quality")
        val AUTO_DEGRADE = booleanPreferencesKey("auto_degrade")
        val THEME = stringPreferencesKey("theme")
        val GLASS_BLUR = booleanPreferencesKey("glass_blur")
        val BACKGROUND_STRENGTH = intPreferencesKey("background_strength")
        val LOCK_60HZ = booleanPreferencesKey("lock_60hz")
        val MIN_LOCAL_SEC = intPreferencesKey("min_local_sec")
        val CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
        val LAST_SIGNIN = stringPreferencesKey("last_signin_date")
        val SIGNIN_STREAK = intPreferencesKey("signin_streak")
        val TOTAL_SIGNIN = intPreferencesKey("total_signin")
        val SONG_CHECKIN = booleanPreferencesKey("song_checkin")
        val VIP = booleanPreferencesKey("is_vip")
        val BLOCKED_REGEX = stringSetPreferencesKey("blocked_regex")
        val LOCK_LYRIC = booleanPreferencesKey("lock_screen_lyric")
        val VIP_DAILY_REFRESHES = stringSetPreferencesKey("vip_daily_refreshes")
    }

    val quality: Flow<Quality> = context.settingsDataStore.data.map {
        Quality.entries.firstOrNull { q -> q.name == it[Keys.QUALITY] } ?: Quality.EXHIGH
    }
    val autoDegrade: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.AUTO_DEGRADE] ?: true }
    val theme: Flow<String> = context.settingsDataStore.data.map {
        (it[Keys.THEME] ?: "light").also { theme ->
            // Migrate installations created before the startup mirror existed. The next cold
            // start can then render the selected skin before asynchronous preferences arrive.
            StartupThemePreferences.mirror(context, theme)
        }
    }
    val glassBlur: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.GLASS_BLUR] ?: true }
    val backgroundStrength: Flow<Int> = context.settingsDataStore.data.map { (it[Keys.BACKGROUND_STRENGTH] ?: 1).coerceIn(0, 2) }
    val lock60Hz: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.LOCK_60HZ] ?: false }
    val minLocalSec: Flow<Int> = context.settingsDataStore.data.map { it[Keys.MIN_LOCAL_SEC] ?: 30 }
    val cacheLimitMb: Flow<Int> = context.settingsDataStore.data.map { it[Keys.CACHE_LIMIT_MB] ?: 256 }
    val isVip: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.VIP] ?: false }

    val lastSigninDate: Flow<String?> = context.settingsDataStore.data.map { it[Keys.LAST_SIGNIN] }
    val signinStreak: Flow<Int> = context.settingsDataStore.data.map { it[Keys.SIGNIN_STREAK] ?: 0 }
    val totalSignin: Flow<Int> = context.settingsDataStore.data.map { it[Keys.TOTAL_SIGNIN] ?: 0 }
    val songCheckin: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.SONG_CHECKIN] ?: false }
    val blockedRegex: Flow<Set<String>> = context.settingsDataStore.data.map { it[Keys.BLOCKED_REGEX] ?: emptySet() }
    /** 自绘锁屏歌词（息屏显示歌词页） */
    val lockScreenLyric: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.LOCK_LYRIC] ?: true }

    suspend fun setQuality(q: Quality) = context.settingsDataStore.edit { it[Keys.QUALITY] = q.name }
    suspend fun setAutoDegrade(v: Boolean) = context.settingsDataStore.edit { it[Keys.AUTO_DEGRADE] = v }
    suspend fun setTheme(v: String) {
        context.settingsDataStore.edit { it[Keys.THEME] = v }
        StartupThemePreferences.mirror(context, v)
    }
    suspend fun setGlassBlur(v: Boolean) = context.settingsDataStore.edit { it[Keys.GLASS_BLUR] = v }
    suspend fun setBackgroundStrength(v: Int) = context.settingsDataStore.edit { it[Keys.BACKGROUND_STRENGTH] = v.coerceIn(0, 2) }
    suspend fun setLock60Hz(v: Boolean) = context.settingsDataStore.edit { it[Keys.LOCK_60HZ] = v }
    suspend fun setMinLocalSec(v: Int) = context.settingsDataStore.edit { it[Keys.MIN_LOCAL_SEC] = v }
    suspend fun setCacheLimitMb(v: Int) = context.settingsDataStore.edit { it[Keys.CACHE_LIMIT_MB] = v }
    suspend fun setIsVip(v: Boolean) = context.settingsDataStore.edit { it[Keys.VIP] = v }

    suspend fun recordSignin(date: String, points: Int) = context.settingsDataStore.edit {
        val last = it[Keys.LAST_SIGNIN]
        val streak = it[Keys.SIGNIN_STREAK] ?: 0
        val consecutive = last != null && isYesterday(last, date)
        it[Keys.LAST_SIGNIN] = date
        it[Keys.SIGNIN_STREAK] = if (last == date) streak else if (consecutive) streak + 1 else 1
        it[Keys.TOTAL_SIGNIN] = (it[Keys.TOTAL_SIGNIN] ?: 0) + 1
    }

    suspend fun setSongCheckin(v: Boolean) = context.settingsDataStore.edit { it[Keys.SONG_CHECKIN] = v }

    suspend fun setBlockedRegex(values: Set<String>) = context.settingsDataStore.edit { it[Keys.BLOCKED_REGEX] = values }
    suspend fun setLockScreenLyric(v: Boolean) = context.settingsDataStore.edit { it[Keys.LOCK_LYRIC] = v }

    /** A daily entitlement marker must survive the app's ordinary content-cache clear. */
    suspend fun hasUsedVipDailyRefresh(userId: Long, date: String): Boolean =
        "$userId:$date" in (context.settingsDataStore.data.first()[Keys.VIP_DAILY_REFRESHES] ?: emptySet())

    suspend fun markVipDailyRefresh(userId: Long, date: String) = context.settingsDataStore.edit { prefs ->
        val marker = "$userId:$date"
        // Retain a bounded recent history in case the user switches between accounts.
        prefs[Keys.VIP_DAILY_REFRESHES] = (prefs[Keys.VIP_DAILY_REFRESHES].orEmpty()
            .filter { it.substringAfter(':') >= date.take(7) }
            .toSet() + marker)
    }

    private fun isYesterday(last: String, today: String): Boolean {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val d1 = fmt.parse(last)
            val d2 = fmt.parse(today)
            (d2.time - d1.time) == 24 * 3600 * 1000L
        } catch (e: Exception) {
            false
        }
    }

}

/** 登录会话（DataStore 明文存储 + EncryptedSharedPreferences 兜底见 CredentialBackup） */
class AuthStore(private val context: Context) {
    private object Keys {
        val COOKIE_MUSIC_U = stringPreferencesKey("cookie_music_u")
        val COOKIE_CSRF = stringPreferencesKey("cookie_csrf")
        val COOKIE_SESS = stringPreferencesKey("cookie_ntes_sess")
        val USER_ID = longPreferencesKey("user_id")
        val NICKNAME = stringPreferencesKey("nickname")
        val AVATAR = stringPreferencesKey("avatar")
        val VIP = booleanPreferencesKey("vip")
        val VIP_LEVEL = intPreferencesKey("vip_level")
        val LEVEL = intPreferencesKey("level")
        val LOGIN_AT = longPreferencesKey("login_at")
    }

    @Serializable
    data class Session(
        val musicU: String = "",
        val csrf: String = "",
        val ntesSess: String = "",
        val userId: Long = 0,
        val nickname: String = "",
        val avatar: String = "",
        val vip: Boolean = false,
        val vipLevel: Int = 0,
        val level: Int = 0,
        val loginAt: Long = 0,
    ) {
        val loggedIn: Boolean get() = musicU.isNotBlank() || ntesSess.isNotBlank()
        /** Profile changes are not a new login. Keep requests tied to the original login. */
        fun sameLoginAs(other: Session): Boolean =
            loginAt == other.loginAt && musicU == other.musicU && ntesSess == other.ntesSess
        fun cookieMap(): Map<String, String> = buildMap {
            if (musicU.isNotBlank()) put("MUSIC_U", musicU)
            if (csrf.isNotBlank()) put("__csrf", csrf)
            if (ntesSess.isNotBlank()) put("NTES_YD_SESS", ntesSess)
        }
    }

    val session: Flow<Session> = context.authDataStore.data.map { p ->
        Session(
            musicU = p[Keys.COOKIE_MUSIC_U] ?: "",
            csrf = cleanCsrf(p[Keys.COOKIE_CSRF] ?: ""),
            ntesSess = p[Keys.COOKIE_SESS] ?: "",
            userId = p[Keys.USER_ID] ?: 0,
            nickname = p[Keys.NICKNAME] ?: "",
            avatar = p[Keys.AVATAR] ?: "",
            vip = p[Keys.VIP] ?: false,
            vipLevel = p[Keys.VIP_LEVEL] ?: 0,
            level = p[Keys.LEVEL] ?: 0,
            loginAt = p[Keys.LOGIN_AT] ?: 0,
        )
    }

    suspend fun current(): Session = session.first()

    /** __csrf 必须是 32 位十六进制串；历史版本可能写入带前缀的脏值，读取时归一化 */
    private fun cleanCsrf(raw: String): String =
        Regex("[0-9a-fA-F]{32}").find(raw)?.value ?: raw.trim()

    suspend fun save(s: Session, isCurrent: () -> Boolean = { true }): Boolean {
        var applied = false
        context.authDataStore.edit {
        if (!isCurrent()) return@edit
        it[Keys.COOKIE_MUSIC_U] = s.musicU
        it[Keys.COOKIE_CSRF] = s.csrf
        it[Keys.COOKIE_SESS] = s.ntesSess
        it[Keys.USER_ID] = s.userId
        it[Keys.NICKNAME] = s.nickname
        it[Keys.AVATAR] = s.avatar
        it[Keys.VIP] = s.vip
        it[Keys.VIP_LEVEL] = s.vipLevel
        it[Keys.LEVEL] = s.level
        it[Keys.LOGIN_AT] = System.currentTimeMillis()
        applied = true
        }
        return applied
    }

    /** Update profile fields atomically without renewing the login or replacing newer cookies. */
    suspend fun updateProfile(expected: Session, updated: Session): Boolean {
        var applied = false
        context.authDataStore.edit { p ->
            if ((p[Keys.LOGIN_AT] ?: 0L) != expected.loginAt ||
                (p[Keys.COOKIE_MUSIC_U] ?: "") != expected.musicU ||
                (p[Keys.COOKIE_SESS] ?: "") != expected.ntesSess ||
                (p[Keys.USER_ID] ?: 0L) != expected.userId
            ) return@edit
            p[Keys.USER_ID] = updated.userId
            p[Keys.NICKNAME] = updated.nickname
            p[Keys.AVATAR] = updated.avatar
            p[Keys.VIP] = updated.vip
            p[Keys.VIP_LEVEL] = updated.vipLevel
            p[Keys.LEVEL] = updated.level
            applied = true
        }
        return applied
    }

    suspend fun clear() = context.authDataStore.edit { it.clear() }
}
