import Foundation

/// Keys remain on the phone. The watch selects a saved contact, never supplies relay credentials.
@MainActor enum WatchContactMessaging {
    static func eligible(_ contact: ContactRecord) -> Bool {
        contact.supportsInternet && contact.peerKeyChanged != true && contact.isAuthorityBridge != true
    }

    static func send(_ command: WatchCommand, store: SOSRecoveryStore = .shared,
        lookup: (UUID) -> ContactRecord? = { ContactStore.shared.contact(for: $0) },
        relay: (ContactRecord, String, String, Date) async -> Bool = { contact, id, text, date in
            let transport = InternetChatTransport.shared
            guard transport.isAvailable(allowUltraConstrained: true) else { return false }
            return await transport.sendText(messageId: id, text: text, createdAtMs: Int(date.timeIntervalSince1970 * 1000), contact: contact)
        }
    ) async -> String {
        guard command.isValid(at: Date().timeIntervalSince1970), command.action == .contactReply, let reply = command.reply,
              let peer = command.peerID, let contactID = UUID(uuidString: peer) else { return "invalid" }
        if let previous = store.snapshot.replies.first(where: { $0.id == command.id }) {
            SOSChatStore.shared.reconcileWatchReplies()
            return previous.peer == peer ? previous.result : "stateChanged"
        }
        // Revalidate removal/key changes rather than trusting the watch's cached display roster.
        guard let contact = lookup(contactID), eligible(contact) else { return "noPeer" }
        let text = Locale.current.language.languageCode?.identifier == "tr" ? reply.turkish : reply.english
        do {
            try store.reserve(id: command.id, peer: peer, text: text, kind: "contact")
            if SOSChatStore.shared.session(for: contactID) == nil {
                SOSChatStore.shared.ensureSession(id: contactID, displayName: contact.name, role: .unknown, isVerified: contact.isVerified)
            }
            SOSChatStore.shared.reconcileWatchReplies(store.snapshot.replies)
            let date = store.snapshot.replies.first { $0.id == command.id }?.createdAt ?? .now
            let result = await relay(contact, command.id, text, date) ? "accepted" : "noPeer"
            try store.complete(id: command.id, result: result)
            SOSChatStore.shared.reconcileWatchReplies(store.snapshot.replies)
            return result
        } catch {
            // A reserved entry remains uncertain. No automatic resend is scheduled.
            SOSChatStore.shared.reconcileWatchReplies(store.snapshot.replies)
            return "uncertain"
        }
    }
}
