# Test Results — live backend ↔ dashboard integration (Agent H, 2026-11-14)

## Test conditions

- **Backend:** FastAPI/uvicorn on `http://127.0.0.1:8000`, launched via `backend/run_live.py`
  (`uvicorn run_live:app --port 8000`) — **sqlite (aiosqlite, file `backend/live.db`) + fakeredis**
  because no PostgreSQL/Redis service exists on this machine. This is NOT the production stack;
  the real-PG/Redis path is exercised only in CI (`backend.yml` service containers).
- **Dashboard:** `npm run dev` (Vite 5.4.21) on `http://localhost:5173` with the vite proxy
  `/api → http://localhost:8000`. Production build also re-verified (`tsc --noEmit` + `vite build`, clean).
- **Client tools:** curl (with `--noproxy '*'` — the machine has an HTTP proxy env at 127.0.0.1:8118
  that intercepts localhost otherwise), python 3.12 `websockets` + `httpx` from `backend/.venv`.
- **Note:** fakeredis pub/sub cannot deliver binary frames when the connection uses
  `decode_responses=True` (UnicodeDecodeError in the hub pubsub loop — see KNOWN_ISSUES.md);
  same-instance fan-out (the path used in these tests) works.

## 1. Backend live start — PASS

| Check | Result |
|---|---|
| `uvicorn run_live:app` startup | OK, "Application startup complete", tables auto-created |
| `GET /health` | `200 {"status":"ok"}` |
| `GET /api/v1/admin/health` (admin token) | `200 {"status":"ok","redis":true}` (fakeredis ping) |

## 2. Auth E2E (curl) — PASS

| Step | Request | Actual result |
|---|---|---|
| Register first user "alice" | POST /auth/register | `201` TokenPair; JWT payload carries `"role":"admin"` (bootstrap admin) |
| Register second user "bob" | POST /auth/register | `201` TokenPair, `role":"user"` |
| Refresh rotation | POST /auth/refresh (alice refresh) | `200` new TokenPair |
| **Replay old refresh** | POST /auth/refresh (same refresh again) | `401 {"detail":"refresh_invalid"}` + security event `auth.refresh_reuse` recorded |
| Login | POST /auth/login alice | `200` TokenPair |
| Wrong password | POST /auth/login bob/wrongpass | `401 {"detail":"invalid_credentials"}` + `auth.login_failed` event |

## 3. Devices & key discovery — PASS

| Step | Result |
|---|---|
| POST /devices (alice, X25519/Ed25519/spe-prekey base64 test keys + 2 one-time prekeys) | `201 {"id":"ace4…"}` — no key material echoed back |
| GET /devices (alice) | `200` bare array `[{id, name, registered_at}]` — **public keys NOT returned in own-device list** |
| POST /devices (bob) | `201` |
| GET /users/{alice_id}/keys (bob) | `200` — public keys only: identity_pubkey, signing_pubkey, signed_prekey, one_time_prekeys with key_id + pubkey (by design, this is the public-discovery endpoint) |

## 4. Channels / invites / membership — PASS

| Step | Result |
|---|---|
| POST /channels | `201 {"id","name","epoch":1,"member_count":1,"is_owner":true}` |
| POST /channels/{id}/invites | `200 {"invite_code","qr_payload"}` (qr_payload = base64 `securetalk://join?c=…`) |
| POST /invites/validate (bob) | `200 {"valid":true,"channel_id","channel_name","reason":null}` |
| POST /channels/{id}/join (bob, valid code) | `200` ChannelOut, member_count 2 |
| **Join with forged code** | `403 {"detail":"invalid_invite"}` + `invite.invalid_use` security event |
| Duplicate join | idempotent `200` (still 2 members) |
| GET /channels (alice) | `200` bare array of ChannelOut |
| POST /channels/{id}/epoch/rotate (bob, non-owner) | `403 {"detail":"epoch_rotate_not_allowed"}` |
| POST /channels/{id}/epoch/rotate (alice, owner) | `200 {"channel_id","epoch":2}` + security event |

## 5. Sender-key envelopes — PASS

