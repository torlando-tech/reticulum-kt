package network.reticulum.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tracks service kill events caused by Android battery optimization.
 *
 * Counts how often the service is killed by the system (not by user action)
 * and provides dismissable warning state. Kill counts reset daily so stale
 * data does not accumulate.
 *
 * Detection logic:
 * - On service start: if "service_was_running" is true AND "user_initiated_stop" is false,
 *   the service was killed by the system. Increment kill count.
 * - On user stop: flags are set so next start is NOT counted as a kill.
 * - On system kill: flags remain unchanged, so next start detects the kill.
 *
 * Warning dismissal:
 * - User can dismiss the warning at a given kill count.
 * - Warning only resurfaces when killCountToday exceeds the dismissed-at count.
 *
 * Usage:
 * ```kotlin
 * val tracker = ServiceEventTracker(context)
 * tracker.recordServiceStart()
 *
 * // Observe events reactively
 * tracker.events.collect { events ->
 *     if (events.hasActiveWarning) showWarning(events.killCountToday)
 * }
 *
 * // When user dismisses warning
 * tracker.dismissWarning()
 *
 * // When user explicitly stops service
 * tracker.recordUserStop()
 * ```
 *
 * @param context Application or service context for SharedPreferences access
 */
class ServiceEventTracker(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _events = MutableStateFlow(loadEvents())

    /**
     * Current service event state as a StateFlow.
     *
     * Use `.value` for immediate access or `collect` for reactive updates.
     */
    val events: StateFlow<ServiceEvents> = _events.asStateFlow()

    /**
     * Record that the service has started.
     *
     * Checks if this is a restart after an unexpected system kill by examining
     * the "service_was_running" and "user_initiated_stop" flags. If the service
     * was previously running and stop was not user-initiated, increments the kill count.
     *
     * Always sets "service_was_running" = true and "user_initiated_stop" = false
     * for the new session.
     */
    fun recordServiceStart() {
        resetDayIfNeeded()

        val wasRunning = prefs.getBoolean(KEY_SERVICE_WAS_RUNNING, false)
        val userInitiatedStop = prefs.getBoolean(KEY_USER_INITIATED_STOP, false)

        if (wasRunning && !userInitiatedStop) {
            // Service was running but stop was not user-initiated -> system kill
            Log.w(TAG, "Detected service kill by system (was_running=true, user_stop=false)")
            incrementKillCount()
        }

        // Mark service as running for the new session
        prefs.edit()
            .putBoolean(KEY_SERVICE_WAS_RUNNING, true)
            .putBoolean(KEY_USER_INITIATED_STOP, false)
            .apply()
    }

    /**
     * Record that the user explicitly stopped the service.
     *
     * Sets flags so the next service start is NOT counted as a system kill.
     */
    fun recordUserStop() {
        prefs.edit()
            .putBoolean(KEY_USER_INITIATED_STOP, true)
            .putBoolean(KEY_SERVICE_WAS_RUNNING, false)
            .apply()
        Log.i(TAG, "Recorded user-initiated stop")
    }

    /**
     * Explicitly record a service kill event.
     *
     * Typically not called directly -- [recordServiceStart] handles kill detection
     * automatically. This method is available for external detection mechanisms.
     */
    fun recordServiceKill() {
        resetDayIfNeeded()
        incrementKillCount()
    }

    /**
     * Dismiss the current warning.
     *
     * Records the current kill count as the dismissed-at count. The warning will
     * only resurface if killCountToday increases past this value.
     */
    fun dismissWarning() {
        val current = _events.value
        prefs.edit()
            .putInt(KEY_WARNING_DISMISSED_AT, current.killCountToday)
            .apply()
        _events.value = current.copy(warningDismissedAtCount = current.killCountToday)
        Log.i(TAG, "Warning dismissed at kill count ${current.killCountToday}")
    }

    /**
     * Check whether the optimization warning should be shown.
     *
     * @param isExemptFromOptimization true if the app is exempt from battery optimization
     * @return true if there are kills today, kill count exceeds dismissed count, and app is NOT exempt
     */
    fun shouldShowWarning(isExemptFromOptimization: Boolean): Boolean {
        val current = _events.value
        return current.killCountToday > 0 &&
                current.killCountToday > current.warningDismissedAtCount &&
                !isExemptFromOptimization
    }

    private fun incrementKillCount() {
        val currentCount = prefs.getInt(KEY_KILL_COUNT, 0)
        val newCount = currentCount + 1
        val now = System.currentTimeMillis()

        prefs.edit()
            .putInt(KEY_KILL_COUNT, newCount)
            .putLong(KEY_LAST_KILL_TS, now)
            .apply()

        _events.value = ServiceEvents(
            killCountToday = newCount,
            lastKillTimestamp = now,
            warningDismissedAtCount = prefs.getInt(KEY_WARNING_DISMISSED_AT, -1)
        )

        Log.w(TAG, "Service kill count today: $newCount")
    }

    /**
     * Check if the stored date differs from today and reset counters if so.
     */
    private fun resetDayIfNeeded() {
        val today = LocalDate.now().format(DATE_FORMAT)
        val storedDate = prefs.getString(KEY_EVENT_DATE, null)

        if (storedDate != today) {
            Log.i(TAG, "New day detected ($storedDate -> $today), resetting kill counters")
            prefs.edit()
                .putString(KEY_EVENT_DATE, today)
                .putInt(KEY_KILL_COUNT, 0)
                .putLong(KEY_LAST_KILL_TS, 0L)
                .putInt(KEY_WARNING_DISMISSED_AT, -1)
                .apply()
            _events.value = ServiceEvents.EMPTY
        }
    }

    /**
     * Load current events from SharedPreferences, applying daily reset if needed.
     */
    private fun loadEvents(): ServiceEvents {
        val today = LocalDate.now().format(DATE_FORMAT)
        val storedDate = prefs.getString(KEY_EVENT_DATE, null)

        if (storedDate != today) {
            // Different day or first run -- start fresh
            return ServiceEvents.EMPTY
        }

        return ServiceEvents(
            killCountToday = prefs.getInt(KEY_KILL_COUNT, 0),
            lastKillTimestamp = prefs.getLong(KEY_LAST_KILL_TS, 0L),
            warningDismissedAtCount = prefs.getInt(KEY_WARNING_DISMISSED_AT, -1)
        )
    }

    /**
     * Snapshot of service kill events for today.
     *
     * @property killCountToday How many times the service was killed by the system today
     * @property lastKillTimestamp Unix timestamp of the last kill (0 if none)
     * @property warningDismissedAtCount The kill count at which the user last dismissed the warning (-1 if never)
     * @property hasActiveWarning true if there are kills today AND killCountToday exceeds the dismissed count
     */
    data class ServiceEvents(
        val killCountToday: Int,
        val lastKillTimestamp: Long,
        val warningDismissedAtCount: Int
    ) {
        /** Whether there is an active (undismissed) warning about service kills. */
        val hasActiveWarning: Boolean
            get() = killCountToday > 0 && killCountToday > warningDismissedAtCount

        companion object {
            /** Empty events state for a new day or first run. */
            val EMPTY = ServiceEvents(
                killCountToday = 0,
                lastKillTimestamp = 0L,
                warningDismissedAtCount = -1
            )
        }
    }

    companion object {
        private const val TAG = "ServiceEventTracker"

        /** SharedPreferences file name (shared with BatteryStatsTracker). */
        private const val PREFS_NAME = "reticulum_battery"

        // SharedPreferences keys
        private const val KEY_KILL_COUNT = "event_kill_count"
        private const val KEY_LAST_KILL_TS = "event_last_kill_ts"
        private const val KEY_WARNING_DISMISSED_AT = "event_warning_dismissed_at"
        private const val KEY_EVENT_DATE = "event_date"
        private const val KEY_SERVICE_WAS_RUNNING = "service_was_running"
        private const val KEY_USER_INITIATED_STOP = "user_initiated_stop"

        /** Date format for daily reset detection. */
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
