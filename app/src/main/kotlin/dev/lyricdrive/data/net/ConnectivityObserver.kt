package dev.lyricdrive.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Emits true while the default network has validated internet access. */
class ConnectivityObserver(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    val online: Flow<Boolean> = callbackFlow {
        fun current(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }

            override fun onLost(network: Network) {
                trySend(current())
            }
        }
        trySend(current())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
