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
