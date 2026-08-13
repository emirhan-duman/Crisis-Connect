import XCTest
@testable import Crisis_Connect

final class ResourceAlertWakeClientTests: XCTestCase {
    func testParsesOnlyBoundedContentFreeWake() throws {
        let payload = try XCTUnwrap(ResourceAlertWakePayload(userInfo: [
            "type": "resource_alert_wake",
            "panelId": "afad-istanbul",
            "attemptId": "attempt:device-1",
            "receiptNonce": "11111111-1111-4111-8111-111111111111",
            "unexpected": "ignored",
        ]))
        XCTAssertEqual(payload.panelId, "afad-istanbul")
        XCTAssertNil(ResourceAlertWakePayload(userInfo: [
            "type": "resource_alert_wake",
            "panelId": "../foreign",
            "attemptId": "attempt-1",
            "receiptNonce": "11111111-1111-4111-8111-111111111111",
        ]))
        XCTAssertNil(ResourceAlertWakePayload(userInfo: [
            "type": "chat",
            "panelId": "afad",
            "attemptId": "attempt-1",
            "receiptNonce": "11111111-1111-4111-8111-111111111111",
        ]))
    }

    func testBuildsAuthenticatedNativeAckWithoutAddingAlertContent() throws {
        let payload = try XCTUnwrap(ResourceAlertWakePayload(userInfo: [
            "type": "resource_alert_wake",
            "panelId": "afad",
            "attemptId": "attempt-device-1",
            "receiptNonce": "11111111-1111-4111-8111-111111111111",
        ]))
        let request = try payload.makeRequest(
            endpoint: XCTUnwrap(URL(string: "https://crisisconnect.network/api/dashboard/resource-alert-inbox")),
            idToken: "firebase-token"
        )
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer firebase-token")
        let body = try XCTUnwrap(request.httpBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(json, [
            "panelId": "afad",
            "action": "ackWake",
            "attemptId": "attempt-device-1",
            "receiptNonce": "11111111-1111-4111-8111-111111111111",
            "source": "native",
        ])
        XCTAssertNil(json["alertId"])
        XCTAssertNil(json["message"])
    }
}
