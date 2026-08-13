//
//  LocalDataEraser.swift
//  Crisis Connect
//
//  On-device half of account deletion.
//

import Foundation

/// Erases the personal data this app wrote outside Firebase, so deleting an account does not leave a
/// readable copy of someone's conversations sitting on the phone.
///
/// Deliberately path-by-path instead of emptying Application Support, because two things share that
/// directory and must SURVIVE the erase: downloaded offline map tiles (`OfflineTiles`,
/// `offline_regions.json`) and the Crisis Sentinel model (`CrisisSentinelModels`). Both are
/// gigabyte-scale downloads that hold nothing personal, and they are exactly what a user cannot
/// re-fetch during a disaster with no connectivity — destroying them would take away the offline
/// capability the app exists for. The SwiftData store (`default.store`) is spared here too: it is
/// cleared through its ModelContext instead, because removing the file under a live container
/// corrupts it rather than emptying it.
enum LocalDataEraser {
    /// Directories whose CONTENTS are personal. The directories themselves are kept — see
    /// `removeFiles(under:)` for why.
    private static let personalDirectories = [
        // Mesh + SOS chat JSON, contacts, profile metadata, voice/image message media, session
        // avatars, pending outbound queues, live-location sync queue, shared documents.
        "CrisisConnect",
        // libsignal sessions, identities and prekey pools. The server copy is already gone by the
        // time this runs, and a session without its peer is unusable anyway.
        "SignalStore",
        // Decrypted authority-channel attachment cache.
        "authority_media",
    ]

    /// Single files that are personal in their entirety.
    private static let personalFiles = [
        "authority_channel_rows.json",
        "profile_avatar_upload.jpg",
    ]

    /// UserDefaults keys holding conversation content or account-scoped derived material.
    private static let personalDefaultsKeys = [
        "crisis_sentinel_conversations",
        "crisis_sentinel_default_mode",
    ]

    /// Keys that embed the uid and therefore can only be found by prefix.
    private static let personalDefaultsPrefixes = ["crisisSentinel.panelKey."]

    static func eraseAll() {
        let fileManager = FileManager.default
        guard let root = fileManager
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first
        else {
            NSLog("LocalDataEraser: no Application Support directory, nothing erased")
            return
        }

        for directory in personalDirectories {
            removeFiles(under: root.appendingPathComponent(directory, isDirectory: true))
        }

        for file in personalFiles {
            remove(root.appendingPathComponent(file, isDirectory: false))
        }

        let defaults = UserDefaults.standard
        for key in personalDefaultsKeys {
            defaults.removeObject(forKey: key)
        }
        for key in defaults.dictionaryRepresentation().keys
        where personalDefaultsPrefixes.contains(where: key.hasPrefix) {
            defaults.removeObject(forKey: key)
        }
    }

    /// Deletes every file beneath `directory` while leaving the directory tree in place.
    ///
    /// Removing the tree outright would be shorter but leaves the app half-broken until the next
    /// launch: several stores resolve their directory once in a `static let` and create it there, so
    /// a deleted `voice_messages/` never comes back this session and later writes fail silently
    /// behind a `try?`. Keeping the empty shells means the next message still saves.
    private static func removeFiles(under directory: URL) {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: directory.path) else { return }

        guard let enumerator = fileManager.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: []
        ) else {
            NSLog("LocalDataEraser: could not enumerate %@", directory.lastPathComponent)
            return
        }

        for case let url as URL in enumerator {
            let isDirectory = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            if !isDirectory {
                remove(url)
            }
        }
    }

    private static func remove(_ url: URL) {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: url.path) else { return }
        do {
            try fileManager.removeItem(at: url)
        } catch {
            NSLog(
                "LocalDataEraser: failed to remove %@: %@",
                url.lastPathComponent,
                String(describing: error)
            )
        }
    }
}
