# SecureTalk

Secure end-to-end-encrypted Push-to-Talk (PTT) platform: Android walkie-talkie app, FastAPI backend, React admin dashboard, offline local-network mode.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the system design and [docs/ROADMAP.md](docs/ROADMAP.md) for phase status.

## Repos layout
- `android/` — Kotlin + Jetpack Compose app (multi-module). Build: see `android/README.md` (requires Android SDK; CI produces APK/AAB).
- `backend/` — FastAPI + PostgreSQL + Redis. Dev: `pip install -r requirements.txt && uvicorn app.main:app` (from `backend/`).
- `web/` — React admin dashboard. Dev: `npm install && npm run dev` (from `web/`).
- `deploy/` — Docker Compose production deployment.

## Security posture (summary)
- Device identity keys generated on-device (Android Keystore); servers never hold private or group media keys.
- Group audio: Signal Sender-Key distribution + AES-256-GCM per packet; relay/host sees only opaque ciphertext headers.
- PTT floor control is server-authoritative. Membership changes rotate keys and bump the epoch.

## Local real-stack server (PostgreSQL + Redis)
From `backend/`:

```bash
export DATABASE_URL=postgresql+psycopg://user:pass@127.0.0.1:5432/securetalk
export ST_REDIS_URL=redis://127.0.0.1:6379/0
python serve_local.py --port 8000      # Windows-safe selector loop; or `uvicorn app.main:app`
```

`python scripts/multiinstance_check.py --a http://127.0.0.1:8000 --b http://127.0.0.1:8001`
checks that two replicas share presence, floor control and media via Redis.

## Dashboard contract check
With a live API and an empty database (first registrant becomes admin):

```bash
cd web && npm ci && API_BASE=http://127.0.0.1:8000 npm run check:contracts
```

It validates every endpoint the dashboard uses against the UI's Zod schemas
(against a non-empty DB, pass `ADMIN_USER`/`ADMIN_PASS`). CI runs it as part of
the `capacity` workflow.

## Capacity / latency smoke test
With a live API (dev: `uvicorn run_live:app --port 8000` inside `backend/`):

```bash
python scripts/loadtest_ws.py --base http://127.0.0.1:8000 --members 20 --rounds 20
```

It creates a full 20-member channel over REST + WebSocket, reports floor-grant and
media-relay latency percentiles, verifies the 21st member is rejected with 409, and
exits non-zero if a budget or check fails. Use a raised `AUTH_RATE_LIMIT` on the
server (the script retries 429s, but a run needs 22 accounts).

## Progress
Security/release review: [docs/SECURITY_AUDIT.md](docs/SECURITY_AUDIT.md).

Tracked in `docs/PROGRESS.md` with statuses NOT_STARTED → VERIFIED. Verified means acceptance criteria demonstrated; anything unverifiable in this environment is explicitly recorded in `docs/TEST_RESULTS.md` and `docs/KNOWN_ISSUES.md`.
