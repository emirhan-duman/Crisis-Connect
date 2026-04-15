package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.Surface
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.coroutines.resume

internal data class PendingImage(
    val uri: Uri,
    val mimeType: String?,
    val width: Int? = null,
    val height: Int? = null,
    val filePath: String? = null,
    val shouldDeleteOnClear: Boolean = false
)

internal data class LocationMapRenderKey(
    val ownLatE5: Int?,
    val ownLonE5: Int?,
    val sharedLatE5: Int,
    val sharedLonE5: Int,
    val headingBucket: Int?
)

internal data class DeviceOrientationSnapshot(
    val headingDegrees: Float?,
    val tiltFromFlatDegrees: Float?,
    val headingReliable: Boolean
)

internal const val LOCATION_PROVIDER_HYBRID = "cc.hybrid.location"
internal const val MAP_STYLE_LOAD_TIMEOUT_MS = 4_500L
internal const val MAP_HEADING_BUCKET_DEGREES = 4f
internal const val SENSOR_HEADING_MIN_EMIT_DELTA_DEGREES = 1.1f
internal const val SENSOR_HEADING_MIN_EMIT_INTERVAL_MS = 85L
internal const val LOCATION_MAP_MIN_ZOOM = 2.0
internal const val LOCATION_MAP_MAX_ZOOM = 20.8
internal const val LOCATION_MAP_MANUAL_ZOOM_STEP = 1.0
internal const val LOCATION_STALE_AGE_THRESHOLD_MS = 8 * 60 * 1000L
internal const val CHAT_SEND_LOCATION_MAX_AGE_MS = 2 * 60 * 1000L
internal const val CHAT_SEND_LOCATION_PREFERRED_ACCURACY_METERS = 60f
internal const val CHAT_SEND_LOCATION_ACCEPTABLE_ACCURACY_METERS = 110f
internal const val CHAT_SEND_LOCATION_NETWORK_TIMEOUT_MS = 2_500L
internal const val CHAT_SEND_LOCATION_GPS_TIMEOUT_MS = 6_500L
internal const val CHAT_SEND_LOCATION_HYBRID_MAX_BASE_AGE_MS = 15 * 60 * 1000L
internal const val CHAT_SEND_LOCATION_HYBRID_MAX_BASE_ACCURACY_METERS = 60f
internal const val CHAT_SEND_LOCATION_IMU_MAX_HEADING_STD_DEV_DEGREES = 32f
internal const val CHAT_SEND_LOCATION_IMU_MAX_DISTANCE_METERS = 24f
internal const val CHAT_LIVE_LOCATION_MIN_TIME_MS = 4_000L
internal const val CHAT_LIVE_LOCATION_MIN_DISTANCE_METERS = 2.5f
internal const val CHAT_LIVE_LOCATION_PASSIVE_MIN_TIME_MS = 12_000L
internal const val CHAT_LIVE_LOCATION_PASSIVE_MIN_DISTANCE_METERS = 10f
internal const val CHAT_LIVE_LOCATION_ACTIVE_REFRESH_MS = 12_000L
internal const val CHAT_LIVE_LOCATION_PASSIVE_REFRESH_MS = 40_000L
internal const val CHAT_LIVE_LOCATION_ACTIVE_POLL_MS = 6_000L
internal const val CHAT_LIVE_LOCATION_PASSIVE_POLL_MS = 18_000L
internal const val CHAT_LIVE_LOCATION_ACTIVE_STALE_AGE_MS = 22_000L
internal const val CHAT_LIVE_LOCATION_PASSIVE_STALE_AGE_MS = 90_000L
internal const val LOCATION_VERTICAL_HOLD_WARNING_TILT_DEGREES = 55f
internal const val LOCATION_POOR_ACCURACY_METERS = 75f
private const val NAVIGATION_HEADING_BLEND_START_TILT_DEGREES = 28f
private const val NAVIGATION_HEADING_BLEND_END_TILT_DEGREES = 62f
private const val NAVIGATION_HEADING_MIN_VECTOR_MAGNITUDE = 0.18f
internal val LOCATION_AVATAR_COLORS = intArrayOf(
    0xFF2E4F6F.toInt(),
    0xFF3A6173.toInt(),
    0xFF4A5D89.toInt(),
    0xFF486043.toInt(),
    0xFF6C5437.toInt(),
    0xFF714747.toInt(),
    0xFF5A4D74.toInt(),
    0xFF2F6A62.toInt(),
    0xFF4E6674.toInt(),
    0xFF5E6B44.toInt()
)

