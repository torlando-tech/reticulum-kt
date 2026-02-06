package network.reticulum.android

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks battery level over time and computes drain rate statistics.
 *
 * Periodically samples the battery level (every 60 seconds) and maintains
 * up to 24 hours of history for chart rendering and drain rate computation.
 * Samples are persisted to SharedPreferences so statistics survive service restarts.
 *
 * Usage:
 * ```kotlin
 * val tracker = BatteryStatsTracker(context)
 * tracker.start(serviceScope)
 *
 * // Observe stats reactively
 * tracker.stats.collect { stats ->
 *     updateDrainRate(stats.drainRatePerHour)
 *     updateChart(stats.samples)
 * }
 *
 * // Clean up
 * tracker.stop()
 * ```
 *
 * @param context Application or service context for BatteryManager access
 */
class BatteryStatsTracker(private val context: Context) {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var samplingJob: Job? = null
    private val sampleList = mutableListOf<BatterySample>()

    private val _stats = MutableStateFlow(BatteryStats.EMPTY)

    /**
     * Current battery statistics as a StateFlow.
     *
     * Use `.value` for immediate access or `collect` for reactive updates.
     * Updates every [SAMPLE_INTERVAL_MS] milliseconds.
     */
    val stats: StateFlow<BatteryStats> = _stats.asStateFlow()

