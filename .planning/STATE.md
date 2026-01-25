# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-24)

**Core value:** Perfect byte-level interoperability with Python LXMF
**Current focus:** v2 Android Production Readiness

## Current Position

Phase: Not started (defining requirements)
Plan: —
Status: Defining requirements
Last activity: 2026-01-24 — Milestone v2 started

Progress: v2 [░░░░░░░░░░] 0%

## Milestone Goals

**v2: Android Production Readiness**

Make existing Reticulum-KT interfaces (TCP/UDP) production-ready for Android:
- Always-connected background operation
- Survives Doze and battery optimization
- Reasonable power consumption
- API 26+ (Android 8.0+)

## Accumulated Context

### Decisions

Key decisions logged in PROJECT.md. Relevant for v2:
- API 26+ target floor (from v1 constraints)
- TCP/UDP interfaces already working (from v1)
- No Python at runtime (final Android app)

### From v1

Codebase ready for Android optimization:
- 31,126 LOC (Kotlin + Python bridge for testing)
- TCP/UDP interfaces working
- 120+ interop tests can verify Android changes don't break protocol

### Blockers/Concerns

- **[FLAGGED in v1]** Android battery/Doze issues — now being addressed in v2

## Session Continuity

Last session: 2026-01-24
Stopped at: Starting v2 milestone, defining requirements
Next step: Research → Requirements → Roadmap
