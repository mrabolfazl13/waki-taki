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

## Progress
Tracked in `docs/PROGRESS.md` with statuses NOT_STARTED → VERIFIED. Verified means acceptance criteria demonstrated; anything unverifiable in this environment is explicitly recorded in `docs/TEST_RESULTS.md` and `docs/KNOWN_ISSUES.md`.
