import CoreLocation
import Foundation

enum BreadcrumbTrailMode: String, Codable, Sendable {
    case paused
    case recording
    case returning
    case arrived
}

enum BreadcrumbReturnTarget: String, Codable, Sendable {
    case start
    case lastSafe
}

struct BreadcrumbPoint: Codable, Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let altitudeMeters: Double?
    let accuracyMeters: Double
    let timestamp: Date

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

struct BreadcrumbTrailSession: Codable, Equatable, Sendable {
    let id: UUID
    let startedAt: Date
    var points: [BreadcrumbPoint]
    var safePointIndex: Int
    var mode: BreadcrumbTrailMode
    var returnTarget: BreadcrumbReturnTarget
    var returnCursor: Int?
}

enum BreadcrumbTrailMath {
    static let arrivalRadiusMeters: CLLocationDistance = 15

    static func distance(_ a: BreadcrumbPoint, _ b: BreadcrumbPoint) -> CLLocationDistance {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }

    static func bearing(from: BreadcrumbPoint, to: BreadcrumbPoint) -> CLLocationDirection {
        let lat1 = from.latitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let deltaLongitude = (to.longitude - from.longitude) * .pi / 180
        let y = sin(deltaLongitude) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLongitude)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    static func initialReturnCursor(
        points: [BreadcrumbPoint],
        destinationIndex: Int
    ) -> Int? {
        guard points.count >= 2 else { return nil }
        let destination = min(max(destinationIndex, 0), points.count - 1)
        let current = points[points.count - 1]
        var cursor = points.count - 2
        while cursor > destination && distance(current, points[cursor]) < arrivalRadiusMeters {
            cursor -= 1
        }
        return max(cursor, destination)
    }

    static func advanceReturnCursor(
        points: [BreadcrumbPoint],
        current: BreadcrumbPoint,
        cursor: Int,
        destinationIndex: Int
    ) -> Int {
        guard !points.isEmpty else { return 0 }
        let destination = min(max(destinationIndex, 0), points.count - 1)
        var next = min(max(cursor, destination), points.count - 1)
        let radius = max(arrivalRadiusMeters, min(current.accuracyMeters, 35))
        while next > destination && distance(current, points[next]) <= radius {
            next -= 1
        }
        return next
    }

    static func routeDistance(_ points: [BreadcrumbPoint]) -> CLLocationDistance {
        zip(points, points.dropFirst()).reduce(0) { result, pair in
            result + distance(pair.0, pair.1)
        }
    }

    static func remainingRouteDistance(
        points: [BreadcrumbPoint],
        current: BreadcrumbPoint,
        cursor: Int,
        destinationIndex: Int
    ) -> CLLocationDistance {
        guard !points.isEmpty else { return 0 }
        let destination = min(max(destinationIndex, 0), points.count - 1)
        let safeCursor = min(max(cursor, destination), points.count - 1)
        var result = distance(current, points[safeCursor])
        if safeCursor > destination {
            for index in stride(from: safeCursor, to: destination, by: -1) {
                result += distance(points[index], points[index - 1])
            }
        }
        return result
    }
}

final class BreadcrumbTrailStore {
    private let fileManager = FileManager.default
    private let fileURL: URL
    private let queue = DispatchQueue(label: "breadcrumb.trail.store")

    init() {
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directory = root.appendingPathComponent("BreadcrumbTrail", isDirectory: true)
        fileURL = directory.appendingPathComponent("active-trail.json")
        if !fileManager.fileExists(atPath: directory.path) {
            try? fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
        }
    }

    func load() -> BreadcrumbTrailSession? {
        queue.sync {
            guard let payload = try? LocalEncryptedFileStore.read(from: fileURL) else { return nil }
            if !payload.wasEncrypted {
                try? LocalEncryptedFileStore.write(payload.data, to: fileURL)
            }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            return try? decoder.decode(BreadcrumbTrailSession.self, from: payload.data)
        }
    }

    func save(_ session: BreadcrumbTrailSession?) {
        queue.sync {
            guard let session else {
                try? fileManager.removeItem(at: fileURL)
                return
            }
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            encoder.dateEncodingStrategy = .iso8601
            guard let data = try? encoder.encode(session) else { return }
            try? LocalEncryptedFileStore.write(data, to: fileURL)
        }
    }
}
