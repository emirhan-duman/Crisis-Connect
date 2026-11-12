# Crisis Connect — iOS

Swift / SwiftUI client for [Crisis Connect](../README.md). Offline-first crisis communication over
Bluetooth, with an end-to-end encrypted internet layer on top.

- **Deployment targets:** iOS 17.0 and watchOS 10.0 for the companion
- **Toolchain:** Xcode 26 or later
- **Version:** 1.2.0 (build 44)

## Targets

| Target | Purpose |
|:--|:--|
| `Crisis Connect` | Main app |
| `WidgetExtension` | WidgetKit SOS + Recent Disasters widgets, iOS 18 SOS control, SOS Live Activity |
| `BroadcastExtension` | ReplayKit upload extension for full-device screen sharing during calls |
| `CrisisConnectWatch Watch App` | Apple Watch companion for SOS, connection status and messaging |
| `Crisis ConnectTests` | Unit and integration tests |
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
├── CrisisConnectWatch Watch App/
├── Config/                  Info.plist, entitlements
├── Frameworks/              OrangeMlsWorker.xcframework (headers only — see below)
├── Packages/                Vendored Swift packages
│   ├── LibSignalClient/     signalapp/libsignal v0.86.5 (AGPL-3.0-only)
│   └── LiteRT-LM/           Google AI Edge on-device LLM runtime
├── Scripts/                 Build helper scripts
└── docs/                    Design notes
```

## Prebuilt binaries are not committed

The two XCFramework directories contain headers, module maps and metadata. Supply their
matching static libraries locally before linking the app or running its Xcode tests.

### 1. `Packages/LibSignalClient/SignalFfi.xcframework`

The Rust core of [libsignal](https://github.com/signalapp/libsignal), which backs all internet
messaging. The Swift sources under `Packages/LibSignalClient/Sources` are copied verbatim from
upstream `swift/Sources/LibSignalClient`.

Build it from a checkout of libsignal at the matching `v0.86.5` tag. Set `CC_IOS_SOURCE` to
the absolute path to this repository's `iOS` directory.

```bash
# In a checkout of https://github.com/signalapp/libsignal at v0.86.5
cargo build --locked --release -p libsignal-ffi --features libsignal-bridge-testing \
  --target aarch64-apple-ios
cargo build --locked --release -p libsignal-ffi --features libsignal-bridge-testing \
  --target aarch64-apple-ios-sim

CC_SIGNAL_FRAMEWORK="$CC_IOS_SOURCE/Packages/LibSignalClient/SignalFfi.xcframework"
cp target/aarch64-apple-ios/release/libsignal_ffi.a \
  "$CC_SIGNAL_FRAMEWORK/ios-arm64/libSignalFfi.a"
cp target/aarch64-apple-ios-sim/release/libsignal_ffi.a \
  "$CC_SIGNAL_FRAMEWORK/ios-arm64_x86_64-simulator/libSignalFfi.a"
```

The project also needs device and simulator slices for watchOS. With the iOS libraries in the
paths listed by `Packages/LibSignalClient/SignalFfi.xcframework/Info.plist`, run
`./Scripts/build-watch-signal.sh` from the `iOS` directory. The script verifies the libsignal
revision and prints the path to the combined XCFramework. Review the generated bundle and put
it in `Packages/LibSignalClient/`. For an Intel simulator, combine an `x86_64-apple-ios` build
with the iOS simulator library using `lipo` before running the script.

Use `libsignal-bridge-testing` for every slice so the same static library supplies the
`signal_ffi_testing.h` symbols used by the test helpers.

### 2. `Frameworks/OrangeMlsWorker.xcframework`

The iOS target links this OpenMLS library for authority messaging and SFU group calls. A full
build requires the device and simulator slices from the matching `rust-mls-ios` crate described
in [the SFU group-call notes](docs/SFU_GROUP_CALLS.md). Obtain the crate or matching artifacts
separately. The public checkout includes the C interface and XCFramework metadata.

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

After configuring Firebase and supplying the static libraries above, open the project or run
the test plan from the `iOS` directory.

```bash
open "Crisis Connect.xcodeproj"      # SPM dependencies resolve on first open

xcodebuild test \
  -scheme "Crisis Connect" \
  -destination "platform=iOS Simulator,name=iPhone 16"
```

> Bluetooth features require a **physical device**. The Simulator has no BLE radio, so pairing,
> mesh relay and offline calls cannot be exercised there.

## Continuous integration

The `iOS CI` workflow runs on every iOS pull request and validates the Xcode project, shared
scheme, test plan, local Swift package manifests, and the syntax of every Swift source file. Run
the same checks locally with:

```bash
./Scripts/ci-validate.sh
```

A clean public checkout cannot currently link or run the Xcode test plan because the static
libraries inside `SignalFfi.xcframework` and `OrangeMlsWorker.xcframework` are intentionally not
committed. Full build-and-test CI must be enabled together with a reproducible, integrity-checked
artifact pipeline for those two frameworks; the project must not download unsigned binaries at
build time.

## Licensing note

`Packages/LibSignalClient` is vendored from signalapp/libsignal, licensed **AGPL-3.0-only** —
the same license as Crisis Connect itself. Keep any redistribution AGPL-compliant.
