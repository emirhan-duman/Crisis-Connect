# Changelog

All notable changes to Crisis Connect are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.1] - 2026-06-14

### Added
- Android Crisis Sentinel offline assistant, model manifest/download flow, and quality coverage tests
- Android authority mesh, certificate provisioning, Enterprise SSO, and rescue mobile sync support
- Expanded Android localization resources and rescue mesh service coverage

### Changed
- Bumped Android and iOS release metadata to 1.1.1
- Refreshed Firebase Functions sources for certificate issuance, attestation, model access, and authority mesh key handling
- Updated Android build, ProGuard, Gradle, and CI-facing source files for the new release line

### Security
- Kept release signing files, Firebase config files, local property files, generated build outputs, and packaged APK/AAB artifacts out of the public source tree

## [1.0.0] - 2026-04-03

Initial public release on Google Play and App Store.

### Communication
- End-to-end encrypted P2P messaging over BLE and RFCOMM
- GATT mesh multi-hop message relay for extended range
- Opus-encoded voice calls over Bluetooth Classic (Android)
- Voice message recording and playback with waveform visualization
- Image and file transfer with chunking, progress tracking, and delivery receipts
- QR-based ECDH key exchange for secure contact pairing
- SOS emergency broadcast to nearby devices
- Read receipts and message timestamps

### Rescue Operations
- Role-based access control (admin / field team) with Firebase custom claims
- ECDSA-signed role certificates for offline identity verification (72h TTL)
- Live GPS location sharing during rescue coordination
- CrisisLink background sync engine for rescue session state
- Agency-scoped Firestore access control
- Dynamic feature module for rescue operations (Android Play Feature Delivery)

### Emergency Tools
- Offline maps with downloadable regions (MapLibre)
- Sensor-fused digital compass
- Cellular and Wi-Fi signal scanner
- Magnetometer-based metal detector
- High-volume emergency whistle with configurable frequency
- Real-time sensor dashboard (accelerometer, gyroscope, barometer)
- Survival guide with step-by-step emergency checklists
- LiDAR depth scanner and night vision tool (iOS, LiDAR-equipped devices)

### Security
- AES-256-GCM message encryption (Google Tink on Android, CryptoKit on iOS)
- ECDH P-256 key agreement via QR code exchange
- SQLCipher encrypted local database (Android)
- Android Keystore and iOS Keychain secure credential storage
- Firebase App Check with Play Integrity (Android) and App Attest (iOS)
- Firestore security rules (400+ lines) with per-document access control

### Infrastructure
- Firebase Authentication with Google Sign-In
- Firebase Cloud Functions for role certificate issuance
- GitHub Actions CI for Android (lint + unit tests)
- Xcode Cloud CI for iOS
- Localization: English and Turkish
- 213 automated tests (194 unit + 19 instrumented)

### Platforms
- Android: min SDK 24 (Android 7.0), target SDK 35 (Android 15)
- iOS: min iOS 17.0
