//
//  OfflineMapTileStore.swift
//  Crisis Connect
//
//  Created by Codex on 27.12.2025
//

import Foundation
import MapKit

struct OfflineMapTileArchiveEntry: Sendable {
    let z: Int
    let x: Int
    let y: Int
    let data: Data
}

final class OfflineTileStore {
    private let fileManager = FileManager.default
    private let baseURL: URL

    init() {
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        baseURL = root.appendingPathComponent("OfflineTiles", isDirectory: true)
        ensureDirectory(baseURL)
    }

    func tileURL(regionId: UUID, z: Int, x: Int, y: Int) -> URL {
        baseURL
            .appendingPathComponent(regionId.uuidString, isDirectory: true)
            .appendingPathComponent(String(z), isDirectory: true)
            .appendingPathComponent(String(x), isDirectory: true)
            .appendingPathComponent("\(y).png")
    }

    func tileExists(regionId: UUID, z: Int, x: Int, y: Int) -> Bool {
        fileManager.fileExists(atPath: tileURL(regionId: regionId, z: z, x: x, y: y).path)
    }

    func localTileData(regionIds: [UUID], z: Int, x: Int, y: Int) -> Data? {
        for regionId in regionIds {
            let url = tileURL(regionId: regionId, z: z, x: x, y: y)
            if fileManager.fileExists(atPath: url.path) {
                if let payload = try? LocalEncryptedFileStore.read(from: url) {
                    if !payload.wasEncrypted {
                        try? LocalEncryptedFileStore.write(payload.data, to: url)
                    }
                    return payload.data
                }
                return try? Data(contentsOf: url)
            }
        }
        return nil
    }

    func createTileDirectory(regionId: UUID, z: Int, x: Int) throws {
        let dir = baseURL
            .appendingPathComponent(regionId.uuidString, isDirectory: true)
            .appendingPathComponent(String(z), isDirectory: true)
            .appendingPathComponent(String(x), isDirectory: true)
        let attributes: [FileAttributeKey: Any] = [
            .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
        ]
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true, attributes: attributes)
    }

    func writeTileData(_ data: Data, regionId: UUID, z: Int, x: Int, y: Int) throws {
        try createTileDirectory(regionId: regionId, z: z, x: x)
        let destination = tileURL(regionId: regionId, z: z, x: x, y: y)
        try LocalEncryptedFileStore.write(data, to: destination)
    }

    func removeRegion(regionId: UUID) {
        let regionURL = baseURL.appendingPathComponent(regionId.uuidString, isDirectory: true)
        try? fileManager.removeItem(at: regionURL)
    }

    func countTiles(regionId: UUID) -> Int {
        let regionURL = baseURL.appendingPathComponent(regionId.uuidString, isDirectory: true)
        guard let enumerator = fileManager.enumerator(at: regionURL, includingPropertiesForKeys: nil) else {
            return 0
        }
        var count = 0
        for case let fileURL as URL in enumerator {
            if fileURL.pathExtension.lowercased() == "png" {
                count += 1
            }
        }
        return count
    }

    func exportTiles(regionId: UUID) -> [OfflineMapTileArchiveEntry] {
        let regionURL = baseURL.appendingPathComponent(regionId.uuidString, isDirectory: true)
        guard let enumerator = fileManager.enumerator(
            at: regionURL,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        var entries: [OfflineMapTileArchiveEntry] = []
        for case let fileURL as URL in enumerator {
            guard fileURL.pathExtension.lowercased() == "png" else { continue }
            let relativePath = fileURL.path.replacingOccurrences(of: regionURL.path + "/", with: "")
            let components = relativePath.split(separator: "/")
            guard components.count == 3,
                  let z = Int(components[0]),
                  let x = Int(components[1]),
                  let y = Int(URL(fileURLWithPath: String(components[2])).deletingPathExtension().lastPathComponent),
                  let payload = try? LocalEncryptedFileStore.read(from: fileURL) else {
                continue
            }
            entries.append(
                OfflineMapTileArchiveEntry(
                    z: z,
                    x: x,
                    y: y,
                    data: payload.data
                )
            )
        }

        return entries.sorted {
            if $0.z != $1.z { return $0.z < $1.z }
            if $0.x != $1.x { return $0.x < $1.x }
            return $0.y < $1.y
        }
    }

    private func ensureDirectory(_ url: URL) {
        guard !fileManager.fileExists(atPath: url.path) else { return }
        let attributes: [FileAttributeKey: Any] = [
            .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
        ]
        try? fileManager.createDirectory(at: url, withIntermediateDirectories: true, attributes: attributes)
    }
}

final class OfflineTileProvider {
    private let tileStore: OfflineTileStore
    private let queue = DispatchQueue(label: "offline.tile.provider.queue")
    private var regionIds: [UUID] = []
    var allowsNetworkFallback: Bool = true

    init(tileStore: OfflineTileStore) {
        self.tileStore = tileStore
    }

    func updateRegions(_ regionIds: [UUID]) {
        queue.async {
            self.regionIds = regionIds
        }
    }

    func localTileData(z: Int, x: Int, y: Int) -> Data? {
        queue.sync {
            tileStore.localTileData(regionIds: regionIds, z: z, x: x, y: y)
        }
    }
}

final class OfflineTileOverlay: MKTileOverlay {
    private let provider: OfflineTileProvider

    init(provider: OfflineTileProvider, urlTemplate: String) {
        self.provider = provider
        super.init(urlTemplate: urlTemplate)
        canReplaceMapContent = true
        minimumZ = OfflineMapConfiguration.overlayMinZoom
        maximumZ = OfflineMapConfiguration.overlayMaxZoom
        tileSize = CGSize(width: 256, height: 256)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        if let data = provider.localTileData(z: path.z, x: path.x, y: path.y) {
            result(data, nil)
            return
        }
        guard provider.allowsNetworkFallback else {
            result(nil, NSError(domain: "offline.map", code: -1, userInfo: nil))
            return
        }
        super.loadTile(at: path, result: result)
    }
}
