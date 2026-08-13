//
//  ChannelAttachments.swift
//  Crisis Connect
//
//  Byte-compatible port of the web dashboard's `lib/messaging/attachments.ts` and Android's
//  ChannelAttachments. Each new blob is AES-256-GCM sealed with its own random key (AAD = its
//  Storage path) and uploaded
//  to Firebase Storage as opaque ciphertext at
//  `authorityMessageAttachments/{conversationId}/{uid}/{uuid}`; a small
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
struct PendingChannelAttachment: Sendable {
    let data: Data
    let name: String
    let mime: String
    var width: Int? = nil
    var height: Int? = nil
    /// Playback length in seconds, for voice notes (audio types).
    var durationSec: Int? = nil
}

/// A decoded attachment descriptor read off a message doc; its blob is still encrypted in Storage.
struct ChannelAttachment: Equatable, Identifiable, Sendable {
    var id: String { path }
    let path: String
    let nonce: String
    /// Per-file AES key. Nil only for legacy blobs sealed with the channel key.
    var keyBase64: String? = nil
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
    /// Android's authenticated GATT file lane currently caps one transfer at 512 KiB. Keep the
    /// Authority MLS relay at the cross-platform minimum; larger encrypted blobs wait for cloud.
    static let authorityMlsBluetoothMaxBytes = 512 * 1024
    static let authorityMlsBlobMime = "application/x-crisisconnect-authority-mls-attachment"
    static let authorityMlsEnvelopeMime = "application/x-crisisconnect-authority-mls-envelope"
    private static let cacheMagic = Data([0x43, 0x43, 0x41, 0x54, 0x54, 0x02])
    private static let authorityConversationPattern = "^am2_[A-Za-z0-9_-]{43}$"
    private static let authorityAttachmentPathPattern = "^authorityMessageAttachments/am2_[A-Za-z0-9_-]{43}/[^/]{1,256}/[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"

    // MARK: - Crypto (AES-256-GCM, ct || tag — identical to web/Android)

