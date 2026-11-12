package com.auralis.crisisconnect.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import java.util.TimeZone
import kotlin.math.*

/** Coarse routing stays on the device. Reads existing fixes; never starts GPS or asks permission. */
object MessagingRegionHint {
 private var cached: String? = null
 private var expires = 0L
 @Synchronized fun preferredRegion(): String {
  val now = SystemClock.elapsedRealtime()
  if (now < expires) return cached!!
  cached = deviceRegion(); expires = now + 30_000
  return cached!!
 }
 @SuppressLint("MissingPermission")
 private fun deviceRegion(): String {
  val context = runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull()
  if (context != null) {
   val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    .any { ContextCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED }
   if (granted) {
    val manager = context.getSystemService(LocationManager::class.java)
    val location = runCatching { manager?.allProviders.orEmpty().mapNotNull { provider ->
     runCatching { manager?.getLastKnownLocation(provider) }.getOrNull()
    }.filter { it.hasAccuracy() && it.accuracy in 0f..100_000f &&
     (SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos) in 0..1_800_000_000_000L
    }.maxByOrNull { it.elapsedRealtimeNanos } }.getOrNull()
    if (location != null) return nearest(location.latitude,location.longitude) ?: timeZoneRegion(TimeZone.getDefault().id)
   }
   val country = runCatching { context.getSystemService(TelephonyManager::class.java)?.networkCountryIso }.getOrNull()
   countryRegion(country)?.let { return it }
  }
  return timeZoneRegion(TimeZone.getDefault().id)
 }
 fun nearest(latitude: Double, longitude: Double): String? {
  if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
  fun score(lat: Double, lon: Double): Double {
   val a=Math.toRadians(latitude);val b=Math.toRadians(lat)
   return sin(a)*sin(b)+cos(a)*cos(b)*cos(Math.toRadians(longitude-lon))
  }
  // Approximate regional sites, sufficient for a continental first choice; real RTT refines it.
  return if(score(41.2,-95.9)>score(52.4,5.5)) RegionalRoutePolicy.US else RegionalRoutePolicy.EUROPE
 }
 private val americas = setOf("US","CA","MX","BR","AR","CL","CO","PE","VE","EC","BO","PY","UY","GY","SR","GF","BZ","GT","HN","SV","NI","CR","PA","CU","DO","HT","JM","BS","BB","TT","PR","VI","AW","CW","SX","BM","GU","AS","MP")
 fun countryRegion(country: String?): String? {
  val code=country?.uppercase(java.util.Locale.ROOT) ?: return null
  if (code !in java.util.Locale.getISOCountries()) return null
  return if(code in americas || code in setOf("AU","NZ","FJ")) RegionalRoutePolicy.US else RegionalRoutePolicy.EUROPE
 }
 fun timeZoneRegion(zone: String): String = if(zone.startsWith("America/") || zone.startsWith("Pacific/") ||
  (zone.startsWith("Australia/") && zone!="Australia/Perth")) RegionalRoutePolicy.US else RegionalRoutePolicy.EUROPE
}
