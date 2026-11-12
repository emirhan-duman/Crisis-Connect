import XCTest
import FirebaseFunctions
@testable import Crisis_Connect

final class AuthorityMeshKeyAccessTests: XCTestCase {
    private func failure(_ code: FunctionsErrorCode) -> AuthorityMeshKeyAccess {
        .failure(NSError(domain: FunctionsErrorDomain, code: code.rawValue))
    }

    func testMissingBackendDoesNotClaimUnauthorized() {
        XCTAssertEqual(failure(.notFound), .serviceUnavailable)
        XCTAssertEqual(failure(.unimplemented).errorMessageKey, "RESCUE_MESH_KEY_SERVICE_UNAVAILABLE")
        XCTAssertNotEqual(failure(.notFound).errorMessageKey, failure(.permissionDenied).errorMessageKey)
    }

    func testAuthorizationRolloutAndSignInAreDistinct() {
        XCTAssertEqual(failure(.permissionDenied), .notAuthorized)
        XCTAssertEqual(failure(.failedPrecondition), .notEnabled)
        XCTAssertEqual(failure(.unauthenticated), .signInRequired)
        XCTAssertEqual(AuthorityMeshKeyAccess.failure(MissingRoleCertificateError.missing), .certificateRequired)
    }

    func testNetworkAndUnexpectedFailuresDoNotClaimUnauthorized() {
        XCTAssertEqual(failure(.unavailable), .networkError)
        XCTAssertEqual(failure(.deadlineExceeded), .networkError)
        XCTAssertEqual(AuthorityMeshKeyAccess.failure(URLError(.notConnectedToInternet)), .networkError)
        XCTAssertEqual(AuthorityMeshKeyAccess.failure(NSError(domain: "invalid-response", code: 1)), .failed)
        XCTAssertNil(AuthorityMeshKeyAccess.available.errorMessageKey)
    }
}
