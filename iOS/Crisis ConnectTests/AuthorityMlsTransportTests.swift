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
        XCTAssertEqual(first, "am2_36om86YSvFJ90u_qaV8unF2vgbwU75BrB87jRIT7wPw")
        XCTAssertEqual(first, reordered)
        let independent = AuthorityMlsBinding(scopeType: .agency, channelId: "ankara", participants: ["u2", "u1"], resilient: true)
        XCTAssertEqual(try AuthorityMlsIdentifiers.conversationId(independent), "am2_Q34xV18_kMERdYo6alt4AfiSGWizq-ISGFglVhtEjHQ")
        XCTAssertTrue(try AuthorityMlsIdentifiers.legacyConversationIds(independent).isEmpty)
        XCTAssertEqual(try AuthorityMlsIdentifiers.legacyConversationIds(AuthorityMlsBinding(
            scopeType: .agency,
            channelId: "ankara",
            participants: ["u2", "u1"]
        )), [
            "am2_lstm_ouUI1rNUblPFA_yc2mCJCVvUVN3vwSdORLDlMk",
            "am2_XQTDvUgIzCL0dW3dmJdXah8A_Wm9Bb2UDhUxYQa0eKI",
            "am2_vvDRM4CAUnWzulIh43GmvwOHV2so1SHHUbGgYEQA1Rs",
            "am2_57M0l0d9Te7ypH32OmZ89TFyAV9zUndBflhQQGk4QgI",
            "am2_fF4HTidtEcLtqh80WthF4MEYGxOZGW0_SzBYC7NS328",
            "am2_-0xwogGrbE5ImXGISHIDBuZYKFGAWpJxqA7j3Sa8vf8",
            "am2_tQZALic0fIKnNzoxfkuOa1cZQYP66Ju48Ybfs_j4N_g"
        ])
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