| Step | Result |
|---|---|
| Distribute to device of non-member | `403 {"detail":"recipient_not_channel_member"}` |
| Distribute to bob's device (alice, epoch 1) | `201 {"stored":1,"recipient_device_ids":[…]}` |
| **Bob distributes under alice's sender device** | `403 {"detail":"sender_device_not_owned"}` |
| Fetch envelopes (bob, `?device_id=` required) | `200 {"envelopes":[{"envelope_id","sender_device_id","epoch","envelope_ciphertext"}]}` — ciphertext only |
| Ack | `204`, then re-fetch → `{"envelopes":[]}` (**delete-on-ack verified**) |

## 6. Admin gating — PASS

| Step | Result |
|---|---|
| GET /admin/users (alice, admin) | `200 [{id, display_name, role, disabled}]` |
| GET /admin/users (bob, user) | `403 {"detail":"admin_required"}` |
| GET /admin/users (no token) | `401 {"detail":"missing credentials"}` |
| GET /admin/channels | `200 [{id, name, owner_id, epoch, member_count, created_at}]` (includes owner_id/created_at unlike per-user /channels) |
| GET /admin/audit | `200 []` |
| GET /admin/security-events | `200` — 9 events recorded live: refresh_reuse, login_failed, invite.invalid_use, device.registered, channel.joined, channel.epoch_rotated, account.created |
| POST /admin/roles (nonexistent user) | `404 {"detail":"user_not_found"}` |

## 7. WebSocket floor control + media relay (scripted, python websockets) — PASS

Frames are `{"type","data"}`; join requires `{"channel_id","device_id"}` where the device
must be owned by the authenticated user AND a channel member.

| Step | Actual result |
|---|---|
| Unauthenticated WS (`?token=garbage`) | `{"type":"error","data":{"code":"invalid_token"}}` then close 4401 |
| Text before join | `{"code":"not_joined"}` |
| Join with device not owned / not member | `device_not_owned` / `not_a_member` (+ `ws.unauthorized_join` event) |
| Valid joins (alice + bob) | `joined {channel_id, device_id, epoch, present:[…]}`, `floor.event idle`, `channel.event online` broadcast to peer |
| ping | `pong` |
| Media without floor | `{"code":"floor_not_held"}` |
| floor.request (bob) | `floor.granted {holder_device_id, lease_expires_ms}` + `floor.event speaking` broadcast |
| floor.request (alice while bob holds) | `floor.denied {reason:"busy", holder_device_id, queue_position:1}` |
| floor.release (bob) | `floor.released {previous_holder}` (+ queued request auto-promoted → `floor.granted`) |
| **Binary media relay** (alice holds floor, frame `epoch(4)\|sender(16)\|seq(4)\|ciphertext`, sender==device verified) | bob received identical binary frame (`BIN len=29`); sender excluded from fan-out |
| **Replay same seq** | `{"code":"replay_detected"}` + `media.replay` security event; nothing relayed |

## 8. Removal / unauthorized access — PASS

| Step | Result |
|---|---|
| POST /channels/{id}/members/{bob}/remove (alice, owner) | `204` |
| Bob distribute after removal | `403 {"detail":"not_a_member"}` |
| Bob fetch envelopes after removal | `403 {"detail":"not_a_member"}` |

## 9. Dashboard ↔ live API — PASS

- `GET http://localhost:5173/` → `200`, page title `SecureTalk Admin`.
- Login through the vite proxy: `POST /api/v1/auth/login` → `200` TokenPair.
- Authenticated proxied call: `GET /api/v1/admin/users` → `200`; `GET /api/v1/admin/health` → `200 {"status":"ok","redis":true}`.
- Zod schemas corrected against actual responses (see below); `tsc --noEmit` + `vite build` clean after fixes.

### Schema drift fixed (web/src/lib/schemas.ts, web/src/pages/AdminRolesPage.tsx)

| Schema | Before (assumed) | Actual backend | Fix |
|---|---|---|---|
| tokenPairSchema | refresh_token optional | always present | required |
| channelSchema | owner_id, created_at, member_count | `+ epoch`, `+ is_owner` on per-user /channels | added optional epoch/is_owner |
| adminRoleSchema | `{id?, name, permissions?}` role objects | POST /admin/roles takes `{user_id, role}` and returns UserOut; no GET /admin/roles | replaced with `{user_id, role}` input + userSchema response; AdminRolesPage rewritten as role-assignment page |
| listSchema | accepted bare array or `{items}` | actual endpoints return bare arrays | unchanged (compatible) |
| userSchema / deviceSchema / securityEventSchema / auditLogSchema / healthSchema | — | match actual responses (device list never includes key material; disabled always present) | no change needed |

