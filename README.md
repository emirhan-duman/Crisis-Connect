<p align="center">
  <img src="https://github.com/emirhan-duman/Crisis-Connect/raw/main/Android/app/src/main/res/drawable-nodpi/dcslogo.png" alt="Crisis Connect" width="100" />
</p>

<h1 align="center">Crisis Connect</h1>

<p align="center">
  <strong>When networks fail, we connect.</strong><br/><br/>
  Open-source, offline-first communication platform for disaster response.<br/>
  End-to-end encrypted messaging, voice calls, and rescue coordination over Bluetooth.<br/>
  No servers. No internet. No single point of failure.
</p>

<br/>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.auralis.crisisconnect"><img src="https://img.shields.io/badge/Google%20Play-Download-3ddc84?style=for-the-badge&logo=google-play&logoColor=white" alt="Google Play" /></a>&nbsp;&nbsp;
  <a href="https://apps.apple.com/app/crisis-connect/id6759731195"><img src="https://img.shields.io/badge/App%20Store-Download-147efb?style=for-the-badge&logo=app-store&logoColor=white" alt="App Store" /></a>&nbsp;&nbsp;
  <a href="https://crisisconnect.network"><img src="https://img.shields.io/badge/Website-crisisconnect.network-0f172a?style=for-the-badge&logo=safari&logoColor=white" alt="Website" /></a>
</p>

<p align="center">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-8b0000.svg" alt="License" /></a>&nbsp;
  <img src="https://img.shields.io/badge/platforms-Android%20%7C%20iOS-blue" alt="Platforms" />&nbsp;
  <img src="https://img.shields.io/badge/version-1.0.0-green" alt="Version" />&nbsp;
  <img src="https://img.shields.io/badge/kotlin-104k%20LOC-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />&nbsp;
  <img src="https://img.shields.io/badge/swift-50k%20LOC-F05138?logo=swift&logoColor=white" alt="Swift" />&nbsp;
  <img src="https://img.shields.io/badge/tests-213-brightgreen" alt="Tests" />
</p>

<p align="center">
  <a href="https://x.com/CrisisConnectHQ"><img src="https://img.shields.io/badge/X-@CrisisConnectHQ-000000?logo=x&logoColor=white" alt="X" /></a>&nbsp;
  <a href="https://www.instagram.com/crisisconnecthq/"><img src="https://img.shields.io/badge/Instagram-crisisconnecthq-E4405F?logo=instagram&logoColor=white" alt="Instagram" /></a>&nbsp;
  <a href="https://www.linkedin.com/company/112030175"><img src="https://img.shields.io/badge/LinkedIn-Crisis%20Connect-0A66C2?logo=linkedin&logoColor=white" alt="LinkedIn" /></a>&nbsp;
  <a href="https://crisisconnect.com.tr"><img src="https://img.shields.io/badge/TR-crisisconnect.com.tr-c8102e" alt="Turkish Website" /></a>
</p>

<p align="center">
  <a href="#the-problem">Problem</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#how-it-works">How It Works</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#features">Features</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#security-model">Security</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#architecture">Architecture</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#getting-started">Build From Source</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#contributing">Contribute</a>
</p>

---

## Table of Contents

