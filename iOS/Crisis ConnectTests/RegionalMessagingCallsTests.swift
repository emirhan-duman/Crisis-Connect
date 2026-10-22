import XCTest
import FirebaseFunctions
@testable import Crisis_Connect
final class RegionalMessagingCallsTests: XCTestCase {
 func testHighAvailabilityDoesNotSilentlyAcceptAnotherDatabase() async {
  var calls=0
  let router=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0}),highAvailability:true,invoke:{_,_,_ in
   calls+=1;return ["ok":true]
  })
  do {_ = try await router.call("relayMessage",[:]);XCTFail()}catch{}
  XCTAssertEqual(calls,1)
 }
 func testAmbiguousPreKeyPublicationIsNotReplayed() async {
  var calls=0
  let policy=RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0})
  let router=RegionalMessagingCalls(policy:policy,invoke:{_,_,_ in
   calls+=1;throw NSError(domain:FunctionsErrorDomain,code:FunctionsErrorCode.unavailable.rawValue)
  })
  do {_ = try await router.call("publishSignalPreKeys",[:]);XCTFail()}catch{}
  XCTAssertEqual(calls,1)
  XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.us)
 }
 func testMissingIdentityDoesNotCauseRegionalRetry() async {
  for operation in ["lookupIdentityKey","fetchSignalPreKeyBundle"] {
   var calls=0
   let router=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0}),invoke:{_,_,_ in
    calls+=1;throw NSError(domain:FunctionsErrorDomain,code:FunctionsErrorCode.notFound.rawValue)
   })
   do {_ = try await router.call(operation,[:]);XCTFail()}catch{}
   XCTAssertEqual(calls,1)
  }
 }
 func testKeyInventoryCanFailOverWithoutExtraSampling() async throws {
  var calls:[String]=[]
  let router=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0}),invoke:{region,_,_ in
   calls.append(region)
   if region==RegionalRoutePolicy.europe {throw NSError(domain:FunctionsErrorDomain,code:FunctionsErrorCode.unavailable.rawValue)}
   return ["published":true,"routing":["databaseId":MessagingDatabaseTarget.databaseID,"protocol":4]]
  })
  _ = try await router.call("checkSignalPreKeys",[:])
  XCTAssertEqual(calls,[RegionalRoutePolicy.europe,RegionalRoutePolicy.us])
 }
 func testCoordinatesAndTimeZoneHintsChooseARegion() {
  XCTAssertEqual(MessagingRegionHint.nearest(latitude:41,longitude:29),RegionalRoutePolicy.europe)
  XCTAssertEqual(MessagingRegionHint.nearest(latitude:40.7,longitude:-74),RegionalRoutePolicy.us)
  XCTAssertEqual(MessagingRegionHint.nearest(latitude:-33.9,longitude:151.2),RegionalRoutePolicy.us)
  XCTAssertNil(MessagingRegionHint.nearest(latitude:.nan,longitude:0))
  XCTAssertEqual(MessagingRegionHint.timeZoneRegion("America/New_York"),RegionalRoutePolicy.us)
  XCTAssertEqual(MessagingRegionHint.timeZoneRegion("Europe/Istanbul"),RegionalRoutePolicy.europe)
 }
 func testChangedHintAndWarmObservedNetworkLatencyRefineChoice() {
  var geo=RegionalRoutePolicy.us;var now:Double=0
  let policy=RegionalRoutePolicy(initialRegion:{geo},now:{now})
  XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.us);geo=RegionalRoutePolicy.europe
  XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.europe)
  for _ in 0..<4 {policy.recordSuccess(RegionalRoutePolicy.europe,durationMs:500);policy.recordSuccess(RegionalRoutePolicy.us,durationMs:100)}
  XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.us)
  now=1800;XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.europe)
 }
 func testNormalSamplingSubstitutesRequestsWithoutExtraTraffic() async throws {
  var regions:[String]=[]
  let router=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0}),invoke:{region,_,_ in regions.append(region);return ["ok":true,"routing":["databaseId":MessagingDatabaseTarget.databaseID,"protocol":4]]})
  for _ in 0..<40 {_ = try await router.call("relayMessage",["trafficPolicyVersion":1])}
  XCTAssertEqual(regions.count,40);XCTAssertEqual(regions.filter {$0==RegionalRoutePolicy.us}.count,2)
 }
 func testConservingAndEmergencyRequestsNeverSample() async throws {
  var regions:[String]=[]
  let router=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0}),invoke:{region,_,_ in regions.append(region);return ["ok":true,"routing":["databaseId":MessagingDatabaseTarget.databaseID,"protocol":4]]})
  for _ in 0..<40 {
   _ = try await router.call("relayMessage",["trafficPolicyVersion":2]);_ = try await router.call("relayMessage",["priority":"high"])
   _ = try await router.call("compactInbox",["saving":true])
  }
  XCTAssertEqual(regions.count,120);XCTAssertTrue(regions.allSatisfy {$0==RegionalRoutePolicy.europe})
 }
 func testUsPrimaryCanFailOverToEuropeAndOperatorOverrideBlocksOtherRegion() async throws {
  var regions:[String]=[];let policy=RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.us},now:{0})
  let router=RegionalMessagingCalls(policy:policy,invoke:{region,_,_ in
   regions.append(region);if region==RegionalRoutePolicy.us {throw NSError(domain:FunctionsErrorDomain,code:FunctionsErrorCode.unavailable.rawValue)}
   return ["ok":true,"routing":["databaseId":MessagingDatabaseTarget.databaseID,"protocol":4]]
  })
  _ = try await router.call("relayMessage",[:]);XCTAssertEqual(regions,[RegionalRoutePolicy.us,RegionalRoutePolicy.europe])
  policy.apply(["routing":["preferredRegion":RegionalRoutePolicy.us]])
  XCTAssertEqual(policy.preferred(allowSampling:true),RegionalRoutePolicy.us);XCTAssertNil(policy.alternate(RegionalRoutePolicy.us))
 }
 func testAccountSwitchBetweenAttemptsStopsFailover() async {
  var sameAccount=true;var calls=0
  let router=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0}),invoke:{_,_,_ in calls+=1;sameAccount=false;throw NSError(domain:FunctionsErrorDomain,code:FunctionsErrorCode.unavailable.rawValue)})
  do {_ = try await router.call("relayMessage",[:],accountCurrent:{sameAccount});XCTFail()}catch{}
  XCTAssertEqual(calls,1)
 }
 func testAmbiguousSuccessKeepsPayloadAndCooldownSuppressesEuropeanRetries() async throws {
  var now: Double=0;let policy=RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{now});var calls:[String]=[]
  let router=RegionalMessagingCalls(policy:policy,invoke:{region,_,payload in
   XCTAssertEqual(payload["messageId"] as? String,"stable");XCTAssertEqual(payload["ciphertext"] as? String,"same")
   calls.append(region)
   if region==RegionalRoutePolicy.europe {throw NSError(domain:FunctionsErrorDomain,code:FunctionsErrorCode.unavailable.rawValue)}
   return ["ok":true,"routing":["databaseId":MessagingDatabaseTarget.databaseID,"protocol":4]]
  })
  _ = try await router.call("relayMessage",["messageId":"stable","ciphertext":"same"])
  _ = try await router.call("relayMessage",["messageId":"stable","ciphertext":"same"])
  XCTAssertEqual(calls,[RegionalRoutePolicy.europe,RegionalRoutePolicy.us,RegionalRoutePolicy.us])
  now=300;XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.europe)
 }
 func testAuthQuotaAndValidationNeverFailOver() async {
  for code in [FunctionsErrorCode.unauthenticated,.permissionDenied,.resourceExhausted,.invalidArgument] {
   var calls=0;let router=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0}),invoke:{_,_,_ in
    calls+=1;throw NSError(domain:FunctionsErrorDomain,code:code.rawValue)
   })
   do {_ = try await router.call("relayMessage",[:]);XCTFail()}catch{}
   XCTAssertEqual(calls,1)
  }
 }
 func testCooldownNeverBouncesToFailedRegionAndCancellationNeverRetries() async {
  XCTAssertFalse(RegionalMessagingCalls.retryable(CancellationError()))
  let policy=RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{0});policy.failed(RegionalRoutePolicy.europe);var calls=0
  let router=RegionalMessagingCalls(policy:policy,invoke:{_,_,_ in calls+=1;throw NSError(domain:FunctionsErrorDomain,code:FunctionsErrorCode.unavailable.rawValue)})
  do {_ = try await router.call("compactInbox",[:]);XCTFail()}catch{}
  XCTAssertEqual(calls,1)
 }
 func testOperatorRollbackExpiresAndIgnoresUnknownRegions() {
  var now:Double=0;let policy=RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe},now:{now})
  policy.apply(["routing":["preferredRegion":"attacker.example"]]);XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.europe)
  policy.apply(["routing":["preferredRegion":RegionalRoutePolicy.us]]);XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.us)
  now=300;XCTAssertEqual(policy.preferred(),RegionalRoutePolicy.europe)
 }
}
