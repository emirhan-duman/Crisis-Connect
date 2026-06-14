# Disaster Communication System

`Disaster Communication System` is an Android-first crisis communication platform whose primary mobile product is shipped as **Crisis Connect**. The repository is built for degraded-network and offline-first scenarios where responders and civilians may need nearby communication, rescue coordination, and field tools even when conventional connectivity is unreliable or unavailable.

It combines Bluetooth-based communication, offline map support, role-aware rescue workflows, and Firebase-backed identity and telemetry services in a single multi-module codebase.

## Highlights

- Nearby messaging over BLE and GATT-based peer-to-peer / mesh flows
- RFCOMM-backed call handling and low-connectivity voice transport
- Reliable image, voice, and file transfer with chunking, acknowledgements, and receipt tracking
- SOS broadcasting and rescue-side discovery workflows
- On-demand `feature_rescue` delivery for rescue operations
- Offline map download, storage, and sharing powered by MapLibre
- Built-in field tools such as compass, whistle, signal finder, and sensor-based utilities
- Firebase Auth, Firestore, Functions, Crashlytics, Performance Monitoring, Analytics, and App Check integration
- Android Keystore-backed identity material, encrypted local storage, and backend-issued role certificates

## Repository Structure

| Path | Purpose |
| --- | --- |
| `app` | Main Android application: Compose UI, local storage, messaging, calls, media transfer, maps, tools, settings, and shared chat flows |
| `feature_rescue` | Android dynamic feature module for rescue coordination, rescue mesh, scanning, and Crisis Link sync |
| `functions` | Firebase Cloud Functions codebase that issues signed rescue role certificates |
| `dashboard` | Lightweight single-page summary surface for presenting the system at a high level |
| `.github/workflows` | CI pipeline definitions for Android lint and unit-test validation |
| `scripts` | Helper scripts such as Firebase config preparation |

## Core Product Areas

### 1. Communication Layer

- Nearby direct messaging over BLE
- GATT mesh chat flows for broader local propagation
- RFCOMM call flows for voice communication
- Media and file transfer pipelines for images, audio, and shared documents
- QR-assisted chat setup and secure session flows

### 2. Rescue Operations

- SOS signal discovery and rescue-side scanning
- Dedicated rescue UI delivered as an on-demand dynamic feature
- Crisis Link synchronization to Firestore-backed rescue schemas
- Role-restricted rescue access using backend-issued certificates and claim-based authorization

### 3. Offline and Field Utilities

- Offline map region download, storage, and import/export support
- Location sharing and map-based chat context
- Compass, whistle, signal finder, sensor tool, and metal detector style utilities
- English, Turkish, Japanese, and Spanish resource support in the Android app

## Architecture Overview

The Android client is organized around a core `app` module plus an on-demand `feature_rescue` module delivered through Play Feature Delivery. The main app owns messaging, calls, storage, map tooling, and shared UI flows. Rescue-only capabilities are isolated in the dynamic feature so they can be installed only when needed.

Firebase is used for identity, authorization-adjacent metadata, rescue telemetry, and certificate issuance. Local persistence uses Room, SQLCipher, DataStore, and keystore-backed secure storage. Mapping and offline regions are handled with MapLibre. Crash reporting and runtime diagnostics are wired through Crashlytics and Firebase Performance Monitoring.

## Technology Stack

| Area | Implementation |
| --- | --- |
| Android UI | Kotlin, Jetpack Compose, Material 3 |
| Modules | `app` + `feature_rescue` dynamic feature |
| Android SDK | `minSdk 24`, `compileSdk 35`, `targetSdk 35` |
| Local storage | Room, SQLCipher, DataStore, encrypted preferences |
| Connectivity | BLE, GATT, RFCOMM, foreground services |
| Maps | MapLibre with offline region support |
| Backend | Firebase Auth, Firestore, Functions, App Check |
| Observability | Crashlytics, Analytics, Firebase Performance |
| Backend runtime | Firebase Functions on Node.js 22 |

## Prerequisites

Before building locally, make sure the following are available:

- Android Studio with Android SDK 35
- JDK 17 for Gradle / Android tooling compatibility
- Node.js 22 if you will build or deploy Firebase Functions
- A Firebase project if you want live authentication, Firestore, App Check, or certificate issuance
- Google Services configuration files for Android builds that use Firebase

## Local Setup

### 1. Clone and open the project

```bash
git clone <your-repo-url>
cd DisasterCommunicationSystem
```

Open the project in Android Studio after syncing Gradle.

### 2. Prepare Firebase Android config

