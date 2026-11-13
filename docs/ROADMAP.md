# Roadmap & Phases

Statuses: NOT_STARTED / IN_PROGRESS / BLOCKED / IMPLEMENTED / TESTED / VERIFIED

| Phase | Scope | Status |
|---|---|---|
| 0 | Repository audit, architecture, docs | VERIFIED (greenfield; see PROJECT_AUDIT.md) |
| 1 | Foundation: repo structure, backend skeleton, web skeleton, Android skeleton, Docker, CI | IMPLEMENTED (backend tests 30✅ local; web build ✅ local; Android compile + Docker/CI pending CI) |
| 2 | Product UI (mobile screens + dashboard layout) | NOT_STARTED |
| 3 | Authentication & device identity | NOT_STARTED |
| 4 | Audio & PTT (Opus, floor control) | NOT_STARTED |
| 5 | Security (Signal-compatible sessions, sender-key group media) | NOT_STARTED |
| 6 | Internet communication (signaling, relay) | NOT_STARTED |
| 7 | Offline local communication (Android host, NSD, QR) | NOT_STARTED |
| 8 | 20-member capacity validation | NOT_STARTED |
| 9 | Advanced UX (Bluetooth, background, diagnostics, a11y) | NOT_STARTED |
| 10 | Security & performance audit | NOT_STARTED |
| 11 | Production release (Docker, CI/CD, docs, artifacts) | NOT_STARTED |

Dependency graph: 1 → (2, 3) → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11.
Phases 2 (web) and 3+ (backend) and 2+ (Android) proceed in parallel across agents with file ownership per docs/AGENT_ASSIGNMENTS.md.
