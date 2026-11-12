import XCTest
@testable import Crisis_Connect

final class AuthorityMlsTrustTests: XCTestCase {
    func testAutomaticTrustRequiresACompleteUniqueDeviceSet() {
        XCTAssertTrue(AuthorityMlsTrust.automaticTrustEligible(
            deviceCount: 2, commitments: ["device-a", "device-b"]
        ))
        XCTAssertFalse(AuthorityMlsTrust.automaticTrustEligible(
            deviceCount: 0, commitments: []
        ))
        XCTAssertFalse(AuthorityMlsTrust.automaticTrustEligible(
            deviceCount: 2, commitments: ["device-a", "device-a"]
        ))
    }

    func testTrustVectorMatchesWebAndAndroid() throws {
        let records = [
            AuthorityMlsDirectoryRecord(
                uid: "u1", deviceId: "d1", credential: "cc-mls:v1:dTE:ZDE",
                signingPublicKey: Data(repeating: 1, count: 32), label: "one"
            ),
            AuthorityMlsDirectoryRecord(
                uid: "u1", deviceId: "d2", credential: "cc-mls:v1:dTE:ZDI",
                signingPublicKey: Data(repeating: 2, count: 32), label: "two"
            )
        ]
        let commitments = records.map(AuthorityMlsTrust.deviceCommitment).sorted()
        XCTAssertEqual(commitments, [
            "KUrMQ3EQrFjsAY7xUwZRy8KJ-JmNjBxTcwuMHAzA120",
            "Or3O_8ukemH97rdjnpgJ90RZ-xMdJCrFWpHnoAD-OPQ"
        ])
        XCTAssertEqual(
            try AuthorityMlsTrust.deviceSetFingerprint(commitments),
            "hspJmhlKDmNDB_H7JNvLmoliP_Dl7w0r_RVHo_BEXb4"
        )
        XCTAssertEqual(
            try AuthorityMlsTrust.safetyNumber(commitments),
            "142 118  216 027  103 003  210 092  042 031  090 004  202 113  133 020"
        )
    }
}
