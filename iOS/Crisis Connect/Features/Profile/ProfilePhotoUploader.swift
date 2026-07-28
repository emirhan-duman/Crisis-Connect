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

    private static var pendingFileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return base.appendingPathComponent("profile_avatar_upload.jpg", isDirectory: false)
    }

    /// Queues `jpegData` for upload (replacing any older pending pick) and tries immediately.
    static func schedule(jpegData: Data) {
        guard !jpegData.isEmpty else { return }
        try? jpegData.write(to: pendingFileURL, options: .atomic)
        UserDefaults.standard.set(true, forKey: pendingKey)
        Task { await attemptPendingUpload() }
    }

    /// Attempts delivery of a pending photo; called after scheduling and on app foreground.
    static func attemptPendingUpload() async {
        guard UserDefaults.standard.bool(forKey: pendingKey) else { return }
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return }
        guard let data = try? Data(contentsOf: pendingFileURL), !data.isEmpty else {
            UserDefaults.standard.removeObject(forKey: pendingKey)
            return
        }
        do {
            let reference = Storage.storage().reference(withPath: "users/\(user.uid)/avatar.jpg")
            let metadata = StorageMetadata()
            metadata.contentType = "image/jpeg"
            _ = try await reference.putDataAsync(data, metadata: metadata)
            let url = try await reference.downloadURL()
            try await Firestore.firestore().collection("users").document(user.uid).setData([
                "photoURL": url.absoluteString,
                "photoUpdatedAt": FieldValue.serverTimestamp()
            ], merge: true)
            UserDefaults.standard.removeObject(forKey: pendingKey)
            try? FileManager.default.removeItem(at: pendingFileURL)
            NSLog("ProfilePhotoUploader: avatar uploaded")
        } catch {
            // Storage/Firestore rules gate unentitled accounts; offline retries on next foreground.
            NSLog("ProfilePhotoUploader: upload failed (will retry): %@", String(describing: error))
        }
    }
}