The repository includes a helper script that places `google-services.json` files into the expected locations.

```bash
./scripts/prepare_google_services.sh
```

Supported inputs, in priority order:

- `GOOGLE_SERVICES_JSON_PATH` or `GOOGLE_SERVICES_JSON_BASE64`
- `GOOGLE_SERVICES_INTERNAL_JSON_PATH` or `GOOGLE_SERVICES_INTERNAL_JSON_BASE64`
- Fallback files under `.secrets/`
- Existing local target files already present in the repo checkout

Targets written by the script:

- `app/google-services.json`
- `app/src/internal/google-services.json`

### 3. Configure local Android secrets

Set the required values in `local.properties` or export them as environment variables:

```properties
GOOGLE_WEB_CLIENT_ID=your-google-web-client-id
MAPLIBRE_API_KEY=your-maplibre-api-key
APP_CHECK_DEBUG_TOKEN=your-firebase-app-check-debug-token
RESCUE_NODE_ID_HEX_LENGTH=12
```

Notes:

- `GOOGLE_WEB_CLIENT_ID` is used for Google Sign-In.
- `MAPLIBRE_API_KEY` is required for hosted style requests and map features.
- `APP_CHECK_DEBUG_TOKEN` is useful for debug and internal builds.
- `RESCUE_NODE_ID_HEX_LENGTH` is optional and defaults to `12`.

### 4. Configure signing for `internal` / `release` builds

Debug builds work without release signing, but `internal` and `release` builds require signing material.

Create `keystore.properties` from the included example:

```bash
cp keystore.properties.example keystore.properties
```

Supported keys:

```properties
STORE_FILE=app/release-key.jks
STORE_PASSWORD=change_me
KEY_ALIAS=release
KEY_PASSWORD=change_me
```

You can also provide the same values via environment variables:

- `ANDROID_KEYSTORE_FILE`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

### 5. Configure Firebase Functions secrets if deploying backend services

The callable function in `functions/` signs rescue role certificates and expects the Firebase secret:

- `MASTER_PRIVATE_KEY_PEM`

Without that secret, certificate issuance will fail.

## Build and Verification

### Android

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :feature_rescue:testDebugUnitTest
```

If release signing is configured, you can also validate the signed internal variant:

```bash
./gradlew :app:testInternalUnitTest
```

### Firebase Functions

```bash
cd functions
npm ci
npm run build
```

Optional local backend workflows:

```bash
cd functions
npm run serve
npm run deploy
```

## CI

GitHub Actions runs Android validation on pushes to `main` / `master` and on pull requests. The current workflow executes:

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest --no-daemon
```

## Security Model

The repo contains explicit security controls for rescue-only access and local device identity:

- Rescue authorization is based on Firebase authentication plus role-aware claims / profile checks.
- The backend issues signed role certificates through `issueRoleCertificate`.
- Certificate issuance enforces Firebase App Check.
- Device-side verification validates certificate shape, ownership, time validity, and signature.
- Local identity and cached sensitive values are stored with Android secure storage primitives.
- Firestore rules enforce ownership, agency scoping, rescue panel access, and rescue device constraints.

The current Cloud Function issues certificates with:

- ECDSA `P-256`
- `SHA256withECDSA`
- a default validity window of 72 hours

## Operational Notes

- `feature_rescue` is a Play-delivered on-demand dynamic feature and depends on `app`.
- `internal` is a dedicated build type for signed internal distribution.
- The app uses multiple foreground services for communication, rescue sync, maps, and call flows.
- Some features are hardware-dependent and gracefully degrade on devices without the required radios or sensors.
- The `dashboard` directory currently contains a minimal summary surface, not a full standalone web product.

## Recommended Onboarding Order

If you are new to the codebase, this order is usually the fastest way to get productive:

1. Read `app/build.gradle.kts` and `feature_rescue/build.gradle.kts` to understand modules and build variants.
2. Open `MainActivity.kt` and `MainScreen.kt` to understand the primary app shell and navigation.
3. Review `feature_rescue` for rescue-only flows and dynamic delivery.
4. Review `functions/src/index.ts` and `firestore.rules` before changing authorization or rescue data flows.

## Current Scope of the Repository

This repository is best understood as a field-communication platform codebase rather than a single Android screen demo. It includes:

- a production-style Android application surface
- an on-demand rescue operations module
- Firebase backend support for signed role certificates
- CI automation for Android validation
- a lightweight web-facing project summary page

If you plan to extend the project, the highest-impact areas are usually rescue workflows, low-connectivity transport reliability, offline map operations, and hardening of Firebase-backed authorization flows.
