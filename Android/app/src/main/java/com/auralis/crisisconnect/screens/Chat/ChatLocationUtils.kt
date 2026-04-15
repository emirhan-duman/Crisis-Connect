package com.auralis.crisisconnect.screens.Chat

import android.content.Context
import android.location.Location
import com.auralis.crisisconnect.R
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class SharedLocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val timestampMillis: Long? = null,
    val confidenceRadiusMeters: Float? = null,
    val source: String? = null
)

internal data class ChatLocationEstimate(
    val location: Location,
    val confidenceRadiusMeters: Float,
    val source: String
)

internal data class ComparisonMarkerLayout(
    val ownMarkerLatLng: LatLng,
    val sharedMarkerLatLng: LatLng,
    val cameraDistanceMeters: Double
)

private const val LOCATION_MESSAGE_PREFIX = "CC_LOC:"
internal const val LOCATION_SOURCE_GPS = "gps"
internal const val LOCATION_SOURCE_BLE_IMU = "ble_imu"
internal const val LOCATION_SOURCE_LAST_KNOWN = "last_known"
internal const val LOCATION_DEFAULT_ACCURACY_METERS = 120f
private const val CONNECTED_BLUETOOTH_FALLBACK_DISTANCE_METERS = 4.0
private const val MAP_CAMERA_DISTANCE_ANIMATE_THRESHOLD_METERS = 10.0
private const val MAP_CAMERA_ZOOM_ANIMATE_THRESHOLD = 0.28
private const val MAP_CAMERA_BEARING_ANIMATE_THRESHOLD_DEGREES = 13f
private const val LOCATION_CAMERA_MIN_SPAN_METERS = 42.0
private const val LOCATION_CAMERA_CLOSE_RANGE_DISTANCE_THRESHOLD_METERS = 18.0
private const val LOCATION_CAMERA_CLOSE_RANGE_MIN_SPAN_METERS = 10.0
private const val LOCATION_CAMERA_CLOSE_RANGE_ACCURACY_WEIGHT = 0.38
private const val LOCATION_MARKER_OVERLAP_DISTANCE_THRESHOLD_METERS = 8.0
private const val LOCATION_MARKER_MIN_VISUAL_SEPARATION_METERS = 10.0
private const val LOCATION_MARKER_MAX_VISUAL_SEPARATION_METERS = 14.0
private const val LOCATION_CAMERA_BASE_PADDING_METERS = 28.0
private const val LOCATION_CAMERA_FIT_MARGIN_FACTOR = 1.26

internal fun buildLocationPayload(estimate: ChatLocationEstimate): String {
    val location = estimate.location
    val latitude = formatLocationCoordinate(location.latitude)
    val longitude = formatLocationCoordinate(location.longitude)
    val accuracy = location.accuracy
        .takeIf { it > 0f && it.isFinite() }
        ?: estimate.confidenceRadiusMeters.coerceIn(LOCATION_DEFAULT_ACCURACY_METERS, 500f)
    val accuracyText = String.format(Locale.US, "%.1f", accuracy)
    val timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
    val confidenceRadius = estimate.confidenceRadiusMeters
        .takeIf { it > 0f && it.isFinite() }
        ?.let { String.format(Locale.US, "%.1f", it) }
    val source = estimate.source.lowercase(Locale.US).takeIf { it.isNotBlank() }
    return buildString {
        append(LOCATION_MESSAGE_PREFIX)
        append(latitude)
        append(',')
        append(longitude)
        append(',')
        append(accuracyText)
        append(',')
        append(timestamp)
        if (confidenceRadius != null) {
            append(',')
            append(confidenceRadius)
        }
        if (source != null) {
            append(',')
            append(source)
        }
    }
}

