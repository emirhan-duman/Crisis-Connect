//
//  Crisis_ConnectTests.swift
//  Crisis ConnectTests
//
//  Created by Emirhan Duman on 25.10.2025.
//

import XCTest
@testable import Crisis_Connect
import CoreBluetooth
import CoreLocation
import Foundation
import Darwin

final class Crisis_ConnectTests: XCTestCase {

    private var temporaryURLs: [URL] = []

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.
    }

    override func tearDownWithError() throws {
        for url in temporaryURLs {
            try? FileManager.default.removeItem(at: url)
        }
        temporaryURLs.removeAll()
    }

    func testOfflineMapConfigurationBuildTileTemplateUsesExplicitKey() {
        let template = OfflineMapConfiguration.buildTileTemplate(
            style: "streets-v2",
            apiKey: "ios-inline-map-key"
        )

        XCTAssertEqual(
            template,
            "https://api.maptiler.com/maps/streets-v2/256/{z}/{x}/{y}.png?key=ios-inline-map-key"
        )
    }

    func testOfflineMapConfigurationResolvesMapLibreAliasFromEnvironment() {
        let keyName = "MAPLIBRE_API_KEY"
        let previous = getenv(keyName).map { String(cString: $0) }
        let previousMapTiler = getenv("MAPTILER_API_KEY").map { String(cString: $0) }
        unsetenv("MAPTILER_API_KEY")
        setenv(keyName, "android-shared-map-key", 1)
        defer {
            restoreEnvironmentValue(previous, for: keyName)
            restoreEnvironmentValue(previousMapTiler, for: "MAPTILER_API_KEY")
        }

        XCTAssertEqual(OfflineMapConfiguration.mapTilerAPIKey, "android-shared-map-key")
    }

    func testLocalEncryptedFileStoreRoundTripsEncryptedPayload() throws {
        let url = makeTemporaryURL(fileName: "encrypted-roundtrip.bin")
        let plaintext = Data("highly-sensitive-payload".utf8)

        try LocalEncryptedFileStore.write(plaintext, to: url)

        let onDisk = try Data(contentsOf: url)
        XCTAssertNotEqual(onDisk, plaintext)

        let payload = try LocalEncryptedFileStore.read(from: url)
        XCTAssertTrue(payload.wasEncrypted)
        XCTAssertEqual(payload.data, plaintext)
    }

    func testLocalEncryptedFileStoreReadsLegacyPlaintext() throws {
        let url = makeTemporaryURL(fileName: "legacy-plaintext.json")
        let plaintext = Data("[{\"name\":\"legacy\"}]".utf8)

        try plaintext.write(to: url, options: [.atomic])

        let payload = try LocalEncryptedFileStore.read(from: url)
        XCTAssertFalse(payload.wasEncrypted)
        XCTAssertEqual(payload.data, plaintext)
    }

    func testContactQRPayloadDecodesLegacyPayloadWithoutShareId() throws {
        let keyData = Data(repeating: 0xAB, count: 32)
        let legacyJSON = """
        {"v":1,"n":"Ada","b":"LEGACY123","k":"\(keyData.base64EncodedString())"}
        """

        let payload = try XCTUnwrap(ContactQRPayload.decode(from: legacyJSON))

        XCTAssertEqual(payload.v, ContactQRPayload.currentVersion)
        XCTAssertEqual(payload.name, "Ada")
        XCTAssertEqual(payload.code, "LEGACY123")
        XCTAssertEqual(payload.key, keyData.base64EncodedString())
        XCTAssertNil(payload.shareId)
    }

    func testP2pBleProtocolAdvertisedShareIdUsesLocalNameFallback() {
        let advertisementData: [String: Any] = [
            CBAdvertisementDataLocalNameKey: "ABCD2345"
        ]

        XCTAssertEqual(
            P2pBleProtocol.advertisedShareId(from: advertisementData),
            "ABCD2345"
        )
    }

    func testP2pBleProtocolAdvertisedShareIdIgnoresNonShareLikeNames() {
        let advertisementData: [String: Any] = [
            CBAdvertisementDataLocalNameKey: "Crisis Connect"
        ]

        XCTAssertNil(
            P2pBleProtocol.advertisedShareId(
                from: advertisementData,
                peripheralName: "Nearby Contact"
            )
        )
    }

    func testP2pBleProtocolAdvertisedShareIdReadsServiceData() {
        let advertisementData: [String: Any] = [
            CBAdvertisementDataServiceDataKey: [
                P2pBleProtocol.serviceUUID: Data("ZXCV6789".utf8)
            ]
        ]

        XCTAssertEqual(
            P2pBleProtocol.advertisedShareId(from: advertisementData),
            "ZXCV6789"
        )
    }

    func testGattMeshProtocolChatPacketRoundTripsEncryptedPayload() throws {
        let packet = try XCTUnwrap(GattMeshProtocol.makeChatPacket(
            text: "mesh roundtrip",
            messageId: "A1B2C3D4-1122"
        ))

        let encoded = try XCTUnwrap(GattMeshProtocol.encodePacket(packet))
        let decoded = try XCTUnwrap(GattMeshProtocol.decodePacket(from: encoded))

        XCTAssertEqual(decoded.id, packet.id)
        XCTAssertEqual(decoded.type, .chat)
        XCTAssertEqual(decoded.message, "mesh roundtrip")
        XCTAssertTrue(decoded.encrypted)
        XCTAssertTrue(decoded.isReadable)
    }

    func testGattMeshProtocolReceiptPacketRoundTripsReceiptIDs() throws {
        let packet = try XCTUnwrap(
            GattMeshProtocol.makeReceiptPacket(
                type: .read,
                messageIds: ["ABCDEF12-0001", "ABCDEF12-0002"],
                senderLabel: "Responder"
            )
        )

        let encoded = try XCTUnwrap(GattMeshProtocol.encodePacket(packet))
        let decoded = try XCTUnwrap(GattMeshProtocol.decodePacket(from: encoded))

        XCTAssertEqual(decoded.type, .receipt)
        XCTAssertEqual(decoded.receiptType, .read)
        XCTAssertEqual(decoded.receiptMessageIds, ["ABCDEF12-0001", "ABCDEF12-0002"])
    }

    func testGattMeshProtocolV4ChatRoundTripsOriginProofFields() throws {
        let basePacket = GattMeshPacket(
            id: "ABCDEF12-3001",
            senderLabel: "Responder",
            timestampMillis: 1_700_000_000_000,
            message: "secure relay",
            type: .chat,
            receiptType: nil,
            receiptMessageIds: [],
            authNonce: nil,
            authProofJSON: nil,
            hop: 0,
            protocol: "dcs-gattmesh-v4",
            encrypted: false,
            keyId: nil,
            ivBase64: nil,
            cipherBase64: nil,
            originProofJSON: "{\"pk\":\"abc\",\"c\":\"def\",\"ts\":1700000000000,\"s\":\"ghi\"}",
            originSignatureBase64: "c2lnbmF0dXJl",
            isReadable: true
        )

        let encoded = try XCTUnwrap(GattMeshProtocol.encodePacket(basePacket))
        let decoded = try XCTUnwrap(GattMeshProtocol.decodePacket(from: encoded))

        XCTAssertEqual(decoded.type, .chat)
        XCTAssertEqual(decoded.protocol, "dcs-gattmesh-v4")
        XCTAssertEqual(decoded.originProofJSON, basePacket.originProofJSON)
        XCTAssertEqual(decoded.originSignatureBase64, basePacket.originSignatureBase64)
    }

    func testGattMeshProtocolAuthChallengeRoundTripsNonce() throws {
        let packet = try XCTUnwrap(
            GattMeshProtocol.makePeerVerificationChallenge(
                senderLabel: "Verifier",
                nonce: "nonce-1234"
            )
        )

        let encoded = try XCTUnwrap(GattMeshProtocol.encodePacket(packet))
        let decoded = try XCTUnwrap(GattMeshProtocol.decodePacket(from: encoded))

        XCTAssertEqual(decoded.type, .authChallenge)
        XCTAssertEqual(decoded.protocol, "dcs-gattmesh-v4")
        XCTAssertEqual(decoded.authNonce, "nonce-1234")
    }

    func testGattMeshProtocolTimestampValidationRejectsStaleAndFutureValues() {
        let nowMillis: Int64 = 1_700_000_000_000

        XCTAssertTrue(GattMeshProtocol.isTimestampValid(nowMillis, nowMillis: nowMillis))
        XCTAssertFalse(
            GattMeshProtocol.isTimestampValid(
                nowMillis + GattMeshProtocol.maxFutureClockSkewMillis + 1,
                nowMillis: nowMillis
            )
        )
        XCTAssertFalse(
            GattMeshProtocol.isTimestampValid(
                nowMillis - GattMeshProtocol.maxMessageAgeMillis - 1,
                nowMillis: nowMillis
            )
        )
    }

    @MainActor
    func testContactRecordDecodeDowngradesLegacyVerifiedWithoutIdentityKey() throws {
        let legacyJSON = """
        {"name":"Legacy","broadcastId":"LEGACY","sessionCode":"ble:legacy","isVerified":true,"verifiedAt":12345}
        """

        let record = try JSONDecoder().decode(ContactRecord.self, from: Data(legacyJSON.utf8))

        XCTAssertFalse(record.isVerified)
        XCTAssertNil(record.verifiedIdentityKey)
        XCTAssertNil(record.verifiedAt)
    }

    @MainActor
    func testContactRecordRoundTripKeepsVerifiedIdentityKeyAndTimestamp() throws {
        let verifiedAt = Date(timeIntervalSinceReferenceDate: 42_000)
        let input = ContactRecord(
            id: UUID(),
            name: "Trusted",
            broadcastId: "",
            sessionCode: "ble:trusted",
            isVerified: true,
            verifiedIdentityKey: "crypto:peer-identity-key",
            verifiedAt: verifiedAt,
            aesKeyBase64: "",
            preferredTransport: .bleGatt,
            createdAt: Date(timeIntervalSinceReferenceDate: 10),
            lastUpdated: Date(timeIntervalSinceReferenceDate: 20)
        )

        let encoded = try JSONEncoder().encode(input)
        let decoded = try JSONDecoder().decode(ContactRecord.self, from: encoded)

        XCTAssertTrue(decoded.isVerified)
        XCTAssertEqual(decoded.verifiedIdentityKey, "crypto:peer-identity-key")
        XCTAssertEqual(decoded.verifiedAt, verifiedAt)
    }

    func testAppNotificationPreferencesKeepSosAlertsIndependentFromContactUpdates() {
        let defaults = makeIsolatedUserDefaults()
        defaults.set(true, forKey: "notifications.appEnabled")
        defaults.set(true, forKey: "notifications.sosAlerts")
        defaults.set(false, forKey: "notifications.contactUpdates")

        let preferences = AppNotificationPreferences(userDefaults: defaults)

        XCTAssertTrue(preferences.allows(.sosAlert))
        XCTAssertFalse(preferences.allows(.contactUpdate))
    }

    func testAppNotificationPreferencesAllowGeneralChatWithoutContactUpdatesToggle() {
        let defaults = makeIsolatedUserDefaults()
        defaults.set(true, forKey: "notifications.appEnabled")
        defaults.set(false, forKey: "notifications.sosAlerts")
        defaults.set(false, forKey: "notifications.contactUpdates")

        let preferences = AppNotificationPreferences(userDefaults: defaults)

        XCTAssertTrue(preferences.allows(.generalChat))
    }

    func testOfflineMapConfigurationResolvesMapTilerKeyFromEnvironment() {
        let keyName = "MAPTILER_API_KEY"
        let previous = getenv(keyName).map { String(cString: $0) }
        setenv(keyName, "unit-test-maptiler-key", 1)
        defer {
            restoreEnvironmentValue(previous, for: keyName)
        }

        XCTAssertEqual(OfflineMapConfiguration.mapTilerAPIKey, "unit-test-maptiler-key")
        XCTAssertTrue(OfflineMapConfiguration.tileTemplate.contains("unit-test-maptiler-key"))
        XCTAssertTrue(OfflineMapConfiguration.fallbackTileTemplate.contains("unit-test-maptiler-key"))
    }

    @MainActor
    func testOfflineMapDownloaderRetriesTransientFailuresBeforeSucceeding() async throws {
        let region = makeTinyOfflineMapRegion()
        let tileStore = OfflineTileStore()
        defer {
            tileStore.removeRegion(regionId: region.id)
        }

        let session = makeMockURLSession()
        MockURLProtocol.requestCount = 0
        MockURLProtocol.handler = { _, requestCount in
            if requestCount == 1 {
                let response = HTTPURLResponse(
                    url: URL(string: "https://example.com/tile.png")!,
                    statusCode: 503,
                    httpVersion: "HTTP/1.1",
                    headerFields: nil
                )!
                return (response, Data())
            }
            let response = HTTPURLResponse(
                url: URL(string: "https://example.com/tile.png")!,
                statusCode: 200,
                httpVersion: "HTTP/1.1",
                headerFields: nil
            )!
            return (response, Data("tile-bytes".utf8))
        }
        defer {
            MockURLProtocol.handler = nil
        }

        let plan = OfflineMapDownloadPlan(region: region, initialDownloaded: 0, resumeOrdinal: nil)
        var progressEvents: [OfflineMapDownloadProgress] = []
        let result = await OfflineMapDownloader.run(
            plan: plan,
            tileStore: tileStore,
            session: session,
            tileTemplate: mockTileTemplate(),
            retryPolicy: OfflineMapRetryPolicy(
                maxAttempts: 3,
                initialDelaySeconds: 0,
                maximumDelaySeconds: 0,
                retryableHTTPStatusCodes: [503],
                retryableURLErrorCodes: [.timedOut]
            ),
            sleep: { _ in }
        ) { progress in
            progressEvents.append(progress)
        }

        XCTAssertEqual(result, .completed)
        XCTAssertEqual(MockURLProtocol.requestCount, 2)
        XCTAssertEqual(progressEvents.last?.downloadedTiles, 1)
        XCTAssertEqual(progressEvents.last?.missingTiles, 0)
        XCTAssertEqual(tileStore.countTiles(regionId: region.id), 1)
    }

    @MainActor
    func testOfflineMapDownloaderCountsMissingTilesAsCompletedProgress() async throws {
        let region = makeTinyOfflineMapRegion()
        let tileStore = OfflineTileStore()
        defer {
            tileStore.removeRegion(regionId: region.id)
        }

        let session = makeMockURLSession()
        MockURLProtocol.requestCount = 0
        MockURLProtocol.handler = { _, _ in
            let response = HTTPURLResponse(
                url: URL(string: "https://example.com/tile.png")!,
                statusCode: 404,
                httpVersion: "HTTP/1.1",
                headerFields: nil
            )!
            return (response, Data())
        }
        defer {
            MockURLProtocol.handler = nil
        }

        let plan = OfflineMapDownloadPlan(region: region, initialDownloaded: 0, resumeOrdinal: nil)
        var lastProgress: OfflineMapDownloadProgress?
        let result = await OfflineMapDownloader.run(
            plan: plan,
            tileStore: tileStore,
            session: session,
            tileTemplate: mockTileTemplate(),
            retryPolicy: OfflineMapRetryPolicy(
                maxAttempts: 1,
                initialDelaySeconds: 0,
                maximumDelaySeconds: 0,
                retryableHTTPStatusCodes: [],
                retryableURLErrorCodes: []
            ),
            sleep: { _ in }
        ) { progress in
            lastProgress = progress
        }

        XCTAssertEqual(result, .completed)
        XCTAssertEqual(lastProgress?.downloadedTiles, 0)
        XCTAssertEqual(lastProgress?.missingTiles, 1)
        XCTAssertEqual(tileStore.countTiles(regionId: region.id), 0)
    }

    @MainActor
    func testOfflineMapDownloaderCountsExistingTilesWithoutRescanningOnMainThread() async throws {
        let region = makeTinyOfflineMapRegion()
        let tileStore = OfflineTileStore()
        defer {
            tileStore.removeRegion(regionId: region.id)
        }

        try tileStore.writeTileData(
            Data("cached-tile".utf8),
            regionId: region.id,
            z: region.minZoom,
            x: TileCalculator.tileX(longitude: region.bounds.west, zoom: region.minZoom),
            y: TileCalculator.tileY(latitude: region.bounds.north, zoom: region.minZoom)
        )

        let session = makeMockURLSession()
        MockURLProtocol.requestCount = 0
        MockURLProtocol.handler = { _, _ in
            XCTFail("Downloader should not hit the network when the tile already exists.")
            let response = HTTPURLResponse(
                url: URL(string: "https://example.com/tile.png")!,
                statusCode: 500,
                httpVersion: "HTTP/1.1",
                headerFields: nil
            )!
            return (response, Data())
        }
        defer {
            MockURLProtocol.handler = nil
        }

        let plan = OfflineMapDownloadPlan(region: region, initialDownloaded: 0, resumeOrdinal: nil)
        var lastProgress: OfflineMapDownloadProgress?
        let result = await OfflineMapDownloader.run(
            plan: plan,
            tileStore: tileStore,
            session: session,
            tileTemplate: mockTileTemplate(),
            retryPolicy: OfflineMapRetryPolicy(
                maxAttempts: 1,
                initialDelaySeconds: 0,
                maximumDelaySeconds: 0,
                retryableHTTPStatusCodes: [],
                retryableURLErrorCodes: []
            ),
            sleep: { _ in }
        ) { progress in
            lastProgress = progress
        }

        XCTAssertEqual(result, .completed)
        XCTAssertEqual(MockURLProtocol.requestCount, 0)
        XCTAssertEqual(lastProgress?.downloadedTiles, 1)
        XCTAssertEqual(lastProgress?.missingTiles, 0)
    }

    @MainActor
    func testOfflineMapRegionPersistsResumeMetadata() throws {
        let region = OfflineMapRegion(
            id: UUID(),
            name: "Resume",
            bounds: MapBounds(north: 10.1, south: 10.0, east: 20.1, west: 20.0),
            minZoom: 5,
            maxZoom: 5,
            tileCount: 1,
            downloadedTiles: 1,
            missingTiles: 1,
            status: .paused,
            createdAt: Date(timeIntervalSinceReferenceDate: 1_000),
            lastError: "Transient",
            downloadCursorOrdinal: 42
        )

        let encoded = try JSONEncoder().encode(region)
        let decoded = try JSONDecoder().decode(OfflineMapRegion.self, from: encoded)

        XCTAssertEqual(decoded.missingTiles, 1)
        XCTAssertEqual(decoded.downloadCursorOrdinal, 42)
        XCTAssertEqual(decoded.lastError, "Transient")
    }

    @MainActor
    func testOfflineMapDownloaderPreservesMissingTilesAcrossResume() async throws {
        let region = makeTwoTileOfflineMapRegion(missingTiles: 1, resumeOrdinal: 1)
        let tileStore = OfflineTileStore()
        defer {
            tileStore.removeRegion(regionId: region.id)
        }

        let session = makeMockURLSession()
        MockURLProtocol.requestCount = 0
        MockURLProtocol.handler = { _, _ in
            let response = HTTPURLResponse(
                url: URL(string: "https://example.com/tile.png")!,
                statusCode: 200,
                httpVersion: "HTTP/1.1",
                headerFields: nil
            )!
            return (response, Data("tile-bytes".utf8))
        }
        defer {
            MockURLProtocol.handler = nil
        }

        let plan = OfflineMapDownloadPlan(
            region: region,
            initialDownloaded: 0,
            resumeOrdinal: region.downloadCursorOrdinal
        )
        var lastProgress: OfflineMapDownloadProgress?
        let result = await OfflineMapDownloader.run(
            plan: plan,
            tileStore: tileStore,
            session: session,
            tileTemplate: mockTileTemplate(),
            retryPolicy: OfflineMapRetryPolicy(
                maxAttempts: 1,
                initialDelaySeconds: 0,
                maximumDelaySeconds: 0,
                retryableHTTPStatusCodes: [],
                retryableURLErrorCodes: []
            ),
            sleep: { _ in }
        ) { progress in
            lastProgress = progress
        }

        XCTAssertEqual(result, .completed)
        XCTAssertEqual(MockURLProtocol.requestCount, 1)
        XCTAssertEqual(lastProgress?.downloadedTiles, 1)
        XCTAssertEqual(lastProgress?.missingTiles, 1)
        XCTAssertEqual(tileStore.countTiles(regionId: region.id), 1)
    }

    func testRescueSessionMergePlannerPrefersReadySession() {
        let lhs = UUID()
        let rhs = UUID()

        let preferred = RescueSessionMergePlanner.preferredSessionId(
            lhs: lhs,
            lhsStatus: .disconnected,
            rhs: rhs,
            rhsStatus: .ready
        )

        XCTAssertEqual(preferred, rhs)
    }

    func testRescueSessionMergePlannerKeepsCanonicalSessionWhileMergingFreshMetadata() {
        let targetSessionId = UUID()
        let targetPeripheralId = UUID()
        let sourcePeripheralId = UUID()
        let baseTime = Date(timeIntervalSinceReferenceDate: 500)
        let newerTime = baseTime.addingTimeInterval(12)
        let unknownName = NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "")

        let target = RescueBroadcast(
            id: targetSessionId,
            broadcastId: "legacy",
            peripheralId: targetPeripheralId,
            peripheralName: unknownName,
            status: .ready,
            rssi: nil,
            lastSeen: baseTime,
            lastUpdated: baseTime
        )
        let source = RescueBroadcast(
            id: UUID(),
            broadcastId: "replacement",
            peripheralId: sourcePeripheralId,
            peripheralName: "Responder 7",
            status: .connected,
            rssi: -42,
            lastSeen: newerTime,
            lastUpdated: newerTime
        )

        let merged = RescueSessionMergePlanner.mergedBroadcast(
            targetSessionId: targetSessionId,
            targetEntry: target,
            targetStatus: .ready,
            sourceEntry: source,
            sourceStatus: .connected,
            broadcastId: "canonical"
        )

        XCTAssertEqual(merged.id, targetSessionId)
        XCTAssertEqual(merged.broadcastId, "canonical")
        XCTAssertEqual(merged.status, .ready)
        XCTAssertEqual(merged.peripheralId, targetPeripheralId)
        XCTAssertEqual(merged.peripheralName, "Responder 7")
        XCTAssertEqual(merged.rssi, -42)
        XCTAssertEqual(merged.lastSeen, newerTime)
        XCTAssertEqual(merged.lastUpdated, newerTime)
    }

    func testLiveLocationSyncBackoffGrowsAndCaps() {
        XCTAssertEqual(LiveLocationSyncBackoff.delay(forAttempt: 1), 2, accuracy: 0.001)
        XCTAssertEqual(LiveLocationSyncBackoff.delay(forAttempt: 2), 4, accuracy: 0.001)
        XCTAssertEqual(LiveLocationSyncBackoff.delay(forAttempt: 3), 8, accuracy: 0.001)
        XCTAssertEqual(LiveLocationSyncBackoff.delay(forAttempt: 20), 300, accuracy: 0.001)
    }

    @MainActor
    func testPendingDashboardSyncOperationRoundTripsThroughCodable() throws {
        let payload = CrisisLinkDashboardLocationPayload(
            latitude: 41.0123,
            longitude: 29.1234,
            horizontalAccuracyMeters: 12.5,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let operation = PendingDashboardSyncOperation(
            kind: .liveLocation,
            payload: payload,
            attemptCount: 2,
            nextRetryAt: Date(timeIntervalSince1970: 1_700_000_120),
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            lastError: "sync_failed"
        )

        let encoded = try JSONEncoder().encode(operation)
        let decoded = try JSONDecoder().decode(PendingDashboardSyncOperation.self, from: encoded)

        XCTAssertEqual(decoded, operation)
    }

    func testPendingDashboardSyncStoreRoundTripsEncryptedQueue() throws {
        let queueURL = makeTemporaryURL(fileName: "live_location_sync_queue.json")
        let store = PendingDashboardSyncStore(fileURL: queueURL)
        let payload = CrisisLinkDashboardLocationPayload(
            latitude: 40.9922,
            longitude: 29.0280,
            horizontalAccuracyMeters: nil,
            capturedAt: Date(timeIntervalSince1970: 1_700_000_300)
        )
        let operations = [
            PendingDashboardSyncOperation(
                kind: .liveLocation,
                payload: payload,
                attemptCount: 1,
                nextRetryAt: Date(timeIntervalSince1970: 1_700_000_310),
                createdAt: Date(timeIntervalSince1970: 1_700_000_300),
                lastError: nil
            )
        ]

        store.save(operations)
        let loaded = store.load()

        XCTAssertEqual(loaded, operations)
    }

    func testRescueLiveLocationPolicyKeepsForegroundPrecisionWhileActive() {
        let profile = RescueLiveLocationPolicy.runtimeProfile(
            liveLocationEnabled: true,
            applicationIsActive: true,
            authorizationStatus: .authorizedWhenInUse,
            backgroundRefreshAvailable: true
        )

        XCTAssertEqual(profile.desiredAccuracy, kCLLocationAccuracyNearestTenMeters)
        XCTAssertEqual(profile.distanceFilter, kCLDistanceFilterNone)
        XCTAssertFalse(profile.pausesLocationUpdatesAutomatically)
        XCTAssertTrue(profile.shouldUseTimer)
        XCTAssertFalse(profile.shouldUseSignificantChangeMonitoring)
        XCTAssertTrue(profile.shouldWarmLocation)
        XCTAssertTrue(profile.shouldRequestOneShotLocation)
    }

    func testRescueLiveLocationPolicyUsesLowPowerBackgroundProfileWithAlwaysAuthorization() {
        let profile = RescueLiveLocationPolicy.runtimeProfile(
            liveLocationEnabled: true,
            applicationIsActive: false,
            authorizationStatus: .authorizedAlways,
            backgroundRefreshAvailable: true
        )

        XCTAssertEqual(profile.desiredAccuracy, kCLLocationAccuracyHundredMeters)
        XCTAssertEqual(profile.distanceFilter, 100)
        XCTAssertTrue(profile.pausesLocationUpdatesAutomatically)
        XCTAssertFalse(profile.shouldUseTimer)
        XCTAssertTrue(profile.shouldUseSignificantChangeMonitoring)
        XCTAssertFalse(profile.shouldWarmLocation)
        XCTAssertFalse(profile.shouldRequestOneShotLocation)
    }

    private func makeTemporaryURL(fileName: String) -> URL {
        let directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
        try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        let url = directoryURL
            .appendingPathComponent(fileName)
        temporaryURLs.append(directoryURL)
        return url
    }

    private func makeTinyOfflineMapRegion() -> OfflineMapRegion {
        let bounds = MapBounds(north: 10.1, south: 10.0, east: 20.1, west: 20.0)
        let tileCount = TileCalculator.tileCount(bounds: bounds, minZoom: 5, maxZoom: 5)
        XCTAssertEqual(tileCount, 1, "Test bounds should resolve to exactly one tile.")
        return OfflineMapRegion(
            id: UUID(),
            name: "Tiny",
            bounds: bounds,
            minZoom: 5,
            maxZoom: 5,
            tileCount: tileCount,
            downloadedTiles: 0,
            missingTiles: 0,
            status: .paused,
            createdAt: Date(),
            lastError: nil
        )
    }

    private func makeTwoTileOfflineMapRegion(
        missingTiles: Int,
        resumeOrdinal: Int?
    ) -> OfflineMapRegion {
        let zoom = 5
        let west = longitudeForTileX(17, zoom: zoom) + 0.0001
        let east = longitudeForTileX(19, zoom: zoom) - 0.0001
        let north = latitudeForTileY(15, zoom: zoom) - 0.0001
        let south = latitudeForTileY(16, zoom: zoom) + 0.0001
        let bounds = MapBounds(north: north, south: south, east: east, west: west)
        let tileCount = TileCalculator.tileCount(bounds: bounds, minZoom: zoom, maxZoom: zoom)
        XCTAssertEqual(tileCount, 2, "Test bounds should resolve to exactly two tiles.")
        return OfflineMapRegion(
            id: UUID(),
            name: "Two Tile Resume",
            bounds: bounds,
            minZoom: zoom,
            maxZoom: zoom,
            tileCount: tileCount,
            downloadedTiles: 0,
            missingTiles: missingTiles,
            status: .paused,
            createdAt: Date(),
            lastError: nil,
            downloadCursorOrdinal: resumeOrdinal
        )
    }

    private func longitudeForTileX(_ x: Int, zoom: Int) -> Double {
        let tiles = pow(2.0, Double(zoom))
        return (Double(x) / tiles) * 360.0 - 180.0
    }

    private func latitudeForTileY(_ y: Int, zoom: Int) -> Double {
        let tiles = pow(2.0, Double(zoom))
        let mercator = Double.pi * (1.0 - (2.0 * Double(y) / tiles))
        return atan(sinh(mercator)) * 180.0 / Double.pi
    }

    private func makeMockURLSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func mockTileTemplate() -> String {
        "https://example.com/{z}/{x}/{y}.png"
    }

    private func restoreEnvironmentValue(_ value: String?, for key: String) {
        if let value {
            setenv(key, value, 1)
        } else {
            unsetenv(key)
        }
    }

    private func makeIsolatedUserDefaults() -> UserDefaults {
        let suiteName = "CrisisConnectTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }

}

final class MockURLProtocol: URLProtocol {
    static var requestCount = 0
    static var handler: ((URLRequest, Int) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        Self.requestCount += 1
        do {
            guard let handler = Self.handler else {
                fatalError("MockURLProtocol.handler was not configured")
            }
            let (response, data) = try handler(request, Self.requestCount)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
