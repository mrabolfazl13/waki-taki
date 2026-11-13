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
