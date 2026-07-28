//
//  CrisisLinkDashboardSync.swift
//  Crisis Connect
//
//  Created by Assistant on 27.03.2026.
//

import Foundation
import FirebaseAuth
import FirebaseFirestore

/// Live counters the responder documents report to the dashboard. Android has always synced real
/// numbers; iOS hardcoded every one of these to 0, so the panel showed a roster of rescuers who had
/// apparently never seen a single signal. Updated by RescueClientManager as adverts arrive.
final class ResponderSignalStats: @unchecked Sendable {
    static let shared = ResponderSignalStats()
    private let lock = NSLock()
    private var uniqueSignalIds: Set<String> = []
    private var active = 0
    private var scanEvents = 0

    func recordScanEvent() {
        lock.lock(); scanEvents += 1; lock.unlock()
    }

    func update(activeCount: Int, signalIds: [String]) {
        lock.lock()
        active = activeCount
        uniqueSignalIds.formUnion(signalIds)
        lock.unlock()
    }

    var snapshot: (active: Int, totalUnique: Int, scanEvents: Int) {
        lock.lock(); defer { lock.unlock() }
        return (active, uniqueSignalIds.count, scanEvents)
    }
}

struct CrisisLinkDashboardLocationPayload: Sendable, Codable, Equatable {
    let latitude: Double
    let longitude: Double
    let horizontalAccuracyMeters: Double?
    let capturedAt: Date
}

@MainActor
final class CrisisLinkDashboardSyncService {
    typealias LocationPayload = CrisisLinkDashboardLocationPayload

    private struct RescuerProfile {
        let uid: String
        let role: String
        let agency: String
        let agencySlug: String
        let operatorName: String
        let username: String
        let country: String
    }

    static let shared = CrisisLinkDashboardSyncService()

    private let secureStore = SecureLocalStore.shared
    private lazy var auth: Auth = {
        FirebaseRuntime.ensureConfigured()
        return Auth.auth()
    }()
    private lazy var firestore: Firestore = {
        FirebaseRuntime.ensureConfigured()
        return Firestore.firestore()
    }()

    private var cachedProfile: RescuerProfile?
    private var profileFetchedAt: Date = .distantPast
    private var signalFirstSeenMillisById: [String: Int64] = [:]

    func syncLiveLocation(payload: LocationPayload) async -> Bool {
        guard let profile = await resolveRescuerProfile() else { return false }

        let responderDeviceId = secureStore.getOrCreateRescueDeviceId()
        let panelRef = firestore.collection(Self.panelCollection).document(profile.agencySlug)
        let responderRef = panelRef.collection(Self.respondersCollection).document(responderDeviceId)
        let rescueDeviceRef = firestore.collection(Self.rescueDevicesCollection).document(responderDeviceId)
        do {
            try await rescueDeviceRef.setDataAsync([
                "deviceId": responderDeviceId,
                "uid": profile.uid,
                "updatedAt": FieldValue.serverTimestamp()
            ], merge: true)
        } catch {
            NSLog("Failed to register Crisis Link rescue device: %@", String(describing: error))
            return false
        }

        let batch = firestore.batch()
        batch.setData([
            "agency": profile.agency,
            "agencySlug": profile.agencySlug,
            "schemaVersion": Self.panelSchemaVersion,
            "source": Self.panelSource,
            "activeSignals": ResponderSignalStats.shared.snapshot.active,
            "totalSignalsObserved": ResponderSignalStats.shared.snapshot.totalUnique,
            "updatedByDeviceId": responderDeviceId,
            "updatedByUid": profile.uid,
            "updatedAt": FieldValue.serverTimestamp()
        ], forDocument: panelRef, merge: true)

        var responderData: [String: Any] = [
            "responderDeviceId": responderDeviceId,
            "uid": profile.uid,
            "agency": profile.agency,
            "role": profile.role,
            "schemaVersion": Self.responderSchemaVersion,
            "crisisLinkEnabled": true,
            "activeSignalCount": ResponderSignalStats.shared.snapshot.active,
            "totalUniqueSignalCount": ResponderSignalStats.shared.snapshot.totalUnique,
            "totalScanEventCount": ResponderSignalStats.shared.snapshot.scanEvents,
            "updatedAt": FieldValue.serverTimestamp(),
            "gps": gpsMap(for: payload)
        ]

        if !profile.operatorName.isEmpty {
            responderData["name"] = profile.operatorName
        }
        if !profile.username.isEmpty {
            responderData["username"] = profile.username
        }
        if !profile.country.isEmpty {
            responderData["country"] = profile.country
        }

        batch.setData(responderData, forDocument: responderRef, merge: true)

        do {
            try await batch.commitAsync()
        } catch {
            NSLog("Crisis Link dashboard sync failed: %@", String(describing: error))
            return false
        }
        return true
    }

