# Known Issues — open items found during integration (Agent H, 2026-11-14)

1. **Fakeredis + binary pub/sub crash (medium, blocks multi-instance media test locally).**
   `app/realtime/hub.py` `_pubsub_loop` dies with
   `UnicodeDecodeError: 'utf-8' codec can't decode byte 0xc1` when a binary frame is published to
   `media:{channel_id}` while the Redis connection uses `decode_responses=True` (the factory in
   `app/db.py make_redis` and the fakeredis instance in tests/live launcher both do). The loop
   catches and logs the error, but after the crash cross-instance media/events fan-out stops until
   reconnect. **Likely affects real Redis too** (redis-py decodes pub/sub payloads on
   `decode_responses=True` connections); the pubsub connection should be created with
   `decode_responses=False`, or media should be base64-encoded on the wire. Verified live via
   uvicorn log; not reproduced against real Redis (not available locally).
2. **Duplicate `floor.released` frames (low).** On `floor.release`, the releasing client receives
   the `floor.released` frame twice (once via `_floor_release` direct send, once via the broadcast
   loop that includes the sender). Same for some error frames observed twice in scripted tests.
   Harmless but should be deduped (`_floor_release` broadcast should exclude the sender).
3. **`GET /channels/{id}/members` does not exist (low).** MembershipsPage in the dashboard calls
   `/admin/memberships`, and there is no per-channel member-list endpoint either — both 404.
   The page renders its "data unavailable" state, but a member-listing endpoint is needed for
   the admin dashboard (and for clients) eventually.
4. **Dashboard endpoints not implemented in Phase 1 backend (informational).** `/admin/devices`,
   `/admin/config`, `/admin/settings`, `GET /admin/roles` return 404; pages degrade gracefully.
   Either implement backend endpoints or remove/re-scope the pages in a later phase.
5. **Envelope fetch requires `device_id` query param (informational / contract note).**
   `GET /channels/{id}/keys/envelopes?device_id=…` is mandatory; ARCHITECTURE §5 does not mention
   the parameter. Documented here so client agents (B–E) know.
6. **Invite creation returns 200, not 201 (trivial).** §5 does not pin a status; noting actual
   behavior (200) for client agents.
7. **Live-run helper requires fakeredis injection (informational).** `app.main:app` defaults to a
   real Redis URL; with no local Redis the backend must be started via `backend/run_live.py`
   (`uvicorn run_live:app --port 8000`, sqlite + fakeredis). Run_live.py is integration tooling
   only — production must use `app.main:app` with PG/Redis.
8. **Local HTTP proxy env interferes with local testing (environment note).** The machine exports
   `http_proxy=http://127.0.0.1:8118`; all local curl/python testing must bypass it
   (`curl --noproxy '*'`, popping proxy env vars in scripts). Vite dev server also binds
   `[::1]:5173` only, so `localhost` (not `127.0.0.1`) must be used from the same stack.