internal fun parseSharedLocationPayload(text: String): SharedLocationPayload? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
        return null
    }

    parseLocationPayloadCandidate(trimmed)?.let { return it }

    trimmed.lineSequence().forEach { line ->
        parseLocationPayloadCandidate(line)?.let { return it }
    }

    val mapsMatch = Regex("""https?://maps\.google\.com/\?q=([-0-9.]+),([-0-9.]+)""")
        .find(trimmed)
    if (mapsMatch != null) {
        return parseLocationCoordinates("${mapsMatch.groupValues[1]},${mapsMatch.groupValues[2]}")
    }

    val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
    val hasLocationPrefix = firstLine.contains("location", ignoreCase = true) ||
        firstLine.contains("konum", ignoreCase = true)
    if (hasLocationPrefix) {
        val coordinateMatch = Regex("""([-0-9.]+)\s*,\s*([-0-9.]+)""")
            .find(firstLine)
        if (coordinateMatch != null) {
            return parseLocationCoordinates(
                "${coordinateMatch.groupValues[1]},${coordinateMatch.groupValues[2]}"
            )
        }
    }

    return null
}

private fun parseLocationPayloadCandidate(text: String): SharedLocationPayload? {
    val prefixIndex = text.indexOf(LOCATION_MESSAGE_PREFIX, ignoreCase = true)
    if (prefixIndex < 0) {
        return null
    }
    val payload = text.substring(prefixIndex + LOCATION_MESSAGE_PREFIX.length).trim()
    if (payload.isEmpty()) {
        return null
    }
    return parseLocationCoordinates(payload)
}

private fun parseLocationCoordinates(raw: String): SharedLocationPayload? {
    val parts = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (parts.size < 2) {
        return null
    }
    val latitude = parts[0].toDoubleOrNull() ?: return null
    val longitude = parts[1].toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
        return null
    }
    val accuracy = parts.getOrNull(2)?.toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }
    val timestamp = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0L }
    val confidenceRadius = parts.getOrNull(4)
        ?.toFloatOrNull()
        ?.takeIf { it > 0f && it.isFinite() }
    val source = parts.getOrNull(5)
        ?.lowercase(Locale.US)
        ?.takeIf {
            it == LOCATION_SOURCE_GPS ||
                it == LOCATION_SOURCE_BLE_IMU ||
                it == LOCATION_SOURCE_LAST_KNOWN
        }
    return SharedLocationPayload(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        timestampMillis = timestamp,
        confidenceRadiusMeters = confidenceRadius,
        source = source
    )
}

internal fun SharedLocationPayload.effectiveUncertaintyMeters(defaultMeters: Double): Double {
    val fromAccuracy = accuracyMeters
        ?.takeIf { it > 0f && it.isFinite() }
        ?.toDouble()
    val fromConfidence = confidenceRadiusMeters
        ?.takeIf { it > 0f && it.isFinite() }
        ?.toDouble()
    return when {
        fromAccuracy != null && fromConfidence != null -> max(fromAccuracy, fromConfidence)
        fromConfidence != null -> fromConfidence
        fromAccuracy != null -> fromAccuracy
        else -> defaultMeters
    }.coerceIn(1.5, 380.0)
}

internal fun buildConfidenceRadiusText(
    context: Context,
    payload: SharedLocationPayload
): String? {
    val radius = payload.confidenceRadiusMeters
        ?.takeIf { it > 0f && it.isFinite() }
        ?: payload.accuracyMeters?.takeIf { it > 0f && it.isFinite() }
        ?: return null
    val sourceLabel = when (payload.source) {
        LOCATION_SOURCE_BLE_IMU -> context.getString(R.string.chat_location_source_ble_imu)
        LOCATION_SOURCE_LAST_KNOWN -> context.getString(R.string.chat_location_source_last_known)
        else -> context.getString(R.string.chat_location_source_gps)
    }
    return context.getString(
        R.string.chat_location_confidence_radius,
        radius,
        sourceLabel
    )
}

internal fun calculateDistanceMeters(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double
): Double {
    val startLatitudeRadians = Math.toRadians(fromLatitude)
    val endLatitudeRadians = Math.toRadians(toLatitude)
    val latitudeDeltaRadians = Math.toRadians(toLatitude - fromLatitude)
    val longitudeDeltaRadians = Math.toRadians(toLongitude - fromLongitude)
    val haversine = (
        sin(latitudeDeltaRadians / 2.0) * sin(latitudeDeltaRadians / 2.0)
    ) + (
        cos(startLatitudeRadians) *
            cos(endLatitudeRadians) *
            sin(longitudeDeltaRadians / 2.0) *
            sin(longitudeDeltaRadians / 2.0)
        )
    val clampedHaversine = haversine.coerceIn(0.0, 1.0)
    val centralAngle = 2.0 * atan2(
        sqrt(clampedHaversine),
        sqrt(1.0 - clampedHaversine)
    )
    return (6_371_000.0 * centralAngle).coerceAtLeast(0.0)
}

