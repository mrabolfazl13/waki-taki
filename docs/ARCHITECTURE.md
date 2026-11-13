# SecureTalk — System Architecture

Owner: Agent A (Chief Architect). This document is the **binding contract** between agents.
Changes require updating `docs/DECISIONS.md`.

---

## 1. Monorepo Layout

```
backend/          FastAPI + PostgreSQL + Redis        (Agent F)
  app/
    api/          HTTP routers (versioned /api/v1)
    core/         config, logging, security deps
    auth/         accounts, tokens
    devices/      device registration & verification
    channels/     channels + membership
    key_management/  group key / sender-key distribution
    signaling/    WebRTC signaling relay (WS)
    floor_control/   PTT floor coordination
    realtime/     WebSocket connection hub (Redis pub/sub)
    infrastructure/  encrypted relay endpoint
    monitoring/   health, metrics, admin/audit
  migrations/     Alembic
  tests/          pytest
web/              React + TS admin dashboard          (Agent G)
android/          Kotlin monorepo (settings includes all modules) (Agents B/C/D/E)
  app/            shell app, navigation, DI wiring
  core/
    common/ network/ database/ security/ audio/ design/ permissions/
  feature/
    onboarding/ authentication/ home/ channels/ channel/ members/
    local_network/ security/ settings/ devices/ diagnostics/
deploy/           docker-compose, nginx, TLS docs     (Agent J)
.github/workflows/  CI                                (Agent J)
docs/             all project documentation           (Agent A/H)
```

## 2. Communication Modes

### 2.1 Internet mode
Android ⇄ (HTTPS + WSS) ⇄ FastAPI ⇄ { PostgreSQL, Redis } ⇄ other clients.
Audio is **never** relayed in decrypted form. v1 audio path: encrypted RTP/Opus packets sent over the same authenticated WebSocket fabric to a relay endpoint (backend relays opaque bytes + per-sender header only). `infrastructure/` isolates this behind a `MediaRelay` interface so a real SFU (LiveKit/mediasoup) can replace it without touching clients.

### 2.2 Local (offline) mode
An Android device runs a Ktor-based local host service (foreground service). Other clients on the same Wi-Fi/hotspot connect over HTTP+WS to the host. The host relays encrypted packets and coordinates floor control **but never holds group media keys** — key distribution is done client-to-client using the same sender-key envelope scheme; the host only routes ciphertext envelopes addressed to device IDs. Discovery: NSD/DNS-SD (`_securetalk._tcp`) + QR payload + manual address fallback.

### 2.3 Common abstractions (Kotlin interfaces in `core/common/`)
`CommunicationTransport`, `AudioEngine`, `SecureSessionManager`, `GroupKeyManager`,
`ChannelManager`, `PushToTalkController`, `ParticipantPresence`, `ConnectionMonitor`.
Both `OnlineTransport` and `LocalTransport` implement these; UI and audio layers are mode-agnostic.

## 3. Security Architecture

### 3.1 Device identity
- Per-device identity key pair **X25519** (DH) + **Ed25519** (signing), generated on-device.
- Private keys in **Android Keystore** (StrongBox where available); identity public keys registered with backend.
- Signed prekey + one-time prekeys (Signal X3DK-style) published for session establishment.

### 3.2 Messaging/session security
- Signal-protocol-compatible double-ratchet sessions for key-establishment and control messages, via **libsignal-android** if the artifact resolves in CI; otherwise a documented equivalent built on X25519 + HKDF-SHA256 + AES-256-GCM following the Signal X3DH/Sender-Key specifications (BouncyCastle primitives). Wrap in `core/security/CryptoEngine` interface so the choice is isolated.

### 3.3 Group media encryption (audio)
- **Signal Sender Key** model: each sender in a channel creates a Sender Key (32-byte seed) distributed pairwise encrypted (double-ratchet session) to every member; increments epoch on membership change.
- Each RTP/Opus packet encrypted with **AES-256-GCM** (SRTP-like framing per RFC 3711 layering, custom SFrame-style header since we control both ends):
  - AAD = `epoch || sender_device_id || sequence`
  - Nonce = 12 bytes: 4 random per-sender salt (in sender key material) XOR big-endian sequence-derived counter — **never reused** for a key.
  - Sequence numbers monotonically increasing; receivers keep a sliding replay window (e.g. 128).
- Membership change → coordinator rotates sender keys → new epoch; receivers reject packets with stale epoch or revoked sender IDs.
- Backend/relay/host only sees `epoch | sender_id | seq | ciphertext | tag`.

### 3.4 Non-negotiables
- No custom crypto primitives; only well-reviewed constructions.
- Backend DB never stores private keys or group media keys.
- The local host cannot decrypt participants' media.
- No silent security downgrade; transport switch re-runs session establishment.

## 4. Floor Control Protocol (PTT)

Single-speaker, server-authoritative (backend in online mode; elected host in local mode).

WS messages (JSON, both directions), type strings:

| type | direction | payload |
|---|---|---|
| `floor.request` | C→S | `{channel_id}` |
| `floor.granted` | S→C | `{channel_id, holder_device_id, lease_expires_ms}` |
| `floor.denied` | S→C | `{channel_id, reason, holder_device_id, queue_position}` |
| `floor.release` | C→S | `{channel_id}` |
| `floor.released` | S→all | `{channel_id, previous_holder}` |
| `floor.event` | S→all | `{channel_id, state, holder_device_id}` (state: idle/speaking) |
| `floor.override` | C→S | `{channel_id}` (admin/priority only) |