@SuppressLint("WakelockTimeout")
@Composable
internal fun rememberCallProximityScreenOff(enabled: Boolean): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isNear by remember(enabled, context) { mutableStateOf(false) }

    DisposableEffect(enabled, context, lifecycleOwner) {
        isNear = false
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = if (
            powerManager != null &&
            powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        ) {
            runCatching {
                powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "${context.packageName}:CallProximity"
                ).apply {
                    setReferenceCounted(false)
                }
            }.getOrNull()
        } else {
            null
        }

        fun acquireIfNeeded() {
            if (wakeLock != null && !wakeLock.isHeld) {
                runCatching { wakeLock.acquire() }
            }
        }

        fun releaseIfHeld() {
            if (wakeLock != null && wakeLock.isHeld) {
                runCatching { wakeLock.release() }
            }
        }

        val listener = if (sensorManager != null && proximitySensor != null) {
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val distance = event.values.firstOrNull() ?: proximitySensor.maximumRange
                    isNear = distance < proximitySensor.maximumRange
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
        } else {
            null
        }

        fun registerSensorListener() {
            val activeListener = listener ?: return
            val sensor = proximitySensor ?: return
            sensorManager?.registerListener(
                activeListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        fun unregisterSensorListener() {
            listener?.let { sensorManager?.unregisterListener(it) }
            isNear = false
        }

        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    acquireIfNeeded()
                    registerSensorListener()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    releaseIfHeld()
                    unregisterSensorListener()
                }
                else -> Unit
            }
        }

        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            acquireIfNeeded()
            registerSensorListener()
        }

        onDispose {
            lifecycle.removeObserver(observer)
            releaseIfHeld()
            unregisterSensorListener()
        }
    }

    return enabled && isNear
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberOwnLocationSnapshot(
    enabled: Boolean,
    liveTracking: Boolean = false
): Location? {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<Location?>(null) }

    DisposableEffect(enabled, context, liveTracking) {
        if (!enabled || !hasLocationPermission(context)) {
            snapshot = null
            return@DisposableEffect onDispose { }
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@DisposableEffect onDispose { }

        val providers = buildList {
            val networkEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
            val gpsEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            if (networkEnabled) add(LocationManager.NETWORK_PROVIDER)
            if (gpsEnabled) add(LocationManager.GPS_PROVIDER)
        }

        if (providers.isEmpty()) {
            return@DisposableEffect onDispose { }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                snapshot = pickBetterLocationForPreview(snapshot, location)
            }

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: android.os.Bundle?
            ) = Unit
        }

        providers.forEach { provider ->
            if (!hasLocationPermission(context)) {
                return@forEach
            }
            try {
                val minTimeMs = if (liveTracking) {
                    CHAT_LIVE_LOCATION_MIN_TIME_MS
                } else {
                    CHAT_LIVE_LOCATION_PASSIVE_MIN_TIME_MS
                }
                val minDistanceMeters = if (liveTracking) {
                    CHAT_LIVE_LOCATION_MIN_DISTANCE_METERS
                } else {
                    CHAT_LIVE_LOCATION_PASSIVE_MIN_DISTANCE_METERS
                }
                locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceMeters,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (_: SecurityException) {
            }
        }

        onDispose {
            runCatching {
                locationManager.removeUpdates(listener)
            }
        }
    }

    LaunchedEffect(enabled, context, liveTracking) {
        if (!enabled) {
            snapshot = null
            return@LaunchedEffect
        }
        snapshot = pickBetterLocationForPreview(
            snapshot,
            getBestLastKnownLocation(context)
        )
        fetchCurrentLocation(context) { location ->
            snapshot = pickBetterLocationForPreview(snapshot, location)
        }
        val pollIntervalMs = if (liveTracking) {
            CHAT_LIVE_LOCATION_ACTIVE_POLL_MS
        } else {
            CHAT_LIVE_LOCATION_PASSIVE_POLL_MS
        }
        val activeRefreshIntervalMs = if (liveTracking) {
            CHAT_LIVE_LOCATION_ACTIVE_REFRESH_MS
        } else {
            CHAT_LIVE_LOCATION_PASSIVE_REFRESH_MS
        }
        val staleThresholdMs = if (liveTracking) {
            CHAT_LIVE_LOCATION_ACTIVE_STALE_AGE_MS
        } else {
            CHAT_LIVE_LOCATION_PASSIVE_STALE_AGE_MS
        }
        var activeRefreshElapsed = 0L
        while (true) {
            delay(pollIntervalMs)
            val currentSnapshot = snapshot
            val snapshotAgeMs = currentSnapshot?.let {
                locationAgeForComparisonMillis(it, System.currentTimeMillis())
            } ?: Long.MAX_VALUE
            if (snapshotAgeMs >= staleThresholdMs) {
                snapshot = pickBetterLocationForPreview(
                    snapshot,
                    getBestLastKnownLocation(context)
                )
            }
            activeRefreshElapsed += pollIntervalMs
            if (activeRefreshElapsed >= activeRefreshIntervalMs) {
                activeRefreshElapsed = 0L
                fetchCurrentLocation(context) { location ->
                    snapshot = pickBetterLocationForPreview(snapshot, location)
                }
            }
        }
    }

    return snapshot
}

