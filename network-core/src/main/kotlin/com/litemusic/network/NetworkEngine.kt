package com.litemusic.network

import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** OkHttp 统一配置：连接池 8、超时、Cookie、日志 */
class NetworkEngine(
    private val cookieJar: PersistentCookieJar,
    private val debug: Boolean = false,
) {
    private fun newBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 8 })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (debug) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }

    /** 通用客户端（带 cookieJar，供图片/媒体/WebView 复用） */
    fun okHttpClient(): OkHttpClient = newBuilder().cookieJar(cookieJar).build()

    /**
     * ApiClient(Ktor) 专用：**不挂 cookieJar**。
     *
     * OkHttp 的 BridgeInterceptor 会用 cookieJar.loadForRequest() 的结果**无条件覆盖**
     * 请求里显式设置的 Cookie 头。而 ApiClient 的 eapi 请求把设备指纹
     * （os/appver/deviceId/channel/requestId/...）放在 Cookie 里，一旦被 jar 覆盖成
     * 仅 MUSIC_U/__csrf/NTES_YD_SESS 三个，网易服务端就把请求判定为 Web 客户端，
     * 下发带 authSecret 的播放地址 → CDN 一律 403。
     * 去掉 jar 后显式 Cookie 得以保留（登录 Cookie 由 AuthRepository 单独持久化，不依赖 jar）。
     */
    fun okHttpClientNoCookies(): OkHttpClient = newBuilder().build()

    fun ktorEngine(client: OkHttpClient) = OkHttp.create {
        preconfigured = client
    }
}
