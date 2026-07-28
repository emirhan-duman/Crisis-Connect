//
//  EmergencyNumberResolverTests.swift
//  Crisis ConnectTests
//
//  Locks the region→emergency-number table against Android's EmergencyNumberResolver so the SOS
//  call button dials the right number per region (a wrong number in an emergency is a real harm).
//

import XCTest

@testable import Crisis_Connect

final class EmergencyNumberResolverTests: XCTestCase {

    func testKnownRegionsMatchAndroidTable() {
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "TR"), "112")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "US"), "911")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "GB"), "999")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "AU"), "000")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "JP"), "110")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "BR"), "190")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "ZA"), "10111")
    }

    func testLowercaseIsoIsNormalized() {
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "us"), "911")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "tr"), "112")
    }

    func testUnknownRegionDefaultsTo112() {
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: "ZZ"), "112")
        XCTAssertEqual(EmergencyNumberResolver.number(forCountryIso: ""), "112")
    }

    func testResolveWithRegionAlwaysReturnsANumber() {
        // Whatever the simulator's region, a dialable number must come back (default 112).
        let resolved = EmergencyNumberResolver.resolveWithRegion()
        XCTAssertFalse(resolved.number.isEmpty)
    }
}