private fun formatDistance(distanceMeters: Double): String {
    return if (distanceMeters < 1_000.0) {
        String.format(Locale.getDefault(), "%.0f m", distanceMeters)
    } else {
        String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1_000.0)
    }
}

internal fun buildDistanceRangeText(
    context: Context,
    ownLocation: Location,
    payload: SharedLocationPayload,
    isBluetoothConnected: Boolean,
    bluetoothSignalInfo: SignalStrengthInfo?
): String {
    val gpsDistanceMeters = calculateDistanceMeters(
        fromLatitude = ownLocation.latitude,
        fromLongitude = ownLocation.longitude,
        toLatitude = payload.latitude,
        toLongitude = payload.longitude
    )
    val ownAccuracyMeters = ownLocation.accuracy
        .takeIf { it > 0f && it.isFinite() }
        ?.toDouble()
        ?.coerceIn(1.5, 35.0)
        ?: 10.0
    val sharedAccuracyMeters = payload.effectiveUncertaintyMeters(defaultMeters = 8.0)
        .coerceIn(1.5, 35.0)
    val combinedAccuracyMeters = sqrt(
        (ownAccuracyMeters * ownAccuracyMeters) + (sharedAccuracyMeters * sharedAccuracyMeters)
    )
    val bluetoothDistanceMeters = when {
        !isBluetoothConnected -> null
        bluetoothSignalInfo?.rssi != null -> estimateDistanceFromRssiMeters(bluetoothSignalInfo.rssi)
        else -> CONNECTED_BLUETOOTH_FALLBACK_DISTANCE_METERS
    }
    val estimatedCenter = bluetoothDistanceMeters?.let { bluetooth ->
        if (gpsDistanceMeters <= 40.0) {
            (gpsDistanceMeters * 0.25) + (bluetooth * 0.75)
        } else {
            (gpsDistanceMeters * 0.45) + (bluetooth * 0.55)
        }
    } ?: gpsDistanceMeters
    val uncertaintyScale = when {
        bluetoothSignalInfo?.rssi != null -> 0.38
        isBluetoothConnected -> 0.50
        else -> 0.72
    }
    var uncertainty = combinedAccuracyMeters * uncertaintyScale
    uncertainty = when {
        estimatedCenter <= 10.0 -> uncertainty.coerceIn(2.0, 8.0)
        estimatedCenter <= 25.0 -> uncertainty.coerceIn(3.0, 12.0)
        else -> uncertainty.coerceIn(4.0, 42.0)
    }
    val minDistance = (estimatedCenter - uncertainty).coerceAtLeast(0.0)
    val maxDistance = estimatedCenter + uncertainty
    val rangeText = "${formatDistance(minDistance)} - ${formatDistance(maxDistance)}"
    return context.getString(R.string.chat_location_distance, rangeText)
}

internal fun isDirectionEstimateReliable(
    ownLocation: Location?,
    payload: SharedLocationPayload
): Boolean {
    val location = ownLocation ?: return false
    val distanceMeters = calculateDistanceMeters(
        fromLatitude = location.latitude,
        fromLongitude = location.longitude,
        toLatitude = payload.latitude,
        toLongitude = payload.longitude
    )
    val ownAccuracyMeters = location.accuracy
        .takeIf { it > 0f && it.isFinite() }
        ?.toDouble()
        ?: 12.0
    val sharedAccuracyMeters = payload.effectiveUncertaintyMeters(defaultMeters = 8.0)
    val minimumReliableDistance = max(6.0, (ownAccuracyMeters + sharedAccuracyMeters) * 1.2)
    return distanceMeters >= minimumReliableDistance
}

internal fun coordinateToE5(value: Double): Int = (value * 100_000.0).roundToInt()

