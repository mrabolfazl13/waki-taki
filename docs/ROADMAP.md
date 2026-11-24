# Roadmap & Phases

Statuses: NOT_STARTED / IN_PROGRESS / BLOCKED / IMPLEMENTED / TESTED / VERIFIED

| Phase | Scope | Status |
|---|---|---|
| 0 | Repository audit, architecture, docs | VERIFIED (greenfield; see PROJECT_AUDIT.md) |
| 1 | Foundation: repo structure, backend skeleton, web skeleton, Android skeleton, Docker, CI | VERIFIED (backend 44✅ + 1 skip local; web build ✅ local; Android compile + Docker builds are CI-only) |
| 2 | Product UI (mobile screens + dashboard layout) | IMPLEMENTED (web verified locally; Android screens await first CI compile) |
| 3 | Authentication & device identity | TESTED (backend: rotation/reuse/logout/device revoke; Android X3DH awaits CI) |
| 4 | Audio & PTT (Opus, floor control) | TESTED (backend floor + relay latency; Android audio path on-device pending) |
| 5 | Security (Signal-compatible sessions, sender-key group media) | IMPLEMENTED + backend TESTED (crypto unit tests await CI execution) |
| 6 | Internet communication (signaling, relay) | TESTED (signaling relay + media fan-out incl. cross-instance on one Redis) |
| 7 | Offline local communication (Android host, NSD, QR) | IMPLEMENTED (awaiting Android CI/on-device) |
| 8 | 20-member capacity validation | TESTED locally (20 members: grant p95 4.97 ms, relay p95 6.07 ms, 21st → 409); real deployment pending |
| 9 | Advanced UX (Bluetooth, background, diagnostics, a11y) | IMPLEMENTED (Android; on-device verification pending) |
| 10 | Security & performance audit | IN_PROGRESS (transport bounds, envelope caps, device revoke hardening landed; external audit pending) |
| 11 | Production release (Docker, CI/CD, docs, artifacts) | IN_PROGRESS (compose/nginx/scripts/workflows authored; first real Docker build + CI green pending) |

Dependency graph: 1 → (2, 3) → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11.
Phases 2 (web) and 3+ (backend) and 2+ (Android) proceed in parallel across agents with file ownership per docs/AGENT_ASSIGNMENTS.md.
