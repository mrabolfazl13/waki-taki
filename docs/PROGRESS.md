# Progress Log

### T-0.1 — Repository audit & architecture contracts
- Agent: A | Status: VERIFIED
- Files: docs/PROJECT_AUDIT.md, docs/ARCHITECTURE.md, docs/ROADMAP.md, docs/DECISIONS.md, docs/CURRENT_STATE.md, docs/AGENT_ASSIGNMENTS.md
- Details: Greenfield audit; defined API/WS/floor-control/crypto/DB contracts binding all agents.
- Tests: n/a (docs). Acceptance: contracts established. Remaining: none.

### T-1.x — Phase 1 foundation (parallel)
- Agent: B-E (Android), F (backend), G (web), J (deploy/CI) | Status: IN_PROGRESS
- Details: see per-task entries below as they land.

### T-1.J1 — Docker images (backend + web)
- Agent: J | Status: IMPLEMENTED
- Depends: T-1.F (backend app layout), T-1.G (web build) | Files: backend/Dockerfile, web/Dockerfile, web/nginx.conf
- Details: Multi-stage backend image (python:3.12-slim builder with wheels venv -> slim runtime, non-root `appuser`, HEALTHCHECK on /health via curl). Multi-stage web image (node:20-alpine `npm ci` + build -> nginx:1.27-alpine static serve with SPA fallback, hashed-asset caching). Authored-but-unverified locally: NO Docker daemon on this machine — validated by CI (docker.yml build job) only.
- Tests: none locally (no Docker daemon) -> to be validated by CI image builds.
- Acceptance: both images build in CI, backend container passes /health healthcheck | Remaining: first real build verification on a Docker-capable host.

### T-1.J2 — deploy/ compose stack, nginx edge, env template, README
- Agent: J | Status: IMPLEMENTED
- Depends: T-1.J1 | Files: deploy/docker-compose.yml, deploy/nginx/securetalk.conf, deploy/.env.example, deploy/README.md
- Details: compose: api + postgres:16 (pgdata volume, pg_isready healthcheck) + redis:7 (RDB/AOF DISABLED with comment: no voice content persisted per ARCHITECTURE §7, state is ephemeral) + nginx TLS termination. Edge nginx: WSS proxy for /api/v1/ws and /ws with Upgrade headers, /api proxy, HSTS, ACME webroot. .env.example contains only placeholders (SECRET_KEY, POSTGRES_PASSWORD, ADMIN_TOKEN, etc.) with generation instructions. README: deploy steps, pg_dump/pg_restore backup-restore, rollback procedure, self-signed + certbot TLS, firewall (only 80/443 exposed).
- Tests: YAML parsed successfully with PyYAML (syntax validation only).
- Acceptance: `docker compose up -d` works on a host with .env configured | Remaining: live verification.

### T-1.J3 — GitHub Actions CI (backend, web, android, docker, migration check)
- Agent: J | Status: IMPLEMENTED
- Depends: T-1.F, T-1.G, T-1.B-E | Files: .github/workflows/backend.yml, web.yml, android.yml, docker.yml
- Details: backend.yml: ruff lint + pytest on 3.12 with postgres:16 + redis:7 service containers, explicit failure when pytest exits 5 (no tests collected). web.yml: npm ci, tsc --noEmit, npm run build, dist artifact. android.yml: JDK 17 temurin, gradle/actions/wrapper-validation + setup-gradle, ./gradlew :app:assembleDebug (APK artifact) + :app:bundleRelease (AAB artifact); gradle test failures fail the build (assembleDebug runs unit tests via `check`-chain dependencies of assemble... note: unit test task not explicitly invoked — see Remaining). docker.yml: buildx builds of backend and web images, push ONLY if DOCKER_PUSH_ENABLED secret == 'true'; plus migration-check job applying `alembic upgrade head` twice (idempotency) and `downgrade -1` against ephemeral postgres:16.
- Tests: YAML parsed successfully with PyYAML (syntax validation only).
- Acceptance: workflows green once backend/web/android code lands | Remaining: android.yml does not run a separate `test` task — add `:app:testDebugUnitTest` when Agent B-E's test suite exists.

