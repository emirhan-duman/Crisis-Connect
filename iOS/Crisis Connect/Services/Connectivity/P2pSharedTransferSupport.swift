//
//  P2pSharedTransferSupport.swift
//  Crisis Connect
//
//  Created by Codex on 24.03.2026.
//

import CoreLocation
import Foundation
import Combine
import UniformTypeIdentifiers

struct P2pSharedLocationPayload {
    let latitude: Double
    let longitude: Double
    let accuracyMeters: Double?
    let timestampMillis: Int64?
    let confidenceRadiusMeters: Double?
    let source: String?
}

struct P2pSharedFilePayload {
    let displayName: String
    let originalSizeBytes: Int
    let transferSizeBytes: Int
    let compression: String
    let mimeType: String?
}

struct PreparedP2pDocumentAttachment {
    let displayName: String
    let mimeType: String?
    let originalSizeBytes: Int
    let transferSizeBytes: Int
    let payloadData: Data
}

struct P2pInlineReplyMetadata: Equatable, Sendable {
    let preview: String
    let body: String
    let authorLabel: String?
    let targetMessageId: String?
}

enum P2pSharedTransferSupport {
    static let locationPrefix = "CC_LOC:"
    static let filePrefix = "CC_FILE:"
    static let fileCompressionNone = "none"
    static let locationSourceGPS = "gps"
    static let locationSourceLastKnown = "last_known"
    static let locationSourceBleImu = "ble_imu"

    private static let sharedDocumentsDirectoryName = "shared_documents"
    private static let maxSharedDocumentNameLength = 180
    private static let replyPrefix = "↪"
    private static let multiWhitespacePattern = "\\s+"

    static func buildLocationMessage(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double?,
        timestamp: Date = Date(),
        confidenceRadiusMeters: Double? = nil,
        source: String? = locationSourceGPS
    ) -> String {
        let latitudeText = formatCoordinate(latitude)
        let longitudeText = formatCoordinate(longitude)
        let accuracy = (accuracyMeters?.isFinite == true && accuracyMeters! > 0)
            ? accuracyMeters!
            : (confidenceRadiusMeters ?? 120)
        let timestampMillis = Int64(timestamp.timeIntervalSince1970 * 1000)
        let confidenceText = confidenceRadiusMeters.flatMap { value -> String? in
            guard value.isFinite, value > 0 else { return nil }
            return String(format: "%.1f", locale: Locale.enUSPOSIX, value)
        }
        let sourceText = source?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .nilIfEmpty

        var parts = [
            latitudeText,
            longitudeText,
            String(format: "%.1f", locale: Locale.enUSPOSIX, accuracy),
            String(timestampMillis)
        ]
        if let confidenceText {
            parts.append(confidenceText)
        }
        if let sourceText {
            if confidenceText == nil {
                parts.append("")
            }
            parts.append(sourceText)
        }
        return locationPrefix + parts.joined(separator: ",")
    }