internal fun hasLocationPermission(context: Context): Boolean {
    val hasFineLocationPermission = hasPreciseLocationPermission(context)
    val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return hasFineLocationPermission || hasCoarseLocationPermission
}

internal fun hasPreciseLocationPermission(context: Context): Boolean {
    val hasFineLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return hasFineLocationPermission
}

private fun isLocationSuitableForChatSend(
    location: Location?,
    maxAccuracyMeters: Float,
    maxAgeMillis: Long,
): Boolean {
    if (location == null) {
        return false
    }
    if (!isLocationCoordinatePlausible(location)) {
        return false
    }
    val accuracy = locationAccuracyForComparison(location)
    val ageMillis = locationAgeForComparisonMillis(location, System.currentTimeMillis())
    return accuracy <= maxAccuracyMeters && ageMillis <= maxAgeMillis
}

internal suspend fun fetchCurrentLocationEstimateForChatSend(
    context: Context,
    bleRssi: Int?,
    preferFreshFix: Boolean = false
): ChatLocationEstimate? {
    if (!hasPreciseLocationPermission(context)) {
        return null
    }
    val liveLocation = fetchCurrentLocationForChatSend(
        context = context,
        preferFreshFix = preferFreshFix
    )
    if (liveLocation != null) {
        val confidenceRadius = computeConfidenceRadiusMeters(
            source = LOCATION_SOURCE_GPS,
            baseLocation = liveLocation,
            baseAgeMillis = locationAgeForComparisonMillis(liveLocation, System.currentTimeMillis()),
            imuDistanceMeters = 0f,
            imuHeadingStdDevDegrees = null,
            bleRssi = bleRssi
        )
        return ChatLocationEstimate(
            location = liveLocation,
            confidenceRadiusMeters = confidenceRadius,
            source = LOCATION_SOURCE_GPS
        )
    }

    val baseLocation = getBestLastKnownLocation(context) ?: return null
    val baseAgeMillis = locationAgeForComparisonMillis(baseLocation, System.currentTimeMillis())
    val baseAccuracyMeters = locationAccuracyForComparison(baseLocation)
    if (
        baseAgeMillis > CHAT_SEND_LOCATION_HYBRID_MAX_BASE_AGE_MS ||
        baseAccuracyMeters > CHAT_SEND_LOCATION_HYBRID_MAX_BASE_ACCURACY_METERS
    ) {
        return null
    }

    val imuEstimate = LowPowerBleImuEstimator(context).captureMotion()
    val hasReliableImuDisplacement = imuEstimate != null &&
        imuEstimate.distanceMeters in 0.6f..CHAT_SEND_LOCATION_IMU_MAX_DISTANCE_METERS &&
        imuEstimate.headingDegrees != null &&
        (imuEstimate.headingStdDevDegrees ?: Float.MAX_VALUE) <= CHAT_SEND_LOCATION_IMU_MAX_HEADING_STD_DEV_DEGREES
    val movedLocation = if (hasReliableImuDisplacement) {
        displaceLocation(
            base = baseLocation,
            headingDegrees = imuEstimate?.headingDegrees ?: 0f,
            distanceMeters = (imuEstimate?.distanceMeters ?: 0f).toDouble()
        )
    } else {
        Location(baseLocation)
    }

    val source = if (hasReliableImuDisplacement) {
        LOCATION_SOURCE_BLE_IMU
    } else {
        LOCATION_SOURCE_LAST_KNOWN
    }
    val confidenceRadius = computeConfidenceRadiusMeters(
        source = source,
        baseLocation = baseLocation,
        baseAgeMillis = baseAgeMillis,
        imuDistanceMeters = imuEstimate?.distanceMeters ?: 0f,
        imuHeadingStdDevDegrees = imuEstimate?.headingStdDevDegrees,
        bleRssi = bleRssi
    )

    movedLocation.provider = LOCATION_PROVIDER_HYBRID
    movedLocation.time = System.currentTimeMillis()
    movedLocation.accuracy = max(
        locationAccuracyForComparison(baseLocation),
        confidenceRadius
    )

    return ChatLocationEstimate(
        location = movedLocation,
        confidenceRadiusMeters = confidenceRadius,
        source = source
    )
}

