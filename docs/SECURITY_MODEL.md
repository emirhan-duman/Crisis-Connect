# Security Model

Security is not a feature layer added on top -- it is foundational to the Crisis Connect architecture. Every design decision assumes an adversarial environment where devices may be compromised, networks may be monitored, and identities may be spoofed.

## Contents

- [Bluetooth Layer Encryption](#bluetooth-layer-encryption)
- [Internet Layer Encryption](#internet-layer-encryption)
- [Pairing & Key Exchange](#pairing--key-exchange)
- [Identity & Certificates](#identity--certificates)
- [Data Storage](#data-storage)
- [API Protection](#api-protection)
- [What Is *Not* End-to-End Encrypted](#what-is-not-end-to-end-encrypted)
- [Privacy](#privacy)
- [Related Documentation](#related-documentation)

---

## Bluetooth Layer Encryption

All offline messages are encrypted with **AES-256-GCM** before leaving the sending device. The key is derived from the ECDH shared secret established during pairing.

| Platform | Encryption Library | Algorithm |
|:--|:--|:--|
| Android | [Google Tink](https://github.com/tink-crypto/tink-java) | AES-256-GCM |
| iOS | Apple CryptoKit | AES-256-GCM |

Messages are encrypted **before** entering the BLE transport layer. Even if Bluetooth traffic is intercepted, the attacker sees only ciphertext. The GATT mesh relay nodes forward encrypted payloads without being able to read them.

## Internet Layer Encryption

Internet messaging runs the **Signal Protocol** through [libsignal](https://github.com/signalapp/libsignal) -- the same library Signal ships -- on both platforms:

- **PQXDH** session establishment with a server-hosted prekey pool
- **Double Ratchet** forward secrecy: every message advances the chain, and a message key is one-time
- **Static-static ECDH sender authentication** binds a received conversation to the authenticated sender
- **Anti-downgrade pin**: once a Signal session exists with a peer, an older-format message from that peer is dropped fail-closed
- **Hardware-backed identity key** (Android Keystore `AGREE_KEY` / iOS Keychain)
- **TOFU warning** when a contact's identity key changes, with safety numbers for out-of-band verification

The relay server stores only ciphertext, and expired envelopes are purged server-side.

## Pairing & Key Exchange

Two offline paths establish a verified channel:

**QR pairing** uses **Elliptic-Curve Diffie-Hellman (ECDH)** over the **P-256 curve**:

1. Device A generates a key pair
2. Device A encodes its public key into a QR code
3. Device B scans the QR code and generates its own key pair
4. Both devices compute the shared secret using ECDH
5. The shared secret is passed through **HKDF-SHA256** to derive the AES-256 session key
6. A relay-borne identity announce makes the pairing bidirectional, so both sides end up with a usable internet identity too

**Nearby pairing** uses **SPAKE2 (RFC 9382)** over P-256: a password-authenticated key exchange driven by a short code, so a device never advertises a harvestable phone number or identifier in order to be addable.

Both happen entirely offline. No certificate authority, no key server, no internet connection.

## Identity & Certificates

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

Any device can verify a role certificate offline using the embedded public key of the signing authority. This allows rescue teams to prove their identity to civilian devices without internet connectivity. Issuance is gated behind platform attestation (Play Integrity on Android, App Attest on iOS).

## Data Storage

| Data | Android | iOS |
|:--|:--|:--|
| Messages | SQLCipher encrypted Room database | Keychain-backed local storage |
| Signal sessions | Room, inside the SQLCipher database | Keychain-backed store |
| Credentials | Android Keystore (hardware-backed) | iOS Keychain (Secure Enclave) |
| Preferences | EncryptedSharedPreferences | Keychain-backed preferences |
| Media blobs | Encrypted on-device cache for offline viewing | Encrypted on-device cache |

P2P messages are stored **only on the two communicating devices**. Internet messages transit the relay as ciphertext and are deleted after delivery. Deleting the app removes all local messages and keys permanently.

## API Protection

When internet is available, all Firebase API calls are protected by:

- **Firebase App Check** with platform-native attestation:
  - Android: [Play Integrity API](https://developer.android.com/google/play/integrity)
  - iOS: [App Attest](https://developer.apple.com/documentation/devicecheck/establishing-your-app-s-integrity)
- **Firebase Authentication** (Google Sign-In, phone verification, optional enterprise SSO)
- **Firestore Security Rules** -- server-side access control enforcing per-document permissions based on user roles, agency membership, and document ownership

## What Is *Not* End-to-End Encrypted

Being explicit about the boundaries matters more than a marketing claim:

- **1:1 internet calls** are encrypted in transit with **DTLS-SRTP** (standard WebRTC). They are not additionally end-to-end encrypted above the media layer, and they traverse a TURN relay when a direct path is unavailable.
- **SOS reports sent to an agency dashboard** are transport-encrypted, not end-to-end encrypted -- the receiving agency is, by design, able to read them.
- **Group calls over an SFU with MLS per-frame encryption** exist in this repository as an experimental, unshipped backend. They are **not enabled in the released apps**. See the [Roadmap](../README.md#roadmap).
- **Crash reporting and analytics** report app-level events, never message content.

## Privacy

Crisis Connect is designed with privacy as a core principle:

- **Telemetry is opt-in.** Firebase Analytics, Crashlytics, and Android Performance Monitoring start disabled and send data only after explicit privacy/diagnostics choices. Events use coarse app-level categories and never include message content, phone numbers, location, or Firebase user IDs.
- **No plaintext on servers.** P2P messages never leave the two devices. Internet messages transit the relay as Signal Protocol ciphertext and are purged after delivery.
- **No contact upload.** Contact discovery is opt-in, gated behind phone verification, and can be turned off. Contact exchange works entirely locally through QR or SPAKE2 pairing.
- **No tracking.** No advertising SDKs. No third-party tracking.
- **Location is user-controlled.** GPS is only accessed when you explicitly use offline maps, share a location, or opt into live location sharing during rescue operations.
- **Data deletion.** In-app account deletion clears the enumerated cloud data and the local data explicitly covered by the erase flow; active SOS records are identity-redacted so an ongoing rescue is not destroyed. Some platform-managed keys and retained local stores currently remain, and uninstall behavior for platform-managed stores depends on the operating system. Internet message envelopes are deleted after delivery and are never cloud conversation backups. See the [data inventory, retention, and deletion standard](DATA_INVENTORY_AND_RETENTION.md) for the implemented controls and pilot gaps.

## Related Documentation

- [Threat Model](THREAT_MODEL.md)
- [Secure Development Policy](SECURE_DEVELOPMENT.md)
- [External Interfaces Reference](EXTERNAL_INTERFACES.md)
- [Data Inventory, Retention, and Deletion](DATA_INVENTORY_AND_RETENTION.md)
- [Release Verification Guide](RELEASE_VERIFICATION.md)
- [Security Incident Response Plan](INCIDENT_RESPONSE.md)
- [Security Policy & Responsible Disclosure](../SECURITY.md)
