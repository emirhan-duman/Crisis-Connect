package com.auralis.crisisconnect.screens.Tools

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class BreadcrumbTrailMode { PAUSED, RECORDING, RETURNING, ARRIVED }

enum class BreadcrumbReturnTarget { START, LAST_SAFE }

data class BreadcrumbPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val timestampMillis: Long,
)

data class BreadcrumbTrailSession(
    val id: String,
    val startedAtMillis: Long,
    val points: List<BreadcrumbPoint>,
    val safePointIndex: Int,
    val mode: BreadcrumbTrailMode,
    val returnTarget: BreadcrumbReturnTarget,
    val returnCursor: Int?,
)

data class BreadcrumbRuntimeState(
    val session: BreadcrumbTrailSession? = null,
    val currentLocation: BreadcrumbPoint? = null,
)

internal object BreadcrumbTrailMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(a: BreadcrumbPoint, b: BreadcrumbPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = Math.toRadians(b.latitude - a.latitude)
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearingDegrees(from: BreadcrumbPoint, to: BreadcrumbPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun initialReturnCursor(points: List<BreadcrumbPoint>, destinationIndex: Int): Int? {
        if (points.size < 2) return null
        val destination = destinationIndex.coerceIn(0, points.lastIndex)
        val current = points.last()
        var cursor = points.lastIndex - 1
        while (cursor > destination && distanceMeters(current, points[cursor]) < ARRIVAL_RADIUS_METERS) {
            cursor--
        }
        return cursor.coerceAtLeast(destination)
    }

    fun advanceReturnCursor(
        points: List<BreadcrumbPoint>,
        current: BreadcrumbPoint,
        cursor: Int,
        destinationIndex: Int,
    ): Int {
        val destination = destinationIndex.coerceIn(0, points.lastIndex)
        var next = cursor.coerceIn(destination, points.lastIndex)
        val arrivalRadius = max(ARRIVAL_RADIUS_METERS, current.accuracyMeters.toDouble().coerceAtMost(35.0))
        while (next > destination && distanceMeters(current, points[next]) <= arrivalRadius) {
            next--
        }
        return next
    }

    fun routeDistance(points: List<BreadcrumbPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    fun remainingRouteDistance(
        points: List<BreadcrumbPoint>,
        current: BreadcrumbPoint,
        cursor: Int,
        destinationIndex: Int,
    ): Double {
        val destination = destinationIndex.coerceIn(0, points.lastIndex)
        val safeCursor = cursor.coerceIn(destination, points.lastIndex)
        var distance = distanceMeters(current, points[safeCursor])
        for (index in safeCursor downTo destination + 1) {
            distance += distanceMeters(points[index], points[index - 1])
        }
        return distance
    }

    const val ARRIVAL_RADIUS_METERS = 15.0
}

object BreadcrumbTrailRepository {
    private const val PREFERENCES_NAME = "breadcrumb_trail"
    private const val SESSION_KEY = "active_session"
    private const val MAX_ACCEPTED_ACCURACY_METERS = 75f
    private const val MIN_POINT_DISTANCE_METERS = 8.0
    private const val MIN_POINT_INTERVAL_MILLIS = 4_000L
    private const val STATIONARY_HEARTBEAT_MILLIS = 45_000L
    private const val MAX_POINTS = 5_000

    private val lock = Any()
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var preferences: SharedPreferences
    private val _state = MutableStateFlow(BreadcrumbRuntimeState())
    val state: StateFlow<BreadcrumbRuntimeState> = _state.asStateFlow()

    fun initialize(context: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        appContext = context.applicationContext
        val legacyPreferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences = runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "${PREFERENCES_NAME}_encrypted",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { legacyPreferences }
        val legacySession = legacyPreferences.getString(SESSION_KEY, null)
        if (preferences !== legacyPreferences &&
            preferences.getString(SESSION_KEY, null) == null &&
            legacySession != null
        ) {
            preferences.edit().putString(SESSION_KEY, legacySession).apply()
            legacyPreferences.edit().remove(SESSION_KEY).apply()
        }
        val session = decodeSession(preferences.getString(SESSION_KEY, legacySession))
        _state.value = BreadcrumbRuntimeState(session = session, currentLocation = session?.points?.lastOrNull())
        initialized = true
    }

    fun startNew() = update {
        val now = System.currentTimeMillis()
        BreadcrumbRuntimeState(
            session = BreadcrumbTrailSession(
                id = UUID.randomUUID().toString(),
                startedAtMillis = now,
                points = emptyList(),
                safePointIndex = 0,
                mode = BreadcrumbTrailMode.RECORDING,
                returnTarget = BreadcrumbReturnTarget.START,
                returnCursor = null,
            )
        )
    }

    fun resumeRecording() = update { runtime ->
        val session = runtime.session ?: return@update runtime
        runtime.copy(session = session.copy(mode = BreadcrumbTrailMode.RECORDING, returnCursor = null))
    }

    fun pause() = update { runtime ->
        val session = runtime.session ?: return@update runtime
        runtime.copy(session = session.copy(mode = BreadcrumbTrailMode.PAUSED, returnCursor = null))
    }

    fun clear() = update { BreadcrumbRuntimeState() }

    fun markCurrentAsSafe() = update { runtime ->
        val session = runtime.session ?: return@update runtime
        if (session.points.isEmpty()) return@update runtime
        runtime.copy(session = session.copy(safePointIndex = session.points.lastIndex))
    }

    fun startReturn(target: BreadcrumbReturnTarget) = update { runtime ->
        val session = runtime.session ?: return@update runtime
        if (session.points.size < 2) return@update runtime
        val destination = when (target) {
            BreadcrumbReturnTarget.START -> 0
            BreadcrumbReturnTarget.LAST_SAFE -> session.safePointIndex.coerceIn(0, session.points.lastIndex)
        }
        val cursor = BreadcrumbTrailMath.initialReturnCursor(session.points, destination)
            ?: return@update runtime
        runtime.copy(
            session = session.copy(
                mode = BreadcrumbTrailMode.RETURNING,
                returnTarget = target,
                returnCursor = cursor,
            )
        )
    }

    fun acceptLocation(location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) return
        val point = BreadcrumbPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
            accuracyMeters = location.accuracy,
            timestampMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        update { runtime ->
            val session = runtime.session ?: return@update runtime
            when (session.mode) {
                BreadcrumbTrailMode.RECORDING -> acceptRecordingPoint(runtime, session, point)
                BreadcrumbTrailMode.RETURNING -> advanceReturn(runtime, session, point)
                else -> runtime.copy(currentLocation = point)
            }
        }
    }

    private fun acceptRecordingPoint(
        runtime: BreadcrumbRuntimeState,
        session: BreadcrumbTrailSession,
        point: BreadcrumbPoint,
    ): BreadcrumbRuntimeState {
        val last = session.points.lastOrNull()
        if (last != null) {
            val elapsed = point.timestampMillis - last.timestampMillis
            val distance = BreadcrumbTrailMath.distanceMeters(last, point)
            if (elapsed < MIN_POINT_INTERVAL_MILLIS ||
                (distance < MIN_POINT_DISTANCE_METERS && elapsed < STATIONARY_HEARTBEAT_MILLIS)
            ) {
                return runtime.copy(currentLocation = point)
            }
        }

        var points = session.points + point
        var safeIndex = session.safePointIndex
        if (points.size > MAX_POINTS) {
            val compacted = buildList {
                points.forEachIndexed { index, item ->
                    if (index == 0 || index == points.lastIndex || index % 2 == 0) add(item)
                }
            }
            val safePoint = points.getOrNull(safeIndex)
            points = compacted
            safeIndex = safePoint?.let { safe ->
                points.indices.minByOrNull { BreadcrumbTrailMath.distanceMeters(points[it], safe) }
            } ?: 0
        }
        return runtime.copy(
            session = session.copy(points = points, safePointIndex = safeIndex.coerceIn(0, points.lastIndex)),
            currentLocation = point,
        )
    }

    private fun advanceReturn(
        runtime: BreadcrumbRuntimeState,
        session: BreadcrumbTrailSession,
        current: BreadcrumbPoint,
    ): BreadcrumbRuntimeState {
        val destination = when (session.returnTarget) {
            BreadcrumbReturnTarget.START -> 0
            BreadcrumbReturnTarget.LAST_SAFE -> session.safePointIndex.coerceIn(0, session.points.lastIndex)
        }
        val cursor = session.returnCursor ?: return runtime.copy(currentLocation = current)
        val advanced = BreadcrumbTrailMath.advanceReturnCursor(session.points, current, cursor, destination)
        val arrived = advanced == destination &&
            BreadcrumbTrailMath.distanceMeters(current, session.points[destination]) <=
            max(BreadcrumbTrailMath.ARRIVAL_RADIUS_METERS, current.accuracyMeters.toDouble())
        return runtime.copy(
            session = session.copy(
                mode = if (arrived) BreadcrumbTrailMode.ARRIVED else BreadcrumbTrailMode.RETURNING,
                returnCursor = advanced,
            ),
            currentLocation = current,
        )
    }

    private fun update(transform: (BreadcrumbRuntimeState) -> BreadcrumbRuntimeState) = synchronized(lock) {
        check(initialized) { "BreadcrumbTrailRepository must be initialized first" }
        val updated = transform(_state.value)
        _state.value = updated
        persist(updated.session)
    }

    private fun persist(session: BreadcrumbTrailSession?) {
        preferences.edit().apply {
            if (session == null) remove(SESSION_KEY) else putString(SESSION_KEY, encodeSession(session))
        }.apply()
    }

    private fun encodeSession(session: BreadcrumbTrailSession): String = JSONObject().apply {
        put("id", session.id)
        put("startedAt", session.startedAtMillis)
        put("safeIndex", session.safePointIndex)
        put("mode", session.mode.name)
        put("target", session.returnTarget.name)
        put("cursor", session.returnCursor ?: JSONObject.NULL)
        put("points", JSONArray().apply {
            session.points.forEach { point ->
                put(JSONObject().apply {
                    put("lat", point.latitude)
                    put("lng", point.longitude)
                    put("alt", point.altitudeMeters ?: JSONObject.NULL)
                    put("accuracy", point.accuracyMeters.toDouble())
                    put("time", point.timestampMillis)
                })
            }
        })
    }.toString()

    private fun decodeSession(raw: String?): BreadcrumbTrailSession? = runCatching {
        if (raw.isNullOrBlank()) return@runCatching null
        val json = JSONObject(raw)
        val pointsJson = json.getJSONArray("points")
        val points = buildList {
            for (index in 0 until pointsJson.length()) {
                val point = pointsJson.getJSONObject(index)
                val lat = point.getDouble("lat")
                val lng = point.getDouble("lng")
                if (!lat.isFinite() || !lng.isFinite()) continue
                add(
                    BreadcrumbPoint(
                        latitude = lat,
                        longitude = lng,
                        altitudeMeters = point.optDouble("alt", Double.NaN).takeIf(Double::isFinite),
                        accuracyMeters = point.optDouble("accuracy", 50.0).toFloat(),
                        timestampMillis = point.optLong("time", 0L),
                    )
                )
            }
        }
        val safeIndex = if (points.isEmpty()) 0 else json.optInt("safeIndex", 0).coerceIn(0, points.lastIndex)
        BreadcrumbTrailSession(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            startedAtMillis = json.optLong("startedAt", System.currentTimeMillis()),
            points = points,
            safePointIndex = safeIndex,
            mode = BreadcrumbTrailMode.entries.firstOrNull { it.name == json.optString("mode") }
                ?: BreadcrumbTrailMode.PAUSED,
            returnTarget = BreadcrumbReturnTarget.entries.firstOrNull { it.name == json.optString("target") }
                ?: BreadcrumbReturnTarget.START,
            returnCursor = json.optInt("cursor", -1).takeIf { it >= 0 && points.isNotEmpty() }
                ?.coerceIn(0, points.lastIndex),
        )
    }.getOrNull()
}
