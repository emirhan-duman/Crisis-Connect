import Foundation
import CryptoKit

enum SignalPeerBinding {
    private static let lock = NSRecursiveLock()
    private static func scope(_ owner: String, _ peer: String, _ p256: String) -> String {
        "signal-peer-binding-v1-" + SHA256.hash(data: Data([owner, peer, p256].map { "\($0.utf8.count):\($0)" }.joined().utf8))
            .map { String(format: "%02x", $0) }.joined()
    }
    static func read(owner: String, peer: String, p256: String) -> String? {
        (try? KeychainStore.loadStrict(scope(owner, peer, p256))).flatMap { String(data: $0, encoding: .utf8) }
    }
    static func changed(owner: String, peer: String, p256: String) -> Bool {
        do {
            _ = try KeychainStore.loadStrict(scope(owner, peer, p256))
            return try KeychainStore.loadStrict(scope(owner, peer, p256) + "-changed") != nil
        } catch { return true }
    }
    static func migrationPending(owner: String, peer: String, p256: String) -> Bool {
        (try? KeychainStore.loadStrict(scope(owner, peer, p256) + "-legacy")) != nil
    }
    @discardableResult static func markMigrationPending(owner: String, peer: String, p256: String) -> Bool {
        do { try KeychainStore.saveStrict(Data([1]), key: scope(owner, peer, p256) + "-legacy"); return true }
        catch { return false }
    }
    static func accept(owner: String, peer: String, p256: String, signal: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !owner.isEmpty, !peer.isEmpty, !p256.isEmpty, let bytes = Data(base64Encoded: signal), bytes.count == 33 else { return false }
        let id = scope(owner, peer, p256), canonical = bytes.base64EncodedString()
        if let previous = read(owner: owner, peer: peer, p256: p256), previous != canonical {
            try? KeychainStore.saveStrict(Data([1]), key: id + "-changed"); return false
        }
        guard !changed(owner: owner, peer: peer, p256: p256) else { return false }
        do { try KeychainStore.saveStrict(Data(canonical.utf8), key: id) } catch { return false }
        KeychainStore.delete(id + "-legacy")
        return read(owner: owner, peer: peer, p256: p256) == canonical
    }
    static func reset(owner: String, peer: String, p256: String) {
        lock.lock(); defer { lock.unlock() }
        let id = scope(owner, peer, p256)
        for key in [id, id + "-changed", id + "-sent", id + "-legacy"] { KeychainStore.delete(key) }
    }
    static func claimAnnouncement(owner: String, peer: String, p256: String, now: TimeInterval) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let id = scope(owner, peer, p256) + "-sent"
        guard !changed(owner: owner, peer: peer, p256: p256) else { return false }
        let previous = (try? KeychainStore.loadStrict(id)).flatMap { String(data: $0, encoding: .utf8) }.flatMap(Double.init) ?? 0
        guard now - previous >= 60 else { return false }
        do { try KeychainStore.saveStrict(Data(String(now).utf8), key: id); return true } catch { return false }
    }
}

extension Notification.Name {
    static let signalProtocolMigrationStateChanged = Notification.Name("signalProtocolMigrationStateChanged")
}
