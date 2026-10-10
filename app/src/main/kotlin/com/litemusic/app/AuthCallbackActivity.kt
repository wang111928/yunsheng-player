package com.litemusic.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.litemusic.app.feature.auth.AuthHandoffBridge

/** Restores only a pending live authorization. Callback values are never login credentials. */
class AuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val received = intent
        val uri = received?.data
        val httpsJump = received?.action == Intent.ACTION_VIEW &&
            uri?.scheme == "https" && uri.host == "ssl.ptlogin2.qq.com" && uri.path == "/jump"
        val accepted = httpsJump && AuthHandoffBridge.captureHttpsReturn(received)
        if (accepted) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } else if (httpsJump) {
            // Explicit browser package avoids routing a rejected login link back into this gate.
            val browserPackages = packageManager.queryIntentActivities(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                PackageManager.MATCH_DEFAULT_ONLY,
            ).map { it.activityInfo.packageName }.distinct().filter { it != packageName }
            // Resolve a non-captured public link without opening it. APP_BROWSER itself may
            // resolve to the system chooser rather than a real browser package.
            val preferred = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.qq.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE).resolveActivity(packageManager)?.packageName
                ?.takeIf { it in browserPackages }
            val opened = browserPackages.isNotEmpty() && runCatching {
                val alternatives = browserPackages.map { browser ->
                    Intent(Intent.ACTION_VIEW, uri).setPackage(browser).addCategory(Intent.CATEGORY_BROWSABLE)
                }
                val destination = if (preferred != null) alternatives.first { it.`package` == preferred }
                    else if (alternatives.size == 1) alternatives.single()
                    else Intent.createChooser(alternatives.first(), "使用浏览器继续登录").apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, alternatives.drop(1).toTypedArray())
                    }
                startActivity(destination)
                true
            }.getOrDefault(false)
            if (!opened) Toast.makeText(this, "本次登录链接无法接收，请重新打开官方登录页", Toast.LENGTH_SHORT).show()
        }
        intent?.data = null
        finish()
    }
}
