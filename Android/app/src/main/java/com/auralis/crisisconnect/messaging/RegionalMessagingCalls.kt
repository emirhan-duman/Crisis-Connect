package com.auralis.crisisconnect.messaging

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** Passive circuit: real traffic alone tests recovery, never background probes or parallel sends. */
class RegionalRoutePolicy(
 private val initialRegion: () -> String = { MessagingRegionHint.preferredRegion() },
 private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
 private var cooldownRegion: String? = null
 private var cooldownUntil = 0L
 private var operatorRegion: String? = null
 private var operatorUntil = 0L
 private var normalRequests = 0
 private data class Timing(var successes: Int=0,var warmMean: Double=0.0,var last: Long=0)
 private val timings = mutableMapOf<String,Timing>()
 @Synchronized fun preferred(allowSampling: Boolean = false): String {
  if (now()<operatorUntil) return operatorRegion!!
  if (now()<cooldownUntil) return cooldownRegion!!
  val geo=initialRegion().takeIf { it==US || it==EUROPE } ?: EUROPE
  val eu=timings[EUROPE];val us=timings[US]
  val learned=if(eu!=null && us!=null && eu.successes>=4 && us.successes>=4 &&
   now()-eu.last<1_800_000 && now()-us.last<1_800_000) {
   when {us.warmMean<eu.warmMean*0.8->US;eu.warmMean<us.warmMean*0.8->EUROPE;else->geo}
  } else geo
  // Substitute one ordinary request, never add a probe or duplicate a successful message.
  return if(allowSampling && ++normalRequests%20==0) other(learned) else learned
 }
 @Synchronized fun alternate(region: String): String? =
  if(now()<operatorUntil || (now()<cooldownUntil && cooldownRegion==region)) null else other(region)
 @Synchronized fun failed(region: String) { cooldownRegion=other(region);cooldownUntil=now()+300_000 }
 @Synchronized fun recordSuccess(region: String, durationMs: Double) {
  if(!durationMs.isFinite() || durationMs<0) return
  val t=timings.getOrPut(region){Timing()}
  if(now()-t.last>=1_800_000) {t.successes=0;t.warmMean=0.0}
  t.successes++
  if(t.successes==2) t.warmMean=durationMs else if(t.successes>2) t.warmMean=t.warmMean*0.8+durationMs*0.2
  t.last=now()
 }
 @Synchronized fun apply(data: Any?) {
  val routing = (data as? Map<*, *>)?.get("routing") as? Map<*, *> ?: return
  if (routing["preferredRegion"] == US) {operatorRegion=US;operatorUntil=now()+300_000}
 }
 companion object { const val EUROPE = "europe-west4"; const val US = "us-central1"
  fun other(region: String)=if(region==EUROPE) US else EUROPE
 }
}

class RegionalMessagingCalls(
 private val policy: RegionalRoutePolicy = sharedPolicy,
 private val highAvailability: Boolean = MessagingDatabaseTarget.HIGH_AVAILABILITY_ENABLED,
 private val invoke: suspend (String, String, Map<String, Any>) -> Any? = { region, operation, payload ->
  val name = if (highAvailability) {
   if (region == RegionalRoutePolicy.EUROPE) "messagingGatewayHaEurope" else "messagingGatewayHaUS"
  } else if (region == RegionalRoutePolicy.EUROPE) "messagingGatewayEurope" else "messagingGatewayUS"
  val seconds = when(operation) { "publishSignalPreKeys" -> 45L; "findContactsOnCrisisConnect" -> 20L; else -> 8L }
  withTimeout(seconds * 1_000 + 1_000) {
   FirebaseFunctions.getInstance(region).getHttpsCallable(name).apply { setTimeout(seconds, TimeUnit.SECONDS) }
    .call(mapOf("operation" to operation, "payload" to payload)).await().data
  }
 }
) {
 suspend fun call(operation: String, payload: Map<String, Any>, accountCurrent: () -> Boolean = { true }): Any? {
  require(operation in operations)
  check(accountCurrent()) { "Messaging account changed." }
  val sampling=operation in deliveryOperations && payload["saving"]!=true && payload["trafficPolicyVersion"]!=2 && payload["priority"]!="high" && payload["callRing"]!=true
  val region = policy.preferred(sampling)
  suspend fun attempt(target:String):Any? {
   val started=android.os.SystemClock.elapsedRealtime()
   val result=invoke(target,operation,payload)
   if (highAvailability) {
    val routing=(result as? Map<*, *>)?.get("routing") as? Map<*, *>
    check(routing?.get("databaseId") == MessagingDatabaseTarget.DATABASE_ID &&
     (routing?.get("protocol") as? Number)?.toInt() == 4) { "Unexpected messaging response." }
   }
   policy.recordSuccess(target,(android.os.SystemClock.elapsedRealtime()-started).toDouble())
   return result
  }
  val result = try { attempt(region) } catch (error: Exception) {
   currentCoroutineContext().ensureActive()
   check(accountCurrent()) { "Messaging account changed." }
   if (!retryable(error)) throw error
   // A lost publication response is ambiguous: repeating an upload can reinsert
   // a one-time prekey that another device has already consumed. The manager
   // creates fresh IDs on its next publication instead of replaying this batch.
   if (operation == "publishSignalPreKeys") { policy.failed(region); throw error }
   // A missing identity/bundle is a domain result, not evidence of a broken region.
   if (operation !in deliveryOperations &&
       (error as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.NOT_FOUND) throw error
   val alternate=policy.alternate(region) ?: throw error
   policy.failed(region)
   // The exact same envelope, message ID, revision and ACK snapshot cross the boundary.
   attempt(alternate)
  }
  check(accountCurrent()) { "Messaging account changed." }
  policy.apply(result)
  return result
 }
 companion object {
  private val deliveryOperations = setOf("relayMessage", "compactInbox", "acknowledgeMessage")
  private val operations = deliveryOperations + setOf("publishIdentityKey", "lookupIdentityKey",
   "findContactsOnCrisisConnect", "registerPushToken", "unregisterPushToken", "publishSignalPreKeys",
   "checkSignalPreKeys", "fetchSignalPreKeyBundle", "resolveResilientGroup", "openResilientGroup", "listResilientHierarchy", "listAuthorityRoster", "requestAuthorityMlsPreparation")
  private val sharedPolicy = RegionalRoutePolicy()
  fun retryable(error: Exception): Boolean = error is TimeoutCancellationException ||
   (error !is CancellationException && (error as? FirebaseFunctionsException)?.code in setOf(
    FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
    FirebaseFunctionsException.Code.NOT_FOUND))
 }
}
