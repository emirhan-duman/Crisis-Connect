<p align="center">
  <img src="https://github.com/emirhan-duman/Crisis-Connect/raw/main/Android/app/src/main/res/drawable-nodpi/dcslogo.png" alt="Crisis Connect" width="120" />
</p>

<h1 align="center">Crisis Connect</h1>

<p align="center">
  Offline-first communication platform for disaster and emergency scenarios.<br/>
  Peer-to-peer encrypted messaging, voice calls, and rescue coordination over Bluetooth.
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.auralis.crisisconnect"><img src="https://img.shields.io/badge/Google%20Play-Available-3ddc84?logo=google-play&logoColor=white" alt="Google Play" /></a>
  <a href="https://apps.apple.com/app/crisis-connect/id6742044940"><img src="https://img.shields.io/badge/App%20Store-Available-147efb?logo=app-store&logoColor=white" alt="App Store" /></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-8b0000.svg" alt="License: AGPL-3.0" /></a>
</p>

<p align="center">
  <a href="#features">Features</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#architecture">Architecture</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#getting-started">Getting Started</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;
  <a href="#contributing">Contributing</a>
</p>

---

## Why Crisis Connect

When disaster strikes, communication infrastructure is often the first thing to fail. Cell towers go down. Internet becomes unavailable. The tools people rely on every day stop working exactly when they are needed most.

Crisis Connect takes a different approach: **every core feature works without internet**. Devices communicate directly over Bluetooth, forming ad-hoc mesh networks that relay messages across multiple hops. Encryption is built in from the ground up. Rescue teams get dedicated coordination tools with cryptographically verified identities.

The result is a communication system that remains operational when everything else fails.

## Features

<table>
<tr>
<td width="50%">

### Communication
- **P2P Encrypted Messaging** -- E2E encrypted text over BLE/RFCOMM, zero internet dependency
- **GATT Mesh Networking** -- Multi-hop relay through nearby devices extends range beyond direct connection
- **Voice Calls** -- Opus-encoded calls over Bluetooth Classic with jitter buffering
- **Image & File Transfer** -- Chunked binary transfer with progress tracking and delivery receipts
- **QR Key Exchange** -- ECDH key agreement through QR code scanning

</td>
<td width="50%">

### Rescue Operations
- **Role-Based Access** -- Admin/field team roles with ECDSA-signed certificates
- **Live Location Sharing** -- Real-time GPS during rescue coordination
- **CrisisLink Sync** -- Background state sync across rescue devices
- **Dynamic Feature Module** -- On-demand rescue features via Play Feature Delivery

</td>
</tr>
<tr>
<td>

### Emergency Tools
- **Offline Maps** -- MapLibre with downloadable regions
- **Compass** -- Sensor-based directional navigation
- **Signal Finder** -- Cellular/Wi-Fi signal scanning
- **Metal Detector** -- Magnetometer-based detection
- **Emergency Whistle** -- Audible tone generator
- **Survival Guide** -- Localized guidelines (EN/TR)
- **LiDAR Scanner** -- Depth-sensing (iOS with LiDAR)
- **Sensor Dashboard** -- Real-time sensor readings

</td>
<td>

### Security
- **AES-256-GCM** -- Message encryption via Tink / CryptoKit
- **ECDSA Role Certificates** -- Offline identity verification for rescue teams
- **SQLCipher** -- Encrypted local database (Android)
- **Keychain / Keystore** -- Platform-native secure storage
- **App Check** -- Play Integrity + App Attest API protection
- **Certificate Pinning** -- Server communication integrity

</td>
</tr>
</table>

## Architecture

Crisis Connect is a native cross-platform system with a shared Firebase backend:

```
Crisis-Connect/
├── Android/                    Kotlin  ·  Jetpack Compose  ·  Material 3
│   ├── app/                    Main application module (268 source files)
│   ├── feature_rescue/         Dynamic feature module for rescue operations
│   ├── functions/              Firebase Cloud Functions (TypeScript)
│   └── firestore.rules         Firestore security rules
│
├── iOS/                        Swift  ·  SwiftUI
│   ├── Crisis Connect/         Main application target (113 source files)
│   ├── Crisis ConnectTests/    Unit tests
│   └── Crisis ConnectUITests/  UI tests
│
├── LICENSE
└── README.md
```

### Tech Stack

