package com.litemusic.app.feature.auth

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.litemusic.app.BuildConfig

/** Native authorization is opt-in through Android's own domain selection. */
internal fun qqNativeHandoffEnabled(context: Context): Boolean {
    if (BuildConfig.AUTH_HANDOFF_EXPERIMENT) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return runCatching {
        val state = context.getSystemService(DomainVerificationManager::class.java)
            ?.getDomainVerificationUserState(context.packageName) ?: return@runCatching false
        allowsQqAuthReturn(state.isLinkHandlingAllowed, state.hostToStateMap["ssl.ptlogin2.qq.com"] ?: 0)
    }.getOrDefault(false)
}

internal fun allowsQqAuthReturn(linkHandlingAllowed: Boolean, domainState: Int): Boolean =
    linkHandlingAllowed && (domainState == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
        domainState == DomainVerificationUserState.DOMAIN_STATE_VERIFIED)

internal fun openAuthLinkSettings(context: Context): Boolean = runCatching {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
        else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    context.startActivity(Intent(action, Uri.parse("package:${context.packageName}")))
}.isSuccess
