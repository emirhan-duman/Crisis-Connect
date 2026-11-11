import XCTest
import Foundation
import FirebaseCore
import FirebaseAuth
import FirebaseFunctions
import FirebaseAppCheck
@testable import Crisis_Connect

final class TrafficRegionLiveProbeTests: XCTestCase {
 func testAuthenticatedMobileSDKRegions() async throws {
  let url = URL(string: "http://127.0.0.1:31234/ios")!
  let fixture: [String: Any]
  do {
   let (data, _) = try await URLSession.shared.data(from: url)
   fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  } catch { throw XCTSkip("Explicit localhost live fixture required") }
  func post(_ body: [String: Any]) async throws {
   var request = URLRequest(url: url)
   request.httpMethod = "POST"
   request.httpBody = try JSONSerialization.data(withJSONObject: body)
   let (_, response) = try await URLSession.shared.data(for: request)
   XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
  }
  let run = try XCTUnwrap(fixture["crossRunId"] as? String)
  let previousDebugToken = ProcessInfo.processInfo.environment["FIRAAppCheckDebugToken"]
  setenv("FIRAAppCheckDebugToken", try XCTUnwrap(fixture["debugToken"] as? String), 1)
  AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
  defer {
   if let previousDebugToken { setenv("FIRAAppCheckDebugToken", previousDebugToken, 1) }
   else { unsetenv("FIRAAppCheckDebugToken") }
   AppCheck.setAppCheckProviderFactory(CrisisConnectAppCheckProviderFactory())
  }
  let name = "region-probe-ios-\(run)"
  FirebaseApp.configure(name: name, options: try XCTUnwrap(FirebaseApp.app()?.options))
  let app = try XCTUnwrap(FirebaseApp.app(name: name))
  let auth = Auth.auth(app: app)
  do {
   try await post(["probeStatus": "running"])
   _ = try await XCTUnwrap(AppCheck.appCheck(app: app)).token(forcingRefresh: true)
   let uid = try await auth.signInAnonymously().user.uid
   try await post(["uids": [uid]])
   let idToken = try await XCTUnwrap(auth.currentUser).getIDToken()
   let appCheckToken = try await XCTUnwrap(AppCheck.appCheck(app: app)).token(forcingRefresh: false).token
   var accessChecks: [[String: Any]] = []
   func verifyRejected(_ region: String, _ function: String, authToken: String?, checkToken: String?, kind: String) async throws {
    var request = URLRequest(url: URL(string: "https://\(region)-crisis-connect-1.cloudfunctions.net/\(function)")!)
    request.httpMethod = "POST"
    request.timeoutInterval = 40
    request.httpBody = Data("{\"data\":{}}".utf8)
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    if let authToken { request.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization") }
    if let checkToken { request.setValue(checkToken, forHTTPHeaderField: "X-Firebase-AppCheck") }
    let (body, response) = try await URLSession.shared.data(for: request)
    let parsed = try JSONSerialization.jsonObject(with: body) as? [String: Any]
    guard (response as? HTTPURLResponse)?.statusCode == 401,
          (parsed?["error"] as? [String: Any])?["status"] as? String == "UNAUTHENTICATED" else {
     throw NSError(domain: "LiveRegionAccessCheck", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(region) did not reject \(kind)"])
    }
    accessChecks.append(["region": region, "case": kind, "httpStatus": 401])
   }
   var measurements: [[String: Any]] = []
   for (region, function) in [("us-central1", "messagingRegionProbe"), ("europe-west4", "messagingRegionProbeEurope"), ("asia-south1", "messagingRegionProbeMumbai")] {
    var requestTimes: [Int] = []
    var serverTimes: [Int] = []
    let callable = Functions.functions(app: app, region: region).httpsCallable(function)
    callable.timeoutInterval = 40
    for _ in 0..<4 {
     let started = ProcessInfo.processInfo.systemUptime
     let result = try await callable.call()
     requestTimes.append(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))
     let data = try XCTUnwrap(result.data as? [String: Any])
     guard data["region"] as? String == region else {
      throw NSError(domain: "LiveRegionProbe", code: 1, userInfo: [NSLocalizedDescriptionKey: "Unexpected serving region"])
     }
     serverTimes.append(try XCTUnwrap(data["serverDurationMs"] as? NSNumber).intValue)
    }
    measurements.append(["region": region, "requestDurationMs": requestTimes, "serverDurationMs": serverTimes])
    try await verifyRejected(region, function, authToken: idToken, checkToken: nil, kind: "missingAppCheck")
    try await verifyRejected(region, function, authToken: idToken, checkToken: "invalid-app-check-token", kind: "invalidAppCheck")
    try await verifyRejected(region, function, authToken: nil, checkToken: appCheckToken, kind: "missingAuth")
    try await post(["regionMeasurements": measurements, "accessControlChecks": accessChecks])
   }
   try await post(["regionMeasurements": measurements, "accessControlChecks": accessChecks, "probeStatus": "passed"])
  } catch {
   try? await post(["probeStatus": "failed", "errorType": String(describing: type(of: error))])
   try? await auth.currentUser?.delete()
   await withCheckedContinuation { continuation in app.delete { _ in continuation.resume() } }
   throw error
  }
  try await auth.currentUser?.delete()
  await withCheckedContinuation { continuation in app.delete { _ in continuation.resume() } }
 }
}