    /// Syncs one victim sighting. `signalLocation` nil = an UNLOCATED sighting: Android has always
    /// synced those (firstSeen/lastSeen still tell the dashboard a live victim exists); iOS dropped
    /// them entirely, so a rescuer without a fix contributed nothing to the panel. Returns whether
    /// the write committed, so the caller can queue the observation instead of losing it.
    @discardableResult
    func syncSignalLocation(
        signalId: String,
        signalLocation: SOSSignalLocationPayload?,
        reporterLocation: LocationPayload?,
        victimName: String? = nil,
        victimBatteryPercent: Int? = nil
    ) async -> Bool {
        guard let normalizedSignalId = normalizedSignalId(signalId),
              let profile = await resolveRescuerProfile() else {
            return false
        }
        let resolvedGPS = signalLocation?.gps

        let responderDeviceId = secureStore.getOrCreateRescueDeviceId()
        let rescueDeviceRef = firestore.collection(Self.rescueDevicesCollection).document(responderDeviceId)
        do {
            try await rescueDeviceRef.setDataAsync([
                "deviceId": responderDeviceId,
                "uid": profile.uid,
                "updatedAt": FieldValue.serverTimestamp()
            ], merge: true)
        } catch {
            NSLog("Failed to register Crisis Link rescue device for signal sync: %@", String(describing: error))
            return false
        }

        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        let firstSeenMillis = signalFirstSeenMillisById[normalizedSignalId] ?? nowMillis
        signalFirstSeenMillisById[normalizedSignalId] = firstSeenMillis

        let signalRef = firestore
            .collection(Self.panelCollection)
            .document(profile.agencySlug)
            .collection(Self.signalsCollection)
            .document(normalizedSignalId)
        let reporterRef = signalRef
            .collection(Self.reportersCollection)
            .document(responderDeviceId)

        let signalLocationSource = signalLocation?.relativeEstimate == nil
            ? Self.signalLocationSourceVictimGPS
            : Self.signalLocationSourceRelativeEstimate

        var signalData: [String: Any] = [
            "signalId": normalizedSignalId,
            "schemaVersion": Self.signalSchemaVersion,
            "status": "active",
            "source": "ble",
            "firstSeenMillis": firstSeenMillis,
            "lastSeenMillis": nowMillis,
            "lastReporterUid": profile.uid,
            "lastReporterDeviceId": responderDeviceId,
            "victimRole": "victim",
            "reporterIds": FieldValue.arrayUnion([responderDeviceId]),
            "lastSeenAt": FieldValue.serverTimestamp()
        ]
        if let victimName = victimName?.trimmingCharacters(in: .whitespacesAndNewlines),
           !victimName.isEmpty {
            signalData["victimName"] = victimName
            signalData["victimDisplayName"] = victimName
        }
        if let victimBatteryPercent, (0...100).contains(victimBatteryPercent) {
            signalData["victimBatteryPercent"] = victimBatteryPercent
        }
        if let resolvedGPS {
            signalData["gps"] = gpsMap(for: resolvedGPS)
            signalData["locationSource"] = signalLocationSource
            if signalLocation?.relativeEstimate == nil {
                signalData["victimGps"] = gpsMap(for: resolvedGPS)
            } else {
                signalData["estimatedVictimGps"] = gpsMap(for: resolvedGPS)
            }
        }
        if let relativeEstimate = signalLocation?.relativeEstimate {
            signalData["relativeEstimate"] = relativeEstimateMap(for: relativeEstimate)
        }
        if let reporterLocation = reporterLocation {
            signalData["lastReporterLocation"] = gpsMap(for: reporterLocation)
        } else if let anchorGPS = signalLocation?.relativeEstimate?.anchorGPS {
            signalData["lastReporterLocation"] = gpsMap(for: anchorGPS)
        }

        var reporterData: [String: Any] = [
            "responderDeviceId": responderDeviceId,
            "uid": profile.uid,
            "agency": profile.agency,
            "lastSeenMillis": nowMillis,
            "lastSeenAt": FieldValue.serverTimestamp()
        ]
        if let resolvedGPS {
            reporterData["signalLocationSource"] = signalLocationSource
            reporterData["signalGps"] = gpsMap(for: resolvedGPS)
        }
        if let victimName = victimName?.trimmingCharacters(in: .whitespacesAndNewlines),
           !victimName.isEmpty {
            reporterData["signalVictimName"] = victimName
            reporterData["signalVictimDisplayName"] = victimName
        }
        if let victimBatteryPercent, (0...100).contains(victimBatteryPercent) {
            reporterData["signalVictimBatteryPercent"] = victimBatteryPercent
        }
        if let reporterLocation = reporterLocation {
            reporterData["gps"] = gpsMap(for: reporterLocation)
        } else if let anchorGPS = signalLocation?.relativeEstimate?.anchorGPS {
            reporterData["gps"] = gpsMap(for: anchorGPS)
        }
        if let relativeEstimate = signalLocation?.relativeEstimate {
            reporterData["relativeEstimate"] = relativeEstimateMap(for: relativeEstimate)
        }
        if !profile.operatorName.isEmpty {
            reporterData["name"] = profile.operatorName
        }
        if !profile.username.isEmpty {
            reporterData["username"] = profile.username
        }
        if !profile.country.isEmpty {
            reporterData["country"] = profile.country
        }

        let batch = firestore.batch()
        batch.setData(signalData, forDocument: signalRef, merge: true)
        batch.setData(reporterData, forDocument: reporterRef, merge: true)

        do {
            try await batch.commitAsync()
            return true
        } catch {
            NSLog("Crisis Link signal sync failed: %@", String(describing: error))
            return false
        }
    }

