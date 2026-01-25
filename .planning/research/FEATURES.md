# Feature Landscape: Android Background Networking

**Domain:** Android messaging app background services
**Researched:** 2026-01-24
**Context:** Reticulum-KT v2 - Adding Android production readiness to existing TCP/UDP networking and LXMF messaging implementation

## Executive Summary

Android messaging apps in 2026 must navigate a complex landscape of battery optimization, foreground service types, and user expectations. The table stakes are higher than ever: users expect instant message delivery with zero perceived delay, yet tolerate no battery drain. Google Play policies and Android OS restrictions make this a tightrope walk between functionality and system compliance.

**Key insight:** The ecosystem has converged on FCM (Firebase Cloud Messaging) as the standard solution for most apps, but Reticulum-KT's decentralized mesh architecture requires maintaining persistent connections directly - which is technically allowed but requires careful implementation to avoid battery issues that could trigger Play Store warnings or app removals.

## Table Stakes

Features users expect. Missing any = product feels broken or unreliable.

### Background Message Delivery
**What:** Messages arrive while app is backgrounded, device is locked, or screen is off
**Why expected:** Standard behavior since Android 2.x - any messaging app that doesn't deliver in background feels fundamentally broken
**Complexity:** High
**Notes:**
- Must survive Doze mode (device stationary for 30+ minutes)
- Must survive App Standby (app unused for days)
- Must work across device manufacturers with aggressive battery optimization (Xiaomi, OnePlus, Samsung)
- Standard solution is FCM high-priority messages, but Reticulum's mesh architecture requires persistent TCP/UDP connections
- **Implementation:** Foreground service with `remoteMessaging` type + persistent notification

| Requirement | Status | Implementation |
|-------------|--------|----------------|
| Deliver while backgrounded | Required | Foreground service |
| Survive Doze maintenance windows | Required | Partial wake lock during network operations |
| Handle network interruptions | Required | ConnectivityManager + NetworkCallback |
| Resume after connectivity loss | Required | Automatic reconnection logic |