    private static func encryptBytes(
        key: SymmetricKey, aad: String, bytes: Data
    ) throws -> (nonceBase64: String, cipher: Data) {
        var nonceData = Data(count: 12)
        let randomStatus = nonceData.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!)
        }
        guard randomStatus == errSecSuccess else { throw ChannelAttachmentsError.cryptoFailed }
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

    /// Encrypts MLS-v2 file blobs with independent random keys and returns descriptors that must be
    /// carried only inside MLS plaintext. The path lets Storage rules enforce the exact conversation.
    static func prepareAuthorityMlsAttachments(
        conversationId: String,
        pendings: [PendingChannelAttachment]
    ) async throws -> [ChannelAttachment] {
        guard conversationId.range(of: authorityConversationPattern, options: .regularExpression) != nil,
              pendings.count <= 8 else { throw ChannelAttachmentsError.cryptoFailed }
        guard !pendings.isEmpty else { return [] }
        guard let uid = Auth.auth().currentUser?.uid,
              (1...256).contains(uid.count), !uid.contains("/") else {
            throw ChannelAttachmentsError.notSignedIn
        }
        var descriptors: [ChannelAttachment] = []
        for pending in pendings {
            guard !pending.data.isEmpty, pending.data.count <= maxAttachmentBytes,
                  (1...255).contains(pending.name.utf8.count),
                  (1...255).contains(pending.mime.utf8.count) else {
                throw ChannelAttachmentsError.cryptoFailed
            }
            let path = "authorityMessageAttachments/\(conversationId)/\(uid)/\(UUID().uuidString)"
            let fileKey = SymmetricKey(size: .bits256)
            let (nonce, cipher) = try encryptBytes(key: fileKey, aad: path, bytes: pending.data)
            guard cacheAuthorityMlsCiphertext(path: path, cipher: cipher) else {
                throw ChannelAttachmentsError.cryptoFailed
            }
            descriptors.append(ChannelAttachment(
                path: path,
                nonce: nonce,
                keyBase64: fileKey.withUnsafeBytes { Data($0).base64EncodedString() },
                name: pending.name,
                mime: pending.mime,
                size: pending.data.count,
                width: pending.width,
                height: pending.height,
                durationSec: pending.durationSec
            ))
        }
        return descriptors
    }

    /// Uploads the exact locally cached ciphertext; safe to retry after an offline BLE send.
    static func ensureAuthorityMlsAttachmentsUploaded(_ attachments: [ChannelAttachment]) async throws {
        guard !attachments.isEmpty else { return }
        guard let uid = Auth.auth().currentUser?.uid,
              (1...256).contains(uid.count), !uid.contains("/") else {
            throw ChannelAttachmentsError.notSignedIn
        }
        for attachment in attachments {
            let components = attachment.path.split(separator: "/", omittingEmptySubsequences: false)
            guard attachment.path.range(of: authorityAttachmentPathPattern, options: .regularExpression) != nil,
                  components.count == 4, components[2] == Substring(uid),
                  attachment.keyBase64 != nil,
                  let cipher = cachedAuthorityMlsCiphertext(path: attachment.path) else {
                throw ChannelAttachmentsError.cryptoFailed
            }
            let reference = Storage.storage().reference(withPath: attachment.path)
            let digest = Data(SHA256.hash(data: cipher)).base64EncodedString()
            func uploadedObjectMatches() async -> Bool {
                guard let existing = try? await reference.getMetadata(),
                      existing.size == Int64(cipher.count) else { return false }
                if let storedDigest = existing.customMetadata?["cc-sha256"] {
                    return storedDigest == digest
                }
                guard let existingCipher = try? await reference.data(
                    maxSize: Int64(maxAttachmentBytes + 4096)
                ) else { return false }
                return existingCipher == cipher
            }
            let metadata = StorageMetadata()
            metadata.contentType = "application/octet-stream"
            metadata.customMetadata = ["cc-sha256": digest]
            do {
                _ = try await reference.putDataAsync(cipher, metadata: metadata)
            } catch {
                // Objects are immutable. Treat a raced/crash-recovery retry as success only after
                // verifying that the already-created object is this exact ciphertext.
                guard await uploadedObjectMatches() else { throw error }
            }
        }
    }

    /// Stores only opaque AES-GCM ciphertext in the app-private cache.
    @discardableResult
    static func cacheAuthorityMlsCiphertext(path: String, cipher: Data) -> Bool {
        guard path.range(of: authorityAttachmentPathPattern, options: .regularExpression) != nil,
              (17...(maxAttachmentBytes + 16)).contains(cipher.count) else { return false }
        do {
            try (cacheMagic + cipher).write(to: mediaCacheURL(for: path), options: .atomic)
            return true
        } catch {
            return false
        }
    }

    static func cachedAuthorityMlsCiphertext(path: String) -> Data? {
        guard path.range(of: authorityAttachmentPathPattern, options: .regularExpression) != nil,
              let encoded = try? Data(contentsOf: mediaCacheURL(for: path)),
              encoded.starts(with: cacheMagic) else { return nil }
        let cipher = Data(encoded.dropFirst(cacheMagic.count))
        return (17...(maxAttachmentBytes + 16)).contains(cipher.count) ? cipher : nil
    }

    /// Best-effort rollback for blobs uploaded before MLS staging fails. Storage rules only allow
    /// the original uploader to delete these immutable objects.
    static func deleteAuthorityMlsAttachments(_ attachments: [ChannelAttachment]) async {
        for attachment in attachments where
            attachment.path.range(of: authorityAttachmentPathPattern, options: .regularExpression) != nil {
            try? await Storage.storage().reference(withPath: attachment.path).delete()
        }
    }

    /// Encrypts + uploads every pending blob, then returns the encrypted descriptor fields to merge
    /// into the message doc (`attMeta` + `attMetaNonce`), or nil when there are no attachments.
    static func buildAttachmentFields(
        key _: SymmetricKey,
        aad _: String,
        pendings _: [PendingChannelAttachment]
    ) async throws -> [String: Any]? {
        throw ChannelAttachmentsError.cryptoFailed
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
                keyBase64: descriptor["key"] as? String,
                name: descriptor["name"] as? String ?? "file",
                mime: descriptor["mime"] as? String ?? "application/octet-stream",
                size: (descriptor["size"] as? NSNumber)?.intValue ?? 0,
                width: (descriptor["width"] as? NSNumber)?.intValue,
                height: (descriptor["height"] as? NSNumber)?.intValue,
                durationSec: (descriptor["duration"] as? NSNumber)?.intValue
            )
        }
    }

    /// Returns the decrypted blob for a descriptor. Offline cache files contain only the original
    /// ciphertext. Pre-v2 cache entries held plaintext and are deleted without being returned.
    static func fetchAttachmentBytes(
        key: SymmetricKey?,
        aad: String?,
        attachment: ChannelAttachment
    ) async -> Data? {
        guard attachment.path.range(of: authorityAttachmentPathPattern, options: .regularExpression) != nil,
              attachment.keyBase64 != nil else { return nil }
        let cacheURL = mediaCacheURL(for: attachment.path)
        if let cached = try? Data(contentsOf: cacheURL), !cached.isEmpty {
            if cached.starts(with: cacheMagic) {
                let cipher = cached.dropFirst(cacheMagic.count)
                if let opened = decryptAttachment(
                    legacyChannelKey: key,
                    legacyAad: aad,
                    attachment: attachment,
                    cipher: Data(cipher)
                ) {
                    return opened
                }
            } else {
                // Legacy plaintext cache: remove the insecure at-rest copy and fetch ciphertext.
                try? FileManager.default.removeItem(at: cacheURL)
            }
        }
        guard let cipher = try? await Storage.storage()
            .reference(withPath: attachment.path)
            .data(maxSize: Int64(maxAttachmentBytes + 4096)) else {
            return nil
        }
        guard let plain = decryptAttachment(
            legacyChannelKey: key,
            legacyAad: aad,
            attachment: attachment,
            cipher: cipher
        ) else {
            return nil
        }
        guard plain.count == attachment.size else { return nil }
        // Cache ciphertext atomically, never plaintext.
        _ = cacheAuthorityMlsCiphertext(path: attachment.path, cipher: cipher)
        return plain
    }

    private static func decryptAttachment(
        legacyChannelKey: SymmetricKey?,
        legacyAad: String?,
        attachment: ChannelAttachment,
        cipher: Data
    ) -> Data? {
        if let encoded = attachment.keyBase64,
           let rawKey = Data(base64Encoded: encoded),
           rawKey.count == 32 {
            return try? decryptBytes(
                key: SymmetricKey(data: rawKey),
                aad: attachment.path,
                nonceBase64: attachment.nonce,
                cipher: cipher
            )
        }
        guard let legacyChannelKey, let legacyAad else { return nil }
        return try? decryptBytes(
            key: legacyChannelKey,
            aad: legacyAad,
            nonceBase64: attachment.nonce,
            cipher: cipher
        )
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
