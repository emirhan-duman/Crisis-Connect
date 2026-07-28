//
//  CrisisSentinelModelManifestClient.swift
//  Crisis Connect
//

import FirebaseCore
import FirebaseFunctions

struct CrisisSentinelModelManifestClient {
    private let functions: Functions?

    init(functions: Functions? = nil) {
        self.functions = functions ?? (FirebaseApp.app() != nil ? Functions.functions(region: "us-central1") : nil)
    }

    func fetchLatest(platform: String = "ios") async throws -> CrisisSentinelModelRelease? {
        guard let functions else { return nil }
        let result = try await functions
            .httpsCallable("getCrisisSentinelModelManifest")
            .callAsync(["platform": platform])
        return try Self.parse(result.data)
    }

    static func parse(_ raw: Any) throws -> CrisisSentinelModelRelease? {
        guard let envelope = raw as? [String: Any] else { return nil }
        guard envelope["available"] as? Bool == true else { return nil }
        guard let release = envelope["release"] as? [String: Any] else {
            throw ManifestError.missingField("release")
        }

        let id = try stringValue("id", from: release)
        let displayName = try stringValue("displayName", from: release)
        let fileName = try stringValue("fileName", from: release)
        let downloadURL = try optionalDownloadURL(from: release)
        let storageLocation = try optionalStorageLocation(from: release)
            ?? downloadURL.flatMap(CrisisSentinelModelDownloadURLPolicy.storageLocation)
        guard storageLocation != nil else {
            throw ManifestError.invalidField("storagePath")
        }

        let sha256 = optionalStringValue("expectedSha256", from: release)?.lowercased()
        if let sha256, sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) == nil {
            throw ManifestError.invalidField("expectedSha256")
        }

        return CrisisSentinelModelRelease(
            id: id,
            displayName: displayName,
            fileName: fileName,
            downloadURL: downloadURL,
            storageLocation: storageLocation,
            expectedSHA256: sha256,
            expectedBytes: try optionalInt64Value("expectedBytes", from: release),
            minFreeBytes: try optionalInt64Value("minFreeBytes", from: release)
                ?? CrisisSentinelModelRelease.defaultRelease.minFreeBytes
        )
    }

    private static func stringValue(_ key: String, from data: [String: Any]) throws -> String {
        guard let value = optionalStringValue(key, from: data) else {
            throw ManifestError.missingField(key)
        }
        return value
    }

    private static func optionalStringValue(_ key: String, from data: [String: Any]) -> String? {
        (data[key] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
    }

    private static func optionalInt64Value(_ key: String, from data: [String: Any]) throws -> Int64? {
        guard let raw = data[key] else { return nil }
        let value: Int64?
        switch raw {
        case let number as NSNumber:
            let double = number.doubleValue
            guard double.rounded() == double else { throw ManifestError.invalidField(key) }
            value = number.int64Value
        case let int as Int:
            value = Int64(int)
        case let int64 as Int64:
            value = int64
        case let double as Double:
            guard double.rounded() == double else { throw ManifestError.invalidField(key) }
            value = Int64(double)
        default:
            throw ManifestError.invalidField(key)
        }
        guard let value, value > 0 else { throw ManifestError.invalidField(key) }
        return value
    }

    private static func optionalDownloadURL(from data: [String: Any]) throws -> URL? {
        guard let downloadURLString = optionalStringValue("downloadUrl", from: data) else {
            return nil
        }
        guard let downloadURL = URL(string: downloadURLString),
              CrisisSentinelModelDownloadURLPolicy.isAllowed(downloadURL) else {
            throw ManifestError.invalidField("downloadUrl")
        }
        return downloadURL
    }

    private static func optionalStorageLocation(from data: [String: Any]) throws -> CrisisSentinelStorageLocation? {
        let explicitBucket = optionalStringValue("storageBucket", from: data)
        let explicitPath = optionalStringValue("storagePath", from: data) ?? optionalStringValue("objectPath", from: data)
        guard explicitBucket != nil || explicitPath != nil else { return nil }
        guard let bucket = explicitBucket, let objectPath = explicitPath else {
            throw ManifestError.invalidField("storagePath")
        }
        guard CrisisSentinelModelDownloadURLPolicy.isAllowed(bucket: bucket, objectPath: objectPath) else {
            throw ManifestError.invalidField("storagePath")
        }
        return CrisisSentinelStorageLocation(bucket: bucket, objectPath: objectPath)
    }

    enum ManifestError: Error, Equatable {
        case missingField(String)
        case invalidField(String)
    }
}

