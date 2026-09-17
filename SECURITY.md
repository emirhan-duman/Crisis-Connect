# Security Policy

## Reporting a Vulnerability

Crisis Connect takes security seriously. The application handles encrypted communications and sensitive user data in disaster scenarios, making security a top priority.

The repository's security boundaries, assets, attacker assumptions, abuse cases, and pilot readiness
requirements are documented in the [Crisis Connect threat model](docs/THREAT_MODEL.md). Implementation
and review requirements are documented in the [secure development guide](docs/SECURE_DEVELOPMENT.md).
Operators handling a suspected production event must follow the
[security incident response plan](docs/INCIDENT_RESPONSE.md).

If you discover a security vulnerability, **please do not open a public issue.**

### How to Report

1. Open a [private vulnerability report](https://github.com/emirhan-duman/Crisis-Connect/security/advisories/new)
   on GitHub. The report and all follow-up discussion remain private between you and the maintainers.
2. Include a detailed description of the vulnerability and its impact.
3. Provide steps to reproduce or a minimal proof of concept when possible.
4. Do not include secrets or personal data that are unnecessary to reproduce the issue.
5. Allow reasonable time for a fix before public disclosure.

### Scope

The following areas are in scope for security reports:

- Encryption implementation (AES-256-GCM, ECDH key exchange)
- Key management and storage (Keystore, Keychain, SQLCipher)
- Role certificate verification (ECDSA signatures)
- BLE protocol security (message framing, mesh relay)
- Firebase security rules and Cloud Functions
- Authentication and authorization flows
- Data storage and privacy

### Response

- We will acknowledge receipt within 48 hours
- We will provide an initial assessment within 7 days
- Critical vulnerabilities will be prioritized for immediate patching
- We will coordinate disclosure and assign or request a CVE when appropriate

### Recognition

Security researchers who responsibly disclose vulnerabilities will be credited in the release notes (unless they prefer to remain anonymous).

## Supported Versions

| Version | Supported |
|:--------|:----------|
| 1.0.x   | Yes       |