## Explicitly NOT verified locally

- **Real PostgreSQL / real Redis** — everything ran on aiosqlite + fakeredis. PG-specific behavior
  ( Alembic migration on PG, JSONB columns, Redis pub/sub across instances, floor CAS under real Redis)
  is CI-only.
- **Multi-instance WS fan-out via Redis pub/sub** — the binary `media:{channel_id}` pub/sub path
  crashes the hub's pubsub listener under fakeredis+decode_responses (KNOWN_ISSUES #2); only
  same-instance fan-out was verified live.
- **WebRTC signaling relay** (`signaling.offer/answer/ice`) — code path exists and is unit-tested,
  but no live SDP/ICE exchange was run (no WebRTC client available).
- **Android clients** — not compiled locally (no SDK); no on-device E2E.
- **Docker / nginx TLS / CI workflows** — no Docker daemon; CI-only.
- **Performance targets (§9)** — p95 latencies and PTT grant timing NOT VERIFIED (no load tooling run).
- **Envelope 24 h TTL sweeper** — verified by unit test only; not waited for live.
- **Rate limiting under load** — limits confirmed via pytest only, not hammered live.

---

# Test Results — suite determinism, hardening, capacity (Agent A, 2026-11-24)

## Environment

Windows 11, Python 3.12.10, Node 24, no Android SDK, no Docker/Postgres/Redis.
Backend tests use in-memory sqlite + fakeredis by default; `DATABASE_URL` (CI) is
now respected instead of being overwritten.

## 1. Backend suite — PASS (was hanging)

| Run | Before | After |
|---|---|---|
| `pytest -q` (44 tests) | hung; per-test schema DDL took 25–50 s on this disk; interrupted runs left hot sqlite journals that blocked the next run | **44 passed, 1 skipped in 23.1 s** |

Root cause/fixes (see PROGRESS T-6.T1): in-memory sqlite + StaticPool with
`journal_mode=MEMORY`, `synchronous=OFF`; CI `DATABASE_URL` preserved; lifespan
awaits the cancelled sweeper; `Hub.stop()` awaits the pubsub task; interrupted
runs can no longer wedge the next one on a stale journal.

`ruff check app tests` → clean. Web `tsc --noEmit` + `vite build` → clean
(built in 44.8 s; chunks: index 164.8 kB, react 163.9 kB, recharts 385.2 kB).

## 2. New regression coverage — PASS

