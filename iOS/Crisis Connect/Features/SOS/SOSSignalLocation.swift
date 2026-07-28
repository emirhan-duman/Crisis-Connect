//
//  SOSSignalLocation.swift
//  Crisis Connect
//
//  Created by Codex on 18.03.2026.
//

import Foundation
import CoreLocation

struct SOSSignalLocationPayload: Sendable, Equatable, Codable {
    struct GPSPayload: Sendable, Equatable, Codable {
        let latitude: Double
        let longitude: Double
        let horizontalAccuracyMeters: Double?
        let provider: String
        let capturedAt: Date

        var capturedAtMillis: Int64 {
            Int64((capturedAt.timeIntervalSince1970 * 1_000).rounded())
        }

        func toJSON() -> [String: Any] {
            var json: [String: Any] = [
                "lat": latitude,
                "lon": longitude,
                "provider": provider,
                "capturedAtMillis": capturedAtMillis
            ]
            if let horizontalAccuracyMeters {
                json["accuracyMeters"] = horizontalAccuracyMeters
            }
            return json
        }
    }

    struct RelativeEstimate: Sendable, Equatable, Codable {
        let anchorGPS: GPSPayload
        let distanceMeters: Double
        let bearingDegrees: Double?
        let confidenceRadiusMeters: Double
        let bleRSSI: Int?
        let headingAccuracyDegrees: Double?
        let source: String
        let capturedAt: Date

        var capturedAtMillis: Int64 {
            Int64((capturedAt.timeIntervalSince1970 * 1_000).rounded())
        }

        func toJSON() -> [String: Any] {
            var json: [String: Any] = [
                "anchorGps": anchorGPS.toJSON(),
                "distanceMeters": distanceMeters,
                "confidenceRadiusMeters": confidenceRadiusMeters,
                "source": source,
                "capturedAtMillis": capturedAtMillis
            ]
            if let bearingDegrees {
                json["bearingDegrees"] = bearingDegrees
            }
            if let bleRSSI {
                json["bleRssi"] = bleRSSI
            }
            if let headingAccuracyDegrees {
                json["headingAccuracyDegrees"] = headingAccuracyDegrees
            }
            return json
        }
    }

    let gps: GPSPayload?
    let relativeEstimate: RelativeEstimate?

    func toPeerInfoJSON() -> [String: Any]? {
        guard gps != nil || relativeEstimate != nil else { return nil }
        var json: [String: Any] = [:]
        if let gps {
            json["gps"] = gps.toJSON()
        }
        if let relativeEstimate {
            json["relativeEstimate"] = relativeEstimate.toJSON()
        }
        return json
    }

    static func fromPeerInfoJSON(_ value: Any?) -> SOSSignalLocationPayload? {
        guard let json = value as? [String: Any] else { return nil }
        let gps = GPSPayload.fromJSON(json["gps"])
        let relativeEstimate = RelativeEstimate.fromJSON(json["relativeEstimate"])
        if gps == nil && relativeEstimate == nil {
            return nil
        }
        return SOSSignalLocationPayload(gps: gps, relativeEstimate: relativeEstimate)
    }

    static func fromLegacyLocationJSON(_ value: Any?) -> SOSSignalLocationPayload? {
        guard let gps = GPSPayload.fromJSON(value) else { return nil }
        return SOSSignalLocationPayload(gps: gps, relativeEstimate: nil)
    }
}