    static func parseLocationMessage(_ text: String) -> P2pSharedLocationPayload? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let direct = parseLocationCandidate(trimmed) {
            return direct
        }
        for line in trimmed.split(whereSeparator: \.isNewline) {
            if let candidate = parseLocationCandidate(String(line)) {
                return candidate
            }
        }
        return nil
    }

    static func parseReplyMetadata(_ text: String) -> P2pInlineReplyMetadata? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(replyPrefix) else { return nil }

        let newlineIndex = trimmed.firstIndex(of: "\n")
        let headerBody: String
        if let newlineIndex {
            let headerStart = trimmed.index(after: trimmed.startIndex)
            guard headerStart < newlineIndex else { return nil }
            headerBody = String(trimmed[headerStart..<newlineIndex])
        } else {
            headerBody = String(trimmed.dropFirst())
        }

        let rawHeader = headerBody.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rawHeader.isEmpty else { return nil }

        let replyTargetId: String?
        let headerContent: String
        if rawHeader.hasPrefix("["),
           let closingIndex = rawHeader.firstIndex(of: "]"),
           closingIndex > rawHeader.index(after: rawHeader.startIndex) {
            replyTargetId = rawHeader[rawHeader.index(after: rawHeader.startIndex)..<closingIndex]
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
            headerContent = rawHeader[rawHeader.index(after: closingIndex)...]
                .trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            replyTargetId = nil
            headerContent = rawHeader
        }

        let authorLabel: String?
        let preview: String
        if let separatorIndex = headerContent.firstIndex(of: "|"),
           separatorIndex > headerContent.startIndex,
           separatorIndex < headerContent.index(before: headerContent.endIndex) {
            authorLabel = headerContent[..<separatorIndex]
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
            preview = headerContent[headerContent.index(after: separatorIndex)...]
                .trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            authorLabel = nil
            preview = headerContent.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        guard !preview.isEmpty else { return nil }

        let body: String = {
            guard let newlineIndex else { return "" }
            let bodyStart = trimmed.index(after: newlineIndex)
            guard bodyStart < trimmed.endIndex else { return "" }
            return String(trimmed[bodyStart...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }()

        return P2pInlineReplyMetadata(
            preview: preview,
            body: body,
            authorLabel: authorLabel,
            targetMessageId: replyTargetId
        )
    }

    static func previewTextForReplyTarget(_ text: String?) -> String? {
        let normalizedText = text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard let normalizedText else { return nil }
        let previewSource = unwrapReplyPreviewSource(normalizedText)

        let preview: String
        if parseFilePreviewMessage(previewSource) != nil {
            preview = sharedFilePreviewText()
        } else if parseLocationMessage(previewSource) != nil {
            preview = SOSChatStore.locationPreviewText()
        } else {
            preview = previewSource
        }

        return collapsedWhitespace(preview).nilIfEmpty
    }

    static func stripReplyMetadata(_ text: String?) -> String? {
        let normalizedText = text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard let normalizedText else { return nil }
        return unwrapReplyBody(normalizedText)
    }

    static func buildReplyFormattedMessage(
        body: String,
        replyToMessageId: String?,
        replyPreviewText: String?,
        replyAuthorLabel: String?
    ) -> String {
        let trimmedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedBody.isEmpty else { return "" }

        guard let safePreview = previewTextForReplyTarget(replyPreviewText) else {
            return trimmedBody
        }

        let safeReplyToMessageId = replyToMessageId?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        let safeAuthorLabel = collapsedWhitespace(
            replyAuthorLabel?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        ).nilIfEmpty

        let heading = if let safeAuthorLabel {
            "\(safeAuthorLabel)|\(safePreview)"
        } else {
            safePreview
        }

        var header = replyPrefix
        if let safeReplyToMessageId {
            header += "[\(safeReplyToMessageId)]"
        }
        header += " \(heading)"
        return "\(header)\n\(trimmedBody)"
    }

    private static func unwrapReplyPreviewSource(_ text: String) -> String {
        var candidate = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var depth = 0
        while depth < 8, let parsedReply = parseReplyMetadata(candidate) {
            let nextCandidate = (parsedReply.body.nilIfEmpty ?? parsedReply.preview)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !nextCandidate.isEmpty, nextCandidate != candidate else {
                break
            }
            candidate = nextCandidate
            depth += 1
        }
        return candidate
    }

    private static func unwrapReplyBody(_ text: String) -> String? {
        var candidate = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var depth = 0
        while depth < 8, let parsedReply = parseReplyMetadata(candidate) {
            guard let nextCandidate = parsedReply.body.nilIfEmpty?
                .trimmingCharacters(in: .whitespacesAndNewlines),
                  !nextCandidate.isEmpty,
                  nextCandidate != candidate else {
                return parsedReply.body.nilIfEmpty
            }
            candidate = nextCandidate
            depth += 1
        }
        return candidate
    }

    static func prepareDocumentAttachment(
        from sourceURL: URL,
        messageId: String,
        maxBytes: Int = P2pBleProtocol.fileMaxTotalBytes
    ) -> PreparedP2pDocumentAttachment? {
        let accessGranted = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessGranted {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        let effectiveMaxBytes = max(1, maxBytes)
        guard
            let data = try? Data(contentsOf: sourceURL, options: [.mappedIfSafe]),
            !data.isEmpty,
            data.count <= effectiveMaxBytes
        else {
            return nil
        }

        let displayName = sanitizedDocumentName(
            sourceURL.lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        let mimeType = resolvedMimeType(for: sourceURL)
        _ = persistSharedDocumentData(data, messageId: messageId, displayName: displayName)

        return PreparedP2pDocumentAttachment(
            displayName: displayName,
            mimeType: mimeType,
            originalSizeBytes: data.count,
            transferSizeBytes: data.count,
            payloadData: data
        )
    }

    static func buildFilePreviewMessage(_ attachment: PreparedP2pDocumentAttachment) -> String {
        let encodedName = Data(attachment.displayName.utf8).base64EncodedString(
            options: [.endLineWithLineFeed]
        )
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        let encodedMime = attachment.mimeType
            .flatMap { mimeType -> String? in
                let trimmed = mimeType.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return nil }
                return Data(trimmed.utf8).base64EncodedString(
                    options: [.endLineWithLineFeed]
                )
                    .replacingOccurrences(of: "\n", with: "")
                    .replacingOccurrences(of: "+", with: "-")
                    .replacingOccurrences(of: "/", with: "_")
            } ?? "-"

        return [
            filePrefix + encodedName,
            String(attachment.originalSizeBytes),
            String(attachment.transferSizeBytes),
            fileCompressionNone,
            encodedMime
        ].joined(separator: ",")
    }

    static func parseFilePreviewMessage(_ text: String) -> P2pSharedFilePayload? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(filePrefix) else { return nil }
        let payload = String(trimmed.dropFirst(filePrefix.count))
        let parts = payload.split(separator: ",", maxSplits: 4, omittingEmptySubsequences: false)
        guard parts.count == 5 else { return nil }
        guard
            let encodedName = decodeBase64URLComponent(String(parts[0])),
            let displayName = String(data: encodedName, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty,
            let originalSizeBytes = Int(parts[1]),
            let transferSizeBytes = Int(parts[2]),
            originalSizeBytes > 0,
            transferSizeBytes > 0
        else {
            return nil
        }
        let compression = String(parts[3]).trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? fileCompressionNone
        let mimeType = decodeBase64URLComponent(String(parts[4]))
            .flatMap { String(data: $0, encoding: .utf8) }?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        return P2pSharedFilePayload(
            displayName: displayName,
            originalSizeBytes: originalSizeBytes,
            transferSizeBytes: transferSizeBytes,
            compression: compression,
            mimeType: mimeType
        )
    }

    static func persistSharedDocumentData(
        _ data: Data,
        messageId: String,
        displayName: String
    ) -> URL? {
        let safeMessageId = sanitizedMessageIdentifier(messageId)
        guard !safeMessageId.isEmpty, !data.isEmpty else { return nil }
        let targetURL = sharedDocumentsDirectoryURL()
            .appendingPathComponent(
                "\(safeMessageId)_\(sanitizedDocumentName(displayName))",
                isDirectory: false
            )
        do {
            try data.write(to: targetURL, options: .atomic)
            return targetURL
        } catch {
            return nil
        }
    }

    static func resolveSharedDocumentURL(messageId: String) -> URL? {
        let safeMessageId = sanitizedMessageIdentifier(messageId)
        guard !safeMessageId.isEmpty else { return nil }
        let prefix = "\(safeMessageId)_"
        let directory = sharedDocumentsDirectoryURL()
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        return contents
            .filter { $0.lastPathComponent.hasPrefix(prefix) }
            .sorted {
                let leftDate = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let rightDate = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return leftDate > rightDate
            }
            .first
    }

    static func previewText(for text: String) -> String {
        let visibleText = stripReplyMetadata(text)
            ?? parseReplyMetadata(text)?.preview
            ?? text.trimmingCharacters(in: .whitespacesAndNewlines)
        if parseFilePreviewMessage(visibleText) != nil {
            return sharedFilePreviewText()
        }
        if parseLocationMessage(visibleText) != nil {
            return SOSChatStore.locationPreviewText()
        }
        return visibleText
    }

    static func sharedFilePreviewText() -> String {
        NSLocalizedString("Shared file", comment: "")
    }

    private static func parseLocationCandidate(_ text: String) -> P2pSharedLocationPayload? {
        guard let range = text.range(of: locationPrefix, options: [.caseInsensitive]) else {
            return nil
        }
        let payload = text[range.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = payload.split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard parts.count >= 2,
              let latitude = Double(parts[0]),
              let longitude = Double(parts[1]),
              (-90...90).contains(latitude),
              (-180...180).contains(longitude) else {
            return nil
        }
        let accuracyMeters = parts[safe: 2].flatMap(Double.init).flatMap { $0 > 0 ? $0 : nil }
        let timestampMillis = parts[safe: 3].flatMap(Int64.init).flatMap { $0 > 0 ? $0 : nil }
        let confidenceRadiusMeters = parts[safe: 4].flatMap(Double.init).flatMap { $0 > 0 ? $0 : nil }
        let source = parts[safe: 5]?.lowercased().nilIfEmpty
        return P2pSharedLocationPayload(
            latitude: latitude,
            longitude: longitude,
            accuracyMeters: accuracyMeters,
            timestampMillis: timestampMillis,
            confidenceRadiusMeters: confidenceRadiusMeters,
            source: source
        )
    }

    private static func decodeBase64URLComponent(_ raw: String) -> Data? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != "-" else { return nil }
        let standard = trimmed
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = (4 - (standard.count % 4)) % 4
        return Data(base64Encoded: standard + String(repeating: "=", count: padding))
    }

    private static func resolvedMimeType(for sourceURL: URL) -> String? {
        if #available(iOS 14.0, *) {
            if let values = try? sourceURL.resourceValues(forKeys: [.contentTypeKey]),
               let contentType = values.contentType,
               let mimeType = contentType.preferredMIMEType?.trimmingCharacters(in: .whitespacesAndNewlines),
               !mimeType.isEmpty {
                return mimeType
            }
        }
        let pathExtension = sourceURL.pathExtension.trimmingCharacters(in: .whitespacesAndNewlines)
        if #available(iOS 14.0, *),
           let mimeType = UTType(filenameExtension: pathExtension)?.preferredMIMEType?.trimmingCharacters(in: .whitespacesAndNewlines),
           !mimeType.isEmpty {
            return mimeType
        }
        return nil
    }

    private static func formatCoordinate(_ value: Double) -> String {
        String(format: "%.6f", locale: Locale.enUSPOSIX, value)
    }

    private static func sanitizedMessageIdentifier(_ raw: String) -> String {
        raw.unicodeScalars
            .map { CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_" ? Character($0) : "_" }
            .reduce(into: "") { $0.append($1) }
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
            .prefix(96)
            .description
    }

    private static func collapsedWhitespace(_ text: String) -> String {
        text.replacingOccurrences(
            of: multiWhitespacePattern,
            with: " ",
            options: .regularExpression
        )
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func sanitizedDocumentName(_ raw: String) -> String {
        let normalized = raw
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .replacingOccurrences(of: "*", with: "_")
            .replacingOccurrences(of: "?", with: "_")
            .replacingOccurrences(of: "\"", with: "_")
            .replacingOccurrences(of: "<", with: "_")
            .replacingOccurrences(of: ">", with: "_")
            .replacingOccurrences(of: "|", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let collapsed = normalized.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        return collapsed.nilIfEmpty?.prefix(maxSharedDocumentNameLength).description
            ?? "shared_file_\(Int(Date().timeIntervalSince1970))"
    }

    private static func sharedDocumentsDirectoryURL() -> URL {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directoryURL = baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent(sharedDocumentsDirectoryName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: directoryURL.path) {
            try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        }
        return directoryURL
    }
}

final class ChatAttachmentLocationCoordinator: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let locationManager = CLLocationManager()
    private var authorizationContinuation: CheckedContinuation<CLAuthorizationStatus, Never>?
    private var locationContinuations: [UUID: CheckedContinuation<CLLocation?, Never>] = [:]

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    func requestLocation(timeout: TimeInterval = 4) async -> CLLocation? {
        let authorization = await resolvedAuthorizationStatus(timeout: 2.5)
        switch authorization {
        case .authorizedAlways, .authorizedWhenInUse:
            break
        default:
            return nil
        }

        if let current = locationManager.location,
           abs(current.timestamp.timeIntervalSinceNow) <= 120 {
            return current
        }

        let token = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                locationContinuations[token] = continuation
                locationManager.requestLocation()
                DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                    guard let self,
                          let continuation = self.locationContinuations.removeValue(forKey: token) else {
                        return
                    }
                    continuation.resume(returning: self.locationManager.location)
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                guard let self,
                      let continuation = self.locationContinuations.removeValue(forKey: token) else {
                    return
                }
                continuation.resume(returning: self.locationManager.location)
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationContinuation?.resume(returning: manager.authorizationStatus)
        authorizationContinuation = nil
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let best = locations.last ?? manager.location
        let continuations = locationContinuations.values
        locationContinuations.removeAll()
        continuations.forEach { $0.resume(returning: best) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let fallback = manager.location
        let continuations = locationContinuations.values
        locationContinuations.removeAll()
        continuations.forEach { $0.resume(returning: fallback) }
    }

    private func resolvedAuthorizationStatus(timeout: TimeInterval) async -> CLAuthorizationStatus {
        let current = locationManager.authorizationStatus
        if current == .authorizedAlways || current == .authorizedWhenInUse || current == .denied || current == .restricted {
            return current
        }
        if current == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                authorizationContinuation = continuation
                DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                    guard let self, let continuation = self.authorizationContinuation else { return }
                    self.authorizationContinuation = nil
                    continuation.resume(returning: self.locationManager.authorizationStatus)
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.authorizationContinuation?.resume(returning: self.locationManager.authorizationStatus)
                self.authorizationContinuation = nil
            }
        }
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private extension Locale {
    static let enUSPOSIX = Locale(identifier: "en_US_POSIX")
}
