# Contributing to Crisis Connect

Thank you for your interest in contributing to Crisis Connect. This document provides guidelines and information for contributors.

## Table of Contents

- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Code Style](#code-style)
- [Testing](#testing)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Issue Guidelines](#issue-guidelines)
- [Security](#security)
- [License](#license)

## Getting Started

1. **Fork** the repository on GitHub
2. **Clone** your fork locally
3. **Create** a feature branch from `main`
4. **Make** your changes
5. **Test** your changes
6. **Push** to your fork and submit a Pull Request

## Development Setup

### Android

**Requirements:** Android Studio Ladybug+, JDK 17, Android SDK 36

```bash
git clone https://github.com/<your-username>/Crisis-Connect.git
cd Crisis-Connect/Android

cp app/google-services.json.example app/google-services.json
cp local.properties.example local.properties
cp keystore.properties.example keystore.properties
```

1. Replace `app/google-services.json` with your Firebase config (download from [Firebase Console](https://console.firebase.google.com))
2. Fill in `local.properties` with your API keys
3. Open in Android Studio, sync Gradle, and build

> BLE features require a physical device. Use an emulator for UI-only changes.

### iOS

**Requirements:** Xcode 16+, iOS 17+ deployment target

```bash
cd Crisis-Connect/iOS

cp "Crisis Connect/GoogleService-Info.plist.example" "Crisis Connect/GoogleService-Info.plist"
```

1. Replace `GoogleService-Info.plist` with your Firebase config
2. Update URL schemes in `Config/Info.plist`
3. Open `Crisis Connect.xcodeproj` in Xcode, resolve SPM dependencies, and build

### Firebase Backend

Only needed if you're modifying Cloud Functions or Firestore rules:

```bash
npm install -g firebase-tools
firebase login
cd Android/functions && npm install
```

## Making Changes

### Branch Naming

Use descriptive branch names:

- `feature/group-mesh-chat` -- New features
- `fix/ble-connection-timeout` -- Bug fixes
- `docs/protocol-specification` -- Documentation
- `test/voice-pipeline-coverage` -- Test improvements
- `i18n/french-localization` -- Translations

### Commit Messages

Write clear, descriptive commit messages:

```
Add jitter buffer adaptive sizing for voice calls

The fixed 100ms buffer caused audible gaps on slower BLE connections.
This implements an adaptive buffer that scales between 60-200ms based
on measured packet inter-arrival variance.
```

- Use the imperative mood ("Add feature" not "Added feature")
- First line: concise summary (under 72 characters)
- Body: explain *what* and *why*, not *how*

## Code Style

### Android (Kotlin)

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use the existing code style in the project as reference
- Run `./gradlew :app:lintDebug` before submitting

### iOS (Swift)

- Follow [Swift API Design Guidelines](https://www.swift.org/documentation/api-design-guidelines/)
- Match the existing project conventions
- Use SwiftUI for new UI components

### General

- No hardcoded secrets, API keys, or credentials
- Keep functions focused and small
- Add comments only where the logic isn't self-evident
- Use meaningful variable and function names

## Testing

### Before Submitting

Ensure all existing tests pass:

```bash
# Android
cd Android
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug

# iOS
cd iOS
xcodebuild test -scheme "Crisis Connect" -destination "platform=iOS Simulator,name=iPhone 16"
```

### Writing Tests

- Add tests for new functionality
- Place unit tests alongside the code they test
- Use descriptive test names that explain the scenario
- Test edge cases, not just the happy path

### What to Test

| Area | Priority |
|:-----|:---------|
| Crypto operations | Critical |
| BLE message framing | Critical |
| Certificate verification | Critical |
| ViewModel logic | High |
| Data layer / repositories | High |
| UI components | Medium |
| Utility functions | Medium |

## Submitting a Pull Request

1. **Update your branch** with the latest `main` before submitting
2. **Fill out the PR template** completely
3. **Link related issues** if applicable
4. **Keep PRs focused** -- one feature or fix per PR
5. **Include screenshots** for UI changes
6. **Describe testing** -- what did you test and on what device?

### Review Process

- All PRs require review before merging
- Respond to review feedback promptly
- Keep the conversation constructive

## Issue Guidelines

### Bug Reports

Use the **Bug Report** issue template. Include:

- App version, device model, and OS version
- Steps to reproduce
- Expected vs. actual behavior
- Screenshots or logs if applicable

### Feature Requests

Use the **Feature Request** issue template. Include:

- The problem you're trying to solve
- Your proposed solution
- Any alternatives you've considered

## Security

If you discover a security vulnerability, **do not open a public issue.** See [SECURITY.md](./SECURITY.md) for responsible disclosure instructions.

## Contribution Areas

We especially welcome contributions in these areas:

| Area | Description | Difficulty |
|:-----|:------------|:-----------|
| Security auditing | Review crypto, key management, certificate verification | Advanced |
| BLE protocol | Connection stability, MTU negotiation, edge cases | Advanced |
| Mesh improvements | Routing efficiency, relay reliability | Advanced |
| Localization | Translate UI and survival guide to new languages | Beginner |
| Accessibility | Screen reader support, dynamic type, contrast | Intermediate |
| Test coverage | Unit and integration tests for uncovered paths | Intermediate |
| Documentation | Protocol specs, architecture docs | Intermediate |

## License

By contributing to Crisis Connect, you agree that your contributions will be licensed under the [AGPL-3.0 License](./LICENSE).
