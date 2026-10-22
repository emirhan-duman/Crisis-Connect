# Security Best Practices Report

Date: 2026-03-18
Repository: `DisasterCommunicationSystem`

## Executive Summary

I reviewed the repository as a mixed Android/Kotlin app with a TypeScript Firebase Functions backend. The `security-best-practices` skill had direct reference coverage for the TypeScript backend; the Android portion was reviewed against Android/Firebase platform security expectations and the app's own trust boundaries.

I found 4 issues worth tracking:

1. 2 High severity findings
2. 1 Medium severity finding
3. 1 Low severity finding

No hardcoded private keys were committed in the inspected source. I also verified that `keystore.properties` exists locally but is git-ignored and not tracked.

## Scope And Notes

- Reviewed Firebase callable backend authn/authz and secret handling.
- Reviewed Firestore rules relevant to rescue roles and device ownership.
- Reviewed Android app auth, certificate caching, App Check setup, local secret storage, manifest exposure, and logging behavior.
- Findings below are evidence-based and include exact file/line references.

## High Severity

### SBP-001: Rescue certificates are not bound to registered devices

Rule ID: SBP-001

Severity: High

Location:

- `functions/src/index.ts:68-131`
- `functions/src/index.ts:57-65`
- `app/src/main/java/com/auralis/crisisconnect/security/RescueDeviceRegistry.kt:11-35`
- `firestore.rules:265-302`

Evidence:

- The callable `issueRoleCertificate` signs any caller-supplied `publicKey` after checking only `request.auth` and the caller's `role` claim.
- The signed payload contains only `publicKeyBase64|ownerUid|role|issuedAtMs|expiresAtMs`.
- No `deviceId` is submitted, no `rescueDevices/{deviceId}` lookup is performed, and no device ownership proof is included in the certificate.
- The app already maintains a `rescueDevices` ownership registry, and Firestore panel writes explicitly require `currentUserOwnsRescueDevice(...)`, which shows device binding is an intended security concept elsewhere in the system.

Impact:

- Any compromised or misused rescue account can mint transferable rescue certificates for arbitrary keypairs and arbitrary devices.
- Those certificates can then be used offline until expiry, even though the backend never verified that the certified key belongs to a registered rescue device.

Fix:

- Require a `deviceId` in certificate requests.
- Verify that `deviceId` is owned by `request.auth.uid` before issuing a certificate.
- Include `deviceId` in the signed payload and in client-side verification.
- Consider recording certificate issuance events and limiting active certificates per user/device pair.

Mitigation:

- Reduce certificate TTL while the design remains key-only.
- Add backend-side anomaly detection for unusual certificate issuance volume.

False positive notes:

- If transferable rescue certificates are an intentional operational feature, that decision should be documented explicitly, because the rest of the codebase suggests device ownership is meant to matter.

### SBP-002: Internal builds ship an App Check debug bypass

Rule ID: SBP-002

Severity: High

Location:

- `app/build.gradle.kts:44-46`
- `app/build.gradle.kts:137-170`
- `app/build.gradle.kts:313-315`
- `app/src/main/java/com/auralis/crisisconnect/CrisisConnectApp.kt:77-127`

Evidence:

- The build reads `APP_CHECK_DEBUG_TOKEN` from local properties or environment.
- Both `debug` and `internal` build types embed `APP_CHECK_DEBUG_TOKEN` into `BuildConfig`.
- The `internal` variant includes `com.google.firebase:firebase-appcheck-debug`.
- At runtime, `CrisisConnectApp` selects the debug App Check provider whenever `BuildConfig.BUILD_TYPE == INTERNAL_BUILD_TYPE`, seeds the debug secret into shared preferences, and warms the debug token path.

Impact:

- If an internal APK or its embedded debug token leaks, an attacker can mint valid App Check tokens for backend resources that trust App Check.
- In this repo, `issueRoleCertificate` relies on `enforceAppCheck: true`, so this directly weakens the function's attestation boundary.

Fix:

- Restrict the debug App Check provider to the `debug` build only.
- Do not embed `APP_CHECK_DEBUG_TOKEN` in any distributable variant.
- Use a separate Firebase project for internal/testing builds if testers still need relaxed attestation.
- Fail the build when `APP_CHECK_DEBUG_TOKEN` is non-empty for `internal` or `release`.

Mitigation:

- Rotate any existing debug token that may already have been distributed.
- Audit which Firebase resources currently trust App Check and whether internal builds talk to production.

False positive notes:

- If `internal` builds never leave a tightly controlled developer environment and never talk to production, the practical risk is lower. The current code does not enforce either of those assumptions.

## Medium Severity

### SBP-003: Cached rescue certificates authorize rescue mode even when Firebase auth is absent

Rule ID: SBP-003

Severity: Medium

Location:

- `functions/src/index.ts:9-10`
- `app/src/main/java/com/auralis/crisisconnect/screens/MainScreen.kt:482-494`
- `app/src/main/java/com/auralis/crisisconnect/screens/MainScreen.kt:534-547`
- `app/src/main/java/com/auralis/crisisconnect/security/RoleCertificate.kt:44-76`

Evidence:

- Backend-issued rescue certificates are valid for 72 hours.
- In `MainScreen`, if `FirebaseAuth.getInstance().currentUser` is `null`, the app still grants `Authorized(...)` when a cached rescue certificate is locally usable.
- The same fallback is used when role verification returns `Unauthenticated` or a fetch failure.

Impact:

- A lost, stolen, or repurposed device that already holds a valid rescue certificate can keep rescue privileges without a current Firebase session until the certificate expires.
- Server-side role removal is therefore not an immediate control for offline devices.

Fix:

- Shorten the certificate lifetime.
- Require a local device-auth step such as biometrics or device credential before enabling rescue-only actions when Firebase auth is absent.
- Add revocation handling that is checked whenever the device comes back online.

Mitigation:

- If offline rescue continuity is a hard product requirement, document this as an explicit resilience tradeoff and define an incident response procedure for device loss.

False positive notes:

- This may be an intentional disaster-mode behavior. Even if intentional, it should be treated as a documented security tradeoff rather than an implicit one.

## Low Severity

### SBP-004: Verbose logs expose operational identifiers

Rule ID: SBP-004

Severity: Low

Location:

- `app/src/main/java/com/auralis/crisisconnect/service/RfcommForegroundService.kt:3613-3619`
- `app/src/main/java/com/auralis/crisisconnect/service/RfcommForegroundService.kt:3708-3713`
- `app/src/main/java/com/auralis/crisisconnect/screens/QR/QRHelpers.kt:852-855`
- `app/src/main/java/com/auralis/crisisconnect/service/gattmesh/GattMeshForegroundService.kt:2143`

Evidence:

- Logs include Bluetooth device addresses, QR-derived session codes, and other peer/session identifiers in plaintext.
- These values appear in normal operational logs, not only in one-off error handling.

Impact:

- On debug/internal builds, rooted devices, shared test devices, or centralized log collection systems, these identifiers can reveal responder relationships and session activity.

Fix:

- Redact or hash device/session identifiers before logging.
- Keep verbose transport logs behind explicit debug-only flags.
- Review Crashlytics and any other log export path to ensure these values are not retained externally.

Mitigation:

- Establish a logging policy for operational identifiers and BLE metadata.

## Recommended Next Steps

1. Fix SBP-001 first. It affects the core trust model for rescue certificate issuance.
2. Fix SBP-002 next. It weakens the App Check boundary protecting the backend.
3. Decide whether SBP-003 is an accepted offline-resilience tradeoff, then document and compensate for it if retained.
4. Clean up verbose transport logging as routine hardening.
