# Reticulum-KT: Gap Analysis vs Python Reference

Last updated: 2026-02-18

## Interop Bugs (will break Kotlin<->Python communication)

- [x] **#1 Keepalive payload wrong** — Fixed: sends `byteArrayOf(0xFF)` instead of `ByteArray(0)`.
- [x] **#2 Keepalive formula wrong** — Fixed: uses `KEEPALIVE_MAX / (KEEPALIVE_MAX_RTT * 1000)` scaling factor (~205.7x) instead of `1.75x`.
- [x] **#3 LRPROOF pack() ~~missing~~** — NOT A BUG. Kotlin uses `Packet.createRaw(destinationHash = linkId)` which passes `linkId` explicitly, avoiding the need for `pack()` to switch between `hash` and `link_id` like Python does.

## Partially Implemented (functional but incomplete)

- [x] **#4 Recursive path requests** — Fixed: added `processPathRequest()` fallthrough branches for local client forwarding, recursive unknown-path discovery with announce-cap throttling, and local client notification. Added `discoveryPathRequests` tracking and `requestPathInternal()` with tag/recursive support.
- [x] **#5 Announce rate control** — Fixed: wired `shouldIngressLimit()` into `processAnnounce()` for unknown destinations, added `recordIncomingAnnounce()` to `InterfaceRef`, enhanced `processHeldAnnounces()` to release one held announce per 30s interval.
- [ ] **#6 Implicit proof mode** — `PacketReceipt` validates both formats, but `Packet.prove()` always sends explicit proofs. Works today (Python accepts both), but doesn't save bandwidth.
- [ ] **#7 Destination auto-registration** — Python auto-registers with Transport on construction; Kotlin requires manual `Transport.registerDestination()`. API divergence.
- [x] **#8 Destination.announce(attached_interface)** — Fixed: added `attachedInterface` parameter to `Destination.announce()` and `Packet.createAnnounce()`, stored on `Packet`, respected in `Transport.processOutbound()`. Path response announces now target the requesting interface.
- [x] **#9 PacketReceipt link-aware timeout** — Fixed: `calculateTimeout()` now uses `max(link.rtt * TRAFFIC_TIMEOUT_FACTOR, TRAFFIC_TIMEOUT_MIN_MS/1000)` for LINK destinations, populating `link` from `packet.link`.

## Missing Features

- [ ] **#10 Config file parser** — No `~/.reticulum/config` INI parsing. Interfaces must be registered programmatically. Can't be a drop-in replacement daemon.
- [ ] **#11 Remote management** — No `rnstransport.remote.management` destination, no `/status` or `/path` request handlers.
- [ ] **#12 Blackhole identity table** — No `blackholed_identities`, no filtering, no persistence.
- [ ] **#13 Destination.links list** — Python tracks all incoming links on a destination; Kotlin doesn't.
- [ ] **#14 Destination.mtu field** — Missing. Link MTU doesn't propagate to packet MTU enforcement.
- [ ] **#15 Packet.ratchetId field** — Can't inspect which ratchet encrypted a sent/received packet.
- [ ] **#16 Identity exit_handler** — No `atexit` to persist known destinations on unclean shutdown.

## Missing Interface Types (7 of 14)

- [ ] **SerialInterface** — UART/RS-232 for embedded/radio devices
- [ ] **KISSInterface** — KISS TNC protocol for amateur radio TNCs
- [ ] **AX25KISSInterface** — AX.25 over KISS for ham radio
- [ ] **PipeInterface** — stdin/stdout for process chaining
- [ ] **BackboneInterface** — High-throughput backbone for infrastructure links
- [ ] **WeaveInterface** — Peer mesh weaving for mesh network formation
- [ ] **RNodeMultiInterface** — Multi-subinterface RNode for multi-frequency operation
