package com.litemusic.app.feature.auth

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Installs a same-document QR polling recovery script before QQ's own page scripts execute.
 * It never exposes cookies, QR signatures, keys, or authorization parameters to Android.
 */
internal object QqLoginLifecycleBridge {
    private const val ASSET = "qq_lifecycle_bridge.js"
    private val allowedOrigins = setOf("https://xui.ptlogin2.qq.com")

    fun install(webView: WebView): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        val script = runCatching {
            webView.context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOrigins)
        }.getOrNull()
    }

    fun remove(handler: ScriptHandler) {
        handler.remove()
    }
}
