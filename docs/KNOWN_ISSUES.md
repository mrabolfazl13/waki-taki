# Known Issues — open items found during integration (Agent H, 2026-11-14)

1. ~~**Fakeredis + binary pub/sub crash**~~ — **FIXED (2026-11-23).** Media payloads published to
   `media:{channel_id}` are now base64 text (`hub._on_remote_media` decodes), so a single normal
   pubsub client works with `decode_responses=True` on both real Redis and fakeredis. The brittle
   second-client fallback was removed. Not yet exercised across two live API processes; unit suite
   no longer emits the `PubSub.__del__` warning.
2. ~~**Duplicate `floor.released` frames**~~ — already fixed in `hub._floor_release` (sender
   excluded from the broadcast). Stale entry retained for history.

6. **Envelope fetch requires `device_id` query param (informational / contract note).**
   `GET /channels/{id}/keys/envelopes?device_id=…` is mandatory; ARCHITECTURE §5 does not mention
   the parameter. Android `SecureTalkApi.fetchKeyEnvelopes` now supplies it (2026-11-23).
7. **Invite creation returns 200, not 201 (trivial).** §5 does not pin a status; noting actual
   behavior (200) for client agents.
8. **Live-run helper requires fakeredis injection (informational).** `app.main:app` defaults to a
   real Redis URL; with no local Redis the backend must be started via `backend/run_live.py`
   (`uvicorn run_live:app --port 8000`, sqlite + fakeredis). Run_live.py is integration tooling
   only — production must use `app.main:app` with PG/Redis.
9. **Local HTTP proxy env interferes with local testing (environment note).** The machine exports
   `http_proxy=http://127.0.0.1:8118`; all local curl/python testing must bypass it
   (`curl --noproxy '*'`, popping proxy env vars in scripts). Vite dev server also binds
   `[::1]:5173` only, so `localhost` (not `127.0.0.1`) must be used from the same stack.
