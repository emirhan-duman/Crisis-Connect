@preconcurrency import CoreLocation
import Combine
import Foundation
import UIKit

@MainActor
final class BreadcrumbTrailManager: NSObject, ObservableObject {
    static let shared = BreadcrumbTrailManager()

    @Published private(set) var session: BreadcrumbTrailSession?
    @Published private(set) var currentPoint: BreadcrumbPoint?
    @Published private(set) var headingDegrees: CLLocationDirection?
    @Published private(set) var headingAccuracy: CLLocationDirection?
    @Published private(set) var offlineRegionName: String?
    @Published private(set) var completedOfflineRegionCount = 0
    @Published private(set) var errorKey: String?

    private enum PendingAction: Sendable {
        case startNew
        case resume
        case startReturn(BreadcrumbReturnTarget)
    }

    private let locationManager = CLLocationManager()
    private let store = BreadcrumbTrailStore()
    private let regionStore = OfflineRegionStore()
    private var completedRegions: [OfflineMapRegion] = []
    private var pendingAction: PendingAction?

    private let maxAcceptedAccuracy: CLLocationAccuracy = 75
    private let minimumPointDistance: CLLocationDistance = 8
    private let minimumPointInterval: TimeInterval = 4
    private let stationaryHeartbeat: TimeInterval = 45
    private let maximumPoints = 5_000

    override private init() {
        let loaded = store.load()
        session = loaded
        currentPoint = loaded?.points.last
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = minimumPointDistance
        locationManager.activityType = .fitness
        locationManager.pausesLocationUpdatesAutomatically = true
        locationManager.headingFilter = 2
        refreshOfflineMapAvailability()

        if loaded?.mode == .recording || loaded?.mode == .returning {
            Task { [weak self] in self?.resumeAfterProcessLaunchIfPossible() }
        }
    }

    var distanceToStart: CLLocationDistance? {
        guard let current = effectiveCurrent, let start = session?.points.first else { return nil }
        return BreadcrumbTrailMath.distance(current, start)
    }

    var distanceToSafe: CLLocationDistance? {
        guard let session, !session.points.isEmpty, let current = effectiveCurrent else { return nil }
        return BreadcrumbTrailMath.distance(
            current,
            session.points[min(max(session.safePointIndex, 0), session.points.count - 1)]
        )
    }

    var routeDistance: CLLocationDistance {
        BreadcrumbTrailMath.routeDistance(session?.points ?? [])
    }

    var nextBreadcrumbDistance: CLLocationDistance? {
        guard let session,
              let current = effectiveCurrent,
              let cursor = session.returnCursor,
              session.points.indices.contains(cursor) else { return nil }
        return BreadcrumbTrailMath.distance(current, session.points[cursor])
    }

    var targetBearing: CLLocationDirection? {
        guard let session,
              let current = effectiveCurrent,
              let cursor = session.returnCursor,
              session.points.indices.contains(cursor) else { return nil }
        return BreadcrumbTrailMath.bearing(from: current, to: session.points[cursor])
    }

    var remainingRouteDistance: CLLocationDistance? {
        guard let session,
              let current = effectiveCurrent,
              let cursor = session.returnCursor,
              !session.points.isEmpty else { return nil }
        return BreadcrumbTrailMath.remainingRouteDistance(
            points: session.points,
            current: current,
            cursor: cursor,
            destinationIndex: destinationIndex(for: session)
        )
    }

    var returnProgress: Double {
        guard let session else { return 0 }
        if session.mode == .arrived { return 1 }
        guard session.mode == .returning, let cursor = session.returnCursor else { return 0 }
        let destination = destinationIndex(for: session)
        let denominator = max(session.points.count - 1 - destination, 1)
        return min(max(Double(session.points.count - 1 - cursor) / Double(denominator), 0), 1)
    }

    var routeCoordinates: [CLLocationCoordinate2D] {
        sampledPoints(maximum: 250).map(\.coordinate)
    }

    func requestStartNew() {
        performWithAuthorization(.startNew)
    }

    func requestResume() {
        performWithAuthorization(.resume)
    }

    func requestReturn(to target: BreadcrumbReturnTarget) {
        guard let session, session.points.count >= 2 else {
            errorKey = "BREADCRUMB_ERROR_NOT_ENOUGH_POINTS"
            return
        }
        performWithAuthorization(.startReturn(target))
    }

