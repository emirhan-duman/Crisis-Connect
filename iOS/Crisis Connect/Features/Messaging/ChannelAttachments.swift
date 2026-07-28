//
//  ChannelAttachments.swift
//  Crisis Connect
//
//  Byte-compatible port of the web dashboard's `lib/messaging/attachments.ts` and Android's
//  ChannelAttachments. Each blob is AES-256-GCM sealed with the channel key (AAD = the channel's
//  AAD — `channelId` for hierarchy, `agencySlug` for agency, same as the text seal) and uploaded
//  to Firebase Storage as opaque ciphertext at `messageAttachments/{uid}/{uuid}`; a small
//  descriptor array `{path,nonce,name,mime,size,width?,height?,duration?}` is itself encrypted
//  and stored inside the message doc as `attMeta`/`attMetaNonce`, so filenames and storage paths
//  never leak. A channel member decrypts the descriptor, downloads the blob and decrypts it
//  locally — matching what the web renders.
//

import CryptoKit
import FirebaseAuth
import FirebaseStorage
import Foundation

/// A ready-to-send attachment: an already-prepared blob (compressed image / recorded voice / file).
struct PendingChannelAttachment {
    let data: Data
    let name: String
    let mime: String
    var width: Int? = nil
    var height: Int? = nil
    /// Playback length in seconds, for voice notes (audio types).
    var durationSec: Int? = nil
}

/// A decoded attachment descriptor read off a message doc; its blob is still encrypted in Storage.
struct ChannelAttachment: Equatable, Identifiable {
    var id: String { path }
    let path: String
    let nonce: String
    let name: String
    let mime: String
    let size: Int
    var width: Int? = nil
    var height: Int? = nil
    var durationSec: Int? = nil

    var isImage: Bool { mime.hasPrefix("image/") }
    var isAudio: Bool { mime.hasPrefix("audio/") }
}

enum ChannelAttachmentsError: Error {
    case notSignedIn
    case cryptoFailed
}

enum ChannelAttachments {
    static let maxAttachmentBytes = 25 * 1024 * 1024

    // MARK: - Crypto (AES-256-GCM, ct || tag — identical to web/Android)

