# Crisis Connect — Offline-First Disaster Communication Platform

**We are connecting lives when it matters most.**

Crisis Connect is an offline-first, secure communication platform designed for disaster and emergency scenarios where conventional communication infrastructure becomes unavailable. It enables device-to-device messaging, local peer discovery, and rescue coordination using Bluetooth networking and short-range mesh connectivity.

---

## Key Features

- Offline messaging without cellular or internet infrastructure  
- Peer discovery and data exchange via Bluetooth  
- Secure, authenticated, and encrypted communication flows  
- Modular rescue and field-operation tools  
- Cross-platform architecture (Android and iOS under one repository)  
- Full transparency through open-source code for public verification  

---

## Repository Structure

Crisis-Connect/
  android/    → Android implementation (Kotlin, Jetpack Compose)
  ios/        → iOS implementation (Swift / SwiftUI)
  docs/       → Architecture, protocol specifications, and technical documentation
  designs/    → UI/UX resources and design assets
  LICENSE
  README.md
  
## Security and Privacy
Crisis Connect is designed for reliability and trust in high-risk, safety-critical environments.

- End-to-end encrypted communication where applicable

- Publicly auditable networking and cryptographic logic

- Strong copyleft licensing (AGPL-3.0) to maintain long-term transparency

Independent review from the security community is encouraged.

## Connectivity Architecture

### Bluetooth Classic (RFCOMM)
- Stable and low-latency data channels

- Authenticated session handshake

- Reliable message transfer under constrained conditions

### Bluetooth Low Energy (GATT)
- Energy-efficient peer discovery

- Advertisement-based presence detection

- Flexible scanning and connection management

Detailed technical specifications will be available under the docs/ directory.

## Platform Implementations
### Android
- Kotlin and Jetpack Compose

- Foreground service communication layer

- BLE + Classic hybrid networking stack

- Encrypted local message storage

- Modular, maintainable, and testable architecture

### iOS (Currently in development)
- Swift / SwiftUI

- Multipeer Connectivity and Bluetooth abstractions

- Offline-first local storage and synchronization

## Roadmap

- Cross-platform cryptographic key exchange

- iOS beta release

- Web-based operations dashboard

- Municipality and agency integration

## Contributing
Contributions are welcome. Developers may submit issues, propose improvements, or open pull requests for discussion.

## License
This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
Any modified versions, derivative works, or network-accessible services must remain open source under the same license to ensure long-term transparency and trust.

Transparency Statement
Crisis Connect is built for environments where communication must remain reliable and verifiable.
All components—including networking logic, cryptographic operations, and offline-first mechanisms—are fully open for public inspection. This ensures accountability, operational reliability, and transparent behavior during critical moments.
