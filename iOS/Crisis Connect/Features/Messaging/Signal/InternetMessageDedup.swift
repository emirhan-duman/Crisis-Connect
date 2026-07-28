//
//  InternetMessageDedup.swift
//  Crisis Connect
//
//  FS-6: de-dupes the push and Firestore-listener copies of the same message BEFORE they reach the
//  ratchet — iOS port of Android's `InternetMessageDedup`. v1 decryption is idempotent (the
//  duplicate is caught later by the stored-message check), but a v3 Signal message key is ONE-TIME:
//  decrypting the same ciphertext twice throws. The two delivery paths land within a few hundred
//  ms of each other, so the loser must skip decryption entirely. Bounded, in-memory only — the
//  durable dedup remains the Signal session store (v3) and the message store (v1/v2), which cover
//  process restarts.
//

import Foundation

final class InternetMessageDedup {
    static let shared = InternetMessageDedup()

    private let lock = NSLock()
    private var order: [String] = []
    private var claimed = Set<String>()
    private static let maxTracked = 512

    private init() {}

    /// Atomically claim `messageId`; true = this caller owns it (proceed), false = a racing copy
    /// already has it.
    func claim(_ messageId: String) -> Bool {
        guard !messageId.isEmpty else { return true }
        lock.lock(); defer { lock.unlock() }
        if claimed.contains(messageId) { return false }
        claimed.insert(messageId)
        order.append(messageId)
        if order.count > Self.maxTracked {
            claimed.remove(order.removeFirst())
        }
        return true
    }

    /// Release a claim so a genuine later re-delivery can retry (used only on a real failure).
    func release(_ messageId: String) {
        lock.lock(); defer { lock.unlock() }
        if let index = order.firstIndex(of: messageId) { order.remove(at: index) }
        claimed.remove(messageId)
    }
}