struct CrisisSentinelModelDownloadAccess: Equatable {
    let releaseId: String
    let downloadURL: URL
    let expiresAtMs: Int64
}

struct CrisisSentinelModelDownloadAccessClient {
    private let functions: Functions?

    init(functions: Functions? = nil) {
        self.functions = functions ?? (FirebaseApp.app() != nil ? Functions.functions(region: "us-central1") : nil)
    }

    func fetchDownloadURL(
        release: CrisisSentinelModelRelease,
        platform: String = "ios"
    ) async throws -> CrisisSentinelModelDownloadAccess {
        guard let functions else {
            throw AccessError.notAvailable
        }
        let result = try await functions
            .httpsCallable("getCrisisSentinelModelDownloadUrl")
            .callAsync([
                "platform": platform,
                "releaseId": release.id
            ])
        guard let access = try Self.parse(result.data, expectedReleaseId: release.id) else {
            throw AccessError.notAvailable
        }
        return access
    }

    static func parse(_ raw: Any, expectedReleaseId: String? = nil) throws -> CrisisSentinelModelDownloadAccess? {
        guard let envelope = raw as? [String: Any] else { return nil }
        guard envelope["available"] as? Bool == true else { return nil }

        let releaseId = try stringValue("releaseId", from: envelope)
        if let expectedReleaseId, releaseId != expectedReleaseId {
            throw AccessError.releaseMismatch
        }

        guard
            let downloadURL = URL(string: try stringValue("downloadUrl", from: envelope)),
            CrisisSentinelModelDownloadURLPolicy.isAllowed(downloadURL)
        else {
            throw AccessError.invalidDownloadURL
        }

        let expiresAtMs = try int64Value("expiresAtMs", from: envelope)
        guard expiresAtMs > 0 else { throw AccessError.invalidExpiry }

        return CrisisSentinelModelDownloadAccess(
            releaseId: releaseId,
            downloadURL: downloadURL,
            expiresAtMs: expiresAtMs
        )
    }

    private static func stringValue(_ key: String, from data: [String: Any]) throws -> String {
        guard let value = (data[key] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        else {
            throw AccessError.missingField(key)
        }
        return value
    }

    private static func int64Value(_ key: String, from data: [String: Any]) throws -> Int64 {
        guard let raw = data[key] else { throw AccessError.missingField(key) }
        switch raw {
        case let number as NSNumber:
            let double = number.doubleValue
            guard double.rounded() == double else { throw AccessError.invalidField(key) }
            return number.int64Value
        case let int as Int:
            return Int64(int)
        case let int64 as Int64:
            return int64
        case let double as Double:
            guard double.rounded() == double else { throw AccessError.invalidField(key) }
            return Int64(double)
        default:
            throw AccessError.invalidField(key)
        }
    }

    enum AccessError: Error, Equatable {
        case notAvailable
        case missingField(String)
        case invalidField(String)
        case releaseMismatch
        case invalidDownloadURL
        case invalidExpiry
    }
}

enum CrisisSentinelModelDownloadURLPolicy {
    private static let firebaseStorageHost = "firebasestorage.googleapis.com"
    private static let gcsHost = "storage.googleapis.com"
    private static let modelObjectPrefix = "crisis-sentinel/models/"
    private static let allowedModelBuckets: Set<String> = [
        "crisis-connect-1.firebasestorage.app",
        "crisis-connect-1.appspot.com"
    ]

    static func isAllowed(_ url: URL) -> Bool {
        storageLocation(url) != nil
    }

    static func storageLocation(_ url: URL) -> CrisisSentinelStorageLocation? {
        guard
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.scheme?.lowercased() == "https",
            let host = components.host?.lowercased()
        else { return nil }

        switch host {
        case firebaseStorageHost:
            return firebaseStorageLocation(components)
        case gcsHost:
            return gcsStorageLocation(components)
        default:
            return nil
        }
    }

    static func isAllowed(bucket: String, objectPath: String) -> Bool {
        allowedModelBuckets.contains(bucket) && objectPath.hasPrefix(modelObjectPrefix)
    }

