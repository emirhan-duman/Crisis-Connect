//
//  ChildProfileManager.swift
//  Crisis Connect
//
//  Child profile mode — the iOS port of Android's ChildProfileManager. An app-wide flag that can
//  only be turned off with a parent-chosen PIN. The PIN is never stored; only a salted PBKDF2
//  hash lives in the Keychain, and wrong guesses are throttled: after five consecutive failures
//  each further attempt sits behind a growing cool-down.
//
//  Relationship state (all keyed by contact UUID, local-only):
//   - parents:        contacts that CONFIRMED the parent request from their own device — the SOS
//                     recipients while child mode is on
//   - pendingParents: request sent (template 206), not answered yet
//   - children:       contacts whose parent request THIS device accepted (the parent's view)
//   - incomingRequests: received requests awaiting an accept/decline on this device
//

import Combine
import CommonCrypto
import Foundation

enum ChildPinResult: Equatable {
    case success
    case wrongPin(attemptsLeft: Int)
    case lockedOut(remainingSeconds: Int)
}

@MainActor
final class ChildProfileManager: ObservableObject {
    static let shared = ChildProfileManager()

    static let pinMinLength = 4
    static let pinMaxLength = 8

    private enum Keys {
        static let enabled = "childProfile.enabled"
        static let pinHash = "childProfile.pinHash"
        static let pinSalt = "childProfile.pinSalt"
        static let failedAttempts = "childProfile.failedAttempts"
        static let lockoutUntil = "childProfile.lockoutUntil"
        static let parents = "childProfile.parents"
        static let pendingParents = "childProfile.pendingParents"
        static let children = "childProfile.children"
        static let incomingRequests = "childProfile.incomingRequests"
    }

    private static let pbkdf2Iterations: UInt32 = 120_000
    private static let freeAttempts = 5
    private static let lockoutStepSeconds = 30

    @Published private(set) var isEnabled: Bool
    @Published private(set) var parents: Set<UUID>
    @Published private(set) var pendingParents: Set<UUID>
    @Published private(set) var children: Set<UUID>
    @Published private(set) var incomingRequests: Set<UUID>

    private init() {
        isEnabled = KeychainStore.loadString(Keys.enabled) == "true"
        parents = Self.readSet(Keys.parents)
        pendingParents = Self.readSet(Keys.pendingParents)
        children = Self.readSet(Keys.children)
        incomingRequests = Self.readSet(Keys.incomingRequests)
    }

    static func isValidPin(_ pin: String) -> Bool {
        pin.count >= pinMinLength && pin.count <= pinMaxLength && pin.allSatisfy(\.isNumber)
    }

    // MARK: - Mode + PIN

    /// Turns the mode on with a freshly chosen PIN.
    func enable(pin: String) -> Bool {
        guard Self.isValidPin(pin) else { return false }
        var salt = Data(count: 16)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard let hash = Self.hashPin(pin, salt: salt) else { return false }
        KeychainStore.saveString(salt.base64EncodedString(), key: Keys.pinSalt)
        KeychainStore.saveString(hash.base64EncodedString(), key: Keys.pinHash)
        KeychainStore.delete(Keys.failedAttempts)
        KeychainStore.delete(Keys.lockoutUntil)
        KeychainStore.saveString("true", key: Keys.enabled)
        isEnabled = true
        return true
    }

    /// Turns the mode off if `pin` matches; wrong guesses count toward the lockout.
    /// The parents list intentionally survives, so re-enabling doesn't rebuild it.
    func disable(pin: String) -> ChildPinResult {
        let result = verifyPin(pin)
        if result == .success {
            KeychainStore.delete(Keys.enabled)
            KeychainStore.delete(Keys.pinHash)
            KeychainStore.delete(Keys.pinSalt)
            KeychainStore.delete(Keys.failedAttempts)
            KeychainStore.delete(Keys.lockoutUntil)
            isEnabled = false
        }
        return result
    }

    /// Checks `pin` without changing the mode — gates parent-list edits while the mode is on.
    /// Wrong guesses share the disable lockout.
    func verifyPin(_ pin: String) -> ChildPinResult {
        let lockedFor = remainingLockoutSeconds()
        if lockedFor > 0 { return .lockedOut(remainingSeconds: lockedFor) }

        guard let saltB64 = KeychainStore.loadString(Keys.pinSalt),
              let expectedB64 = KeychainStore.loadString(Keys.pinHash),
              let salt = Data(base64Encoded: saltB64),
              let expected = Data(base64Encoded: expectedB64) else {
            // Storage lost or tampered with; failing open would defeat the lock.
            return registerFailure()
        }
        guard let actual = Self.hashPin(pin, salt: salt),
              actual.count == expected.count,
              actual.withUnsafeBytes({ a in
                  expected.withUnsafeBytes { b in
                      timingsafe_bcmp(a.baseAddress, b.baseAddress, actual.count) == 0
                  }
              }) else {
            return registerFailure()
        }
        KeychainStore.delete(Keys.failedAttempts)
        KeychainStore.delete(Keys.lockoutUntil)
        return .success
    }

