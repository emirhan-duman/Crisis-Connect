//
//  CertificateProvisioningNotifier.swift
//  Crisis Connect
//
//  Mirrors Android's CertificateProvisioningNotifier: an app-level event bus
//  for automatic rescue-certificate provisioning. The banner host observes
//  `event`; events are transient (a host that mounts after the event fired
//  simply misses it, which is the correct behavior for a passing notification).
//

import Foundation
import Combine

@MainActor
final class CertificateProvisioningNotifier: ObservableObject {
    static let shared = CertificateProvisioningNotifier()

    enum Event: Equatable {
        /// Provisioning has started in the background.
        case inProgress
        /// Provisioning finished; the certificate is cached and usable.
        case success
        /// Provisioning failed. `detail` is a short user-facing snippet if available.
        case failure(detail: String?)
    }

    @Published private(set) var event: Event?
    /// Bumped on every emit so the banner can restart its auto-dismiss timer
    /// even when the same event value is emitted twice in a row.
    @Published private(set) var sequence: Int = 0

    private init() {}

    func emitInProgress() {
        emit(.inProgress)
    }

    func emitSuccess() {
        emit(.success)
    }

    func emitFailure(_ error: Error) {
        let detail = describeFailure(error)
        emit(.failure(detail: detail))
    }

    func dismiss() {
        event = nil
    }

    private func emit(_ newEvent: Event) {
        event = newEvent
        sequence &+= 1
    }

    private func describeFailure(_ error: Error) -> String? {
        let message: String
        switch error {
        case SecurityRepository.SecurityError.authRequired:
            message = String(localized: "CERT_BANNER_REASON_AUTH")
        case SecurityRepository.SecurityError.invalidResponse,
             SecurityRepository.SecurityError.invalidCertificate,
             SecurityRepository.SecurityError.ownerMismatch:
            message = String(localized: "CERT_BANNER_REASON_REJECTED")
        default:
            message = (error as NSError).localizedDescription
        }
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(160))
    }
}
