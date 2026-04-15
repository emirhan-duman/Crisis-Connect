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
  <img src="https://img.shields.io/badge/kotlin-154k%20LOC-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />&nbsp;
  <img src="https://img.shields.io/badge/swift-50k%20LOC-F05138?logo=swift&logoColor=white" alt="Swift" />
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
  <a href="#architecture">Architecture</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#getting-started">Build From Source</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#contributing">Contribute</a>
</p>

---

## The Problem

When disaster strikes, communication infrastructure is the first thing to fail. Cell towers collapse. Power grids go offline. Internet becomes unreachable. The very tools people depend on for coordination -- messaging apps, phone calls, maps -- stop working at the exact moment they are needed most.

Every major earthquake, flood, and hurricane exposes the same gap: **there is no widely available communication tool designed to work without infrastructure.**

## How It Works

Crisis Connect eliminates the dependency on centralized infrastructure. Instead of routing through cell towers or internet servers, devices talk directly to each other:

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

**Bluetooth Low Energy** for discovery and messaging. **RFCOMM** for voice calls. **GATT mesh** for multi-hop relay when devices aren't in direct range. **QR codes** for secure key exchange. Everything encrypted with **AES-256-GCM** before it leaves the device.

When internet *is* available, Firebase handles authentication and rescue team coordination -- but the core messaging stack never depends on it.

## Features

<table>
<tr>
<td width="50%">

**Communication**
- End-to-end encrypted P2P messaging (BLE / RFCOMM)
- Multi-hop GATT mesh networking
- Opus-encoded voice calls over Bluetooth Classic
- Chunked image & file transfer with delivery receipts
- QR-based ECDH key exchange for secure pairing

</td>
<td width="50%">

**Rescue Operations**
- Role-based access with ECDSA-signed certificates
- Real-time GPS location sharing
- CrisisLink background sync across rescue devices
- Dynamic feature delivery (Android Play Feature Delivery)
- Agency-scoped document access control

</td>
</tr>
<tr>
<td>

**Emergency Tools**
- Offline maps with downloadable regions (MapLibre)
- Digital compass
- Cellular & Wi-Fi signal scanner
- Magnetometer-based metal detector
- High-volume emergency whistle
- Localized survival guide (EN/TR)
- LiDAR obstacle scanner (iOS)
- Real-time sensor dashboard

</td>
<td>

**Security**
- AES-256-GCM message encryption (Tink / CryptoKit)
- ECDSA role certificates for offline identity verification
- SQLCipher encrypted database (Android)
- Keychain / Android Keystore secure storage
- Firebase App Check (Play Integrity + App Attest)
- Zero plaintext on servers -- messages never leave the device unencrypted

</td>
</tr>
</table>

## Architecture

```
Crisis-Connect/
│
├── Android/                        Kotlin  ·  Jetpack Compose  ·  Material 3
│   ├── app/                        Main application (268 files · 104k LOC)
│   │   ├── core/                   Crypto, chunking, media processing
│   │   ├── data/                   Room DB, Firestore, BLE data layer
│   │   ├── screens/                UI screens (Chat, Tools, Settings, QR, SOS)
│   │   ├── security/              ECDSA certificates, role verification, keystore
│   │   ├── service/               BLE GATT, RFCOMM, mesh, voice, file transfer
│   │   └── ui/                    Design system, theme, shared components
│   ├── feature_rescue/             Dynamic feature module (rescue operations)
│   ├── functions/                  Firebase Cloud Functions (TypeScript)
│   └── firestore.rules             Security rules for Firestore
│
├── iOS/                            Swift  ·  SwiftUI
│   ├── Crisis Connect/             Main application (113 files · 50k LOC)
│   │   ├── Features/              Compass, Contacts, LiDAR, Maps, Rescue, SOS, ...
│   │   ├── Security/              Keychain, certificates, role verification
│   │   ├── Services/              BLE, GATT mesh, Firebase, background sync
│   │   └── Shared/                Design system, utilities, media
│   ├── Crisis ConnectTests/        Unit tests
│   └── Crisis ConnectUITests/      UI tests
│
├── LICENSE                         AGPL-3.0
└── README.md
```

### Tech Stack

