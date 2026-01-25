package network.reticulum.android

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides a unified [ConnectionPolicy] by combining Doze, network, and battery states.
 *
 * This class merges three state sources into a single reactive [StateFlow] that downstream
 * components (Transport, interfaces) can observe to adjust their behavior.
 *
 * **State Sources:**
 * - [DozeStateObserver]: Device idle mode state
 * - [NetworkStateObserver]: Network connectivity and type
 * - [BatteryMonitor]: Battery level and charging state (polled every 30 seconds)
 *
 * **Battery Polling:**
 * Unlike Doze and Network which are event-driven via StateFlow, BatteryMonitor uses a
 * listener-based API. Since battery level changes slowly (typically 1% every few minutes
 * under normal use), we poll every 30 seconds rather than converting to callbackFlow.
 * This approach is simpler and sufficient for our throttling needs.
 *
 * **Hysteresis:**
 * Battery throttling uses 3% hysteresis to prevent flip-flopping:
 * - Throttle begins at 15% battery
 * - Throttle ends at 18% battery
 *
 * Usage:
 * ```kotlin
 * val provider = ConnectionPolicyProvider(dozeObserver, networkObserver, batteryMonitor, scope)
 * provider.start()
 *
 * // React to policy changes
 * provider.policy.collect { policy ->
 *     if (policy.shouldThrottle) {
 *         reducePollingFrequency(policy.throttleMultiplier)
 *     }
 * }
 *
 * provider.stop()
 * ```
 *
 * @param dozeObserver Observer for device Doze state
 * @param networkObserver Observer for network connectivity
 * @param batteryMonitor Monitor for battery level and charging state
 * @param scope Coroutine scope for launching the combination flow
 */
class ConnectionPolicyProvider(
    private val dozeObserver: DozeStateObserver,
    private val networkObserver: NetworkStateObserver,
    private val batteryMonitor: BatteryMonitor,
    private val scope: CoroutineScope
) {
    private val _policy = MutableStateFlow(createInitialPolicy())

    /**
     * The current connection policy as a StateFlow.
     *
     * Emits whenever any underlying state changes (Doze, network, or battery).
     */
    val policy: StateFlow<ConnectionPolicy> = _policy.asStateFlow()

    /**
     * Current policy for synchronous access.
     */
    val currentPolicy: ConnectionPolicy
        get() = _policy.value

    private var combineJob: Job? = null
    private val started = AtomicBoolean(false)

    // Hysteresis state for battery throttling
    @Volatile
    private var wasThrottledForBattery = false

    /**
     * Start observing and combining state sources.
     *
     * Safe to call multiple times - subsequent calls are no-ops.
     */
    fun start() {
        if (started.getAndSet(true)) return

        combineJob = scope.launch {
            // Create a polling flow for battery info (30 second intervals)
            val batteryFlow = flow {
                while (isActive) {
                    emit(batteryMonitor.getBatteryInfo())
                    delay(BATTERY_POLL_INTERVAL_MS)
                }
            }

            combine(
                dozeObserver.state,
                networkObserver.state,
                batteryFlow
            ) { doze, network, battery ->
                val (policy, newWasThrottled) = ConnectionPolicy.create(
                    doze = doze,
                    network = network,
                    batteryLevel = battery.level,
                    isCharging = battery.charging,
                    wasThrottledForBattery = wasThrottledForBattery
                )
                wasThrottledForBattery = newWasThrottled
                policy
            }.collect { newPolicy ->
                val oldPolicy = _policy.value
                if (newPolicy != oldPolicy) {
                    Log.i(TAG, "Connection policy changed: $oldPolicy -> $newPolicy")
                    _policy.value = newPolicy
                }
            }
        }

        Log.i(TAG, "Started. Initial policy: ${_policy.value}")
    }

    /**
     * Stop observing state sources.
     *
     * Safe to call multiple times or before start().
     */
    fun stop() {
        if (!started.getAndSet(false)) return

        combineJob?.cancel()
        combineJob = null
        Log.i(TAG, "Stopped")
    }

    /**
     * Check if currently running.
     */
    val isRunning: Boolean
        get() = started.get()

    private fun createInitialPolicy(): ConnectionPolicy {
        val battery = batteryMonitor.getBatteryInfo()
        val (policy, newWasThrottled) = ConnectionPolicy.create(
            doze = dozeObserver.state.value,
            network = networkObserver.state.value,
            batteryLevel = battery.level,
            isCharging = battery.charging,
            wasThrottledForBattery = wasThrottledForBattery
        )
        wasThrottledForBattery = newWasThrottled
        return policy
    }

    companion object {
        private const val TAG = "ConnectionPolicyProvider"

        /** Battery polling interval - 30 seconds is sufficient since battery changes slowly. */
        const val BATTERY_POLL_INTERVAL_MS = 30_000L
    }
}
