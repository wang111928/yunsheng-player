package com.litemusic.app.data

import android.webkit.CookieManager
import com.litemusic.data.prefs.AuthStore
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * 登录态管理：Cookie 持久化 + 会员状态 + 账号信息刷新。
 * 过期前 301 天提示重新登录（music_wyy 优点）。
 */
class AuthRepository(
    private val api: NMApi,
    private val authStore: AuthStore,
    private val settings: SettingsStore,
    private val clearWebCookies: suspend () -> Unit = { AndroidWebLoginCookieClearer.clear() },
) {
    private val loginCommitMutex = Mutex()
    val session: Flow<AuthStore.Session> = authStore.session
    val isVip: Flow<Boolean> = settings.isVip
    suspend fun currentSession(): AuthStore.Session = authStore.current()

    suspend fun membership(uid: Long): AppResult<String> = when (val r = api.vipInfo(uid)) {
        is AppResult.Failure -> r
        is AppResult.Success -> membershipDescription(r.data, uid, System.currentTimeMillis())
            ?.let { AppResult.Success(it) } ?: AppResult.Failure(-1, "会员资料暂未确认")
    }

    suspend fun refreshProfile(): AppResult<AuthStore.Session> {
        val current = authStore.current()
        if (!current.loggedIn) return AppResult.Failure(-1, "未登录")
        val acc = api.getAccount()
        return when (acc) {
            is AppResult.Success -> {
                if (acc.data.code != 200) return AppResult.Failure(acc.data.code, "账号资料读取失败(${acc.data.code})")
                val a = acc.data.account
                val p = acc.data.profile
                if (a == null || a.id <= 0L || p == null ||
                    (current.userId > 0L && current.userId != a.id)
                ) return AppResult.Failure(-1, "账号资料与当前登录不匹配，请重新登录")
                val updated = current.copy(
                    userId = a?.id ?: current.userId,
                    nickname = p?.nickname ?: current.nickname,
                    avatar = p?.avatarUrl ?: current.avatar,
                    vip = (a?.vipType ?: 0) > 0 || (a?.vipLevel ?: 0) > 0,
                    vipLevel = a?.vipLevel?.takeIf { it > 0 } ?: 0,
                )
                if (!authStore.updateProfile(current, updated)) {
                    return AppResult.Failure(-1, "登录状态已变化，请重新刷新")
                }
                settings.setIsVip(updated.vip)
                AppResult.Success(updated)
            }
            is AppResult.Failure -> acc
        }
    }

    suspend fun applyCookies(cookies: Map<String, String>) {
        val cur = authStore.current()
        val replacement = sessionWithCookies(cur, cookies)
        authStore.save(replacement)
        if (replacement.userId == 0L) settings.setIsVip(false)
    }

    /** Verify with request-local credentials; publish cookies and profile in one store edit. */
    suspend fun completeLogin(cookies: Map<String, String>, isCurrent: () -> Boolean = { true }): AppResult<AuthStore.Session> {
        if (!isCurrent()) return AppResult.Failure(-1, "登录操作已取消")
        val credentials = mapOf(
            "MUSIC_U" to cookies["MUSIC_U"].orEmpty(),
            "NTES_YD_SESS" to cookies["NTES_YD_SESS"].orEmpty(),
            "__csrf" to cookies["__csrf"].orEmpty(),
        )
        val result = api.getAccount(credentials)
        if (result is AppResult.Failure) return result
        val account = (result as AppResult.Success).data
        val a = account.account
        val p = account.profile
        if (account.code != 200 || a == null || a.id <= 0L || p == null || p.userId != a.id) {
            return AppResult.Failure(account.code, "登录凭据尚未确认或账号资料读取失败，请重试")
        }
        val session = AuthStore.Session(
            musicU = credentials.getValue("MUSIC_U"), ntesSess = credentials.getValue("NTES_YD_SESS"),
            csrf = credentials.getValue("__csrf"), userId = a.id, nickname = p.nickname, avatar = p.avatarUrl,
            vip = a.vipType > 0 || a.vipLevel > 0, vipLevel = a.vipLevel,
        )
        return loginCommitMutex.withLock {
            // Navigation can dispose the login screen as soon as authStore emits. Finish the
            // matching membership preference after that commit, even if its caller is disposed.
            withContext(NonCancellable) {
                if (!authStore.save(session, isCurrent)) AppResult.Failure(-1, "登录操作已取消")
                else {
                    settings.setIsVip(session.vip)
                    AppResult.Success(session)
                }
            }
        }
    }

    /**
     * 确保本地已登录且拿得到 uid。
     * 历史版本（扫码登录）成功后 uid 未落库 → 本地为 0，导致「我的歌单/喜欢的音乐」全部拿不到数据。
     * 这里在本地 uid 缺失时自动补拉一次账号信息并落库。
     */
    suspend fun ensureUserId(): Long {
        val s = authStore.current()
        if (s.userId != 0L) return s.userId
        if (!s.loggedIn) return 0L
        val r = refreshProfile()
        return if (r is AppResult.Success) r.data.userId else 0L
    }

    suspend fun logout() {
        loginCommitMutex.withLock {
            withContext(NonCancellable) {
                clearLogoutStateInOrder(
                    clearWebCookies = clearWebCookies,
                    clearLocalAuth = authStore::clear,
                    clearVip = { settings.setIsVip(false) },
                )
            }
        }
    }
}

/** Clears the WebView process cookie jar before local auth emits a logged-out session. */
internal object AndroidWebLoginCookieClearer {
    suspend fun clear() = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies {
                cookies.flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

/** Ordering is intentionally shared by production logout and JVM tests. */
internal suspend fun clearLogoutStateInOrder(
    clearWebCookies: suspend () -> Unit,
    clearLocalAuth: suspend () -> Unit,
    clearVip: suspend () -> Unit,
) {
    clearWebCookies()
    clearLocalAuth()
    clearVip()
}

/** New authentication credentials cannot inherit another account's profile. */
internal fun sessionWithCookies(current: AuthStore.Session, cookies: Map<String, String>): AuthStore.Session {
    val musicU = cookies["MUSIC_U"] ?: current.musicU
    val ntes = cookies["NTES_YD_SESS"] ?: current.ntesSess
    val changed = musicU != current.musicU || ntes != current.ntesSess
    return if (changed) AuthStore.Session(musicU = cookies["MUSIC_U"].orEmpty(), ntesSess = cookies["NTES_YD_SESS"].orEmpty(),
        csrf = cookies["__csrf"].orEmpty())
    else current.copy(csrf = cookies["__csrf"] ?: current.csrf)
}
