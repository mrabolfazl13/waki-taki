# Roadmap & Phases

Statuses: NOT_STARTED / IN_PROGRESS / BLOCKED / IMPLEMENTED / TESTED / VERIFIED

| Phase | Scope | Status |
|---|---|---|
| 0 | Repository audit, architecture, docs | VERIFIED (greenfield; see PROJECT_AUDIT.md) |
| 1 | Foundation: repo structure, backend skeleton, web skeleton, Android skeleton, Docker, CI | VERIFIED (backend 46✅ + 1 skip local; web build ✅ local; Android test/APK/AAB ✅ local; Docker builds CI-only) |
| 2 | Product UI (mobile screens + dashboard layout) | VERIFIED at build level (web build ✅; Android 22 screens compile into debug APK; no on-device UI pass) |
| 3 | Authentication & device identity | TESTED (backend: rotation/reuse/logout/device revoke; Android X3DH awaits CI) |
| 4 | Audio & PTT (Opus, floor control) | TESTED (backend floor + relay latency; Android audio path on-device pending) |
| 5 | Security (Signal-compatible sessions, sender-key group media) | TESTED (backend + Android crypto/replay unit tests pass locally; on-device interop pending) |
| 6 | Internet communication (signaling, relay) | VERIFIED (signaling relay + media fan-out across two live API replicas on real Redis/PostgreSQL) |
| 7 | Offline local communication (Android host, NSD, QR) | TESTED at unit level (local floor controller + media framing tests pass; on-device NSD/QR pending) |
| 8 | 20-member capacity validation | VERIFIED locally (real PG/Redis: grant p95 5.95 ms, relay p95 8.42 ms, 21st → 409); Docker/nginx deployment pending |
| 9 | Advanced UX (Bluetooth, background, diagnostics, a11y) | IMPLEMENTED + compiles into APK/AAB; on-device verification pending |
| 10 | Security & performance audit | TESTED internally (transport bounds, envelope caps, device revoke, production-settings fail-closed, /metrics ops endpoint; external audit still open) |
| 11 | Production release | VERIFIED (deployed on 2.189.255.225 behind Cloudflare; public health/SPA/admin/WSS OK; signed release APK+AAB published as v0.1.0; optional CD workflow ready behind secrets) |

Dependency graph: 1 → (2, 3) → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11.
Phases 2 (web) and 3+ (backend) and 2+ (Android) proceed in parallel across agents with file ownership per docs/AGENT_ASSIGNMENTS.md.