    func markResponderInactive() async -> Bool {
        guard let profile = await resolveRescuerProfile(allowCachedOnFailure: true) else { return false }

        let responderDeviceId = secureStore.getOrCreateRescueDeviceId()
        let responderRef = firestore
            .collection(Self.panelCollection)
            .document(profile.agencySlug)
            .collection(Self.respondersCollection)
            .document(responderDeviceId)

        var data: [String: Any] = [
            "uid": profile.uid,
            "agency": profile.agency,
            "role": profile.role,
            "schemaVersion": Self.responderSchemaVersion,
            "responderDeviceId": responderDeviceId,
            "crisisLinkEnabled": false,
            // Going inactive zeroes the ACTIVE count by definition, but the totals are history —
            // zeroing them erased the shift's work from the panel the moment the rescuer clocked off.
            "activeSignalCount": 0,
            "totalUniqueSignalCount": ResponderSignalStats.shared.snapshot.totalUnique,
            "totalScanEventCount": ResponderSignalStats.shared.snapshot.scanEvents,
            "updatedAt": FieldValue.serverTimestamp()
        ]

        if !profile.operatorName.isEmpty {
            data["name"] = profile.operatorName
        }
        if !profile.username.isEmpty {
            data["username"] = profile.username
        }
        if !profile.country.isEmpty {
            data["country"] = profile.country
        }

        do {
            try await responderRef.setDataAsync(data, merge: true)
        } catch {
            NSLog("Failed to mark Crisis Link responder inactive: %@", String(describing: error))
            return false
        }
        return true
    }

