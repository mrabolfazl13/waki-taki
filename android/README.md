# SecureTalk Android

Kotlin/Compose multi-module Android client for SecureTalk (Agents B–E). Code-complete
locally; **compilation is unverified** — no Android SDK/Gradle toolchain exists on the
authoring machine (see PROGRESS.md). CI (`.github/workflows/android.yml`) performs the
first real build.

## Modules

```
app                       shell: MainActivity, navigation, Hilt wiring, PTT foreground service,
                          SessionController (media pump + mode switching)
core/common               §2.3 abstractions, models, WS protocol codec, PTT controller (pure Kotlin)
core/security             crypto: BouncyCastle X25519/Ed25519/HKDF + JCE AES-256-GCM,
                          Tink Keystore-sealed secret storage, X3DH-lite, double-ratchet-lite,
                          Signal Sender-Key group scheme (pure Kotlin + one Android store class)
core/audio                AudioRecord(VOICE_COMMUNICATION)+AEC/NS, AudioTrack low-latency,
                          MediaCodec Opus (API 29+) / AAC-LC fallback (26–28), adaptive jitter
                          buffer (≤400 ms), PLC, audio routing + device callbacks
core/network              Retrofit REST (§5), OkHttp WebSocket transports (online + local),
                          ConnectionMonitor (§8 states, NSD probe, never silent downgrade),
                          NsdDiscovery, embedded Ktor local host (§2.2) + floor controller
core/database             Room cache (channels/members/devices/identity) + DataStore settings
core/design               radio-inspired dark Material3 theme + shared components
core/permissions          stable ActivityResult-based permission request helpers
feature/*                 11 feature modules, 22 screens wired to ViewModels + services
```

## Building

Requirements: JDK 17, Android SDK 34.

```bash
cd android
./gradlew :app:assembleDebug        # debug APK
./gradlew test                      # JVM unit tests (crypto, codec framing, jitter, floor)
```

**Wrapper note:** `gradle/wrapper/gradle-wrapper.properties`, the wrapper **JAR**
(official Gradle 8.7 artifact) and the `gradlew` scripts are all committed, so CI can
build directly. `gradle/actions/wrapper-validation` verifies the JAR's checksum.

## Key implementation decisions

- **Crypto stack** (ARCHITECTURE §3.2/D3): BouncyCastle provides X25519 key agreement,
  Ed25519 signatures and HKDF-SHA256; per-packet media uses JCE AES-256-GCM with the
  §3.3 nonce scheme (12 B big-endian sequence counter XOR 4 B per-sender salt) and
  AAD = `epoch | sender(16) | seq`. Tink (`tink-android`) provides the Keystore-backed
  AEAD via `AndroidKeysetManager` (`android-keystore://` master key) that seals every
  stored secret — private keys never persist in plaintext.
- **X3DH + double-ratchet-lite**: real X3DH (DH1–DH3, DH4 with one-time prekeys) against
  published prekey bundles; pairwise sessions use a symmetric-chain ratchet with a
  64-message out-of-order key cache. This is the documented "lite" variant (DECISIONS D3),
  not the full Signal DH ratchet.
- **Sender keys**: per (channel, epoch) 32-byte seed, HKDF-derived key+salt, sliding
  replay window of 128, stale-epoch and revoked-sender rejection, pairwise-encrypted
  distribution envelopes (REST online, opaque WS relay on the local host).
- **Opus**: MediaCodec `"audio/opus"` encoder+decoder on API 29+; API 26–28 devices fall
  back to AAC-LC (`"audio/mp4a-latm"`, AudioSpecificConfig csd-0) — the codec choice is
  symmetric across the channel. Opus decoders receive a hand-built 19-byte OpusHead csd-0.
- **Local host**: Ktor CIO embedded in a `dataSync` foreground service, same WS protocol
  as the backend, authoritative floor copy (60 s lease, queue ≤ 20, 10 req/10 s rate
  limit), media relay parses only the plaintext header and enforces floor ownership —
  it cannot decrypt media. NSD `_securetalk._tcp` registration; hotspot setup opens the
  real `Settings.ACTION_WIRELESS_SETTINGS` screen with step-by-step guidance (no fake
  hotspot control).

## Known limitations / unverified items

- Compilation, lint and unit-test execution are NOT verified locally (no SDK). Run CI or
  a local `./gradlew build` before trusting anything.
- Media path end-to-end (encrypt → WS → relay → decrypt → jitter → decode) requires two
  devices + a host; not exercised locally.
- Auth refresh token rotation is wired but the server contract (`expires_in`) is assumed.
- `AuthRepository.bundleFor` currently resolves bundles from the device registry; peer
  user→device key discovery via `GET /users/{id}/keys` needs a userId parameter plumbing
  improvement.
- Local host authorization uses "sender currently holds some floor" rather than
  per-channel mapping (media frames carry no channel id in the §6 header).
