# Security & Release Audit (Phase 10/11)

**Date:** 2026-11-24 · **Scope:** backend, web dashboard, deploy config. Android is
included only where source-level review is possible (no SDK/device here).

Status legend: ✅ verified locally (test/measurement) · 🟡 implemented, not verified
in this environment · ❌ open gap.

## 1. Authentication & session

| Control | Status | Evidence |
|---|---|---|
| Passwords hashed with bcrypt, constant-time verify | ✅ | `core/security.py`; `test_auth.py` |
| Access token 15 min, refresh 30 d, refresh rotation single-use | ✅ | reuse → `401 refresh_invalid` + `auth.refresh_reuse` event |
| Token type confusion rejected (`typ` claim) | ✅ | `decode_token(expected_type=…)` |
| Logout revokes the refresh token | ✅ | `test_auth.py` |
| Disabled user's access token stops working immediately | ✅ | `test_admin.py::test_admin_flows` |
| Auth endpoints rate-limited per IP | ✅ | `core/ratelimit.py`, 429 tested |
| First-registrant bootstrap admin disabled in prod | ✅ | `main._assert_prod_settings`; refuses placeholder secrets, auto-create tables |

## 2. Devices & key material

| Control | Status | Evidence |
|---|---|---|
| Server stores only public identity/prekeys | ✅ | `test_auth.py`, `test_admin_dashboard.py` assert no key fields in responses |
| Device list never returns key material | ✅ | `DeviceOut` excludes it |
| Revoking a device closes its live WS and roster entry | ✅ | `TestDeviceRevocation` (new) |
| Envelope ciphertext size capped (8 KiB) + fields validated | ✅ | `test_envelope_size_and_fields_are_validated` (new) |
| Envelopes ack-deleted, 24 h sweeper | ✅ | `test_keys.py`; sweeper covered by unit test |
| Private/group keys never sent to or stored on the server | 🟡 | by construction (client generates); Android crypto awaits CI/on-device proof |

## 3. Realtime & media

| Control | Status | Evidence |
|---|---|---|
| WS requires valid access token; invalid → `invalid_token` + close 4401 | ✅ | `test_ws.py` |
| Join verifies device ownership **and** channel membership | ✅ | `device_not_owned` / `not_a_member` tests |
| Server-authoritative floor, lease, queue, rate limit, admin override | ✅ | `test_ws.py::TestFloorControl` |
| Media relayed only from the floor holder | ✅ | `floor_not_held` test |
| Epoch enforcement rejects stale ciphertext | ✅ | `epoch_mismatch` test |
| Replay window (128) rejects duplicate/stale seq | ✅ | `replay_detected`, `seq_too_old` |
| Frame/text size bounds (64 KiB / 16 KiB) | ✅ | `TestTransportLimits` (new) |
| Cross-instance fan-out via Redis pub/sub, own-echo suppressed | ✅ (single fake Redis) | `test_realtime_fanout.py` (new) |
| Revoked device cannot keep transmitting | ✅ | hub closes socket on `device_revoked` |
| Server never decrypts/stores audio | ✅ | opaque relay only; Redis persistence disabled in compose |

## 4. Web dashboard & edge

| Control | Status | Evidence |
|---|---|---|
| HSTS, nosniff, DENY framing, no-referrer, Permissions-Policy | ✅ (config) | `deploy/nginx/securetalk.conf` |
| CSP restricting script/style/connect origins | 🟡 | added to edge + web nginx; needs a browser smoke test |
| Admin endpoints role-gated (403 non-admin, 401 anonymous) | ✅ | `test_admin.py` |
| Dashboard has no fake metrics; unavailable ≠ zero | ✅ | `CURRENT_STATE.md` + page code |
| Dashboard tokens live in `localStorage` | ❌ accepted risk | XSS would expose tokens; CSP + no third-party scripts mitigate. Consider httpOnly cookie + CSRF token in a later phase. |

## 5. Deployment configuration

| Item | Status |
|---|---|
| Only 80/443 exposed; postgres/redis/api/web on the internal network | 🟡 `deploy/docker-compose.yml` (not run here — no Docker daemon) |
| Redis persistence disabled (ephemeral presence/floor only) | 🟡 compose command; not smoke-tested |
| `uvicorn --ws-max-size 262144` + `--ws-ping-interval/timeout 20` | 🟡 `backend/Dockerfile` (build unverified here) |
| Alembic migration path + idempotency | 🟡 verified on sqlite and in CI job definition; Postgres run pending CI |
| Secrets are placeholders only in `.env.example`; no `ADMIN_TOKEN` (admin = JWT role) | ✅ (docs corrected 2026-11-24) |

## 6. Open gaps (honest list)

1. **Android not compiled/tested here** — all Kotlin security code (X3DH, ratchet,
   sender keys, Keystore sealing) must be proven by CI `./gradlew test` + on-device
   interop. Until then phase 5 remains "implemented", not "verified".
2. **No external penetration test / third-party audit.**
3. **Multi-instance deployment untested** — cross-instance fan-out is covered only
   with two Hub objects on one fake Redis.
4. **No load test under real Postgres/Redis or through nginx**; local numbers are in
   `TEST_RESULTS.md`.
5. **WebRTC signaling has no live SDP/ICE exchange test** (relay path is unit-tested).
6. **Dashboard token storage** (see §4) and **no CSRF** because auth is header-based.
7. **No key transparency / safety-number verification UX** in the Android app yet —
   users cannot detect a malicious server substituting prekeys.

## 7. Release gate (phase 11) — what must pass before a production tag

- [ ] GitHub Actions green: `backend`, `web`, `android` (with `./gradlew test`), `docker` image builds + migration check.
- [ ] `docker compose up -d` on a real host, `/health` green through nginx HTTPS.
- [ ] TLS certificate (certbot) issued; WSS handshake verified from a client.
- [ ] Backend tests re-run against Postgres + real Redis (CI job).
- [ ] `scripts/loadtest_ws.py` against the composed stack (raise `AUTH_RATE_LIMIT` for the run only).
- [ ] Two API replicas behind nginx: media reaches clients connected to the other replica.
- [ ] Android release build installed on two devices: PTT round trip online and in local mode, key-rotation on member removal.
- [ ] Backup/restore drill (`pg_dump` / `pg_restore`) per `deploy/README.md`.
- [ ] Rotate `SECRET_KEY`, `INVITE_SECRET`, `POSTGRES_PASSWORD`; confirm `ENVIRONMENT=prod` refuses placeholders.
