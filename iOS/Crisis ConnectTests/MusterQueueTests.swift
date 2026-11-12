import BackgroundTasks
import Foundation
import XCTest
import SwiftUI
import UIKit
@testable import Crisis_Connect

private final class MemoryMusterStore: MusterPersisting, @unchecked Sendable {
    private let lock = NSLock(); private var data: Data?; var fail = false
    func load() throws -> Data? { lock.lock(); defer { lock.unlock() }; return data }
    func save(_ value: Data) throws { lock.lock(); defer { lock.unlock() }; if fail { throw MusterError.persistence }; data = value }
}
final class MusterQueueTests: XCTestCase {
#if DEBUG
    @MainActor
    func testOfflineHTTPSLANRequiresCanonicalPrivateIPv4AndPinnedCA() throws {
        // Synthetic boundary values from the RFC 1918 ranges. No network requests are made.
        let exampleLAN = "192.168.0.0"
        let allowed = ["10.0.0.0", "172.16.0.0", "172.31.255.255", exampleLAN]
        let rejected = ["127.0.0.1", "172.15.0.1", "172.32.0.1", "192.169.1.1", "10.00.0.1",
                        "10.+1.0.1", "10.0.0.256", "10.0.1", "10..0.1", "10.0.0.1.", "::1", "example.test"]
        for address in allowed { XCTAssertTrue(MusterOfflineHTTPS.isCanonicalLANIPv4(address), address) }
        for address in rejected { XCTAssertFalse(MusterOfflineHTTPS.isCanonicalLANIPv4(address), address) }

        func manifest(origin: String) throws -> String {
            let data = try JSONSerialization.data(withJSONObject: [
                "origin": origin,
                "bearer": String(repeating: "a", count: 43),
                "uid": "qa-muster-ios",
                "panelId": "native-qa-12345678-1234-1234-1234-123456789012",
                "sessionId": String(repeating: "b", count: 32),
                "expiresAtMillis": Int64(Date().addingTimeInterval(300).timeIntervalSince1970 * 1_000)
            ])
            return String(decoding: data, as: UTF8.self)
        }

        let local = try manifest(origin: "https://\(exampleLAN):19089")
        XCTAssertThrowsError(try MusterOfflineHTTPS(json: local, caPEM: nil, approvedLANIPv4: exampleLAN))
        let loopback = try MusterOfflineHTTPS(json: manifest(origin: "https://127.0.0.1:19089"), caPEM: nil)
        loopback.close()
    }
#endif

    func testBackgroundDeliveryRequestRequiresNetworkAndWaitsForRetry() {
        let now = Date(timeIntervalSince1970: 1_000)
        let ready = MusterBackgroundDelivery.request(nextAt: 1_001, now: now)
        let backingOff = MusterBackgroundDelivery.request(nextAt: 1_060, now: now)
        XCTAssertEqual(ready.identifier, "com.auralis.crisisconnect.muster-send")
        XCTAssertTrue(ready.requiresNetworkConnectivity)
        XCTAssertEqual(ready.earliestBeginDate, now.addingTimeInterval(30))
        XCTAssertEqual(backingOff.earliestBeginDate, now.addingTimeInterval(60))
    }

