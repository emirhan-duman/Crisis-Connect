import XCTest
@testable import Crisis_Connect

final class MessagingRegistrationLifecycleTests: XCTestCase {
    func testExplicitAccountRegistrationRejectsMissingAnonymousAndReplacedSessions() async {
        let probe = RegistrationProbe()
        let accounts: [(uid: String, isAnonymous: Bool)?] = [nil, ("alice", true), ("bob", false), ("alice", false)]
        for account in accounts {
            await MessagingRegistrar.ensureRegistered(
                forAuthenticatedUid: "alice", currentAccount: { account },
                register: { await probe.record($0) }
            )
        }
        let calls = await probe.calls
        XCTAssertEqual(calls, ["alice"])
    }

    func testRepeatedExplicitAccountRegistrationDoesNotDropANewAccount() async {
        let probe = RegistrationProbe()
        for uid in ["alice", "bob", "alice"] {
            await MessagingRegistrar.ensureRegistered(
                forAuthenticatedUid: uid, currentAccount: { (uid, false) },
                register: { await probe.record($0) }
            )
        }
        let calls = await probe.calls
        XCTAssertEqual(calls, ["alice", "bob", "alice"])
    }
}

private actor RegistrationProbe {
    private(set) var calls: [String] = []
    func record(_ uid: String) { calls.append(uid) }
}