internal fun quantizeHeadingDegrees(heading: Float, stepDegrees: Float): Float {
    val normalized = normalizeHeadingDegrees(heading)
    if (!stepDegrees.isFinite() || stepDegrees <= 0f) {
        return normalized
    }
    val bucket = (normalized / stepDegrees).roundToInt()
    return normalizeHeadingDegrees(bucket * stepDegrees)
}

internal fun shouldAnimateCameraTransition(
    currentCamera: CameraPosition?,
    targetCenter: LatLng,
    targetZoom: Double,
    targetBearing: Double?
): Boolean {
    val camera = currentCamera ?: return true
    val currentTarget = camera.target ?: return true
    val centerShiftMeters = calculateDistanceMeters(
        fromLatitude = currentTarget.latitude,
        fromLongitude = currentTarget.longitude,
        toLatitude = targetCenter.latitude,
        toLongitude = targetCenter.longitude
    )
    val zoomDelta = abs(camera.zoom - targetZoom)
    val bearingDelta = targetBearing?.let { bearing ->
        abs(
            shortestHeadingDelta(
                from = normalizeHeadingDegrees(camera.bearing.toFloat()),
                to = normalizeHeadingDegrees(bearing.toFloat())
            )
        )
    } ?: 0f
    return centerShiftMeters >= MAP_CAMERA_DISTANCE_ANIMATE_THRESHOLD_METERS ||
        zoomDelta >= MAP_CAMERA_ZOOM_ANIMATE_THRESHOLD ||
        bearingDelta >= MAP_CAMERA_BEARING_ANIMATE_THRESHOLD_DEGREES
}

internal fun zoomForDistanceMeters(distanceMeters: Double): Double {
    val clamped = distanceMeters.coerceAtLeast(1.0)
    val stops = arrayOf(
        2.0 to 20.8,
        8.0 to 20.1,
        20.0 to 19.1,
        45.0 to 18.2,
        90.0 to 17.4,
        180.0 to 16.8,
        350.0 to 16.1,
        700.0 to 15.4,
        1_400.0 to 14.7,
        2_800.0 to 14.0,
        5_500.0 to 13.3,
        11_000.0 to 12.5,
        22_000.0 to 11.7,
        45_000.0 to 10.8,
        90_000.0 to 9.9
    )
    if (clamped <= stops.first().first) {
        return stops.first().second
    }
    for (index in 1 until stops.size) {
        val (prevDistance, prevZoom) = stops[index - 1]
        val (nextDistance, nextZoom) = stops[index]
        if (clamped <= nextDistance) {
            val t = ((clamped - prevDistance) / (nextDistance - prevDistance))
                .coerceIn(0.0, 1.0)
            return prevZoom + ((nextZoom - prevZoom) * t)
        }
    }
    return stops.last().second
}

internal fun comparisonCameraSpanMeters(
    distanceMeters: Double,
    ownAccuracyMeters: Double,
    sharedAccuracyMeters: Double,
    cameraBearingDegrees: Double?
): Double {
    val clampedDistanceMeters = distanceMeters.coerceAtLeast(0.0)
    if (clampedDistanceMeters <= LOCATION_CAMERA_CLOSE_RANGE_DISTANCE_THRESHOLD_METERS) {
        val closeDistanceSpan = clampedDistanceMeters.coerceAtLeast(1.0) * 2.4
        val closeAccuracySpan = (
            max(ownAccuracyMeters, sharedAccuracyMeters) * LOCATION_CAMERA_CLOSE_RANGE_ACCURACY_WEIGHT
        ) + 6.0
        return max(
            max(closeDistanceSpan, closeAccuracySpan),
            LOCATION_CAMERA_CLOSE_RANGE_MIN_SPAN_METERS
        )
    }
    val normalizedBearing = (((cameraBearingDegrees ?: 0.0) % 90.0) + 90.0) % 90.0
    val bearingRadians = Math.toRadians(normalizedBearing)
    val rotationFitFactor = abs(cos(bearingRadians)) + abs(sin(bearingRadians))
    val distanceSpan = clampedDistanceMeters *
        rotationFitFactor *
        LOCATION_CAMERA_FIT_MARGIN_FACTOR
    val uncertaintySpan = (ownAccuracyMeters + sharedAccuracyMeters + LOCATION_CAMERA_BASE_PADDING_METERS) *
        max(1.0, rotationFitFactor * 0.9)
    return max(
        max(distanceSpan, uncertaintySpan),
        LOCATION_CAMERA_MIN_SPAN_METERS
    )
}

