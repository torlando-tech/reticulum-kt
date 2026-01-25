package network.reticulum.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents the current network connection type.
 */
sealed class NetworkType {
    /** Connected via WiFi. */
    data object WiFi : NetworkType()

    /** Connected via cellular (mobile data). */
    data object Cellular : NetworkType()

    /** No network connection available. */
    data object None : NetworkType()

    override fun toString(): String = when (this) {
        is WiFi -> "WiFi"
        is Cellular -> "Cellular"
        is None -> "None"
    }
}

/**
 * Complete network state including connection type and interface details.
 */
data class NetworkState(
    val type: NetworkType,
    val interfaceName: String? = null,
    val isAvailable: Boolean = type != NetworkType.None
) {
    override fun toString(): String = when {
        interfaceName != null -> "$type ($interfaceName)"
        else -> type.toString()
    }
}

/**
 * A timestamped network state change for history tracking.
 */
data class NetworkStateChange(
    val state: NetworkState,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String = "$state at ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(timestamp)}"
}

/**
 * Observes Android network state changes.
 *
 * Provides a [StateFlow] of the current network state and maintains a history
 * buffer of recent state changes for debugging and logging. Includes debouncing
 * to handle rapid WiFi/cellular handoffs gracefully.
 *
 * Usage:
 * ```kotlin
 * val observer = NetworkStateObserver(context)
 * observer.start()
 *
 * // Current state
 * val isWifi = observer.state.value.type == NetworkType.WiFi
 *
 * // React to changes
 * observer.state.collect { state ->
 *     when (state.type) {
 *         NetworkType.WiFi -> onWifiConnected(state.interfaceName)
 *         NetworkType.Cellular -> onCellularConnected(state.interfaceName)
 *         NetworkType.None -> onDisconnected()
 *     }
 * }
 *
 * observer.stop()
 * ```
 *
 * @param context Application or service context
 * @param historySize Maximum number of state changes to keep (default: 20)
 * @param debounceMs Debounce time for rapid network changes (default: 500ms)
 */
class NetworkStateObserver(
    private val context: Context,
    private val historySize: Int = 20,
    private val debounceMs: Long = 500L
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow(getCurrentState())

    /**
     * The current network state as a StateFlow.
     *
     * Use `.value` for immediate access or `collect` for reactive updates.
     */
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val history = ConcurrentLinkedDeque<NetworkStateChange>()

    /**
     * Recent network state changes, newest first.
     *
     * Limited to [historySize] entries (default 20).
     */
    val recentHistory: List<NetworkStateChange>
        get() = history.toList()

    // Debounce job for coalescing rapid changes
    private val debounceScope = CoroutineScope(Dispatchers.Default)
    private var debounceJob: Job? = null
    private var pendingState: NetworkState? = null

    /**
     * Start observing network state changes.
     */
    fun start() {
        if (started.getAndSet(true)) return

        // Record initial state
        recordStateChange(_state.value)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateStateDebounced()
            }

            override fun onLost(network: Network) {
                updateStateDebounced()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                updateStateDebounced()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                updateStateDebounced()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
        Log.i(TAG, "Started observing network state. Current: ${_state.value}")
    }

    /**
     * Stop observing network state changes.
     */
    fun stop() {
        if (!started.getAndSet(false)) return

        debounceJob?.cancel()
        debounceJob = null

        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // May not be registered
            }
        }
        networkCallback = null
        Log.i(TAG, "Stopped observing network state")
    }

    /**
     * Check if currently observing.
     */
    val isObserving: Boolean
        get() = started.get()

    private fun updateStateDebounced() {
        val newState = getCurrentState()
        pendingState = newState

        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            delay(debounceMs)

            pendingState?.let { state ->
                val oldState = _state.value
                if (state != oldState) {
                    Log.i(TAG, "Network state changed: $oldState -> $state")
                    recordStateChange(state)
                    _state.value = state
                }
            }
        }
    }

    private fun getCurrentState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }

        if (activeNetwork == null || capabilities == null) {
            return NetworkState(NetworkType.None)
        }

        val interfaceName = linkProperties?.interfaceName

        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WiFi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular
            else -> NetworkType.None
        }

        return NetworkState(type, interfaceName)
    }

    private fun recordStateChange(state: NetworkState) {
        history.addFirst(NetworkStateChange(state))
        while (history.size > historySize) {
            history.removeLast()
        }
    }

    companion object {
        private const val TAG = "NetworkStateObserver"
    }
}
