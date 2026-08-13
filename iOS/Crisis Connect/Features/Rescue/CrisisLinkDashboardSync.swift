//
//  CrisisLinkDashboardSync.swift
//  Crisis Connect
//
//  Created by Assistant on 27.03.2026.
//

import CoreLocation
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
    // Persisted across relaunches (see loadSignalFirstSeenIfNeeded): purely in-memory, every app
    // restart rewrote firstSeenMillis as "now" and regressed the earliest-contact time on the
    // dashboard.
    private var signalFirstSeenMillisById: [String: Int64] = [:]
    private var signalFirstSeenLoaded = false
    // The last track point actually written, for the 60 s / 25 m spacing guard. In-memory only:
    // a relaunch writes at most one extra point, which the dashboard timeline tolerates.
    private var lastTrackPoint: (capturedAtMillis: Int64, latitude: Double, longitude: Double)?

    func syncLiveLocation(payload: LocationPayload) async -> Bool {
        await syncResponderRoster(payload: payload)
    }

    /// A location-less roster heartbeat: the panel + responder docs exactly as syncLiveLocation
    /// writes them, minus the "gps" field. Live location defaults OFF, so without this an iOS
    /// rescuer with only Crisis Link enabled never appeared in the dashboard teams roster —
    /// Android has always sent these location-less heartbeats.
    func syncResponderPresence() async -> Bool {
        await syncResponderRoster(payload: nil)
    }

    private func syncResponderRoster(payload: LocationPayload?) async -> Bool {
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
            "updatedAt": FieldValue.serverTimestamp()
        ]
        if let payload {
            responderData["gps"] = gpsMap(for: payload)
        }

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
        if let payload {
            // Best-effort extras ride the successful sync; neither may fail it.
            await writeTrackPointIfDue(profile: profile, responderDeviceId: responderDeviceId, payload: payload)
            mirrorLiveLocationEvent(profile: profile, responderDeviceId: responderDeviceId, payload: payload)
        }
        return true
    }

    /// Appends a breadcrumb to the responder's `track` subcollection for the dashboard timeline.
    /// Create-only, spaced at 60 s / 25 m, and best effort by design: a lost track point must
    /// never fail the roster sync that carried it.
    private func writeTrackPointIfDue(
        profile: RescuerProfile,
        responderDeviceId: String,
        payload: LocationPayload
    ) async {
        let capturedAtMillis = Int64(payload.capturedAt.timeIntervalSince1970 * 1_000)
        if let last = lastTrackPoint {
            let ageMillis = capturedAtMillis - last.capturedAtMillis
            let movedMeters = CLLocation(latitude: payload.latitude, longitude: payload.longitude)
                .distance(from: CLLocation(latitude: last.latitude, longitude: last.longitude))
            if ageMillis < Self.trackPointMinIntervalMillis && movedMeters < Self.trackPointMinDistanceMeters {
                return
            }
        }

        let trackRef = firestore
            .collection(Self.panelCollection)
            .document(profile.agencySlug)
            .collection(Self.respondersCollection)
            .document(responderDeviceId)
            .collection(Self.trackCollection)
            .document(String(capturedAtMillis))
        let data: [String: Any] = [
            "uid": profile.uid,
            "responderDeviceId": responderDeviceId,
            "agency": profile.agency,
            "gps": gpsMap(for: payload),
            "capturedAtMillis": capturedAtMillis,
            "activeSignalCount": ResponderSignalStats.shared.snapshot.active,
            "recordedAt": FieldValue.serverTimestamp(),
            // A Firestore TTL policy on expiresAt garbage-collects old breadcrumbs server-side.
            "expiresAt": Timestamp(date: Date().addingTimeInterval(Self.trackPointRetention)),
            "source": Self.panelSource
        ]
        do {
            try await trackRef.setDataAsync(data)
            lastTrackPoint = (capturedAtMillis, payload.latitude, payload.longitude)
        } catch {
            NSLog("Crisis Link track point write failed: %@", String(describing: error))
        }
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
        let firstSeenMillis = self.firstSeenMillis(for: normalizedSignalId, observedAt: nowMillis)

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
        } catch {
            NSLog("Crisis Link signal sync failed: %@", String(describing: error))
            return false
        }
        mirrorSignalEvent(
            profile: profile,
            responderDeviceId: responderDeviceId,
            signalId: normalizedSignalId,
            firstSeenMillis: firstSeenMillis,
            lastSeenMillis: nowMillis,
            signalLocation: signalLocation,
            reporterLocation: reporterLocation,
            victimName: victimName,
            victimBatteryPercent: victimBatteryPercent
        )
        return true
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

    /// The earliest observation for a signal, kept at the MINIMUM of persisted and new so a
    /// relaunch can never move earliest-contact forward. Backed by UserDefaults (JSON dictionary),
    /// loaded lazily and pruned at 48 h — well past the dashboard's freshness window.
    private func firstSeenMillis(for signalId: String, observedAt nowMillis: Int64) -> Int64 {
        loadSignalFirstSeenIfNeeded()
        let firstSeen = min(signalFirstSeenMillisById[signalId] ?? nowMillis, nowMillis)
        if signalFirstSeenMillisById[signalId] != firstSeen {
            signalFirstSeenMillisById[signalId] = firstSeen
            persistSignalFirstSeen()
        }
        return firstSeen
    }

    private func loadSignalFirstSeenIfNeeded() {
        guard !signalFirstSeenLoaded else { return }
        signalFirstSeenLoaded = true
        guard let data = UserDefaults.standard.data(forKey: Self.signalFirstSeenDefaultsKey),
              let stored = try? JSONDecoder().decode([String: Int64].self, from: data) else {
            return
        }
        let cutoff = Int64(Date().timeIntervalSince1970 * 1_000) - Self.signalFirstSeenRetentionMillis
        signalFirstSeenMillisById = stored.filter { $0.value >= cutoff }
        if signalFirstSeenMillisById.count != stored.count {
            persistSignalFirstSeen()
        }
    }

    private func persistSignalFirstSeen() {
        guard let data = try? JSONEncoder().encode(signalFirstSeenMillisById) else { return }
        UserDefaults.standard.set(data, forKey: Self.signalFirstSeenDefaultsKey)
    }

    private func mirrorLiveLocationEvent(
        profile: RescuerProfile,
        responderDeviceId: String,
        payload: LocationPayload
    ) {
        let capturedAtMillis = Int64(payload.capturedAt.timeIntervalSince1970 * 1_000)
        let stats = ResponderSignalStats.shared.snapshot
        // The fix rides NESTED under "gps": /api/mobile/sync filters location payloads with
        // RESPONDER_PAYLOAD_ALLOWLIST, which passes `gps` but not top-level lat/lon — a flattened
        // fix is silently stripped and the responder never moves on a self-hosted map.
        var eventPayload: [String: Any] = ["gps": gpsMap(for: payload)]
        eventPayload["uid"] = profile.uid
        eventPayload["responderDeviceId"] = responderDeviceId
        eventPayload["agency"] = profile.agency
        eventPayload["agencySlug"] = profile.agencySlug
        eventPayload["role"] = profile.role
        eventPayload["username"] = profile.username
        eventPayload["operatorName"] = profile.operatorName
        eventPayload["activeSignalCount"] = stats.active
        eventPayload["totalUniqueSignalCount"] = stats.totalUnique
        eventPayload["totalScanEventCount"] = stats.scanEvents
        mirrorEventToSelfHostedDashboard(
            panelId: profile.agencySlug,
            responderDeviceId: responderDeviceId,
            event: [
                "id": "location-\(responderDeviceId)-\(capturedAtMillis)",
                "type": "location",
                "occurredAt": capturedAtMillis,
                "payload": eventPayload
            ]
        )
    }

    private func mirrorSignalEvent(
        profile: RescuerProfile,
        responderDeviceId: String,
        signalId: String,
        firstSeenMillis: Int64,
        lastSeenMillis: Int64,
        signalLocation: SOSSignalLocationPayload?,
        reporterLocation: LocationPayload?,
        victimName: String?,
        victimBatteryPercent: Int?
    ) {
        var payload: [String: Any] = [
            "signalId": signalId,
            "schemaVersion": Self.signalSchemaVersion,
            "status": "active",
            "source": "ble",
            "firstSeenMillis": firstSeenMillis,
            "lastSeenMillis": lastSeenMillis,
            "lastReporterUid": profile.uid,
            "lastReporterDeviceId": responderDeviceId,
            "victimRole": "victim",
            "reporter": [
                "responderDeviceId": responderDeviceId,
                "uid": profile.uid,
                "agency": profile.agency,
                "name": profile.operatorName,
                "username": profile.username,
                "lastSeenMillis": lastSeenMillis
            ]
        ]
        if let gps = signalLocation?.gps {
            payload["gps"] = gpsMap(for: gps)
            if signalLocation?.relativeEstimate == nil {
                payload["victimGps"] = gpsMap(for: gps)
                payload["locationSource"] = Self.signalLocationSourceVictimGPS
            } else {
                payload["estimatedVictimGps"] = gpsMap(for: gps)
                payload["locationSource"] = Self.signalLocationSourceRelativeEstimate
            }
        }
        if let relativeEstimate = signalLocation?.relativeEstimate {
            payload["relativeEstimate"] = relativeEstimateMap(for: relativeEstimate)
        }
        if let reporterLocation {
            payload["lastReporterLocation"] = gpsMap(for: reporterLocation)
        } else if let anchorGPS = signalLocation?.relativeEstimate?.anchorGPS {
            payload["lastReporterLocation"] = gpsMap(for: anchorGPS)
        }
        if let victimName = victimName?.trimmingCharacters(in: .whitespacesAndNewlines),
           !victimName.isEmpty {
            payload["victimName"] = victimName
            payload["victimDisplayName"] = victimName
        }
        if let victimBatteryPercent, (0...100).contains(victimBatteryPercent) {
            payload["victimBatteryPercent"] = victimBatteryPercent
        }
        mirrorEventToSelfHostedDashboard(
            panelId: profile.agencySlug,
            responderDeviceId: responderDeviceId,
            event: [
                "id": "signal-\(signalId)-\(lastSeenMillis)",
                "type": "signal",
                "occurredAt": lastSeenMillis,
                "payload": payload
            ]
        )
    }

    /// Fire-and-forget mirror to the self-hosted dashboard, Android parity with
    /// CrisisLinkForegroundService.syncPayloadToSelfHostedDashboard. Best effort: Firestore is the
    /// source of truth and must never wait on, or fail with, this POST.
    private func mirrorEventToSelfHostedDashboard(
        panelId: String,
        responderDeviceId: String,
        event: [String: Any]
    ) {
        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        let clientEventId = "ios-crisis-link-\(responderDeviceId)-\(nowMillis)"
        Task {
            await MobileSyncClient.syncEvents(
                panelId: panelId,
                deviceId: responderDeviceId,
                clientEventId: clientEventId,
                events: [event]
            )
        }
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
    private static let trackCollection = "track"
    private static let trackPointMinIntervalMillis: Int64 = 60_000
    private static let trackPointMinDistanceMeters: Double = 25
    private static let trackPointRetention: TimeInterval = 7 * 24 * 3600
    private static let signalFirstSeenDefaultsKey = "crisisLink.signalFirstSeenMillisById"
    private static let signalFirstSeenRetentionMillis: Int64 = 48 * 3600 * 1_000
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
