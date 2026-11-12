//
//  NearbyPairingSupport.swift
//  Crisis Connect
//
//  Bridges the transport-agnostic SPAKE2 session ([NearbySpakePairing]) to the app: our identity
//  (Firebase uid + messaging public key + name), our own phone number (SPAKE2 password), and saving
//  the paired contact. iOS port of the Android `NearbyPairingSupport`.
//
//  The session code is the deterministic pair id shared with internet messaging when both uids are
//  known (matching Android), unifying the thread across transports; the SHA256-of-key form remains
//  the fallback for identity-less pairings.
//

import Foundation
import CryptoKit
import FirebaseAuth

enum NearbyPairingSupport {

    /// Our identity to reveal to a peer once the SPAKE2 handshake authenticates the number.
    static func localIdentity() -> NearbyIdentity {
        let uid = Auth.auth().currentUser?.uid ?? ""
        let publicKey = (try? MessagingIdentity.shared.publicKeyBase64()) ?? ""
        let name = Auth.auth().currentUser?.displayName ?? ""
        return NearbyIdentity(uid: uid, publicKeyBase64: publicKey, displayName: name)
    }

    /// Our own verified phone number in E.164, or nil if not signed in with a phone.
    /// The proven number is ALSO stored locally at OTP time (recordVerifiedOwnPhone): on the
    /// "linked" outcome the server attaches the number server-side, and Auth's cached user object
    /// doesn't reflect phoneNumber until the next token refresh — without the fallback, the SPAKE2
    /// responder and the discoverability default would both stay dead for the rest of the session
    /// right after the user verified. Auth wins whenever it has a value.
    private static let verifiedPhoneKey = "nearby.ownPhone.verified"

    static func ownPhoneE164() -> String? {
        if let phone = Auth.auth().currentUser?.phoneNumber?.trimmingCharacters(in: .whitespacesAndNewlines),
           phone.hasPrefix("+") {
            return phone
        }
        if let stored = UserDefaults.standard.string(forKey: verifiedPhoneKey),
           stored.hasPrefix("+") {
            return stored
        }
        return nil
    }

    /// Called from both OTP success paths (onboarding + profile) with the number just proven.
    static func recordVerifiedOwnPhone(_ phone: String) {
        let trimmed = phone.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("+") else { return }
        UserDefaults.standard.set(trimmed, forKey: verifiedPhoneKey)
    }

    /// Persists a contact for a peer we just paired with over SPAKE2. Returns the session code.
    @discardableResult
    static func savePairedContact(peer: NearbyIdentity, contactKey: Data, bluetoothAddress: String?) throws -> String {
        // A phone number is not an account credential. Keep this local, unverified pairing
        // separate from every account conversation; a QR exchange binds the account identity.
        let peerUid = peer.uid.trimmingCharacters(in: .whitespacesAndNewlines)
        let sessionCode = localSessionCode(contactKey: contactKey)
        guard ContactStore.shared.contact(for: sessionCode) == nil,
              peerUid.isEmpty || ContactStore.shared.contactForPeerUid(peerUid) == nil else {
            throw NearbyPairingError.confirmationFailed
        }
        _ = ContactStore.shared.upsertBleContact(
            name: peer.displayName.isEmpty ? (bluetoothAddress ?? "") : peer.displayName,
            sessionCode: sessionCode,
            aesKeyBase64: contactKey.base64EncodedString(),
            remoteSessionCode: nil,
            remotePlatform: .unknown,
            bleShareId: nil,
            lastKnownBleAddress: bluetoothAddress,
            remoteDeviceId: nil,
            peerUid: nil,
            peerPublicKey: nil,
            analyticsSource: "nearby"
        )
        return sessionCode
    }

    static func localSessionCode(contactKey: Data) -> String {
        "n" + base64Url(Data(SHA256.hash(data: contactKey))).prefix(16)
    }

    private static func base64Url(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
