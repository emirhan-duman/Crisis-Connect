# Security Policy

## Reporting a Vulnerability

Crisis Connect takes security seriously. The application handles encrypted communications and sensitive user data in disaster scenarios, making security a top priority.

If you discover a security vulnerability, **please do not open a public issue.**

### How to Report

1. Email your findings to the security contact listed on [crisisconnect.network](https://crisisconnect.network)
2. Include a detailed description of the vulnerability
3. Provide steps to reproduce if possible
4. Allow reasonable time for a fix before public disclosure

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

### Recognition

Security researchers who responsibly disclose vulnerabilities will be credited in the release notes (unless they prefer to remain anonymous).

## Supported Versions

| Version | Supported |
|:--------|:----------|
| 1.0.x   | Yes       |