internal fun markerScaleForComparisonDistance(distanceMeters: Double): Float = when {
    !distanceMeters.isFinite() -> 1f
    distanceMeters <= 6.0 -> 0.68f
    distanceMeters <= LOCATION_CAMERA_CLOSE_RANGE_DISTANCE_THRESHOLD_METERS -> 0.82f
    else -> 1f
}

internal fun resolveComparisonMarkerLayout(
    ownLocation: Location,
    shared: SharedLocationPayload,
    cameraBearingDegrees: Double?
): ComparisonMarkerLayout {
    val ownActualLatLng = LatLng(ownLocation.latitude, ownLocation.longitude)
    val sharedActualLatLng = LatLng(shared.latitude, shared.longitude)
    val actualDistanceMeters = calculateDistanceMeters(
        fromLatitude = ownLocation.latitude,
        fromLongitude = ownLocation.longitude,
        toLatitude = shared.latitude,
        toLongitude = shared.longitude
    )
    if (actualDistanceMeters > LOCATION_MARKER_OVERLAP_DISTANCE_THRESHOLD_METERS) {
        return ComparisonMarkerLayout(
            ownMarkerLatLng = ownActualLatLng,
            sharedMarkerLatLng = sharedActualLatLng,
            cameraDistanceMeters = actualDistanceMeters
        )
    }

    val midpoint = LatLng(
        (ownLocation.latitude + shared.latitude) / 2.0,
        (ownLocation.longitude + shared.longitude) / 2.0
    )
    val actualBearingDegrees = bearingBetweenCoordinatesDegrees(
        fromLatitude = ownLocation.latitude,
        fromLongitude = ownLocation.longitude,
        toLatitude = shared.latitude,
        toLongitude = shared.longitude
    )
    val spreadBearingDegrees = normalizeHeadingDegrees(
        ((actualBearingDegrees ?: cameraBearingDegrees ?: 0.0) + 90.0).toFloat()
    ).toDouble()
    val visualSeparationMeters = (actualDistanceMeters + 6.0)
        .coerceIn(
            LOCATION_MARKER_MIN_VISUAL_SEPARATION_METERS,
            LOCATION_MARKER_MAX_VISUAL_SEPARATION_METERS
        )
    val halfSeparationMeters = visualSeparationMeters / 2.0
    return ComparisonMarkerLayout(
        ownMarkerLatLng = offsetLatLng(
            origin = midpoint,
            bearingDegrees = spreadBearingDegrees + 180.0,
            distanceMeters = halfSeparationMeters
        ),
        sharedMarkerLatLng = offsetLatLng(
            origin = midpoint,
            bearingDegrees = spreadBearingDegrees,
            distanceMeters = halfSeparationMeters
        ),
        cameraDistanceMeters = max(actualDistanceMeters, visualSeparationMeters)
    )
}

private fun bearingBetweenCoordinatesDegrees(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double
): Double? {
    val distanceMeters = calculateDistanceMeters(
        fromLatitude = fromLatitude,
        fromLongitude = fromLongitude,
        toLatitude = toLatitude,
        toLongitude = toLongitude
    )
    if (!distanceMeters.isFinite() || distanceMeters <= 0.3) {
        return null
    }
    val startLatitudeRadians = Math.toRadians(fromLatitude)
    val endLatitudeRadians = Math.toRadians(toLatitude)
    val longitudeDeltaRadians = Math.toRadians(toLongitude - fromLongitude)
    val y = sin(longitudeDeltaRadians) * cos(endLatitudeRadians)
    val x = (
        cos(startLatitudeRadians) * sin(endLatitudeRadians)
        ) - (
        sin(startLatitudeRadians) *
            cos(endLatitudeRadians) *
            cos(longitudeDeltaRadians)
        )
    if (!x.isFinite() || !y.isFinite()) {
        return null
    }
    return normalizeHeadingDegrees(Math.toDegrees(atan2(y, x)).toFloat()).toDouble()
}

