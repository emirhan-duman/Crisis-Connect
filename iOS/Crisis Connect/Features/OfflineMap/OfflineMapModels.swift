//
//  OfflineMapModels.swift
//  Crisis Connect
//
//  Created by Codex on 27.12.2025
//

import Foundation
import CoreLocation
import Darwin

struct MapBounds: Codable, Equatable, Sendable {
    var north: Double
    var south: Double
    var east: Double
    var west: Double

    var center: CLLocationCoordinate2D {
        let lat = (north + south) / 2
        let lon = (east + west) / 2
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    var normalized: MapBounds {
        MapBounds(
            north: max(north, south),
            south: min(north, south),
            east: east,
            west: west
        )
    }
}

enum OfflineRegionStatus: String, Codable, Sendable {
    case idle
    case downloading
    case paused
    case complete
    case failed

    var label: String {
        switch self {
        case .idle:
            return "Idle"
        case .downloading:
            return "Downloading"
        case .paused:
            return "Paused"
        case .complete:
            return "Complete"
        case .failed:
            return "Failed"
        }
    }
}

struct OfflineMapRegion: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    var name: String
    var bounds: MapBounds
    var minZoom: Int
    var maxZoom: Int
    var tileCount: Int
    var downloadedTiles: Int
    var missingTiles: Int = 0
    var status: OfflineRegionStatus
    var createdAt: Date
    var lastError: String?
    var downloadCursorOrdinal: Int? = nil

    var progress: Double {
        if status == .complete { return 1 }
        guard tileCount > 0 else { return 0 }
        let processedTiles = downloadedTiles + missingTiles
        return min(max(Double(processedTiles) / Double(tileCount), 0), 1)
    }

    var percentComplete: Int {
        Int((progress * 100).rounded())
    }
}

enum OfflineMapSheet: Identifiable, Sendable {
    case editor
    case list

    var id: Int {
        switch self {
        case .editor: return 0
        case .list: return 1
        }
    }
}

struct OfflineMapConfiguration: Sendable {
    static var tileTemplate: String {
        buildTileTemplate(style: "streets-v2")
    }

    static var fallbackTileTemplate: String {
        buildTileTemplate(style: "basic-v2")
    }
    static let overlayMinZoom = 0
    static let overlayMaxZoom = 18
    static let downloadMinZoom = 5
    static let downloadMaxZoom = 18
    static let defaultMinZoom = 10
    static let defaultMaxZoom = 12
    static let averageTileBytes: Int64 = 50_000
    static let warningTileLimit = 4000
    static let warningByteLimit: Int64 = 200_000_000
    static let refreshStride = 25
    static let progressPublishStride = 8
    static let progressPublishInterval: TimeInterval = 0.25

    static var mapTilerAPIKey: String? {
        resolvedMapTilerAPIKey()
    }

    static var hasMapTilerAPIKey: Bool {
        mapTilerAPIKey != nil
    }

    static func buildTileTemplate(style: String, apiKey: String? = nil) -> String {
        let normalizedStyle = style.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "streets-v2"
        let key = (apiKey ?? mapTilerAPIKey)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let baseURL = "https://api.maptiler.com/maps/\(normalizedStyle)/256/{z}/{x}/{y}.png"
        guard let key else { return baseURL }
        return "\(baseURL)?key=\(key)"
    }

    private static func resolvedMapTilerAPIKey() -> String? {
        let environmentKeys = [
            "MAPTILER_API_KEY",
            "MAPTILER_ACCESS_TOKEN",
            "MAPLIBRE_API_KEY"
        ]
        for key in environmentKeys {
            if let value = environmentValue(for: key) {
                return value
            }
        }

        let infoDictionaryKeys = [
            "MapTilerAPIKey",
            "MAPTILER_API_KEY",
            "MAPTILER_ACCESS_TOKEN",
            "MAPLIBRE_API_KEY"
        ]
        for key in infoDictionaryKeys {
            if let value = Bundle.main.object(forInfoDictionaryKey: key) as? String,
               let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
                return normalized
            }
        }
        return nil
    }

    private static func environmentValue(for key: String) -> String? {
        guard let raw = getenv(key) else { return nil }
        return String(cString: raw).trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }
}

struct TileCoordinate: Hashable, Sendable {
    let z: Int
    let x: Int
    let y: Int
}

struct TileCalculator {
    static func tileCount(bounds: MapBounds, minZoom: Int, maxZoom: Int) -> Int {
        let clampedMin = max(minZoom, OfflineMapConfiguration.downloadMinZoom)
        let clampedMax = min(maxZoom, OfflineMapConfiguration.downloadMaxZoom)
        guard clampedMin <= clampedMax else { return 0 }

        var total = 0
        for zoom in clampedMin...clampedMax {
            let xRanges = tileXRanges(bounds: bounds, zoom: zoom)
            let yRange = tileYRange(bounds: bounds, zoom: zoom)
            let yCount = yRange.count
            for range in xRanges {
                total += range.count * yCount
            }
        }
        return total
    }

    static func tileXRanges(bounds: MapBounds, zoom: Int) -> [ClosedRange<Int>] {
        let normalized = bounds.normalized
        let n = maxIndex(zoom: zoom)
        let west = clampLongitude(normalized.west)
        let east = clampLongitude(normalized.east)
        let xMin = tileX(longitude: west, zoom: zoom)
        let xMax = tileX(longitude: east, zoom: zoom)

        if west <= east {
            return [xMin...xMax]
        }
        return [xMin...n, 0...xMax]
    }

    static func tileYRange(bounds: MapBounds, zoom: Int) -> ClosedRange<Int> {
        let normalized = bounds.normalized
        let north = clampLatitude(normalized.north)
        let south = clampLatitude(normalized.south)
        let yMin = tileY(latitude: north, zoom: zoom)
        let yMax = tileY(latitude: south, zoom: zoom)
        let lower = min(yMin, yMax)
        let upper = max(yMin, yMax)
        return lower...upper
    }

    static func tileX(longitude: Double, zoom: Int) -> Int {
        let n = pow(2.0, Double(zoom))
        let x = Int(floor((longitude + 180.0) / 360.0 * n))
        return clamp(value: x, min: 0, max: Int(n) - 1)
    }

    static func tileY(latitude: Double, zoom: Int) -> Int {
        let lat = clampLatitude(latitude)
        let latRad = lat * Double.pi / 180.0
        let n = pow(2.0, Double(zoom))
        let y = Int(floor((1.0 - log(tan(latRad) + 1.0 / cos(latRad)) / Double.pi) / 2.0 * n))
        return clamp(value: y, min: 0, max: Int(n) - 1)
    }

    static func maxIndex(zoom: Int) -> Int {
        Int(pow(2.0, Double(zoom)) - 1)
    }

    static func clampLatitude(_ value: Double) -> Double {
        min(max(value, -85.05112878), 85.05112878)
    }

    static func clampLongitude(_ value: Double) -> Double {
        min(max(value, -180.0), 180.0)
    }

    static func clamp(value: Int, min: Int, max: Int) -> Int {
        Swift.max(min, Swift.min(value, max))
    }
}

private extension ClosedRange where Bound == Int {
    var count: Int {
        Swift.max(0, upperBound - lowerBound + 1)
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
