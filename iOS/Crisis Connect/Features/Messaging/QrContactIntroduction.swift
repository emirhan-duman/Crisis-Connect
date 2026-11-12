import Foundation
import CryptoKit

/// Byte-compatible with Android's QR introduction. The capability authorizes an add,
/// while the relay envelope independently authenticates the sender's account/key.
enum QrContactIntroduction {
    static func resolve(
        sender: String, owner: String, publicKey: String, payload: [String: Any]?,
        existing: ContactRecord?, activeQrKeyBase64: String?, fallbackName: String,
        knownPeerIsChild: Bool = false, knownPeerPhotoURL: String? = nil,
        now: Date = Date()
    ) -> ContactRecord? {
        let code = (payload?["rsc"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let proof = payload?["binding"] as? String ?? ""
        guard !sender.isEmpty, sender != owner, !publicKey.isEmpty, !code.isEmpty, code.utf16.count <= 128 else { return nil }
        if let existing {
            if let pinned = existing.peerUid, !pinned.isEmpty, pinned != sender { return nil }
            if let pinned = existing.peerPublicKey, !pinned.isEmpty, pinned != publicKey { return nil }
        }
        // A new live QR must never rebind an unrelated old BLE identity by session-code collision.
        let pairedKey = existing.flatMap { $0.offlineKeyVersion >= 2 && !$0.aesKeyBase64.isEmpty ? $0.aesKeyBase64 : nil }
        let candidateKey = pairedKey ?? activeQrKeyBase64
        let key = candidateKey.flatMap { Data(base64Encoded: $0) }
        let proven = key.map { $0.count == 32 && ContactIdentityBinding.verify(
            proof: proof, key: SymmetricKey(data: $0), uid: sender, sessionCode: code, publicKey: publicKey
        ) } ?? false
        if !proven {
            return existing.flatMap { $0.peerUid == sender && $0.peerPublicKey == publicKey ? $0 : nil }
        }
        let resolvedAesKey = candidateKey ?? ""
        if var existing {
            existing.peerUid = sender
            existing.peerPublicKey = publicKey
            if existing.remoteSessionCode?.isEmpty != false { existing.remoteSessionCode = code }
            if existing.aesKeyBase64.isEmpty && !resolvedAesKey.isEmpty {
                existing.aesKeyBase64 = resolvedAesKey
                existing.offlineKeyVersion = 2
            }
            return existing
        }
        let suppliedName = (payload?["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return ContactRecord(
            id: BroadcastSessionId.fromRawIdentifier(sender),
            name: suppliedName.isEmpty ? fallbackName : String(suppliedName.prefix(120)),
            broadcastId: "", sessionCode: InternetConversation.pairId(sender, owner),
            remoteSessionCode: code, aesKeyBase64: resolvedAesKey, offlineKeyVersion: resolvedAesKey.isEmpty ? 1 : 2,
            preferredTransport: .legacyBroadcast, peerUid: sender, peerPublicKey: publicKey,
            peerPhotoUrl: knownPeerPhotoURL, peerIsChild: knownPeerIsChild,
            createdAt: now, lastUpdated: now
        )
    }
}