    private func resolveRescuerProfile(allowCachedOnFailure: Bool = false) async -> RescuerProfile? {
        let uid = auth.currentUser?.uid ?? secureStore.loadUid()
        guard let uid, !uid.isEmpty else { return nil }

        let now = Date()
        if let cachedProfile,
           cachedProfile.uid == uid,
           now.timeIntervalSince(profileFetchedAt) < Self.profileCacheTTL {
            return cachedProfile
        }

        do {
            let snapshot = try await firestore.collection("users").document(uid).getDocumentAsync()
            guard snapshot.exists else {
                if allowCachedOnFailure, let cachedProfile, cachedProfile.uid == uid {
                    return cachedProfile
                }
                return nil
            }
            guard snapshot.get("verified") as? Bool == true else {
                cachedProfile = nil
                return nil
            }

            let role = RescueRoleAccess.normalizedRole(snapshot.get("role") as? String)
                ?? RescueRoleAccess.normalizedRole(secureStore.loadRole())
            guard let role else {
                cachedProfile = nil
                return nil
            }

            let remoteAgency = firstNonBlank(
                snapshot.get("agency") as? String,
                snapshot.get("agencyName") as? String,
                snapshot.get("authority") as? String,
                snapshot.get("institution") as? String,
                snapshot.get("organization") as? String
            )
            let remoteAgencySlug = firstNonBlank(
                snapshot.get("agencySlug") as? String,
                snapshot.get("panelSlug") as? String,
                snapshot.get("panelId") as? String,
                snapshot.get("agencyPanelId") as? String,
                snapshot.get("agencyKey") as? String,
                snapshot.get("panelKey") as? String
            )
            let remoteUsername = firstNonBlank(
                snapshot.get("username") as? String,
                snapshot.get("displayName") as? String
            )
            let remoteEmail = firstNonBlank(
                snapshot.get("email") as? String,
                auth.currentUser?.email
            )
            let authDisplayName = auth.currentUser?.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let localCountry = ProfileMetadataStore.loadCountry()
            let remoteCountry = ProfileMetadataStore.normalizeCountryCode(snapshot.get("country") as? String)
            let country = firstNonBlank(remoteCountry, localCountry)
            let localAgency = ProfileMetadataStore.loadAgency()
            let agency = firstNonBlank(
                remoteAgency,
                ProfileMetadataStore.resolvedAgency(
                    email: remoteEmail,
                    country: country,
                    explicitAgency: localAgency
                ),
                "International"
            )
            let agencySlug = firstNonBlank(
                remoteAgencySlug,
                AgencyRouter.slugify(agency)
            )
            guard !agencySlug.isEmpty else {
                cachedProfile = nil
                return nil
            }

            let operatorName = firstNonBlank(
                remoteUsername,
                authDisplayName,
                remoteEmail.split(separator: "@").first.map(String.init),
                cachedProfile?.operatorName,
                "Responder \(uid.suffix(6).uppercased())"
            )
            let username = firstNonBlank(
                remoteUsername,
                authDisplayName,
                remoteEmail.split(separator: "@").first.map(String.init),
                cachedProfile?.username
            )

            let profile = RescuerProfile(
                uid: uid,
                role: role,
                agency: agency,
                agencySlug: agencySlug,
                operatorName: operatorName,
                username: username,
                country: country
            )
            cachedProfile = profile
            profileFetchedAt = now
            return profile
        } catch {
            if allowCachedOnFailure, let cachedProfile, cachedProfile.uid == uid {
                return cachedProfile
            }
            return nil
        }
    }

    private func gpsMap(for payload: LocationPayload) -> [String: Any] {
        var gps: [String: Any] = [
            "lat": payload.latitude,
            "lon": payload.longitude,
            "provider": "ios-corelocation",
            "capturedAtMillis": Int64(payload.capturedAt.timeIntervalSince1970 * 1_000)
        ]
        if let horizontalAccuracyMeters = payload.horizontalAccuracyMeters, horizontalAccuracyMeters > 0 {
            gps["accuracyMeters"] = horizontalAccuracyMeters
        }
        return gps
    }

    private func gpsMap(for payload: SOSSignalLocationPayload.GPSPayload) -> [String: Any] {
        var gps: [String: Any] = [
            "lat": payload.latitude,
            "lon": payload.longitude,
            "provider": payload.provider,
            "capturedAtMillis": payload.capturedAtMillis
        ]
        if let horizontalAccuracyMeters = payload.horizontalAccuracyMeters, horizontalAccuracyMeters > 0 {
            gps["accuracyMeters"] = horizontalAccuracyMeters
        }
        return gps
    }

    private func relativeEstimateMap(for payload: SOSSignalLocationPayload.RelativeEstimate) -> [String: Any] {
        var map: [String: Any] = [
            "anchorGps": gpsMap(for: payload.anchorGPS),
            "distanceMeters": payload.distanceMeters,
            "confidenceRadiusMeters": payload.confidenceRadiusMeters,
            "source": payload.source,
            "capturedAtMillis": payload.capturedAtMillis
        ]
        if let bearingDegrees = payload.bearingDegrees {
            map["bearingDegrees"] = bearingDegrees
        }
        if let bleRSSI = payload.bleRSSI {
            map["bleRssi"] = bleRSSI
        }
        if let headingAccuracyDegrees = payload.headingAccuracyDegrees {
            map["headingAccuracyDegrees"] = headingAccuracyDegrees
        }
        return map
    }

