import Foundation

enum ChatBadgeTransport { case bluetooth, internet }

enum ChatBadgePhase { case idle, connecting, bluetoothConnected, internetAvailable, disconnected, failed }

/// A single presentation value. A usable local internet path is not a verified server/peer session.
struct ChatConnectionBadgeSnapshot: Equatable {
    let phase: ChatBadgePhase
    var transport: ChatBadgeTransport { phase == .internetAvailable ? .internet : .bluetooth }
    var bluetoothReady: Bool { phase == .bluetoothConnected }

    static func resolve(bluetoothReady: Bool, internetAvailable: Bool,
                        bluetoothConnecting: Bool, bluetoothFailed: Bool, enabled: Bool) -> Self {
        if bluetoothReady { return Self(phase: .bluetoothConnected) }
        if internetAvailable { return Self(phase: .internetAvailable) }
        if bluetoothConnecting { return Self(phase: .connecting) }
        if bluetoothFailed { return Self(phase: .failed) }
        return Self(phase: enabled ? .disconnected : .idle)
    }
}
