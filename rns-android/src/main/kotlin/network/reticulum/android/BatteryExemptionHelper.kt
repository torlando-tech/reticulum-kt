package network.reticulum.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper for requesting battery optimization exemption from the system.
 *
 * Encapsulates the flow for prompting the user to exempt this app from
 * Android's battery optimization (Doze mode restrictions). The system dialog
 * is a one-tap "Allow" / "Deny" prompt launched via
 * [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
 *
 * **Prompt flow:**
 * 1. On first service start, if not already exempt, [shouldPrompt] returns true.
 * 2. The UI shows a bottom sheet (built in Plan 03) wrapping [buildExemptionIntent].
 * 3. If the user dismisses without granting, call [markPromptDismissed] so
 *    [shouldPrompt] returns false permanently.
 * 4. The user can always re-enable the prompt from the Monitor screen
 *    via [resetPromptDismissed], or navigate to system settings directly.
 *
 * @param context Application or service context
 */
class BatteryExemptionHelper(private val context: Context) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether this app is currently exempt from battery optimization.
     *
     * When exempt, the app can maintain network connections more freely
     * during Doze mode maintenance windows.
     */
    fun isExempt(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Build the system intent for requesting battery optimization exemption.
     *
     * This launches the system dialog that asks the user to allow this app
     * to ignore battery optimizations. It is a direct one-tap "Allow" / "Deny"
     * dialog, not the full battery settings screen.
     *
     * Usage:
     * ```kotlin
     * val intent = helper.buildExemptionIntent()
     * activity.startActivity(intent)
     * ```
     *
     * @return Intent for [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
     */
    fun buildExemptionIntent(): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * Whether the exemption prompt should be shown to the user.
     *
     * Returns true only if BOTH conditions are met:
     * 1. The app is NOT already exempt from battery optimization.
     * 2. The user has NOT previously dismissed the prompt.
     *
     * After a user dismisses the prompt once, this returns false permanently
     * until [resetPromptDismissed] is called.
     */
    fun shouldPrompt(): Boolean {
        if (isExempt()) return false
        val dismissed = prefs.getBoolean(KEY_PROMPT_DISMISSED, false)
        return !dismissed
    }

    /**
     * Mark the exemption prompt as dismissed by the user.
     *
     * After calling this, [shouldPrompt] will always return false regardless
     * of exemption status. This implements the "first-prompt-only" semantic:
     * the user is asked once, and if they decline, they are not asked again.
     */
    fun markPromptDismissed() {
        prefs.edit().putBoolean(KEY_PROMPT_DISMISSED, true).apply()
        Log.i(TAG, "Battery exemption prompt dismissed by user")
    }

    /**
     * Reset the dismissed state so the user can be prompted again.
     *
     * Intended for use from the Monitor screen settings, allowing the user
     * to re-enable the exemption prompt if they change their mind.
     */
    fun resetPromptDismissed() {
        prefs.edit().putBoolean(KEY_PROMPT_DISMISSED, false).apply()
        Log.i(TAG, "Battery exemption prompt reset - will prompt again if not exempt")
    }

    companion object {
        private const val TAG = "BatteryExemptionHelper"

        /** SharedPreferences file name (shared with BatteryStatsTracker). */
        internal const val PREFS_NAME = "reticulum_battery"

        /** SharedPreferences key tracking whether user dismissed the exemption prompt. */
        internal const val KEY_PROMPT_DISMISSED = "battery_exemption_dismissed"
    }
}
