//
//  VerifySecurityCodeSheet.swift
//  Crisis Connect
//
//  Created by Assistant on 08.11.2026.
//

import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

struct VerifySecurityCodeSheet: View {
    let contact: ContactRecord
    @Environment(\.dismiss) private var dismiss
    @State private var safetyNumber: String? = nil
    @State private var qrImage: UIImage? = nil
    @State private var isVerified: Bool = false
    @State private var copyFeedback: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // Contact header & Shield
                    VStack(spacing: 8) {
                        ZStack {
                            Circle()
                                .fill(isVerified ? Color.appPrimary.opacity(0.18) : Color.secondary.opacity(0.12))
                                .frame(width: 64, height: 64)
                            Image(systemName: isVerified ? "checkmark.shield.fill" : "shield.fill")
                                .font(.system(size: 32, weight: .bold))
                                .foregroundStyle(isVerified ? Color.appPrimary : .secondary)
                        }

                        Text(contact.name)
                            .font(.title3.weight(.bold))

                        Text(isVerified ? NSLocalizedString("SAFETY_NUMBER_VERIFIED_BADGE", comment: "") : NSLocalizedString("SAFETY_NUMBER_TITLE_NAV", comment: ""))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(isVerified ? Color.appPrimary : .secondary)
                    }

                    // QR Code
                    if let qrImage {
                        VStack(spacing: 8) {
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 180, height: 180)
                                .padding(12)
                                .background(Color.white)
                                .cornerRadius(16)
                                .shadow(color: Color.black.opacity(0.08), radius: 4, y: 2)

                            Text(NSLocalizedString("SAFETY_NUMBER_EXPLAIN", comment: ""))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 20)
                        }
                    } else {
                        ProgressView()
                            .frame(height: 180)
                    }

                    // 60-digit number card
                    if let safetyNumber {
                        VStack(spacing: 12) {
                            Text(safetyNumber)
                                .font(.system(.body, design: .monospaced).weight(.semibold))
                                .multilineTextAlignment(.center)
                                .lineSpacing(6)
                                .padding(16)
                                .frame(maxWidth: .infinity)
                                .background(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .fill(Color.appSurfaceElevated)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .stroke(Color.secondary.opacity(0.2), lineWidth: 1)
                                )

                            Button(action: copyNumber) {
                                HStack(spacing: 6) {
                                    Image(systemName: copyFeedback ? "checkmark" : "doc.on.doc")
                                    Text(copyFeedback ? NSLocalizedString("SAFETY_NUMBER_COPY_SUCCESS", comment: "") : NSLocalizedString("CHAT_ACTION_COPY", comment: ""))
                                }
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color.appPrimary)
                                .padding(.vertical, 8)
                                .padding(.horizontal, 16)
                                .background(Color.appPrimary.opacity(0.1))
                                .clipShape(Capsule())
                            }
                        }
                        .padding(.horizontal, 8)
                    }

                    Divider().padding(.horizontal, 12)

                    // Mark as verified button
                    Button(action: toggleVerified) {
                        HStack(spacing: 8) {
                            Image(systemName: isVerified ? "checkmark.circle.fill" : "checkmark.seal")
                            Text(isVerified ? NSLocalizedString("SAFETY_NUMBER_VERIFIED_BADGE", comment: "") : NSLocalizedString("SAFETY_NUMBER_MARK_VERIFIED", comment: ""))
                        }
                        .font(.headline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(isVerified ? Color.appPrimary.opacity(0.15) : Color.appPrimary)
                        .foregroundStyle(isVerified ? Color.appPrimary : .white)
                        .cornerRadius(14)
                    }
                    .padding(.horizontal, 8)
                }
                .padding(20)
            }
            .background(Color.appBackground.ignoresSafeArea())
            .navigationTitle(NSLocalizedString("SAFETY_NUMBER_TITLE_NAV", comment: ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .onAppear {
                isVerified = contact.isVerified
                computeSafetyNumber()
            }
        }
    }

    private func copyNumber() {
        guard let safetyNumber else { return }
        UIPasteboard.general.string = safetyNumber
        copyFeedback = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            copyFeedback = false
        }
    }

    private func toggleVerified() {
        isVerified.toggle()
        if isVerified {
            ContactStore.shared.markVerified(
                sessionId: contact.id,
                verifiedIdentityKey: contact.peerPublicKey ?? contact.verifiedIdentityKey
            )
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
    }

    private func computeSafetyNumber() {
        guard let myUid = InternetMessagingClient().currentUid,
              let peerUid = contact.peerUid?.trimmingCharacters(in: .whitespacesAndNewlines),
              !peerUid.isEmpty else {
            // Fallback for Bluetooth-only contact using session codes
            let fallbackCode = SafetyNumber.fingerprint(publicKeyB64: contact.sessionCode, uid: contact.name)
            self.safetyNumber = formatInGroups(fallbackCode)
            self.qrImage = generateQRCode(from: self.safetyNumber ?? fallbackCode)
            return
        }

        let peerKey = contact.peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        Task.detached(priority: .userInitiated) {
            if let v3 = SignalSessionGate.shared.safetyNumber(localUid: myUid, peerUid: peerUid) {
                let qr = generateQRCode(from: v3)
                await MainActor.run {
                    self.safetyNumber = v3
                    self.qrImage = qr
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
            let qr = generateQRCode(from: v2)
            await MainActor.run {
                self.safetyNumber = v2
                self.qrImage = qr
            }
        }
    }

    private func formatInGroups(_ text: String) -> String {
        var groups: [String] = []
        var index = text.startIndex
        while index < text.endIndex {
            let end = text.index(index, offsetBy: 5, limitedBy: text.endIndex) ?? text.endIndex
            groups.append(String(text[index..<end]))
            index = end
        }
        return groups.joined(separator: " ")
    }
}

private func generateQRCode(from string: String) -> UIImage? {
    let context = CIContext()
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(string.utf8)
    filter.correctionLevel = "M"

    guard let outputImage = filter.outputImage else { return nil }
    let scaled = outputImage.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
    guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
    return UIImage(cgImage: cgImage)
}