- [The Problem](#the-problem)
- [How It Works](#how-it-works)
- [Screenshots](#screenshots)
- [Features](#features)
  - [Offline Communication](#offline-communication)
  - [Rescue Operations](#rescue-operations)
  - [Emergency Toolkit](#emergency-toolkit)
- [Security Model](#security-model)
  - [Encryption](#encryption)
  - [Key Exchange](#key-exchange)
  - [Identity & Certificates](#identity--certificates)
  - [Data Storage](#data-storage)
  - [API Protection](#api-protection)
- [Architecture](#architecture)
  - [Repository Structure](#repository-structure)
  - [Tech Stack](#tech-stack)
  - [Communication Protocol](#communication-protocol)
  - [Mesh Networking](#mesh-networking)
  - [Voice Pipeline](#voice-pipeline)
  - [Backend Services](#backend-services)
  - [Build Variants](#build-variants)
- [Getting Started](#getting-started)
  - [Download the App](#download-the-app)
  - [Build From Source](#build-from-source)
- [Testing](#testing)
- [Privacy](#privacy)
- [Permissions](#permissions)
- [Localization](#localization)
- [Contributing](#contributing)
- [Security Policy](#security-policy)
- [Roadmap](#roadmap)
- [Links](#links)
- [License](#license)

---

## The Problem

When disaster strikes, communication infrastructure is the first thing to fail. Cell towers collapse. Power grids go offline. Internet becomes unreachable. The very tools people depend on for coordination -- messaging apps, phone calls, maps -- stop working at the exact moment they are needed most.

This is not a hypothetical scenario. After the 2023 earthquakes in Turkey and Syria, cellular networks were down for days across entire provinces. During Hurricane Maria in Puerto Rico, 95% of cell sites were knocked out. In the 2011 Japan earthquake and tsunami, millions lost all connectivity.

Every major disaster exposes the same gap: **there is no widely available communication tool designed to work without infrastructure.**

Existing solutions either require specialized radio hardware (walkie-talkies, satellite phones) that most people don't carry, or depend on mesh networking protocols that still assume some form of internet backhaul.

Crisis Connect was built to close that gap. A communication tool that runs on the phone already in your pocket, works over Bluetooth with zero infrastructure dependency, and encrypts everything end-to-end by default.

## How It Works

Crisis Connect eliminates the dependency on centralized infrastructure. Instead of routing through cell towers or internet servers, devices talk directly to each other using the Bluetooth hardware already present in every modern smartphone.

```
     ┌──────────┐         ┌──────────┐         ┌──────────┐
     │ Device A │◄──BLE──►│ Device B │◄──BLE──►│ Device C │
     └──────────┘         └──────────┘         └──────────┘
          │                     │                     │
          │   QR Key Exchange   │   GATT Mesh Relay   │
          │   ECDH + AES-256    │   Multi-hop Forward  │
          │                     │                     │
          ▼                     ▼                     ▼
     ┌─────────────────────────────────────────────────────┐
     │              All messages E2E encrypted              │
     │         Stored only on sender & receiver             │
     │            No server ever sees plaintext             │
     └─────────────────────────────────────────────────────┘
```

The system operates across three layers:

1. **Discovery** -- BLE advertising and scanning find nearby devices automatically. No pairing menu, no Bluetooth settings. The app handles everything.

2. **Secure Channel** -- Before any communication, devices exchange cryptographic keys through QR code scanning. This creates a verified, encrypted channel between two specific devices using ECDH key agreement.

3. **Communication** -- Messages, voice, images, and files flow through this encrypted channel. If the recipient is not in direct range, the GATT mesh protocol relays data through intermediate devices -- each hop maintaining end-to-end encryption.

When internet *is* available, Firebase handles authentication and rescue team coordination -- but the core messaging stack never depends on it. The app is fully functional in airplane mode with Bluetooth enabled.

## Screenshots

<p align="center">
  <img src="docs/screenshots/ios/messages.png" width="230" alt="Messages" />&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/ios/chat.png" width="230" alt="Encrypted Chat" />&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/ios/tools.png" width="230" alt="Emergency Tools" />
</p>

<p align="center">
  <sub><strong>Left:</strong> Message inbox with trusted contacts and SOS chats&nbsp;&nbsp;&bull;&nbsp;&nbsp;<strong>Center:</strong> End-to-end encrypted P2P chat with voice messages and call history&nbsp;&nbsp;&bull;&nbsp;&nbsp;<strong>Right:</strong> Built-in emergency tools for disaster scenarios</sub>
</p>

## Features

### Offline Communication

| Feature | Description | Transport |
|:--|:--|:--|
| **Text Messaging** | End-to-end encrypted text messages with read receipts and timestamps | BLE GATT / RFCOMM |
| **Voice Calls** | Real-time voice calls encoded with Opus codec at 16kHz sample rate | RFCOMM (Bluetooth Classic) |
| **Voice Messages** | Record, send, and play back voice messages with waveform visualization | BLE GATT (chunked) |
| **Image Transfer** | Send photos with automatic compression, chunked transfer, and progress tracking | BLE GATT (chunked) |
| **File Transfer** | Share documents and files with delivery receipts | BLE GATT (chunked) |
| **Mesh Relay** | Messages hop through intermediate devices when sender and receiver aren't in direct range | GATT mesh protocol |
| **Contact Exchange** | Scan QR codes to securely add contacts and establish encrypted channels | Camera + ECDH |
| **SOS Broadcast** | Emergency broadcast visible to all nearby devices running Crisis Connect | BLE advertising |

All communication features work with **zero internet connectivity**. Messages are stored locally on-device and never pass through any server.

### Rescue Operations

Crisis Connect includes a dedicated rescue module designed for emergency response teams (AFAD, Red Cross, field teams):

- **Role-Based Access Control** -- Rescue team members are assigned `admin` or `fieldteam` roles through Firebase custom claims. Roles are cryptographically verified even when offline through ECDSA-signed role certificates.

- **Role Certificates** -- When a rescue device is online, it requests a signed certificate from Firebase Cloud Functions. This certificate (ECDSA P-256, 72-hour TTL) can be verified by any other device offline, proving the holder's rescue team identity without needing to contact a server.

- **Live Location Sharing** -- Rescue devices share real-time GPS coordinates during active operations, with configurable update intervals and battery-aware policies.

- **CrisisLink Sync** -- A background sync engine that maintains rescue session state across all team devices, handling merge conflicts and offline queue reconciliation.

- **Agency Routing** -- Firestore security rules scope data access by agency, ensuring that different rescue organizations see only their own operational data.

- **Dynamic Feature Module** -- On Android, rescue features are delivered as a dynamic feature module through Play Feature Delivery, keeping the base app lightweight for civilian users.

### Emergency Toolkit

Beyond communication, the app includes tools designed for disaster scenarios:

| Tool | Description | Platform |
|:--|:--|:--|
| **Offline Maps** | Download map regions for offline use. Navigate without internet using MapLibre vector tiles. | Android + iOS |
| **Compass** | Sensor-fused directional compass with heading, pitch, and roll readings. | Android + iOS |
| **Signal Finder** | Scans for cellular, Wi-Fi, and Bluetooth signals in the area. Helps locate zones with potential connectivity. | Android + iOS |
| **Metal Detector** | Uses the device magnetometer to detect metallic objects. Visual and audio feedback with adjustable sensitivity. | Android + iOS |
| **Emergency Whistle** | Generates high-volume acoustic signals at configurable frequencies. Louder and more sustained than a physical whistle. | Android + iOS |
| **Sensor Dashboard** | Real-time readouts from all device sensors: accelerometer, gyroscope, magnetometer, barometer, ambient light. | Android + iOS |
| **Survival Guide** | Step-by-step emergency checklists for earthquakes, floods, fires, and other scenarios. Available offline in English and Turkish. | Android + iOS |
| **LiDAR Scanner** | Uses LiDAR depth sensor for obstacle awareness in dark, smoky, or low-visibility environments. | iOS (LiDAR devices) |
| **Night Vision** | Camera-assisted obstacle detection for low-light conditions using LiDAR point cloud. | iOS (LiDAR devices) |

## Security Model

Security is not a feature layer added on top -- it is foundational to the architecture. Every design decision assumes an adversarial environment where devices may be compromised, networks may be monitored, and identities may be spoofed.

### Encryption

All messages are encrypted with **AES-256-GCM** before leaving the sending device. The encryption key is derived from the ECDH shared secret established during QR code exchange.

| Platform | Encryption Library | Algorithm |
|:--|:--|:--|
| Android | [Google Tink](https://github.com/tink-crypto/tink-java) | AES-256-GCM |
| iOS | Apple CryptoKit | AES-256-GCM |

Messages are encrypted **before** entering the BLE transport layer. Even if Bluetooth traffic is intercepted, the attacker sees only ciphertext. The GATT mesh relay nodes forward encrypted payloads without being able to read them.

### Key Exchange

Key exchange uses **Elliptic-Curve Diffie-Hellman (ECDH)** over the **P-256 curve**:

1. Device A generates an ephemeral key pair
2. Device A encodes its public key into a QR code
3. Device B scans the QR code and generates its own key pair
4. Both devices compute the shared secret using ECDH
5. The shared secret is passed through **HKDF-SHA256** to derive the AES-256 session key

This happens entirely offline. No certificate authority, no key server, no internet connection.

### Identity & Certificates

For rescue team identity verification, Crisis Connect uses a custom certificate chain:

```
Firebase Cloud Functions (trusted signer)
        │
        │── Signs certificate with ECDSA P-256
        │   using server-held private key
        │
        ▼
┌─────────────────────────────────────────┐
│           Role Certificate              │
├─────────────────────────────────────────┤
│  Owner UID                              │
│  Role (admin / fieldteam)               │
│  Public Key (Base64)                    │
│  Issued At (timestamp)                  │
│  Expires At (issued + 72 hours)         │
│  ECDSA Signature (DER-encoded)          │
│  Algorithm: SHA256withECDSA             │
│  Curve: P-256                           │
└─────────────────────────────────────────┘
```

Any device can verify a role certificate offline using the embedded public key of the signing authority. This allows rescue teams to prove their identity to civilian devices without internet connectivity.

### Data Storage

| Data | Android | iOS |
|:--|:--|:--|
| Messages | SQLCipher encrypted Room database | Keychain-backed local storage |
| Credentials | Android Keystore (hardware-backed) | iOS Keychain (Secure Enclave) |
| Preferences | EncryptedSharedPreferences | Keychain-backed preferences |
| Session keys | Keystore-backed encrypted storage | Keychain |

Messages are stored **only on the two communicating devices**. There is no server-side message store. Deleting the app removes all messages permanently.

### API Protection

When internet is available, all Firebase API calls are protected by:

- **Firebase App Check** with platform-native attestation:
  - Android: [Play Integrity API](https://developer.android.com/google/play/integrity)
  - iOS: [App Attest](https://developer.apple.com/documentation/devicecheck/establishing-your-app-s-integrity)
- **Firebase Authentication** (Google Sign-In)
- **Firestore Security Rules** -- 400+ lines of server-side access control rules enforcing per-document permissions based on user roles, agency membership, and document ownership

## Architecture

### Repository Structure

```
Crisis-Connect/
│
├── Android/                            Kotlin  ·  Jetpack Compose  ·  Material 3
│   ├── app/                            Main application module
│   │   ├── src/main/java/.../
│   │   │   ├── core/                   Crypto primitives, chunking, media processing
│   │   │   ├── data/                   Room DB, Firestore repos, BLE stores, offline maps
│   │   │   ├── navigation/             Compose navigation, deep links, route resolution
│   │   │   ├── screens/                UI: Chat, Tools, Settings, QR, SOS, Guide, Profile
│   │   │   ├── security/              ECDSA certs, role proofs, keystore, crypto helpers
│   │   │   ├── service/               BLE GATT server/client, RFCOMM, mesh, voice, files
│   │   │   ├── telecom/               Android Telecom integration for call management
│   │   │   └── ui/                    Theme, design system, shared components
│   │   ├── src/test/                   194 unit tests
│   │   └── src/androidTest/            19 instrumented tests
│   │
│   ├── feature_rescue/                 Dynamic feature module
│   │   ├── CrisisLinkForegroundService  Background rescue sync service
│   │   ├── GattRescueClientService      BLE client for rescue network
│   │   ├── MeshAwareService             Mesh networking for rescue ops
│   │   └── screens/                     Rescue UI, settings, mesh chat
│   │
│   ├── functions/                      Firebase Cloud Functions (TypeScript)
│   │   └── src/index.ts                issueRoleCertificate endpoint
│   │
│   ├── firestore.rules                 Firestore security rules
│   ├── firebase.json                   Firebase deployment config
│   ├── build.gradle.kts                Project-level build config
│   └── settings.gradle.kts             Module definitions
│
├── iOS/                                Swift  ·  SwiftUI
│   ├── Crisis Connect/
│   │   ├── App/                        App entry point, root navigation, splash
│   │   ├── Features/
│   │   │   ├── Compass/                Sensor-fused compass
│   │   │   ├── Contacts/               Contact management, QR pairing, broadcast
│   │   │   ├── GattMesh/               GATT mesh chat interface
│   │   │   ├── LiDAR/                  LiDAR depth scanning
│   │   │   ├── MetalDetector/          Magnetometer metal detection
│   │   │   ├── OfflineMap/             Tile-based offline maps
│   │   │   ├── Profile/                User profile management
│   │   │   ├── Rescue/                 Rescue client, live location, role access
│   │   │   ├── SOS/                    SOS broadcast, chat, image transfer, voice
│   │   │   ├── SensorsDashboard/       Real-time sensor readings
│   │   │   ├── Settings/               App settings, theme, language, privacy
│   │   │   ├── SignalScanner/          Signal strength scanning
│   │   │   ├── SurvivalGuide/          Emergency checklists
│   │   │   └── Whistle/                Emergency tone generator
│   │   ├── Security/                   Keychain, certs, role verification
│   │   ├── Services/
│   │   │   ├── Background/             Background refresh manager
│   │   │   ├── Connectivity/           BLE, GATT mesh, P2P, Wi-Fi Aware
│   │   │   └── Firebase/               Auth, App Check, Crashlytics, role helper
│   │   └── Shared/                     Design system, utilities, media
│   │
│   ├── Crisis ConnectTests/            Unit tests
│   └── Crisis ConnectUITests/          UI tests
│
├── LICENSE                             AGPL-3.0
└── README.md
```

### Tech Stack

| Layer | Android | iOS |
|:--|:--|:--|
| **Language** | Kotlin | Swift |
| **UI Framework** | Jetpack Compose + Material 3 | SwiftUI |
| **Bluetooth** | Android BLE GATT / RFCOMM | CoreBluetooth |
| **Mesh Protocol** | Custom GATT mesh protocol | GATT mesh + Wi-Fi Aware |
| **Encryption** | Google Tink · SQLCipher | CryptoKit · Keychain |
| **Database** | Room (SQLCipher encrypted) | SwiftData / UserDefaults (Keychain-backed) |
| **Maps** | MapLibre GL Native | MapLibre (tile-based offline) |
| **Voice Codec** | Opus (native .aar) | AVFoundation |
| **Auth** | Firebase Auth · Google Sign-In | Firebase Auth · Google Sign-In |
| **Backend** | Firestore · Cloud Functions | Firestore · Cloud Functions |
| **App Security** | Play Integrity · App Check | App Attest · App Check |
| **Crash Reporting** | Firebase Crashlytics | Firebase Crashlytics |
| **CI/CD** | GitHub Actions | Xcode Cloud |
| **Min Version** | Android 7.0 (API 24) | iOS 17.0 |
| **Target Version** | Android 15 (API 35) | iOS 18 |
| **Test Frameworks** | JUnit · Espresso · MockK · Robolectric | XCTest |

### Communication Protocol

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
│  RFCOMM (voice calls)                                        │
│  GATT mesh (multi-hop relay)                                 │
└─────────────────────────────────────────────────────────────┘
```

### Mesh Networking

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

### Voice Pipeline

Voice calls use a dedicated pipeline over Bluetooth Classic (RFCOMM):

```
Microphone ──► PCM 16kHz ──► Opus Encoder ──► RFCOMM Socket ──► Opus Decoder ──► Speaker
                                    │
                              Jitter Buffer
                           (adaptive, 60-200ms)
```

- **Codec**: Opus at 16kHz mono (optimized for speech)
- **Transport**: RFCOMM provides a reliable stream socket over Bluetooth Classic
- **Latency**: ~100-200ms end-to-end depending on device and environment
- **Call Management**: Integrated with Android Telecom API for native call UI

### Backend Services

Firebase is used for three specific purposes. None of them are required for core messaging:

| Service | Purpose | Required? |
|:--|:--|:--|
| **Firebase Auth** | User identity for rescue role management | Only for rescue features |
| **Cloud Firestore** | Rescue team coordination data, agency routing | Only for rescue features |
| **Cloud Functions** | Role certificate issuance (`issueRoleCertificate`) | Only for rescue features |
| **App Check** | API abuse prevention | Only when online |
| **Crashlytics** | Crash reporting for production stability | Optional |

The `issueRoleCertificate` Cloud Function is the only server-side business logic:

1. Validates the caller's Firebase Auth token
2. Verifies the caller has a rescue role custom claim
3. Validates the provided public key
4. Signs a role certificate with ECDSA P-256 (72-hour TTL)
5. Returns the signed certificate for offline use

### Build Variants

**Android** provides three build types:

| Variant | Purpose | Signing | App Check |
|:--|:--|:--|:--|
| `debug` | Development | Debug keystore | Debug provider |
| `internal` | QA / testing | Release keystore | Debug provider |
| `release` | Production (Play Store) | Release keystore | Play Integrity |

**iOS** uses Xcode Cloud for CI/CD with App Attest configured for production builds.

## Getting Started

### Download the App

The easiest way to use Crisis Connect is to download it from the app stores:

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.auralis.crisisconnect"><img src="https://img.shields.io/badge/Google%20Play-Download-3ddc84?style=for-the-badge&logo=google-play&logoColor=white" alt="Google Play" /></a>&nbsp;&nbsp;
  <a href="https://apps.apple.com/app/crisis-connect/id6759731195"><img src="https://img.shields.io/badge/App%20Store-Download-147efb?style=for-the-badge&logo=app-store&logoColor=white" alt="App Store" /></a>
</p>

### Build From Source

#### Prerequisites

| Platform | Requirements |
|:--|:--|
| **Android** | Android Studio Ladybug+ · JDK 17 · Android SDK 35 |
| **iOS** | Xcode 16+ · iOS 17+ deployment target · macOS Sequoia+ |
| **Backend** | Node.js 18+ · Firebase CLI · Firebase project (Firestore, Auth, Functions) |

> **Important:** BLE features require a **physical device**. Simulators and emulators do not support Bluetooth.

#### Android

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/Android

# Set up configuration files
cp app/google-services.json.example app/google-services.json
cp local.properties.example local.properties
cp keystore.properties.example keystore.properties
```

1. Download `google-services.json` from [Firebase Console](https://console.firebase.google.com) > Project Settings > Your Apps > Android app, and replace the example file
2. Edit `local.properties`:
   ```properties
   sdk.dir=/path/to/your/Android/sdk
   GOOGLE_WEB_CLIENT_ID=your-client-id.apps.googleusercontent.com
   MAPLIBRE_API_KEY=your_maplibre_api_key
   ```
3. Open in Android Studio, sync Gradle, and run on a physical device
4. For release builds, fill in `keystore.properties` with your signing configuration

#### iOS

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/iOS

# Set up configuration
cp "Crisis Connect/GoogleService-Info.plist.example" "Crisis Connect/GoogleService-Info.plist"
```

1. Download `GoogleService-Info.plist` from [Firebase Console](https://console.firebase.google.com) > Project Settings > Your Apps > iOS app, and replace the example file
2. Update URL schemes in `Config/Info.plist` with your reversed Google client ID
3. Open `Crisis Connect.xcodeproj` in Xcode
4. SPM dependencies resolve automatically on first open
5. Select a physical device target and run

#### Firebase Backend

```bash
# Install Firebase CLI if not already installed
npm install -g firebase-tools
firebase login

# Deploy Firestore security rules
firebase deploy --only firestore:rules

# Deploy Cloud Functions
cd Android/functions
npm install
firebase deploy --only functions
```

After deployment, configure **App Check** in the Firebase Console:
- Android: Enable Play Integrity provider
- iOS: Enable App Attest provider

## Testing

The project includes 213 tests across both platforms:

```bash
# ── Android (194 unit tests + 19 instrumented tests) ─────────
cd Android
./gradlew :app:testDebugUnitTest              # Unit tests
./gradlew :app:connectedDebugAndroidTest      # Instrumented tests (physical device)
./gradlew :app:lintDebug                      # Static analysis
./gradlew :feature_rescue:testDebugUnitTest   # Rescue module tests

# ── iOS ───────────────────────────────────────────────────────
cd iOS
xcodebuild test \
  -scheme "Crisis Connect" \
  -destination "platform=iOS Simulator,name=iPhone 16"
```

Test coverage includes:
- Cryptographic operations (AES-GCM encrypt/decrypt, key derivation)
- BLE message framing, chunking, and reassembly
- Role certificate creation and verification
- Mesh protocol command routing
- Voice codec pipeline
- Chat message formatting and parsing
- Offline map region management
- QR code encoding/decoding

## Privacy

Crisis Connect is designed with privacy as a core principle:

- **No telemetry or analytics on messages.** Firebase Analytics is included for app-level usage metrics (screen views, crash-free rates) but never touches message content.
- **No message content on servers.** All P2P messages are stored exclusively on the sender and receiver devices. There is no server-side message store.
- **No contact upload.** Your contacts are never uploaded to any server. Contact exchange happens locally through QR code scanning.
- **No tracking.** No advertising SDKs. No third-party tracking.
- **Location is user-controlled.** GPS is only accessed when you explicitly use offline maps or opt into live location sharing during rescue operations.
- **Data deletion.** Uninstalling the app permanently deletes all messages and keys. There is no cloud backup of conversations.

## Permissions

Crisis Connect requests only the permissions necessary for its features. Every permission maps to a specific user-facing capability:

| Permission | Why It's Needed |
|:--|:--|
| **Bluetooth** (scan, advertise, connect) | Core P2P messaging and device discovery |
| **Camera** | QR code scanning for contact exchange |
| **Microphone** | Voice calls and voice message recording |
| **Location** | Offline maps centering and rescue live location sharing |
| **Internet** | Firebase auth, rescue sync, crash reporting (optional) |
| **Notifications** | Message and call notifications |
| **Foreground Service** | Maintaining BLE connections and active voice calls |
| **Sensors** | Compass, metal detector, sensor dashboard |
| **Wi-Fi State** | Signal finder tool |

All permissions are requested at runtime with clear explanations. The app is functional with only Bluetooth permission granted -- all other permissions are optional and tied to specific features.

## Localization

Crisis Connect is currently available in:

| Language | Coverage |
|:--|:--|
| **English** | Full (UI + Survival Guide) |
| **Turkish** | Full (UI + Survival Guide) |

We welcome contributions for additional languages. See [Contributing](#contributing) for details.

## Contributing

We welcome contributions from developers, security researchers, translators, and anyone who cares about resilient communication.

### How to Contribute

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/your-feature`)
3. **Commit** with clear, descriptive messages
4. **Open** a Pull Request with a description of what you changed and why

### Contribution Areas

| Area | Description | Difficulty |
|:--|:--|:--|
| **Security Audit** | Review crypto implementation, key management, certificate verification | Advanced |
| **BLE Protocol** | Edge-case handling, connection stability, MTU negotiation | Advanced |
| **Mesh Improvements** | Routing efficiency, hop optimization, relay reliability | Advanced |
| **Localization** | Translate UI strings and survival guide content to new languages | Beginner |
| **Accessibility** | Screen reader support, dynamic type, high contrast | Intermediate |
| **Test Coverage** | Add unit and integration tests for uncovered code paths | Intermediate |
| **Documentation** | Protocol specs, architecture docs, API documentation | Intermediate |
| **Bug Reports** | Report issues with clear reproduction steps | Beginner |

For bugs and feature requests, open an [issue](https://github.com/emirhan-duman/Crisis-Connect/issues).

## Security Policy

If you discover a security vulnerability in Crisis Connect, **please do not open a public issue.** Instead, report it responsibly:

- **Email**: Report security issues through the contact information on [crisisconnect.network](https://crisisconnect.network)
- **Scope**: Encryption implementation, key management, certificate verification, data storage, API security, BLE protocol security

We take security reports seriously and will respond as quickly as possible.

## Roadmap

Crisis Connect is in production on both app stores and under active development. The roadmap reflects what has been shipped and what comes next.

### Shipped (v1.0.0)

- [x] BLE GATT peer-to-peer encrypted messaging (Android + iOS)
- [x] RFCOMM voice calls with Opus codec and jitter buffering (Android)
- [x] GATT mesh multi-hop message relay
- [x] QR-based ECDH key exchange and secure contact pairing
- [x] AES-256-GCM end-to-end encryption (Tink on Android, CryptoKit on iOS)
- [x] Image and file transfer over BLE with chunking and delivery receipts
- [x] Voice message recording, sending, and waveform playback
- [x] SOS emergency broadcast to nearby devices
- [x] Rescue role system with ECDSA-signed certificates (72h TTL)
- [x] CrisisLink background sync engine for rescue coordination
- [x] Live GPS location sharing during rescue operations
- [x] Dynamic feature module for rescue operations (Android Play Feature Delivery)
- [x] Offline maps with downloadable regions (MapLibre)
- [x] Emergency toolkit: compass, signal finder, metal detector, whistle, sensor dashboard
- [x] LiDAR scanner and night vision tool (iOS)
- [x] Survival guide with step-by-step checklists (EN/TR)
- [x] SQLCipher encrypted local database (Android)
- [x] Firebase App Check with Play Integrity and App Attest
- [x] Firestore security rules with agency-scoped access control
- [x] Google Sign-In authentication
- [x] Localization: English and Turkish
- [x] 213 automated tests (194 unit + 19 instrumented)
- [x] GitHub Actions CI for Android
- [x] Xcode Cloud CI for iOS
- [x] Published on Google Play and App Store

### In Progress

- [ ] Protocol specification documentation (BLE framing format, mesh routing algorithm, certificate schema)
- [ ] Cross-platform mesh relay testing (Android device relaying to iOS device and vice versa)
- [ ] Expanded BLE edge-case test coverage (connection drops, MTU negotiation failures, background state)

### Planned

- [ ] Group mesh chat (multi-party encrypted conversation over GATT mesh)
- [ ] Offline map region sharing between devices over BLE
- [ ] Additional language localizations (community-driven)
- [ ] Architecture decision records (ADRs) for key design choices
- [ ] WCAG accessibility audit and improvements
- [ ] Wi-Fi Direct transport layer as BLE alternative on Android
- [ ] Bluetooth voice calls on iOS (currently Android-only via RFCOMM)

## Links

<table>
<tr>
<td>

| | |
|:--|:--|
| **Website** | [crisisconnect.network](https://crisisconnect.network) |
| **Website (TR)** | [crisisconnect.com.tr](https://crisisconnect.com.tr) |
| **Google Play** | [Download for Android](https://play.google.com/store/apps/details?id=com.auralis.crisisconnect) |
| **App Store** | [Download for iOS](https://apps.apple.com/app/crisis-connect/id6759731195) |

</td>
<td>

| | |
|:--|:--|
| **X (Twitter)** | [@CrisisConnectHQ](https://x.com/CrisisConnectHQ) |
| **Instagram** | [@crisisconnecthq](https://www.instagram.com/crisisconnecthq/) |
| **LinkedIn** | [Crisis Connect](https://www.linkedin.com/company/112030175) |
| **Source Code** | [github.com/emirhan-duman/Crisis-Connect](https://github.com/emirhan-duman/Crisis-Connect) |

</td>
</tr>
</table>

## License

This project is licensed under the [GNU Affero General Public License v3.0](./LICENSE).

You are free to use, modify, and distribute this software. Any modified version made available over a network must also be open-sourced under the same license.

---

<p align="center">
  <strong>Built for resilience. Designed for crisis. Open for everyone.</strong><br/><br/>
  <sub>154,000+ lines of code across Android and iOS, 213 tests, 2 platforms, 1 mission:<br/>keeping people connected when it matters most.</sub>
</p>
