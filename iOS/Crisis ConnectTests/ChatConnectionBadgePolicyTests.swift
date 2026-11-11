import XCTest
@testable import Crisis_Connect

final class ChatConnectionBadgePolicyTests: XCTestCase {
    private func status(bt: Bool = false, internet: Bool = false, connecting: Bool = false,
                        failed: Bool = false, enabled: Bool = true) -> ChatConnectionBadgeSnapshot {
        .resolve(bluetoothReady: bt, internetAvailable: internet,
                 bluetoothConnecting: connecting, bluetoothFailed: failed, enabled: enabled)
    }

    func testNetworkLossImmediatelyRemovesInternetAvailability() {
        XCTAssertEqual(status(internet: true).phase, .internetAvailable)
        XCTAssertEqual(status().phase, .disconnected)
    }

    func testNetworkReturnRecoversWithoutAnOutgoingMessage() {
        XCTAssertEqual(status(failed: true).phase, .failed)
        XCTAssertEqual(status(internet: true, failed: true).phase, .internetAvailable)
    }

    func testLocalInternetPathNeverClaimsVerifiedBluetooth() {
        let current = status(internet: true)
        XCTAssertEqual(current.phase, .internetAvailable)
        XCTAssertEqual(current.transport, .internet)
        XCTAssertFalse(current.bluetoothReady)
    }

    func testBluetoothTakesOverImmediatelyAndWinsOverLateInternetCompletion() {
        let current = status(bt: true, internet: true, failed: true)
        XCTAssertEqual(current.phase, .bluetoothConnected)
        XCTAssertEqual(current.transport, .bluetooth)
        XCTAssertTrue(current.bluetoothReady)
    }

    func testHandshakeIsNotVerifiedBluetooth() {
        XCTAssertEqual(status(connecting: true).phase, .connecting)
        XCTAssertFalse(status(connecting: true).bluetoothReady)
        XCTAssertEqual(status(internet: true, connecting: true).phase, .internetAvailable)
    }

    func testConstrainedInternetAvailabilityDoesNotUnlockBluetoothMedia() {
        // Constrained text still has an internet route; media uses bluetoothReady separately.
        XCTAssertFalse(status(internet: true).bluetoothReady)
        XCTAssertTrue(status(bt: true, internet: true).bluetoothReady)
    }

    func testHiddenInactiveHeaderHasNoStaleConnection() {
        XCTAssertEqual(status(enabled: false).phase, .idle)
        XCTAssertEqual(status(internet: true, enabled: false).phase, .internetAvailable)
    }

    func testAllInputCombinationsPreserveVerifiedBluetoothInvariant() {
        for bt in [false, true] { for internet in [false, true] {
            for connecting in [false, true] { for failed in [false, true] {
                let current = status(bt: bt, internet: internet, connecting: connecting, failed: failed)
                XCTAssertEqual(current.bluetoothReady, bt)
                XCTAssertEqual(current.phase == .internetAvailable, !bt && internet)
                if bt { XCTAssertEqual(current.transport, .bluetooth) }
            }}
        }}
    }
}
