# Decision Log

| # | Decision | Rationale | Date |
|---|---|---|---|
| D1 | Greenfield build; empty repo | Audit found no existing code | 2026-11-14 |
| D2 | v1 audio relay over authenticated WebSocket binary frames (opaque encrypted packets) instead of a full SFU | No Docker/SFU infra available in this environment; relay is E2EE-opaque so security properties hold; `MediaRelay` interface isolates future SFU (LiveKit/mediasoup) swap | 2026-11-14 |
| D3 | Crypto: libsignal-android if resolvable in CI; fallback to BouncyCastle X25519/Ed25519 + Signal Sender-Key per published spec behind `CryptoEngine` interface | Native artifact availability uncertain; interface isolates swap; no custom crypto either way | 2026-11-14 |
| D4 | Android code-complete locally, built in GitHub Actions (no Android SDK/Gradle on this machine) | Toolchain inventory in PROJECT_AUDIT.md | 2026-11-14 |
| D5 | PTT floor state authoritative in Redis (online) / host memory (local), never client-side | Spec §20 "do not trust client-side floor state" | 2026-11-14 |
| D6 | Key envelopes deleted on ack (24 h max retention) | Metadata minimization | 2026-11-14 |
| D7 | Group media = Signal Sender Keys + AES-256-GCM per packet, SFrame-style framing, sliding replay window | Reviewed design per spec §5C; documented in ARCHITECTURE §3.3 | 2026-11-14 |
| D8 | Android modules in one Gradle root (`android/`) with `core/`+`feature/` modules | Matches spec structure, keeps build fast | 2026-11-14 |

## Known limits (documented per spec)
- Forward secrecy for group media: limited to sender-key rotation on membership change; previously captured ciphertext of past epochs remains unreadable only while sender keys stayed secret (no ratcheted group FS like MLS). Explicitly documented.
- Backend learns connection metadata (IP, timestamps, participation) — minimized but not hideable; documented in threat model.
