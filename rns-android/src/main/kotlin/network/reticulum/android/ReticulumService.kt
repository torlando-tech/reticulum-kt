package network.reticulum.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.interfaces.local.LocalServerInterface
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
    private var config: ReticulumConfig = ReticulumConfig.DEFAULT
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Lifecycle-aware observers for Android system state
    private lateinit var dozeObserver: DozeStateObserver
    private lateinit var networkObserver: NetworkStateObserver
    private lateinit var batteryChecker: BatteryOptimizationChecker

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ReticulumService = this@ReticulumService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize lifecycle-aware observers
        dozeObserver = DozeStateObserver(this)
        networkObserver = NetworkStateObserver(this)
        batteryChecker = BatteryOptimizationChecker(this)

        // Start observers
        dozeObserver.start()
        networkObserver.start()
        batteryChecker.start()

        // Log initial states
        Log.i(TAG, "Doze state: ${dozeObserver.state.value}")
        Log.i(TAG, "Network state: ${networkObserver.state.value}")
        Log.i(TAG, "Battery optimization: ${batteryChecker.status.value}")

        // Collect state changes with placeholder logging
        // Phase 12 will implement actual reactions to these state changes
        lifecycleScope.launch {
            dozeObserver.state.collect { state ->
                Log.i(TAG, "Doze state changed: $state")
                // Phase 12 will implement actual reactions
            }
        }

        lifecycleScope.launch {
            networkObserver.state.collect { state ->
                Log.i(TAG, "Network state changed: $state")
                // Phase 12 will implement actual reactions
            }
        }

        lifecycleScope.launch {
            batteryChecker.status.collect { status ->
                Log.i(TAG, "Battery optimization status: $status")
                // Phase 15 will implement guidance flow
            }
        }
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
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
        // Stop observers before other cleanup
        dozeObserver.stop()
        networkObserver.stop()
        batteryChecker.stop()

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
                trimMemory(aggressive = true)
            }
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_MODERATE -> {
                // Moderate cleanup
                trimMemory(aggressive = false)
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // System is critically low on memory - aggressive cleanup
        trimMemory(aggressive = true)
    }

    private suspend fun initializeReticulum() {
        try {
            val configDir = config.configDir ?: File(filesDir, "reticulum").absolutePath

            // Ensure config directory exists
            File(configDir).mkdirs()

            // Configure Transport for coroutine-based job loop on Android
            // Use 250ms loop (matches Python) with battery-adjusted intervals for expensive operations
            network.reticulum.transport.Transport.configureCoroutineJobLoop(
                scope = serviceScope,
                intervalMs = network.reticulum.transport.TransportConstants.JOB_INTERVAL, // 250ms
                tablesCullIntervalMs = config.getEffectiveTablesCullInterval(),
                announcesCheckIntervalMs = config.getEffectiveAnnouncesCheckInterval()
            )

            // Check if another shared instance is already running
            val sharedInstanceExists = Reticulum.isSharedInstanceRunning(config.sharedInstancePort)

            reticulum = Reticulum.start(
                configDir = configDir,
                enableTransport = config.enableTransport,
                shareInstance = config.shareInstance && !sharedInstanceExists,
                sharedInstancePort = config.sharedInstancePort,
                connectToSharedInstance = sharedInstanceExists
            )

            // If shareInstance is enabled but server hasn't started yet,
            // set up factories and start the local server now
            reticulum?.let { rns ->
                if (config.shareInstance && !sharedInstanceExists && !rns.isSharedInstance) {
                    // Start the local server manually
                    startLocalServer(rns, config.sharedInstancePort)
                }
            }

            // When transport is enabled, schedule WorkManager for periodic maintenance
            // This survives Doze mode and app backgrounding
            if (config.enableTransport) {
                ReticulumWorker.schedule(this@ReticulumService, intervalMinutes = 15)
            }

            val statusText = when {
                reticulum?.isSharedInstance == true -> "Shared Instance (port ${config.sharedInstancePort})"
                reticulum?.isConnectedToSharedInstance == true -> "Connected to shared instance"
                else -> "Connected"
            }
            updateNotification(statusText)
        } catch (e: Exception) {
            updateNotification("Error: ${e.message}")
        }
    }

    private fun startLocalServer(rns: Reticulum, port: Int) {
        try {
            val server = LocalServerInterface(name = "SharedInstance", tcpPort = port)

            // Set up packet callback to forward to Transport
            server.onPacketReceived = { data, fromInterface ->
                network.reticulum.transport.Transport.inbound(
                    data,
                    network.reticulum.interfaces.InterfaceAdapter.getOrCreate(fromInterface)
                )
            }

            server.start()
            network.reticulum.transport.Transport.registerInterface(
                network.reticulum.interfaces.InterfaceAdapter.getOrCreate(server)
            )

            // Monitor and register spawned client interfaces
            serviceScope.launch {
                val registeredClients = mutableSetOf<network.reticulum.interfaces.Interface>()
                while (this.isActive) {
                    val spawned = server.spawnedInterfaces ?: emptyList()
                    for (client in spawned) {
                        if (!registeredClients.contains(client)) {
                            network.reticulum.transport.Transport.registerInterface(
                                network.reticulum.interfaces.InterfaceAdapter.getOrCreate(client)
                            )
                            registeredClients.add(client)
                            android.util.Log.i("ReticulumService", "Registered spawned client interface: ${client.name}")
                        }
                    }
                    // Remove disconnected clients
                    registeredClients.retainAll(spawned.toSet())
                    kotlinx.coroutines.delay(1000) // Check every second
                }
            }

            // Update the Reticulum instance state via reflection (since isSharedInstance is private set)
            try {
                val isSharedField = rns::class.java.getDeclaredField("isSharedInstance")
                isSharedField.isAccessible = true
                isSharedField.setBoolean(rns, true)

                // Also set sharedInterface so getSharedInstanceClientCount() works
                val sharedInterfaceField = rns::class.java.getDeclaredField("sharedInterface")
                sharedInterfaceField.isAccessible = true
                sharedInterfaceField.set(rns, server)
            } catch (e: Exception) {
                // Ignore if reflection fails
            }

            android.util.Log.i("ReticulumService", "Started shared instance server on port $port")
        } catch (e: Exception) {
            android.util.Log.e("ReticulumService", "Failed to start shared instance server: ${e.message}")
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
        try {
            if (aggressive) {
                // Critical memory situation - aggressive cleanup
                network.reticulum.transport.Transport.aggressiveTrimMemory()
            } else {
                // Moderate memory pressure - normal trim
                network.reticulum.transport.Transport.trimMemory()
            }
        } catch (e: Exception) {
            // Don't crash on memory trim errors
        }
    }

    /**
     * Get current memory statistics.
     * Useful for monitoring and debugging memory usage.
     */
    fun getMemoryStats(): network.reticulum.transport.Transport.MemoryStats? {
        return try {
            network.reticulum.transport.Transport.getMemoryStats()
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        NotificationChannels.createChannels(this)
    }

    private fun createNotification(status: String = "Starting..."): Notification {
        val modeText = if (config.enableTransport) "Transport" else "Client"

        return NotificationCompat.Builder(this, NotificationChannels.SERVICE_CHANNEL_ID)
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
        private const val TAG = "ReticulumService"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_CONFIG = "config"

        /**
         * Start the Reticulum service with default client-only configuration.
         */
        fun start(context: Context) {
            start(context, ReticulumConfig.DEFAULT)
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
