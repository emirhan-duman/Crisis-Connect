//
//  SafetyNumberTests.swift
//  Crisis ConnectTests
//
//  The fingerprint vector replicates Android's SafetyNumber (SHA-512 → first 16 bytes as an
//  unsigned big integer → mod 10^30, zero-padded). If this fails, the two platforms would show
//  users DIFFERENT safety numbers for the same key pair — reconcile before shipping.
//

import XCTest
@testable import Crisis_Connect

final class SafetyNumberTests: XCTestCase {

    func testFingerprintMatchesAndroidVector() {
        XCTAssertEqual(
            SafetyNumber.fingerprint(publicKeyB64: "KEYLOCALB64==", uid: "uidA"),
            "826573556481912426003615653427"
        )
        XCTAssertEqual(
            SafetyNumber.fingerprint(publicKeyB64: "KEYREMOTEB64==", uid: "uidB"),
            "010859217262364314539044948548"
        )
    }

    func testComputeGroupsAndOrdersLikeAndroid() {
        let expected = "01085 92172 62364 31453 90449 48548 82657 35564 81912 42600 36156 53427"
        XCTAssertEqual(
            SafetyNumber.compute(
                localPublicKeyB64: "KEYLOCALB64==",
                remotePublicKeyB64: "KEYREMOTEB64==",
                localUid: "uidA",
                remoteUid: "uidB"
            ),
            expected
        )
    }

    func testBothPeersComputeTheSameNumber() {
        let mine = SafetyNumber.compute(
            localPublicKeyB64: "KEYLOCALB64==",
            remotePublicKeyB64: "KEYREMOTEB64==",
            localUid: "uidA",
            remoteUid: "uidB"
        )
        let theirs = SafetyNumber.compute(
            localPublicKeyB64: "KEYREMOTEB64==",
            remotePublicKeyB64: "KEYLOCALB64==",
            localUid: "uidB",
            remoteUid: "uidA"
        )
        XCTAssertEqual(mine, theirs)
    }

    func testPhoneNormalization() {
        XCTAssertEqual(DeviceContactsReader.normalizeToE164("+90 532 111 22 33", defaultDialCode: nil), "+905321112233")
        XCTAssertEqual(DeviceContactsReader.normalizeToE164("0090 532 111 22 33", defaultDialCode: nil), "+905321112233")
        XCTAssertEqual(DeviceContactsReader.normalizeToE164("0532 111 22 33", defaultDialCode: "90"), "+905321112233")
        XCTAssertNil(DeviceContactsReader.normalizeToE164("0532 111 22 33", defaultDialCode: nil))
        XCTAssertNil(DeviceContactsReader.normalizeToE164("112", defaultDialCode: "90"))
    }
}
