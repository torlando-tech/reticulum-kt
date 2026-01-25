package network.reticulum.android

/**
 * Connection policy that determines how Reticulum connections should behave based
 * on the current device state (Doze mode, network availability, battery level).
 *
 * The [throttleMultiplier] indicates how much slower operations should run:
 * - 1.0 = normal speed
 * - 2.0 = 2x slower (double the intervals)
 * - 5.0 = 5x slower (used for Deep Doze and low battery)
 *
 * Downstream components (Transport, interfaces) should multiply their normal
 * polling/maintenance intervals by this value.
 *
 * @property throttleMultiplier Factor by which to slow down operations (1.0 = normal)
 * @property shouldThrottle Convenience flag: true when multiplier > 1.0
 * @property networkAvailable Whether network connectivity is available
 * @property reason Human-readable explanation for the current policy
 */
data class ConnectionPolicy(
    val throttleMultiplier: Float,
    val shouldThrottle: Boolean,
    val networkAvailable: Boolean,
    val reason: String
) {
    companion object {
        /** Normal operation - no throttling. */
        const val NORMAL_MULTIPLIER = 1.0f

        /** Deep Doze and low battery throttle factor. */
        const val DOZE_MULTIPLIER = 5.0f

        /** Battery level at which throttling begins. */
        const val BATTERY_THROTTLE_THRESHOLD = 15

        /** Battery level at which throttling stops (3% hysteresis above threshold). */
        const val BATTERY_RESUME_THRESHOLD = 18

        /**
         * Creates a ConnectionPolicy based on the current device state.
         *
         * Priority order:
         * 1. Charging overrides all battery throttling (full speed when plugged in)
         * 2. Doze mode applies 5x throttle
         * 3. Low battery (<15%) applies 5x throttle with hysteresis
         *
         * Hysteresis behavior:
         * - Throttle begins when battery drops below [BATTERY_THROTTLE_THRESHOLD] (15%)
         * - Throttle continues until battery rises to [BATTERY_RESUME_THRESHOLD] (18%)
         * - This prevents rapid on/off cycling when battery hovers near the threshold
         *
         * @param doze Current Doze state
         * @param network Current network state
         * @param batteryLevel Current battery percentage (0-100)
         * @param isCharging Whether the device is currently charging
         * @param wasThrottledForBattery Previous hysteresis state
         * @return Pair of (new policy, new wasThrottledForBattery state)
         */
        fun create(
            doze: DozeState,
            network: NetworkState,
            batteryLevel: Int,
            isCharging: Boolean,
            wasThrottledForBattery: Boolean
        ): Pair<ConnectionPolicy, Boolean> {
            val networkAvailable = network.isAvailable

            // Priority 1: Charging overrides battery throttling
            if (isCharging) {
                // Still respect Doze even when charging
                return if (doze == DozeState.Dozing) {
                    ConnectionPolicy(
                        throttleMultiplier = DOZE_MULTIPLIER,
                        shouldThrottle = true,
                        networkAvailable = networkAvailable,
                        reason = "Device in Doze mode"
                    ) to false // Reset battery hysteresis when charging
                } else {
                    ConnectionPolicy(
                        throttleMultiplier = NORMAL_MULTIPLIER,
                        shouldThrottle = false,
                        networkAvailable = networkAvailable,
                        reason = "Normal operation (charging)"
                    ) to false
                }
            }

            // Priority 2: Doze mode
            if (doze == DozeState.Dozing) {
                return ConnectionPolicy(
                    throttleMultiplier = DOZE_MULTIPLIER,
                    shouldThrottle = true,
                    networkAvailable = networkAvailable,
                    reason = "Device in Doze mode"
                ) to wasThrottledForBattery // Preserve battery hysteresis
            }

            // Priority 3: Low battery with hysteresis
            val newWasThrottled = when {
                // Start throttling when battery drops below threshold
                batteryLevel < BATTERY_THROTTLE_THRESHOLD && !wasThrottledForBattery -> true
                // Stop throttling when battery rises above resume threshold
                batteryLevel >= BATTERY_RESUME_THRESHOLD && wasThrottledForBattery -> false
                // Otherwise keep current state
                else -> wasThrottledForBattery
            }

            return if (newWasThrottled) {
                ConnectionPolicy(
                    throttleMultiplier = DOZE_MULTIPLIER,
                    shouldThrottle = true,
                    networkAvailable = networkAvailable,
                    reason = "Low battery ($batteryLevel%)"
                ) to true
            } else {
                ConnectionPolicy(
                    throttleMultiplier = NORMAL_MULTIPLIER,
                    shouldThrottle = false,
                    networkAvailable = networkAvailable,
                    reason = "Normal operation"
                ) to false
            }
        }
    }

    override fun toString(): String =
        "ConnectionPolicy(${throttleMultiplier}x, network=$networkAvailable, $reason)"
}
