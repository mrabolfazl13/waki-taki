# Current State

**Updated:** 2026-11-24 · **Phase:** 1 verified; 2–9 implemented, 8 tested locally; 10–11 in progress

## Done
- Phase 0: audit, architecture contracts, decisions (greenfield).
- Phase 1 foundation — all four tracks complete:
  - **Backend (F):** full FastAPI app per ARCHITECTURE §5–§7 (auth with refresh rotation, devices/prekeys, channels + 20-member cap, invites/QR, sender-key envelopes with ack-delete + 24h TTL, Redis-authoritative floor control, WS hub with signaling/presence/media relay + replay window, admin + audit, Alembic migration verified upgrade/downgrade/upgrade). **pytest: 30 passed, 1 skipped — independently re-run by coordinator.**
  - **Web (G):** 15-page admin dashboard (React 18 + TS strict + Tailwind + TanStack Query + Zod). tsc clean, vite build clean. No fake data; "unavailable" states distinct from zero. Live integration with the running API still pending.
  - **Android (B–E):** 19-module Gradle project, ~8,250 lines Kotlin (X3DH + double-ratchet + Sender-Key AES-256-GCM media, Tink/Keystore storage, AudioRecord/MediaCodec-Opus with AAC fallback, jitter buffer, full audio routing, Ktor local host with ciphertext-only relay, NSD + QR, 22 Compose screens, PTT wired to floor control, foreground services). **NOT compiled locally (no SDK) — first build happens in GitHub Actions CI; unit tests authored but not executed.**
  - **DevOps (J):** Dockerfiles, docker-compose + nginx TLS/WSS, .env.example, deploy/rollback scripts, 4 CI workflows + migration-check. Authored only — no Docker daemon locally.

## Verification status (honest)
| Component | Locally verified |
|---|---|
| Backend tests | ✅ 30 passed (aiosqlite + fakeredis; real PG/Redis path only in CI) |
| Web build | ✅ tsc 0 errors, vite build clean |
| Android build/tests | ❌ CI-only (no SDK here) |
| Docker/CI | ❌ CI-only (no daemon here) |
| End-to-end media path, live backend↔dashboard↔app integration | ❌ next phase |

## Blockers
- Default pip index (pypi.devneeds.ir) broken — all installs must use `-i https://pypi.org/simple`. Documented for all agents.

## 2026-11-24 update
- Backend suite is deterministic again: **44 passed, 1 skipped in ~23 s** (was hanging; see PROGRESS T-6.T1).
- Live backend ↔ dashboard integration remains green (TEST_RESULTS 2026-11-14 section).
- New regression tests: cross-instance realtime fan-out, transport size bounds, envelope validation, revoked-device disconnect.
- Capacity/latency measured locally (20 members): floor grant p95 **4.97 ms**, media relay p95 **6.07 ms**, 21st member → 409.
- Android CI now runs `./gradlew test` across all modules (unit tests live in core modules, not `:app`).
- **Android now builds locally**: `gradle test` → 38 tests pass, `:app:assembleDebug` → 25.9 MB APK, `:app:bundleRelease` → 10.0 MB AAB (R8 clean). On-device audio/PTT interop still pending.

## Next
- CI: confirm backend/android/web/docker workflows green on GitHub.
- Android: first successful compile + unit tests in CI, then on-device PTT/crypto interop.
- Deploy: real Docker build, TLS/WSS smoke, Postgres/Redis live run, multi-instance pub/sub.
- Phase 10: external security review + load testing under a real deployment.
