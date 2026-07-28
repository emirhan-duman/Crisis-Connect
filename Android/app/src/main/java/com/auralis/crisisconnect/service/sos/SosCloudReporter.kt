package com.auralis.crisisconnect.service.sos

import android.content.Context
import android.content.SharedPreferences
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.GattSOSServerService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Victim-side internet uplink for the SOS broadcast.
 *
 * The BLE broadcast is the primary, offline-first channel and must never wait on this class. When
 * usable connectivity exists (now or later — store-and-forward), the active SOS is additionally
 * self-reported to the agency dashboard through the `reportSosSignal` callable, then refreshed on
 * a battery-aware heartbeat, and finally marked resolved when the victim stops the broadcast.
 * A resolve that cannot be delivered (offline stop) is persisted and retried by
 * [SosReportWorker] once a network appears.
 */
object SosCloudReporter {

    sealed interface State {
        data object Idle : State

        /** SOS is active but there is no usable network yet; the report is queued locally. */
        data object WaitingForNetwork : State

        data object Sending : State

        data class Reported(
            /** Human-readable place of the reported fix (e.g. district/city), when resolvable. */
            val placeName: String?,
            val lastReportedAtMillis: Long
        ) : State

        /** Last attempt over a live network failed; will retry automatically. */
        data object Failed : State
    }

    private const val TAG = "SosCloudReporter"
    private const val FUNCTIONS_REGION = "us-central1"
    private const val CALLABLE_NAME = "reportSosSignal"
    private const val PREFS_NAME = "sos_cloud_reporter"
    private const val KEY_PENDING_RESOLVE_SIGNAL_ID = "pending_resolve_signal_id"
    private const val KEY_HAS_REPORTED_ACTIVE = "has_reported_active"

    private const val CALL_TIMEOUT_MS = 20_000L
    private const val HEARTBEAT_INTERVAL_MS = 45_000L
    private const val HEARTBEAT_INTERVAL_LOW_BATTERY_MS = 90_000L
    private const val LOW_BATTERY_THRESHOLD_PERCENT = 20
    private const val OFFLINE_POLL_INTERVAL_MS = 10_000L
    private const val FAILURE_RETRY_DELAY_MS = 20_000L
    private const val SIGN_IN_TIMEOUT_MS = 10_000L