private fun displaceLocation(
    base: Location,
    headingDegrees: Float,
    distanceMeters: Double
): Location {
    if (!distanceMeters.isFinite() || distanceMeters <= 0.0 || !headingDegrees.isFinite()) {
        return Location(base)
    }
    val earthRadiusMeters = 6_378_137.0
    val bearingRad = Math.toRadians(headingDegrees.toDouble())
    val latRad = Math.toRadians(base.latitude)
    val distanceRatio = distanceMeters / earthRadiusMeters

    val newLatRad = latRad + (distanceRatio * cos(bearingRad))
    val cosLat = cos(latRad).coerceAtLeast(1e-6)
    val newLonRad = Math.toRadians(base.longitude) + (distanceRatio * sin(bearingRad) / cosLat)

    return Location(base).apply {
        latitude = Math.toDegrees(newLatRad)
        longitude = Math.toDegrees(newLonRad)
    }
}

private fun computeConfidenceRadiusMeters(
    source: String,
    baseLocation: Location,
    baseAgeMillis: Long,
    imuDistanceMeters: Float,
    imuHeadingStdDevDegrees: Float?,
    bleRssi: Int?
): Float {
    val baseAccuracy = locationAccuracyForComparison(baseLocation).coerceIn(6f, 220f)
    val agePenalty = (baseAgeMillis / 60_000f).coerceAtLeast(0f)
    return when (source) {
        LOCATION_SOURCE_GPS -> {
            val blePenalty = when {
                bleRssi == null -> 6f
                bleRssi >= -65 -> 2f
                bleRssi >= -75 -> 5f
                else -> 9f
            }
            (baseAccuracy + (agePenalty * 0.35f) + blePenalty)
                .coerceIn(6f, 90f)
        }

        LOCATION_SOURCE_BLE_IMU -> {
            val imuPenalty = (imuDistanceMeters.coerceAtLeast(0f) * 0.68f + 11f)
                .coerceAtMost(145f)
            val headingPenalty = (imuHeadingStdDevDegrees ?: 18f)
                .coerceIn(4f, 90f) * 0.55f
            val blePenalty = when {
                bleRssi == null -> 25f
                bleRssi >= -62 -> 9f
                bleRssi >= -72 -> 15f
                bleRssi >= -82 -> 23f
                else -> 30f
            }
            (baseAccuracy + (agePenalty * 3.8f) + imuPenalty + headingPenalty + blePenalty)
                .coerceIn(24f, 360f)
        }

        else -> {
            val blePenalty = when {
                bleRssi == null -> 28f
                bleRssi >= -65 -> 14f
                bleRssi >= -75 -> 21f
                else -> 30f
            }
            (baseAccuracy + (agePenalty * 4.2f) + blePenalty + 16f)
                .coerceIn(32f, 380f)
        }
    }
}

