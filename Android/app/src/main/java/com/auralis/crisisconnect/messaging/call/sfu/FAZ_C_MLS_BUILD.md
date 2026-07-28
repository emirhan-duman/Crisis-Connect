# Faz C — MLS-E2EE for SFU authority calls (Android)

Interop with the web needs the **same** MLS group (web enforces E2EE — all-or-nothing). The Rust core
is shared with the web (`~/Desktop/crisis-connect-web/rust-mls-worker/src/mls_ops.rs`, OpenMLS 0.7.1,
ciphersuite `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`).

## What's already written
- `rust-mls-worker/src/android_ffi.rs` (web repo, orphan file — ignored by wasm build): JNI surface
  reusing `mls_ops`, returns handshake msgs as web-format JSON (`{broadcast:[…], safetyNumber?}`).
- `rust-mls-android/` (web repo): Android cdylib crate that path-includes `mls_ops.rs` + `android_ffi.rs`.
  **Web crate untouched.**
- `MlsHandshakeCodec.kt`: byte-exact wire codec (typed-array framing) ↔ web. Compiles.
- `MlsWorker.kt` + `MlsSession`: loads `liborange_mls_worker.so`, drives the group handshake over
  `SfuRoomClient` (`sfuRooms/{id}/mlsMessages`). Inert until the `.so` exists (`MlsWorker.available`).

## Remaining native work (your machine — needs Rust + NDK + C++)

### 1. Build the `.so`
```
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
cd ~/Desktop/crisis-connect-web/rust-mls-android
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ~/AndroidStudioProjects/DisasterCommunicationSystem/app/src/main/jniLibs build --release
```
Verify: openmls builds for android without the wasm `js` feature (getrandom picks the android backend).
If it fails on RNG, pin `getrandom` with the android backend. `openmls` version MUST equal the web's.

### 2. C++ FrameEncryptor bridge (the crux)
`org.webrtc.FrameEncryptor/FrameDecryptor` are **native-pointer only** (no Java per-frame hook). So:
- Write a tiny C++ lib implementing `webrtc::FrameEncryptorInterface` + `FrameDecryptorInterface` whose
  `Encrypt/Decrypt` call the Rust `encrypt_msg`/`decrypt_msg` (link the same `.so` or a combined lib).
- Expose `long nativeCreateFrameEncryptor()/Decryptor()` to Kotlin; set via
  `RtpSender.setFrameEncryptor(...)` / `RtpReceiver.setFrameDecryptor(...)` in `SfuCallManager`
  (attach to each sender in `join()` and each receiver in `onTrack`).
- Needs the webrtc native headers matching this project's org.webrtc AAR (check the AAR / build from
  the WebRTC source at the same milestone).

### 3. THREADING (must resolve)
`mls_ops::STATE` is `thread_local`. Handshakes run on `MlsSession`'s pinned thread; per-frame crypto runs
on webrtc's encoder thread → **different thread_local state → broken**. Fix ONE of:
- (preferred) change `mls_ops.rs` `thread_local! STATE` → a global `OnceLock<Mutex<WorkerState>>` **IF**
  `WorkerState: Send` (check — OpenMLS + rust-crypto provider). Small, and safe for the web too
  (single-threaded wasm never contends). This is the clean fix.
- else marshal every frame onto the MLS thread (adds latency, blocks the encoder thread — avoid).

### 4. Wire into the call
In `SfuAuthorityCallManager.onRoom()`:
- `claimMlsCreator` (race-free room-doc claim; add to `SfuRoomClient` mirroring web `sfu-room.ts`).
- `MlsSession(myUid, room).start(isCreator)`; pass its encrypt/decrypt to `SfuCallManager` so senders/
  receivers get the FrameEncryptor/Decryptor.
- **Gate**: don't publish our tracks (or don't mark "live") until MLS reports the group is established,
  or early frames go out unencrypted and the web drops them.
- On leave: `MlsSession.stop()`.

### 5. Test
Two Android devices first (both MLS on) → then Android↔web. Compare the safety number on both ends.
```
adb logcat | grep -iE "MlsWorker|SfuCallManager|SfuAuthorityCall"
```