extension SOSSignalLocationPayload.GPSPayload {
    static func fromJSON(_ value: Any?) -> Self? {
        guard let json = value as? [String: Any],
              let latitude = numericValue(json["lat"]),
              let longitude = numericValue(json["lon"]),
              latitude.isFinite,
              longitude.isFinite,
              (-90.0...90.0).contains(latitude),
              (-180.0...180.0).contains(longitude) else {
            return nil
        }

        let provider = (json["provider"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? "unknown"
        let capturedAtMillis = numericValue(json["capturedAtMillis"]).map { Int64($0.rounded()) }
        let capturedAt = capturedAtMillis.flatMap { millis in
            guard millis > 0 else { return nil }
            return Date(timeIntervalSince1970: TimeInterval(millis) / 1_000)
        } ?? Date()
        let accuracy = numericValue(json["accuracyMeters"])

        return Self(
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: accuracy.flatMap { $0 > 0 ? $0 : nil },
            provider: provider,
            capturedAt: capturedAt
        )
    }
}

extension SOSSignalLocationPayload.RelativeEstimate {
    static func fromJSON(_ value: Any?) -> Self? {
        guard let json = value as? [String: Any],
              let anchorGPS = SOSSignalLocationPayload.GPSPayload.fromJSON(json["anchorGps"]),
              let distanceMeters = numericValue(json["distanceMeters"]),
              distanceMeters.isFinite,
              distanceMeters >= 0,
              let confidenceRadiusMeters = numericValue(json["confidenceRadiusMeters"]),
              confidenceRadiusMeters.isFinite,
              confidenceRadiusMeters > 0 else {
            return nil
        }

        let bearingDegrees = numericValue(json["bearingDegrees"])
        let bleRSSI = numericValue(json["bleRssi"]).map { Int($0.rounded()) }
        let headingAccuracyDegrees = numericValue(json["headingAccuracyDegrees"])
        let source = (json["source"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? "unknown"
        let capturedAtMillis = numericValue(json["capturedAtMillis"]).map { Int64($0.rounded()) }
        let capturedAt = capturedAtMillis.flatMap { millis in
            guard millis > 0 else { return nil }
            return Date(timeIntervalSince1970: TimeInterval(millis) / 1_000)
        } ?? Date()

        return Self(
            anchorGPS: anchorGPS,
            distanceMeters: distanceMeters,
            bearingDegrees: bearingDegrees,
            confidenceRadiusMeters: confidenceRadiusMeters,
            bleRSSI: bleRSSI,
            headingAccuracyDegrees: headingAccuracyDegrees,
            source: source,
            capturedAt: capturedAt
        )
    }
}

final class SOSSignalLocationCoordinator: NSObject, CLLocationManagerDelegate {
    static let shared = SOSSignalLocationCoordinator()

    var onBroadcastLocationChange: ((SOSSignalLocationPayload.GPSPayload?) -> Void)?

    private let locationManager = CLLocationManager()
    private var latestLocation: CLLocation?
    private var latestHeading: CLHeading?
    private var broadcastingSessionCount = 0
    private var locationRequests: [UUID: CheckedContinuation<CLLocation?, Never>] = [:]
    private var headingRequests: [UUID: CheckedContinuation<CLHeading?, Never>] = [:]
    private var headingUpdatesActive = false

    private override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.headingFilter = kCLHeadingFilterNone
        locationManager.headingOrientation = .portrait
    }

    func beginBroadcasting() {
        broadcastingSessionCount += 1
        refreshMonitoring()
        requestVictimLocationIfPossible()
    }

    func endBroadcasting() {
        broadcastingSessionCount = max(0, broadcastingSessionCount - 1)
        refreshMonitoring()
    }

    func currentVictimLocationPayload() -> SOSSignalLocationPayload.GPSPayload? {
        guard canShareSOSLocation else { return nil }
        return gpsPayload(from: latestLocation ?? locationManager.location, providerOverride: "ios-corelocation")
    }

    func currentReporterLocationPayload() -> SOSSignalLocationPayload.GPSPayload? {
        guard canShareSOSLocation else { return nil }
        return gpsPayload(from: latestLocation ?? locationManager.location, providerOverride: "ios-corelocation")
    }

    func requestReporterLocationPayload() async -> SOSSignalLocationPayload.GPSPayload? {
        guard let location = await requestLocationSnapshot(maxAge: 120, timeout: 2.5) else {
            return currentReporterLocationPayload()
        }
        return gpsPayload(from: location, providerOverride: "ios-corelocation")
    }

    func requestRelativeEstimate(rssi: Int?) async -> SOSSignalLocationPayload? {
        guard canShareSOSLocation else { return nil }
        guard let anchorLocation = await requestLocationSnapshot(maxAge: 120, timeout: 2.5),
              let anchorGPS = gpsPayload(from: anchorLocation, providerOverride: "ios-corelocation") else {
            return nil
        }

        let heading = await requestHeadingSnapshot(timeout: 2.0)
        let bearingDegrees = resolvedBearingDegrees(from: heading)
        let distanceMeters = estimatedDistanceMeters(from: rssi)
        let headingAccuracy = heading.flatMap { sample in
            sample.headingAccuracy >= 0 ? sample.headingAccuracy : nil
        }
        let confidenceRadius = estimatedConfidenceRadius(
            anchorAccuracyMeters: anchorGPS.horizontalAccuracyMeters,
            distanceMeters: distanceMeters,
            headingAccuracyDegrees: headingAccuracy,
            hasBearing: bearingDegrees != nil,
            rssi: rssi
        )

        let resolvedGPS: SOSSignalLocationPayload.GPSPayload
        if let bearingDegrees {
            let coordinate = displacedCoordinate(
                from: anchorLocation.coordinate,
                bearingDegrees: bearingDegrees,
                distanceMeters: distanceMeters
            )
            resolvedGPS = SOSSignalLocationPayload.GPSPayload(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
                horizontalAccuracyMeters: confidenceRadius,
                provider: "ios-heading-rssi-estimate",
                capturedAt: Date()
            )
        } else {
            resolvedGPS = SOSSignalLocationPayload.GPSPayload(
                latitude: anchorGPS.latitude,
                longitude: anchorGPS.longitude,
                horizontalAccuracyMeters: confidenceRadius,
                provider: "ios-anchor-estimate",
                capturedAt: Date()
            )
        }

        let relativeEstimate = SOSSignalLocationPayload.RelativeEstimate(
            anchorGPS: anchorGPS,
            distanceMeters: distanceMeters,
            bearingDegrees: bearingDegrees,
            confidenceRadiusMeters: confidenceRadius,
            bleRSSI: rssi,
            headingAccuracyDegrees: headingAccuracy,
            source: bearingDegrees == nil ? "anchor_only" : "heading_rssi",
            capturedAt: Date()
        )
        return SOSSignalLocationPayload(gps: resolvedGPS, relativeEstimate: relativeEstimate)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        refreshMonitoring()
        if canShareSOSLocation {
            requestVictimLocationIfPossible()
        } else {
            onBroadcastLocationChange?(nil)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        latestLocation = location
        let payload = gpsPayload(from: location, providerOverride: "ios-corelocation")
        onBroadcastLocationChange?(payload)

        let pending = locationRequests
        locationRequests.removeAll()
        pending.values.forEach { $0.resume(returning: location) }

        if broadcastingSessionCount == 0 {
            manager.stopUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        latestHeading = newHeading
        let pending = headingRequests
        headingRequests.removeAll()
        pending.values.forEach { $0.resume(returning: newHeading) }
        if broadcastingSessionCount == 0 {
            stopHeadingUpdatesIfNeeded()
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let pending = locationRequests
        locationRequests.removeAll()
        pending.values.forEach { $0.resume(returning: nil) }
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        false
    }

    private var canShareSOSLocation: Bool {
        let status = locationManager.authorizationStatus
        let authorized = status == .authorizedAlways || status == .authorizedWhenInUse
        return authorized && PrivacyPreferences.isShareLocationInSOSEnabled()
    }

    private func refreshMonitoring() {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            if broadcastingSessionCount > 0 {
                locationManager.startUpdatingLocation()
            } else if locationRequests.isEmpty {
                locationManager.stopUpdatingLocation()
            }
        case .denied, .restricted:
            locationManager.stopUpdatingLocation()
            stopHeadingUpdatesIfNeeded(force: true)
        @unknown default:
            break
        }
    }

    private func requestVictimLocationIfPossible() {
        guard canShareSOSLocation else { return }
        if let payload = currentVictimLocationPayload() {
            onBroadcastLocationChange?(payload)
        } else {
            locationManager.requestLocation()
        }
    }

    private func requestLocationSnapshot(maxAge: TimeInterval, timeout: TimeInterval) async -> CLLocation? {
        guard canShareSOSLocation else { return nil }
        if let current = latestLocation ?? locationManager.location,
           isLocationUsable(current, maxAge: maxAge) {
            return current
        }

        let token = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                locationRequests[token] = continuation
                locationManager.startUpdatingLocation()
                locationManager.requestLocation()
                DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                    guard let self,
                          let continuation = self.locationRequests.removeValue(forKey: token) else {
                        return
                    }
                    continuation.resume(returning: self.latestLocation ?? self.locationManager.location)
                    if self.broadcastingSessionCount == 0 {
                        self.locationManager.stopUpdatingLocation()
                    }
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                guard let self,
                      let continuation = self.locationRequests.removeValue(forKey: token) else {
                    return
                }
                continuation.resume(returning: self.latestLocation ?? self.locationManager.location)
            }
        }
    }

    private func requestHeadingSnapshot(timeout: TimeInterval) async -> CLHeading? {
        guard CLLocationManager.headingAvailable() else { return nil }
        if let latestHeading, isHeadingUsable(latestHeading) {
            return latestHeading
        }

        let token = UUID()
        startHeadingUpdatesIfNeeded()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                headingRequests[token] = continuation
                DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                    guard let self,
                          let continuation = self.headingRequests.removeValue(forKey: token) else {
                        return
                    }
                    continuation.resume(returning: self.latestHeading)
                    if self.broadcastingSessionCount == 0 {
                        self.stopHeadingUpdatesIfNeeded()
                    }
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let continuation = self.headingRequests.removeValue(forKey: token) {
                    continuation.resume(returning: self.latestHeading)
                }
                if self.broadcastingSessionCount == 0 {
                    self.stopHeadingUpdatesIfNeeded()
                }
            }
        }
    }

    private func startHeadingUpdatesIfNeeded() {
        guard !headingUpdatesActive else { return }
        headingUpdatesActive = true
        locationManager.startUpdatingHeading()
    }

    private func stopHeadingUpdatesIfNeeded(force: Bool = false) {
        guard headingUpdatesActive else { return }
        guard force || headingRequests.isEmpty else { return }
        headingUpdatesActive = false
        locationManager.stopUpdatingHeading()
    }

    private func gpsPayload(
        from location: CLLocation?,
        providerOverride: String
    ) -> SOSSignalLocationPayload.GPSPayload? {
        guard let location else { return nil }
        guard isLocationUsable(location, maxAge: 300) else { return nil }
        let latitude = location.coordinate.latitude
        let longitude = location.coordinate.longitude
        guard latitude.isFinite,
              longitude.isFinite,
              (-90.0...90.0).contains(latitude),
              (-180.0...180.0).contains(longitude) else {
            return nil
        }

        return SOSSignalLocationPayload.GPSPayload(
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
            provider: providerOverride,
            capturedAt: location.timestamp.timeIntervalSince1970 > 0 ? location.timestamp : Date()
        )
    }

    private func isLocationUsable(_ location: CLLocation, maxAge: TimeInterval) -> Bool {
        let latitude = location.coordinate.latitude
        let longitude = location.coordinate.longitude
        guard latitude.isFinite,
              longitude.isFinite,
              (-90.0...90.0).contains(latitude),
              (-180.0...180.0).contains(longitude) else {
            return false
        }
        let age = Date().timeIntervalSince(location.timestamp)
        return age.isFinite && age <= maxAge
    }

    private func isHeadingUsable(_ heading: CLHeading) -> Bool {
        let resolved = resolvedBearingDegrees(from: heading)
        guard resolved != nil else { return false }
        return heading.headingAccuracy < 0 || heading.headingAccuracy <= 120
    }

    private func resolvedBearingDegrees(from heading: CLHeading?) -> Double? {
        guard let heading else { return nil }
        let value = heading.trueHeading >= 0 ? heading.trueHeading : heading.magneticHeading
        guard value.isFinite else { return nil }
        let normalized = value.truncatingRemainder(dividingBy: 360)
        return normalized >= 0 ? normalized : normalized + 360
    }

    private func estimatedDistanceMeters(from rssi: Int?) -> Double {
        guard let rssi else { return 6.0 }
        let txPower = -59.0
        let pathLossExponent = 2.2
        let distance = pow(10.0, (txPower - Double(rssi)) / (10.0 * pathLossExponent))
        return distance.isFinite ? min(max(distance, 1.5), 30.0) : 6.0
    }

    private func estimatedConfidenceRadius(
        anchorAccuracyMeters: Double?,
        distanceMeters: Double,
        headingAccuracyDegrees: Double?,
        hasBearing: Bool,
        rssi: Int?
    ) -> Double {
        let anchor = min(max(anchorAccuracyMeters ?? 18.0, 6.0), 220.0)
        let distancePenalty = min(max(distanceMeters * 1.35, 8.0), 120.0)
        let headingPenalty: Double
        if hasBearing {
            headingPenalty = min(max((headingAccuracyDegrees ?? 25.0) * 0.65, 6.0), 80.0)
        } else {
            headingPenalty = 110.0
        }
        let blePenalty: Double
        switch rssi ?? -80 {
        case let value where value >= -60:
            blePenalty = 8.0
        case let value where value >= -70:
            blePenalty = 14.0
        case let value where value >= -80:
            blePenalty = 24.0
        default:
            blePenalty = 34.0
        }
        return min(max(anchor + distancePenalty + headingPenalty + blePenalty, 18.0), 360.0)
    }

    private func displacedCoordinate(
        from coordinate: CLLocationCoordinate2D,
        bearingDegrees: Double,
        distanceMeters: Double
    ) -> CLLocationCoordinate2D {
        let earthRadiusMeters = 6_378_137.0
        let distanceRatio = distanceMeters / earthRadiusMeters
        let bearingRadians = bearingDegrees * .pi / 180.0
        let latitudeRadians = coordinate.latitude * .pi / 180.0
        let longitudeRadians = coordinate.longitude * .pi / 180.0

        let displacedLatitude = latitudeRadians + (distanceRatio * cos(bearingRadians))
        let cosLatitude = max(cos(latitudeRadians), 1e-6)
        let displacedLongitude = longitudeRadians + (distanceRatio * sin(bearingRadians) / cosLatitude)
        return CLLocationCoordinate2D(
            latitude: displacedLatitude * 180.0 / .pi,
            longitude: displacedLongitude * 180.0 / .pi
        )
    }
}

private func numericValue(_ value: Any?) -> Double? {
    switch value {
    case let value as Double where value.isFinite:
        return value
    case let value as Float where value.isFinite:
        return Double(value)
    case let value as Int:
        return Double(value)
    case let value as Int64:
        return Double(value)
    case let value as NSNumber:
        let resolved = value.doubleValue
        return resolved.isFinite ? resolved : nil
    default:
        return nil
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