private suspend fun fetchCurrentLocationForChatSend(
    context: Context,
    preferFreshFix: Boolean
): Location? {
    if (!hasLocationPermission(context)) {
        return null
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null

    val bestLastKnown = getBestLastKnownLocation(context)
    if (!preferFreshFix && isLocationSuitableForChatSend(
            bestLastKnown,
            CHAT_SEND_LOCATION_PREFERRED_ACCURACY_METERS,
            CHAT_SEND_LOCATION_MAX_AGE_MS
        )
    ) {
        return bestLastKnown
    }

    val providers = buildList {
        val networkEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)

        if (preferFreshFix) {
            if (gpsEnabled) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (networkEnabled) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        } else {
            if (networkEnabled) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            if (gpsEnabled) {
                add(LocationManager.GPS_PROVIDER)
            }
        }
    }
    if (providers.isEmpty()) {
        return bestLastKnown
    }

    var fallbackLocation = bestLastKnown
    for (provider in providers) {
        val timeoutMs = if (provider == LocationManager.GPS_PROVIDER) {
            CHAT_SEND_LOCATION_GPS_TIMEOUT_MS
        } else {
            CHAT_SEND_LOCATION_NETWORK_TIMEOUT_MS
        }
        val fixedLocation = requestSingleLocationFix(
            context = context,
            locationManager = locationManager,
            provider = provider,
            timeoutMs = timeoutMs
        )

        fallbackLocation = pickBetterLocationForPreview(fallbackLocation, fixedLocation)

        if (isLocationSuitableForChatSend(
                fixedLocation,
                CHAT_SEND_LOCATION_PREFERRED_ACCURACY_METERS,
                CHAT_SEND_LOCATION_MAX_AGE_MS
            )
        ) {
            return fixedLocation
        }
    }

    return fallbackLocation.takeIf {
        isLocationSuitableForChatSend(
            it,
            CHAT_SEND_LOCATION_ACCEPTABLE_ACCURACY_METERS,
            CHAT_SEND_LOCATION_MAX_AGE_MS
        )
    }
}