    /**
     * Start periodic battery sampling.
     *
     * Restores any previously persisted samples (trimming those older than 24 hours),
     * then begins a coroutine loop that samples every 60 seconds.
     *
     * @param scope The coroutine scope to launch sampling in (typically serviceScope)
     */
    fun start(scope: CoroutineScope) {
        if (samplingJob != null) return

        restoreSamples()

        samplingJob = scope.launch {
            Log.i(TAG, "Started battery stats tracking with ${sampleList.size} restored samples")
            while (true) {
                takeSample()
                updateStats()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop periodic battery sampling and persist current samples.
     *
     * Persists samples to SharedPreferences so they survive service restarts.
     */
    fun stop() {
        samplingJob?.cancel()
        samplingJob = null
        persistSamples()
        Log.i(TAG, "Stopped battery stats tracking. Persisted ${sampleList.size} samples")
    }

    private fun takeSample() {
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val now = System.currentTimeMillis()

        val sample = BatterySample(timestamp = now, level = level, charging = charging)
        synchronized(sampleList) {
            sampleList.add(sample)
            // Trim to max samples (24 hours at 1-minute intervals)
            while (sampleList.size > MAX_SAMPLES) {
                sampleList.removeAt(0)
            }
        }
    }

    private fun updateStats() {
        val samples: List<BatterySample>
        synchronized(sampleList) {
            samples = sampleList.toList()
        }

        if (samples.isEmpty()) {
            _stats.value = BatteryStats.EMPTY
            return
        }

        val latest = samples.last()
        val sessionStart = samples.first()
        val isCharging = latest.charging

        // Drain rate over last 60 minutes (or full session if shorter)
        val oneHourAgo = latest.timestamp - HOUR_MS
        val recentSamples = samples.filter { it.timestamp >= oneHourAgo }
        val drainRatePerHour = computeDrainRate(recentSamples)

        // Session drain rate (since tracker started)
        val sessionDrainRate = computeDrainRate(samples)

        _stats.value = BatteryStats(
            currentLevel = latest.level,
            drainRatePerHour = if (isCharging) 0f else drainRatePerHour,
            sessionDrainRate = sessionDrainRate,
            samples = samples,
            sessionStartTime = sessionStart.timestamp,
            isCharging = isCharging
        )
    }

    /**
     * Computes drain rate in percent per hour from a list of samples.
     *
     * Returns 0.0 if there is insufficient data (fewer than 2 samples or
     * less than 1 second of elapsed time).
     */
    private fun computeDrainRate(samples: List<BatterySample>): Float {
        if (samples.size < 2) return 0f

        val first = samples.first()
        val last = samples.last()
        val elapsedMs = last.timestamp - first.timestamp
        if (elapsedMs < 1000) return 0f // Need at least 1 second of data

        val elapsedHours = elapsedMs.toFloat() / HOUR_MS
        val levelDrop = first.level - last.level
        return levelDrop / elapsedHours
    }

    private fun persistSamples() {
        val samples: List<BatterySample>
        synchronized(sampleList) {
            samples = sampleList.toList()
        }

        val jsonArray = JSONArray()
        for (sample in samples) {
            val obj = JSONObject()
            obj.put(KEY_TIMESTAMP, sample.timestamp)
            obj.put(KEY_LEVEL, sample.level)
            obj.put(KEY_CHARGING, sample.charging)
            jsonArray.put(obj)
        }

        prefs.edit().putString(PREFS_KEY_SAMPLES, jsonArray.toString()).apply()
    }

    private fun restoreSamples() {
        val json = prefs.getString(PREFS_KEY_SAMPLES, null) ?: return

        try {
            val jsonArray = JSONArray(json)
            val cutoff = System.currentTimeMillis() - MAX_HISTORY_MS
            val restored = mutableListOf<BatterySample>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val timestamp = obj.getLong(KEY_TIMESTAMP)
                // Trim samples older than 24 hours
                if (timestamp >= cutoff) {
                    restored.add(
                        BatterySample(
                            timestamp = timestamp,
                            level = obj.getInt(KEY_LEVEL),
                            charging = obj.optBoolean(KEY_CHARGING, false)
                        )
                    )
                }
            }

            synchronized(sampleList) {
                sampleList.clear()
                sampleList.addAll(restored)
            }

            Log.i(TAG, "Restored ${restored.size} samples from persistence (trimmed ${jsonArray.length() - restored.size} expired)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore battery samples: ${e.message}")
        }
    }

    /**
     * A single battery level sample with timestamp and charging state.
     */
    data class BatterySample(
        /** Unix timestamp in milliseconds when this sample was taken. */
        val timestamp: Long,
        /** Battery level as a percentage (0-100). */
        val level: Int,
        /** Whether the device was charging when this sample was taken. */
        val charging: Boolean = false
    )

    /**
     * Aggregated battery statistics computed from sampled history.
     */
    data class BatteryStats(
        /** Current battery level as a percentage (0-100). */
        val currentLevel: Int,
        /**
         * Drain rate in percent per hour, computed from the last 60 minutes of samples.
         * Returns 0.0 when charging. Negative values indicate charging gain.
         */
        val drainRatePerHour: Float,
        /**
         * Drain rate in percent per hour since the tracker started.
         * Includes charging periods (may be negative if net charging).
         */
        val sessionDrainRate: Float,
        /** Full sample history for chart rendering (up to 24 hours). */
        val samples: List<BatterySample>,
        /** Unix timestamp when tracking began (first sample). */
        val sessionStartTime: Long,
        /** Whether the device is currently charging. */
        val isCharging: Boolean
    ) {
        companion object {
            /** Empty stats returned before any samples are collected. */
            val EMPTY = BatteryStats(
                currentLevel = 0,
                drainRatePerHour = 0f,
                sessionDrainRate = 0f,
                samples = emptyList(),
                sessionStartTime = 0L,
                isCharging = false
            )
        }
    }

    companion object {
        private const val TAG = "BatteryStatsTracker"

        /** SharedPreferences file name (shared with BatteryExemptionHelper). */
        internal const val PREFS_NAME = "reticulum_battery"

        /** Sampling interval: 60 seconds. */
        private const val SAMPLE_INTERVAL_MS = 60_000L

        /** Maximum samples to keep in memory (24 hours at 1-minute intervals). */
        private const val MAX_SAMPLES = 1440

        /** Maximum history age for restored samples: 24 hours. */
        private const val MAX_HISTORY_MS = 24L * 60 * 60 * 1000

        /** One hour in milliseconds. */
        private const val HOUR_MS = 60L * 60 * 1000

        // JSON keys for persistence
        private const val PREFS_KEY_SAMPLES = "battery_samples_json"
        private const val KEY_TIMESTAMP = "t"
        private const val KEY_LEVEL = "l"
        private const val KEY_CHARGING = "c"
    }
}