| | Android | iOS |
|:--|:--|:--|
| **Language** | Kotlin | Swift |
| **UI** | Jetpack Compose + Material 3 | SwiftUI |
| **BLE** | Android BLE GATT / RFCOMM | CoreBluetooth |
| **Mesh** | Custom GATT mesh protocol | GATT mesh + Wi-Fi Aware |
| **Crypto** | Google Tink + SQLCipher | CryptoKit + Keychain |
| **Maps** | MapLibre GL Native | MapLibre (tile-based) |
| **Audio** | Opus codec (native) | AVFoundation |
| **Auth** | Firebase Auth + Google Sign-In | Firebase Auth + Google Sign-In |
| **Backend** | Firestore + Cloud Functions | Firestore + Cloud Functions |
| **CI** | GitHub Actions | Xcode Cloud |
| **Min Version** | Android 7.0 (API 24) | iOS 17.0 |

### Communication Protocol

```
Device A                    Device B                    Device C
   │                           │                           │
   │◄── BLE Scan/Advertise ──►│                           │
   │                           │                           │
   │── ECDH Key Exchange ────►│                           │
   │   (via QR code scan)      │                           │
   │                           │                           │
   │── AES-256-GCM Message ──►│── GATT Mesh Relay ──────►│
   │   (chunked over BLE)      │   (multi-hop forward)     │
   │                           │                           │
   │◄─ Delivery Receipt ──────│                           │
   │                           │                           │
   │══ RFCOMM Voice Call ════►│                           │
   │   (Opus @ 16kHz)          │                           │
```

### Backend Services

Firebase Cloud Functions provide server-side operations:

- **`issueRoleCertificate`** -- Signs ECDSA certificates for verified rescue team members, enforcing App Check and role validation. Private key stored in Firebase Secrets Manager.

Firestore security rules enforce per-document access control based on user roles, agency membership, and document ownership.

## Getting Started

### Prerequisites

| Platform | Requirements |
|:--|:--|
| **Android** | Android Studio Ladybug+, JDK 17, SDK 35 |
| **iOS** | Xcode 16+, iOS 17+ deployment target |
| **Backend** | Firebase project (Firestore, Auth, Cloud Functions) |

> **Note:** BLE features require a physical device. Simulators/emulators do not support Bluetooth.

### Android

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/Android

# Copy config templates
cp app/google-services.json.example app/google-services.json
cp local.properties.example local.properties
cp keystore.properties.example keystore.properties
```

1. Download `google-services.json` from [Firebase Console](https://console.firebase.google.com) and replace the example file
2. Fill in `local.properties` with your API keys
3. Open in Android Studio, sync Gradle, and run on a physical device

### iOS

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/iOS

# Copy config template
cp "Crisis Connect/GoogleService-Info.plist.example" "Crisis Connect/GoogleService-Info.plist"
```

1. Download `GoogleService-Info.plist` from [Firebase Console](https://console.firebase.google.com) and replace the example file
2. Update URL schemes in `Config/Info.plist` with your Google Client ID
3. Open `Crisis Connect.xcodeproj` in Xcode, resolve SPM dependencies, and run on a physical device

### Firebase

1. Create a project at [Firebase Console](https://console.firebase.google.com)
2. Enable **Authentication** (Google Sign-In) and **Cloud Firestore**
3. Deploy rules and functions:
   ```bash
   firebase deploy --only firestore:rules
   cd Android/functions && npm install && firebase deploy --only functions
   ```
4. Configure **App Check** with Play Integrity (Android) and App Attest (iOS)

## Testing

```bash
# Android - Unit tests
cd Android && ./gradlew :app:testDebugUnitTest

# Android - Instrumented tests (requires device)
./gradlew :app:connectedDebugAndroidTest

# Android - Lint
./gradlew :app:lintDebug

# iOS - All tests
cd iOS && xcodebuild test \
  -scheme "Crisis Connect" \
  -destination "platform=iOS Simulator,name=iPhone 16"
```

## Contributing

Contributions are welcome. Please follow these steps:

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/your-feature`)
3. **Commit** with clear messages
4. **Push** and open a **Pull Request**

For bug reports and feature requests, open an [issue](https://github.com/emirhan-duman/Crisis-Connect/issues).

Areas where contributions are especially valued:
- Protocol documentation and security auditing
- Test coverage improvements
- Localization (new languages)
- Accessibility enhancements

## License

Licensed under the [GNU Affero General Public License v3.0](./LICENSE).

You are free to use, modify, and distribute this software. Any modified version made available over a network must also be open-sourced under the same license.

---

<p align="center">
  <sub>Built for resilience. Designed for crisis.</sub>
</p>
