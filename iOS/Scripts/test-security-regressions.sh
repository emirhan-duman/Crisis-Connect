#!/bin/bash
set -euo pipefail
IOS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_ROOT="${SECURITY_TEST_OUTPUT:-${TMPDIR:-/tmp}/crisis-connect-security-tests}"
mkdir -p "$CACHE_ROOT/modules"
# Compile the real wire model without the Firebase-dependent proof creator. No security logic is stubbed.
awk '/^final class RoleProofCreator/ { exit } { print }' \
  "$IOS_ROOT/Crisis Connect/Security/RoleProofCreator.swift" > "$CACHE_ROOT/RoleProofPayload.swift"
xcrun swiftc -O -module-cache-path "$CACHE_ROOT/modules" \
  "$IOS_ROOT/Crisis Connect/Security/AuthorityMeshKeyLease.swift" \
  "$IOS_ROOT/Crisis Connect/Security/KeychainStore.swift" \
  "$IOS_ROOT/Crisis Connect/Security/RoleCertificate.swift" \
  "$IOS_ROOT/Crisis Connect/Security/RoleProofVerifier.swift" \
  "$IOS_ROOT/Crisis Connect/Services/Connectivity/BleCrypto.swift" \
  "$IOS_ROOT/Crisis Connect/Features/Messaging/MessagingIdentity.swift" \
  "$IOS_ROOT/Crisis Connect/Features/Messaging/InternetMessagingCrypto.swift" \
  "$IOS_ROOT/Crisis Connect/Features/Messaging/NearbyPairingAdmission.swift" \
  "$IOS_ROOT/Crisis Connect/Features/Messaging/Spake2P256.swift" \
  "$IOS_ROOT/Crisis Connect/Features/Messaging/NearbySpakePairing.swift" \
  "$IOS_ROOT/Crisis Connect/Features/Messaging/Signal/SignalMigrationPolicy.swift" \
  "$IOS_ROOT/Crisis Connect/Services/Connectivity/Telsiz/SecureMeshPtt.swift" \
  "$CACHE_ROOT/RoleProofPayload.swift" "$IOS_ROOT/Scripts/security-regressions.swift" \
  -o "$CACHE_ROOT/security-regressions"
"$CACHE_ROOT/security-regressions"