private fun offsetLatLng(
    origin: LatLng,
    bearingDegrees: Double,
    distanceMeters: Double
): LatLng {
    if (!distanceMeters.isFinite() || distanceMeters <= 0.0) {
        return origin
    }
    val earthRadiusMeters = 6_378_137.0
    val distanceRatio = distanceMeters / earthRadiusMeters
    val bearingRadians = Math.toRadians(bearingDegrees)
    val latitudeRadians = Math.toRadians(origin.latitude)
    val longitudeRadians = Math.toRadians(origin.longitude)

    val nextLatitudeRadians = asin(
        (sin(latitudeRadians) * cos(distanceRatio)) +
            (cos(latitudeRadians) * sin(distanceRatio) * cos(bearingRadians))
    )
    val nextLongitudeRadians = longitudeRadians + atan2(
        sin(bearingRadians) * sin(distanceRatio) * cos(latitudeRadians),
        cos(distanceRatio) - (sin(latitudeRadians) * sin(nextLatitudeRadians))
    )
    return LatLng(
        Math.toDegrees(nextLatitudeRadians),
        Math.toDegrees(nextLongitudeRadians)
    )
}

internal fun estimateDistanceFromRssiMeters(rssi: Int): Double = when {
    rssi >= -50 -> 1.0
    rssi >= -55 -> 1.8
    rssi >= -60 -> 2.8
    rssi >= -67 -> 4.5
    rssi >= -75 -> 8.5
    rssi >= -85 -> 15.0
    else -> 28.0
}

internal fun describeBluetoothProximity(context: Context, rssi: Int): String = when {
    rssi >= -55 -> context.getString(R.string.chat_location_bluetooth_very_close)
    rssi >= -65 -> context.getString(R.string.chat_location_bluetooth_close)
    rssi >= -75 -> context.getString(R.string.chat_location_bluetooth_medium)
    else -> context.getString(R.string.chat_location_bluetooth_far)
}

internal fun normalizeHeadingDegrees(value: Float): Float {
    val normalized = value % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

internal fun shortestHeadingDelta(from: Float, to: Float): Float {
    val rawDelta = (to - from + 540f) % 360f - 180f
    return if (rawDelta < -180f) rawDelta + 360f else rawDelta
}

internal fun directionLabelResForHeading(heading: Float): Int {
    val normalized = normalizeHeadingDegrees(heading)
    return when {
        normalized < 22.5f || normalized >= 337.5f -> R.string.compass_direction_n
        normalized < 67.5f -> R.string.compass_direction_ne
        normalized < 112.5f -> R.string.compass_direction_e
        normalized < 157.5f -> R.string.compass_direction_se
        normalized < 202.5f -> R.string.compass_direction_s
        normalized < 247.5f -> R.string.compass_direction_sw
        normalized < 292.5f -> R.string.compass_direction_w
        else -> R.string.compass_direction_nw
    }
}

private fun relativeDirectionLabelResForDelta(deltaDegrees: Float): Int {
    val absDelta = abs(deltaDegrees)
    return when {
        absDelta < 22.5f -> R.string.chat_location_relative_ahead
        absDelta < 67.5f && deltaDegrees >= 0f -> R.string.chat_location_relative_ahead_right
        absDelta < 67.5f -> R.string.chat_location_relative_ahead_left
        absDelta < 112.5f && deltaDegrees >= 0f -> R.string.chat_location_relative_right
        absDelta < 112.5f -> R.string.chat_location_relative_left
        absDelta < 157.5f && deltaDegrees >= 0f -> R.string.chat_location_relative_behind_right
        absDelta < 157.5f -> R.string.chat_location_relative_behind_left
        else -> R.string.chat_location_relative_behind
    }
}

internal fun buildRelativeDirectionText(
    context: Context,
    phoneHeadingDegrees: Float,
    targetBearingDegrees: Float
): String {
    val delta = shortestHeadingDelta(
        from = normalizeHeadingDegrees(phoneHeadingDegrees),
        to = normalizeHeadingDegrees(targetBearingDegrees)
    )
    val directionLabel = context.getString(relativeDirectionLabelResForDelta(delta))
    return context.getString(
        R.string.chat_location_relative_direction,
        directionLabel,
        abs(delta).roundToInt()
    )
}
