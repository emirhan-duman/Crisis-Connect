# Crisis Connect — iOS

Swift / SwiftUI client for [Crisis Connect](../README.md). Offline-first crisis communication over
Bluetooth, with an end-to-end encrypted internet layer on top.

- **Deployment target:** iOS 17.0
- **Toolchain:** Xcode 16+, Swift 6 language mode where the package sets it
- **Build verification:** Xcode 16+ with code signing disabled for public-source checks
- **Version:** 1.1.9 (build 36)

## Targets

| Target | Purpose |
|:--|:--|
| `Crisis Connect` | Main app |
| `WidgetExtension` | WidgetKit SOS + Recent Disasters widgets, iOS 18 SOS control, SOS Live Activity |
| `BroadcastExtension` | ReplayKit upload extension for full-device screen sharing during calls |
| `Crisis ConnectTests` | 175 unit tests |
| `Crisis ConnectUITests` | UI tests |

## Layout

```
iOS/
├── Crisis Connect/          App sources
│   ├── App/                 Entry point, root navigation, splash
│   ├── Features/            Chat, Contacts, SOS, Rescue, Sentinel, Settings, Messaging,
│   │                        Compass, LiDAR, MetalDetector, OfflineMap, SignalScanner,
│   │                        SurvivalGuide, Whistle, …
│   ├── Security/            Keychain, role certificates, verification
│   ├── Services/            BLE / GATT mesh / P2P, internet transport, calls, Firebase
│   ├── Shared/              Design system, utilities, media
│   └── *.lproj/             19 localizations
├── WidgetExtension/
├── BroadcastExtension/
├── Config/                  Info.plist, entitlements
├── Frameworks/              OrangeMlsWorker.xcframework (headers only — see below)
├── Packages/                Vendored Swift packages
│   ├── LibSignalClient/     signalapp/libsignal v0.86.5 (AGPL-3.0-only)
│   └── LiteRT-LM/           Google AI Edge on-device LLM runtime
├── Scripts/                 Build helper scripts
└── docs/                    Design notes
```

## Prebuilt binaries are not committed

Two `.xcframework` bundles in this tree ship as **prebuilt static libraries**. Together they are
roughly **375 MB** of build output, so the `.a` slices are deliberately kept out of this
repository — only the headers, module maps and `Info.plist` are committed. You need to produce
them locally before the app will link.

### 1. `Packages/LibSignalClient/SignalFfi.xcframework`

The Rust core of [libsignal](https://github.com/signalapp/libsignal), which backs all internet
messaging. The Swift sources under `Packages/LibSignalClient/Sources` are copied verbatim from
upstream `swift/Sources/LibSignalClient`.

Build it from a checkout of libsignal at the **matching tag** (`v0.86.5` — keep this aligned with
the `org.signal:libsignal-android` version used by the Android app):

```bash
# In a checkout of https://github.com/signalapp/libsignal at v0.86.5
cargo build --release -p libsignal-ffi --features libsignal-bridge-testing \
  --target aarch64-apple-ios
cargo build --release -p libsignal-ffi --features libsignal-bridge-testing \
  --target aarch64-apple-ios-sim

xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libsignal_ffi.a \
  -headers swift/Sources/SignalFfi \
  -library target/aarch64-apple-ios-sim/release/libsignal_ffi.a \
  -headers swift/Sources/SignalFfi \
  -output SignalFfi.xcframework
```

Drop the resulting `SignalFfi.xcframework` into `Packages/LibSignalClient/`. The
`libsignal-bridge-testing` feature is required: it bakes in the `signal_ffi_testing.h` symbols
that `LibSignalClient`'s fake-connection test helpers reference, so a single static library links
both the app and the tests.

To upgrade: bump the tag, rebuild both slices, re-run `-create-xcframework`, and re-copy the Swift
sources.

### 2. `Frameworks/OrangeMlsWorker.xcframework`

The OpenMLS core used by the **experimental SFU group-call backend**
(see [`docs/SFU_GROUP_CALLS.md`](docs/SFU_GROUP_CALLS.md)). This feature is gated off
(`SfuCallConfig.enabled = false`) and is **not part of the shipped app**.

Its Rust crate is not currently part of this open-source mirror, so this binary cannot be rebuilt
from what is published here. Because the feature is disabled, the practical options are to stub
`RustMlsWorkerBackend` out, or to remove the framework reference from the Xcode target, until the
crate is published. If you are working on group calls, open an
[issue](https://github.com/emirhan-duman/Crisis-Connect/issues) and we will sort the source drop
out.

## Firebase configuration

Real Firebase config is not committed. Copy the examples and fill them in with your own project:

```bash
cp "Crisis Connect/GoogleService-Info.plist.example" "Crisis Connect/GoogleService-Info.plist"
```

Then edit `Config/Info.plist` and replace the placeholder URL schemes
(`app-1-YOUR_GCM_SENDER_ID-ios-YOUR_APP_HASH` and
`com.googleusercontent.apps.YOUR_GCM_SENDER_ID-YOUR_CLIENT_ID_SUFFIX`) with the values from your
own `GoogleService-Info.plist`.

App Attest must be enabled for the iOS app in the Firebase Console for App Check to pass in
release builds.

## Build & test

```bash
open "Crisis Connect.xcodeproj"      # SPM dependencies resolve on first open

xcodebuild test \
  -scheme "Crisis Connect" \
  -destination "platform=iOS Simulator,name=iPhone 16"
```

> Bluetooth features require a **physical device**. The Simulator has no BLE radio, so pairing,
> mesh relay and offline calls cannot be exercised there.

## Licensing note

`Packages/LibSignalClient` is vendored from signalapp/libsignal, licensed **AGPL-3.0-only** —
the same license as Crisis Connect itself. Keep any redistribution AGPL-compliant.
