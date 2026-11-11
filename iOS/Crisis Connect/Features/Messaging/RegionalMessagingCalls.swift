import Foundation
import FirebaseFunctions

final class RegionalRoutePolicy: @unchecked Sendable {
 static let europe = "europe-west4", us = "us-central1"
 private let lock = NSLock()
 private let now: () -> Double
 private let initialRegion: () -> String
 private var cooldownRegion:String?,cooldownUntil:Double=0
 private var operatorRegion:String?,operatorUntil:Double=0
 private var normalRequests=0
 private struct Timing {var successes=0;var warmMean:Double=0;var last:Double=0}
 private var timings:[String:Timing]=[:]
 init(initialRegion: @escaping () -> String = {MessagingRegionHint.preferredRegion()},
      now: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }) { self.now=now;self.initialRegion=initialRegion }
 func preferred(allowSampling:Bool=false) -> String { lock.withLock {
  if now()<operatorUntil {return operatorRegion!}
  if now()<cooldownUntil {return cooldownRegion!}
  let hint=initialRegion();let geo=[Self.europe,Self.us].contains(hint) ? hint : Self.europe
  var learned=geo
  if let eu=timings[Self.europe],let us=timings[Self.us],eu.successes>=4,us.successes>=4,
     now()-eu.last<1800,now()-us.last<1800 {
   if us.warmMean<eu.warmMean*0.8 {learned=Self.us} else if eu.warmMean<us.warmMean*0.8 {learned=Self.europe}
  }
  if allowSampling {normalRequests+=1;if normalRequests%20==0 {return Self.other(learned)}}
  return learned
 } }
 func alternate(_ region:String)->String? {lock.withLock {
  now()<operatorUntil || (now()<cooldownUntil && cooldownRegion==region) ? nil : Self.other(region)
 } }
 func failed(_ region:String) {lock.withLock {cooldownRegion=Self.other(region);cooldownUntil=now()+300}}
 func recordSuccess(_ region:String,durationMs:Double) {lock.withLock {
  guard durationMs.isFinite,durationMs>=0 else {return}
  var t=timings[region] ?? Timing()
  if now()-t.last>=1800 {t=Timing()}
  t.successes+=1
  if t.successes==2 {t.warmMean=durationMs} else if t.successes>2 {t.warmMean=t.warmMean*0.8+durationMs*0.2}
  t.last=now();timings[region]=t
 } }
 static func other(_ region:String)->String {region==europe ? us : europe}
 func apply(_ result: Any) {
  guard let data = result as? [String: Any], let routing = data["routing"] as? [String: Any],
        routing["preferredRegion"] as? String == Self.us else { return }
  lock.withLock {operatorRegion=Self.us;operatorUntil=now()+300}
 }
}

final class RegionalMessagingCalls {
 private static let deliveryOperations: Set<String> = ["relayMessage", "compactInbox", "acknowledgeMessage"]
 private static let operations = deliveryOperations.union(["publishIdentityKey", "lookupIdentityKey",
  "findContactsOnCrisisConnect", "registerPushToken", "unregisterPushToken", "publishSignalPreKeys",
  "checkSignalPreKeys", "fetchSignalPreKeyBundle", "resolveResilientGroup", "openResilientGroup", "listResilientHierarchy", "listAuthorityRoster", "requestAuthorityMlsPreparation"])
 private static let sharedPolicy = RegionalRoutePolicy()
 private let policy: RegionalRoutePolicy
 private let highAvailability: Bool
 private let invoke: (String, String, [String: Any]) async throws -> Any
 init(policy: RegionalRoutePolicy? = nil, highAvailability: Bool = MessagingDatabaseTarget.highAvailabilityEnabled,
      invoke: ((String, String, [String: Any]) async throws -> Any)? = nil) {
  self.policy = policy ?? Self.sharedPolicy
  self.highAvailability = highAvailability
  self.invoke = invoke ?? { region, operation, payload in
   let name = highAvailability
    ? (region == RegionalRoutePolicy.europe ? "messagingGatewayHaEurope" : "messagingGatewayHaUS")
    : (region == RegionalRoutePolicy.europe ? "messagingGatewayEurope" : "messagingGatewayUS")
   let callable = Functions.functions(region: region).httpsCallable(name)
   callable.timeoutInterval = operation == "publishSignalPreKeys" ? 45 : (operation == "findContactsOnCrisisConnect" ? 20 : 8)
   return try await callable.callAsync(["operation": operation, "payload": payload]).data
  }
 }
 func call(_ operation: String, _ payload: [String: Any], accountCurrent: () -> Bool = { true }) async throws -> Any {
  precondition(Self.operations.contains(operation))
  try Task.checkCancellation()
  guard accountCurrent() else { throw InternetMessagingClientError.notSignedIn }
  let sampling=Self.deliveryOperations.contains(operation) && (payload["saving"] as? Bool != true) && (payload["trafficPolicyVersion"] as? Int != 2) &&
   (payload["priority"] as? String != "high") && (payload["callRing"] as? Bool != true)
  let region = policy.preferred(allowSampling:sampling)
  func attempt(_ target:String) async throws -> Any {
   let started=ProcessInfo.processInfo.systemUptime
   let result=try await invoke(target,operation,payload)
   if highAvailability {
    guard let routing=(result as? [String:Any])?["routing"] as? [String:Any],
      routing["databaseId"] as? String == MessagingDatabaseTarget.databaseID,
      (routing["protocol"] as? NSNumber)?.intValue == 4 else { throw InternetMessagingClientError.malformedResponse }
   }
   policy.recordSuccess(target,durationMs:(ProcessInfo.processInfo.systemUptime-started)*1000)
   return result
  }
  let result: Any
  do { result = try await attempt(region) }
  catch {
   try Task.checkCancellation()
   guard accountCurrent() else { throw InternetMessagingClientError.notSignedIn }
   guard Self.retryable(error) else { throw error }
   // Never replay an ambiguous publication: doing so can restore already-consumed prekeys.
   if operation == "publishSignalPreKeys" { policy.failed(region); throw error }
   let failure = error as NSError
   if !Self.deliveryOperations.contains(operation), failure.domain == FunctionsErrorDomain,
      failure.code == FunctionsErrorCode.notFound.rawValue { throw error }
   guard let alternate=policy.alternate(region) else { throw error }
   policy.failed(region)
   result = try await attempt(alternate)
  }
  try Task.checkCancellation()
  guard accountCurrent() else { throw InternetMessagingClientError.notSignedIn }
  policy.apply(result)
  return result
 }
 static func retryable(_ error: Error) -> Bool {
  let e = error as NSError
  if e.domain == FunctionsErrorDomain {
   return [FunctionsErrorCode.unavailable.rawValue, FunctionsErrorCode.deadlineExceeded.rawValue,
           FunctionsErrorCode.notFound.rawValue].contains(e.code)
  }
  return e.domain == NSURLErrorDomain && [NSURLErrorTimedOut, NSURLErrorNetworkConnectionLost,
   NSURLErrorCannotConnectToHost, NSURLErrorCannotFindHost].contains(e.code)
 }
}
