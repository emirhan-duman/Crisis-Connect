import XCTest
@testable import Crisis_Connect

final class AuthorityMlsTransportTests: XCTestCase {
    func testIdentifiersMatchCrossPlatformVectors() throws {
        let first = try AuthorityMlsIdentifiers.conversationId(AuthorityMlsBinding(
            scopeType: .agency,
            channelId: "ankara",
            participants: ["u2", "u1"]
        ))
        let reordered = try AuthorityMlsIdentifiers.conversationId(AuthorityMlsBinding(
            scopeType: .agency,
            channelId: "ankara",
            participants: ["u1", "u2"]
        ))
        XCTAssertEqual(first, "am2_vvDRM4CAUnWzulIh43GmvwOHV2so1SHHUbGgYEQA1Rs")
        XCTAssertEqual(first, reordered)
        XCTAssertNotEqual(first, try AuthorityMlsIdentifiers.conversationId(AuthorityMlsBinding(
            scopeType: .hierarchy,
            channelId: "ankara",
            participants: ["u1", "u2"]
        )))
        XCTAssertEqual(
            try AuthorityMlsIdentifiers.controlEventId(
                conversationId: "am2_test",
                sequence: 7,
                senderCredential: "cc-mls:v1:dTE:ZDE",
                payload: "payload-a"
            ),
            "c_P1CEdhUZK8L-JW5uRkiOo4hi4sGj70AOMkOu73VV4ck"
        )
    }
}
