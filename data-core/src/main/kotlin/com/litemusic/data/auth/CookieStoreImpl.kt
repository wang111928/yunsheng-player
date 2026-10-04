package com.litemusic.data.auth

import com.litemusic.data.prefs.AuthStore
import com.litemusic.network.CookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl
import kotlinx.coroutines.runBlocking

/**
 * DataStore 持久化的 Cookie 实现：
 * 从 AuthStore 会话中读取关键 Cookie（MUSIC_U / __csrf / NTES_YD_SESS），
 * WebView 登录成功后回写。
 */
class CookieStoreImpl(private val authStore: AuthStore) : CookieStore {

    // AuthRepository can replace the login independently of this CookieStore.
    // Reading DataStore here avoids sending a permanently cached previous account cookie.
    private fun current(): AuthStore.Session = runBlocking { authStore.current() }

    override fun saveCookies(url: HttpUrl, cookies: List<Cookie>) {
        val s = current()
        val m = s.cookieMap().toMutableMap()
        cookies.forEach { c ->
            when (c.name) {
                "MUSIC_U" -> m["MUSIC_U"] = c.value
                "__csrf" -> m["__csrf"] = c.value
                "NTES_YD_SESS" -> m["NTES_YD_SESS"] = c.value
            }
        }
        if (m != s.cookieMap()) {
            runBlocking {
                authStore.save(
                    s.copy(
                        musicU = m["MUSIC_U"] ?: s.musicU,
                        csrf = m["__csrf"] ?: s.csrf,
                        ntesSess = m["NTES_YD_SESS"] ?: s.ntesSess,
                    )
                )
            }
        }
    }

    override fun loadCookies(url: HttpUrl): List<Cookie> {
        val s = current()
        return s.cookieMap().map { (k, v) ->
            Cookie.Builder().name(k).value(v)
                .domain(url.host)
                .path("/")
                .httpOnly()
                .build()
        }
    }

    override fun all(): Map<String, String> = current().cookieMap()

    override fun clear() {
        runBlocking { authStore.clear() }
    }
}
