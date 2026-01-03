package network.reticulum.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import network.reticulum.transport.Transport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors network connectivity state on Android.
 *
 * Integrates with ConnectivityManager to:
 * - Detect network availability changes
 * - Identify network type (WiFi, cellular, metered)
 * - Enable data saver mode on metered connections
 * - Notify Transport layer of network state changes
 *
 * Usage:
 * ```kotlin
 * val monitor = NetworkMonitor(context)
 * monitor.start()
 * // ... app lifecycle ...
 * monitor.stop()
 * ```
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)

    /**
     * Listener for network state changes.
     */
    interface NetworkStateListener {
        fun onNetworkAvailable()
        fun onNetworkLost()
        fun onNetworkTypeChanged(isWifi: Boolean, isCellular: Boolean, isMetered: Boolean)
    }

    private var listener: NetworkStateListener? = null

    /**
     * Current network state.
     */
    @Volatile
    var isNetworkAvailable: Boolean = false
        private set

    @Volatile
    var isWifi: Boolean = false
        private set

    @Volatile
    var isCellular: Boolean = false
        private set

    @Volatile
    var isMetered: Boolean = true
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            isNetworkAvailable = true
            onNetworkAvailable()
        }

        override fun onLost(network: Network) {
            // Check if we still have any network
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                isNetworkAvailable = false
                onNetworkLost()
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val wasMetered = isMetered

            isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            onNetworkTypeChanged(isWifi, isCellular, isMetered)

            // Only update data saver mode if metered state changed
            if (wasMetered != isMetered) {
                updateDataSaverMode()
            }
        }
    }

    /**
     * Set a listener for network state changes.
     */
    fun setListener(listener: NetworkStateListener?) {
        this.listener = listener
    }

    /**
     * Start monitoring network state.
     */
    fun start() {
        if (started.getAndSet(true)) return

        // Check initial state
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        isNetworkAvailable = activeNetwork != null
        if (capabilities != null) {
            isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        // Update Transport based on initial state
        updateDataSaverMode()

        // Register for network changes
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    /**
     * Stop monitoring network state.
     */
    fun stop() {
        if (!started.getAndSet(false)) return

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Callback may not be registered
        }
    }

    private fun onNetworkAvailable() {
        try {
            Transport.onNetworkAvailable()
        } catch (e: Exception) {
            // Transport may not be initialized
        }
        listener?.onNetworkAvailable()
    }

    private fun onNetworkLost() {
        try {
            Transport.onNetworkLost()
        } catch (e: Exception) {
            // Transport may not be initialized
        }
        listener?.onNetworkLost()
    }

    private fun onNetworkTypeChanged(isWifi: Boolean, isCellular: Boolean, isMetered: Boolean) {
        listener?.onNetworkTypeChanged(isWifi, isCellular, isMetered)
    }

    private fun updateDataSaverMode() {
        try {
            // Enable data saver on metered connections (cellular)
            Transport.setDataSaverMode(isCellular && isMetered)
        } catch (e: Exception) {
            // Transport may not be initialized
        }
    }

    /**
     * Get a summary of current network state.
     */
    fun getNetworkInfo(): NetworkInfo {
        return NetworkInfo(
            available = isNetworkAvailable,
            wifi = isWifi,
            cellular = isCellular,
            metered = isMetered
        )
    }

    /**
     * Network state information.
     */
    data class NetworkInfo(
        val available: Boolean,
        val wifi: Boolean,
        val cellular: Boolean,
        val metered: Boolean
    ) {
        override fun toString(): String {
            return when {
                !available -> "No network"
                wifi -> "WiFi" + if (metered) " (metered)" else ""
                cellular -> "Cellular" + if (metered) " (metered)" else ""
                else -> "Unknown network"
            }
        }
    }
}
