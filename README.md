# Crisis Connect

[![Status](https://img.shields.io/badge/status-alpha-0f172a)](https://github.com/emirhan-duman/Crisis-Connect)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-8b0000.svg)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/platform-Android-3ddc84)](./Android)
[![Platform: iOS](https://img.shields.io/badge/platform-iOS-147efb)](./iOS)

Offline-first communication platform for disaster and emergency scenarios. Peer-to-peer encrypted messaging, voice calls, and rescue coordination over Bluetooth -- no internet required.

## Overview

Crisis Connect is designed for situations where conventional infrastructure (mobile networks, internet) is degraded or unavailable. Instead of relying on centralized servers, devices communicate directly using Bluetooth Low Energy (BLE), RFCOMM, and GATT mesh networking.

The project provides native Android and iOS clients that share the same protocol design and Firebase backend, enabling cross-platform encrypted communication during emergencies.

## Features

### Communication
- **P2P Encrypted Messaging** -- End-to-end encrypted text messages over BLE and RFCOMM with no internet dependency
- **GATT Mesh Networking** -- Multi-hop message relay through nearby devices, extending range beyond direct Bluetooth connection
- **Voice Calls** -- Opus-encoded voice calls over Bluetooth Classic (RFCOMM) with jitter buffering
- **Image & File Transfer** -- Chunked binary transfer over BLE with progress tracking and delivery receipts
- **QR-Based Key Exchange** -- Secure device pairing through QR code scanning (ECDH key agreement)

### Rescue Operations
- **Role-Based Access** -- Admin and field team roles with Firebase custom claims and ECDSA-signed role certificates
- **Live Location Sharing** -- Real-time GPS coordinates shared during rescue coordination
- **CrisisLink Sync** -- Background sync engine for rescue session state across devices
- **Dynamic Feature Module** -- Rescue features loaded on-demand (Android Play Feature Delivery)

### Emergency Tools
- **Offline Maps** -- MapLibre-based maps with downloadable regions for offline use
- **Compass** -- Sensor-based directional compass
- **Signal Finder** -- Scans for nearby cellular and Wi-Fi signals
- **Metal Detector** -- Magnetometer-based metal detection
- **Emergency Whistle** -- Configurable tone generator for audible signaling
- **Survival Guide** -- Localized emergency guidelines (EN/TR)
- **LiDAR Scanner** -- Depth-sensing environment scan (iOS, devices with LiDAR)
- **Sensor Dashboard** -- Real-time device sensor readings

### Security
- **AES-256-GCM Encryption** -- All messages encrypted with Tink (Android) and CryptoKit (iOS)
- **ECDSA Role Certificates** -- Server-signed certificates verify rescue team identity offline
- **SQLCipher Database** -- Local message database encrypted at rest (Android)
- **Keychain / Keystore Storage** -- Platform-native secure credential storage
- **Firebase App Check** -- Play Integrity (Android) and App Attest (iOS) enforce API access

## Architecture

```
Crisis-Connect/
├── Android/                 # Kotlin + Jetpack Compose
│   ├── app/                 # Main application module
│   ├── feature_rescue/      # Dynamic feature module (rescue operations)
│   ├── functions/           # Firebase Cloud Functions (TypeScript)
│   └── firestore.rules      # Firestore security rules
│
├── iOS/                     # Swift + SwiftUI
│   ├── Crisis Connect/      # Main application target
│   ├── Crisis ConnectTests/ # Unit tests
│   └── Crisis ConnectUITests/ # UI tests
│
├── LICENSE                  # AGPL-3.0
└── README.md
```

### Tech Stack

| Layer | Android | iOS |
|-------|---------|-----|
| UI | Jetpack Compose + Material 3 | SwiftUI |
| Networking | BLE GATT / RFCOMM | CoreBluetooth / Wi-Fi Aware |
| Crypto | Tink + SQLCipher | CryptoKit + Keychain |
| Maps | MapLibre GL Native | MapLibre (tile-based offline) |
| Auth | Firebase Auth + Google Sign-In | Firebase Auth + Google Sign-In |
| Backend | Firebase Firestore + Cloud Functions | Firebase Firestore + Cloud Functions |
| Audio | Opus codec (native .aar) | AVFoundation |
| Testing | JUnit + Espresso + MockK | XCTest |

### Backend

Firebase Cloud Functions handle role certificate issuance:

- `issueRoleCertificate` -- Signs ECDSA certificates for verified rescue team members
- Enforces App Check, authentication, and role validation
- Private key stored in Firebase Secrets Manager

Firestore security rules enforce per-document access control based on user roles and agency membership.

## Getting Started

### Prerequisites

- **Android**: Android Studio Ladybug+, JDK 17, Android SDK 35
- **iOS**: Xcode 16+, iOS 17+ deployment target
- **Backend**: Firebase project with Firestore, Authentication (Google Sign-In), and Cloud Functions enabled

### Android Setup

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/Android
```

1. Copy the example config files:
   ```bash
   cp app/google-services.json.example app/google-services.json
   cp local.properties.example local.properties
   cp keystore.properties.example keystore.properties
   ```

2. Edit `app/google-services.json` with your Firebase project credentials (download from Firebase Console)

3. Edit `local.properties`:
   ```properties
   sdk.dir=/path/to/your/Android/sdk
   GOOGLE_WEB_CLIENT_ID=your-client-id.apps.googleusercontent.com
   MAPLIBRE_API_KEY=your_maplibre_api_key
   ```

4. Open in Android Studio and sync Gradle

5. Run on a device (BLE features require a physical device)

### iOS Setup

```bash
git clone https://github.com/emirhan-duman/Crisis-Connect.git
cd Crisis-Connect/iOS
```

1. Copy the example config:
   ```bash
   cp "Crisis Connect/GoogleService-Info.plist.example" "Crisis Connect/GoogleService-Info.plist"
   ```

2. Edit `Crisis Connect/GoogleService-Info.plist` with your Firebase project credentials

3. Update `Config/Info.plist` URL schemes with your Google Client ID

4. Open `Crisis Connect.xcodeproj` in Xcode

5. Resolve Swift Package Manager dependencies (automatic on first open)

6. Select a physical device and run (BLE requires a physical device)

### Firebase Setup

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
2. Enable **Authentication** with Google Sign-In provider
3. Enable **Cloud Firestore**
4. Deploy security rules: `firebase deploy --only firestore:rules`
5. Deploy Cloud Functions: `cd Android/functions && npm install && firebase deploy --only functions`
6. Configure **App Check** with Play Integrity (Android) and App Attest (iOS)

## Testing

### Android
```bash
cd Android
./gradlew :app:testDebugUnitTest          # Unit tests
./gradlew :app:connectedDebugAndroidTest  # Instrumented tests (requires device)
./gradlew :app:lintDebug                  # Lint checks
```

### iOS
```bash
cd iOS
xcodebuild test -scheme "Crisis Connect" -destination "platform=iOS Simulator,name=iPhone 16"
```

## Design Principles

- **Offline-first** -- Core features work without any network connectivity
- **Local resilience** -- Device-to-device communication with no single point of failure
- **Security by default** -- End-to-end encryption, certificate-based identity, encrypted storage
- **Stress-tolerant UX** -- Clear, simple interfaces designed for high-pressure situations
- **Incremental delivery** -- Ship working features over inflated scope
- **Open development** -- Public codebase, reviewable architecture decisions

## Project Status

Crisis Connect is in **alpha**. The core communication stack (BLE messaging, mesh networking, voice calls) and rescue coordination features are functional. The project is under active development.

Current priorities:
1. Protocol documentation and architecture decision records
2. Expanded test coverage
3. Contribution workflow and issue templates
4. Cross-platform interoperability testing

## Contributing

Contributions are welcome. If you are interested in contributing:

1. Fork the repository
2. Create a feature branch
3. Make your changes with clear commit messages
4. Submit a pull request

For bug reports and feature requests, please open an [issue](https://github.com/emirhan-duman/Crisis-Connect/issues).

Feedback on architecture, protocol design, security model, and documentation quality is especially valued.

## License

This project is licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](./LICENSE).

This means you can freely use, modify, and distribute this software, but any modified versions that are made available over a network must also be open-sourced under the same license.
