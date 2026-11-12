import CoreBluetooth
import CoreLocation
import XCTest
@testable import Crisis_Connect

final class SosContactDeliveryStateTests: XCTestCase {
    func testOfflineStartRestoresEveryRecipientWithOriginalMessageIDs() {
        let started = state(selected: ["a", "b"])
        let reopened = state(resuming: true, selected: ["changed-selection"], ids: started.messageIDs)
        XCTAssertEqual(reopened.messageIDs, started.messageIDs)
        XCTAssertTrue(reopened.isPending("a"))
        XCTAssertTrue(reopened.isPending("b"))
        XCTAssertNil(reopened.messageIDs["changed-selection"])
    }

    func testPartialDeliveryRestoresOnlyUnsentAlertsAsPending() {
        var started = state(selected: ["a", "b"])
        started.markAlertSent("a", location: nil)
        let reopened = state(resuming: true, ids: started.messageIDs, sent: started.sentMessageIDs)
        XCTAssertFalse(reopened.isPending("a"))
        XCTAssertTrue(reopened.isPending("b"))
        XCTAssertEqual(reopened.messageIDs["b"], started.messageIDs["b"])
    }

    func testLegacyDeliveredAlertsAreKeptWithoutAlertingNewContacts() {
        let reopened = state(resuming: true, selected: ["new"], sent: ["a": "original"])
        XCTAssertEqual(reopened.messageIDs, ["a": "original"])
        XCTAssertFalse(reopened.isPending("a"))
    }

    func testResumeBeforeRecipientSelectionAndFreshSessionCanSelectContacts() {
        XCTAssertTrue(state(resuming: true, selected: ["a"]).isPending("a"))
        let fresh = state(selected: ["b"], ids: ["a": "old"], sent: ["a": "old"])
        XCTAssertEqual(Set(fresh.messageIDs.keys), ["b"])
        XCTAssertTrue(fresh.isPending("b"))
    }

    func testOneSuccessfulLocationUpdateDoesNotSuppressAnotherRecipientsRetry() {
        var delivery = state(selected: ["a", "b"])
        let old = CLLocationCoordinate2D(latitude: 41, longitude: 29)
        let moved = CLLocationCoordinate2D(latitude: 41.001, longitude: 29)
        delivery.markAlertSent("a", location: old)
        delivery.markAlertSent("b", location: old)
        XCTAssertTrue(delivery.needsLocationUpdate("a", location: moved))
        delivery.markLocationSent("a", location: moved)
        // B's send failed. The next tick at the SAME position must retry B, but not A.
        XCTAssertFalse(delivery.needsLocationUpdate("a", location: moved))
        XCTAssertTrue(delivery.needsLocationUpdate("b", location: moved))
        delivery.markLocationSent("b", location: moved)
        XCTAssertFalse(delivery.needsLocationUpdate("b", location: moved))
    }

    func testFirstFixAfterLocationlessAlertAndResumeIsSentWithoutMovement() {
        var delivery = state(selected: ["a"])
        delivery.markAlertSent("a", location: nil)
        let fix = CLLocationCoordinate2D(latitude: 41, longitude: 29)
        XCTAssertTrue(delivery.needsLocationUpdate("a", location: fix))
        XCTAssertFalse(delivery.needsLocationUpdate("not-alerted", location: fix))
        delivery.markLocationSent("a", location: fix)
        XCTAssertFalse(delivery.needsLocationUpdate("a", location: fix))
        let reopened = state(resuming: true, ids: delivery.messageIDs, sent: delivery.sentMessageIDs)
        XCTAssertTrue(reopened.needsLocationUpdate("a", location: fix))
    }

    func testBluetoothReadinessNeverClaimsBroadcastingWithRadioOffOrNoAdvertisement() {
        XCTAssertEqual(SOSBluetoothBroadcastStatus(isBroadcasting: false, bluetoothState: .poweredOn), .unavailable)
        XCTAssertEqual(SOSBluetoothBroadcastStatus(isBroadcasting: true, bluetoothState: .poweredOff), .poweredOff)
        XCTAssertEqual(SOSBluetoothBroadcastStatus(isBroadcasting: false, bluetoothState: .unauthorized), .unauthorized)
        XCTAssertEqual(SOSBluetoothBroadcastStatus(isBroadcasting: false, bluetoothState: .unsupported), .unsupported)
        XCTAssertEqual(SOSBluetoothBroadcastStatus(isBroadcasting: false, bluetoothState: .resetting), .preparing)
        XCTAssertEqual(SOSBluetoothBroadcastStatus(isBroadcasting: true, bluetoothState: .poweredOn), .broadcasting)
    }

    private func state(resuming: Bool = false, selected: [String] = [], ids: [String: String] = [:], sent: [String: String] = [:]) -> SosContactDeliveryState {
        SosContactDeliveryState(resuming: resuming, selectedIDs: selected, messageIDs: ids, sentMessageIDs: sent)
    }
}
