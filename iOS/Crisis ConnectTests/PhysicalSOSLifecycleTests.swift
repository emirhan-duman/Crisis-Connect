import XCTest
import CoreBluetooth
@testable import Crisis_Connect

/// Runs the real declaration/recovery state machine with no public advertising or cloud alerts.
@MainActor final class PhysicalSOSLifecycleTests: XCTestCase {
    func testDeclarationIsDurableIdempotentAndExplicitlyStoppedWithoutExternalAlarm() async throws {
        guard ProcessInfo.processInfo.environment["CC_PHYSICAL_SOS"] == "1" else {
            throw XCTSkip("Opt-in isolated SOS lifecycle test")
        }
        XCTAssertTrue(PlatformRuntime.isRunningTests)
        let manager = SOSBroadcastManager.shared
        let recovery = SOSRecoveryStore.shared
        guard !manager.isSessionActive, recovery.snapshot.session == nil else {
            throw XCTSkip("Preserve an existing SOS session")
        }
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("sos-recovery-v1.json")
        let original = FileManager.default.fileExists(atPath: url.path) ? try Data(contentsOf: url) : nil
        let host = BlePeripheralHostCoordinator.shared
        // Replace only this test's SOS host registration. The production state machine stays intact.
        let quiet = QuietSOSProfile(manager)
        host.register(quiet)
        host.queue.sync {}
        defer {
            manager.stop()
            host.register(manager)
            if let original { try? original.write(to: url, options: .atomic) }
            else { try? FileManager.default.removeItem(at: url) }
        }
        XCTAssertNil(host.currentPeripheralManager, "No radio host may be active in this isolated test")
        manager.start(declaringEmergency: true)
        XCTAssertTrue(manager.isSessionActive)
        XCTAssertTrue(manager.isEmergencyDeclared)
        let id = try XCTUnwrap(manager.emergencySessionID)
        XCTAssertEqual(SOSRecoveryStore(url: url).snapshot.session?.id, id)
        manager.start(declaringEmergency: true)
        XCTAssertEqual(manager.emergencySessionID, id)
        XCTAssertThrowsError(try SOSRecoveryStore(url: url).begin())
        XCTAssertEqual(try SOSRecoveryStore(url: url).begin(resuming: id).id, id)
        host.queue.sync {}
        XCTAssertNil(host.currentPeripheralManager)
        XCTAssertEqual(SosCloudReporter.shared.state, .idle)
        XCTAssertTrue(SosContactNotifier.shared.recipients.isEmpty)
        manager.stop()
        XCTAssertFalse(manager.isSessionActive)
        XCTAssertFalse(manager.isEmergencyDeclared)
        XCTAssertNil(SOSRecoveryStore(url: url).snapshot.session)
        XCTAssertThrowsError(try SOSRecoveryStore(url: url).begin(resuming: id))
        print("CCPhysicalSOSTest PASS: durable declaration, duplicate start, confirmed resume and explicit stop; no public radio/cloud alarm")
    }
}

private final class QuietSOSProfile: NSObject, BlePeripheralHostProfile {
    private let wrapped: SOSBroadcastManager
    init(_ wrapped: SOSBroadcastManager) { self.wrapped = wrapped }
    var hostProfileIdentifier: String { wrapped.hostProfileIdentifier }
    var hostPrimaryServiceUUID: CBUUID { wrapped.hostPrimaryServiceUUID }
    var isPeripheralHostActive: Bool { false }
    var hostedAdvertisementDescriptor: BleHostedAdvertisementDescriptor? { nil }
    func buildHostedService() -> CBMutableService? { nil }
    func peripheralHostWillReset() {}
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {}
}
