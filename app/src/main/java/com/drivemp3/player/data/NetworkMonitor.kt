package com.drivemp3.player.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the device currently has an internet-capable network (spec §5, v0.7).
 *
 * Reports *capability*, not just a connected interface: a network is only "online" here
 * once it advertises [NetworkCapabilities.NET_CAPABILITY_INTERNET], so a Wi-Fi link that
 * has associated but not yet routed does not read as usable.
 *
 * The value drives falling back to a cached-only library and blocking plays of
 * undownloaded tracks. It is a hint, not a guarantee — a network can advertise internet
 * and still fail a request — so callers still handle request-time failures; this just
 * lets the common offline case be caught before a doomed attempt rather than after it.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * Emits the current state immediately, then on every change. Cold and
     * self-unregistering: the callback is torn down when the last collector goes away,
     * so an idle screen holds no system callback.
     */
    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // Another network may still be up (e.g. Wi-Fi dropped, cellular remains),
                // so re-read rather than assuming this loss means offline.
                trySend(currentlyOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val manager = connectivityManager
        if (manager == null) {
            // No ConnectivityManager is vanishingly unlikely, but assuming online keeps
            // the app usable rather than wedging it in a permanent offline state.
            trySend(true)
            awaitClose { }
        } else {
            manager.registerNetworkCallback(request, callback)
            trySend(currentlyOnline())
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun currentlyOnline(): Boolean {
        val manager = connectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