| Test file | Covers |
|---|---|
| `tests/test_realtime_fanout.py` (4) | two Hubs on one Redis: cross-instance media fan-out, own-echo suppression, malformed payload tolerance, header round-trip (KNOWN_ISSUES #1) |
| `tests/test_fixes.py::test_envelope_size_and_fields_are_validated` | envelope field validation (`422`), size cap (`413`), happy path (`201`) |
| `tests/test_ws.py::TestTransportLimits` | oversized media frame → `media_frame_too_large`; oversized text → `message_too_large` + close 4409 |
| `tests/test_ws.py::TestDeviceRevocation` | `DELETE /devices/{id}` → live socket gets `device_revoked` and closes; device leaves the roster |

## 3. Phase 8 capacity/latency (local, single instance) — PASS

`scripts/loadtest_ws.py --members 20 --rounds 20` against `run_live` on 127.0.0.1:8010:

| Metric | p50 | p95 | max |
|---|---|---|---|
| Floor grant latency | 1.68 ms | **4.97 ms** | 5.23 ms |
| Media relay latency | 2.60 ms | **6.07 ms** | 10.70 ms |

- 20/20 media frames delivered; 20 members joined; 21st member join → **409 channel_full**.
- Result JSON: `"ok": true` (budgets: p95 grant ≤ 500 ms, p95 relay ≤ 300 ms).
- **Not covered:** real Postgres/Redis, multi-instance/edge nginx, Android
  client-side audio latency, and long-running load. Note: capacity runs need a
  raised `AUTH_RATE_LIMIT` (the script retries 429s otherwise).

## 4. Still not verifiable in this environment

Android compile/unit tests (no SDK at `ANDROID_HOME=G:\SDK`), Docker image builds,
TLS/WSS through nginx, real Redis pub/sub across processes, WebRTC SDP/ICE with a
real browser/device client.

---

# Test Results — Android first real build (Agent A, 2026-11-24)

Environment: Windows, Temurin JDK 17.0.19, Gradle 8.7, Android SDK platform-34 /
build-tools 34.0.0 (installed locally; dl.google.com is unreachable from this
network so a local-only Gradle mirror init script was used — repository build
files were not modified).

| Command | Result |
|---|---|
| `gradle :core:common:testDebugUnitTest` | BUILD SUCCESSFUL (3m25s) |
| `gradle test` (all modules) | **BUILD SUCCESSFUL** in 30m; **38 test executions (debug+release), 0 failures, 0 errors** |
| `gradle :app:assembleDebug` | **BUILD SUCCESSFUL**; `app-debug.apk` = 25,859,816 bytes |
| `gradle :app:bundleRelease` | **BUILD SUCCESSFUL**; `app-release.aab` = 9,994,881 bytes (R8/minify clean) |

Tests executed: `MediaPacketCodecTest` (3), `CryptoEngineTest` (5), `ReplayWindowTest` (2),
`JitterBufferTest` (3), `LocalFloorControllerTest` (6) — each in debug and release.

This resolves the long-standing "Android not compiled locally" blocker for phases
2/5/7/9 at the build level. Still NOT verified: on-device audio path, Bluetooth
routing, PTT round trip against a live server, crypto interop with a second device,
and the §9 performance targets.

---

# Test Results — real PostgreSQL/Redis and multi-replica (Agent A, 2026-11-24)

PostgreSQL 16.4 (portable binaries, port 5433) and Redis 5.0.14 (port 6380) were
installed locally to exercise the production datastore path instead of
sqlite+fakeredis.

## Migrations on real PostgreSQL — PASS

`alembic upgrade head` → `upgrade head` (idempotent) → `downgrade -1` → `upgrade head`;
`alembic current` = `0002_admin_settings (head)`.

## Full backend suite on real PG + real Redis — PASS

```
DATABASE_URL=postgresql+psycopg://securetalk@127.0.0.1:5433/securetalk_test SECURETALK_TEST_REAL_REDIS=1 ST_REDIS_URL=redis://127.0.0.1:6380/15 pytest -q
→ 46 passed, 1 skipped in 614.98s
```

Fixes required to make the real path work (all landed):

| Issue | Fix |
|---|---|
| psycopg async refuses Windows Proactor loop | Alembic env sets SelectorEventLoop on win32; `backend/serve_local.py` runs uvicorn on a selector loop |
| redis-py 8 sends `HELLO 3`, Redis 5 rejects it | `make_redis` pins `protocol=2` |
| Persistent Postgres keeps rows between tests | WS fixture drops/recreates schema; unique display-name suffix per run |
| Real Redis can deliver presence before `joined` | WS test helpers receive until the expected type |

## Live capacity on the real stack — PASS

`scripts/loadtest_ws.py --members 20 --rounds 30` against uvicorn + PG + Redis:

| Metric | p50 | p95 | max |
|---|---|---|---|
| Floor grant | 2.38 ms | **5.95 ms** | 6.32 ms |
| Media relay | 3.97 ms | **8.42 ms** | 10.88 ms |

30/30 frames delivered, 21st member → 409, `ok: true`.

## Two API replicas — PASS

Two uvicorn processes (ports 8011/8012) sharing one PostgreSQL + one Redis;
`scripts/multiinstance_check.py`:

```json
{"ok": true, "token_accepted_on_other_instance": true, "presence_shared": true,
 "floor_shared": true, "media_crossed_instances": true}
```

So a client on replica A and a subscriber on replica B share presence, compete for
one floor, and media published on A arrives on B. Not covered: nginx/TLS, Docker
images, and more than two replicas.

---

# Test Results — edge nginx TLS/WSS smoke (Agent A, 2026-11-24)

nginx 1.27.3 (Windows) was used to validate the shipped proxy configs without Docker.

| Check | Result |
|---|---|
| `nginx -t` on `deploy/nginx/securetalk.conf` (upstreams pointed at localhost, self-signed cert) | **syntax ok / test successful** |
| `nginx -t` on `web/nginx.conf` (SPA) | **syntax ok / test successful** |
| `https://…/health` through the edge | `200 {"status":"ok"}` |
| SPA `https://…/` through the edge | `index.html` served |
| Response headers | `Strict-Transport-Security`, `Content-Security-Policy`, `X-Frame-Options: DENY`, `Permissions-Policy` present |
| **WSS** `wss://…/ws?token=…` | register → device → channel → `join` → `joined` → `ping` → `pong` ✅ |

Not covered: Docker builds, certbot/real certificates, HTTP/2 over a real browser,
and load through nginx.

---

# Test Results — dashboard ↔ backend contract check (Agent A, 2026-11-24)

`web/scripts/contract-check.ts` (run via `npm run check:contracts`) validates the
real API responses against the same Zod schemas the admin UI parses with.

| Check group | Result |
|---|---|
| auth register/login + refresh rotation | ✅ |
| `/admin/users`, `/admin/channels`, `/admin/security-events`, `/admin/audit` | ✅ |
| `/admin/health`, `/admin/config`, `/admin/settings` (+ PUT round-trip) | ✅ |
| `/admin/memberships`, `/admin/devices` | ✅ |
| user disable → enable | ✅ |
| per-user `/devices`, `/channels` | ✅ |
| **Total** | **17/17 checks, `"ok": true`** on a fresh live API |

Wired into `.github/workflows/capacity.yml` (starts the API, then runs the
contract check before the 20-member load test).

---

# Production deployment — Ubuntu host 2.189.255.225 (Agent A, 2026-11-24)

Existing host services reused (no new databases/redis/nginx):
`shared_postgres` (db `wakitaki`), `shared_redis` (db 5), edge container
`futsal_nginx` on networks `ubuntu_futsal_network` + `shared_infra`.

| Check | Result |
|---|---|
| `docker compose config` (2 app services only) | OK |
| `alembic upgrade head` on shared PostgreSQL 17 | 0001 + 0002 applied |
| `wakitaki_api` / `wakitaki_web` | healthy; API `/health` = `{"status":"ok"}` |
| Edge nginx `nginx -t` + reload | OK; pre-existing sites unaffected |
| `http://wakitaki.*/health` | `301` → https |
| `https://wakitaki.absadeghi.ir/health` (via `--resolve` to the host) | `200 {"status":"ok"}` + HSTS/CSP/X-Frame/Permissions headers |
| `https://wakitaki.sadsirco.ir/health` (via `--resolve` to the host) | `200 {"status":"ok"}` |
| SPA at `/` | `index.html` served |
| First registration | `201` (after the enum fix), promoted to admin; `GET /admin/users` → `200` |

**Bug found only in production:** PostgreSQL rejected registrations with
`invalid input value for enum user_role: "USER"` because `Enum(Role)` persisted
enum names while migration 0001 defines lowercase enum values. Fixed in
`backend/app/models.py` (`values_callable`) and guarded by
`test_orm_enums_match_migrated_enum_values`.

Public reachability is blocked on DNS only:
`wakitaki.absadeghi.ir` → NXDOMAIN; `wakitaki.sadsirco.ir` → 77.90.59.77 /
5.231.23.233 (do not serve this app). Both need to resolve to 2.189.255.225.

## Post-incident recovery + public verification (2026-11-24 ~00:40 UTC)

Host rebooted by the owner; 2 GB swap added permanently; containers restarted.

| Check | Result |
|---|---|
| `free -m` / `swapon --show` | 1967 MB RAM, ≈440 MB available, **2 GB swap active** |
| `wakitaki_api` / `wakitaki_web` | healthy (105 MB / 9 MB) |
| Public `https://wakitaki.sadsirco.ir/health` | `200 {"status":"ok"}` |
| Public SPA `/` | `200`, `index.html` |
| Public admin API | login 200, `/admin/users` 200, `/admin/health` → `{"status":"ok","redis":true}` |
| Public WSS `wss://…/ws` | join ✔, ping → pong ✔ |
| Origin with `Host: wakitaki.absadeghi.ir` | `200 {"status":"ok"}` |
| `wakitaki.absadeghi.ir` DNS (1.1.1.1 / 8.8.8.8) | **NXDOMAIN** — record still missing |
