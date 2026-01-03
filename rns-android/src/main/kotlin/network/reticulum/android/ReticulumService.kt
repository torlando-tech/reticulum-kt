package network.reticulum.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import java.io.File

/**
 * Android foreground service for running Reticulum.
 *
 * This service manages the Reticulum instance lifecycle, handles Android-specific
 * concerns like Doze mode, and provides a foreground notification for reliable
 * background operation.
 *
 * Usage:
 * ```kotlin
 * // Start with default config
 * ReticulumService.start(context)
 *
 * // Start with custom config
 * ReticulumService.start(context, ReticulumConfig.ROUTING)
 *
 * // Stop
 * ReticulumService.stop(context)
 * ```
 */
class ReticulumService : LifecycleService() {

    private var reticulum: Reticulum? = null
    private var config: ReticulumConfig = ReticulumConfig.CLIENT_ONLY
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ReticulumService = this@ReticulumService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Extract config from intent if provided
        intent?.getParcelableExtra<ReticulumConfig>(EXTRA_CONFIG)?.let {
            config = it
        }

        // Start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // Initialize Reticulum
        lifecycleScope.launch {
            initializeReticulum()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        serviceScope.cancel()
        shutdownReticulum()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                // Aggressive memory cleanup
                trimMemory()
            }
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_MODERATE -> {
                // Moderate cleanup
                trimMemory(aggressive = false)
            }
        }
    }

    private suspend fun initializeReticulum() {
        try {
            val configDir = config.configDir ?: File(filesDir, "reticulum").absolutePath

            // Ensure config directory exists
            File(configDir).mkdirs()

            // Configure Transport for coroutine-based job loop on Android
            network.reticulum.transport.Transport.configureCoroutineJobLoop(
                scope = serviceScope,
                intervalMs = config.getEffectiveJobInterval()
            )

            reticulum = Reticulum.start(
                configDir = configDir,
                enableTransport = config.mode == ReticulumConfig.Mode.ROUTING && config.enableTransport
            )

            // In ROUTING mode, schedule WorkManager for periodic maintenance
            // This survives Doze mode and app backgrounding
            if (config.mode == ReticulumConfig.Mode.ROUTING) {
                ReticulumWorker.schedule(this@ReticulumService, intervalMinutes = 15)
            }

            updateNotification("Connected")
        } catch (e: Exception) {
            updateNotification("Error: ${e.message}")
        }
    }

    private fun shutdownReticulum() {
        try {
            // Cancel WorkManager maintenance if scheduled
            ReticulumWorker.cancel(this)

            Reticulum.stop()
            reticulum = null
        } catch (e: Exception) {
            // Log error but don't crash
        }
    }

    private fun trimMemory(aggressive: Boolean = true) {
        // TODO: Implement memory trimming
        // - Clear byte array pools
        // - Trim hashlists
        // - Clear caches
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reticulum Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Reticulum mesh network service"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(status: String = "Starting..."): Notification {
        val modeText = when (config.mode) {
            ReticulumConfig.Mode.CLIENT_ONLY -> "Client"
            ReticulumConfig.Mode.ROUTING -> "Routing"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reticulum $modeText")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_reticulum)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Get the current Reticulum instance.
     * Returns null if not yet initialized.
     */
    fun getReticulum(): Reticulum? = reticulum

    /**
     * Get the current configuration.
     */
    fun getConfig(): ReticulumConfig = config

    /**
     * Check if Reticulum is running.
     */
    fun isRunning(): Boolean = reticulum != null

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "reticulum_service"
        private const val EXTRA_CONFIG = "config"

        /**
         * Start the Reticulum service with default client-only configuration.
         */
        fun start(context: Context) {
            start(context, ReticulumConfig.CLIENT_ONLY)
        }

        /**
         * Start the Reticulum service with the specified configuration.
         */
        fun start(context: Context, config: ReticulumConfig) {
            val intent = Intent(context, ReticulumService::class.java).apply {
                putExtra(EXTRA_CONFIG, config)
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop the Reticulum service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, ReticulumService::class.java))
        }
    }
}
