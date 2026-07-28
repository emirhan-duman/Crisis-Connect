//
//  CrisisSentinelModelFileStore.swift
//  Crisis Connect
//

import CryptoKit
import Foundation

struct CrisisSentinelStorageLocation: Equatable {
    let bucket: String
    let objectPath: String
}

struct CrisisSentinelModelRelease: Equatable {
    let id: String
    let displayName: String
    let fileName: String
    let downloadURL: URL?
    let storageLocation: CrisisSentinelStorageLocation?
    let expectedSHA256: String?
    let expectedBytes: Int64?
    let minFreeBytes: Int64

    init(
        id: String,
        displayName: String,
        fileName: String,
        downloadURL: URL? = nil,
        storageLocation: CrisisSentinelStorageLocation? = nil,
        expectedSHA256: String? = nil,
        expectedBytes: Int64? = nil,
        minFreeBytes: Int64 = 2_500_000_000
    ) {
        precondition(Self.isSafeToken(id), "Model id contains unsupported characters")
        precondition(Self.isSafeToken(fileName), "Model file name contains unsupported characters")
        precondition(expectedBytes == nil || expectedBytes! > 0, "expectedBytes must be positive")
        precondition(minFreeBytes >= 0, "minFreeBytes must be non-negative")
        self.id = id
        self.displayName = displayName
        self.fileName = fileName
        self.downloadURL = downloadURL
        self.storageLocation = storageLocation
        self.expectedSHA256 = expectedSHA256
        self.expectedBytes = expectedBytes
        self.minFreeBytes = minFreeBytes
    }

    static let defaultRelease = CrisisSentinelModelRelease(
        id: "crisis-sentinel-edge-mobile-q4",
        displayName: "Crisis Sentinel Edge Mobile Q4",
        fileName: "crisis-sentinel-edge-mobile-q4.task"
    )

    private static func isSafeToken(_ value: String) -> Bool {
        value.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil
    }
}

struct CrisisSentinelInstalledModel: Equatable {
    let release: CrisisSentinelModelRelease
    let fileURL: URL
    let bytes: Int64
    let sha256: String?
}

enum CrisisSentinelModelAvailability: Equatable {
    case missing
    case ready
    case corrupt
    case insufficientStorage
}

struct CrisisSentinelModelStatus: Equatable {
    let release: CrisisSentinelModelRelease
    let availability: CrisisSentinelModelAvailability
    let fileURL: URL
    let bytes: Int64
    let sha256: String?
    let reason: String?

    var isReady: Bool { availability == .ready }
}

final class CrisisSentinelModelFileStore {
    private let fileManager: FileManager
    private let directoryURL: URL

    init(fileManager: FileManager = .default, directoryURL: URL? = nil) {
        self.fileManager = fileManager
        if let directoryURL {
            self.directoryURL = directoryURL
        } else {
            let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            self.directoryURL = base.appendingPathComponent("CrisisSentinelModels", isDirectory: true)
        }
        try? fileManager.createDirectory(at: self.directoryURL, withIntermediateDirectories: true)
    }

    func modelURL(for release: CrisisSentinelModelRelease = .defaultRelease) -> URL {
        directoryURL.appendingPathComponent(release.fileName, isDirectory: false)
    }

    func temporaryDownloadURL(for release: CrisisSentinelModelRelease = .defaultRelease) -> URL {
        directoryURL.appendingPathComponent("\(release.fileName).download", isDirectory: false)
    }

    /// Fast UI readiness checks should pass `validateChecksum: false`; hashing the multi-GB
    /// model file on the main thread stalls navigation. Download commit still validates fully.
    func status(
        for release: CrisisSentinelModelRelease = .defaultRelease,
        validateChecksum: Bool = true
    ) -> CrisisSentinelModelStatus {
        let url = modelURL(for: release)
        guard fileManager.fileExists(atPath: url.path) else {
            if freeBytes() < release.minFreeBytes {
                return CrisisSentinelModelStatus(
                    release: release,
                    availability: .insufficientStorage,
                    fileURL: url,
                    bytes: 0,
                    sha256: nil,
                    reason: "Free storage is below model reserve"
                )
            }
            return CrisisSentinelModelStatus(
                release: release,
                availability: .missing,
                fileURL: url,
                bytes: 0,
                sha256: nil,
                reason: nil
            )
        }

        let bytes = fileSize(url)
        if let expectedBytes = release.expectedBytes, bytes != expectedBytes {
            return CrisisSentinelModelStatus(
                release: release,
                availability: .corrupt,
                fileURL: url,
                bytes: bytes,
                sha256: nil,
                reason: "Model size does not match manifest"
            )
        }

        let sha = (validateChecksum && release.expectedSHA256 != nil) ? Self.sha256(url: url) : nil
        if validateChecksum,
           let expectedSHA256 = release.expectedSHA256,
           sha?.caseInsensitiveCompare(expectedSHA256) != .orderedSame {
            return CrisisSentinelModelStatus(
                release: release,
                availability: .corrupt,
                fileURL: url,
                bytes: bytes,
                sha256: sha,
                reason: "Model checksum does not match manifest"
            )
        }

        return CrisisSentinelModelStatus(
            release: release,
            availability: .ready,
            fileURL: url,
            bytes: bytes,
            sha256: sha,
            reason: nil
        )
    }