    func testOnlyAnExactTypedReceiptCanMarkAnAnswerSent() throws {
        let valid = try JSONSerialization.data(withJSONObject: ["ok": true, "id": "drill", "eventId": String(repeating: "a", count: 32), "applied": false])
        XCTAssertTrue(acceptsMusterReceipt(status: 200, data: valid, session: "drill"))
        XCTAssertFalse(acceptsMusterReceipt(status: 503, data: valid, session: "drill"))
        XCTAssertFalse(acceptsMusterReceipt(status: 200, data: valid, session: "other"))
        let invalid = try JSONSerialization.data(withJSONObject: ["ok": "true", "id": "drill", "eventId": String(repeating: "a", count: 32)])
        XCTAssertFalse(acceptsMusterReceipt(status: 200, data: invalid, session: "drill"))
    }
    func testRestartReplayAndAccountIsolation() async throws {
        let storage = MemoryMusterStore(), queue = MusterQueue(storage: MemoryMusterStore())
        _ = try await queue.enqueue(uid: "other", panel: "factory", session: "drill", answer: "safe")
        let first = MusterQueue(storage: storage), time = Date(timeIntervalSince1970: 1000)
        let event = try await first.enqueue(uid: "alice", panel: "factory", session: "drill", answer: "help", note: "Stair exit", now: time, operationId: "event-id")
        let restarted = MusterQueue(storage: storage)
        _ = try await restarted.enqueue(uid: "bob", panel: "factory", session: "drill", answer: "safe", now: time)
        let claimed = try await restarted.claim(uid: "alice", now: time)
        XCTAssertEqual(claimed?.id, event.id)
        let early = try await restarted.claim(uid: "alice", now: time.addingTimeInterval(1)); XCTAssertNil(early)
        try await restarted.finish(claimed!, state: "pending", error: "connection", now: time)
        let retry = try await restarted.claim(uid: "alice", now: time.addingTimeInterval(1000))
        XCTAssertEqual(retry?.answeredAtMillis, event.answeredAtMillis); XCTAssertEqual(retry?.note, "Stair exit")
        try await restarted.finish(retry!, state: "sent")
        let alice = try await restarted.events(uid: "alice"), bob = try await restarted.events(uid: "bob")
        XCTAssertEqual(alice.single?.state, "sent"); XCTAssertEqual(bob.single?.state, "pending")
    }
    func testReconnectMakesOnlyCompletedConnectionFailuresImmediatelyClaimable() async throws {
        let storage = MemoryMusterStore()
        let queue = MusterQueue(storage: storage)
        let offlineAt = Date(timeIntervalSince1970: 1_000)
        let claimAt = offlineAt.addingTimeInterval(61)
        let restoredAt = claimAt.addingTimeInterval(1)

        let first = try await queue.enqueue(uid: "alice", panel: "factory", session: "drill", answer: "safe",
                                            now: offlineAt, operationId: "leased")
        let firstClaim = try await queue.claim(uid: "alice", now: offlineAt)
        try await queue.finish(XCTUnwrap(firstClaim), state: "pending", error: "connection", now: offlineAt)
        let activeClaim = try await queue.claim(uid: "alice", now: claimAt)
        XCTAssertEqual(activeClaim?.id, first.id)

        let failures = [
            (id: "network", error: "connection", state: "pending"),
            (id: "service", error: "http-503", state: "pending"),
            (id: "request", error: "request", state: "pending"),
            (id: "permanent", error: "http-403", state: "failed"),
        ]
        for failure in failures {
            let event = try await queue.enqueue(uid: "alice", panel: "factory", session: "drill", answer: "safe",
                                                now: claimAt, operationId: failure.id)
            let claim = try await queue.claim(uid: "alice", now: claimAt)
            XCTAssertEqual(claim?.id, event.id)
            try await queue.finish(XCTUnwrap(claim), state: failure.state, error: failure.error, now: claimAt)
        }

        let before = try await queue.events(uid: "alice")
        let restarted = MusterQueue(storage: storage)
        try await restarted.releaseConnectionBackoff(uid: "alice", now: restoredAt)
        let after = try await restarted.events(uid: "alice")
        XCTAssertEqual(before.filter { $0.id != "network" }, after.filter { $0.id != "network" })
        XCTAssertEqual(after.first(where: { $0.id == "network" })?.nextAt, restoredAt.timeIntervalSince1970)
        let retry = try await restarted.claim(uid: "alice", now: restoredAt)
        XCTAssertEqual(retry?.id, "network")
        XCTAssertEqual(retry?.serverEventId, before.first(where: { $0.id == "network" })?.serverEventId)
        XCTAssertEqual(retry?.attempts, 2)

        try await restarted.releaseConnectionBackoff(uid: "alice", now: restoredAt)
        let duplicateClaim = try await restarted.claim(uid: "alice", now: restoredAt)
        XCTAssertNil(duplicateClaim)
    }
    func testPersistenceFailureDoesNotInventASavedResponse() async throws {
        let storage = MemoryMusterStore(); storage.fail = true; let queue = MusterQueue(storage: storage)
        do { _ = try await queue.enqueue(uid: "alice", panel: "factory", session: "drill", answer: "safe"); XCTFail("Must fail before acknowledging save") } catch {}
        let events = try await queue.events(uid: "alice"); XCTAssertTrue(events.isEmpty)
    }
    func testPermanentFailureIsRetainedAndCapacityNeverEvictsPendingEvents() async throws {
        let queue = MusterQueue(storage: MemoryMusterStore()), time = Date(timeIntervalSince1970: 1000)
        for index in 0..<256 { _ = try await queue.enqueue(uid: "alice", panel: "factory", session: "drill", answer: "safe", now: time, operationId: "event-\(index)") }
        do { _ = try await queue.enqueue(uid: "alice", panel: "factory", session: "drill", answer: "help"); XCTFail("Full queue must refuse") } catch {}
        let event = try await queue.claim(uid: "alice", now: time)!
        try await queue.finish(event, state: "failed", error: "http-403")
        let events = try await queue.events(uid: "alice"); XCTAssertEqual(events.count, 256); XCTAssertEqual(events.first?.state, "failed")
    }
    func testWakeCachePreservesLiveAndDrillModesWithoutChangingHistory() async throws {
        let queue = MusterQueue(storage: MemoryMusterStore())
        try await queue.cacheWake(uid: "alice", panel: "factory", session: "live-session", kind: "live")
        try await queue.cacheWake(uid: "alice", panel: "factory", session: "drill-session", kind: "drill")
        try await queue.cacheWake(uid: "alice", panel: "factory", session: "drill-session", kind: "live")
        let rows = try await queue.assignments(uid: "alice")
        XCTAssertEqual(rows.map { $0.assignment.session.kind }, ["live", "drill"])
        let other = try await queue.assignments(uid: "bob"); XCTAssertTrue(other.isEmpty)
        do { try await queue.cacheWake(uid: "alice", panel: "factory", session: "unknown", kind: "unknown"); XCTFail("Unknown mode must fail") } catch {}
    }
    @MainActor func testRealUnansweredCheckRemainsActionableAfterDeadline() {
        func row(_ kind: String, status: String = "active", answer: String? = nil) -> CachedMusterAssignment {
            CachedMusterAssignment(panelId: "factory", assignment: MusterAssignment(
                session: MusterSession(id: "session", kind: kind, title: "Check in", instructions: "Report", status: status, deadlineAtMillis: 200),
                response: MusterOwnResponse(answer: answer)))
        }
        XCTAssertTrue(musterNeedsResponse(row("live"), latest: nil, now: 300))
        XCTAssertFalse(musterNeedsResponse(row("drill"), latest: nil, now: 300))
        XCTAssertFalse(musterNeedsResponse(row("live", status: "closed"), latest: nil, now: 150))
        XCTAssertFalse(musterNeedsResponse(row("live", answer: "safe"), latest: nil, now: 150))
        let queued = MusterEvent(uid: "alice", panelId: "factory", sessionId: "session", operationId: "event", answer: "help", note: "", answeredAtMillis: 150)
        XCTAssertFalse(musterNeedsResponse(row("live"), latest: queued, now: 150))
    }

