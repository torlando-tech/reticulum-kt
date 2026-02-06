# Phase 15: Battery Optimization UX - Context

**Gathered:** 2026-02-05
**Status:** Ready for planning

<domain>
## Phase Boundary

User-facing battery impact visibility and optimization controls. The user understands how the always-connected service affects their battery, can request exemption from battery optimization, and sees usage statistics. Enhances the existing Monitor screen's battery card. Does not include OEM-specific handling (Phase 16) or memory optimization (Phase 17).

</domain>

<decisions>
## Implementation Decisions

### Exemption request flow
- Prompt on first service start via bottom sheet dialog
- If user dismisses, never re-prompt — user can find it in settings/Monitor if they change their mind
- Use system intent directly (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) — requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission in manifest
- Bottom sheet includes bullet points of specific impacts (dropped connections, missed messages, delayed routing) then action button

### Battery stats display
- Enhance the existing battery card in the Monitor screen (not a new screen)
- Show drain rate (%/hr) — no battery life estimate (avoids misleading predictions)
- Line chart showing drain over time plus summary numbers
- Time windows: Claude's discretion (pick reasonable windows based on what's useful)

### Optimization impact visibility
- Inline warning on the battery card when problems are detected (not whenever exemption is missing)
- Track and display specific optimization event counts (e.g., "Service killed 3 times today")
- Warning includes "Fix" action button that launches the exemption bottom sheet
- Warning is dismissable with memory — returns only if new events occur after dismissal

### Guidance tone & messaging
- Technical/direct tone: "Battery optimization restricts background processes. Exempt this app for persistent mesh connectivity."
- Battery info lives in-app only (Monitor screen) — notification stays focused on connection status
- Dismissable warnings that remember user's choice

### Claude's Discretion
- Exact time windows for battery stats (current session, 24h, weekly — or whatever makes sense)
- Chart library/implementation approach
- Specific wording of bullet points in exemption bottom sheet
- How to detect optimization-caused kills vs normal lifecycle events
- Warning icon/color design

</decisions>

<specifics>
## Specific Ideas

- Enhance the existing battery card in Monitor rather than creating a new screen
- Event counting gives users concrete evidence ("killed 3 times today") rather than vague warnings
- System intent for exemption (one-tap experience) rather than guiding user through settings manually

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 15-battery-optimization-ux*
*Context gathered: 2026-02-05*