    private static func encryptBytes(
        key: SymmetricKey, aad: String, bytes: Data
    ) throws -> (nonceBase64: String, cipher: Data) {
        var nonceData = Data(count: 12)
        _ = nonceData.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!) }
        let box = try AES.GCM.seal(
            bytes,
            using: key,
            nonce: AES.GCM.Nonce(data: nonceData),
            authenticating: Data(aad.utf8)
        )
        return (nonceData.base64EncodedString(), box.ciphertext + box.tag)
    }

    private static func decryptBytes(
        key: SymmetricKey, aad: String, nonceBase64: String, cipher: Data
    ) throws -> Data {
        guard let nonceData = Data(base64Encoded: nonceBase64), cipher.count > 16 else {
            throw ChannelAttachmentsError.cryptoFailed
        }
        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonceData),
            ciphertext: cipher.prefix(cipher.count - 16),
            tag: cipher.suffix(16)
        )
        return try AES.GCM.open(box, using: key, authenticating: Data(aad.utf8))
    }

    // MARK: - Send side

    /// Encrypts + uploads every pending blob, then returns the encrypted descriptor fields to merge
    /// into the message doc (`attMeta` + `attMetaNonce`), or nil when there are no attachments.
    static func buildAttachmentFields(
        key: SymmetricKey,
        aad: String,
        pendings: [PendingChannelAttachment]
    ) async throws -> [String: Any]? {
        guard !pendings.isEmpty else { return nil }
        guard let uid = Auth.auth().currentUser?.uid else { throw ChannelAttachmentsError.notSignedIn }
        let storage = Storage.storage()
        var descriptors: [[String: Any]] = []
        for pending in pendings {
            let (nonce, cipher) = try encryptBytes(key: key, aad: aad, bytes: pending.data)
            let path = "messageAttachments/\(uid)/\(UUID().uuidString)"
            _ = try await storage.reference(withPath: path).putDataAsync(cipher)
            var descriptor: [String: Any] = [
                "path": path,
                "nonce": nonce,
                "name": pending.name,
                "mime": pending.mime,
                "size": pending.data.count
            ]
            if let width = pending.width { descriptor["width"] = width }
            if let height = pending.height { descriptor["height"] = height }
            if let duration = pending.durationSec { descriptor["duration"] = duration }
            descriptors.append(descriptor)
        }
        let metaJson = try JSONSerialization.data(withJSONObject: descriptors)
        let (metaNonce, metaCipher) = try encryptBytes(key: key, aad: aad, bytes: metaJson)
        return [
            "attMeta": metaCipher.base64EncodedString(),
            "attMetaNonce": metaNonce
        ]
    }

    // MARK: - Receive side

    /// Reads + decrypts the descriptor array off a raw message doc. Empty when none/undecodable.
    static func decodeAttachments(
        key: SymmetricKey,
        aad: String,
        attMeta: String?,
        attMetaNonce: String?
    ) -> [ChannelAttachment] {
        guard let attMeta, let attMetaNonce,
              !attMeta.isEmpty, !attMetaNonce.isEmpty,
              let metaCipher = Data(base64Encoded: attMeta),
              let plain = try? decryptBytes(key: key, aad: aad, nonceBase64: attMetaNonce, cipher: metaCipher),
              let raw = try? JSONSerialization.jsonObject(with: plain) as? [[String: Any]] else {
            return []
        }
        return raw.compactMap { descriptor in
            guard let path = descriptor["path"] as? String, !path.isEmpty,
                  let nonce = descriptor["nonce"] as? String, !nonce.isEmpty else {
                return nil
            }
            return ChannelAttachment(
                path: path,
                nonce: nonce,
                name: descriptor["name"] as? String ?? "file",
                mime: descriptor["mime"] as? String ?? "application/octet-stream",
                size: (descriptor["size"] as? NSNumber)?.intValue ?? 0,
                width: (descriptor["width"] as? NSNumber)?.intValue,
                height: (descriptor["height"] as? NSNumber)?.intValue,
                durationSec: (descriptor["duration"] as? NSNumber)?.intValue
            )
        }
    }

    /// Returns the decrypted blob for a descriptor. Offline-first: once fetched, the plaintext is
    /// cached in app-private storage keyed by the Storage path, so later views (including offline
    /// ones) read from disk without hitting Storage or needing the channel key again — a disaster
    /// app should keep its media on the device.
    static func fetchAttachmentBytes(
        key: SymmetricKey?,
        aad: String?,
        attachment: ChannelAttachment
    ) async -> Data? {
        let cacheURL = mediaCacheURL(for: attachment.path)
        if let cached = try? Data(contentsOf: cacheURL), !cached.isEmpty {
            return cached
        }
        // Not cached yet: need the channel key to download + decrypt.
        guard let key, let aad else { return nil }
        guard let cipher = try? await Storage.storage()
            .reference(withPath: attachment.path)
            .data(maxSize: Int64(maxAttachmentBytes + 4096)) else {
            return nil
        }
        guard let plain = try? decryptBytes(
            key: key, aad: aad, nonceBase64: attachment.nonce, cipher: cipher
        ) else {
            return nil
        }
        // Cache atomically so a crash mid-write never leaves a truncated "complete" file.
        try? plain.write(to: cacheURL, options: .atomic)
        return plain
    }

    /// App-private cache path for a blob, named by SHA-256 of its Storage path (which has slashes).
    private static func mediaCacheURL(for path: String) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = base.appendingPathComponent("authority_media", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let hash = SHA256.hash(data: Data(path.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return directory.appendingPathComponent(hash, isDirectory: false)
    }
}
