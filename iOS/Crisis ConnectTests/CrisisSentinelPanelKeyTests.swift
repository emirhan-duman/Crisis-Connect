//
//  CrisisSentinelPanelKeyTests.swift
//  Crisis ConnectTests
//
//  The panel-key derivation MUST stay byte-identical to the web dashboard's `activeAgencyKey`
//  (dashboard/page.tsx) or cloud chats written on iPhone land in a panel the web never reads.
//  These are the same vectors as Android's CrisisSentinelCloudChatStoreTest.
//

import XCTest
@testable import Crisis_Connect

final class CrisisSentinelPanelKeyTests: XCTestCase {

    func testNormalizeAgencyDocumentIdKeepsCaseAndOnlyReplacesSlashes() {
        XCTAssertEqual(
            CrisisSentinelCloudChatStore.normalizeAgencyDocumentId(" Ankara/Merkez "),
            "Ankara-Merkez"
        )
        XCTAssertEqual(CrisisSentinelCloudChatStore.normalizeAgencyDocumentId("AFAD"), "AFAD")
        XCTAssertNil(CrisisSentinelCloudChatStore.normalizeAgencyDocumentId("   "))
        XCTAssertNil(CrisisSentinelCloudChatStore.normalizeAgencyDocumentId(nil))
    }

    func testToAgencyKeySlugifiesWithDiacriticStripping() {
        XCTAssertEqual(CrisisSentinelCloudChatStore.toAgencyKey("AFAD İstanbul"), "afad-istanbul")
        XCTAssertEqual(CrisisSentinelCloudChatStore.toAgencyKey("Sécurité Civile"), "securite-civile")
        XCTAssertEqual(CrisisSentinelCloudChatStore.toAgencyKey("FEMA"), "fema")
        XCTAssertEqual(CrisisSentinelCloudChatStore.toAgencyKey("  Protezione   Civile  "), "protezione-civile")
        XCTAssertNil(CrisisSentinelCloudChatStore.toAgencyKey("!!!"))
    }

    func testDerivePanelKeyPrecedence() {
        // defaultDashboardPanelId wins over everything.
        XCTAssertEqual(
            CrisisSentinelCloudChatStore.derivePanelKey([
                "defaultDashboardPanelId": "istanbul-il",
                "defaultPanelId": "other",
                "agencySlug": "another",
                "agencyName": "AFAD İstanbul",
            ]),
            "istanbul-il"
        )
        // Falls through to agencySlug when the panel ids are absent.
        XCTAssertEqual(
            CrisisSentinelCloudChatStore.derivePanelKey([
                "agencySlug": "izmir-afad",
                "agencyName": "AFAD İzmir",
            ]),
            "izmir-afad"
        )
        // Last resort: slugified agency name.
        XCTAssertEqual(
            CrisisSentinelCloudChatStore.derivePanelKey(["agencyName": "AFAD İstanbul"]),
            "afad-istanbul"
        )
        // No usable field → nil (sync disabled, never a guessed panel).
        XCTAssertNil(CrisisSentinelCloudChatStore.derivePanelKey(["role": "fieldteam"]))
    }

    func testConversationIdCloudTagging() {
        let id = CrisisSentinelConversationIds.cloud("abc123")
        XCTAssertTrue(CrisisSentinelConversationIds.isCloud(id))
        XCTAssertEqual(CrisisSentinelConversationIds.cloudDocId(id), "abc123")
        XCTAssertFalse(CrisisSentinelConversationIds.isCloud("local-uuid"))
    }
}
