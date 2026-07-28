//
//  SafetyNumber.swift
//  Crisis Connect
//
//  Signal-style safety number for an internet chat — the iOS port of Android's SafetyNumber,
//  digit-for-digit identical. A 60-digit fingerprint derived from BOTH peers' long-term identity
//  public keys; both sides compute the SAME number, so two people reading it out (or comparing on
//  screen) and matching means no man-in-the-middle swapped a key during the QR/directory exchange.
//  Any mismatch means the keys differ — do not trust the channel.
//

import CryptoKit
import Foundation
import SwiftUI

enum SafetyNumber {
    private static let domain = "crisisconnect:safetynumber:v1"
    private static let digitsPerParty = 30

    /// Returns the shared safety number formatted as 12 space-separated groups of 5 digits.
    static func compute(
        localPublicKeyB64: String,
        remotePublicKeyB64: String,
        localUid: String,
        remoteUid: String
    ) -> String {
        let local = fingerprint(publicKeyB64: localPublicKeyB64, uid: localUid)
        let remote = fingerprint(publicKeyB64: remotePublicKeyB64, uid: remoteUid)
        // Order-independent so both peers derive the same string.
        let ordered = [local, remote].sorted()
        let combined = ordered[0] + ordered[1]
        var groups: [String] = []
        var index = combined.startIndex
        while index < combined.endIndex {
            let end = combined.index(index, offsetBy: 5, limitedBy: combined.endIndex) ?? combined.endIndex
            groups.append(String(combined[index..<end]))
            index = end
        }
        return groups.joined(separator: " ")
    }

    static func fingerprint(publicKeyB64: String, uid: String) -> String {
        let trimmedKey = publicKeyB64.trimmingCharacters(in: .whitespacesAndNewlines)
        let digest = SHA512.hash(data: Data("\(domain):\(uid):\(trimmedKey)".utf8))
        return lastDecimalDigits(of: Array(digest.prefix(16)), count: digitsPerParty)
    }

    /// The unsigned big-endian integer in `bytes`, mod 10^count, zero-padded — matches Android's
    /// `BigInteger(1, bytes).mod(10^30)` without needing a big-integer type: base-256 → base-10
    /// conversion with a digit array, keeping only the low `count` decimal digits.
    private static func lastDecimalDigits(of bytes: [UInt8], count: Int) -> String {
        var digits: [UInt8] = [0] // little-endian decimal digits
        for byte in bytes {
            var carry = Int(byte)
            for i in 0..<digits.count {
                let value = Int(digits[i]) * 256 + carry
                digits[i] = UInt8(value % 10)
                carry = value / 10
            }
            while carry > 0 {
                digits.append(UInt8(carry % 10))
                carry /= 10
            }
        }
        return String(
            (0..<count).reversed().map { i -> Character in
                Character(String(i < digits.count ? digits[i] : 0))
            }
        )
    }
}

/// Contact info screen opened by tapping the chat header — mirrors Android's ChatInfoScreen:
/// a centered profile card (large avatar + name + role line), the session code, and — for
/// internet-identified contacts — the SafetyNumberCard with the shared 60-digit verification
/// number both peers can compare.
struct ChatInfoView: View {
    let sessionId: UUID
    let title: String
    let subtitle: String?
    let initials: String
    let avatarHue: Double
    let avatarImageRelativePath: String?
    let contact: ContactRecord?

    @ObservedObject private var contactStore = ContactStore.shared
    @State private var isRenaming = false
    @State private var renameDraft = ""