    private val reporterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Starts the uplink for a freshly started SOS broadcast. Runs entirely on the reporter's own
     * scope so a slow network call can never delay or block BLE startup or service teardown.
     */
    fun onSosStarted(
        context: Context,
        locationProvider: suspend () -> BlePeerIdentityUtils.PeerLocationSnapshot?,
        batteryProvider: () -> Int?
    ) {
        val appContext = context.applicationContext
        heartbeatJob?.cancel()
        cachedPlace = null
        // A new broadcast supersedes any queued resolve for the same device signal.
        prefs(appContext).edit()
            .remove(KEY_PENDING_RESOLVE_SIGNAL_ID)
            .putBoolean(KEY_HAS_REPORTED_ACTIVE, false)
            .apply()
        _state.value =
            if (appContext.hasUsableInternet()) State.Sending else State.WaitingForNetwork
        heartbeatJob = reporterScope.launch {
            while (isActive && GattSOSServerService.isDeclared.value) {
                if (!appContext.hasUsableInternet()) {
                    _state.value = State.WaitingForNetwork
                    delay(OFFLINE_POLL_INTERVAL_MS)
                    continue
                }
                _state.value = State.Sending
                val battery = runCatching(batteryProvider).getOrNull()
                val location = runCatching { locationProvider() }.getOrNull()
                val reported = sendReport(
                    context = appContext,
                    status = STATUS_ACTIVE,
                    location = location,
                    batteryPercent = battery
                )
                if (reported) {
                    prefs(appContext).edit().putBoolean(KEY_HAS_REPORTED_ACTIVE, true).apply()
                    _state.value = State.Reported(
                        placeName = resolvePlaceName(appContext, location),
                        lastReportedAtMillis = System.currentTimeMillis()
                    )
                    val interval = if (battery != null && battery < LOW_BATTERY_THRESHOLD_PERCENT) {
                        HEARTBEAT_INTERVAL_LOW_BATTERY_MS
                    } else {
                        HEARTBEAT_INTERVAL_MS
                    }
                    delay(interval)
                } else {
                    _state.value = State.Failed
                    delay(FAILURE_RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * Marks the broadcast resolved on the dashboard. Best-effort immediately; when offline (or on
     * failure) the resolve is persisted and handed to [SosReportWorker] for delivery once a
     * network appears. Only signals that were actually reported as active get resolved, so a
     * fully-offline SOS session never writes a phantom "resolved" marker.
     */
    fun onSosStopped(context: Context) {
        val appContext = context.applicationContext
        heartbeatJob?.cancel()
        heartbeatJob = null
        _state.value = State.Idle
        if (!prefs(appContext).getBoolean(KEY_HAS_REPORTED_ACTIVE, false)) {
            return
        }
        val signalId = LocalKeyStorage.getOrCreateRescueDeviceId(appContext)
        prefs(appContext).edit()
            .putString(KEY_PENDING_RESOLVE_SIGNAL_ID, signalId)
            .putBoolean(KEY_HAS_REPORTED_ACTIVE, false)
            .apply()
        reporterScope.launch {
            val resolved = appContext.hasUsableInternet() && sendReport(
                context = appContext,
                status = STATUS_RESOLVED,
                location = null,
                batteryPercent = null
            )
            if (resolved) {
                clearPendingResolve(appContext)
            } else {
                SosReportWorker.enqueue(appContext)
            }
        }
    }

    /** Attempts delivery of a persisted resolve; used by [SosReportWorker]. */
    internal suspend fun deliverPendingResolve(context: Context): Boolean {
        val appContext = context.applicationContext
        val pending = prefs(appContext).getString(KEY_PENDING_RESOLVE_SIGNAL_ID, null)
            ?: return true
        // The victim restarted SOS meanwhile; the active heartbeat owns the signal again.
        if (GattSOSServerService.isDeclared.value) {
            return true
        }
        val resolved = sendReport(
            context = appContext,
            status = STATUS_RESOLVED,
            location = null,
            batteryPercent = null,
            signalIdOverride = pending
        )
        if (resolved) {
            clearPendingResolve(appContext)
        }
        return resolved
    }

    internal fun clearPendingResolve(context: Context) {
        prefs(context.applicationContext).edit().remove(KEY_PENDING_RESOLVE_SIGNAL_ID).apply()
    }

    /** Returns true when the report was accepted by the backend. */
    private suspend fun sendReport(
        context: Context,
        status: String,
        location: BlePeerIdentityUtils.PeerLocationSnapshot?,
        batteryPercent: Int?,
        signalIdOverride: String? = null
    ): Boolean {
        if (status == STATUS_ACTIVE && location == null) {
            Log.w(TAG, "Skipping SOS cloud report: no usable location fix yet")
            return false
        }
        val uid = ensureSignedInUid() ?: run {
            Log.w(TAG, "Skipping SOS cloud report: no Firebase identity available")
            return false
        }
        val signalId = signalIdOverride ?: LocalKeyStorage.getOrCreateRescueDeviceId(context)
        val payload = buildMap<String, Any> {
            put("signalId", signalId)
            put("status", status)
            location?.let { fix ->
                put("lat", fix.latitude)
                put("lon", fix.longitude)
                put("capturedAtMillis", fix.capturedAtMillis)
                put("provider", fix.source)
                fix.accuracyMeters?.let { put("accuracyMeters", it.toDouble()) }
            }
            batteryPercent?.let { put("batteryPercent", it) }
            resolveCountryIso(context, location)?.let { put("countryIso", it) }
        }
        return withTimeoutOrNull(CALL_TIMEOUT_MS) {
            runCatching {
                FirebaseFunctions.getInstance(FUNCTIONS_REGION)
                    .getHttpsCallable(CALLABLE_NAME)
                    .call(payload)
                    .await()
                Analytics.sosCloudReported(status)
                Log.d(TAG, "SOS cloud report ok status=$status uid=${uid.takeLast(6)}")
                true
            }.onFailure { throwable ->
                Log.w(TAG, "SOS cloud report failed status=$status", throwable)
            }.getOrDefault(false)
        } ?: false
    }

    /** Restores the persisted Firebase session or signs in anonymously, time-bounded. */
    private suspend fun ensureSignedInUid(): String? {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { return it }
        return withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) {
            runCatching { auth.signInAnonymously().await().user?.uid }
                .onFailure { Log.w(TAG, "Anonymous sign-in for SOS report failed", it) }
                .getOrNull()
        }
    }

    /**
     * Best-effort ISO country for agency routing: telephony network country, then SIM country,
     * then reverse-geocoding the fix. Any failure just omits the hint (routes to International).
     */
    private suspend fun resolveCountryIso(
        context: Context,
        location: BlePeerIdentityUtils.PeerLocationSnapshot?
    ): String? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val fromTelephony = runCatching {
            telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
                ?: telephony?.simCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!fromTelephony.isNullOrBlank()) {
            return fromTelephony.uppercase(Locale.US)
        }
        if (location == null || !Geocoder.isPresent()) {
            return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.US)
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
                    ?.countryCode
                    ?.takeIf { it.isNotBlank() }
                    ?.uppercase(Locale.US)
            }.getOrNull()
        }
    }

    /**
     * Human-readable place of the fix (district/city) for the status UI. Cached until the victim
     * moves ~1 km so heartbeats don't re-geocode the same spot.
     */
    private var cachedPlace: Triple<Double, Double, String>? = null

    private suspend fun resolvePlaceName(
        context: Context,
        location: BlePeerIdentityUtils.PeerLocationSnapshot?
    ): String? {
        if (location == null || !Geocoder.isPresent()) {
            return cachedPlace?.third
        }
        cachedPlace?.let { (lat, lon, place) ->
            if (isWithinPlaceCacheRadius(lat, lon, location.latitude, location.longitude)) {
                return place
            }
        }
        val place = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val address = Geocoder(context)
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
                    ?: return@runCatching null
                listOfNotNull(
                    address.subLocality?.takeIf { it.isNotBlank() }
                        ?: address.locality?.takeIf { it.isNotBlank() }
                        ?: address.subAdminArea?.takeIf { it.isNotBlank() },
                    address.adminArea?.takeIf { it.isNotBlank() }
                )
                    .distinct()
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        if (place != null) {
            cachedPlace = Triple(location.latitude, location.longitude, place)
        }
        return place ?: cachedPlace?.third
    }

    private fun isWithinPlaceCacheRadius(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Boolean {
        val results = FloatArray(1)
        return runCatching {
            android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            results[0] <= PLACE_CACHE_RADIUS_METERS
        }.getOrDefault(false)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Context.hasUsableInternet(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private const val STATUS_ACTIVE = "active"
    private const val STATUS_RESOLVED = "resolved"
    private const val PLACE_CACHE_RADIUS_METERS = 1_000f
}