    func pause() {
        guard var session else { return }
        session.mode = .paused
        session.returnCursor = nil
        updateSession(session)
        stopLocationEngine()
    }

    func clear() {
        stopLocationEngine()
        session = nil
        currentPoint = nil
        errorKey = nil
        offlineRegionName = nil
        store.save(nil)
    }

    func markCurrentAsSafe() {
        guard var session, !session.points.isEmpty else { return }
        session.safePointIndex = session.points.count - 1
        updateSession(session)
    }

    func clearError() {
        errorKey = nil
    }

    func refreshOfflineMapAvailability() {
        completedRegions = regionStore.load().filter { $0.status == .complete }
        completedOfflineRegionCount = completedRegions.count
        updateContainingRegion()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            guard let pendingAction else { return }
            self.pendingAction = nil
            performAuthorized(pendingAction)
        case .denied, .restricted:
            pendingAction = nil
            errorKey = "BREADCRUMB_ERROR_PERMISSION"
        case .notDetermined:
            break
        @unknown default:
            pendingAction = nil
            errorKey = "BREADCRUMB_ERROR_PERMISSION"
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        locations.forEach(accept)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code != .locationUnknown {
            errorKey = "BREADCRUMB_ERROR_LOCATION"
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let trueHeading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        guard trueHeading.isFinite, trueHeading >= 0 else { return }
        headingDegrees = trueHeading
        headingAccuracy = newHeading.headingAccuracy >= 0 ? newHeading.headingAccuracy : nil
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        (headingAccuracy ?? 180) > 25
    }

    private var effectiveCurrent: BreadcrumbPoint? {
        currentPoint ?? session?.points.last
    }

    private func performWithAuthorization(_ action: PendingAction) {
        errorKey = nil
        guard CLLocationManager.locationServicesEnabled() else {
            errorKey = "BREADCRUMB_ERROR_LOCATION_DISABLED"
            return
        }
        switch locationManager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            performAuthorized(action)
        case .notDetermined:
            pendingAction = action
            locationManager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            errorKey = "BREADCRUMB_ERROR_PERMISSION"
        @unknown default:
            errorKey = "BREADCRUMB_ERROR_PERMISSION"
        }
    }