    private static func firebaseStorageLocation(_ components: URLComponents) -> CrisisSentinelStorageLocation? {
        guard components.queryItems?.contains(where: { $0.name == "alt" && $0.value == "media" }) == true else {
            return nil
        }

        let path = components.percentEncodedPath
        let bucketPrefix = "/v0/b/"
        let objectMarker = "/o/"
        guard path.hasPrefix(bucketPrefix), let markerRange = path.range(of: objectMarker) else {
            return nil
        }

        let bucketStart = path.index(path.startIndex, offsetBy: bucketPrefix.count)
        let bucket = decode(String(path[bucketStart..<markerRange.lowerBound]))
        let objectPath = decode(String(path[markerRange.upperBound...]))
        return isAllowed(bucket: bucket, objectPath: objectPath)
            ? CrisisSentinelStorageLocation(bucket: bucket, objectPath: objectPath)
            : nil
    }

    private static func gcsStorageLocation(_ components: URLComponents) -> CrisisSentinelStorageLocation? {
        let path = decode(components.percentEncodedPath).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let separator = path.firstIndex(of: "/") else { return nil }
        let bucket = String(path[..<separator])
        let objectStart = path.index(after: separator)
        let objectPath = String(path[objectStart...])
        return isAllowed(bucket: bucket, objectPath: objectPath)
            ? CrisisSentinelStorageLocation(bucket: bucket, objectPath: objectPath)
            : nil
    }

    private static func decode(_ value: String) -> String {
        value.removingPercentEncoding ?? value
    }
}

final class CrisisSentinelModelManifestCache {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> CrisisSentinelModelRelease? {
        guard
            let id = defaults.string(forKey: Keys.id)?.nilIfEmpty,
            let fileName = defaults.string(forKey: Keys.fileName)?.nilIfEmpty
        else { return nil }

        let displayName = defaults.string(forKey: Keys.displayName)?.nilIfEmpty ?? id
        let downloadURL = defaults.string(forKey: Keys.downloadURL)
            .flatMap { URL(string: $0) }
        let storageLocation = storedStorageLocation()
        let expectedSHA256 = defaults.string(forKey: Keys.sha256)?.nilIfEmpty
        let expectedBytes = optionalStoredInt64(Keys.expectedBytes)
        let minFreeBytes = optionalStoredInt64(Keys.minFreeBytes)
            ?? CrisisSentinelModelRelease.defaultRelease.minFreeBytes

        return CrisisSentinelModelRelease(
            id: id,
            displayName: displayName,
            fileName: fileName,
            downloadURL: downloadURL,
            storageLocation: storageLocation,
            expectedSHA256: expectedSHA256,
            expectedBytes: expectedBytes,
            minFreeBytes: minFreeBytes
        )
    }

    func save(_ release: CrisisSentinelModelRelease) {
        defaults.set(release.id, forKey: Keys.id)
        defaults.set(release.displayName, forKey: Keys.displayName)
        defaults.set(release.fileName, forKey: Keys.fileName)
        defaults.set(release.downloadURL?.absoluteString, forKey: Keys.downloadURL)
        defaults.set(release.storageLocation?.bucket, forKey: Keys.storageBucket)
        defaults.set(release.storageLocation?.objectPath, forKey: Keys.storagePath)
        defaults.set(release.expectedSHA256, forKey: Keys.sha256)
        defaults.set(release.expectedBytes ?? -1, forKey: Keys.expectedBytes)
        defaults.set(release.minFreeBytes, forKey: Keys.minFreeBytes)
    }

    private func optionalStoredInt64(_ key: String) -> Int64? {
        let value = defaults.object(forKey: key) as? NSNumber
        let intValue = value?.int64Value ?? -1
        return intValue > 0 ? intValue : nil
    }

    private func storedStorageLocation() -> CrisisSentinelStorageLocation? {
        guard
            let bucket = defaults.string(forKey: Keys.storageBucket)?.nilIfEmpty,
            let objectPath = defaults.string(forKey: Keys.storagePath)?.nilIfEmpty,
            CrisisSentinelModelDownloadURLPolicy.isAllowed(bucket: bucket, objectPath: objectPath)
        else { return nil }
        return CrisisSentinelStorageLocation(bucket: bucket, objectPath: objectPath)
    }

    private enum Keys {
        static let id = "crisisSentinel.modelManifest.id"
        static let displayName = "crisisSentinel.modelManifest.displayName"
        static let fileName = "crisisSentinel.modelManifest.fileName"
        static let downloadURL = "crisisSentinel.modelManifest.downloadURL"
        static let storageBucket = "crisisSentinel.modelManifest.storageBucket"
        static let storagePath = "crisisSentinel.modelManifest.storagePath"
        static let sha256 = "crisisSentinel.modelManifest.sha256"
        static let expectedBytes = "crisisSentinel.modelManifest.expectedBytes"
        static let minFreeBytes = "crisisSentinel.modelManifest.minFreeBytes"
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