### T-1.J4 — scripts/deploy.sh
- Agent: J | Status: IMPLEMENTED
- Depends: T-1.J2 | Files: scripts/deploy.sh
- Details: bash with `set -euo pipefail`; commands: deploy (build, tag previous image for rollback, pull, `alembic upgrade head` via compose run, up, health poll on /health with auto-rollback on failure), rollback (retag previous image, up -d api, `alembic downgrade -1`, re-healthcheck), health. bash -n syntax-checked.
- Tests: `bash -n` syntax check passed -> runtime behavior untested locally (no Docker).
- Acceptance: safe deploy + one-command rollback on a Docker host | Remaining: live verification.

### T-1.G — Web admin dashboard (React + TS)
- Agent: G | Status: IMPLEMENTED
- Depends: T-1.F (backend /api/v1) | Files: web/** (package.json, vite.config.ts, tsconfig.json, index.html, src/{main,App}.tsx, src/index.css, src/lib/{api,auth,schemas,queries,utils}.ts(x), src/components/{layout.tsx, ui/*}, src/pages/* (15 pages))
- Details: Vite + React 18 + strict TS + Tailwind v4 + hand-rolled shadcn-style UI components + React Router + TanStack Query + Zod + recharts. Pages: admin login (JWT access+refresh in localStorage, auto-refresh on 401, route guard), dashboard overview, users (list/search/disable-enable), devices, channels (delete, member counts), memberships, security events (severity filter), audit logs, server health, realtime infra, network diagnostics (real latency probes), configuration, system settings, admin roles (create), access control. Dark mode, responsive, accessible (labels, focus-visible, skip link, aria). Vite proxy /api -> localhost:8000.
- Tests: npm install (127 packages) -> OK; npx tsc --noEmit -> 0 errors; npm run build -> vite v5.4.21, 713 modules, built in 3.76s (index 162kB + react 164kB + recharts 385kB chunks), no warnings.
- Acceptance: build/tsc pass; no fake metrics — unavailable endpoints render a distinct "Data unavailable" state (devices, memberships, config, settings, WS counts pending backend); never displays values the server can't know. | Remaining: live verification once backend (Agent F) serves /api/v1; adjust Zod schemas to actual response field names if they differ from §5 assumptions.

### T-1.B1 — Android core modules (common, security, audio, database, network, design, permissions)
- Agent: B-E (Android) | Status: IMPLEMENTED
- Depends: T-0.1 (architecture contracts) | Files: android/core/** (all 7 modules, 30+ Kotlin files)
- Details: §2.3 abstractions + WS protocol codec (§4/§6 envelopes + binary media header) in core/common (pure Kotlin, unit tests for media framing). Crypto per §3.3: BouncyCastle X25519/Ed25519/HKDF + JCE AES-256-GCM; Tink AndroidKeysetManager (Keystore master key) seals all secret storage; real X3DH against published prekey bundles (DH1-DH4 incl. one-time prekeys); double-ratchet-LITE pairwise sessions (symmetric chains, 64-msg out-of-order cache — documented D3 trade-off); Signal Sender-Key manager with per-(channel,epoch) seeds, salt-XOR-counter nonces, AAD=epoch|sender|seq, 128-seq replay window, stale-epoch/revocation rejection, pairwise-encrypted distribution (REST online / opaque WS relay local). Audio: AudioRecord VOICE_COMMUNICATION + AEC/NS, AudioTrack low-latency, MediaCodec Opus (API 29+) with AAC-LC fallback (26-28, documented), adaptive jitter buffer (≤400 ms) + silence PLC, AudioManager routing incl. Bluetooth with device-change callbacks. Database: Room (channels/members/devices/identity) + DataStore settings. Network: Retrofit REST (§5 endpoints), OkHttp WS transports with exponential backoff, ConnectionMonitor per §8 (NetworkCallback, NSD probe, RequiresUserDecision — never silent downgrade, session reset on switch), embedded Ktor CIO local host (same WS protocol, authoritative floor copy: 60 s lease, queue 20, rate limit 10/10 s, opaque media relay by plaintext header only, cannot decrypt), NsdDiscovery _securetalk._tcp, QR payload gen/parse (ZXing) + Bitmap rendering.
- Tests: unit tests authored (crypto round-trips, nonce uniqueness, replay window, jitter buffer, media codec framing, floor controller: grant/queue/promote/expiry/rate-limit/queue-depth) → NOT EXECUTED (no JVM test toolchain for Android modules on this machine; no Android SDK/Gradle). Compilation NOT verified locally — first build happens in CI (.github/workflows/android.yml).
- Acceptance: modules compile in CI and unit tests pass | Remaining: on-device verification of audio path and crypto interop.

### T-1.B2 — Android app + 11 feature modules (22 screens), PTT controller, local host service
- Agent: B-E (Android) | Status: IMPLEMENTED
- Depends: T-1.B1 | Files: android/app/**, android/feature/** (Hilt ViewModels + Compose Material3 screens), android/settings.gradle.kts, gradle/libs.versions.toml, gradle wrapper scripts/properties, android/README.md
- Details: PushToTalkController (press→floor.request→3 s await granted→TX; denial/queue/lease-expiry/timeout handling; never self-grants). Compose navigation covering 22 screens: onboarding(3), auth(3), home(1), channels(3), channel(2), members(1), local_network(3), security(2), settings(2), devices(1), diagnostics(1) — real loading/error/empty/offline states. Active Channel screen: hold-to-talk PTT bar wired to PushToTalkController, ON AIR/queue/denied indicators from floor events, member presence strip, ConnectionBadge from ConnectionMonitor §8 states, audio route selector (speaker/earpiece/wired/BT), epoch rotation button. Radio-inspired dark Material3 theme. Foreground services: PttSessionService (microphone type, started only after RECORD_AUDIO granted — Android 14 FGS rules) and LocalHostService (dataSync type, in feature manifest) with notification channels; POST_NOTIFICATIONS requested at onboarding. Hilt DI binds all core interfaces; SessionController pumps decrypt→jitter→playback and applies incoming sender-key envelopes; ModeHolder switches transports only via user-confirmed ConnectionMonitor decisions.
- Tests: none authored for UI (Compose testing deferred) → NOT EXECUTED. Compilation NOT verified locally (no Android SDK/Gradle on this machine) — exactly what CI's :app:assembleDebug must prove.
- Acceptance: CI assembleDebug green; on-device PTT round trip in both modes | Remaining: runtime verification, performance measurements (§9 targets NOT VERIFIED), peer key discovery via GET /users/{id}/keys plumbing.

### T-1.F1 — Backend core: DB models, auth, devices, keys discovery
- Agent: F | Status: TESTED
- Depends: T-0.1 | Files: backend/app/{db,models,schemas,deps,main}.py, backend/app/api/{auth,devices,users}.py, backend/app/core/config.py, backend/requirements*.txt
- Details: SQLAlchemy async models for §7 schema (users, devices, one_time_prekeys, channels, memberships, invites, key_envelopes, security_events, audit_log, revocations, refresh_tokens). JWT access (15 min) + refresh (30 d) with single-use rotation (reuse rejected + security event), logout revocation, bcrypt passwords. Bootstrap admin (first account). Device registration stores public keys only. Public key discovery endpoint. Rate limiting on auth/invite endpoints (configurable).
- Tests: backend pytest (pytest + httpx ASGI + fakeredis + aiosqlite) -> 30 passed, 1 skipped, 0 failed.
- Acceptance: register/login/refresh-rotation/logout, device CRUD, key discovery | Remaining: none for this scope.

### T-1.F2 — Channels, membership, invites, 20-member cap
- Agent: F | Status: TESTED
- Depends: T-1.F1 | Files: backend/app/api/channels.py
- Details: Channel create/list/delete (owner/admin gated), join via invite (HMAC-hashed code, timing-safe compare, expiry + max_uses), leave, invite creation (rate-limited, QR payload), invite validation, member removal (owner/admin) with automatic epoch rotation + revocation record. Server-enforced 20-member cap (409 channel_full).
- Tests: pytest -> included in 30 passed (channel cap test: 20 join OK, 21st rejected 409; invalid invite 403).
- Acceptance: full invite lifecycle + cap enforced | Remaining: none.

### T-1.F3 — Sender-key envelopes (ephemeral) + epoch rotation
- Agent: F | Status: TESTED
- Depends: T-1.F1, T-1.F2 | Files: backend/app/api/keys.py
- Details: Envelope distribution (base64 ciphertext stored as bytes; sender must own sending device; recipients must be devices of current channel members; epoch must match channel), fetch addressed-to-me, ack + delete (only own envelopes). Epoch rotation (owner/admin-member) with revocation records and old-epoch rejection. Hourly sweeper deletes envelopes older than 24 h. No key material ever logged or persisted beyond the encrypted envelope blob.
- Tests: pytest -> distribute/fetch/ack-deletes, recipient-not-member 403, epoch mismatch 409, rotation + old-epoch rejection all pass.
- Acceptance: §5 key endpoints + ephemeral retention | Remaining: none.

### T-1.F4 — Redis floor control (single speaker, lease, queue, override)
- Agent: F | Status: TESTED
- Depends: T-1.F1 | Files: backend/app/floor_control/service.py
- Details: Redis-authoritative floor: WATCH/MULTI CAS for receive-order arbitration, 60 s lease with renewal (heartbeat as re-request), rate limit 10 req/10 s/device, queue depth 20 with hand-off on release, admin override bypass (grants immediately, bypasses rate limit).
- Tests: pytest WS tests -> single speaker grant/deny, release + regrant, admin override granted for admin and denied (error) for non-admin, rate_limited denial.
- Acceptance: §4 rules enforced | Remaining: none.

### T-1.F5 — WebSocket hub: signaling, presence, media relay, replay window
- Agent: F | Status: TESTED
- Depends: T-1.F1, T-1.F4 | Files: backend/app/realtime/{hub,ws}.py
- Details: Single authenticated WS (/ws and /api/v1/ws, token query param). JSON frames {type,data}: join (membership + device-ownership verified, presence via Redis set + pub/sub), signaling.offer/answer/ice relayed verbatim to target device in same channel (never parsed), floor.* per §4, ping/pong, channel.event fan-out (join/leave/remove/epoch via Redis pub/sub so REST changes reach WS clients). Binary media relay: epoch(4)|sender_device_id(16)|seq(4)|ciphertext — verifies sender identity + floor ownership + sliding replay window (128, Redis zset, duplicate seq and stale seq rejected, security events recorded), then forwards identical bytes to channel members; Redis pub/sub media:{channel_id} with instance-ID echo suppression for multi-instance fan-out. Backend never reads/decrypts/stores media.
- Tests: pytest -> fanout frame round-trip with header verification, replay_detected error, floor_not_held for non-holder, signaling relay, unauthorized join/device rejection, invalid token close.
- Acceptance: §6 frame spec implemented | Remaining: none.

### T-1.F6 — Admin endpoints, audit log, health, Alembic migration
- Agent: F | Status: TESTED
- Depends: T-1.F1 | Files: backend/app/api/{admin,health}.py, backend/alembic.ini, backend/migrations/{env.py,versions/0001_full_schema.py}
- Details: /api/v1/admin: users list, disable, roles, all-channels, health (redis ping), audit log, security-events — all role-gated (403 for non-admins). /health (no detail) and /health/details (admin-gated). Full-schema Alembic migration 0001_full_schema with matching downgrade.
- Tests: pytest admin gating/audit/security-events tests pass; alembic upgrade head -> downgrade -1 -> upgrade head verified idempotent on sqlite.
- Acceptance: §5 admin + §7 schema | Remaining: postgres pass in CI (no local postgres).

### T-1.F7 — Backend test suite & environment
- Agent: F | Status: TESTED
- Depends: T-1.F1..F6 | Files: backend/tests/{conftest,test_auth,test_channels,test_keys,test_admin,test_ws}.py, backend/.venv (rebuilt)
- Details: 31 tests: auth (rotation reuse, logout, rate limit, bootstrap admin), devices, channels + 20-cap, invites, envelopes + ack deletion, epoch, admin gating/audit, WS floor/relay/replay. aiosqlite + fakeredis (no local postgres/redis on machine); venv recreated from official pypi.org index (default index pypi.devneeds.ir is broken).
- Tests: `.venv/Scripts/python.exe -m pytest -q` -> 30 passed, 1 skipped (conditional admin-removal path), 0 failed.
- Acceptance: coverage list per task instructions | Remaining: H (QA) security-review layer; postgres/redis integration run in CI.

### T-2.H1 — Live integration: backend ↔ dashboard, full auth/media E2E, security spot-checks
- Agent: H | Status: TESTED
- Depends: T-1.F1..F7, T-1.G | Files: docs/TEST_RESULTS.md, docs/KNOWN_ISSUES.md, backend/run_live.py, web/src/lib/schemas.ts, web/src/pages/AdminRolesPage.tsx
- Details: Started backend live (uvicorn on :8000 via run_live.py — sqlite+fakeredis since no local PG/Redis). Compared web/src/lib/schemas.ts against ACTUAL responses and fixed drift: refresh_token required in TokenPair; channelSchema gains epoch/is_owner; adminRoleSchema replaced with the real POST /admin/roles contract ({user_id, role} → UserOut; no GET /admin/roles) and AdminRolesPage rewritten as a role-assignment page. Verified vite dev server + proxy: login page served, login/admin calls succeed against the live API.
- Tests: full curl E2E → register (first user = bootstrap admin), login, refresh rotation (old refresh replay → 401 refresh_invalid), device registration with test keys, key discovery (public keys only), channel create, invite create/validate/join (forged code → 403), envelopes distribute/fetch/ack (delete-on-ack verified; sender/recipient authz 403s verified), epoch rotate (owner-only), admin gating (403 user, 401 anonymous), member removal → distribute/fetch 403 not_a_member. Scripted WS test (python websockets): join/presence, floor grant/deny/release + queue promotion, binary media relay round-trip, replay_detected on duplicate seq, invalid_token close. All PASS — actual request/response transcripts in docs/TEST_RESULTS.md. npm run build clean after schema fixes.
- Acceptance: backend and dashboard work together live; no key material in any API response confirmed | Remaining: real PG/Redis path (CI), multi-instance media fan-out over pub/sub (blocked locally by fakeredis binary pub/sub issue — KNOWN_ISSUES #1), WebRTC signaling live exchange, Android build/on-device E2E.

### T-4.R1 — Review fixes across backend / dashboard / Android (2026-11-23) | Status: PARTIALLY TESTED
- Trigger: full-project review found contract and security defects beyond the Phase-1 checklist.
- Backend: media frames now enforce `channel.epoch` (`hub.handle_media` → `epoch_mismatch`); invite HMAC key is shared via `INVITE_SECRET`/`JWT_SECRET` instead of per-process random bytes; `ENVIRONMENT=prod` refuses placeholder secrets, auto-created tables and first-registrant admin; dashboard toggles are enforced in register/join/invite-creation; duplicate device sessions no longer evict the live session; floor release is WATCH/MULTI-atomic with queue dedup; media pub/sub payloads are base64 (fixes KNOWN ISSUES #1); added `GET /channels/{id}/members`, `POST /admin/users/{id}/enable`, `/api/v1/health` alias; added regression tests (`test_fixes.py`, epoch test in `test_ws.py`).
- Dashboard: Zod ids accept backend integer ids; logout sends `refresh_token` and handles 204; delete-channel uses `/channels/{id}`; enable-user calls the new endpoint; public liveness probes use `/health` via `apiPublic` + Vite proxy; auth invalidation is event-driven and clears TanStack cache (no 2 s poll).
- Deploy: compose now includes the `web` service and edge nginx serves the SPA; `location = /health` passes to the API. `.env.example` documents production defaults.
- Android: X3DH now verifies the signed prekey with the peer Ed25519 key (`PreKeyBundleData.identityEd25519Pub`); peer bundles come from `/users/{id}/keys` using a device→user map learned from channel rosters; `BaseUrl` is a normalized origin and REST/WS paths are appended once; WS `join`/`joined` added (required by backend §6), signaling uses `sdp`/`ice`, channel.event kinds map to backend `event`; local mode uses `device_id`, joins channels, scopes media fan-out to shared channels and verifies header sender; no silent local downgrade; `Joined` starts playback and pulls initial envelopes; PTT ignores others' grants; DTOs aligned with real TokenPair/envelope contracts; `ReplayWindow` test corrected.
- Tests: backend `pytest -q` → **38 passed, 1 skipped** (34 original + 4 new regression tests: epoch mismatch, invite HMAC determinism, enforced flags, roster/enable). `ruff check app tests` clean. Dashboard `tsc --noEmit` + `vite build` clean.
- Remaining: Android still NOT compiled locally (no Android SDK at `$ANDROID_HOME=G:\SDK`); CI must prove the Kotlin changes. Real PostgreSQL/Redis and Docker are CI/live-host only.

### T-3.F1 — Admin dashboard endpoints: memberships, devices, config, settings
- Agent: F | Status: TESTED
- Depends: T-1.F6 | Files: backend/app/api/admin.py, backend/app/models.py, backend/app/schemas.py, backend/migrations/versions/0002_admin_settings.py, backend/tests/test_admin_dashboard.py, web/src/lib/{schemas,queries}.ts, web/src/pages/{ConfigurationPage,SystemSettingsPage,MembershipsPage}.tsx
- Details: Four role-gated endpoints the dashboard was calling blind (all 404): GET /admin/memberships (join Channel + User summaries; excludes deleted channels; limit/offset pagination), GET /admin/devices (public metadata only: id, user_id, name, registered_at, revoked_at — no pubkey/prekey fields), GET /admin/config (non-sensitive operational config from Settings: rate limits, floor params, max_channel_members, invite TTLs, envelope TTL, token TTLs; no secrets), GET/PUT /admin/settings (new `settings` table, JSONB values; allow-listed toggles registration_enabled / maintenance_mode / invite_creation_enabled; PUT restricted to admin, StrictBool validation + extra="forbid" (unknown keys 422) + at-least-one-key validator, audit_log entry `settings.update` written on change). Alembic migration 0002_admin_settings (upgrade/downgrade/upgrade verified on sqlite).
- Tests: pytest -> 34 passed, 1 skipped (was 30 passed, 1 skipped). New backend/tests/test_admin_dashboard.py covers gating (403 non-admin GET + PUT, 401 anonymous), memberships + devices happy path incl. schema-field and no-key-material assertions, config no-secrets assertions, settings defaults/PUT/persistence/audit/422 validation. `npx tsc --noEmit` clean; `npm run build` clean.
- Acceptance: dashboard Devices/Memberships/Configuration/SystemSettings pages wired to real endpoints | Remaining: dashboard settings toggles are persisted but not yet enforced in backend behavior (e.g. registration gate) — behavior wiring in a later phase.

### T-6.T1 — Test-suite determinism + CI timeout root cause (2026-11-24)
- Agent: A | Status: VERIFIED
- Files: backend/app/db.py, backend/tests/conftest.py, backend/tests/test_ws.py, backend/app/main.py
- Details: CI/local pytest wedged and only sometimes reported a timeout. Root cause was not the WS code: per-test `DROP/CREATE` of the full schema on a slow disk took 25–50 s per test, and interrupted runs left hot sqlite `-journal` files that made the next run block inside sqlite `execute`. Fixes: default test DB is now in-memory sqlite with `StaticPool` + `PRAGMA journal_mode=MEMORY, synchronous=OFF`; `DATABASE_URL` from CI is no longer overwritten (the Postgres path is actually exercised now); lifespan awaits the cancelled envelope sweeper; `Hub.stop()` awaits the pubsub task cancellation.
- Tests: `pytest -q` → **44 passed, 1 skipped in ~23 s** (was: hangs / >300 s). `ruff check app tests` clean.
- Acceptance: suite finishes deterministically on this machine | Remaining: Linux CI re-run.

### T-6.T2 — Multi-instance realtime fan-out regression tests
- Agent: A | Status: TESTED
- Files: backend/tests/test_realtime_fanout.py
- Details: Two `Hub` instances share one (fake) Redis: a media frame published by another instance is base64-decoded, stripped of the origin prefix and fanned out to both instances' sockets; an instance ignores its own echo; malformed payloads are dropped without killing the listener. This covers the path previously marked "CI-only" in KNOWN_ISSUES #1.
- Tests: 4 tests pass (1.8 s).
- Acceptance: base64 wire format + echo suppression proven | Remaining: real Redis across two uvicorn processes.

### T-6.T3 — Transport hardening (frame/envelope bounds)
- Agent: A | Status: TESTED
- Files: backend/app/core/config.py, backend/app/realtime/hub.py, backend/app/api/keys.py, backend/tests/test_fixes.py, backend/tests/test_ws.py
- Details: WS binary media frames > 64 KiB and text messages > 16 KiB are rejected (`media_frame_too_large`, `message_too_large` + close 4409); sender-key envelopes > 8 KiB → `413 envelope_too_large`; envelope entries with missing/mistyped fields now return `422` instead of raising `KeyError` (500). Revoking a device (`DELETE /devices/{id}`) now publishes `device_revoked` on the user's channels, and the hub sends an error + closes that device's live socket (code 4403) so a revoked device cannot keep transmitting or hold the floor.
- Tests: pytest → 44 passed, 1 skipped (new: oversized media/text, envelope size/field validation, revoked-device disconnect).
- Acceptance: bounds enforced and regression-tested | Remaining: reverse-proxy `client_max_body_size` / uvicorn `--ws-max-size` alignment in deploy.

### T-8.C1 — 20-member capacity + latency validation (local)
- Agent: A | Status: TESTED
- Files: scripts/loadtest_ws.py
- Details: New CLI creates a 20-member channel (devices + 20 WebSockets), measures floor-grant latency and binary media relay latency over 20 rounds, and asserts the 21st member is rejected with `409 channel_full`.
- Tests: against a live uvicorn (`run_live`, sqlite + fakeredis): floor grant **p50 1.68 ms / p95 4.97 ms / max 5.23 ms**; media relay **p50 2.60 ms / p95 6.07 ms / max 10.70 ms**; 20/20 frames delivered; 21st join → **409**. `ok: true`.
- Acceptance: §8 cap enforced and PTT latency well under the 500 ms p95 budget in this environment | Remaining: re-run against real Postgres/Redis + multi-instance deployment; Android client latency not measured (no device).

### T-10.A1 — Security audit + deploy hardening (phase 10/11)
- Agent: A | Status: IMPLEMENTED
- Files: docs/SECURITY_AUDIT.md, backend/Dockerfile, deploy/nginx/securetalk.conf, web/nginx.conf, deploy/.env.example, deploy/README.md
- Details: Wrote a control-by-control audit (auth/session, key material, realtime/media, dashboard/edge, deployment) with explicit ✅/🟡/❌ status and an honest open-gap list (Android unverified, no external pentest, multi-instance untested, dashboard tokens in localStorage, no key-transparency UX). Hardening: uvicorn `--ws-max-size 262144` + WS ping interval/timeout in the API image; CSP + Permissions-Policy added to the edge nginx and the web image's nginx; removed the nonexistent `ADMIN_TOKEN` from `.env.example`/deploy README (admin endpoints authenticate with a JWT that has role=admin).
- Tests: n/a for config; backend/web suites re-run green after the CMD/config edits (44→45 passed, 1 skipped).
- Acceptance: audit documented with release gate | Remaining: every 🟡 item requires a Docker/TLS/Android/CI environment.

### T-V.A1 — Android first real build + unit tests (local, phase 2/5/7/9 verification)
- Agent: A | Status: TESTED
- Files: android/** (no source changes needed), android/local.properties (local, git-ignored)
- Environment: Temurin JDK 17, Gradle 8.7, Android SDK platform-34 + build-tools 34.0.0 installed locally (dl.google.com is blocked from this network; artifacts were fetched via mirrors with a local-only Gradle init script — no repo build files changed).
- Details: First time the Kotlin project was compiled outside CI. `gradle test` → all modules compile; unit tests in `core/common` (media framing), `core/security` (crypto round-trips, replay window), `core/audio` (jitter buffer) and `core/network` (local floor controller) execute.
- Tests: `gradle test` → **BUILD SUCCESSFUL**, **38 test executions (debug+release), 0 failures/errors**. `:app:assembleDebug` → **BUILD SUCCESSFUL**, `app-debug.apk` 25.9 MB. `:app:bundleRelease` → **BUILD SUCCESSFUL**, `app-release.aab` 10.0 MB (R8/minify passes).
- Acceptance: Android compiles, unit tests pass, debug APK + release AAB build | Remaining: on-device PTT/audio/crypto interop (§9 performance), and the same run in GitHub Actions.

### T-6.T4 — Real PostgreSQL + Redis + two-replica verification (local)
- Agent: A | Status: VERIFIED
- Files: backend/migrations/env.py, backend/app/db.py, backend/tests/conftest.py, backend/tests/test_ws.py, backend/serve_local.py, scripts/multiinstance_check.py
- Details: Installed PostgreSQL 16.4 and Redis 5.0.14 locally (portable builds) to exercise the production stack instead of sqlite+fakeredis. Fixes found by doing so: Alembic's async runner needed a SelectorEventLoop on Windows; redis-py 8 defaults to RESP3 (`HELLO 3`) which Redis 5 rejects, so `make_redis` now pins RESP2; tests can opt into real Redis (`SECURETALK_TEST_REAL_REDIS=1`) and the WS fixture now drops/recreates schema and uses per-run display-name suffixes so a persistent Postgres does not collide; WS helpers tolerate presence events arriving before `joined`. `backend/serve_local.py` runs the real app on a selector loop on Windows.
- Tests: migrations on PostgreSQL 16.4 → upgrade → upgrade (idempotent) → downgrade -1 → upgrade, `alembic current` = 0002 head. Full pytest against real PG + real Redis: **46 passed, 1 skipped in 10m14s**. Live capacity on the real stack (20 members, 30 rounds): floor grant p95 **5.95 ms**, media relay p95 **8.42 ms**, 21st join **409**. Two uvicorn replicas sharing PG+Redis: `scripts/multiinstance_check.py` → token accepted across instances, shared presence, shared floor arbitration, media relayed from instance A to a subscriber on instance B — all true.
- Acceptance: production datastore path and multi-instance fan-out demonstrated locally | Remaining: Docker/nginx/TLS smoke test (no Docker daemon), external security review.

### T-11.A1 — Edge nginx: config validation + live TLS/WSS smoke (phase 11)
- Agent: A | Status: VERIFIED (config level)
- Files: (validation only; deploy/nginx/securetalk.conf, web/nginx.conf unchanged since hardening commit)
- Details: Installed nginx 1.27.3 for Windows and validated both shipped configs with `nginx -t` (edge TLS/upstream/WS + SPA). Then ran the edge config locally against a live API and the built SPA with a self-signed cert: HTTPS API `/health`, SPA `index.html`, and a full **WSS** session (register → device → channel → `join` → `ping/pong`) through the proxy. Response headers confirmed: HSTS, CSP, X-Frame-Options DENY, Permissions-Policy.
- Tests: `nginx -t` → both configs OK. `curl https://127.0.0.1:8443/health` → `{"status":"ok"}`; `wss://127.0.0.1:8443/ws` join/ping → `{"wss":"ok","joined":true,"ping":"pong"}`.
- Acceptance: shipped proxy config terminates TLS, serves the SPA and upgrades WebSockets | Remaining: Docker image build + compose stack (no Docker daemon here), certbot issuance, real domain.
