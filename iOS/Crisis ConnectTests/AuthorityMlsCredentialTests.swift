import XCTest
@testable import Crisis_Connect

final class AuthorityMlsCredentialTests: XCTestCase {
    func testCredentialRoundTripsWithoutDelimiterAmbiguity() throws {
        let encoded = try AuthorityMlsCredential.encode(
            accountUid: "uid:with/slash",
            deviceId: "cc-00112233445566778899aabb"
        )
        XCTAssertEqual(
            AuthorityMlsCredential.decode(encoded),
            .init(accountUid: "uid:with/slash", deviceId: "cc-00112233445566778899aabb")
        )
    }

    func testCredentialRejectsMalformedAndEmptyValues() {
        XCTAssertNil(AuthorityMlsCredential.decode("cc-mls:v1:not base64:x"))
        XCTAssertThrowsError(try AuthorityMlsCredential.encode(accountUid: "", deviceId: "device"))
    }
}
