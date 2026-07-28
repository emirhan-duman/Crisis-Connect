//
//  CrisisSentinelCardTests.swift
//  Crisis ConnectTests
//
//  The `_card` parser must accept the web chat API's payload shapes (nested sub-object for most
//  kinds, flat for quake/facility/damage) and stay lenient on missing fields.
//

import XCTest
@testable import Crisis_Connect

final class CrisisSentinelCardTests: XCTestCase {

    func testNestedWeatherCard() {
        let json = #"{"kind":"weather","weather":{"label":"İstanbul","tempC":23,"windKmh":12.5,"humidity":60}}"#
        guard case .weather(let w)? = parseCrisisSentinelCard(json) else { return XCTFail("not weather") }
        XCTAssertEqual(w.label, "İstanbul")
        XCTAssertEqual(w.tempC, 23)
        XCTAssertEqual(w.windKmh, 12.5)
        XCTAssertEqual(w.humidity, 60)
        XCTAssertNil(w.gustKmh)
    }

    func testFlatQuakeCardWithEvents() {
        let json = #"""
        {"kind":"quake","region":"Marmara","events":[
          {"magnitude":4.2,"depth":7,"place":"Silivri","lat":41.0,"lon":28.2},
          {"magnitude":3.1,"place":"Adalar"}
        ]}
        """#
        guard case .quake(let q)? = parseCrisisSentinelCard(json) else { return XCTFail("not quake") }
        XCTAssertEqual(q.region, "Marmara")
        XCTAssertEqual(q.events.count, 2)
        XCTAssertEqual(q.events[0].magnitude, 4.2)
        XCTAssertEqual(q.events[0].lat, 41.0)
        XCTAssertNil(q.events[1].lat)
    }

    func testFlatDamageCardHazards() {
        let json = #"{"kind":"damage","rating":"Severe","summary":"Widespread","hazards":["gas leak","",  "collapse"]}"#
        guard case .damage(let d)? = parseCrisisSentinelCard(json) else { return XCTFail("not damage") }
        XCTAssertEqual(d.rating, "Severe")
        // Blank hazard entries are dropped.
        XCTAssertEqual(d.hazards, ["gas leak", "collapse"])
    }

    func testAlertsWhenFieldMapsToWhenText() {
        let json = #"{"kind":"alerts","alerts":{"title":"Storm","items":[{"severity":"severe","label":"Wind","when":"18:00"}]}}"#
        guard case .alerts(let a)? = parseCrisisSentinelCard(json) else { return XCTFail("not alerts") }
        XCTAssertEqual(a.items.first?.whenText, "18:00")
        XCTAssertEqual(a.items.first?.severity, "severe")
    }

    func testUnknownAndManagerOnlyKindsReturnNil() {
        XCTAssertNil(parseCrisisSentinelCard(#"{"kind":"aiUsage","total":42}"#))
        XCTAssertNil(parseCrisisSentinelCard(#"{"kind":"totallyUnknown"}"#))
        XCTAssertNil(parseCrisisSentinelCard("not json"))
    }

    func testFmtDropsTrailingZero() {
        XCTAssertEqual(fmt(23.0), "23")
        XCTAssertEqual(fmt(23.4), "23.4")
        XCTAssertNil(fmt(nil))
    }
}
