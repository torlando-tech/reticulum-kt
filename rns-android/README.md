# Reticulum Android Module

Battery-optimized Android integration for Reticulum mesh networking.

## Features

- **Foreground Service**: Reliable background operation with proper Android lifecycle management
- **Battery Optimization**: Configurable modes for client-only or full routing operation
- **Doze Mode Support**: Proper handling of Android's power-saving states
- **Network Monitoring**: Automatic adaptation to WiFi/cellular/metered connections
- **Memory Efficiency**: ByteArray pooling and adaptive memory management

## Requirements

- Android 8.0+ (API 26+)
- Kotlin 1.9+

## Quick Start

### Add Dependency

```kotlin
// settings.gradle.kts
include(":rns-android")

// app/build.gradle.kts
dependencies {
    implementation(project(":rns-android"))
}
```

### Start the Service

```kotlin
// Start with default client-only mode (lowest battery usage)
ReticulumService.start(context)

// Start with routing mode (mesh participation)
ReticulumService.start(context, ReticulumConfig.ROUTING)

// Stop the service
ReticulumService.stop(context)
```

### Custom Configuration

```kotlin
val config = ReticulumConfig(
    mode = ReticulumConfig.Mode.ROUTING,
    enableTransport = true,
    jobIntervalMs = 60_000,
    batteryOptimization = ReticulumConfig.BatteryMode.BALANCED
)

ReticulumService.start(context, config)
```

## Configuration Options

### Operating Modes

| Mode | Description | Battery Impact |
|------|-------------|----------------|
| `CLIENT_ONLY` | Connect to network, no routing | Minimal |
| `ROUTING` | Full mesh participation | Higher |

### Battery Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `MAXIMUM_BATTERY` | Minimal activity | Low battery, power save mode |
| `BALANCED` | Normal operation | Default |
| `PERFORMANCE` | Maximum responsiveness | Charging, high battery |

## Components

### ReticulumService

Foreground service that manages the Reticulum instance lifecycle.

```kotlin
class MyActivity : AppCompatActivity() {
    private var serviceBinder: ReticulumService.LocalBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            serviceBinder = service as ReticulumService.LocalBinder
            val reticulum = serviceBinder?.getService()?.getReticulum()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBinder = null
        }
    }
}
```

### NetworkMonitor

Monitors connectivity state and adjusts behavior automatically.

```kotlin
val monitor = NetworkMonitor(context)
monitor.setListener(object : NetworkMonitor.NetworkStateListener {
    override fun onNetworkAvailable() { /* ... */ }
    override fun onNetworkLost() { /* ... */ }
    override fun onNetworkTypeChanged(wifi: Boolean, cellular: Boolean, metered: Boolean) {
        // Automatically enables data saver on metered cellular
    }
})
monitor.start()
```

### BatteryMonitor

Tracks battery state for adaptive behavior.

```kotlin
val monitor = BatteryMonitor(context)
monitor.start()

// Get recommended mode based on battery state
val mode = monitor.recommendedBatteryMode()

// Get current info
val info = monitor.getBatteryInfo()
println("Battery: ${info.level}%, charging: ${info.charging}")
```

### DozeHandler

Handles Android Doze mode transitions.

```kotlin
val handler = DozeHandler(context)
handler.setListener(object : DozeHandler.DozeStateListener {
    override fun onDozeStateChanged(inDoze: Boolean) {
        // Adjust behavior for Doze state
    }
})
handler.start()

// Request battery optimization exemption (for routing mode)
handler.requestBatteryOptimizationExemption(activity)
```

## Permissions

The following permissions are declared in the manifest:

```xml
<!-- Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- Notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Battery optimization -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- Auto-start (optional) -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

## Battery Optimization Tips

### For Client-Only Mode
- Uses `CLIENT_ONLY` mode by default
- Network activity only when needed
- Longer job intervals (60+ seconds)
- TCP keep-alive disabled by default

### For Routing Mode
- Request battery optimization exemption for reliable operation
- Use WorkManager for periodic maintenance tasks
- Consider enabling TCP keep-alive for persistent connections
- Monitor battery and adjust behavior dynamically

### Memory Management

The module automatically handles memory pressure:

```kotlin
// In your Application or Service
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    when (level) {
        TRIM_MEMORY_RUNNING_CRITICAL -> Transport.aggressiveTrimMemory()
        TRIM_MEMORY_MODERATE -> Transport.trimMemory()
    }
}
```

## Testing

### Unit Tests
```bash
./gradlew :rns-android:test
```

### Instrumented Tests
```bash
./gradlew :rns-android:connectedAndroidTest
```

### Battery Profiling
Use Android Studio Profiler or Battery Historian:
```bash
adb shell dumpsys batterystats --reset
# Run app for test period
adb bugreport > bugreport.zip
```

## Troubleshooting

### Service killed in background
- Ensure foreground notification is showing
- Request battery optimization exemption
- Check for Doze mode restrictions

### High battery usage
- Switch to `CLIENT_ONLY` mode
- Increase job interval
- Enable data saver mode on cellular

### Memory issues
- Call `Transport.trimMemory()` on low memory
- Reduce hashlist sizes via Platform constants
- Monitor with `Transport.getMemoryStats()`

## License

Same as parent project.
