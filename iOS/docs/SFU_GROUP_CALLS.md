# SFU Group Calls (Cloudflare Realtime + MLS-E2EE) — iOS Port Plan

Status: **foundation + Rust MLS core + media core + per-frame MLS crypto DONE (crypto
ABI-verified on simulator via `SfuFrameCryptoTests`); remaining: ring manager + authority-call
UI, then two-device + web interop validation.**
Feature gate: `SfuCallConfig.enabled = false` (Android's is dev-on already, but iOS stays off
until the remaining pieces land and a three-way call is verified).

## Architecture (shared with web + Android)

- **SFU**: Cloudflare Realtime (Serverless SFU), reached through the dashboard's `/api/realtime`
  proxy (`sessions/new`, `sessions/{id}/tracks/new`, `sessions/{id}/renegotiate`). The proxy
  injects the Cloudflare App Secret server-side; mobile authenticates with the Firebase ID token.
- **Coordination plane**: Firestore `sfuRooms/{roomId}`:
  - `participants/{uid}` — each member's SFU sessionId + published track names (roster);
    heartbeat refreshes `updatedAt`; `expireAt` + Firestore TTL sweeps orphans.
  - `mlsMessages` — opaque MLS handshake blobs framed with Cloudflare Orange's typed-array JSON
    (`{FLAG_TYPED_ARRAY: true, data: [...]}`); byte-exact across web (`e2ee-mls.ts`), Android
    (`MlsHandshakeCodec.kt`) and iOS (`MlsHandshakeCodec` in `SfuCallFoundation.swift`).
  - room doc — `mlsCreator` claimed race-free in a transaction; the creator makes the MLS group,
    everyone else shares a KeyPackage and joins via Welcome.
- **E2EE**: OpenMLS **0.7.1** (version-pinned for wire interop) in a shared Rust core:
  `crisis-connect-web/rust-mls-worker` (wasm, web) and `rust-mls-android` (JNI) reuse the same
  `mls_ops.rs`. Media frames are encrypted per-frame with the MLS group key.

## Ported and tested on iOS (this repo)

| Piece | File | Tests |
|---|---|---|
| Feature gate + roster models | `SfuCallFoundation.swift` | — |
| MLS handshake wire codec | `SfuCallFoundation.swift` | `SfuFoundationTests` (framing, round-trip, ArrayBuffer variant, reject-unflagged) |
| `/api/realtime` proxy client | `SfuCallFoundation.swift` | error-contract tests |
| Room roster + MLS relay | `SfuRoomClient.swift` | — (Firestore glue; mirrors Android line-for-line incl. backlog ordering) |
| MLS session orchestration | `MlsSession.swift` (`MlsWorkerBackend` protocol; inert while `backend == nil`) | response-contract test |

## ~~Blocker 1~~ — Rust MLS core: DONE (2026-07-09)

`crisis-connect-web/rust-mls-ios` builds the shared `mls_ops.rs` (OpenMLS 0.7.1, Cargo.lock
seeded from the web crate) as `OrangeMlsWorker.xcframework` with a C FFI (`ios_ffi.rs`).
`RustMlsWorkerBackend.swift` implements `MlsWorkerBackend` over it and registers at startup.
`mls_ops.rs`'s STATE cfg was widened to `any(android, ios)` → process-global mutex on iOS, so
Swift may call from any queue (GCD queues hop threads; thread_local would lose the group).

Verified on this machine by `cargo test` in the crate: a real two-party handshake across two
states (creator → KeyPackage → Welcome → join) ending in IDENTICAL safety numbers plus an
encrypted-frame round-trip, alongside mls_ops' own suite.

Rebuild after any `mls_ops.rs` change:

```
cd crisis-connect-web/rust-mls-ios && ./build-xcframework.sh
```

⚠️ The 113MB xcframework is gitignored (regenerable). A CI release build (Codemagic) therefore
needs either a Rust toolchain step running the script, or the framework tracked via git-lfs —
decide before the first SFU-enabled release.

## SfuCallManager media core — DONE (2026-07-09)

`SfuCallManager.swift` ports Android's media core against the existing stasel/WebRTC: one
RTCPeerConnection to the SFU that publishes mic (+camera) via `sessions/new` → `tracks/new`
(offer/answer, fully-gathered non-trickle SDP for Cloudflare), pulls each roster peer's tracks
(SFU offers new recvonly m-lines → `renegotiate` answer), attributes inbound video by
transceiver mid → uid, runs the MLS handshake (creator claim → MlsSession → safety number),
and handles mute / camera / switch-camera / leave + roster heartbeat. Everything EXCEPT the
per-frame crypto attach (`attachFrameCrypto`, a documented no-op).

## ~~THE ONE REMAINING BLOCKER~~ — per-frame MLS crypto: DONE (2026-07-12, no custom build)

**2026-07-09 investigation stands on one point: LiveKit is the WRONG swap** (its RTCFrameCryptor
does its own SFrame with a key provider; it cannot run our MLS `encrypt_msg`, so its ciphertext
would never match web/Android — and it renames every symbol to `LKRTC*`).

**But the "custom libwebrtc build" conclusion was wrong.** The prebuilt stasel/WebRTC binary
compiles in upstream's `+Native` category methods — `-[RTCRtpSender setFrameEncryptor:]` and
`-[RTCRtpReceiver setFrameDecryptor:]` — absent from the shipped public headers but fully
present in the ObjC metadata and callable at runtime. `MlsFrameCrypto.mm` re-declares those
selectors plus ABI-twin `FrameEncryptorInterface`/`FrameDecryptorInterface` implementations
(the iOS sibling of Android's `mls_frame_crypto.cpp` vtable shim, against the M149
branch-heads/7827 headers) and routes every frame through the statically-linked
`mls_ios_encrypt_frame`/`mls_ios_decrypt_frame`. Key ABI facts, all verified by disassembling
the exact shipped binary:

- the argument is a `scoped_refptr` passed **indirectly** (pointer to caller temp) → mirrored
  with a deliberately non-trivial one-pointer struct (`ShimRefPtr`);
- the wrapper AddRefs through vtable **slot 0** and Releases through **slot 1** → RefCounted
  shim layout confirmed;
- M149 signature drift vs Android's M124 shim: `std::span` instead of `rtc::ArrayView`
  (identical `{ptr,size}` layout), int-width `enum class MediaType`, a 4-value decryptor
  `Status` enum.

`SfuCallManager` now attaches the encryptor to every local sender, the decryptor to every
inbound receiver (audio AND video, in `didStartReceivingOn` — Android's `onTrack`), pins
**VP9 (+rtx)** on video transceivers like Android/web (the MLS whole-frame crypto leaves no
plaintext header; only VP9 is wired for that in the shared Rust `split_vp9_header`), and gates
it all on `e2ee = MlsWorker.available && MlsFrameCrypto.available`.

`SfuFrameCryptoTests` locks the ABI: it drives attach + re-attach + teardown against the real
prebuilt binary on the simulator and asserts the full refcount round-trip (a wrong vtable slot
or argument class crashes there, not on a device mid-call). **Run it after every WebRTC package
bump.**

## Ring + authority-call orchestration + UI — DONE (2026-07-12)

- `SfuRingManager.swift` — the ring state machine, byte-for-byte with web `sfu-ring.ts` /
  Android `SfuRingManager` (offer carries a shared `roomId`; accept → both sides `onRoom`).
  Pure logic, locked by `SfuRingManagerTests` (7 cases: offer framing, caller/callee join,
  busy, no-roomId ignore, stale-reject-after-connect).
- `SfuAuthorityCallSignaling.swift` — web-compatible `callSignals` transport
  (`agencyPanels|hierarchyChannels/{id}/callSignals`) + app-wide `SfuAuthorityCallReceiver`.
  iOS has no legacy authority P2P engine, so this routes ONLY SFU signals (offer-with-roomId +
  follow-ups keyed by a remembered SFU callId); anything else is dropped. NOTE the shared doc
  schema does NOT carry the offer's `video` flag (web + Android don't either) — the callee always
  rings as voice and sees the caller's camera via the roster/track pull.
- `SfuAuthorityCallManager.swift` — process-wide orchestration (ring + media + roster + liveness
  watchdog + WhatsApp-style call-log write). Pre-warms the SFU on the outgoing ring with the mic
  on `micHold` until the callee accepts. Drives the audio session by hand (see below).
- `SfuCallOverlay.swift` — global overlay mounted at ContentView root, styled as the 1:1 call
  card; group grid over `SfuCallManager.remoteVideoTracks`. Call buttons (voice + video) in the
  hierarchy thread top bar, shown only when `SfuCallConfig.enabled`.

### CallKit — DONE (2026-07-12), with one documented gap

`SfuAuthorityCallManager` owns its OWN `CXProvider`, separate from the 1:1 `InternetCallManager`'s.
Apple recommends one provider per app, but a second one is legal and keeps the working 1:1 path
untouched (zero regression risk) — the two are never active at once because a user is in a single
call at a time (a concurrent incoming rings busy through the ring state machine). This buys:

- foreground/background native ringing + lock-screen answer (`reportNewIncomingCall` on
  `ring.state == .incoming`);
- outgoing/connected reporting (`CXStartCallAction` + `reportOutgoingCall(connectedAt:)`);
- the documented manual-audio handoff — WebRTC's audio unit starts in the provider's
  `didActivate` (the manual `activateAudioSession` is gone; we only pre-set the category so the
  route is right when CallKit fires).

Answer / end / mute route through `CXAnswerCallAction` / `CXEndCallAction` / `CXSetMutedCallAction`
so the app UI and the native UI stay in sync either way.

**Remaining CallKit gap (needs backend, deferred with the 1:1 VoIP work):** PushKit VoIP ringing
for a FORCE-QUIT app. CallKit here only rings while the process is alive to receive the Firestore
call signal; waking a killed app needs a backend raw-APNs VoIP sender + a plaintext call-offer
hint + Apple's report-or-die rule. Same deferral as the 1:1 manager.

## ~~CI/CD~~ — DONE (2026-07-12): OrangeMlsWorker.xcframework tracked via git-lfs

The framework was gitignored and rebuilt locally, so a Codemagic `main` build had no framework and
would fail to link `mls_ios_*`. Now tracked via git-lfs (`.gitattributes` covers `**/*.a`;
headers/plist stay in normal git), with a `git lfs pull` step in `codemagic.yaml` before the build
(Codemagic's clone doesn't reliably materialize LFS objects). Chosen over a CI Rust-build step
because the Rust source lives in a separate repo — mirrors Android's already-settled "commit the
binary" approach (its `liborange_mls_worker.so` is tracked in `app/src/main/jniLibs`), just via LFS
because the iOS slices are far larger (~39 MB device + ~78 MB simulator vs 3.3 MB).

## Remaining before flipping `SfuCallConfig.enabled`

**Two-device + web three-way interop validation** (physical devices). Everything code-side is now
in place; the gate flips only after a real device+web call is verified end-to-end.