    // MARK: - Relationships

    /// Marks a parent request as sent; the contact becomes a parent only on `confirmParent`.
    func addPendingParent(_ contactId: UUID) {
        guard !parents.contains(contactId) else { return }
        pendingParents.insert(contactId)
        persist(Keys.pendingParents, pendingParents)
    }

    func removePendingParent(_ contactId: UUID) {
        pendingParents.remove(contactId)
        persist(Keys.pendingParents, pendingParents)
    }

    /// Promotes a pending request to a confirmed parent (the peer accepted). Returns false when
    /// there was no pending request — an unsolicited "accept" changes nothing, and a duplicate
    /// delivery of the same response is a no-op.
    @discardableResult
    func confirmParent(_ contactId: UUID) -> Bool {
        guard pendingParents.contains(contactId) else { return false }
        pendingParents.remove(contactId)
        parents.insert(contactId)
        persist(Keys.pendingParents, pendingParents)
        persist(Keys.parents, parents)
        return true
    }

    func removeParent(_ contactId: UUID) {
        parents.remove(contactId)
        persist(Keys.parents, parents)
    }

    /// A parent request arrived from `contactId`; it waits for an accept/decline on this device.
    func addIncomingRequest(_ contactId: UUID) {
        guard !children.contains(contactId) else { return }
        incomingRequests.insert(contactId)
        persist(Keys.incomingRequests, incomingRequests)
    }

    /// Records our answer to an incoming request; accept makes the contact our child.
    func resolveIncomingRequest(_ contactId: UUID, accepted: Bool) {
        incomingRequests.remove(contactId)
        persist(Keys.incomingRequests, incomingRequests)
        if accepted {
            children.insert(contactId)
            persist(Keys.children, children)
        }
    }

    func removeChild(_ contactId: UUID) {
        children.remove(contactId)
        persist(Keys.children, children)
    }

    // MARK: - Internals

    private func persist(_ key: String, _ set: Set<UUID>) {
        let joined = set.map(\.uuidString).sorted().joined(separator: "\n")
        if joined.isEmpty {
            KeychainStore.delete(key)
        } else {
            KeychainStore.saveString(joined, key: key)
        }
    }

    private static func readSet(_ key: String) -> Set<UUID> {
        guard let raw = KeychainStore.loadString(key) else { return [] }
        return Set(raw.split(separator: "\n").compactMap { UUID(uuidString: String($0)) })
    }

    private func registerFailure() -> ChildPinResult {
        let failures = (Int(KeychainStore.loadString(Keys.failedAttempts) ?? "0") ?? 0) + 1
        KeychainStore.saveString(String(failures), key: Keys.failedAttempts)
        if failures >= Self.freeAttempts {
            // Every failure past the free attempts lengthens the cool-down.
            let lockoutSeconds = Self.lockoutStepSeconds * (failures - Self.freeAttempts + 1)
            let until = Date().addingTimeInterval(TimeInterval(lockoutSeconds))
            KeychainStore.saveString(String(until.timeIntervalSince1970), key: Keys.lockoutUntil)
            return .lockedOut(remainingSeconds: lockoutSeconds)
        }
        return .wrongPin(attemptsLeft: Self.freeAttempts - failures)
    }

    private func remainingLockoutSeconds() -> Int {
        guard let raw = KeychainStore.loadString(Keys.lockoutUntil),
              let until = Double(raw) else { return 0 }
        let remaining = until - Date().timeIntervalSince1970
        // Round up so we never show "0 seconds" while still locked.
        return remaining > 0 ? Int(remaining.rounded(.up)) : 0
    }

    private static func hashPin(_ pin: String, salt: Data) -> Data? {
        var derived = Data(count: 32)
        let status = derived.withUnsafeMutableBytes { derivedBytes in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pin, pin.utf8.count,
                    saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    pbkdf2Iterations,
                    derivedBytes.bindMemory(to: UInt8.self).baseAddress, 32
                )
            }
        }
        return status == kCCSuccess ? derived : nil
    }
}
