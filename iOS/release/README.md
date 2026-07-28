# iOS Release Artifact Production

This project already uses automatic signing with:

- Team ID: `XY9479JQWV`
- App bundle ID: `com.auralis.crisisconnect`
- Version line: `1.0.0 (1)`
- Entitlements: `App Attest` and `Wi-Fi Aware`

The fastest way to produce a deployment-reviewable artifact is to create a signed archive and export a local package from it.

## Prerequisites

- Xcode is signed into an Apple Developer account that can sign for team `XY9479JQWV`.
- The App ID `com.auralis.crisisconnect` exists on the Apple Developer portal.
- The provisioning profile that Xcode creates or selects supports the capabilities declared in `Config/CrisisConnect.entitlements`.
- `Wi-Fi Aware` is approved for this team if Apple treats it as a restricted capability in your account.

## One-command path

From the repository root:

```bash
chmod +x release/archive_and_export.sh
./release/archive_and_export.sh
```

Default output goes to `release/output/` and includes:

- signed `.xcarchive`
- exported package directory
- `.ipa` plus `.sha256` when export succeeds
- archive and export logs
- `.xcresult`
- build-settings snapshot
- archive-info dump
- source entitlements dump
- codesign dump

## Supported export modes

- `release-testing`
  Use this for a signed local IPA that can be installed on provisioned physical devices. This is the best default for a deployment evidence packet.
- `debugging`
  Use this for internal device QA when you need a development-signed export.
- `app-store-connect`
  Use this when you are preparing the package line for TestFlight or App Store submission review.
- `validation`
  Use this to validate an archive against App Store style export rules without claiming local distribution readiness.

Examples:

```bash
EXPORT_METHOD=release-testing ./release/archive_and_export.sh
EXPORT_METHOD=debugging ./release/archive_and_export.sh
EXPORT_METHOD=app-store-connect EXPORT_DESTINATION=export ./release/archive_and_export.sh
```

## Xcode path

1. Open `Crisis Connect.xcodeproj`.
2. Select scheme `Crisis Connect`.
3. Select destination `Any iOS Device (arm64)`.
4. In Signing & Capabilities, verify team `XY9479JQWV` and bundle ID `com.auralis.crisisconnect`.
5. Run `Product > Archive`.
6. In Organizer, use `Distribute App` and choose:
   - `Ad Hoc` style flow for device-distributed review builds
   - `App Store Connect` for TestFlight / App Store pipeline
7. Keep the `.xcarchive`, exported package, and generated manifest/checksum files together.

## Evidence packet attachment

After a successful export, attach these to the iOS deployment-baseline packet:

- archive path
- IPA path
- SHA-256 checksum
- archive log
- export log
- result bundle
- codesign dump
- archive info dump
- device install/run results

## Common failure modes

- `No Accounts` or `No signing certificate`
  Add the correct Apple Developer account in Xcode and let Xcode manage signing.
- `profile doesn't include entitlement`
  The selected provisioning profile does not cover `App Attest` or `Wi-Fi Aware`.
- `No devices registered` on `release-testing`
  Register the target iPhone/iPad UDIDs or switch to `debugging` for connected-device QA.
- `exportArchive` fails after a good archive
  Keep the `.xcarchive`; that still proves signed archive creation and narrows the issue to export policy rather than build correctness.
