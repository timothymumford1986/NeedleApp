package app.needler.core.data.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NetworkStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Network conditions, as far as the domain needs them.
 *
 * An interface rather than a concrete `ConnectivityManager` wrapper because three domain decisions
 * hang off it - whether to attempt the catalogue lane, whether to ask for a transcode, and whether a
 * Wi-Fi-only download may start - and all three need to be testable with no device.
 */
public interface NetworkMonitor {

    public fun observe(): Flow<ConnectivityState>

    public fun current(): ConnectivityState
}

/**
 * [NetworkMonitor] over `ConnectivityManager`.
 *
 * Offline is a first-class state rather than an error: a timeout and a missing network are the same
 * thing to everything above here, so the flow reports only the three coarse statuses the domain
 * models and lets the transport decide what a stalled request means.
 */
public class AndroidNetworkMonitor(
    private val context: Context,
) : NetworkMonitor {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<ConnectivityState> = callbackFlow {
        val manager: ConnectivityManager? = connectivityManager
        if (manager == null) {
            trySend(ConnectivityState.Offline)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(current())
            }
        }
        val request: NetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        trySend(current())
        runCatching { manager.registerNetworkCallback(request, callback) }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()

    override fun current(): ConnectivityState {
        val manager: ConnectivityManager = connectivityManager ?: return ConnectivityState.Offline
        val network: Network = manager.activeNetwork ?: return ConnectivityState.Offline
        val capabilities: NetworkCapabilities =
            manager.getNetworkCapabilities(network) ?: return ConnectivityState.Offline
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ConnectivityState.Offline
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return ConnectivityState.Offline
        }
        val unmetered: Boolean =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val restricted: Boolean = manager.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return ConnectivityState(
            status = if (unmetered) NetworkStatus.UNMETERED else NetworkStatus.METERED,
            isDataSaverRestricted = restricted,
        )
    }
}
