package com.litemusic.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One process-wide network callback shared by every Compose list. */
class NetworkStatusMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        // `activeNetwork` can still reference the lost default briefly.  Passing the lost
        // network prevents stale validated capabilities from keeping offline library rows live.
        // A replacement default network has a different identity and remains online.
        override fun onLost(network: Network) = refresh(lostNetwork = network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }

    init {
        refresh()
        connectivity.registerDefaultNetworkCallback(callback)
    }

    /** Screens call this when returning from background in case a system callback was missed. */
    fun refresh() {
        refresh(lostNetwork = null)
    }

    private fun refresh(lostNetwork: Network?) {
        val active = connectivity.activeNetwork
        val capabilities = active?.let(connectivity::getNetworkCapabilities)
        _isOnline.value = networkIsOnline(
            activeNetworkIsLost = active != null && active == lostNetwork,
            hasInternetCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            hasValidatedCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }
}

internal fun networkIsOnline(
    activeNetworkIsLost: Boolean,
    hasInternetCapability: Boolean,
    hasValidatedCapability: Boolean,
): Boolean = !activeNetworkIsLost && hasInternetCapability && hasValidatedCapability