private suspend fun requestSingleLocationFix(
    context: Context,
    locationManager: LocationManager,
    provider: String,
    timeoutMs: Long
): Location? {
    val current = getBestLastKnownLocation(context)
    if (current != null &&
        isLocationCoordinatePlausible(current) &&
        current.provider == provider &&
        isLocationSuitableForChatSend(
            current,
            CHAT_SEND_LOCATION_ACCEPTABLE_ACCURACY_METERS,
            CHAT_SEND_LOCATION_MAX_AGE_MS / 2
        )
    ) {
        return current
    }

    return withTimeoutOrNull(timeoutMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val hasFineLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasFineLocationPermission && !hasCoarseLocationPermission) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                    return@suspendCancellableCoroutine
                }

                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation {
                    cancellationSignal.cancel()
                }
                runCatching {
                    locationManager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        ContextCompat.getMainExecutor(context)
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }.onFailure {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        } else {
            suspendCancellableCoroutine<Location?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching {
                            locationManager.removeUpdates(this)
                        }
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }

                    override fun onProviderDisabled(provider: String) {
                        runCatching { locationManager.removeUpdates(this) }
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: android.os.Bundle?
                    ) = Unit
                }

                continuation.invokeOnCancellation {
                    runCatching {
                        locationManager.removeUpdates(listener)
                    }
                }
                val granted = runCatching {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                if (!granted) {
                    locationManager.removeUpdates(listener)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                    return@suspendCancellableCoroutine
                }
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                }.onFailure {
                    locationManager.removeUpdates(listener)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
internal fun fetchCurrentLocation(context: Context, onResult: (Location?) -> Unit) {
    if (!hasLocationPermission(context)) {
        onResult(null)
        return
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (locationManager == null) {
        onResult(null)
        return
    }

    val gpsEnabled = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
    val networkEnabled = runCatching {
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)
    val bestLastKnown = getBestLastKnownLocation(context)
    val provider = when {
        bestLastKnown?.provider == LocationManager.GPS_PROVIDER && gpsEnabled -> LocationManager.GPS_PROVIDER
        bestLastKnown?.provider == LocationManager.NETWORK_PROVIDER && networkEnabled -> LocationManager.NETWORK_PROVIDER
        gpsEnabled -> LocationManager.GPS_PROVIDER
        networkEnabled -> LocationManager.NETWORK_PROVIDER
        else -> null
    }

    if (provider == null) {
        onResult(bestLastKnown)
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            locationManager.getCurrentLocation(
                provider,
                null,
                ContextCompat.getMainExecutor(context)
            ) { location ->
                onResult(
                    pickBetterLocationForPreview(
                        current = bestLastKnown,
                        candidate = location
                    ) ?: bestLastKnown
                )
            }
            return
        } catch (_: Throwable) {
            onResult(bestLastKnown)
            return
        }
    }

    onResult(bestLastKnown)
}

@SuppressLint("MissingPermission")
private fun getBestLastKnownLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) {
        return null
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        var bestScore = Double.MAX_VALUE
        val nowMillis = System.currentTimeMillis()
        for (provider in providers) {
            val location = locationManager.getLastKnownLocation(provider) ?: continue
            if (!isLocationCoordinatePlausible(location)) {
                continue
            }
            val score = locationQualityScore(location, nowMillis)
            if (
                bestLocation == null ||
                score < bestScore ||
                (abs(score - bestScore) < 0.2 && location.time > bestLocation.time)
            ) {
                bestLocation = location
                bestScore = score
            }
        }
        bestLocation
    } catch (_: SecurityException) {
        null
    }
}

internal fun createPendingImageFromUri(context: Context, uri: Uri): PendingImage? {
    val dimensions = decodeImageDimensions(context, uri)
    val mimeType = resolveMimeType(context, uri)
    return PendingImage(
        uri = uri,
        mimeType = mimeType,
        width = dimensions?.first,
        height = dimensions?.second
    )
}

private fun resolveMimeType(context: Context, uri: Uri): String? {
    val resolver = context.contentResolver
    resolver.getType(uri)?.let { return it }
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        val displayName = queryDisplayName(resolver, uri)
        val extension = displayName?.substringAfterLast('.', "")?.lowercase(Locale.getDefault())
        if (!extension.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
    }
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase(Locale.getDefault())
        if (!extension.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
    }
    return null
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            }
    }.getOrNull()
}

private fun decodeImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
    return runCatching {
        val resolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null
        val rawWidth = options.outWidth
        val rawHeight = options.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            return null
        }
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_UNDEFINED
        adjustDimensionsForOrientation(rawWidth, rawHeight, orientation)
    }.getOrNull()
}

internal fun decodeImageFileDimensions(file: File): Pair<Int, Int>? {
    return runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val rawWidth = options.outWidth
        val rawHeight = options.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            return null
        }
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        adjustDimensionsForOrientation(rawWidth, rawHeight, orientation)
    }.getOrNull()
}

private fun adjustDimensionsForOrientation(
    width: Int,
    height: Int,
    orientation: Int
): Pair<Int, Int> {
    return if (
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
    ) {
        height to width
    } else {
        width to height
    }
}

internal fun resolveHeadingForNavigation(
    sensorHeadingDegrees: Float?
): Float? {
    return sensorHeadingDegrees
        ?.takeIf { it.isFinite() }
        ?.let(::normalizeHeadingDegrees)
}

