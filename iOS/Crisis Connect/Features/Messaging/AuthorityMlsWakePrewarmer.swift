import Foundation
import UIKit

enum AuthorityMlsDeferredApplicationError: Error {
    case openInChat
}

/**
 * Bounded background bootstrap for one authenticated MLS parent. It publishes only this device's
 * public MLS record and processes control/Welcome events. Application messages remain in the
 * durable MLS inbox until the real thread can persist them into its UI store.
 */
actor AuthorityMlsWakePrewarmer {
    static let shared = AuthorityMlsWakePrewarmer()

    private var inFlight = Set<String>()

    func prepare(
        selfUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType,
        channelId: String
    ) async -> Bool {
        guard !selfUid.isEmpty, !peerUid.isEmpty, selfUid != peerUid, !channelId.isEmpty else { return false }
        let key = "\(scopeType.rawValue)\u{0}\(channelId)\u{0}\(peerUid)"
        guard inFlight.insert(key).inserted else { return true }
        defer { inFlight.remove(key) }

        do {
            let channel = try await AuthorityMlsChatChannel.prepare(
                selfUid: selfUid,
                peerUid: peerUid,
                scopeType: scopeType,
                channelId: channelId,
                deviceLabel: String("iPhone \(UIDevice.current.model)".prefix(64))
            )
            defer { Task { await channel.close() } }
            for attempt in 0..<20 {
                let preparation = try await channel.refreshPreparation()
                if preparation.ready {
                    try await channel.activate(
                        onMessage: { _ in throw AuthorityMlsDeferredApplicationError.openInChat },
                        onSecurityError: { error in
                            if !(error is AuthorityMlsDeferredApplicationError) {
                                NSLog("AuthorityMlsWakePrewarm transport failed: %@", String(describing: error))
                            }
                        }
                    )
                    if try await channel.isReadyToSend() { return true }
                }
                let delay: UInt64 = attempt < 10 ? 400_000_000 : 1_500_000_000
                try await Task.sleep(nanoseconds: delay)
            }
        } catch is AuthorityMlsDeferredApplicationError {
            // The authenticated application is intentionally still durable for the real chat.
            return true
        } catch {
            NSLog("AuthorityMlsWakePrewarm failed: %@", String(describing: error))
        }
        return false
    }
}