**Sources:**
- [Foreground service types - Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

### Persistent Notification
**What:** Ongoing notification showing service is active
**Why expected:** Android requirement for foreground services; users expect visibility and control
**Complexity:** Low
**Notes:**
- Required by Android for any foreground service
- Must use MessagingStyle for conversation notifications
- Should indicate connection status (Connected, Connecting, Offline)
- Tappable to open app
- Must have "Stop service" action for user control
- Cannot be dismissed by user swipe (ongoing notification)

**Implementation details:**
- Notification channel: "Background Service" (user-configurable importance)
- Status updates: Connection state, message queue count
- Actions: Open app, Stop service

**Sources:**
- [About notifications - Android Developers](https://developer.android.com/develop/ui/views/notifications)
- [Push notifications Android 2026 best practices](https://www.pushwoosh.com/blog/android-push-notifications/)

### Connection Persistence
**What:** Maintain TCP/UDP connections during sleep, screen-off, and app backgrounding
**Why expected:** Instant message delivery requires open connection to Reticulum mesh
**Complexity:** High
**Notes:**
- Standard approach (FCM) not applicable for Reticulum's decentralized mesh
- Requires foreground service to prevent connection termination
- Must handle network transitions (WiFi ↔ cellular, airplane mode, no signal)
- ConnectivityManager.NetworkCallback for connectivity monitoring
- Automatic reconnection with exponential backoff

**Specific challenges:**
- Doze mode suspends network access (maintenance windows every 15-60 minutes)
- App Standby restricts background activity
- Manufacturer-specific battery optimization can kill connections
- Network transitions require socket recreation

**Sources:**
- [Android persistent socket connection rules](https://copyprogramming.com/howto/android-persistent-socket-connection-rules)
- [Monitor connectivity status - Android Developers](https://developer.android.com/training/monitoring-device-state/connectivity-status-type)

### Offline Message Queue
**What:** Queue outgoing messages when network unavailable, send when connectivity returns
**Why expected:** Users expect "send" to work even offline - app handles delivery asynchronously
**Complexity:** Medium
**Notes:**
- Room database for persistent queue storage
- WorkManager for retry logic with backoff
- Optimistic UI (show as sent immediately, update if fails)
- Sync status per message (pending, sent, failed)
- Automatic delivery when network returns

**Architecture:**
```
User sends message → Save to Room (status=pending) → Update UI (optimistic)
                  → Queue WorkManager job → Send when connected
                  → Update Room (status=sent) → Update UI
```

**Reference implementation:** Signal's deterministic offline queue (90-second delivery after network restoration)

**Sources:**
- [Offline-First Android with Room, WorkManager - Medium](https://medium.com/@vishalpvijayan4/offline-first-android-build-resilient-apps-for-spotty-networks-with-room-datastore-workmanager-4a23144e8ea2)
- [Building Offline-First App - ProAndroidDev](https://proandroiddev.com/offline-apps-its-easier-than-you-think-9ff97701a73f)

### Conversation Notifications
**What:** Message notifications with MessagingStyle, conversation grouping, direct reply
**Why expected:** Android 8.0+ standard; users expect rich notification UX
**Complexity:** Medium
**Notes:**
- MessagingStyle required for Android conversation features
- Notification channels (Messages, Service Status)
- Direct reply inline in notification
- Person objects for sender identity
- Notification grouping for multiple conversations
- POST_NOTIFICATIONS permission (Android 13+)

**Implementation requirements:**
- Separate channel per conversation group (optional)
- Long-lived shortcuts for conversation bubbles
- Smart replies for wearables (optional enhancement)

**Sources:**
- [About notifications and conversations - Android Developers](https://developer.android.com/social-and-messaging/guides/communication/notifications-conversations)
- [Notification channels best practices 2026](https://www.braze.com/resources/articles/what-are-notification-channels-anyway)

### Battery Efficiency
**What:** No excessive battery drain (< 5% per 8 hours while idle)
**Why expected:** Google Play policy enforcement starting March 1, 2026 - excessive wake locks trigger warnings/removal
**Complexity:** High
**Notes:**
- **Critical:** Apps exceeding wake lock threshold may be removed from Play Store discovery or labeled
- Avoid PARTIAL_WAKE_LOCK except during active network operations
- Use SocketKeepalive API for TCP keepalive instead of manual wake locks
- Monitor with Battery Historian
- Release wake locks immediately after operation
- Use WorkManager instead of AlarmManager for deferred work

**Metrics to monitor:**
- Wake lock duration per hour
- CPU time while backgrounded
- Network bytes transferred
- Battery consumption per user session

**Play Store consequences:**
- Warning label on app listing
- Removal from discovery surfaces
- User complaints about battery drain

**Sources:**
- [Wake Locks and ASO Strategy - Gummicube](https://www.gummicube.com/blog/wake-locks-and-how-it-could-change-your-aso-strategy/)
- [Top apps draining battery 2026 - TechLoy](https://www.techloy.com/top-10-apps-draining-your-smartphone-battery-in-2026-and-how-to-stop-them/)

## Differentiators

Features that set Reticulum-KT apart. Not expected, but provide competitive advantage or address unique needs.

### True Offline Mesh Networking
**What:** Messages route through nearby devices when both sender and recipient offline from internet
**Value proposition:** Works without infrastructure - unique among mainstream messaging apps
**Complexity:** High
**Notes:**
- Reticulum's core value - decentralized mesh routing
- Competitive differentiator vs Signal/WhatsApp (require servers)
- Similar to Bridgefy (Bluetooth mesh) but using TCP/UDP + Reticulum protocol
- Requires WiFi Direct or local network for mesh formation
- NOT table stakes - most users never need this, but it's the reason to use Reticulum

**Marketing angle:** "Works when the internet doesn't"

**Sources:**
- [Bridgefy - Most Popular Offline Mesh Messaging App](https://dscnextconference.com/bridgefy-most-popular-offline-mesh-messaging-app/)

### Transparent Network Transition
**What:** Maintain conversation context while switching WiFi ↔ cellular ↔ offline mesh
**Value proposition:** Seamless experience across network types
**Complexity:** High
**Notes:**
- Handle socket recreation on network change
- Resume Resource transfers in progress
- Update UI connection status without disrupting conversation
- Reticulum protocol handles routing changes - KT implementation must preserve connection state

**Technical challenge:** TCP socket must be recreated on IP change, but Reticulum Link must persist

### Propagation Node Sync Status
**What:** Show user when messages are queued vs synced with propagation node
**Value proposition:** Visibility into delivery status for store-and-forward messages
**Complexity:** Low
**Notes:**
- PROPAGATED delivery uses propagation nodes as message stores
- Users benefit from knowing "queued locally" vs "stored at propagation node" vs "delivered"
- Most apps show only "sent" vs "delivered" - adding "synced to relay" provides intermediate state
- Unique to Reticulum's architecture (propagation nodes)

### Resource Transfer Progress
**What:** Show progress for large file/image transfers in notification and UI
**Value proposition:** Users expect progress for large transfers (50KB+ compressed)
**Complexity:** Medium
**Notes:**
- Reticulum Resource protocol handles chunked transfer
- Android notification supports progress bar
- Update notification incrementally (not per chunk - too frequent)
- Show in conversation UI as well

### Connection Resilience Metrics
**What:** Expose connection quality, retry attempts, mesh path changes to user (advanced settings)
**Value proposition:** Power users can diagnose connectivity issues
**Complexity:** Low
**Notes:**
- Helpful for debugging Reticulum mesh issues
- Show: current path, hop count, link quality, retry count
- Developer/advanced user feature
- Similar to Signal's "Connection stats"

## Anti-Features

Features to explicitly NOT build. Common mistakes in Android messaging apps.

### Battery Optimization Exemption Request
**What:** Prompt user to disable battery optimization for app
**Why avoid:** Google Play policy violation unless core function breaks
**What to do instead:**
- Design to work within battery optimization constraints
- Use foreground service with proper type declaration
- Rely on Doze maintenance windows
- Show users how to manually exempt if they experience issues (help docs), but don't prompt

**Policy context:**
> "Google Play policies prohibit apps from requesting direct exemption from Power Management features—Doze and App Standby—in Android 6.0 and above unless the core function of the app is adversely affected."

**Reticulum's case:** Core function (message delivery) should work via foreground service + maintenance windows. Only request exemption if testing proves delivery failures under Doze.

**Sources:**
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Battery optimization - Taming The Droid](https://tamingthedroid.com/battery-optimization-doze-mode)

### Auto-Start on Boot (Without User Action)
**What:** Automatically start foreground service on device boot
**Why avoid:** Surprising behavior, battery drain from services user didn't request
**What to do instead:**
- Start service only when user opens app
- Persist "service enabled" preference
- If user has enabled service previously, restore on boot (RECEIVE_BOOT_COMPLETED)
- Provide toggle in settings: "Start on boot"
- Default: OFF

**User expectation:** Apps don't start themselves unless user explicitly configured them to

**Sources:**
- [Android Auto Launch - PERFSOL](https://perfsol.tech/android-auto-launch)

### Permanent Wake Locks
**What:** Hold PARTIAL_WAKE_LOCK continuously while service running
**Why avoid:** Massive battery drain, Play Store policy violation (March 2026)
**What to do instead:**
- Acquire wake lock only during active network operations
- Release immediately after operation completes
- Use SocketKeepalive API for TCP keepalive
- Let Doze maintenance windows handle periodic sync

**Consequence if violated:** App removal from Play Store discovery, warning label, user complaints

**Sources:**
- [Wake Locks and ASO Strategy](https://www.gummicube.com/blog/wake-locks-and-how-it-could-change-your-aso-strategy/)

### Multiple Persistent Notifications
**What:** Show separate notifications for service, messages, sync status, etc.
**Why avoid:** Notification drawer clutter, user annoyance
**What to do instead:**
- Single persistent notification for foreground service
- Update notification content to show status
- Use MessagingStyle for message notifications (separate from service notification)
- Use notification channels to let users configure importance

**User expectation:** Minimal notification footprint - WhatsApp uses 1 persistent (if "Keep app alive") + message notifications

### Hidden Background Service
**What:** Run foreground service without showing notification (using notification channels set to minimum importance)
**Why avoid:** Android 8.0+ requires visible notification for foreground services
**What to do instead:**
- Show clear, informative persistent notification
- Make it useful: connection status, message queue count
- Allow user to customize importance via notification channels
- Accept that persistent notification is required by platform

**Platform requirement:** Foreground services must have visible notification - attempting to hide it will cause service termination

**Sources:**
- [Effective foreground services on Android](https://android-developers.googleblog.com/2018/12/effective-foreground-services-on-android_11.html)

### Polling for New Messages
**What:** Use AlarmManager or WorkManager to periodically check for new messages
**Why avoid:** Inefficient, delayed delivery, battery drain
**What to do instead:**
- Maintain persistent connection with push-based delivery (Reticulum protocol already works this way)
- Use foreground service to keep connection alive
- Let Reticulum protocol handle incoming packets

**Efficiency comparison:** Persistent connection with event-driven delivery vs polling every 15 minutes

### FCM/Push Service Dependency
**What:** Rely on Firebase Cloud Messaging for message delivery
**Why avoid:** Contradicts Reticulum's decentralized architecture
**What to do instead:**
- Maintain direct TCP/UDP connections to Reticulum mesh
- Use foreground service with remoteMessaging type
- Accept that this requires more battery optimization care than FCM-based apps

**Philosophical alignment:** Reticulum's value is infrastructure independence - adding FCM dependency defeats the purpose

**Sources:**
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

### Aggressive Background Sync
**What:** Sync all message history, attachments, propagation node state in background
**Why avoid:** Network/battery drain, unnecessary during Doze periods
**What to do instead:**
- Sync only new/unsent messages
- Download attachments on-demand (user taps to load)
- Defer propagation node checks to maintenance windows
- Use WorkManager with appropriate constraints (WiFi, charging)

**User expectation:** Background activity should be minimal - intensive operations should be user-initiated or deferred to ideal conditions

### Exact Alarms for Message Retry
**What:** Use setExact() or setExactAndAllowWhileIdle() for message retry scheduling
**Why avoid:** Android 13 restrictions, battery drain, unnecessary precision
**What to do instead:**
- Use WorkManager with exponential backoff
- Let system schedule retry during next maintenance window
- Messages aren't time-critical to the second - 5-minute variance is acceptable

**Android 13 restriction:** Apps must declare SCHEDULE_EXACT_ALARM permission and justify use case

**Sources:**
- [Android 13 exact alarm API restrictions - Esper](https://www.esper.io/blog/android-13-exact-alarm-api-restrictions)

## Feature Dependencies

```
Foreground Service (remoteMessaging type)
    ├─> Persistent Notification (required by platform)
    ├─> Connection Persistence
    │   ├─> ConnectivityManager.NetworkCallback
    │   ├─> SocketKeepalive API (optional, battery optimization)
    │   └─> Automatic reconnection logic
    ├─> Background Message Delivery
    │   ├─> Doze maintenance window handling
    │   └─> Partial wake locks (minimal, only during operation)
    └─> Offline Message Queue
        ├─> Room database (persistent storage)
        ├─> WorkManager (retry logic)
        └─> Optimistic UI updates

Conversation Notifications
    ├─> MessagingStyle (required for conversation features)
    ├─> Notification channels (required Android 8.0+)
    ├─> POST_NOTIFICATIONS permission (required Android 13+)
    └─> Direct reply (optional enhancement)

Battery Efficiency
    ├─> Wake lock monitoring
    ├─> SocketKeepalive API
    └─> WorkManager constraints (WiFi, charging for heavy operations)

Mesh Networking (Reticulum protocol)
    ├─> TCP/UDP interfaces (already implemented in v1)
    ├─> Transport routing (already implemented in v1)
    └─> Network transition handling (v2 requirement)
```

## MVP Recommendation

For v2 Android Production Readiness, prioritize:

**Phase 1: Core Service (Week 1-2)**
1. Foreground service with remoteMessaging type
2. Persistent notification with connection status
3. FOREGROUND_SERVICE_REMOTE_MESSAGING permission declaration
4. Service lifecycle tied to user preference (not auto-start)

**Phase 2: Connection Resilience (Week 2-3)**
5. ConnectivityManager.NetworkCallback for network monitoring
6. Automatic reconnection on network change
7. Graceful degradation (online → offline transitions)
8. Minimal wake lock usage (only during active operations)

**Phase 3: Message Queue (Week 3-4)**
9. Room database for offline queue
10. WorkManager for retry with backoff
11. Optimistic UI updates
12. Sync status per message (pending, sent, delivered)

**Phase 4: Doze Handling (Week 4-5)**
13. Doze maintenance window compatibility testing
14. Battery optimization testing on major OEMs (Samsung, Xiaomi, OnePlus)
15. Battery Historian profiling
16. Wake lock duration optimization

**Phase 5: Notifications (Week 5-6)**
17. MessagingStyle for message notifications
18. Notification channels (Messages, Service, Alerts)
19. Direct reply support
20. POST_NOTIFICATIONS permission request (Android 13+)

**Defer to post-v2:**
- Connection resilience metrics (advanced settings): Low user value, developer tool
- Smart replies for wearables: Nice-to-have, not expected
- Notification bubbles: Android 11+ feature, low adoption
- Resource transfer progress in notification: Can show in UI first, notification later

## Complexity Assessment

| Feature | Complexity | Dependencies | Risk Level | Notes |
|---------|------------|--------------|------------|-------|
| Foreground service with remoteMessaging | Low | Manifest, permission | Low | Well-documented Android feature |
| Persistent notification | Low | NotificationCompat | Low | Standard implementation |
| Connection persistence | High | NetworkCallback, reconnection logic | Medium | Network transitions tricky |
| Offline message queue | Medium | Room, WorkManager | Low | Standard offline-first pattern |
| Doze mode handling | High | Testing on multiple OEMs | High | OEM-specific behavior unpredictable |
| Battery optimization | High | Profiling, iteration | High | Play Store policy enforcement in 2026 |
| Conversation notifications | Medium | MessagingStyle, channels | Low | Well-documented pattern |
| Direct reply | Medium | RemoteInput, PendingIntent | Low | Standard notification action |
| Wake lock management | High | SocketKeepalive, profiling | High | Critical for Play Store compliance |
| Network transition handling | High | Socket recreation, Reticulum state | Medium | Reticulum-specific complexity |
| Resource transfer progress | Medium | Notification updates, Resource protocol | Low | Already have Resource implementation |
| Mesh networking resilience | High | Reticulum protocol, testing | Medium | Core protocol already works (v1) |

**Highest risk items:**
1. **Doze mode handling** - OEM behavior varies wildly (Xiaomi, OnePlus aggressive)
2. **Battery optimization** - March 2026 Play Store policy enforcement
3. **Wake lock management** - Automated detection, can trigger app warnings

**Mitigation strategies:**
- Extensive testing on physical devices (Samsung, Xiaomi, OnePlus, Google Pixel)
- Battery Historian profiling throughout development
- Incremental wake lock usage (start conservative, measure, optimize)
- Fallback: Document manual battery exemption for users experiencing issues

## Testing Strategy

### Device Matrix
Test on minimum 4 physical devices representing ecosystem:
- Google Pixel (stock Android, lenient battery policies)
- Samsung Galaxy (moderate battery optimization)
- Xiaomi (aggressive battery optimization, auto-start restrictions)
- OnePlus (aggressive Doze, known to kill background services)

### Scenarios
- [ ] Message delivery while device locked (screen off)
- [ ] Message delivery during Doze mode (after 30 minutes stationary)
- [ ] Message delivery during App Standby (app unused for 24 hours)
- [ ] Network transition: WiFi → cellular
- [ ] Network transition: online → airplane mode → online
- [ ] Network transition: cellular → no signal → cellular
- [ ] Battery drain over 8 hours idle (< 5% acceptable)
- [ ] Wake lock duration per hour (< 2 minutes acceptable)
- [ ] Service survives low memory conditions (OOM killer)
- [ ] Service survives app force-stop (should not auto-restart until user opens app)

### Tools
- Battery Historian (wake lock analysis)
- Doze mode testing: `adb shell dumpsys deviceidle force-idle`
- App Standby testing: `adb shell dumpsys battery unplug && adb shell am set-inactive <package> true`
- Network transition testing: Manual airplane mode toggles + network type changes
- Profiler: Android Studio Memory/CPU profiler during background operation

## User Experience Expectations

**Invisible when working:**
- Messages arrive instantly (user perceives < 1 second delay)
- No noticeable battery drain (< 5% per 8 hours idle)
- Persistent notification present but unobtrusive

**Clear when failing:**
- Connection status visible in notification
- "Waiting for network" state when offline
- Message queue count visible if pending sends
- Retry indication if delivery fails

**User control:**
- Toggle service on/off in settings
- Notification importance customization (Android channels)
- Manual battery exemption documentation (if needed)
- Clear explanation of why persistent notification exists

**Manufacturer-specific guidance:**
- Help docs with Xiaomi auto-start instructions
- OnePlus battery optimization allowlist steps
- Samsung "Never sleeping apps" configuration

## Sources Summary

**Official Android Documentation:**
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [About notifications and conversations](https://developer.android.com/social-and-messaging/guides/communication/notifications-conversations)
- [Monitor connectivity status](https://developer.android.com/training/monitoring-device-state/connectivity-status-type)

**Best Practices (2026):**
- [Push notifications Android 2026 - Pushwoosh](https://www.pushwoosh.com/blog/android-push-notifications/)
- [Offline-First Android - Medium](https://medium.com/@vishalpvijayan4/offline-first-android-build-resilient-apps-for-spotty-networks-with-room-datastore-workmanager-4a23144e8ea2)
- [Building Offline-First App - ProAndroidDev](https://proandroiddev.com/offline-apps-its-easier-than-you-think-9ff97701a73f)

**Battery & Performance:**
- [Wake Locks and ASO Strategy - Gummicube](https://www.gummicube.com/blog/wake-locks-and-how-it-could-change-your-aso-strategy/)
- [Top battery draining apps 2026 - TechLoy](https://www.techloy.com/top-10-apps-draining-your-smartphone-battery-in-2026-and-how-to-stop-them/)

**Ecosystem Examples:**
- [Bridgefy - Offline Mesh Messaging](https://dscnextconference.com/bridgefy-most-popular-offline-mesh-messaging-app/)
- [Android persistent socket connection rules](https://copyprogramming.com/howto/android-persistent-socket-connection-rules)

**Policy & Restrictions:**
- [Battery optimization - Taming The Droid](https://tamingthedroid.com/battery-optimization-doze-mode)
- [Android 13 exact alarm restrictions - Esper](https://www.esper.io/blog/android-13-exact-alarm-api-restrictions)