private data class DeviceAxisVector(
    val x: Float,
    val y: Float,
    val z: Float
)

private data class HorizontalHeadingVector(
    val east: Float,
    val north: Float
) {
    val magnitude: Float
        get() = sqrt((east * east) + (north * north))
}

internal fun resolveNavigationHeadingDegrees(
    rotationMatrix: FloatArray,
    displayRotation: Int,
    declinationDegrees: Float? = null
): Float? {
    if (rotationMatrix.size < 9) {
        return null
    }
    val screenTopVector = horizontalHeadingVectorForAxis(
        rotationMatrix = rotationMatrix,
        axis = screenTopAxisForDisplayRotation(displayRotation)
    )
    val forwardVector = horizontalHeadingVectorForAxis(
        rotationMatrix = rotationMatrix,
        axis = DeviceAxisVector(x = 0f, y = 0f, z = -1f)
    )
    val tiltFromFlatDegrees = resolveTiltFromFlatDegrees(rotationMatrix)
    val blendedVector = blendNavigationHeadingVector(
        screenTopVector = screenTopVector,
        forwardVector = forwardVector,
        tiltFromFlatDegrees = tiltFromFlatDegrees
    )
    val fallbackVector = if (screenTopVector.magnitude >= forwardVector.magnitude) {
        screenTopVector
    } else {
        forwardVector
    }
    val headingVector = if (blendedVector.magnitude >= NAVIGATION_HEADING_MIN_VECTOR_MAGNITUDE) {
        blendedVector
    } else {
        fallbackVector
    }
    if (headingVector.magnitude < NAVIGATION_HEADING_MIN_VECTOR_MAGNITUDE) {
        return null
    }
    val magneticHeading = normalizeHeadingDegrees(
        Math.toDegrees(
            atan2(
                headingVector.east.toDouble(),
                headingVector.north.toDouble()
            )
        ).toFloat()
    )
    return normalizeHeadingDegrees(magneticHeading + (declinationDegrees ?: 0f))
}

internal fun resolveTiltFromFlatDegrees(rotationMatrix: FloatArray): Float {
    if (rotationMatrix.size < 9) {
        return 90f
    }
    val zAxisAlignment = abs(rotationMatrix[8]).coerceIn(0f, 1f)
    return Math.toDegrees(
        acos(zAxisAlignment.toDouble())
    ).toFloat()
}

private fun screenTopAxisForDisplayRotation(displayRotation: Int): DeviceAxisVector {
    return when (displayRotation) {
        Surface.ROTATION_90 -> DeviceAxisVector(x = -1f, y = 0f, z = 0f)
        Surface.ROTATION_180 -> DeviceAxisVector(x = 0f, y = -1f, z = 0f)
        Surface.ROTATION_270 -> DeviceAxisVector(x = 1f, y = 0f, z = 0f)
        else -> DeviceAxisVector(x = 0f, y = 1f, z = 0f)
    }
}

private fun horizontalHeadingVectorForAxis(
    rotationMatrix: FloatArray,
    axis: DeviceAxisVector
): HorizontalHeadingVector {
    val east = (
        rotationMatrix[0] * axis.x
            ) + (
        rotationMatrix[1] * axis.y
            ) + (
        rotationMatrix[2] * axis.z
        )
    val north = (
        rotationMatrix[3] * axis.x
            ) + (
        rotationMatrix[4] * axis.y
            ) + (
        rotationMatrix[5] * axis.z
        )
    return HorizontalHeadingVector(east = east, north = north)
}

