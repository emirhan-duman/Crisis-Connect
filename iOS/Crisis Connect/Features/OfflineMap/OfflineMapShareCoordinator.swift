//
//  OfflineMapShareCoordinator.swift
//  Crisis Connect
//
//  Created by Codex on 31.03.2026.
//

import CoreLocation
import Foundation

enum OfflineMapShareCoordinator {
    static let bundleFileExtension = "ccmapbundle"
    static let bundleMimeType = "application/vnd.crisisconnect.offline-map-bundle"
    static let mimeType = bundleMimeType
    static let maxShareBytes = min(10 * 1_024 * 1_024, P2pBleProtocol.fileMaxTotalBytes)

    private static let bundleMagic = Data("CCMAP1".utf8)
    private static let bundleVersion = 1

    static func prepareShareAttachment(
        for coordinate: CLLocationCoordinate2D?,
        messageId: String,
        maxBytes: Int = maxShareBytes
    ) throws -> PreparedP2pDocumentAttachment {
        let region = try selectedRegion(for: coordinate)
        let bundleData = try buildBundle(for: region, maxBytes: maxBytes)
        let displayName = bundleDisplayName(for: region)

        guard P2pSharedTransferSupport.persistSharedDocumentData(
            bundleData,
            messageId: messageId,
            displayName: displayName
        ) != nil else {
            throw ShareError.persistFailed
        }

        return PreparedP2pDocumentAttachment(
            displayName: displayName,
            mimeType: bundleMimeType,
            originalSizeBytes: bundleData.count,
            transferSizeBytes: bundleData.count,
            payloadData: bundleData
        )
    }

    static func canImport(payload: P2pSharedFilePayload) -> Bool {
        if payload.mimeType?.caseInsensitiveCompare(bundleMimeType) == .orderedSame {
            return true
        }
        return payload.displayName.lowercased().hasSuffix(".\(bundleFileExtension)")
    }

