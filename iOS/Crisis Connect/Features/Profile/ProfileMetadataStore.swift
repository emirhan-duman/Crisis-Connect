//
//  ProfileMetadataStore.swift
//  Crisis Connect
//
//  Created by Assistant on 12.03.2026.
//

import Foundation
import SwiftData
import UIKit
import FirebaseCore
import FirebaseAuth

enum ProfileMetadataStore {
    static let fullNameKey = "profile.fullName"
    static let countryKey = "profile.country"
    static let agencyKey = "profile.agency"
    static let avatarThumbnailKey = "profile.avatarThumbnail"

    private static let queueKey = DispatchSpecificKey<UInt8>()
    private static let queueContext: UInt8 = 1
    private static let queue: DispatchQueue = {
        let queue = DispatchQueue(label: "profile.metadata.store")
        queue.setSpecific(key: queueKey, value: queueContext)
        return queue
    }()
    private static var cachedSnapshot: Snapshot?
    private static var legacyMigrationAttempted = false
    private static let persistenceURL: URL = {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent("profile_metadata.json", isDirectory: false)
    }()

    static func loadFullName(userDefaults: UserDefaults = .standard) -> String {
        syncOnQueue {
            currentSnapshotLocked(userDefaults: userDefaults).fullName
        }
    }

    static func preferredDisplayName(userDefaults: UserDefaults = .standard) -> String? {
        let fullName = loadFullName(userDefaults: userDefaults)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !fullName.isEmpty {
            return fullName
        }

        if FirebaseApp.app() != nil {
            let authDisplayName = Auth.auth().currentUser?.displayName?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !authDisplayName.isEmpty {
                return authDisplayName
            }
        }

        return nil
    }

    static func saveFullName(_ value: String, userDefaults: UserDefaults = .standard) {
        syncOnQueue {
            var snapshot = currentSnapshotLocked(userDefaults: userDefaults)
            snapshot.fullName = sanitize(value)
            persistLocked(snapshot, userDefaults: userDefaults)
        }
    }

    static func loadCountry(userDefaults: UserDefaults = .standard) -> String {
        syncOnQueue {
            currentSnapshotLocked(userDefaults: userDefaults).country
        }
    }

    static func saveCountry(_ value: String, userDefaults: UserDefaults = .standard) {
        syncOnQueue {
            var snapshot = currentSnapshotLocked(userDefaults: userDefaults)
            snapshot.country = normalizeCountryCode(value)
            persistLocked(snapshot, userDefaults: userDefaults)
        }
    }

    static func loadAgency(userDefaults: UserDefaults = .standard) -> String {
        syncOnQueue {
            currentSnapshotLocked(userDefaults: userDefaults).agency
        }
    }

    static func saveAgency(_ value: String, userDefaults: UserDefaults = .standard) {
        syncOnQueue {
            var snapshot = currentSnapshotLocked(userDefaults: userDefaults)
            snapshot.agency = sanitize(value)
            persistLocked(snapshot, userDefaults: userDefaults)
        }
    }

    static func loadAvatarThumbnailData(userDefaults: UserDefaults = .standard) -> Data? {
        syncOnQueue {
            currentSnapshotLocked(userDefaults: userDefaults).avatarThumbnailData
        }
    }

    static func saveAvatarThumbnailData(_ data: Data?, userDefaults: UserDefaults = .standard) {
        syncOnQueue {
            var snapshot = currentSnapshotLocked(userDefaults: userDefaults)
            snapshot.avatarThumbnailData = (data?.isEmpty == false) ? data : nil
            persistLocked(snapshot, userDefaults: userDefaults)
        }
    }

    static func normalizeCountryCode(_ rawValue: String?) -> String {
        rawValue?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased() ?? ""
    }

