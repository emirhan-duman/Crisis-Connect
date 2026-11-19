# Architecture

Technical overview of the Crisis Connect repository layout, protocol stack, mesh networking, voice pipeline, backend services, codebase metrics, and test suites.

## Contents

- [Repository Structure](#repository-structure)
- [Tech Stack](#tech-stack)
- [Communication Protocol](#communication-protocol)
- [Mesh Networking](#mesh-networking)
- [Voice Pipeline](#voice-pipeline)
- [Backend Services](#backend-services)
- [Build Variants](#build-variants)
- [Codebase](#codebase)
- [Testing](#testing)

---

## Repository Structure

```
Crisis-Connect/
│
├── Android/                            Kotlin  ·  Jetpack Compose  ·  Material 3
│   ├── app/                            Main application module
│   │   ├── src/main/java/.../
│   │   │   ├── ai/                     Crisis Sentinel offline + online engines
│   │   │   ├── core/                   Crypto primitives, chunking, media processing
│   │   │   ├── data/                   Room DB, Signal stores, Firestore repos, offline maps
│   │   │   ├── messaging/              Internet transport, Signal sessions, relay, receipts
│   │   │   ├── navigation/             Compose navigation, deep links, route resolution
│   │   │   ├── nearby/                 SPAKE2 pairing sessions, nearby discovery
│   │   │   ├── screens/                UI: Chat, Tools, Settings, QR, SOS, Guide, Profile
│   │   │   ├── security/               ECDSA certs, role proofs, keystore, crypto helpers
│   │   │   ├── service/                BLE GATT server/client, RFCOMM, mesh, voice, files
│   │   │   ├── telecom/                Android Telecom integration for call management
│   │   │   ├── ui/                     Theme, design system, shared components
│   │   │   └── widget/                 Home-screen SOS + Recent Disasters widgets
│   │   ├── src/test/                   Unit tests
│   │   └── src/androidTest/            Instrumented tests (device or emulator)
│   │
│   ├── feature_rescue/                 Dynamic feature module (rescue mesh, CrisisLink sync)
│   ├── baselineprofile/                Baseline profile generator for startup/jank
│   ├── wear/                           Wear OS companion app
│   ├── watch-protocol/                 Shared phone-to-watch protocol sources
│   ├── functions/                      Firebase Cloud Functions (TypeScript)
│   │   └── src/                        Certificates, attestation, messaging relay, SOS, push
│   │
│   ├── firestore.rules                 Firestore security rules
│   ├── firebase.json                   Firebase deployment config
│   ├── build.gradle.kts                Project-level build config
│   └── settings.gradle.kts             Module definitions
│
├── iOS/                                Swift  ·  SwiftUI
│   ├── Crisis Connect/
│   │   ├── App/                        App entry point, root navigation, splash
│   │   ├── Features/                   Chat, Contacts, SOS, Rescue, Sentinel, Settings,
│   │   │                               Compass, LiDAR, MetalDetector, OfflineMap,
│   │   │                               SignalScanner, SurvivalGuide, Whistle, …
│   │   ├── Security/                   Keychain, certs, role verification
│   │   ├── Services/                   BLE / GATT mesh / P2P, internet messaging, calls,
│   │   │                               Firebase, background refresh
│   │   └── Shared/                     Design system, utilities, media
│   │
│   ├── WidgetExtension/                WidgetKit widgets, SOS control, Live Activity
│   ├── BroadcastExtension/             ReplayKit screen-share upload extension
│   ├── CrisisConnectWatch Watch App/   Apple Watch companion app
│   ├── Packages/                       Vendored LibSignalClient and LiteRT-LM
│   ├── Config/                         Info.plist, entitlements
│   └── Crisis ConnectTests/            Unit tests
│
├── docs/                               Architecture, security model, threat model, releases
├── LICENSE                             AGPL-3.0
└── README.md
```

> **Note on prebuilt binaries.** The `.xcframework` static libraries under `iOS/Packages/LibSignalClient` and `iOS/Frameworks` are **not** committed to this mirror -- together they are ~375 MB of build output. The Swift sources, headers and module maps are here, and [`iOS/README.md`](../iOS/README.md) documents how to rebuild or fetch them.

Android release assets are published with keyless Sigstore bundles, a CycloneDX SBOM, SHA-256 checksums, and a GitHub artifact attestation. See **[Release verification](RELEASE_VERIFICATION.md)** before pilot distribution.

Pilot operators should assign the private on-call roster and exercise the **[Security incident response plan](INCIDENT_RESPONSE.md)** before handling live data.

## Tech Stack

| Layer | Android | iOS |
|:--|:--|:--|
| **Language** | Kotlin | Swift |
| **UI Framework** | Jetpack Compose + Material 3 | SwiftUI |
| **Bluetooth** | Android BLE GATT / RFCOMM | CoreBluetooth |
| **Mesh Protocol** | Custom GATT mesh protocol | GATT mesh |
| **Offline Encryption** | Google Tink · SQLCipher | CryptoKit · Keychain |
| **Internet Encryption** | libsignal (Signal Protocol) | libsignal (vendored `LibSignalClient`) |
| **Database** | Room (SQLCipher encrypted) | SwiftData / Keychain-backed storage |
| **Maps** | MapLibre GL Native | MapLibre (tile-based offline) |
| **Voice Codec** | Opus (native `.aar`) | Opus · AVFoundation |
| **Calls** | WebRTC · Android Telecom | WebRTC · CallKit · PushKit |
| **On-device AI** | Google AI Edge LiteRT-LM | LiteRT-LM (vendored package) |
| **Auth** | Firebase Auth · Google Sign-In · phone | Firebase Auth · Google Sign-In · phone |
| **Backend** | Firestore · Cloud Functions | Firestore · Cloud Functions |
| **App Security** | Play Integrity · App Check | App Attest · App Check |
| **Crash Reporting** | Firebase Crashlytics | Firebase Crashlytics |
| **Public CI** | GitHub Actions: lint, debug build, unit tests ([scope](#testing)) | GitHub Actions: project and Swift syntax validation ([scope](#testing)) |
| **Min Version** | Android 7.0 (API 24) | iOS 17.0 |
| **Target Version** | Android 16 (API 36) | iOS 18 |
| **Test Frameworks** | JUnit · Espresso · MockK · Robolectric | XCTest |

## Communication Protocol

The BLE communication stack operates as follows:

```
┌─────────────────────────────────────────────────────────────┐
│                     Application Layer                        │
│  Text messages · Voice messages · Images · Files · Location  │
├─────────────────────────────────────────────────────────────┤
│                     Encryption Layer                         │
│  AES-256-GCM envelope (Tink / CryptoKit)                    │
│  Per-message nonce · HKDF-derived session keys               │
├─────────────────────────────────────────────────────────────┤
│                      Framing Layer                           │
│  Chunking (BLE MTU ~512 bytes) · Reassembly · Sequencing    │
│  Delivery receipts · Retry with exponential backoff          │
├─────────────────────────────────────────────────────────────┤
│                     Transport Layer                          │
│  BLE GATT (messages, images, files)                          │
│  GATT audio link 0xCD00 / RFCOMM (voice calls)               │
│  GATT mesh (multi-hop relay)                                 │
└─────────────────────────────────────────────────────────────┘
```

## Mesh Networking

When two devices are not in direct BLE range, the GATT mesh protocol routes messages through intermediate devices:

```
Device A ──(encrypted)──► Device B ──(encrypted)──► Device C
                          (relay node)

  • Device B forwards the encrypted payload without decryption
  • TTL-based hop limit prevents infinite relay loops
  • Each device maintains a seen-message cache for deduplication
  • Mesh operates at the GATT service level (no special hardware)
```

The mesh protocol is transport-agnostic -- the encrypted payload is the same regardless of how many hops it takes. Relay nodes never have access to plaintext.

## Voice Pipeline

Offline voice calls use a dedicated pipeline over Bluetooth, and work cross-platform between Android and iOS:

```
Microphone ──► PCM 16kHz ──► Opus Encoder ──► BT link ──► Opus Decoder ──► Speaker
                                    │
                              Jitter Buffer
                           (adaptive, 60-200ms)
```

- **Codec**: Opus at 16kHz mono (optimized for speech)
- **Transport**: a dedicated GATT audio characteristic (`0xCD00`) with write-without-response for the fast path, or RFCOMM on Android where a Classic link is available
- **Latency**: ~100-200ms end-to-end depending on device and environment
- **Call Management**: native call UI through Android Telecom and iOS CallKit, so calls ring on the lock screen even from a force-quit app

## Backend Services

Firebase is used for identity, coordination and the internet transport. **None of it is required for offline messaging:**

| Service | Purpose | Required? |
|:--|:--|:--|
| **Firebase Auth** | User identity, phone verification, rescue roles | Only for internet + rescue features |
| **Cloud Firestore** | Ciphertext relay, agency coordination, SOS reports | Only for internet + rescue features |
| **Cloud Functions** | Certificate issuance, attestation, push, prekeys, TURN credentials | Only for internet + rescue features |
| **Cloud Messaging / APNs** | Message, call and SOS notifications | Only when online |
| **App Check** | API abuse prevention | Only when online |
| **Crashlytics** | Crash reporting for production stability | Optional |

Cloud Functions live under `Android/functions` and cover role certificate issuance, Play Integrity / App Attest verification, the encrypted messaging relay and prekey pool, VoIP push (APNs HTTP/2 + FCM), SOS signal reporting and short-lived TURN credentials.

## Build Variants

**Android** provides three build types:

| Variant | Purpose | Signing | App Check |
|:--|:--|:--|:--|
| `debug` | Development | Debug keystore | Debug provider |
| `internal` | QA / testing | Release keystore | Debug provider |
| `release` | Production (Play Store) | Release keystore | Play Integrity |

**iOS** public-source builds use Xcode 16+ with code signing disabled. Production signing, App Attest credentials and App Store distribution configuration are intentionally maintained outside this public mirror.

## Codebase

Every figure below is produced by [tokei](https://github.com/XAMPPRocky/tokei) against this repository. **Code** excludes comments and blank lines -- the numbers are not inflated by whitespace. Vendored dependencies, generated build artifacts and dependency lockfiles are counted separately, not as first-party code.

<table>
<tr>
<td align="center"><strong>300,874</strong><br/><sub>lines of first-party code</sub></td>
<td align="center"><strong>252,689</strong><br/><sub>Kotlin + Swift</sub></td>
<td align="center"><strong>997</strong><br/><sub>source files</sub></td>
<td align="center"><strong>21</strong><br/><sub>languages</sub></td>
</tr>
</table>

### Android

`685 files · 206,994 lines of code`

| Language | Files | Code | Comments | Blank | Share | |
|:--|--:|--:|--:|--:|--:|:--|
| Kotlin | 516 | 160,594 | 7,081 | 11,602 | 77.58% | `████████████████` |
| XML (resources, manifests) | 115 | 38,031 | 235 | 124 | 18.37% | `████` |
| TypeScript (Cloud Functions) | 39 | 5,709 | 866 | 590 | 2.76% | `▌` |
| Firestore Rules | 1 | 1,647 | 0 | 71 | 0.80% | `▏` |
| Shell | 2 | 275 | 38 | 49 | 0.14% | `▏` |
| C++ (MLS frame crypto, JNI) | 1 | 168 | 46 | 29 | 0.08% | `▏` |
| JavaScript | 2 | 161 | 0 | 21 | 0.08% | `▏` |
| TSX | 1 | 145 | 0 | 4 | 0.07% | `▏` |
| JSON (config) | 3 | 86 | 0 | 0 | 0.04% | `▏` |
| Batch | 1 | 68 | 0 | 21 | 0.03% | `▏` |
| ProGuard | 1 | 48 | 0 | 9 | 0.02% | `▏` |
| TOML | 1 | 48 | 5 | 2 | 0.02% | `▏` |
| CMake | 1 | 11 | 9 | 4 | 0.01% | `▏` |
| SVG | 1 | 3 | 0 | 0 | 0.00% | `▏` |
| **Total** | **685** | **206,994** | **8,280** | **12,526** | | |

### iOS

`312 files · 93,880 lines of code`

| Language | Files | Code | Comments | Blank | Share | |
|:--|--:|--:|--:|--:|--:|:--|
| Swift | 287 | 92,095 | 5,983 | 9,524 | 98.10% | `████████████████████` |
| HTML + inline CSS | 3 | 875 | 6 | 48 | 0.93% | `▏` |
| JSON (asset catalogs, config) | 10 | 261 | 0 | 0 | 0.28% | `▏` |
| Shell | 2 | 230 | 39 | 44 | 0.24% | `▏` |
| Objective-C++ (MLS frame crypto) | 1 | 188 | 61 | 33 | 0.20% | `▏` |
| C Header (bridging and MLS) | 4 | 105 | 32 | 23 | 0.11% | `▏` |
| Metal (LiDAR night-vision shaders) | 1 | 95 | 0 | 11 | 0.10% | `▏` |
| SVG | 4 | 31 | 0 | 0 | 0.04% | `▏` |
| **Total** | **312** | **93,880** | **6,121** | **9,683** | | |

### Not counted as first-party

| What | Files | Lines | Why |
|:--|--:|--:|:--|
| `iOS/Packages/LibSignalClient` | 136 | 22,853 | Vendored from signalapp/libsignal |
| `Android/app/src/release/generated/baselineProfiles` | 2 | 96,874 | Generated by the baseline profile module |
| `Android/functions/package-lock.json` | 1 | 14,378 | npm dependency lockfile |
| `.xcframework` static libraries | 4 | -- | Prebuilt binaries, not committed ([iOS/README.md](../iOS/README.md)) |

### Reproduce

```bash
tokei Android iOS --exclude Packages --sort code
```

> Three tokei labels are corrected in the tables above: `firestore.rules` is detected as Snakemake, `proguard-rules.pro` as Prolog, and the generated `baseline-prof.txt` / `startup-prof.txt` profiles as Plain Text.

## Testing

This section describes test scope without citing drifting test totals: a meaningful count requires a reproducible counting method, a commit, a platform scope, and a distinction between tests that exist and tests that a given run actually executed.

### What public CI runs

The workflow definitions are the source of truth for what runs automatically. Check the [Actions tab](https://github.com/emirhan-duman/Crisis-Connect/actions) for run results.

| Workflow | Triggers | Configured steps | Not run |
|:--|:--|:--|:--|
| [Android CI](../.github/workflows/android-ci.yml) | Every pull request; pushes to `main` that touch `Android/` or `.github/workflows/android-ci.yml` | Native frame fuzz-target smoke run (`-runs=10000`) · `:app:lintDebug` · `:app:assembleDebug` · `:app:testDebugUnitTest` · `:feature_rescue:testDebugUnitTest` | Instrumented tests (`connectedDebugAndroidTest`) |
| [iOS CI](../.github/workflows/ios-ci.yml) | Pull requests that touch `iOS/` or `.github/workflows/ios-ci.yml`; pushes to `main` with those same paths; manual dispatch | [`iOS/Scripts/ci-validate.sh`](../iOS/Scripts/ci-validate.sh): Xcode project, shared scheme, test plan and local Swift package manifest validation, a syntax check of every Swift source file, and a check that no App Check debug token is committed in the shared scheme | App build, `xcodebuild test`, unit and UI tests |
| [Functions CI](../.github/workflows/functions-ci.yml) | Pull requests and pushes to `main` touching `Android/functions/`, `Android/firestore.rules`, `Android/firebase.json`, or the workflow | `npm ci` · `npm ls --all` · `npm audit` (blocks high-severity runtime vulns) · TypeScript build · unit, property, and Firestore rules emulator tests | Live Firebase deployment |

Android CI uses the public `google-services.json.example` configuration, so it exercises the build and JVM unit tests without real Firebase credentials.

iOS CI is **source and project validation, not build-and-test execution**. A clean public checkout cannot currently link or run the Xcode test plan because the vendored `SignalFfi.xcframework` and `OrangeMlsWorker.xcframework` static libraries are not committed. See [iOS/README.md -- Continuous integration](../iOS/README.md#continuous-integration) for details.

### Running tests locally

These commands are available to contributors but, unless noted in the table above, are **not** executed by public CI.

**Android** -- requires JDK 21 and Android SDK 37, plus the configuration files from [Build From Source](../README.md#build-from-source) (`app/google-services.json` can be the example file for local unit tests):

```bash
cd Android
./gradlew :app:lintDebug                      # Static analysis (also run in CI)
./gradlew :app:testDebugUnitTest              # App unit tests (also run in CI)
./gradlew :feature_rescue:testDebugUnitTest   # Rescue module unit tests (also run in CI)
./gradlew :app:connectedDebugAndroidTest      # Instrumented tests -- manual only, needs a connected device or emulator
```

Instrumented tests that touch Bluetooth need a **physical device**; emulators have no BLE radio.

**iOS** -- the validation that CI runs needs macOS with Xcode 16+, but does not build the app or need the vendored native libraries:

```bash
cd iOS
./Scripts/ci-validate.sh                      # Project + Swift syntax validation (same as CI)
```

Running the XCTest suites is manual and requires the vendored native libraries to be built first (Xcode 16+ and a Rust toolchain; see [iOS/README.md](../iOS/README.md)):

```bash
cd iOS
xcodebuild test \
  -scheme "Crisis Connect" \
  -destination "platform=iOS Simulator,name=iPhone 16"
```

### Areas covered by the test suites

The suites include tests for the following areas (intent, not a measured coverage percentage):

- Cryptographic operations (AES-GCM encrypt/decrypt, key derivation, SPAKE2)
- Signal Protocol session handling, downgrade rejection and replay handling
- Cross-platform E2E golden vectors shared between Android and iOS
- BLE message framing, chunking, and reassembly
- Role certificate creation, verification and revocation
- Mesh protocol command routing
- Voice codec pipeline and call state machines
- Crisis Sentinel model output validation and safety coverage
- Chat message formatting and parsing
- Offline map region management
- QR code encoding/decoding