| Layer | Android | iOS |
|:--|:--|:--|
| **Language** | Kotlin | Swift |
| **UI Framework** | Jetpack Compose + Material 3 | SwiftUI |
| **Bluetooth** | Android BLE GATT / RFCOMM | CoreBluetooth / Wi-Fi Aware |
| **Mesh Protocol** | Custom GATT mesh | GATT mesh + Wi-Fi Aware |
| **Encryption** | Google Tink · SQLCipher | CryptoKit · Keychain |
| **Maps** | MapLibre GL Native | MapLibre (tile-based offline) |
| **Voice Codec** | Opus (native .aar) | AVFoundation |
| **Auth** | Firebase Auth · Google Sign-In | Firebase Auth · Google Sign-In |
| **Backend** | Firestore · Cloud Functions | Firestore · Cloud Functions |
| **App Security** | Play Integrity · App Check | App Attest · App Check |
| **CI/CD** | GitHub Actions | Xcode Cloud |
| **Min Version** | Android 7.0 (API 24) | iOS 17.0 |
| **Test Framework** | JUnit · Espresso · MockK | XCTest |

### Cloud Functions

The only server-side logic is role certificate issuance:

```
Client (rescue device)                     Firebase Cloud Functions
        │                                           │
        │── Request certificate ──────────────────►│
        │   (authenticated + App Check)             │
        │                                           │── Validate role claim
        │                                           │── Sign with ECDSA P-256
        │◄── Signed certificate ──────────────────│
        │   (72h TTL)                               │
        │                                           │
        │   Certificate used offline                │
        │   to prove identity over BLE              │
```

## Getting Started

> **Just want to use the app?** Download from [Google Play](https://play.google.com/store/apps/details?id=com.auralis.crisisconnect) or [App Store](https://apps.apple.com/app/crisis-connect/id6759731195).
>
> The instructions below are for building from source.

### Prerequisites

| Platform | Requirements |
|:--|:--|
| **Android** | Android Studio Ladybug+ · JDK 17 · SDK 35 |
| **iOS** | Xcode 16+ · iOS 17+ deployment target |
| **Backend** | Firebase project with Firestore, Auth, Cloud Functions |

> BLE features require a **physical device**. Simulators and emulators do not support Bluetooth.

### Android

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/Android

# Set up configuration files
cp app/google-services.json.example app/google-services.json    # Replace with your Firebase config
cp local.properties.example local.properties                     # Fill in API keys
cp keystore.properties.example keystore.properties               # For release signing
```

1. Download `google-services.json` from [Firebase Console](https://console.firebase.google.com) and replace the example
2. Fill in `local.properties` with your `GOOGLE_WEB_CLIENT_ID` and `MAPLIBRE_API_KEY`
3. Open in Android Studio, sync Gradle, run on a physical device

### iOS

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/iOS

# Set up configuration
cp "Crisis Connect/GoogleService-Info.plist.example" "Crisis Connect/GoogleService-Info.plist"
```

1. Download `GoogleService-Info.plist` from [Firebase Console](https://console.firebase.google.com) and replace the example
2. Update URL schemes in `Config/Info.plist` with your reversed client ID
3. Open `Crisis Connect.xcodeproj` in Xcode, resolve SPM dependencies, run on a physical device

### Firebase Backend

```bash
# Deploy Firestore security rules
firebase deploy --only firestore:rules

# Deploy Cloud Functions
cd Android/functions
npm install
firebase deploy --only functions
```

Configure **App Check** with Play Integrity (Android) and App Attest (iOS) in the Firebase Console.

## Testing

```bash
# ── Android ──────────────────────────────────────
cd Android
./gradlew :app:testDebugUnitTest              # 44 unit test files
./gradlew :app:connectedDebugAndroidTest      # Instrumented tests (device required)
./gradlew :app:lintDebug                      # Static analysis

# ── iOS ──────────────────────────────────────────
cd iOS
xcodebuild test \
  -scheme "Crisis Connect" \
  -destination "platform=iOS Simulator,name=iPhone 16"
```

## Contributing

We welcome contributions from developers, security researchers, and anyone who cares about resilient communication.

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/your-feature`)
3. **Commit** with clear, descriptive messages
4. **Open** a Pull Request

For bugs and feature requests, open an [issue](https://github.com/emirhan-duman/Crisis-Connect/issues).

**High-impact contribution areas:**
- Security auditing and cryptographic review
- BLE protocol improvements and edge-case handling
- New language localizations
- Accessibility (a11y) improvements
- Test coverage expansion
- Documentation and protocol specification

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
  <strong>Built for resilience. Designed for crisis. Open for everyone.</strong><br/>
  <sub>154,000+ lines of code across Android and iOS, serving one purpose: keeping people connected when it matters most.</sub>
</p>