Rules: lease TTL 60 s (auto-release, renewed by audio activity heartbeat); request timeout 3 s → denial; simultaneous requests resolved by server receive order (Redis lock per channel); rate limit 10 requests/10 s/device; queue depth 20 (= channel max). Server state in Redis: `floor:{channel_id}` hash. Client UI never grants itself the floor locally.

## 5. REST API (v1) — summary

Base `/api/v1`. Auth: JWT access token (15 min) + refresh token (30 d, rotation). Admin endpoints under `/api/v1/admin` (role-gated).

- `POST /auth/register` `{display_name, password?}` → account + tokens
- `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`
- `POST /devices` (register device: identity pubkey, signed prekey, prekeys) · `GET /devices` (own) · `DELETE /devices/{id}`
- `GET /users/{id}/keys` — public key discovery
- `POST /channels` `{name}` (creator = owner) · `GET /channels` · `DELETE /channels/{id}`
- `POST /channels/{id}/join` (invite code) · `POST /channels/{id}/leave`
- `POST /channels/{id}/invites` → `{invite_code, qr_payload}` · `POST /invites/validate`
- `POST /channels/{id}/members/{user_id}/remove` (owner/admin)
- `POST /channels/{id}/keys/distribute` — upload sender-key envelopes `{recipient_device_id, envelope_ciphertext}`
- `GET  /channels/{id}/keys/envelopes` — fetch envelopes addressed to me; ack + delete
- `POST /channels/{id}/epoch/rotate` — bump epoch (owner or member-leave trigger)
- `WS /ws?token=...` — signaling + floor + presence + relay (§4, §6)
- Admin: `GET /admin/users`, `POST /admin/users/{id}/disable`, `GET /admin/channels`, `GET /admin/health`, `GET /admin/audit`, `GET /admin/security-events`, `POST /admin/roles`
- `GET /health` (no sensitive info)

Constraints: max 20 members per channel (enforced server-side); Pydantic validation on all inputs; rate limiting on auth + invite endpoints; structured JSON logging with secret redaction.

## 6. WebSocket Event Specification

Single authenticated WS per client. Frames: `{"type": "...", "data": {...}}`.

Client→Server: `signaling.offer|answer|ice` `{channel_id, target_device_id, sdp/ice}`,
`floor.*` (§4), `media.send` (binary frame, §3.3 header), `ping`.

Server→Client: `presence.update` `{channel_id, device_id, state}`,
`signaling.*` (relayed, never parsed), `floor.*` (§4),
`media.recv` (binary: `epoch(4) | sender_device_id(16) | seq(4) | ciphertext+tag`),
`channel.event` (member joined/left/removed, epoch rotated), `pong`, `error`.

Media relay fan-out: backend reads only the plaintext header fields (epoch, sender, seq), checks sender authorization + floor ownership, then forwards the identical binary payload to other channel members. Redis pub/sub `media:{channel_id}` coordinates multi-instance fan-out.

## 7. Database Schema (PostgreSQL, Alembic)

- `users` (id, display_name, password_hash nullable, role enum user/admin, disabled, created_at)
- `devices` (id, user_id FK, identity_pubkey, signing_pubkey, signed_prekey, name, registered_at, revoked_at)
- `one_time_prekeys` (id, device_id FK, key_id, pubkey, used bool)
- `channels` (id, name, owner_id FK, created_at, deleted_at)
- `memberships` (channel_id, user_id, role enum member/admin, joined_at, unique pair)
- `invites` (id, channel_id, code_hash, created_by, expires_at, used_count, max_uses)
- `key_envelopes` (id, channel_id, sender_device_id, recipient_device_id, epoch, envelope bytea, created_at) — deleted on ack
- `security_events` (id, type, severity, channel_id?, user_id?, detail jsonb, created_at)
- `audit_log` (id, actor_admin_id, action, target, detail jsonb, created_at)
- `revocations` (id, device_id/channel_id, epoch, created_at)

Retention: key envelopes ephemeral (deleted on ack or 24 h); no audio content ever stored; security events pruned after 90 d.

Redis keys: `presence:{channel_id}` (set), `floor:{channel_id}` (hash), `ratelimit:{bucket}` (TTL counters), `ws:{device_id}` routing map, pub/sub `media:{channel_id}`.

## 8. Network Transitions (ConnectionMonitor states)

`ONLINE_CONNECTED → DEGRADED → RECONNECTING → {ONLINE_CONNECTED | LOCAL_AVAILABLE | OFFLINE}`.
On internet loss: client probes known local hosts (NSD); if found, prompts user to switch (explicit action, re-establishes security sessions); never silently continues. On host loss (local mode): notify members, stop media, offer host re-creation. Expired/invalid security state is always re-established, never reused.

## 9. Performance Targets (from spec)

Local p95 latency ≤ 250 ms; internet ≤ 500 ms; PTT grant ≤ 300 ms. Measured results recorded in `docs/TEST_RESULTS.md` with test conditions; unmeasurable items marked NOT VERIFIED.

## 10. Deployment

`deploy/docker-compose.yml`: `api` (FastAPI+uvicorn), `postgres:16`, `redis:7`, `nginx` (TLS termination, WSS proxy). `.env.example` for secrets. Health checks: `/health` (liveness, no detail) and `/health/details` (admin-gated).