    private func performAuthorized(_ action: PendingAction) {
        guard locationManager.accuracyAuthorization == .reducedAccuracy else {
            perform(action)
            return
        }
        pendingAction = action
        locationManager.requestTemporaryFullAccuracyAuthorization(
            withPurposeKey: "BreadcrumbTrail"
        ) { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                guard error == nil, self.locationManager.accuracyAuthorization == .fullAccuracy else {
                    self.pendingAction = nil
                    self.errorKey = "BREADCRUMB_ERROR_PRECISE_REQUIRED"
                    return
                }
                let requested = self.pendingAction ?? action
                self.pendingAction = nil
                self.perform(requested)
            }
        }
    }

    private func perform(_ action: PendingAction) {
        switch action {
        case .startNew:
            updateSession(
                BreadcrumbTrailSession(
                    id: UUID(),
                    startedAt: Date(),
                    points: [],
                    safePointIndex: 0,
                    mode: .recording,
                    returnTarget: .start,
                    returnCursor: nil
                )
            )
            currentPoint = nil
        case .resume:
            guard var session else { return }
            session.mode = .recording
            session.returnCursor = nil
            updateSession(session)
        case .startReturn(let target):
            guard var session else { return }
            let destination = target == .start ? 0 : session.safePointIndex
            guard let cursor = BreadcrumbTrailMath.initialReturnCursor(
                points: session.points,
                destinationIndex: destination
            ) else {
                errorKey = "BREADCRUMB_ERROR_NOT_ENOUGH_POINTS"
                return
            }
            session.mode = .returning
            session.returnTarget = target
            session.returnCursor = cursor
            updateSession(session)
        }
        startLocationEngine()
    }

    private func startLocationEngine() {
        guard locationManager.authorizationStatus == .authorizedAlways ||
                locationManager.authorizationStatus == .authorizedWhenInUse else { return }
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.showsBackgroundLocationIndicator = true
        locationManager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() {
            locationManager.startUpdatingHeading()
        }
    }

    private func stopLocationEngine() {
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
        locationManager.allowsBackgroundLocationUpdates = false
        locationManager.showsBackgroundLocationIndicator = false
    }

    private func resumeAfterProcessLaunchIfPossible() {
        if locationManager.authorizationStatus == .authorizedAlways ||
            locationManager.authorizationStatus == .authorizedWhenInUse {
            startLocationEngine()
        }
    }

    private func accept(_ location: CLLocation) {
        guard location.coordinate.latitude.isFinite,
              location.coordinate.longitude.isFinite,
              location.horizontalAccuracy > 0,
              location.horizontalAccuracy <= maxAcceptedAccuracy,
              abs(location.timestamp.timeIntervalSinceNow) < 120 else { return }

        let point = BreadcrumbPoint(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            altitudeMeters: location.verticalAccuracy >= 0 && location.altitude.isFinite ? location.altitude : nil,
            accuracyMeters: location.horizontalAccuracy,
            timestamp: location.timestamp
        )
        currentPoint = point
        guard var session else { return }

        switch session.mode {
        case .recording:
            if let last = session.points.last {
                let elapsed = point.timestamp.timeIntervalSince(last.timestamp)
                let distance = BreadcrumbTrailMath.distance(last, point)
                if elapsed < minimumPointInterval ||
                    (distance < minimumPointDistance && elapsed < stationaryHeartbeat) {
                    updateContainingRegion()
                    return
                }
            }
            session.points.append(point)
            compactIfNeeded(&session)
            updateSession(session)
        case .returning:
            guard let cursor = session.returnCursor, !session.points.isEmpty else { return }
            let destination = destinationIndex(for: session)
            let advanced = BreadcrumbTrailMath.advanceReturnCursor(
                points: session.points,
                current: point,
                cursor: cursor,
                destinationIndex: destination
            )
            session.returnCursor = advanced
            let arrived = advanced == destination &&
                BreadcrumbTrailMath.distance(point, session.points[destination]) <=
                max(BreadcrumbTrailMath.arrivalRadiusMeters, point.accuracyMeters)
            if arrived {
                session.mode = .arrived
                stopLocationEngine()
            }
            updateSession(session)
        case .paused, .arrived:
            break
        }
        updateContainingRegion()
    }

    private func compactIfNeeded(_ session: inout BreadcrumbTrailSession) {
        guard session.points.count > maximumPoints else { return }
        let originalPoints = session.points
        let safePoint = originalPoints.indices.contains(session.safePointIndex)
            ? originalPoints[session.safePointIndex]
            : originalPoints[0]
        session.points = originalPoints.enumerated().compactMap { index, point in
            index == 0 || index == originalPoints.count - 1 || index.isMultiple(of: 2) ? point : nil
        }
        session.safePointIndex = session.points.indices.min { lhs, rhs in
            BreadcrumbTrailMath.distance(session.points[lhs], safePoint) <
                BreadcrumbTrailMath.distance(session.points[rhs], safePoint)
        } ?? 0
    }

    private func destinationIndex(for session: BreadcrumbTrailSession) -> Int {
        guard !session.points.isEmpty else { return 0 }
        return session.returnTarget == .start
            ? 0
            : min(max(session.safePointIndex, 0), session.points.count - 1)
    }

    private func updateSession(_ updated: BreadcrumbTrailSession) {
        session = updated
        store.save(updated)
        updateContainingRegion()
    }

    private func updateContainingRegion() {
        guard let point = effectiveCurrent ?? session?.points.first else {
            offlineRegionName = nil
            return
        }
        offlineRegionName = completedRegions.first { region in
            let bounds = region.bounds.normalized
            guard point.latitude >= bounds.south && point.latitude <= bounds.north else { return false }
            if bounds.west <= bounds.east {
                return point.longitude >= bounds.west && point.longitude <= bounds.east
            }
            return point.longitude >= bounds.west || point.longitude <= bounds.east
        }?.name
    }

    private func sampledPoints(maximum: Int) -> [BreadcrumbPoint] {
        let points = session?.points ?? []
        guard points.count > maximum, maximum > 1 else { return points }
        let strideValue = Double(points.count - 1) / Double(maximum - 1)
        var result: [BreadcrumbPoint] = []
        var index = 0.0
        while Int(index) < points.count - 1 {
            result.append(points[min(Int(index), points.count - 1)])
            index += strideValue
        }
        if result.last != points.last { result.append(points[points.count - 1]) }
        return result
    }
}

extension BreadcrumbTrailManager: @preconcurrency CLLocationManagerDelegate {}
