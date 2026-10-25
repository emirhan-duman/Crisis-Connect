package com.auralis.crisisconnect.messaging
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RegionalMessagingCallsTest {
 @Test fun highAvailabilityCannotSilentlyAcceptAnotherDatabase() = runBlocking {
  var calls=0
  val router=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE},now={0}),highAvailability=true) {_,_,_->calls++;mapOf("ok" to true)}
  try {router.call("relayMessage",emptyMap());fail()}catch(_:IllegalStateException){}
  assertEquals(1,calls)
 }
 @Test fun ambiguousPreKeyPublicationIsNotReplayedAcrossRegions() = runBlocking {
  var calls=0
  val policy=RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE},now={0})
  val router=RegionalMessagingCalls(policy) {_,_,_->calls++;throw error(FirebaseFunctionsException.Code.UNAVAILABLE)}
  try {router.call("publishSignalPreKeys",mapOf("preKeys" to emptyList<Any>()));fail()}catch(_:FirebaseFunctionsException){}
  assertEquals(1,calls)
  assertEquals(RegionalRoutePolicy.US,policy.preferred())
 }
 @Test fun missingIdentityDoesNotCauseExtraRegionalTraffic() = runBlocking {
  for(operation in listOf("lookupIdentityKey","fetchSignalPreKeyBundle")) {
   var calls=0
   val router=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE},now={0})) {_,_,_->calls++;throw error(FirebaseFunctionsException.Code.NOT_FOUND)}
   try {router.call(operation,emptyMap());fail()}catch(_:FirebaseFunctionsException){}
   assertEquals(1,calls)
  }
 }
 @Test fun identityBootstrapCanUseAlternateRegionWithoutBackgroundSampling() = runBlocking {
  val calls=mutableListOf<String>()
  val router=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE},now={0})) {region,_,_->calls.add(region);if(region==RegionalRoutePolicy.EUROPE)throw error(FirebaseFunctionsException.Code.UNAVAILABLE);mapOf("routing" to mapOf("databaseId" to MessagingDatabaseTarget.DATABASE_ID, "protocol" to 4))}
  router.call("checkSignalPreKeys",emptyMap())
  assertEquals(listOf(RegionalRoutePolicy.EUROPE,RegionalRoutePolicy.US),calls)
 }
 @Test fun geographyUsesFreshCoordinatesOrCountryAndTimeZoneHints() {
  assertEquals(RegionalRoutePolicy.EUROPE,MessagingRegionHint.nearest(41.0,29.0))
  assertEquals(RegionalRoutePolicy.US,MessagingRegionHint.nearest(40.7,-74.0))
  assertEquals(RegionalRoutePolicy.US,MessagingRegionHint.nearest(-33.9,151.2))
  assertNull(MessagingRegionHint.nearest(Double.NaN,0.0))
  assertEquals(RegionalRoutePolicy.US,MessagingRegionHint.countryRegion("ca"))
  assertEquals(RegionalRoutePolicy.EUROPE,MessagingRegionHint.countryRegion("tr"))
  assertNull(MessagingRegionHint.countryRegion("invalid"))
  assertEquals(RegionalRoutePolicy.US,MessagingRegionHint.timeZoneRegion("America/New_York"))
  assertEquals(RegionalRoutePolicy.EUROPE,MessagingRegionHint.timeZoneRegion("Europe/Istanbul"))
 }
 @Test fun changedGeographicHintIsUsedAndWarmNetworkLatencyCanRefineIt() {
  var geo=RegionalRoutePolicy.US;var now=0L
  val policy=RegionalRoutePolicy(initialRegion={geo},now={now})
  assertEquals(RegionalRoutePolicy.US,policy.preferred());geo=RegionalRoutePolicy.EUROPE
  assertEquals(RegionalRoutePolicy.EUROPE,policy.preferred())
  repeat(4){policy.recordSuccess(RegionalRoutePolicy.EUROPE,500.0);policy.recordSuccess(RegionalRoutePolicy.US,100.0)}
  assertEquals(RegionalRoutePolicy.US,policy.preferred())
  now=1_800_000;assertEquals(RegionalRoutePolicy.EUROPE,policy.preferred())
 }
 @Test fun normalSamplingSubstitutesRequestsAndNeverAddsTraffic() = runBlocking {
  val regions=mutableListOf<String>()
  val router=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE},now={0})) {region,_,_->regions.add(region);mapOf("ok" to true, "routing" to mapOf("databaseId" to MessagingDatabaseTarget.DATABASE_ID, "protocol" to 4))}
  repeat(40){router.call("relayMessage",mapOf("trafficPolicyVersion" to 1))}
  assertEquals(40,regions.size);assertEquals(2,regions.count {it==RegionalRoutePolicy.US})
 }
 @Test fun conservingAndEmergencyTrafficNeverSampleOtherRegion() = runBlocking {
  val regions=mutableListOf<String>()
  val router=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE},now={0})) {region,_,_->regions.add(region);mapOf("ok" to true, "routing" to mapOf("databaseId" to MessagingDatabaseTarget.DATABASE_ID, "protocol" to 4))}
  repeat(40){router.call("relayMessage",mapOf("trafficPolicyVersion" to 2));router.call("relayMessage",mapOf("priority" to "high"));router.call("compactInbox",mapOf("saving" to true))}
  assertEquals(120,regions.size);assertTrue(regions.all {it==RegionalRoutePolicy.EUROPE})
 }
 @Test fun usPrimaryCanFailOverToEuropeAndOperatorOverrideBlocksSamplingAndFallback() = runBlocking {
  val regions=mutableListOf<String>();val policy=RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.US},now={0})
  val router=RegionalMessagingCalls(policy) {region,_,_->regions.add(region);if(region==RegionalRoutePolicy.US)throw error(FirebaseFunctionsException.Code.UNAVAILABLE);mapOf("ok" to true, "routing" to mapOf("databaseId" to MessagingDatabaseTarget.DATABASE_ID, "protocol" to 4))}
  router.call("relayMessage",emptyMap());assertEquals(listOf(RegionalRoutePolicy.US,RegionalRoutePolicy.EUROPE),regions)
  policy.apply(mapOf("routing" to mapOf("preferredRegion" to RegionalRoutePolicy.US)))
  assertEquals(RegionalRoutePolicy.US,policy.preferred(true));assertNull(policy.alternate(RegionalRoutePolicy.US))
 }
 private fun error(code: FirebaseFunctionsException.Code) = FirebaseFunctionsException::class.java
  .getDeclaredConstructor(String::class.java,FirebaseFunctionsException.Code::class.java,Any::class.java)
  .newInstance("Synthetic SDK failure",code,null)
 @Test fun accountSwitchBetweenAttemptsStopsFailover() = runBlocking {
  var sameAccount=true;var calls=0
  val router=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE}) {0}) {_,_,_->calls++;sameAccount=false;throw error(FirebaseFunctionsException.Code.UNAVAILABLE)}
  try {router.call("relayMessage",emptyMap()){sameAccount};fail()}catch(_:IllegalStateException){}
  assertEquals(1,calls)
 }
 @Test fun ambiguousSuccessReusesPayloadAndCooldownPreventsExtraEuropeanTraffic() = runBlocking {
  var now=0L;val policy=RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE}) {now};val calls=mutableListOf<String>();val body=mapOf<String,Any>("messageId" to "stable", "ciphertext" to "same")
  val router=RegionalMessagingCalls(policy) {region,_,payload ->
   assertSame(body,payload);calls.add(region)
   if(region==RegionalRoutePolicy.EUROPE) throw error(FirebaseFunctionsException.Code.UNAVAILABLE)
   mapOf("ok" to true, "routing" to mapOf("databaseId" to MessagingDatabaseTarget.DATABASE_ID, "protocol" to 4))
  }
  router.call("relayMessage",body);router.call("relayMessage",body)
  assertEquals(listOf(RegionalRoutePolicy.EUROPE,RegionalRoutePolicy.US,RegionalRoutePolicy.US),calls)
  now=300_000;assertEquals(RegionalRoutePolicy.EUROPE,policy.preferred())
 }
 @Test fun authQuotaAndValidationFailuresNeverFailOver() = runBlocking {
  for(code in listOf(FirebaseFunctionsException.Code.UNAUTHENTICATED,FirebaseFunctionsException.Code.PERMISSION_DENIED,FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,FirebaseFunctionsException.Code.INVALID_ARGUMENT)) {
   var calls=0;val router=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE}) {0}) {_,_,_->calls++;throw error(code)}
   try {router.call("relayMessage",emptyMap());fail()}catch(_:FirebaseFunctionsException){}
   assertEquals(1,calls)
  }
 }
 @Test fun callerCancellationAndCooldownNeverBounceToFailedRegion() = runBlocking {
  assertFalse(RegionalMessagingCalls.retryable(CancellationException()))
  val policy=RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE}) {0};policy.failed(RegionalRoutePolicy.EUROPE);var calls=0
  val router=RegionalMessagingCalls(policy){_,_,_->calls++;throw error(FirebaseFunctionsException.Code.UNAVAILABLE)}
  try {router.call("compactInbox",emptyMap());fail()}catch(_:FirebaseFunctionsException){}
  assertEquals(1,calls)
 }
 @Test fun operatorRollbackHasFiniteLeaseAndIgnoresUnknownRegions() {
  var now=0L;val policy=RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE}) {now}
  policy.apply(mapOf("routing" to mapOf("preferredRegion" to "attacker.example")));assertEquals(RegionalRoutePolicy.EUROPE,policy.preferred())
  policy.apply(mapOf("routing" to mapOf("preferredRegion" to RegionalRoutePolicy.US)));assertEquals(RegionalRoutePolicy.US,policy.preferred())
  now=300_000;assertEquals(RegionalRoutePolicy.EUROPE,policy.preferred())
 }
}