    func installedModel(
        for release: CrisisSentinelModelRelease = .defaultRelease,
        validateChecksum: Bool = true
    ) -> CrisisSentinelInstalledModel? {
        let modelStatus = status(for: release, validateChecksum: validateChecksum)
        guard modelStatus.isReady else { return nil }
        return CrisisSentinelInstalledModel(
            release: release,
            fileURL: modelStatus.fileURL,
            bytes: modelStatus.bytes,
            sha256: modelStatus.sha256
        )
    }

    func commitDownloadedModel(from temporaryURL: URL, release: CrisisSentinelModelRelease = .defaultRelease) -> CrisisSentinelModelStatus {
        try? fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        let targetURL = modelURL(for: release)
        if fileManager.fileExists(atPath: targetURL.path) {
            try? fileManager.removeItem(at: targetURL)
        }
        do {
            try fileManager.moveItem(at: temporaryURL, to: targetURL)
        } catch {
            do {
                try fileManager.copyItem(at: temporaryURL, to: targetURL)
                try? fileManager.removeItem(at: temporaryURL)
            } catch {
                return CrisisSentinelModelStatus(
                    release: release,
                    availability: .corrupt,
                    fileURL: targetURL,
                    bytes: fileSize(targetURL),
                    sha256: nil,
                    reason: "Could not move downloaded model into place"
                )
            }
        }
        return status(for: release, validateChecksum: true)
    }

    @discardableResult
    func deleteModel(release: CrisisSentinelModelRelease = .defaultRelease) -> Bool {
        let urls = [modelURL(for: release), temporaryDownloadURL(for: release)]
        var ok = true
        for url in urls where fileManager.fileExists(atPath: url.path) {
            do {
                try fileManager.removeItem(at: url)
            } catch {
                ok = false
            }
        }
        return ok
    }

    private func fileSize(_ url: URL) -> Int64 {
        let size = (try? fileManager.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
        return size ?? 0
    }

    private func freeBytes() -> Int64 {
        let values = try? directoryURL.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    static func sha256(url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }

        var hasher = SHA256()
        while true {
            let data = handle.readData(ofLength: 1024 * 1024)
            if data.isEmpty { break }
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

actor CrisisSentinelModelDownloader {
    private let accessClient: CrisisSentinelModelDownloadAccessClient
    private let manager: CrisisSentinelModelDownloadManager

    init(
        accessClient: CrisisSentinelModelDownloadAccessClient = CrisisSentinelModelDownloadAccessClient(),
        manager: CrisisSentinelModelDownloadManager = .shared
    ) {
        FirebaseRuntime.ensureConfigured()
        self.accessClient = accessClient
        self.manager = manager
    }

    /// Resolves a signed download URL and then hands the transfer to the background-session manager,
    /// streaming progress fractions (0...1) back through `onProgress`.
    func download(
        release: CrisisSentinelModelRelease,
        onProgress: @escaping @Sendable (Double) -> Void = { _ in }
    ) async throws -> CrisisSentinelModelStatus {
        guard let storageLocation = release.storageLocation ?? release.downloadURL.flatMap(CrisisSentinelModelDownloadURLPolicy.storageLocation) else {
            throw CrisisSentinelModelDownloadError.missingHTTPSURL
        }

        let access = try await accessClient.fetchDownloadURL(release: release)
        guard CrisisSentinelModelDownloadURLPolicy.storageLocation(access.downloadURL) == storageLocation else {
            throw CrisisSentinelModelDownloadError.badResponse
        }

        return try await manager.download(
            release: release,
            from: access.downloadURL,
            onProgress: onProgress
        )
    }
}

enum CrisisSentinelModelDownloadError: Error, Equatable {
    case missingHTTPSURL
    case badResponse
    case validationFailed(String?)
    case superseded
}
