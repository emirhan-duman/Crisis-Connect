# Changelog

All notable changes to Crisis Connect are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.9] - 2026-08-13

Android `versionCode` 64, iOS `MARKETING_VERSION` 1.1.9 (`CURRENT_PROJECT_VERSION` 36).

### Added

- Secure Trust Dossiers on Android and iOS for institutional-role documents, PDF/image import, handwritten annotations, policy validation and immutable manifest freezing
- Breadcrumb Trail, CPR Assist and programmable Flashlight tools on both platforms, with English and Turkish resources
- Durable MLS authority-session state, trust stores, encrypted state vaults, wake prewarming and fail-closed call-readiness gates
- Resource-alert wake queues with acknowledgement, retry and platform notification integration
- Account-deletion data erasure for profile photos, messaging state and related backend records

### Changed

- Authority messaging and SFU orchestration hardened across Android and iOS, including MLS preparation and native persistence coverage
- SOS and rescue synchronization made more resilient to offline transitions, background delivery and duplicate signals
- Recent-message loading and notification routing updated to surface the newest conversation state reliably
- Automated coverage increased to **611 tests** (409 Android unit + 27 instrumented, 175 iOS)
- Public Android CI moved to the repository-root workflow and now builds against the documented example Firebase configuration

### Fixed

- Profile-photo cleanup and account deletion no longer leave orphaned local or cloud data
- Agency and hierarchy call setup now blocks until required MLS state is ready instead of silently falling back
- Resource alerts survive cold starts and reconnects without being acknowledged before local delivery

### Security

- Public-source synchronization is based only on tracked release commits and excludes local environment files, signing material, Firebase credentials, generated outputs, logs and Xcode user state
- Private Codemagic integration metadata is not included in the public mirror

## [1.1.8] - 2026-07-25

Consolidates the 1.1.2 through 1.1.8 store releases. Android `versionCode` 59, iOS `MARKETING_VERSION` 1.1.8.

### Added

**Internet layer (end-to-end encrypted)**
- Signal Protocol internet messaging on both platforms via libsignal (PQXDH session establishment, Double Ratchet forward secrecy), replacing the previous ECIES envelope for new sessions
- Anti-downgrade pinning: once a Signal session exists with a peer, older-format messages from that peer are dropped fail-closed
- Hardware-backed messaging identity key (Android Keystore `AGREE_KEY` / iOS Keychain)
- Static-static ECDH sender authentication, safety numbers, and TOFU warnings when a contact's identity key changes
- Cross-platform E2E golden-vector generator shared between Android and iOS
- 1:1 internet voice and video calls over WebRTC, with Android Telecom and iOS CallKit native call UI, lock-screen ringing, and PushKit/APNs VoIP + FCM wake-up so a force-quit app still rings
- Full-device screen sharing during a call, through a ReplayKit broadcast upload extension on iOS
- Encrypted attachments (images, documents, voice notes) byte-compatible across Android, iOS and the web dashboard
- Opt-in phone-number contact discovery, gated behind phone verification, with server-side delete
- Store-and-forward queueing that drains over whichever transport returns first

**Agency and rescue**
- Agency channel conversations and cross-agency ("hierarchy") messaging, interleaved into the normal chat list with unread badges, receipts, typing and deep-linked notifications
- Offline Bluetooth bridge for agency channels: messages, images, files, voice notes and voice calls relayed to nearby offline devices, with backfill of Bluetooth-era history
- Rescuer ↔ victim live voice calls, medical information hand-off, and a remote signal feed
- Background role-certificate renewal, a provisioning banner, server-side revocation checks, and rescue `deviceId` rotation on account switch
- Rescue dashboard telemetry and sightings that survive going offline

**Offline core**
- Cross-platform Android ↔ iOS Bluetooth voice calls over the GATT audio link (0xCD00), with `WRITE_NO_RESPONSE` on the fast path
- SPAKE2 (RFC 9382) P-256 nearby pairing, replacing the harvestable number beacon with a targeted short-code add
- Relay-borne identity announce so QR pairing is bidirectional and both sides end up with a usable internet identity
- SOS arming countdown (5 seconds) before broadcast, a BLE SOS beacon that survives a background kill, region-aware emergency-call button, and cloud uplink status with background store-and-forward

**Crisis Sentinel**
- On-device offline assistant executed through Google AI Edge LiteRT-LM, with a downloadable model manifest, download worker, output validation and safety coverage
- Optional online engine with a provider/model picker, tool cards, cloud chat sync with the web dashboard, and tool results rendered on the offline map

**Platform integration**
- Android: home-screen SOS and Recent Disasters widgets with live SOS status, and a Quick Settings tile that goes straight into the SOS countdown
- iOS: WidgetKit SOS and Recent Disasters widgets, an SOS Live Activity, and an iOS 18 SOS control for Control Center, the lock screen and the Action Button
- Full Android-parity onboarding on iOS: welcome flow, phone verification, avatar, permissions card, country picker
- Parent/child account linking (child profile mode)
- Firebase Analytics custom-event layer with explicit consent

### Changed
- Localization expanded from 5 to **19 languages** on both platforms (Arabic, Bengali, Chinese Simplified, English, Filipino, French, German, Hindi, Indonesian, Japanese, Kurdish, Persian, Portuguese, Russian, Spanish, Turkish, Ukrainian, Urdu, Vietnamese)
- Android `compileSdk` / `targetSdk` raised to 36 (Android 16)
- iOS CI moved to Codemagic with automatic TestFlight distribution
- Test suite grown from 213 to **534** tests (370 Android unit + 20 instrumented, 144 iOS)
- Android startup and scroll performance: baseline profiles, Compose 1.9.4, recomposition fixes
- Home search overhauled with sectioned results, full-history message search and Turkish-safe matching
- `local.properties.example` now documents every required key, including `MOBILE_SYNC_BASE_URL`

### Fixed
- Notification races and budget drops that lost message notifications; taps now open the exact thread on both platforms
- Call regressions: cold-start audio, single app-wide `CXProvider`, earpiece routing, ICE-budget reset, network-handover restart, remote-video letterboxing, screen-share colour and resolution, duplicate call events
- Pairing no longer strips a child flag on upgrade, and never saves the local user as a contact
- SOS is only declared by a pressed SOS button, and non-victims are no longer listed as SOS victims
- Cloud username no longer blanked by link/bootstrap or passive profile sync
- Signal sessions recover from a stale server prekey pool after reinstall, curing permanently undecryptable messages
- Agency chats show real display names instead of login emails, and notification bodies label control payloads instead of leaking raw wire data

### Security
- Release signing files, Firebase config files, local property files, generated build outputs and packaged APK/AAB artifacts remain outside the public source tree
- Prebuilt `.xcframework` static libraries are no longer committed; `iOS/README.md` documents how to rebuild them

### Known limitations
- 1:1 internet calls are DTLS-SRTP encrypted in transit, not additionally end-to-end encrypted above the media layer
- SOS reports delivered to an agency dashboard are transport-encrypted, not end-to-end encrypted
- SFU group calls with MLS per-frame encryption are present in the source tree but gated off and **not shipped**

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