private fun blendNavigationHeadingVector(
    screenTopVector: HorizontalHeadingVector,
    forwardVector: HorizontalHeadingVector,
    tiltFromFlatDegrees: Float
): HorizontalHeadingVector {
    val forwardWeight = when {
        tiltFromFlatDegrees <= NAVIGATION_HEADING_BLEND_START_TILT_DEGREES -> 0f
        tiltFromFlatDegrees >= NAVIGATION_HEADING_BLEND_END_TILT_DEGREES -> 1f
        else -> (
            (tiltFromFlatDegrees - NAVIGATION_HEADING_BLEND_START_TILT_DEGREES) /
                (NAVIGATION_HEADING_BLEND_END_TILT_DEGREES - NAVIGATION_HEADING_BLEND_START_TILT_DEGREES)
            ).coerceIn(0f, 1f)
    }
    val screenTopWeight = 1f - forwardWeight
    return HorizontalHeadingVector(
        east = (screenTopVector.east * screenTopWeight) + (forwardVector.east * forwardWeight),
        north = (screenTopVector.north * screenTopWeight) + (forwardVector.north * forwardWeight)
    )
}

@Composable
internal fun rememberDeviceOrientationSnapshot(
    referenceLocation: Location? = null
): DeviceOrientationSnapshot {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val rotationVectorSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    val declinationAdjustment = remember(
        referenceLocation?.latitude,
        referenceLocation?.longitude,
        referenceLocation?.altitude,
        referenceLocation?.time
    ) {
        referenceLocation?.let { location ->
            runCatching {
                GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    location.altitude.toFloat(),
                    location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                ).declination
            }.getOrNull()
        }
    }
    var heading by remember { mutableStateOf<Float?>(null) }
    var tiltFromFlat by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(
        lifecycleOwner.lifecycle,
        sensorManager,
        rotationVectorSensor,
        declinationAdjustment
    ) {
        if (sensorManager == null || rotationVectorSensor == null) {
            heading = null
            tiltFromFlat = null
            return@DisposableEffect onDispose { }
        }

        val rotationMatrix = FloatArray(9)
        var smoothedHeading: Float? = null
        var lastKnownAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        var lastEmittedHeading: Float? = null
        var lastEmittedAtMillis = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) {
                    return
                }
                if (lastKnownAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    heading = null
                    return
                }
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                tiltFromFlat = resolveTiltFromFlatDegrees(rotationMatrix)
                val rawHeading = resolveNavigationHeadingDegrees(
                    rotationMatrix = rotationMatrix,
                    displayRotation = resolveDisplayRotation(context),
                    declinationDegrees = declinationAdjustment
                ) ?: run {
                    heading = null
                    return
                }
                val previous = smoothedHeading
                val next = if (previous == null) {
                    rawHeading
                } else {
                    val delta = shortestHeadingDelta(previous, rawHeading)
                    val alpha = when {
                        abs(delta) >= 45f -> 0.68f
                        abs(delta) >= 20f -> 0.46f
                        else -> 0.30f
                    }
                    normalizeHeadingDegrees(previous + delta * alpha)
                }
                smoothedHeading = next
                val nowMillis = event.timestamp / 1_000_000L
                val emittedHeading = lastEmittedHeading
                if (emittedHeading != null) {
                    val deltaFromLastEmit = abs(shortestHeadingDelta(emittedHeading, next))
                    val elapsedMillis = nowMillis - lastEmittedAtMillis
                    if (
                        deltaFromLastEmit < SENSOR_HEADING_MIN_EMIT_DELTA_DEGREES &&
                        elapsedMillis in 0 until SENSOR_HEADING_MIN_EMIT_INTERVAL_MS
                    ) {
                        return
                    }
                }
                lastEmittedHeading = next
                lastEmittedAtMillis = nowMillis
                heading = next
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type != Sensor.TYPE_ROTATION_VECTOR) {
                    return
                }
                lastKnownAccuracy = accuracy
                if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    heading = null
                }
            }
        }

        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    sensorManager.registerListener(
                        listener,
                        rotationVectorSensor,
                        SensorManager.SENSOR_DELAY_UI
                    )
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    sensorManager.unregisterListener(listener)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            sensorManager.registerListener(
                listener,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        onDispose {
            lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }
    return DeviceOrientationSnapshot(
        headingDegrees = heading,
        tiltFromFlatDegrees = tiltFromFlat,
        headingReliable = heading != null
    )
}

private fun resolveDisplayRotation(context: Context): Int {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    return runCatching {
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }.getOrDefault(Surface.ROTATION_0)
}
