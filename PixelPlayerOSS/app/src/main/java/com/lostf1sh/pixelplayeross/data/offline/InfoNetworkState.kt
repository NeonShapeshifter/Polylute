package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class InfoNetworkState { READY, WIFI, INTERNET }

internal fun classifyInfoNetworks(capabilities: List<NetworkCapabilities>): InfoNetworkState {
    val wifi = capabilities.filter {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    if (wifi.isEmpty()) return InfoNetworkState.WIFI
    return if (wifi.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }) {
        InfoNetworkState.READY
    } else InfoNetworkState.INTERNET
}

/** Observes the same Wi-Fi/unmetered constraint as the workers, with validation exposed separately. */
internal fun infoNetworkState(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun current(): InfoNetworkState {
        return classifyInfoNetworks(manager.allNetworks.mapNotNull(manager::getNetworkCapabilities))
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(current()) }
        override fun onLost(network: Network) { trySend(current()) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(current()) }
    }
    manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    trySend(current())
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
