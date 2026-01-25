# Pitfalls Research: Android Background Networking

**Domain:** Always-connected background networking on Android (LXMF messaging)
**Researched:** 2026-01-24
**Overall Confidence:** HIGH

## Executive Summary

Implementing always-connected background networking on Android is a minefield of edge cases, aggressive OEM battery optimizations, and evolving platform restrictions. The core challenge: maintaining TCP connections during Doze mode while navigating Android 14-16 foreground service restrictions, OEM-specific battery killers, and user-facing notification policies.

**Critical insight:** Foreground services are the only viable approach for maintaining persistent TCP connections, but they come with strict time limits (6 hours per 24h for dataSync type in Android 15+), mandatory notification persistence, and aggressive OEM-specific killing mechanisms that override standard Android behavior.

---

## Critical Pitfalls

Mistakes that cause rewrites or major production issues.

### Pitfall 1: Assuming Foreground Services Exempt from Doze Restrictions

**What goes wrong:** Developers believe that foreground services bypass all Doze mode restrictions. They discover in production that TCP connections still drop when the device enters deep Doze, causing message delivery failures.

**Why it happens:**
- Official documentation states "foreground services continue to run" during Doze
- This is technically true (the service process isn't killed)
- However, network access is still suspended during Doze, breaking TCP sockets
- Wake locks are ignored during Doze, preventing CPU from maintaining connections

**Consequences:**
- TCP connections silently close during Doze
- Reconnection attempts fail because network access is suspended
- Messages queue indefinitely until maintenance windows (9+ minutes apart)
- Users experience "offline" behavior despite having network connectivity

**Prevention:**
1. Design for connection loss and reconnection as the default state
2. Use `setAndAllowWhileIdle()` or `setExactAndAllowWhileIdle()` alarms to wake during Doze (max once per 9 minutes)
3. Consider hybrid approach: FCM for waking app, then establish direct TCP connection
4. Implement exponential backoff for reconnection attempts during Doze
5. Monitor `ConnectivityManager.NetworkCallback` to detect network availability changes

**Detection:**
- Test with `adb shell dumpsys deviceidle force-idle`
- Monitor connection state during forced Doze
- Check for `SocketException` or `SocketTimeoutException` in logs
- Verify reconnection attempts happen during maintenance windows (not continuously)

**Sources:**
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Qt Forum: Sending keep-alive Messages on Android in Doze mode](https://forum.qt.io/topic/90939/sending-keep-alive-messages-on-android-even-if-the-device-is-in-sleep-doze-mode)
- [Microsoft Q&A: WebSocket Connection Stability Issues](https://learn.microsoft.com/en-us/answers/questions/2281522/intermittent-availability-issues-with-acs-sdk-in-a)

### Pitfall 2: Wrong Foreground Service Type for Always-On Connections

**What goes wrong:** Developers choose `dataSync` foreground service type for maintaining persistent connections. After 6 hours, the service is forcefully terminated with `RemoteServiceException`, causing the app to crash.

**Why it happens:**
- `dataSync` sounds appropriate for "data synchronization over network"
- Android 15+ imposes strict 6-hour-per-24h time limit on `dataSync` services
- Limit is cumulative across all app's dataSync services
- Timer only resets when user brings app to foreground
- Most documentation examples use `dataSync` for network operations

**Consequences:**
- Service crashes after 6 hours with fatal `RemoteServiceException`
- User must manually open app to reset timer
- No reliable way to maintain 24/7 connection
- Connection drops occur at unpredictable times (after 6h cumulative runtime)

**Prevention:**
1. For always-on connections, consider `shortService` type (Android 15+) for brief network checks
2. For messaging apps, evaluate if `connectedDevice` type fits use case (Bluetooth/USB companion devices)
3. Avoid `dataSync` unless you can genuinely complete work within 6 hours
4. Implement service lifecycle monitoring: log when `onTimeout()` is called
5. Consider splitting architecture: long-lived service for listening, short-lived tasks for sending

**Detection:**
- Monitor Logcat for: `"A foreground service of type [service type] did not stop within its timeout"`
- Test with services running >6 hours in background
- Check for `ForegroundServiceStartNotAllowedException` when timer exhausted
- Implement telemetry to track service uptime vs. crashes

**Warning signs:**
- Service stops unexpectedly after ~6 hours
- `onTimeout(int, int)` callback fired
- Users report "connection lost" after extended periods

**Sources:**
- [Foreground Service Timeouts (Android Developers)](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Changes to Foreground Services (Android Developers)](https://developer.android.com/develop/background-work/services/fgs/changes)
- [Behavior Changes: Apps Targeting Android 15](https://developer.android.com/about/versions/15/behavior-changes-15)

### Pitfall 3: Notification Channel Importance Too Low

**What goes wrong:** Developer creates notification with `IMPORTANCE_LOW` or `IMPORTANCE_MIN` to be "respectful" of user attention. Users swipe away the notification (Android 13+), which stops the foreground service, terminating all connections.

**Why it happens:**
- Starting Android 13, foreground service notifications are user-dismissible by default
- Low importance channels don't alert users, making notifications "invisible"
- Users see ongoing notification in status bar, assume it's safe to dismiss
- Dismissing notification can trigger service shutdown if not handled properly
- Developers want to minimize intrusion, so they choose low importance

**Consequences:**
- Users dismiss notification, unintentionally killing the service
- Connection drops without user understanding why
- App appears "broken" because dismissing notification stops core functionality
- No clear UI signal that dismissal affects app operation

**Prevention:**
1. Use `IMPORTANCE_DEFAULT` or `IMPORTANCE_HIGH` to ensure notification visibility
2. Write clear notification text explaining why it must persist: "Keeping you connected"
3. Implement `deleteIntent` on notification to detect dismissal and recreate notification:
   ```kotlin
   val deleteIntent = Intent(context, NotificationDismissedReceiver::class.java)
   val deletePendingIntent = PendingIntent.getBroadcast(context, 0, deleteIntent, PendingIntent.FLAG_IMMUTABLE)
   notification.setDeleteIntent(deletePendingIntent)
   ```
4. In `NotificationDismissedReceiver`, immediately recreate notification or prompt user
5. Set `setOngoing(true)` for Android <13 (though this doesn't prevent dismissal on 13+)
6. Educate users in onboarding: "Don't dismiss the connection notification"

**Detection:**
- Monitor notification dismissal via `deleteIntent` broadcasts
- Track correlation between notification dismissals and connection drops
- Test on Android 13+ devices by dismissing notification

**Warning signs:**
- Service stops shortly after user swipes notification away
- Users report connection issues after "clearing notifications"
- Service lifecycle logs show stop after notification dismissal

**Sources:**
- [Native Mobile Applications: Foreground Service](https://courses.taltech.akaver.com/native/lectures/foreground_service)
- [Persisting FGS Notifications on Newer Android Versions](https://developer.zebra.com/blog/persisting-fgs-notifications-newer-android-versions)
- [How to Dismiss Running App Notifications in Android 13](https://www.geeksforgeeks.org/android/how-to-dismiss-running-app-notifications-in-android-13/)

### Pitfall 4: Missing POST_NOTIFICATIONS Permission Check (Android 13+)

**What goes wrong:** App targets Android 13+ but doesn't request `POST_NOTIFICATIONS` permission. Foreground service starts successfully, but notification doesn't appear in notification drawer. User has no way to access the app or understand it's running. Background service gets terminated by system due to "invisible" status.

**Why it happens:**
- Android 13 made notifications opt-in via runtime permission
- Foreground services don't require `POST_NOTIFICATIONS` to start (they show in Task Manager)
- Developer assumes foreground service notifications bypass permission check
- Default state for new installs on Android 13+ is "notifications off"
- Users who deny permission still allow service to start, but notification is invisible

**Consequences:**
- Notification doesn't appear in drawer, only in Task Manager
- User can't interact with app via notification tap
- System may treat service as low priority without visible notification
- Users don't understand app is running in background
- If user hasn't granted permission and app runs foreground service, service may be blocked on subsequent starts

**Prevention:**
1. Request `POST_NOTIFICATIONS` permission before starting foreground service (Android 13+):
   ```kotlin
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
       if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
           != PackageManager.PERMISSION_GRANTED) {
           // Request permission
           ActivityCompat.requestPermissions(activity,
               arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
       }
   }
   ```
2. Handle permission denial gracefully: explain to user why notification is needed
3. Add permission to manifest: `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
4. Test on fresh Android 13+ install (permissions default to denied)
5. Implement fallback behavior if permission denied (e.g., don't start service, prompt user)

**Detection:**
- Check permission status before starting service
- Monitor notification visibility after service start
- Test on Android 13+ with permission denied
- Check Task Manager vs notification drawer discrepancy

**Warning signs:**
- Service starts but no notification visible in drawer
- Users report "can't find the app" when it's running
- Analytics show service running but low notification interaction rate

**Sources:**
- [Notification Runtime Permission (Android Developers)](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Android 13 Notification Runtime Permission](https://medium.com/@shaikabdullafaizal/android-13-notification-runtime-permission-f91bec2fc256)
- [Understanding and Implementing Foreground Services in Android](https://medium.com/@RhoumaMaher/understanding-and-implementing-foreground-services-in-android-2e1e3fc234ce)

### Pitfall 5: Not Declaring Foreground Service Type in Manifest

**What goes wrong:** Developer starts foreground service with `startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` but forgets to declare type in `AndroidManifest.xml`. App crashes immediately with `MissingForegroundServiceTypeException`.

**Why it happens:**
- Android 14+ requires explicit type declaration in both manifest AND code
- Older code examples don't include manifest declaration (pre-Android 14)
- IDE doesn't always warn about missing manifest entries
- Developer tests on emulator with older API level, doesn't catch issue until production

**Consequences:**
- App crashes immediately when calling `startForeground()`
- Users on Android 14+ devices experience complete service failure
- No graceful degradation or fallback
- Stack traces in production pointing to `startForeground()` call

**Prevention:**
1. Always declare service type in manifest for Android 14+:
   ```xml
   <service
       android:name=".ReticulumConnectionService"
       android:foregroundServiceType="dataSync"
       android:enabled="true"
       android:exported="false">
   </service>
   ```
2. Add corresponding permission to manifest:
   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
   ```
3. Match type in `startForeground()` call:
   ```kotlin
   startForeground(NOTIFICATION_ID, notification,
       ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
   ```
4. Test on Android 14+ physical device or emulator
5. Use lint checks: `SpecifyForegroundServiceType` warns about missing types

**Detection:**
- Crash on service start: `MissingForegroundServiceTypeException`
- Test on Android 14+ device
- Lint warnings in Android Studio

**Warning signs:**
- Crash immediately on `startForeground()` call
- Exception message: "Starting FGS without a type"
- Only affects Android 14+ devices

**Sources:**
- [Foreground Service Types Are Required (Android Developers)](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Foreground Services in Android 14: What's Changing?](https://proandroiddev.com/foreground-services-in-android-14-whats-changing-dcd56ad72788)
- [Why Android 14's Foreground Service Requirements Might Break Your App](https://medium.com/gravel-engineering/why-android-14s-foreground-service-requirements-might-break-your-app-and-how-to-fix-it-c1cbcf469b69)

---

## Moderate Pitfalls

Mistakes that cause delays, technical debt, or production issues on some devices.

### Pitfall 6: Using WorkManager for Always-On Connections

**What goes wrong:** Developer uses WorkManager with `setForeground()` (long-running worker) to maintain persistent connection. In Android 16+, worker exhausts job quota and gets terminated during extended operation, despite running as foreground service.

**Why it happens:**
- WorkManager documentation suggests using `setForeground()` for long-running work
- Prior to Android 16, foreground services bypassed job execution quotas
- Android 16 changed behavior: jobs running during foreground service now count toward quota
- Job quota varies by App Standby Bucket (restricted bucket = severe limits)
- WorkManager is designed for deferrable tasks, not 24/7 operations

**Consequences:**
- Worker stops unexpectedly when quota exhausted
- No clear error message (just stops executing)
- Behavior inconsistent across Android versions (works on <16, fails on 16+)
- Connection drops without obvious cause

**Prevention:**
1. Don't use WorkManager for always-on connections—use traditional `Service` with `startForeground()`
2. Reserve WorkManager for periodic/deferrable tasks (syncing message history, updating contacts)
3. If using WorkManager, monitor `WorkInfo.getStopReason()` to detect quota exhaustion
4. Test on Android 16+ with app in restricted standby bucket
5. Use `JobScheduler#getPendingJobReasonsHistory` API (Android 16+) to debug job execution issues

**Detection:**
- Worker stops after extended runtime on Android 16+
- `WorkInfo.getStopReason()` returns quota-related reason
- Check standby bucket: `adb shell am get-standby-bucket <package>`

**Warning signs:**
- Worker runs successfully initially, then stops after hours/days
- Behavior differs between Android 15 and 16
- Issue worsens when app is in restricted/rare standby bucket

**Sources:**
- [Support for Long-Running Workers (Android Developers)](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [Behavior Changes: All Apps (Android 16)](https://developer.android.com/about/versions/16/behavior-changes-all)
- [Foreground Service vs WorkManager in Android](https://medium.com/@amar90aqi/foreground-service-vs-workmanager-in-android-choosing-the-right-tool-for-background-tasks-32c1242f9898)

### Pitfall 7: Ignoring App Standby Buckets

**What goes wrong:** App gets placed in "restricted" standby bucket due to excessive resource usage or user behavior. Network access is completely blocked for background operations, even with foreground service running. App appears "broken" to users.

**Why it happens:**
- System automatically assigns apps to standby buckets based on usage patterns
- Restricted bucket introduced in Android 11, active by default in Android 12+
- Apps in restricted bucket cannot access network except when in foreground
- OEMs may aggressively assign apps to restricted bucket
- Developers don't test with app in restricted bucket

**Consequences:**
- Network access blocked entirely for background operations
- Foreground service runs but can't access network
- TCP connections fail to establish or maintain
- Only one alarm permitted per day in restricted bucket
- Job execution severely limited

**Prevention:**
1. Test app behavior in each standby bucket, especially restricted:
   ```bash
   adb shell am set-standby-bucket <package> restricted
   adb shell am get-standby-bucket <package>
   ```
2. Monitor standby bucket at runtime and warn user if restricted:
   ```kotlin
   val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
   val bucket = usageStatsManager.appStandbyBucket
   if (bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) {
       // Warn user, provide guidance to unrestrict
   }
   ```
3. Provide user guidance to remove battery restrictions:
   - Settings → Apps → Your App → Battery → Unrestricted
4. Avoid behaviors that trigger restricted bucket (excessive wake locks, network usage)
5. Request user to exempt app from battery optimization (use judiciously)

**Detection:**
- Check bucket programmatically: `usageStatsManager.appStandbyBucket`
- Test with: `adb shell am set-standby-bucket <package> restricted`
- Monitor network failures correlated with bucket assignment

**Warning signs:**
- Network access fails only when app in background
- Works fine when app is foregrounded
- Issue appears after app runs for extended period
- More common on budget/OEM devices

**Sources:**
- [App Standby Buckets (Android Developers)](https://developer.android.com/topic/performance/appstandby)
- [Understanding Android App Standby Buckets](https://softaai.com/understanding-android-app-standby-buckets-resource-limits-job-execution-and-best-practices/)
- [App Standby Buckets In Android](https://medium.com/mindorks/app-standby-buckets-in-android-ada2d2929350)

### Pitfall 8: Not Handling VPN Changes

**What goes wrong:** User enables VPN while app is connected. Network interface changes, TCP socket binds to wrong interface or loses connectivity. Connection appears "stuck" until app is restarted.

**Why it happens:**
- VPN creates new network interface
- Existing TCP sockets may remain bound to old interface
- Android doesn't automatically migrate active connections
- `ConnectivityManager` callbacks fire but app doesn't respond
- Developer doesn't test with VPN enabled/disabled during operation

**Consequences:**
- TCP connection becomes unresponsive when VPN activated
- Data sent to wrong interface (leaks outside VPN)
- Connection hangs until manual restart
- Security implications: traffic may bypass VPN unexpectedly

**Prevention:**
1. Register `ConnectivityManager.NetworkCallback` to detect network changes:
   ```kotlin
   val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
   val networkCallback = object : ConnectivityManager.NetworkCallback() {
       override fun onAvailable(network: Network) {
           // Reconnect using new network
       }
       override fun onLost(network: Network) {
           // Handle network loss
       }
       override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
           // VPN state may have changed
       }
   }
   connectivityManager.registerDefaultNetworkCallback(networkCallback)
   ```
2. Close and reconnect TCP socket when network changes
3. Use `Network.bindSocket()` to explicitly bind socket to active network
4. Test with VPN enabled/disabled during active connection
5. Monitor VPN state via `NetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)`

**Detection:**
- Test by enabling/disabling VPN during connection
- Monitor `NetworkCallback.onCapabilitiesChanged()` events
- Check if socket remains responsive after VPN change

**Warning signs:**
- Connection hangs after VPN activation
- Traffic doesn't route through VPN as expected
- Users report "connection stuck" after network changes

**Sources:**
- [ConnectivityManager.NetworkCallback (Android Developers)](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- [Read Network State (Android Developers)](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
- [Monitor Connectivity Status and Connection Metering](https://developer.android.com/training/monitoring-device-state/connectivity-status-type)

### Pitfall 9: Battery Optimization "Whitelisting" Without User Consent

**What goes wrong:** App automatically requests battery optimization exemption without clear user consent or explanation. Google Play rejects app for policy violation. Even if approved, users distrust app and uninstall.

**Why it happens:**
- Developer wants app to work reliably, so requests exemption programmatically
- Believes exemption is necessary for functionality
- Doesn't understand Play Store policy around battery optimization
- Skips user education/consent flow

**Consequences:**
- App rejected from Play Store for policy violation
- User distrust: "Why does this app need special battery access?"
- Negative reviews citing battery drain
- OEMs may ignore exemption anyway (see OEM section)

**Prevention:**
1. Only request battery optimization exemption if absolutely necessary
2. Provide clear in-app explanation before requesting:
   - "To keep you connected 24/7, we need to prevent Android from limiting background activity"
3. Use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent (not `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`):
   ```kotlin
   val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
       data = Uri.parse("package:$packageName")
   }
   startActivity(intent)
   ```
4. Implement graceful degradation if user denies exemption
5. Document in Play Store listing why exemption is needed
6. Test app behavior both with and without exemption

**Detection:**
- Monitor exemption status: `PowerManager.isIgnoringBatteryOptimizations(packageName)`
- Track analytics: correlation between exemption status and connection reliability
- Review Play Store policy compliance

**Warning signs:**
- Users report unexpected battery drain
- Negative reviews mentioning battery usage
- Play Store warnings/rejections

**Sources:**
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Set a Wake Lock](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set)
- [Excessive Partial Wake Locks](https://developer.android.com/topic/performance/vitals/wakelock)

---

## OEM-Specific Issues

Manufacturer-imposed restrictions that override standard Android behavior.

### Samsung

**Status in 2026:** Officially improved since One UI 6.0, but workarounds still necessary.

**Key Issues:**

1. **"Put Unused Apps to Sleep" Feature**
   - **What happens:** Even if battery optimization disabled, Samsung will put apps to sleep after 3 days of no user interaction
   - **Detection:** App stops functioning after 72 hours without foreground interaction
   - **Workaround:**
     - Users must add app to "Never sleeping apps" list
     - Path: Settings → Battery and device care → Battery → Background usage limits → Never sleeping apps
     - Guide users through this in onboarding

2. **Battery Optimization Override**
   - **What happens:** Samsung's "Device Care" can override standard Android battery optimization settings
   - **Detection:** App stops despite battery optimization exemption granted
   - **Workaround:**
     - Settings → Apps → Your App → Battery → Optimize battery usage → All apps → Your app (toggle off)
     - Ensure "Put unused apps to sleep" is disabled globally

3. **One UI 6.0+ Improvements**
   - Samsung officially promised to honor foreground service behavior for apps targeting Android 14+
   - Residual issues remain; thorough testing required

**Prevention Strategy:**
- Implement in-app detection for Samsung devices: `Build.MANUFACTURER.equals("samsung", ignoreCase = true)`
- Show Samsung-specific guidance during onboarding
- Deep-link to battery settings: `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`
- Test on multiple Samsung models (S series, A series) with One UI 5.x and 6.x

**Sources:**
- [Samsung - Don't Kill My App](https://dontkillmyapp.com/samsung)
- [Samsung Tops Battery Management Chart (Android Authority)](https://www.androidauthority.com/samsung-dontkillmyapp-battery-management-1201603/)

### Xiaomi / MIUI

**Status in 2026:** Most problematic manufacturer. Aggressive background killing continues.

**Key Issues:**

1. **Autostart Permission**
   - **What happens:** Apps are prevented from starting in background unless explicitly granted Autostart permission
   - **Detection:** Service doesn't start after device reboot or when triggered by alarm
   - **Workaround:**
     - Users must enable Autostart: Settings → Apps → Manage apps → Your app → Autostart (enable)
     - This is separate from standard Android permissions

2. **Background Autostart Permission (MIUI 14+)**
   - **What happens:** MIUI 14 added new "Background autostart" permission separate from regular Autostart
   - **Detection:** Service stops when app moves to background
   - **Workaround:**
     - Settings → Apps → Your app → App permissions → Background autostart (enable)
     - Both Autostart AND Background autostart must be enabled

3. **Battery Saver Override**
   - **What happens:** MIUI's battery saver kills apps even with all restrictions disabled
   - **Detection:** App killed unpredictably, especially when screen off
   - **Workaround:**
     - Settings → Battery & performance → App battery saver → Your app → No restrictions
     - Lock app in recent tasks (swipe down on app card in recent apps)

4. **"X" Button Killing All Services**
   - **What happens:** Using "X" button in recent apps to clear all tasks stops all foreground services
   - **Detection:** Service stops when user clears recent apps
   - **Workaround:**
     - Educate users: "Use back button to exit, not recent apps clear"
     - Lock app in recent tasks to prevent accidental clearing

**Prevention Strategy:**
- Detect MIUI: Check for `ro.miui.ui.version` system property
- Show MIUI-specific onboarding flow with step-by-step screenshots
- Provide in-app "Fix Connection Issues" wizard that checks each setting
- Test on Redmi, Poco, and Mi devices (all use MIUI)
- Consider using `dontkillmyapp.com/xiaomi` as reference in documentation

**Sources:**
- [Xiaomi - Don't Kill My App](https://dontkillmyapp.com/xiaomi)
- [How to Manage Autostart Service on Xiaomi Devices](https://nine-faq.9folders.com/articles/8772-how-to-manage-autostart-service-on-the-xiaomi-devices)
- [Xiaomi Phone Closes Background Apps? Here's How to Fix](https://appuals.com/xiaomi-phone-closes-background-apps/)
- [How to Run Background Service in Xiaomi Mobiles](https://www.freakyjolly.com/how-to-run-background-service-in-xiaomi-mobiles/)

### Huawei / EMUI & HarmonyOS

**Status in 2026:** Ranked #1 most problematic by dontkillmyapp.com (except Nexus 6P).

**Key Issues:**

1. **Protected Apps**
   - **What happens:** Apps not in "Protected apps" list are killed when screen turns off
   - **Detection:** App stops immediately when screen locks
   - **Workaround:**
     - Settings → Battery → Protected apps → Enable for your app
     - Different path on HarmonyOS devices

2. **Launch Permissions**
   - **What happens:** Similar to Xiaomi's Autostart, apps need explicit launch permission
   - **Detection:** Service doesn't start in background
   - **Workaround:**
     - Settings → Apps → Launch → Manage manually → Enable all toggles

3. **HarmonyOS Specifics**
   - HarmonyOS devices have different settings paths
   - Extra layer of "Performance mode" that kills background apps
   - Test on both EMUI and HarmonyOS

**Prevention Strategy:**
- Detect Huawei: `Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)`
- Detect HarmonyOS: Check system property `hw_sc.build.platform.version`
- Provide manufacturer-specific guidance
- Test on P-series, Mate-series, and HarmonyOS devices

**Sources:**
- [Don't Kill My App](https://dontkillmyapp.com/)
- [How to Protect AdGuard from Being Disabled](https://adguard.com/kb/adguard-for-android/solving-problems/background-work/)

### OnePlus / OxygenOS

**Status in 2026:** Ranked #3 most problematic.

**Key Issues:**

1. **Battery Optimization Override**
   - OnePlus adds own battery optimization layer
   - Standard exemption doesn't work reliably

2. **Recent Apps Cleanup**
   - Aggressive memory management kills background apps
   - Affects newer OxygenOS versions more than older

**Prevention Strategy:**
- Settings → Battery → Battery optimization → Your app → Don't optimize
- Settings → Apps → Your app → Advanced → Battery → Battery optimization → Don't optimize
- Lock app in recent apps

**Sources:**
- [Don't Kill My App](https://dontkillmyapp.com/)

### Oppo / ColorOS & Realme / Realme UI

**Key Issues:**

1. **Startup Manager**
   - Apps need explicit permission to run in background
   - Path: Settings → Security → Startup Manager

2. **Battery Optimization**
   - Multiple layers of battery restrictions
   - Similar to Xiaomi's approach

**Prevention Strategy:**
- Detect Oppo: `Build.MANUFACTURER.contains("OPPO", ignoreCase = true)`
- Provide manufacturer-specific instructions

### Vivo / FunTouch OS

**Key Issues:**

1. **iManager Restrictions**
   - Vivo's iManager app controls background behavior
   - Users must whitelist app in iManager

**Prevention Strategy:**
- Guide users through iManager settings
- Test on Vivo devices

### General OEM Strategy

**Universal Workarounds:**
1. Implement manufacturer detection at runtime
2. Show device-specific onboarding instructions
3. Provide in-app "troubleshooting wizard" that checks:
   - Battery optimization status
   - Autostart permissions (if applicable)
   - Recent apps lock status
   - App standby bucket
4. Deep-link to relevant settings pages
5. Use `dontkillmyapp.com` as reference resource
6. Test on top 10 most popular devices in target markets

**Detection Library:**
```kotlin
fun isProblematicOEM(): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    return manufacturer in listOf("xiaomi", "huawei", "oppo", "vivo", "realme", "oneplus")
}

fun getOEMGuidanceUrl(): String {
    val manufacturer = Build.MANUFACTURER.lowercase()
    return "https://dontkillmyapp.com/$manufacturer"
}
```

---

## Testing Pitfalls

Common mistakes when testing background networking behavior.

### Pitfall 10: Only Testing on Emulator

**What goes wrong:** App works perfectly on Android Emulator but fails on physical devices, especially OEM devices.

**Why it happens:**
- Emulator uses stock Android (no OEM modifications)
- Emulator doesn't enforce aggressive battery optimization
- Doze mode behavior differs between emulator and real devices
- Network conditions more stable on emulator

**Prevention:**
1. Test on physical devices from top OEMs: Samsung, Xiaomi, Oppo, OnePlus
2. Test across Android versions: 8.0 (API 26) through latest
3. Use Firebase Test Lab or similar cloud testing for broad device coverage
4. Implement telemetry to track connection stability by device model

**Sources:**
- [Test Power-Related Issues](https://developer.android.com/topic/performance/power/test-power)

### Pitfall 11: Not Testing Doze Mode Transitions

**What goes wrong:** Developer tests app while connected to ADB, which prevents Doze mode from activating. Production users experience connection drops that never occurred in testing.

**Why it happens:**
- ADB connection prevents device from entering Doze
- Developer unplugs device but doesn't wait long enough (Doze takes 30+ minutes)
- Testing while charging (Doze doesn't activate)
- Not using ADB commands to force Doze

**Prevention:**
1. Use ADB commands to force Doze mode during testing:
   ```bash
   # Unplug device first (or simulate)
   adb shell dumpsys battery unplug

   # Enable Doze (may be needed on emulator)
   adb shell dumpsys deviceidle enable

   # Force idle mode
   adb shell dumpsys deviceidle force-idle

   # Check state
   adb shell dumpsys deviceidle get deep

   # Exit Doze
   adb shell dumpsys deviceidle unforce
   adb shell dumpsys battery reset
   ```

2. Test complete Doze progression:
   ```bash
   # Step through Doze states gradually
   adb shell dumpsys deviceidle step
   # Run multiple times to progress through states
   ```

3. Test App Standby as well:
   ```bash
   adb shell dumpsys battery unplug
   adb shell am set-inactive <package> true
   adb shell am get-inactive <package>
   ```

4. Monitor network callbacks during Doze:
   ```kotlin
   Log.d(TAG, "Network available: ${isNetworkAvailable()}")
   ```

5. Test maintenance window behavior (9+ minute intervals)

**Sources:**
- [Test Power-Related Issues](https://developer.android.com/topic/performance/power/test-power)
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [ADB Commands to Test Doze Mode](https://gist.github.com/y-polek/febff143df8dd92f4ed2ce4035c99248)
- [Testing Your Android App on Doze Mode](https://medium.com/@mohitgupta92/testing-your-app-on-doze-mode-4ee30ad6a3b0)

### Pitfall 12: Not Testing All App Standby Buckets

**What goes wrong:** App works fine during initial testing (active bucket) but fails in production when system moves app to rare/restricted bucket.

**Why it happens:**
- New installs start in active/working_set bucket
- Developer doesn't manually test other buckets
- Bucket assignment happens gradually based on usage patterns
- Restricted bucket blocks network access entirely for background apps

**Prevention:**
1. Test each bucket manually:
   ```bash
   # Test each bucket
   adb shell am set-standby-bucket <package> active
   adb shell am set-standby-bucket <package> working_set
   adb shell am set-standby-bucket <package> frequent
   adb shell am set-standby-bucket <package> rare
   adb shell am set-standby-bucket <package> restricted

   # Check current bucket
   adb shell am get-standby-bucket <package>
   ```

2. Verify app behavior in restricted bucket (harshest restrictions):
   - Network access blocked for background operations
   - One alarm per day maximum
   - Severe job execution limits

3. Test bucket transitions during active connection

4. Implement runtime monitoring:
   ```kotlin
   val bucket = usageStatsManager.appStandbyBucket
   when (bucket) {
       UsageStatsManager.STANDBY_BUCKET_ACTIVE -> // Minimal restrictions
       UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> // Light restrictions
       UsageStatsManager.STANDBY_BUCKET_FREQUENT -> // Moderate restrictions
       UsageStatsManager.STANDBY_BUCKET_RARE -> // Strong restrictions
       UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> // Severe restrictions
   }
   ```

**Sources:**
- [Test Power-Related Issues](https://developer.android.com/topic/performance/power/test-power)
- [App Standby Buckets](https://developer.android.com/topic/performance/appstandby)

### Pitfall 13: Not Testing Notification Permission Denial

**What goes wrong:** App tested with notifications enabled. Production users deny `POST_NOTIFICATIONS` permission, foreground service starts but notification is invisible, causing user confusion and poor UX.

**Why it happens:**
- Default testing grants all permissions
- Developer doesn't test permission denial flow
- Android 13+ defaults to notifications disabled for new installs

**Prevention:**
1. Test with permission denied:
   ```bash
   adb shell pm revoke <package> android.permission.POST_NOTIFICATIONS
   ```

2. Verify foreground service behavior without notification visibility

3. Test permission request flow:
   - First launch (permission not requested)
   - Permission granted
   - Permission denied
   - Permission denied with "Don't ask again"

4. Implement fallback UI when permission denied

**Sources:**
- [Notification Runtime Permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)

### Pitfall 14: Not Testing with Battery Saver Enabled

**What goes wrong:** App works in testing but users report issues when battery saver is active.

**Why it happens:**
- Battery saver imposes additional restrictions beyond Doze
- Developer doesn't test with battery saver enabled
- Behavior varies by OEM

**Prevention:**
1. Enable battery saver during testing:
   - Settings → Battery → Battery Saver (enable manually)
   - ADB: `adb shell settings put global low_power 1`

2. Test both standard battery saver and OEM-specific modes:
   - Samsung: "Medium" and "Maximum" power saving modes
   - Xiaomi: "Battery saver" in Battery & performance

3. Monitor `PowerManager.isPowerSaveMode()` at runtime

4. Test app behavior when battery saver activates mid-connection

**Sources:**
- [Test Power-Related Issues](https://developer.android.com/topic/performance/power/test-power)

### Pitfall 15: Not Testing Long-Running Scenarios

**What goes wrong:** App tested for 10-30 minutes, works fine. In production, issues appear after 6+ hours (service timeout) or 24+ hours (memory leaks, connection state bugs).

**Why it happens:**
- Short testing cycles don't reveal long-term issues
- Service timeouts occur after hours (Android 15: 6 hours for dataSync)
- Memory leaks accumulate over time
- Connection state management bugs appear after many reconnection cycles

**Prevention:**
1. Run 24-hour+ soak tests:
   - Monitor memory usage over time
   - Track connection state transitions
   - Log all service lifecycle events

2. Test service timeout behavior (Android 15+):
   - Let dataSync service run for >6 hours
   - Verify `onTimeout()` handling

3. Stress test reconnection logic:
   - Force network disconnects every few minutes
   - Cycle through airplane mode
   - Step through Doze maintenance windows

4. Monitor for resource leaks:
   - Use Android Profiler for memory/CPU tracking
   - Check for unclosed sockets, unregistered receivers

5. Automate long-running tests in CI/CD

**Sources:**
- [Foreground Service Timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)

---

## Architecture Pitfalls

Design decisions that create long-term problems.

### Pitfall 16: Tight Coupling Between Service and UI

**What goes wrong:** Service lifecycle tied to Activity/UI lifecycle. Service stops when UI is destroyed, breaking background connections.

**Why it happens:**
- Developer uses bound service pattern for simplicity
- Service lifecycle managed by Activity
- Assumption that UI and connection should share lifecycle

**Consequences:**
- Connection drops when user navigates away
- Service recreated frequently, causing reconnection storms
- State management complexity increases

**Prevention:**
1. Use started service (`startService()`) for connection management
2. Decouple service lifecycle from UI lifecycle
3. Use LiveData/Flow for service → UI communication
4. Keep connection state in service, expose read-only observables to UI
5. Let service outlive Activity lifecycle

### Pitfall 17: Blocking Operations on Main Thread

**What goes wrong:** Network operations (connect, send, receive) performed on main thread. UI freezes, ANR (Application Not Responding) dialogs appear.

**Why it happens:**
- Developer unfamiliar with Android threading model
- Synchronous socket operations used
- Callbacks invoked on main thread

**Prevention:**
1. Perform all network I/O on background threads:
   - Use coroutines with `Dispatchers.IO`
   - Or use dedicated thread pool
2. Never call `socket.connect()`, `socket.getInputStream().read()` on main thread
3. Use StrictMode during development to catch violations:
   ```kotlin
   StrictMode.setThreadPolicy(
       StrictMode.ThreadPolicy.Builder()
           .detectNetwork()
           .penaltyLog()
           .penaltyDeath()
           .build()
   )
   ```

### Pitfall 18: No Exponential Backoff for Reconnection

**What goes wrong:** Connection fails, app immediately retries. Retry loop exhausts battery, floods network, triggers rate limiting.

**Why it happens:**
- Developer implements simple retry logic: "If connection fails, try again"
- No delay between attempts
- No backoff strategy

**Consequences:**
- Battery drain from constant connection attempts
- Network congestion
- Server-side rate limiting blocks app
- Excessive wake locks

**Prevention:**
1. Implement exponential backoff:
   ```kotlin
   var retryDelay = 1000L // Start with 1 second
   val maxDelay = 300000L // Cap at 5 minutes

   fun scheduleReconnect() {
       handler.postDelayed({
           attemptReconnect()
       }, retryDelay)

       retryDelay = min(retryDelay * 2, maxDelay)
   }

   fun onConnected() {
       retryDelay = 1000L // Reset on successful connection
   }
   ```

2. Add jitter to prevent thundering herd
3. Back off more aggressively when in Doze mode
4. Use `setAndAllowWhileIdle()` for alarms during Doze (9+ minute minimum interval)

---

## Recommendations by Phase

**Phase: Background Connectivity Foundation**
- Address Pitfall 1 (Doze mode networking)
- Address Pitfall 5 (manifest declaration)
- Address Pitfall 17 (main thread blocking)
- Address Pitfall 18 (exponential backoff)

**Phase: Foreground Service Implementation**
- Address Pitfall 2 (service type selection)
- Address Pitfall 3 (notification importance)
- Address Pitfall 4 (POST_NOTIFICATIONS)
- Address Pitfall 16 (service/UI coupling)

**Phase: Production Hardening**
- Address Pitfall 7 (standby buckets)
- Address Pitfall 8 (VPN handling)
- Address Pitfall 9 (battery optimization UX)
- Address all OEM-specific issues (Samsung, Xiaomi, Huawei, etc.)

**Phase: Testing & Validation**
- Implement all testing pitfalls (10-15)
- Set up long-running soak tests (Pitfall 15)
- Test on physical devices from each major OEM

---

## Summary of Confidence Levels

| Category | Confidence | Basis |
|----------|-----------|-------|
| Doze Mode Behavior | HIGH | Official Android documentation + verified reports |
| Foreground Service Restrictions (Android 14-16) | HIGH | Official Android documentation (Jan 2026) |
| OEM-Specific Issues | MEDIUM-HIGH | DontKillMyApp.com + recent forum reports (2024-2026) |
| Testing Approaches | HIGH | Official Android documentation |
| Architecture Patterns | MEDIUM | Community best practices + field reports |

---

## Sources

### Official Android Documentation
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Foreground Service Timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Changes to Foreground Services](https://developer.android.com/develop/background-work/services/fgs/changes)
- [Foreground Service Types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground Service Types Are Required (Android 14)](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Behavior Changes: Android 15](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Behavior Changes: Android 16](https://developer.android.com/about/versions/16/behavior-changes-all)
- [Test Power-Related Issues](https://developer.android.com/topic/performance/power/test-power)
- [App Standby Buckets](https://developer.android.com/topic/performance/appstandby)
- [Notification Runtime Permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [ConnectivityManager.NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)

### OEM Resources
- [Don't Kill My App](https://dontkillmyapp.com/)
- [Samsung - Don't Kill My App](https://dontkillmyapp.com/samsung)
- [Xiaomi - Don't Kill My App](https://dontkillmyapp.com/xiaomi)

### Community Resources
- [Foreground Service vs WorkManager in Android](https://medium.com/@amar90aqi/foreground-service-vs-workmanager-in-android-choosing-the-right-tool-for-background-tasks-32c1242f9898)
- [Testing Your Android App on Doze Mode](https://medium.com/@mohitgupta92/testing-your-app-on-doze-mode-4ee30ad6a3b0)
- [Android 13 Notification Runtime Permission](https://medium.com/@shaikabdullafaizal/android-13-notification-runtime-permission-f91bec2fc256)
- [Understanding Android App Standby Buckets](https://softaai.com/understanding-android-app-standby-buckets-resource-limits-job-execution-and-best-practices/)
- [How to Run Background Service in Xiaomi Mobiles](https://www.freakyjolly.com/how-to-run-background-service-in-xiaomi-mobiles/)

### Technical Discussions
- [Qt Forum: Sending keep-alive Messages in Doze Mode](https://forum.qt.io/topic/90939/sending-keep-alive-messages-on-android-even-if-the-device-is-in-sleep-doze-mode)
- [Microsoft Q&A: WebSocket Connection Stability Issues](https://learn.microsoft.com/en-us/answers/questions/2281522/intermittent-availability-issues-with-acs-sdk-in-a)
- [ADB Commands to Test Doze Mode (GitHub Gist)](https://gist.github.com/y-polek/febff143df8dd92f4ed2ce4035c99248)