    static func sanitize(_ rawValue: String?) -> String {
        rawValue?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    static func resolvedAgency(email: String, country: String, explicitAgency: String) -> String {
        let explicit = sanitize(explicitAgency)
        if !explicit.isEmpty {
            return explicit
        }
        if let emailAgency = AgencyRouter.agencyForEmail(email) {
            return emailAgency
        }
        return AgencyRouter.agencyForCountry(country) ?? ""
    }

    private static func currentSnapshotLocked(userDefaults: UserDefaults) -> Snapshot {
        if let cachedSnapshot {
            return hydrateFromProfileStoreIfNeededLocked(snapshot: cachedSnapshot, userDefaults: userDefaults)
        }
        if let persisted = readPersistedSnapshotLocked(userDefaults: userDefaults) {
            return hydrateFromProfileStoreIfNeededLocked(snapshot: persisted, userDefaults: userDefaults)
        }
        let migrated = migrateLegacySnapshotLocked(userDefaults: userDefaults) ?? Snapshot()
        return hydrateFromProfileStoreIfNeededLocked(snapshot: migrated, userDefaults: userDefaults)
    }

    private static func readPersistedSnapshotLocked(userDefaults: UserDefaults) -> Snapshot? {
        guard let payload = try? LocalEncryptedFileStore.read(from: persistenceURL) else {
            return nil
        }
        guard let snapshot = try? JSONDecoder().decode(Snapshot.self, from: payload.data) else {
            return nil
        }
        if !payload.wasEncrypted {
            persistLocked(snapshot, userDefaults: userDefaults, notifyObservers: false)
        } else {
            clearLegacyUserDefaults(userDefaults)
        }
        return snapshot
    }

    private static func migrateLegacySnapshotLocked(userDefaults: UserDefaults) -> Snapshot? {
        guard !legacyMigrationAttempted else { return nil }
        legacyMigrationAttempted = true

        let snapshot = Snapshot(
            fullName: sanitize(userDefaults.string(forKey: fullNameKey)),
            country: normalizeCountryCode(userDefaults.string(forKey: countryKey)),
            agency: sanitize(userDefaults.string(forKey: agencyKey)),
            avatarThumbnailData: {
                guard let data = userDefaults.data(forKey: avatarThumbnailKey), !data.isEmpty else {
                    return nil
                }
                return data
            }()
        )

        guard snapshot.hasContent else {
            clearLegacyUserDefaults(userDefaults)
            return nil
        }

        persistLocked(snapshot, userDefaults: userDefaults, notifyObservers: false)
        return snapshot
    }

    private static func persistLocked(
        _ snapshot: Snapshot,
        userDefaults: UserDefaults,
        notifyObservers: Bool = true
    ) {
        cachedSnapshot = snapshot
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? LocalEncryptedFileStore.write(data, to: persistenceURL)
        clearLegacyUserDefaults(userDefaults)
        guard notifyObservers else { return }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .profileMetadataStoreDidChange, object: nil)
        }
    }

    private static func clearLegacyUserDefaults(_ userDefaults: UserDefaults) {
        userDefaults.removeObject(forKey: fullNameKey)
        userDefaults.removeObject(forKey: countryKey)
        userDefaults.removeObject(forKey: agencyKey)
        userDefaults.removeObject(forKey: avatarThumbnailKey)
    }

    private static func hydrateFromProfileStoreIfNeededLocked(
        snapshot: Snapshot,
        userDefaults: UserDefaults
    ) -> Snapshot {
        guard snapshot.fullName.isEmpty || snapshot.avatarThumbnailData == nil else {
            cachedSnapshot = snapshot
            return snapshot
        }
        guard let profile = loadPersistedProfile() else {
            cachedSnapshot = snapshot
            return snapshot
        }

        var updated = snapshot
        var didChange = false

        if updated.fullName.isEmpty {
            let resolvedName = sanitize(profile.fullName)
            if !resolvedName.isEmpty {
                updated.fullName = resolvedName
                didChange = true
            }
        }

        if updated.avatarThumbnailData == nil,
           let imageData = profile.avatarImageData,
           !imageData.isEmpty,
           let thumbnailData = makeAvatarThumbnailData(from: imageData) {
            updated.avatarThumbnailData = thumbnailData
            didChange = true
        }

        if didChange {
            persistLocked(updated, userDefaults: userDefaults)
        } else {
            cachedSnapshot = updated
        }
        return updated
    }

    private static func loadPersistedProfile() -> Profile? {
        guard let container = Crisis_ConnectApp.makeSharedModelContainer() else {
            return nil
        }
        let context = ModelContext(container)
        let descriptor = FetchDescriptor<Profile>(
            sortBy: [SortDescriptor(\Profile.createdAt, order: .reverse)]
        )
        return try? context.fetch(descriptor).first
    }

    private static func makeAvatarThumbnailData(from imageData: Data, side: CGFloat = 72) -> Data? {
        guard let image = UIImage(data: imageData) else { return nil }
        let minSide = min(image.size.width, image.size.height)
        guard minSide > 0 else { return nil }

        let origin = CGPoint(
            x: (image.size.width - minSide) / 2,
            y: (image.size.height - minSide) / 2
        )
        let cropRect = CGRect(origin: origin, size: CGSize(width: minSide, height: minSide))
        let cropped: UIImage
        if let cgImage = image.cgImage,
           let croppedImage = cgImage.cropping(to: cropRect) {
            cropped = UIImage(cgImage: croppedImage, scale: image.scale, orientation: image.imageOrientation)
        } else {
            cropped = image
        }

        let targetSize = CGSize(width: side, height: side)
        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let thumbnail = renderer.image { _ in
            cropped.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return thumbnail.jpegData(compressionQuality: 0.78)
    }

    private static func syncOnQueue<T>(_ operation: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) == queueContext {
            return operation()
        }
        return queue.sync(execute: operation)
    }

    private struct Snapshot: Codable {
        var fullName: String = ""
        var country: String = ""
        var agency: String = ""
        var avatarThumbnailData: Data?

        var hasContent: Bool {
            !fullName.isEmpty || !country.isEmpty || !agency.isEmpty || avatarThumbnailData?.isEmpty == false
        }
    }
}

extension Notification.Name {
    static let profileMetadataStoreDidChange = Notification.Name("profileMetadataStoreDidChange")
}
