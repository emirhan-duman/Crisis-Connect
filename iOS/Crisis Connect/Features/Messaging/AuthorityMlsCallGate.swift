import Foundation

/// Requires the exact already-approved AuthorityChat device sets before an authority call may ring.
enum AuthorityMlsCallGate {
    static func isVerified(
        selfUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType,
        channelId: String
    ) async -> Bool {
        do {
            let canonical = try AuthorityMlsIdentifiers.canonicalBinding(AuthorityMlsBinding(
                scopeType: scopeType,
                channelId: channelId,
                participants: [selfUid, peerUid]
            ))
            let conversationId = try AuthorityMlsIdentifiers.conversationId(canonical)
            let directory = try await AuthorityMlsTransport().loadDeviceDirectory(conversationId: conversationId)
            guard directory.rejected == 0 else { return false }
            let grouped = Dictionary(grouping: directory.records, by: \.uid)
            guard Set(grouped.keys) == Set(canonical.participants) else { return false }
            let trust = AuthorityMlsTrustStore()
            return try canonical.participants.allSatisfy { uid in
                try trust.verifyExisting(
                    conversationId: conversationId,
                    uid: uid,
                    devices: grouped[uid] ?? []
                ).approved
            }
        } catch {
            return false
        }
    }
}
