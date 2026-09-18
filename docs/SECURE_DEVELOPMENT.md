# Secure Development

Crisis Connect handles identity, location, emergency signals, and encrypted communications. Changes
to these paths are reviewed as security-sensitive even when they do not modify cryptographic code.

## Design principles

The project applies the secure-design principles described by Saltzer and Schroeder and by modern
mobile security guidance:

- **Fail-safe defaults and complete mediation:** Firestore rules and Cloud Functions deny requests
  that lack the required identity, App Check proof, role, certificate, ownership, or scope. Clients
  also verify offline role certificates and message authentication instead of trusting transport.
- **Least privilege and separation of privilege:** GitHub workflows start with read-only
  permissions and grant narrowly scoped write access per job. Rescue operations combine identity,
  role claims, device attestation, and signed certificates.
- **Open design:** Protocols and controls are documented in the repository. Confidentiality depends
  on protected keys and fresh nonces, not on secret algorithms or hidden message formats.
- **Economy of mechanism and limited attack surface:** Sensitive operations are centralized in
  scoped Functions handlers and platform key stores. Optional cloud and rescue features remain
  unavailable when their configuration or authorization is absent.
- **Input validation:** Backend handlers validate types, sizes, identifiers, authorization context,
  replay state, expiry, and allowed values before use. Firestore rules repeat authorization at the
  data boundary.
- **Usable security:** Pairing uses QR or short-code confirmation, safety numbers expose identity
  changes, and SOS has an arming countdown to reduce accidental broadcasts.

## Common vulnerability classes

Primary maintainers are expected to understand the vulnerability classes relevant to this codebase
and to apply the corresponding controls:

| Risk | Required control |
|:--|:--|
| Missing authentication or authorization | Authenticate at every cloud entry point; authorize the concrete object and action; enforce the same boundary in Firestore rules. |
| Injection and unsafe parsing | Use structured SDK APIs, validate types and bounds, allowlist enumerated values, and never build commands or queries from unchecked input. |
| Cryptographic misuse | Use reviewed platform or FLOSS cryptographic libraries, authenticated encryption, protocol-defined key derivation, fresh CSPRNG output, and fixed minimum key sizes. |
| Replay, downgrade, and identity confusion | Bind ciphertext to the expected identity and context; enforce freshness, deduplication, expiry, protocol versions, and anti-downgrade state. |
| Native memory corruption | Keep native surfaces small, validate lengths before copies, and exercise them with AddressSanitizer-backed ClusterFuzzLite targets. |
| Secret disclosure and supply-chain compromise | Keep credentials out of source, use platform secret stores, pin CI actions, scan dependencies, and sign release assets with keyless Sigstore identities. |
| Privacy or availability loss | Minimize retained data, encrypt sensitive local state, make deletion explicit, bound queues and payloads, and preserve offline failure modes. |

The [security policy](../SECURITY.md) defines private reporting and response targets. Confirmed
medium-or-higher vulnerabilities from static analysis, dynamic analysis, dependency review, or
external reports are fixed before release or tracked as a release blocker. Publicly known project
vulnerabilities are named in the relevant release notes.

## Cryptographic baseline

Production security mechanisms use published, expert-reviewed algorithms and protocols: AES-256-GCM,
HKDF-SHA-256, P-256 ECDH/ECDSA, SPAKE2 as specified by RFC 9382, Signal Protocol, MLS, and
DTLS-SRTP. Android uses Google Tink, Android Keystore, SQLCipher, and libsignal; iOS uses CryptoKit,
Keychain, and libsignal. Equivalent implementations of the algorithms and protocols are available
in FLOSS libraries.

Keys and nonces come from platform cryptographically secure random-number generators. The project
does not offer configuration that selects MD4, MD5, DES, RC4, ECB, or unauthenticated encryption as
a security mechanism. Password authentication is delegated to Firebase Authentication; the project
does not store end-user password verifiers.

## Verification policy

- Major functionality includes automated tests for normal, malformed, unauthorized, replayed, and
  boundary inputs where applicable. Recent feature tests are visible beside the production code and
  summarized in [CHANGELOG.md](../CHANGELOG.md).
- Android lint and compiler diagnostics must be reviewed. The version-controlled lint baseline
  records accepted compatibility and migration debt; `warningsAsErrors` makes every new lint
  diagnostic fail CI. Baseline changes require the same review as source changes.
- CodeQL runs on pull requests and pushes to `main`. Dependency review is automated with Dependabot
  and npm audit. Native frame handling is fuzzed with AddressSanitizer and ClusterFuzzLite.
- Release assets are delivered over HTTPS and accompanied by keyless Sigstore bundles, a CycloneDX
  SBOM, SHA-256 checksums, and a GitHub artifact attestation binding the SBOM to the asset digests.
  Follow [Release verification](RELEASE_VERIFICATION.md) before pilot distribution. Hashes fetched
  over unauthenticated HTTP are never treated as trusted verification data.
- Firebase Analytics and Crashlytics must be disabled in platform configuration before application
  startup. Collection begins only after an explicit stored opt-in, disabling a choice clears queued
  telemetry, and crash reports must not carry Firebase user IDs or message/location content.

The public workflows under [`.github/workflows`](../.github/workflows) and test suites under the
platform source trees are the executable evidence for this policy.