    private func normalizedSignalId(_ value: String) -> String? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard normalized.hasPrefix("cc-") else { return nil }
        let hex = normalized.dropFirst(3)
        guard hex.count == 24,
              hex.allSatisfy({ $0.isHexDigit }) else {
            return nil
        }
        return normalized
    }

    private func firstNonBlank(_ values: String?...) -> String {
        for candidate in values {
            let value = candidate?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !value.isEmpty {
                return value
            }
        }
        return ""
    }

    private static let profileCacheTTL: TimeInterval = 10 * 60
    // The web dashboard reads agencyPanels only ("afadpanel" is a dead legacy constant there), so
    // writing anywhere else makes iOS rescuer sightings invisible on the map.
    private static let panelCollection = "agencyPanels"
    private static let respondersCollection = "responders"
    private static let signalsCollection = "signals"
    private static let reportersCollection = "reporters"
    private static let rescueDevicesCollection = "rescueDevices"
    private static let panelSource = "ios-crisislink"
    private static let panelSchemaVersion = 3
    private static let responderSchemaVersion = 1
    private static let signalSchemaVersion = 3
    private static let signalLocationSourceVictimGPS = "victim_gps"
    private static let signalLocationSourceRelativeEstimate = "rescuer_relative_estimate"
}

struct PendingDashboardSyncOperation: Codable, Equatable, Identifiable {
    enum Kind: String, Codable, Equatable {
        case liveLocation
        case responderInactive
        /// A victim sighting whose Firestore write failed. Android queues these; iOS logged and
        /// LOST them — an offline rescuer's sightings died if the app suspended before reconnect,
        /// which is the realistic field case.
        case signalObservation
    }

    let id: UUID
    let kind: Kind
    let payload: CrisisLinkDashboardLocationPayload?
    // signalObservation only. Optionals, so operations persisted by older builds decode as nil.
    var signalId: String?
    var signalLocation: SOSSignalLocationPayload?
    var signalReporterLocation: CrisisLinkDashboardLocationPayload?
    var attemptCount: Int
    var nextRetryAt: Date
    let createdAt: Date
    var lastError: String?

    init(
        id: UUID = UUID(),
        kind: Kind,
        payload: CrisisLinkDashboardLocationPayload?,
        attemptCount: Int,
        nextRetryAt: Date,
        createdAt: Date,
        lastError: String?,
        signalId: String? = nil,
        signalLocation: SOSSignalLocationPayload? = nil,
        signalReporterLocation: CrisisLinkDashboardLocationPayload? = nil
    ) {
        self.id = id
        self.kind = kind
        self.payload = payload
        self.signalId = signalId
        self.signalLocation = signalLocation
        self.signalReporterLocation = signalReporterLocation
        self.attemptCount = attemptCount
        self.nextRetryAt = nextRetryAt
        self.createdAt = createdAt
        self.lastError = lastError
    }
}

struct LiveLocationSyncBackoff {
    static func delay(forAttempt attemptCount: Int) -> TimeInterval {
        let boundedAttempt = max(1, attemptCount)
        let baseDelay: TimeInterval = 2
        let multiplier = pow(2.0, Double(boundedAttempt - 1))
        return min(300, baseDelay * multiplier)
    }

    static func nextRetryDate(forAttempt attemptCount: Int, now: Date = Date()) -> Date {
        now.addingTimeInterval(delay(forAttempt: attemptCount))
    }
}

struct PendingDashboardSyncStore {
    private let fileManager = FileManager.default
    private let fileURL: URL
    private let queue = DispatchQueue(label: "crisis.link.live-location.sync-store")

    init(fileURL: URL? = nil) {
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let baseURL = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
                .appendingPathComponent("CrisisConnect", isDirectory: true)
            self.fileURL = baseURL.appendingPathComponent("live_location_sync_queue.json")
            ensureDirectoryExists(at: baseURL)
        }
        ensureDirectoryExists(at: self.fileURL.deletingLastPathComponent())
    }

    func load() -> [PendingDashboardSyncOperation] {
        queue.sync {
            guard let payload = try? LocalEncryptedFileStore.read(from: fileURL) else {
                return []
            }
            if !payload.wasEncrypted {
                try? LocalEncryptedFileStore.write(payload.data, to: fileURL)
            }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            return (try? decoder.decode([PendingDashboardSyncOperation].self, from: payload.data)) ?? []
        }
    }

    func save(_ operations: [PendingDashboardSyncOperation]) {
        queue.sync {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            encoder.dateEncodingStrategy = .iso8601
            guard let data = try? encoder.encode(operations) else { return }
            try? LocalEncryptedFileStore.write(data, to: fileURL)
        }
    }

    private func ensureDirectoryExists(at url: URL) {
        guard !fileManager.fileExists(atPath: url.path) else { return }
        let attributes: [FileAttributeKey: Any] = [
            .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
        ]
        try? fileManager.createDirectory(at: url, withIntermediateDirectories: true, attributes: attributes)
    }
}
