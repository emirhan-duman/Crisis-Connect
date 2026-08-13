import XCTest
@testable import Crisis_Connect

final class AuthorityMlsDurableStateTests: XCTestCase {
    func testNearbyEnvelopeRoundTripsAndRejectsTampering() throws {
        let conversationId = "am2_" + String(repeating: "A", count: 43)
        let message = AuthorityMlsCiphertextMessage(
            messageId: "m_cross_platform",
            senderUid: "authority-user",
            senderDeviceId: "ios-device",
            senderCredential: "cc-mls:v1:YXV0aG9yaXR5LXVzZXI:aW9zLWRldmljZQ",
            ciphertext: "AQIDBA"
        )
        let encoded = try AuthorityMlsOfflineEnvelopeCodec.encode(
            conversationId: conversationId,
            message: message
        )
        let decoded = AuthorityMlsOfflineEnvelopeCodec.decode(encoded)
        XCTAssertEqual(decoded?.conversationId, conversationId)
        XCTAssertEqual(decoded?.message.messageId, message.messageId)
        XCTAssertEqual(decoded?.message.senderUid, message.senderUid)
        XCTAssertEqual(decoded?.message.senderDeviceId, message.senderDeviceId)
        XCTAssertEqual(decoded?.message.senderCredential, message.senderCredential)
        XCTAssertEqual(decoded?.message.ciphertext, message.ciphertext)
        XCTAssertNil(AuthorityMlsOfflineEnvelopeCodec.decode(encoded + "!"))
        XCTAssertThrowsError(try AuthorityMlsOfflineEnvelopeCodec.encode(
            conversationId: conversationId,
            message: AuthorityMlsCiphertextMessage(
                messageId: message.messageId, senderUid: "", senderDeviceId: message.senderDeviceId,
                senderCredential: message.senderCredential, ciphertext: message.ciphertext
            )
        ))
    }

    func testRoundTripsSnapshotCursorAndOutbox() throws {
        let state = AuthorityMlsDurableState(
            snapshot: Data([1, 2, 3, 255]),
            nextControlSequence: (1 << 40) + 17,
            nextApplicationSequence: (1 << 39) + 9,
            pendingControlEvents: ["{\"type\":\"shareKeyPackage\"}", "güvenli"],
            pendingApplicationMessages: [AuthorityMlsPendingApplication(messageId: "m_abc123", ciphertext: "AQIDBA")],
            pendingReceivedApplications: [AuthorityMlsPendingReceivedApplication(
                messageId: "m_received", senderCredential: "cc-mls:v1:dTE:ZDE", plaintext: Data([4, 5, 6])
            )],
            offlineReceipts: [AuthorityMlsOfflineReceipt(
                messageId: "m_offline",
                senderCredential: "cc-mls:v1:dTI:ZDI",
                ciphertextHash: String(repeating: "A", count: 43)
            )]
        )
        let decoded = try AuthorityMlsDurableStateCodec.decode(
            AuthorityMlsDurableStateCodec.encode(state)
        )
        XCTAssertEqual(decoded.snapshot, state.snapshot)
        XCTAssertEqual(decoded.nextControlSequence, state.nextControlSequence)
        XCTAssertEqual(decoded.nextApplicationSequence, state.nextApplicationSequence)
        XCTAssertEqual(decoded.pendingControlEvents, state.pendingControlEvents)
        XCTAssertEqual(decoded.pendingApplicationMessages, state.pendingApplicationMessages)
        XCTAssertEqual(decoded.pendingReceivedApplications.first?.messageId, "m_received")
        XCTAssertEqual(decoded.pendingReceivedApplications.first?.plaintext, Data([4, 5, 6]))
        XCTAssertEqual(decoded.offlineReceipts, state.offlineReceipts)
    }

    func testRejectsTruncationTrailingDataAndNegativeSequence() throws {
        let valid = try AuthorityMlsDurableStateCodec.encode(
            AuthorityMlsDurableState(
                snapshot: Data([9]),
                nextControlSequence: 0,
                nextApplicationSequence: 0,
                pendingControlEvents: ["event"],
                pendingApplicationMessages: [],
                pendingReceivedApplications: [],
                offlineReceipts: []
            )
        )
        XCTAssertThrowsError(try AuthorityMlsDurableStateCodec.decode(valid.dropLast()))
        XCTAssertThrowsError(try AuthorityMlsDurableStateCodec.decode(valid + Data([0])))
        XCTAssertThrowsError(try AuthorityMlsDurableStateCodec.encode(
            AuthorityMlsDurableState(
                snapshot: Data([1]),
                nextControlSequence: -1,
                nextApplicationSequence: 0,
                pendingControlEvents: [],
                pendingApplicationMessages: [],
                pendingReceivedApplications: [],
                offlineReceipts: []
            )
        ))
    }
}
