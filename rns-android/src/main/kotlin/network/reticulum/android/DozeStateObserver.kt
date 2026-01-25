package network.reticulum.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Represents the device's Doze state.
 */
sealed class DozeState {
    /** Device is in normal operation (not Doze). */
    data object Active : DozeState()

    /** Device is in Doze mode - network access restricted. */
    data object Dozing : DozeState()

    override fun toString(): String = when (this) {
        is Active -> "Active"
        is Dozing -> "Dozing"
    }
}

/**
 * A timestamped Doze state change for history tracking.
 */
data class DozeStateChange(
    val state: DozeState,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String = "$state at ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(timestamp)}"
}

/**
 * Observes Android Doze mode state changes.
 *
 * Provides a [StateFlow] of the current Doze state and maintains a history
 * buffer of recent state changes for debugging and logging.
 *
 * Usage:
 * ```kotlin
 * val observer = DozeStateObserver(context)
 * observer.start()
 *
 * // Current state
 * val isDozing = observer.state.value == DozeState.Dozing
 *
 * // React to changes
 * observer.state.collect { state ->
 *     when (state) {
 *         DozeState.Active -> resumeNormalOperation()
 *         DozeState.Dozing -> reduceActivity()
 *     }
 * }
 *
 * // View history
 * observer.recentHistory.forEach { change ->
 *     println("${change.state} at ${change.timestamp}")
 * }
 *
 * observer.stop()
 * ```
 *
 * @param context Application or service context for system services
 * @param historySize Maximum number of state changes to keep in history (default: 20)
 */
class DozeStateObserver(
    private val context: Context,
    private val historySize: Int = 20
) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var receiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(getCurrentState())

    /**
     * The current Doze state as a StateFlow.
     *
     * Use `.value` for immediate access or `collect` for reactive updates.
     */
    val state: StateFlow<DozeState> = _state.asStateFlow()

    // Thread-safe deque for history - newest at front
    private val history = ConcurrentLinkedDeque<DozeStateChange>()

    /**
     * Recent Doze state changes, newest first.
     *
     * Limited to [historySize] entries (default 20).
     */
    val recentHistory: List<DozeStateChange>
        get() = history.toList()

    /**
     * Start observing Doze state changes.
     *
     * Must be called before state changes will be tracked.
     * Safe to call multiple times - subsequent calls are no-ops.
     */
    fun start() {
        if (receiver != null) return // Already started

        // Record initial state in history
        recordStateChange(_state.value)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                    val newState = getCurrentState()
                    val oldState = _state.value

                    if (newState != oldState) {
                        Log.i(TAG, "Doze state changed: $oldState -> $newState")
                        recordStateChange(newState)
                        _state.value = newState
                    }
                }
            }
        }

        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        context.registerReceiver(receiver, filter)

        Log.i(TAG, "Started observing Doze state. Current: ${_state.value}")
    }

    /**
     * Stop observing Doze state changes.
     *
     * Safe to call multiple times or before start().
     */
    fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // May not be registered
            }
        }
        receiver = null
        Log.i(TAG, "Stopped observing Doze state")
    }

    /**
     * Check if currently observing.
     */
    val isObserving: Boolean
        get() = receiver != null

    private fun getCurrentState(): DozeState {
        return if (powerManager.isDeviceIdleMode) {
            DozeState.Dozing
        } else {
            DozeState.Active
        }
    }

    private fun recordStateChange(state: DozeState) {
        history.addFirst(DozeStateChange(state))

        // Trim to size limit
        while (history.size > historySize) {
            history.removeLast()
        }
    }

    companion object {
        private const val TAG = "DozeStateObserver"
    }
}
