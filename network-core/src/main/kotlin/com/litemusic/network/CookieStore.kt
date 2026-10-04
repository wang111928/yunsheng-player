package com.litemusic.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookie 存储抽象：由 data-core 提供 DataStore 持久化实现。
 * 登录成功后写入 MUSIC_U / __csrf / NTES_YD_SESS，长期有效。
 */
interface CookieStore {
    fun saveCookies(url: HttpUrl, cookies: List<Cookie>)
    fun loadCookies(url: HttpUrl): List<Cookie>
    fun all(): Map<String, String>
    fun clear()
}

class PersistentCookieJar(private val store: CookieStore) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = store.saveCookies(url, cookies)
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.loadCookies(url)
}