    @MainActor func testNewerServerProjectionIsNotHiddenByAnOlderLocalReceipt() {
        let model = MusterViewModel()
        let old = MusterEvent(uid: "alice", panelId: "factory", sessionId: "session", operationId: "event", answer: "help", note: "Old note", answeredAtMillis: 100, state: "sent")
        model.events = [old]
        let row = CachedMusterAssignment(panelId: "factory", assignment: MusterAssignment(
            session: MusterSession(id: "session", kind: "live", title: "Check", instructions: "Report", status: "active"),
            response: MusterOwnResponse(answer: "safe", answeredAtMillis: 200)))
        XCTAssertNil(model.latest(row))
        model.events = [MusterEvent(uid: "alice", panelId: "factory", sessionId: "session", operationId: "pending", answer: "help", note: "New note", answeredAtMillis: 300)]
        XCTAssertEqual(model.latest(row)?.answer, "help")
    }

    @MainActor func testCaptureIsolatedMusterPresentationForDesignReview() throws {
        let previousLanguage = UserDefaults.standard.object(forKey: "appLanguage")
        UserDefaults.standard.set("tr", forKey: "appLanguage")
        defer { if let previousLanguage { UserDefaults.standard.set(previousLanguage, forKey: "appLanguage") } else { UserDefaults.standard.removeObject(forKey: "appLanguage") } }
        // Render the production SwiftUI components using in-memory fixtures only.
        // No refresh, authentication change, queue write or notification send occurs here.
        let row = CachedMusterAssignment(panelId: "factory", assignment: MusterAssignment(
            session: MusterSession(id: "session", kind: "live", title: "Personel yoklaması", instructions: "Lütfen güvenlik durumunu bildir.", status: "active")))
        let model = MusterViewModel(); model.uid = "design-fixture"; model.assignments = [row]
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { XCTFail("A render scene is required"); return }
        let previous = scene.windows.first(where: \.isKeyWindow)
        func capture<V: View>(_ view: V, name: String) {
            let window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
            window.overrideUserInterfaceStyle = .light
            let host = UIHostingController(rootView: view.environment(\.colorScheme, .light))
            window.rootViewController = host; window.makeKeyAndVisible()
            host.view.frame = window.bounds; host.view.setNeedsLayout(); host.view.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.25))
            let rendered = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let attachment = XCTAttachment(image: rendered); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
            window.isHidden = true
        }
        capture(VStack(spacing: 0) { MusterHomeCard(row: row, latest: nil); Spacer() }, name: "muster-ios-home")
        capture(ScrollView { MusterAssignmentRow(row: row, model: model).padding(24) }, name: "muster-ios-response")
        let configuredRow = CachedMusterAssignment(
            panelId: "factory",
            assignment: MusterAssignment(session: MusterSession(
                id: "configured-session", kind: "live", title: "Personel yoklaması",
                instructions: "Lütfen durumunu bildir. Gerekirse not veya sesli mesaj ekle.",
                status: "active",
                responseConfig: MusterResponseConfig(version: 1, question: "", options: [
                    MusterResponseOption(id: "safe", label: "", meaning: "safe"),
                    MusterResponseOption(id: "help", label: "", meaning: "help"),
                    MusterResponseOption(id: "at_muster", label: "", meaning: "at_muster"),
                ], allowText: true, allowVoice: true))))
        capture(
            ScrollView { MusterAssignmentRow(row: configuredRow, model: model).padding(AppTheme.screenPadding) }
                .background(Color.appBackground),
            name: "muster-ios-configured-response"
        )
        model.events = [MusterEvent(uid: "design-fixture", panelId: "factory", sessionId: "session", operationId: "event", answer: "safe", note: "", answeredAtMillis: 100)]
        capture(ScrollView { MusterAssignmentRow(row: row, model: model).padding(24) }, name: "muster-ios-pending")
        previous?.makeKeyAndVisible()
    }

    @MainActor func testMusterStringsFollowTheAppLanguageAndUseEnglishFallback() {
        let previous = UserDefaults.standard.object(forKey: "appLanguage")
        defer { if let previous { UserDefaults.standard.set(previous, forKey: "appLanguage") } else { UserDefaults.standard.removeObject(forKey: "appLanguage") } }
        UserDefaults.standard.set("tr", forKey: "appLanguage")
        XCTAssertEqual(musterText("title"), "Personel yoklaması")
        UserDefaults.standard.set("en", forKey: "appLanguage")
        XCTAssertEqual(musterText("title"), "Personnel check-in")
        UserDefaults.standard.set("ja", forKey: "appLanguage")
        XCTAssertEqual(musterText("title"), "職員の安否確認")
        UserDefaults.standard.set("unknown", forKey: "appLanguage")
        XCTAssertEqual(musterText("title"), "Personnel check-in")
    }

}
private extension Array { var single: Element? { count == 1 ? first : nil } }