    @discardableResult
    static func importSharedBundle(from url: URL) throws -> OfflineMapRegion {
        let accessGranted = url.startAccessingSecurityScopedResource()
        defer {
            if accessGranted {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let payload = try Data(contentsOf: url, options: [.mappedIfSafe])
        return try importSharedBundle(payload: payload)
    }

    @discardableResult
    static func importShareArchiveData(_ payload: Data) -> Bool {
        (try? importSharedBundle(payload: payload)) != nil
    }

    @discardableResult
    private static func importSharedBundle(payload: Data) throws -> OfflineMapRegion {
        let decoded = try decodeBundle(payload)
        let tileStore = OfflineTileStore()
        let regionStore = OfflineRegionStore()

        var regions = regionStore.load()
        let existingIds = Set(regions.map(\.id))
        let importedTileCount = decoded.tiles.count
        let baseTileCount = max(decoded.manifest.region.tileCount, importedTileCount)
        let missingTiles = max(baseTileCount - importedTileCount, 0)
        let importedRegion = OfflineMapRegion(
            id: uniqueRegionId(avoiding: existingIds),
            name: uniqueRegionName(
                preferred: decoded.manifest.region.name,
                existing: regions.map(\.name)
            ),
            bounds: decoded.manifest.region.bounds,
            minZoom: decoded.manifest.region.minZoom,
            maxZoom: decoded.manifest.region.maxZoom,
            tileCount: baseTileCount,
            downloadedTiles: importedTileCount,
            missingTiles: missingTiles,
            status: missingTiles == 0 ? .complete : .paused,
            createdAt: Date(),
            lastError: nil,
            downloadCursorOrdinal: nil
        )

        for tile in decoded.tiles {
            try tileStore.writeTileData(
                tile.data,
                regionId: importedRegion.id,
                z: tile.z,
                x: tile.x,
                y: tile.y
            )
        }

        regions.append(importedRegion)
        regions.sort { $0.createdAt > $1.createdAt }
        regionStore.save(regions)
        return importedRegion
    }

    private static func selectedRegion(for coordinate: CLLocationCoordinate2D?) throws -> OfflineMapRegion {
        let availableRegions = OfflineRegionStore().load()
            .filter { $0.downloadedTiles > 0 || $0.status == .complete }

        guard !availableRegions.isEmpty else {
            throw ShareError.noOfflineMapsAvailable
        }

        let chosen: OfflineMapRegion?
        if let coordinate {
            let covering = availableRegions.filter { contains(coordinate, in: $0.bounds) }
            chosen = rankedRegions(covering.isEmpty ? availableRegions : covering).first
        } else {
            chosen = rankedRegions(availableRegions).first
        }

        guard let chosen else {
            throw ShareError.noOfflineMapsAvailable
        }
        return chosen
    }

    private static func rankedRegions(_ regions: [OfflineMapRegion]) -> [OfflineMapRegion] {
        regions.sorted {
            if $0.progress != $1.progress { return $0.progress > $1.progress }
            if $0.downloadedTiles != $1.downloadedTiles { return $0.downloadedTiles > $1.downloadedTiles }
            return $0.createdAt > $1.createdAt
        }
    }

    private static func buildBundle(
        for region: OfflineMapRegion,
        maxBytes: Int
    ) throws -> Data {
        let tiles = OfflineTileStore().exportTiles(regionId: region.id)
        guard !tiles.isEmpty else {
            throw ShareError.noOfflineMapsAvailable
        }

        let manifest = OfflineMapShareManifest(
            version: bundleVersion,
            exportedAt: Date(),
            region: OfflineMapShareRegion(
                name: region.name,
                bounds: region.bounds,
                minZoom: region.minZoom,
                maxZoom: region.maxZoom,
                tileCount: region.tileCount,
                downloadedTiles: region.downloadedTiles,
                missingTiles: region.missingTiles
            ),
            tiles: tiles.map {
                OfflineMapShareTile(
                    z: $0.z,
                    x: $0.x,
                    y: $0.y,
                    byteCount: $0.data.count
                )
            }
        )

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let manifestData = try encoder.encode(manifest)

        let bodyBytes = tiles.reduce(0) { $0 + $1.data.count }
        let totalBytes = bundleMagic.count + MemoryLayout<UInt64>.size + manifestData.count + bodyBytes
        guard totalBytes <= maxBytes else {
            throw ShareError.bundleTooLarge(maxBytes: maxBytes)
        }

        var payload = Data()
        payload.append(bundleMagic)
        payload.append(uint64Data(UInt64(manifestData.count)))
        payload.append(manifestData)
        tiles.forEach { payload.append($0.data) }
        return payload
    }

    private static func decodeBundle(_ payload: Data) throws -> (manifest: OfflineMapShareManifest, tiles: [OfflineMapTileArchiveEntry]) {
        guard payload.starts(with: bundleMagic) else {
            throw ShareError.invalidBundle
        }
        let lengthOffset = bundleMagic.count
        let manifestLengthEnd = lengthOffset + MemoryLayout<UInt64>.size
        guard payload.count >= manifestLengthEnd else {
            throw ShareError.invalidBundle
        }

        let manifestLengthData = payload.subdata(in: lengthOffset..<manifestLengthEnd)
        var manifestLengthValue: UInt64 = 0
        _ = withUnsafeMutableBytes(of: &manifestLengthValue) { buffer in
            manifestLengthData.copyBytes(to: buffer)
        }
        let manifestLength = Int(UInt64(bigEndian: manifestLengthValue))
        let manifestStart = manifestLengthEnd
        let manifestEnd = manifestStart + manifestLength
        guard manifestLength > 0, payload.count >= manifestEnd else {
            throw ShareError.invalidBundle
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let manifest = try decoder.decode(
            OfflineMapShareManifest.self,
            from: payload.subdata(in: manifestStart..<manifestEnd)
        )
        guard manifest.version == bundleVersion else {
            throw ShareError.invalidBundle
        }

        var cursor = manifestEnd
        var tiles: [OfflineMapTileArchiveEntry] = []
        tiles.reserveCapacity(manifest.tiles.count)

        for tile in manifest.tiles {
            let end = cursor + tile.byteCount
            guard tile.byteCount > 0, payload.count >= end else {
                throw ShareError.invalidBundle
            }
            tiles.append(
                OfflineMapTileArchiveEntry(
                    z: tile.z,
                    x: tile.x,
                    y: tile.y,
                    data: payload.subdata(in: cursor..<end)
                )
            )
            cursor = end
        }

        guard cursor == payload.count else {
            throw ShareError.invalidBundle
        }
        return (manifest, tiles)
    }

    private static func uniqueRegionId(avoiding ids: Set<UUID>) -> UUID {
        var candidate = UUID()
        while ids.contains(candidate) {
            candidate = UUID()
        }
        return candidate
    }

    private static func uniqueRegionName(preferred: String, existing: [String]) -> String {
        let base = preferred.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "Imported Offline Map"
            : preferred.trimmingCharacters(in: .whitespacesAndNewlines)
        let existingLowercased = Set(existing.map { $0.lowercased() })
        if !existingLowercased.contains(base.lowercased()) {
            return base
        }
        var index = 2
        while true {
            let candidate = "\(base) (\(index))"
            if !existingLowercased.contains(candidate.lowercased()) {
                return candidate
            }
            index += 1
        }
    }

    private static func bundleDisplayName(for region: OfflineMapRegion) -> String {
        let slug = region.name
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: "-", options: .regularExpression)
            .replacingOccurrences(of: "[^A-Za-z0-9_-]", with: "", options: .regularExpression)
        let safeSlug = slug.isEmpty ? "offline-map" : slug
        return "\(safeSlug).\(bundleFileExtension)"
    }

    private static func contains(_ coordinate: CLLocationCoordinate2D, in bounds: MapBounds) -> Bool {
        let normalized = bounds.normalized
        let latitudeMatches = coordinate.latitude <= normalized.north && coordinate.latitude >= normalized.south
        if normalized.west <= normalized.east {
            return latitudeMatches &&
                coordinate.longitude >= normalized.west &&
                coordinate.longitude <= normalized.east
        }
        return latitudeMatches &&
            (coordinate.longitude >= normalized.west || coordinate.longitude <= normalized.east)
    }

    private static func uint64Data(_ value: UInt64) -> Data {
        var bigEndian = value.bigEndian
        return withUnsafeBytes(of: &bigEndian) { Data($0) }
    }

    enum ShareError: LocalizedError {
        case noOfflineMapsAvailable
        case bundleTooLarge(maxBytes: Int)
        case persistFailed
        case invalidBundle

        var errorDescription: String? {
            switch self {
            case .noOfflineMapsAvailable:
                return "No offline map region is available to share."
            case .bundleTooLarge:
                return "The selected offline map is too large to send."
            case .persistFailed:
                return "The offline map bundle could not be prepared."
            case .invalidBundle:
                return "The shared offline map bundle is invalid."
            }
        }
    }
}

private struct OfflineMapShareManifest: Codable {
    let version: Int
    let exportedAt: Date
    let region: OfflineMapShareRegion
    let tiles: [OfflineMapShareTile]
}

private struct OfflineMapShareRegion: Codable {
    let name: String
    let bounds: MapBounds
    let minZoom: Int
    let maxZoom: Int
    let tileCount: Int
    let downloadedTiles: Int
    let missingTiles: Int
}

private struct OfflineMapShareTile: Codable {
    let z: Int
    let x: Int
    let y: Int
    let byteCount: Int
}
