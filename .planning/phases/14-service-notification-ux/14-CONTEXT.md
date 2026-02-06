# Phase 14: Service Notification UX - Context

**Gathered:** 2026-02-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Persistent foreground service notification that shows connection status and provides quick actions (Reconnect, Pause/Resume) without opening the app. The notification is the user's primary window into Reticulum's connection health. Phase 10 already created the notification channel and foreground service type; this phase enhances the notification content and adds interactive controls.

</domain>

<decisions>
## Implementation Decisions

### Status display
- **Type breakdown** for interface info — show counts by type like "2 TCP, 1 UDP"
- **Five connection states**: Connected / Partial / Connecting / Disconnected / Paused
  - "Partial" when some but not all interfaces are online
  - "Paused" when user explicitly paused via quick action
- **Uptime duration** as secondary info line — "Connected for 2h 34m" showing current session duration
- **Interface breakdown placement** — Claude's discretion on whether it appears in the main content text or expanded view

### Pause behavior
- **Stop Transport** on pause — stop the Transport job loop entirely, service stays alive but does nothing
- **Cancel WorkManager** when paused — full silence, no background activity until user resumes
- **Manual resume only** — stays paused until user taps Resume, no auto-timeout
- **Immediate reconnect on Resume** — restart Transport and immediately trigger interface reconnection, not the normal startup flow
- Pause is a user-initiated state distinct from disconnected — notification shows "Paused" state

### State transitions
- **Silent always** — no sound, no vibration on state changes. Notification updates visually only
- **Single static icon** — one mesh/network icon always. State conveyed through text and color, not icon changes
- **Debounced updates** — wait before updating notification to avoid flickering during rapid state changes (WiFi/cellular handoff). Consistent with existing 500ms debounce from Phase 10-03
- **Color-coded status text** — green for connected, yellow/orange for connecting/partial, red for disconnected, gray for paused

### Notification style
- **Standard NotificationCompat** — no custom RemoteViews. Consistent with system, easier to maintain across Android versions/OEMs
- **Two quick action buttons** — Reconnect + Pause/Resume (matches roadmap criteria exactly)
- **Dynamic toggle** — single button that says "Pause" when running and "Resume" when paused
- **Expanded view shows interface details** — per-interface status lines when user pulls notification down (e.g., "TCP 10.0.0.1:4242 — Connected", "UDP mesh — Disconnected")
- Collapsed view: status + uptime. Expanded view: + per-interface breakdown

### Claude's Discretion
- Exact interface breakdown placement in collapsed vs expanded view
- Color values for status states (within green/yellow/red/gray palette)
- How to format uptime duration (hours:minutes vs "2h 34m" etc.)
- Notification icon design (specific drawable resource)
- How the Reconnect action interacts with pause state (grayed out? hidden?)
- BigTextStyle vs InboxStyle for expanded notification

</decisions>

<specifics>
## Specific Ideas

- Phase 10 already created dual notification channels: service (low-priority) and alerts (default-priority). The persistent notification uses the service channel
- Existing 500ms debounce from NetworkStateObserver (Phase 10-03) can be reused or extended for notification update throttling
- Transport.getInterfaces() returns interface list with online status — already used by ReticulumWorker (Phase 13) for health checks
- The existing ReticulumService already has a basic notification from Phase 10 — this phase enhances it rather than creating from scratch

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 14-service-notification-ux*
*Context gathered: 2026-02-05*
