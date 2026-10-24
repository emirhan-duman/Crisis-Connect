import Foundation

/// A durable authenticated identity, rather than a disposable ratchet, pins the protocol.
enum SignalMigrationPolicy {
    static func permitsApplication(isSignal: Bool, hasAuthenticatedBinding: Bool, identityChanged: Bool) -> Bool {
        !identityChanged && (isSignal || !hasAuthenticatedBinding)
    }
    static func permitsBindingControl(requestReply: Bool, alreadyBound: Bool, cohortEnabled: Bool) -> Bool {
        alreadyBound || (requestReply && cohortEnabled)
    }
}
