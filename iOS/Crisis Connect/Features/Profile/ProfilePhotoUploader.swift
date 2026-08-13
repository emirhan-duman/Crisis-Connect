//
//  ProfilePhotoUploader.swift
//  Crisis Connect
//
//  Uploads the locally saved profile photo to Firebase Storage at `users/{uid}/avatar.jpg`, then
//  writes the download URL back to the user doc — the iOS counterpart of Android's
//  ProfilePhotoUploadWorker. The photoURL feeds message notifications, contact avatars and the
//  web panel. Role/agency gates live in storage.rules/firestore.rules server-side; an unentitled
//  upload simply fails quietly (the photo stays local), matching Android's pre-flight outcome.
//
//  A photo picked while offline is persisted to disk and retried on the next app foreground —
//  the newest pick always wins.
//

import FirebaseAuth
import FirebaseFirestore
import FirebaseStorage
import Foundation

enum ProfilePhotoUploader {
    private static let pendingKey = "profile.photoUploadPending"
    private static let pendingGenerationKey = "profile.photoUploadGeneration"
    private static let deletionPendingKey = "profile.photoDeletionPending"

    private static var pendingFileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return base.appendingPathComponent("profile_avatar_upload.jpg", isDirectory: false)
    }

    /// Queues `jpegData` for upload (replacing any older pending pick) and tries immediately.
    static func schedule(jpegData: Data) {
        guard !jpegData.isEmpty else { return }
        UserDefaults.standard.removeObject(forKey: deletionPendingKey)
        try? jpegData.write(to: pendingFileURL, options: .atomic)
        UserDefaults.standard.set(true, forKey: pendingKey)
        UserDefaults.standard.set(UUID().uuidString, forKey: pendingGenerationKey)
        Task { await attemptPendingUpload() }
    }

    /// Attempts delivery of a pending photo; called after scheduling and on app foreground.
    static func attemptPendingUpload() async {
        if UserDefaults.standard.bool(forKey: deletionPendingKey) {
            do {
                try await publishDeletionTombstone()
            } catch {
                NSLog("ProfilePhotoUploader: deletion sync failed (will retry): %@", String(describing: error))
                return
            }
        }
        guard UserDefaults.standard.bool(forKey: pendingKey) else { return }
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return }
        let generation = UserDefaults.standard.string(forKey: pendingGenerationKey) ?? UUID().uuidString
        UserDefaults.standard.set(generation, forKey: pendingGenerationKey)
        guard let data = try? Data(contentsOf: pendingFileURL), !data.isEmpty else {
            clearPendingUpload()
            return
        }
        do {
            let reference = Storage.storage().reference(withPath: "users/\(user.uid)/avatar.jpg")
            let metadata = StorageMetadata()
            metadata.contentType = "image/jpeg"
            metadata.cacheControl = "public, max-age=300"
            _ = try await reference.putDataAsync(data, metadata: metadata)
            let url = try await reference.downloadURL()
            // A delete or a newer selection may have happened while Storage was uploading. Only the
            // generation that is still pending may publish its URL to Firestore.
            guard UserDefaults.standard.string(forKey: pendingGenerationKey) == generation else { return }
            try await Firestore.firestore().collection("users").document(user.uid).setData([
                "photoURL": url.absoluteString,
                "photoUpdatedAt": FieldValue.serverTimestamp(),
                "photoDeletedAt": FieldValue.delete()
            ], merge: true)
            clearPendingUpload()
            NSLog("ProfilePhotoUploader: avatar uploaded")
        } catch {
            // Storage/Firestore rules gate unentitled accounts; offline retries on next foreground.
            NSLog("ProfilePhotoUploader: upload failed (will retry): %@", String(describing: error))
        }
    }

    /// Makes deletion authoritative before removing the Storage object. The empty photoURL is a
    /// cross-platform tombstone, so clients never fall back to an older Auth or local-cache value.
    static func removeCurrentPhoto() async throws {
        clearPendingUpload()
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return }
        UserDefaults.standard.set(true, forKey: deletionPendingKey)
        try await publishDeletionTombstone(user: user)
    }

    private static func publishDeletionTombstone(user explicitUser: User? = nil) async throws {
        guard let user = explicitUser ?? Auth.auth().currentUser, !user.isAnonymous else { return }
        try await Firestore.firestore().collection("users").document(user.uid).setData([
            "photoURL": "",
            "photoUpdatedAt": FieldValue.serverTimestamp(),
            "photoDeletedAt": FieldValue.serverTimestamp()
        ], merge: true)
        UserDefaults.standard.removeObject(forKey: deletionPendingKey)

        do {
            try await Storage.storage().reference(withPath: "users/\(user.uid)/avatar.jpg").delete()
        } catch {
            // The Firestore tombstone is already authoritative. A missing object or a transient
            // Storage failure must not resurrect the photo in any client.
            NSLog("ProfilePhotoUploader: storage cleanup failed: %@", String(describing: error))
        }
    }

    private static func clearPendingUpload() {
        UserDefaults.standard.removeObject(forKey: pendingKey)
        UserDefaults.standard.removeObject(forKey: pendingGenerationKey)
        try? FileManager.default.removeItem(at: pendingFileURL)
    }
}