    /// Live record so store mutations (key-change acknowledgement) reflect immediately.
    private var liveContact: ContactRecord? {
        contact.flatMap { snapshot in
            contactStore.contacts.first { $0.id == snapshot.id }
        } ?? contact
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                profileCard

                if let sessionCode = liveContact?.sessionCode.trimmingCharacters(in: .whitespacesAndNewlines),
                   !sessionCode.isEmpty {
                    sessionCard(sessionCode: sessionCode)
                }

                if let liveContact, liveContact.supportsInternet {
                    SafetyNumberCardView(contact: liveContact)
                }
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 12)
        }
        .background(Color.appBackground)
        .navigationTitle(LocalizedStringKey("CHAT_INFO_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .alert("CHAT_INFO_RENAME_TITLE", isPresented: $isRenaming) {
            TextField("", text: $renameDraft)
            Button("COMMON_CANCEL", role: .cancel) {}
            Button("COMMON_SAVE") {
                let trimmed = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return }
                if let contactId = liveContact?.id {
                    ContactStore.shared.renameContact(id: contactId, to: trimmed)
                }
                // The chat header + conversation list read the SESSION display name — rename both
                // or the info screen and the chat disagree about who this is.
                SOSChatStore.shared.ensureSession(id: sessionId, displayName: trimmed)
            }
        } message: {
            Text("CHAT_INFO_RENAME_MESSAGE")
        }
    }

    private var profileCard: some View {
        VStack(spacing: 10) {
            ChatAvatarCircleView(
                avatarImageRelativePath: avatarImageRelativePath,
                initials: initials,
                avatarHue: avatarHue,
                size: 90
            )

            // Live name (falls back to the passed-in snapshot) with the rename affordance.
            // There was NO way to fix a typo'd contact name anywhere in the app — a bad name at
            // pairing time was permanent. Android's chat info has always offered this.
            Button {
                renameDraft = liveContact?.name ?? title
                isRenaming = true
            } label: {
                HStack(spacing: 6) {
                    Text((liveContact?.name.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? title)
                        .font(.title3.weight(.bold))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(Color.primary)
                    Image(systemName: "pencil")
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            .buttonStyle(.plain)

            if let subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 20)
        .padding(.vertical, 22)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
    }

    private func sessionCard(sessionCode: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("CHAT_INFO_SESSION_CODE")
                .font(.caption.weight(.medium))
                .foregroundStyle(Color.appTextSecondary)
            Text(sessionCode)
                .font(.system(.subheadline, design: .monospaced))
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.vertical, 18)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
    }
}

/// The safety-number card, styled like Android's SafetyNumberCard: shield + bold title row,
/// the 60-digit monospace number, then the comparison explanation. When the peer's identity key
/// changed since it was pinned, the card flips to the red warning state with an
/// "I verified it" acknowledgement.
private struct SafetyNumberCardView: View {
    let contact: ContactRecord

    @State private var safetyNumber: String?
    // True when the shown number is the forward-secret (v3 Signal) fingerprint — the badge explains
    // that a v2→v3 number change is a security upgrade, not a MITM.
    @State private var forwardSecret = false

    private var keyChanged: Bool { contact.peerKeyChanged == true }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: keyChanged ? "exclamationmark.triangle.fill" : "shield.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(keyChanged ? Color.appDanger : Color.appPrimary)
                Text(keyChanged ? "SAFETY_NUMBER_CHANGED_TITLE" : "SAFETY_NUMBER_TITLE")
                    .font(.headline.weight(.bold))
            }

            if keyChanged {
                Text("SAFETY_NUMBER_CHANGED_DESCRIPTION")
                    .font(.subheadline)
            }

            if let safetyNumber {
                Text(safetyNumber)
                    .font(.system(.callout, design: .monospaced).weight(.medium))
                    .lineSpacing(5)
            } else {
                Text("SAFETY_NUMBER_UNAVAILABLE")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
            }

            if forwardSecret && !keyChanged {
                HStack(spacing: 6) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                    Text("SAFETY_NUMBER_FORWARD_SECRET")
                        .font(.footnote)
                        .foregroundStyle(Color.appPrimary)
                }
            }

            Text("SAFETY_NUMBER_EXPLAIN")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)

            if keyChanged {
                Button {
                    ContactStore.shared.acknowledgePeerKeyChange(contactId: contact.id)
                } label: {
                    Text("SAFETY_NUMBER_VERIFY_ACTION")
                        .font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.appDanger)
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.vertical, 18)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(keyChanged ? Color.appDanger.opacity(0.12) : Color.appSurfaceElevated)
        )
        .onAppear { computeNumber() }
        .onChange(of: contact.peerPublicKey) { _, _ in computeNumber() }
    }

    private func computeNumber() {
        guard let myUid = InternetMessagingClient().currentUid,
              let peerUid = contact.peerUid?.trimmingCharacters(in: .whitespacesAndNewlines),
              !peerUid.isEmpty else {
            return
        }
        let peerKey = contact.peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // Off the main thread: the v3 fingerprint runs 5200 hash iterations + file IO.
        Task.detached(priority: .userInitiated) {
            // Prefer the forward-secret (v3) fingerprint once a Signal identity exists for the
            // peer; fall back to the interim v2 P-256 number. Same policy as Android.
            if let v3 = SignalSessionGate.shared.safetyNumber(localUid: myUid, peerUid: peerUid) {
                await MainActor.run {
                    safetyNumber = v3
                    forwardSecret = true
                }
                return
            }
            guard !peerKey.isEmpty, let myKey = try? MessagingIdentity.shared.publicKeyBase64() else { return }
            let v2 = SafetyNumber.compute(
                localPublicKeyB64: myKey,
                remotePublicKeyB64: peerKey,
                localUid: myUid,
                remoteUid: peerUid
            )
            await MainActor.run {
                safetyNumber = v2
                forwardSecret = false
            }
        }
    }
}
