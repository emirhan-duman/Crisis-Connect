package com.auralis.crisisconnect.service

import android.content.Context
import com.auralis.crisisconnect.R
import org.json.JSONObject
import java.util.Locale

object BlePeerIdentityUtils {
    const val ROLE_RESCUE = "rescue"
    const val ROLE_VICTIM = "victim"

    // Legacy Turkish labels are kept for compatibility with existing saved names/tests.
    const val RESCUER_LABEL = "Saha Ekibi"
    const val VICTIM_LABEL = "Kazazede"
    private const val RESCUER_LABEL_EN = "Field Team"
    private const val VICTIM_LABEL_EN = "Victim"

    private const val PEER_INFO_KIND = "peer_info"
    private const val MAX_AVATAR_B64_LENGTH = 8_192
    private val RESCUER_LABELS = setOf(RESCUER_LABEL, RESCUER_LABEL_EN)
    private val VICTIM_LABELS = setOf(VICTIM_LABEL, VICTIM_LABEL_EN)
    private val ALL_ROLE_LABELS = (RESCUER_LABELS + VICTIM_LABELS)
    private val ROLE_LABEL_GROUP = ALL_ROLE_LABELS.joinToString("|") { labelPattern(it) }
    private val RESCUER_LABEL_GROUP = RESCUER_LABELS.joinToString("|") { labelPattern(it) }

    private val RESCUE_ROLES = setOf(
        "admin",
        "fieldteam"
    )

    private val MAC_ADDRESS_REGEX = Regex("(?i)^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val IDENTIFIER_SUFFIX_REGEX = Regex("(?i)\\b[0-9a-z]{1,6}:[0-9a-z]{1,6}\\b")
    private val ROLE_SUFFIX_PATTERN = Regex("(?i)\\s*[\\(\\[]?\\s*$ROLE_LABEL_GROUP\\s*[\\)\\]]?\\s*$")
    private val ROLE_LABEL_PATTERN = Regex("(?i)\\b$ROLE_LABEL_GROUP\\b")
    private val ROLE_TRAILING_DUP_PATTERN = Regex("(?i)\\s*[-–—:]\\s*$ROLE_LABEL_GROUP\\s*$")
    private val RESCUER_LABEL_PATTERN = Regex("(?i)\\b$RESCUER_LABEL_GROUP\\b")
    private val EMPTY_TRAILING_BRACKET_GROUP_PATTERN =
        Regex("""(?:\s*\(\s*\)|\s*\[\s*\])+\s*$""")

    fun roleValue(savedRole: String?): String =
        if (isRescueRole(savedRole)) ROLE_RESCUE else ROLE_VICTIM

    fun normalizePeerRoleValue(roleValue: String?): String {
        return if (roleValue?.lowercase(Locale.US)?.trim() == ROLE_RESCUE) {
            ROLE_RESCUE
        } else {
            ROLE_VICTIM
        }
    }

    fun displayRoleValue(
        claimedRoleValue: String?,
        trustClaimedRole: Boolean
    ): String {
        val normalized = normalizePeerRoleValue(claimedRoleValue)
        return if (trustClaimedRole) normalized else ROLE_VICTIM
    }

    fun roleLabel(roleValue: String?): String = roleLabel(roleValue, Locale.getDefault())

    fun roleLabel(roleValue: String?, context: Context): String {
        val localizedContext = context.applicationContext ?: context
        return when (normalizePeerRoleValue(roleValue)) {
            ROLE_RESCUE -> localizedContext.getString(R.string.ble_peer_role_rescuer_label)
            else -> localizedContext.getString(R.string.ble_peer_role_victim_label)
        }
    }

    fun roleLabel(roleValue: String?, locale: Locale): String = when (roleValue?.lowercase(Locale.US)) {
        ROLE_RESCUE -> localizedRescuerLabel(locale)
        ROLE_VICTIM -> localizedVictimLabel(locale)
        else -> localizedVictimLabel(locale)
    }

    fun isRescueRole(role: String?): Boolean =
        role?.lowercase(Locale.US)?.trim() in RESCUE_ROLES

    fun sanitizeIncomingName(name: String, sessionCode: String?): String {
        val trimmed = cleanupTrailingNameArtifacts(name)
        return if (trimmed.isEmpty() || looksLikeBleIdentifier(trimmed, sessionCode)) {
            ""
        } else {
            trimmed
        }
    }

    fun looksLikeBleIdentifier(name: String, sessionCode: String?): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return true
        }
        if (sessionCode != null && trimmed.equals(sessionCode, ignoreCase = true)) {
            return true
        }
        if (trimmed.startsWith("ble:", ignoreCase = true)) {
            return true
        }
        if (MAC_ADDRESS_REGEX.matches(trimmed)) {
            return true
        }
        if (IDENTIFIER_SUFFIX_REGEX.containsMatchIn(trimmed)) {
            return true
        }
        return ROLE_SUFFIX_PATTERN.matches(trimmed)
    }

    fun parsePeerInfoPayload(raw: String): PeerIdentity? {
        val trimmed = raw.trim()
        return runCatching {
            val json = JSONObject(trimmed)
            if (json.optString("kind") != PEER_INFO_KIND) {
                return null
            }
            val role = normalizePeerRoleValue(json.optString("role", ROLE_VICTIM))
            val name = json.optString("name", "").trim()
            val battery = json.optInt("batteryPct", Int.MIN_VALUE).let {
                if (it in 0..100) it else null
            }
            val avatar = json.optString("avatarB64", "").trim()
                .takeIf { it.isNotEmpty() && it.length <= MAX_AVATAR_B64_LENGTH }
            val location = parsePeerLocation(json.optJSONObject("location"))
                ?: parseSignalLocation(json.optJSONObject("signalLocation"))
            PeerIdentity(
                name = name,
                role = role,
                batteryPercent = battery,
                avatarBase64 = avatar,
                location = location
            )
        }.getOrNull()
    }

    fun buildPeerInfoPayload(
        name: String,
        role: String,
        batteryPercent: Int? = null,
        avatarBase64: String? = null,
        location: PeerLocationSnapshot? = null
    ): String {
        return JSONObject().apply {
            put("kind", PEER_INFO_KIND)
            put("name", name)
            put("role", role)
            if (batteryPercent != null && batteryPercent in 0..100) {
                put("batteryPct", batteryPercent)
            }
            val avatar = avatarBase64?.trim().takeIf { !it.isNullOrBlank() }
            if (avatar != null) {
                put("avatarB64", avatar)
            }
            normalizePeerLocation(location)?.let { snapshot ->
                val locationJson = snapshot.toJson()
                put("location", locationJson)
                put(
                    "signalLocation",
                    JSONObject().apply {
                        put("gps", locationJson)
                    }
                )
            }
        }.toString()
    }

    fun buildLabeledName(
        rawName: String,
        roleValue: String,
        sessionCode: String? = null
    ): String = buildLabeledName(rawName, roleValue, sessionCode, Locale.getDefault())

    fun buildLabeledName(
        rawName: String,
        roleValue: String,
        sessionCode: String?,
        context: Context
    ): String {
        val safeRoleValue = normalizePeerRoleValue(roleValue)
        return buildLabeledNameInternal(
            rawName = rawName,
            safeRoleLabel = roleLabel(safeRoleValue, context),
            sessionCode = sessionCode
        )
    }

    fun buildLabeledName(
        rawName: String,
        roleValue: String,
        sessionCode: String?,
        locale: Locale
    ): String {
        val safeRoleValue = normalizePeerRoleValue(roleValue)
        return buildLabeledNameInternal(
            rawName = rawName,
            safeRoleLabel = roleLabel(safeRoleValue, locale),
            sessionCode = sessionCode
        )
    }

    fun buildUnverifiedPeerDisplayName(
        rawName: String,
        sessionCode: String?,
        locale: Locale = Locale.getDefault()
    ): String {
        return buildUnverifiedPeerDisplayNameInternal(
            rawName = rawName,
            sessionCode = sessionCode,
            fallbackLabel = localizedVictimLabel(locale)
        )
    }

    fun buildUnverifiedPeerDisplayName(
        rawName: String,
        sessionCode: String?,
        context: Context
    ): String {
        return buildUnverifiedPeerDisplayNameInternal(
            rawName = rawName,
            sessionCode = sessionCode,
            fallbackLabel = roleLabel(ROLE_VICTIM, context)
        )
    }

    private fun sanitizeUnverifiedPeerName(rawName: String, sessionCode: String?): String {
        val baseName = sanitizeIncomingName(rawName, sessionCode)
        if (baseName.isBlank()) {
            return ""
        }
        var candidate = baseName
            .replace(ROLE_SUFFIX_PATTERN, "")
            .replace(ROLE_TRAILING_DUP_PATTERN, "")
            .trim()
        candidate = cleanupTrailingNameArtifacts(candidate)
        if (candidate.isBlank()) {
            return ""
        }
        if (ROLE_LABEL_PATTERN.matches(candidate)) {
            return ""
        }
        return candidate
    }

    private fun sanitizeLabeledNameCandidate(rawName: String, roleLabel: String): String {
        val trimmedName = cleanupTrailingNameArtifacts(rawName)
        if (trimmedName.isBlank()) {
            return roleLabel
        }
        var candidate = trimmedName
            .replace(ROLE_SUFFIX_PATTERN, "")
            .replace(ROLE_TRAILING_DUP_PATTERN, "")
            .trim()
        candidate = cleanupTrailingNameArtifacts(candidate)
        if (candidate.isBlank()) {
            return roleLabel
        }
        return candidate
    }

    fun isRescuerDisplayName(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return false
        }
        if (RESCUER_LABEL_PATTERN.containsMatchIn(trimmed)) {
            return true
        }
        return trimmed.lowercase(Locale.US).contains(ROLE_RESCUE)
    }

    fun defaultRoleName(role: String?): String = defaultRoleName(role, Locale.getDefault())

    fun defaultRoleName(role: String?, context: Context): String {
        val safeRoleValue = normalizePeerRoleValue(role)
        return roleLabel(safeRoleValue, context)
    }

    fun defaultRoleName(role: String?, locale: Locale): String {
        val safeRoleValue = normalizePeerRoleValue(role)
        return roleLabel(safeRoleValue, locale)
    }

    fun resolveStableBleContactName(
        storedName: String?,
        peerName: String?,
        sessionCode: String?,
        addressForFallback: String?
    ): String? {
        val normalizedSession = sessionCode?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedAddress = addressForFallback?.trim()?.takeIf { it.isNotEmpty() }
        val identifier = normalizedSession ?: normalizedAddress
        return sequenceOf(storedName, peerName)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .map { candidate -> normalizeResolvedBleContactName(candidate, identifier) }
            .firstOrNull { candidate ->
                candidate.isNotBlank() &&
                !candidate.equals(normalizedSession, ignoreCase = true) &&
                    !candidate.equals(normalizedAddress, ignoreCase = true) &&
                    !looksLikeBleIdentifier(candidate, identifier)
            }
    }

    fun buildDisplayNameForContact(
        preferredName: String?,
        roleValue: String?,
        addressForFallback: String?
    ): String = buildDisplayNameForContact(preferredName, roleValue, addressForFallback, Locale.getDefault())

    fun counterpartyRoleValue(isCurrentUserRescue: Boolean): String {
        return if (isCurrentUserRescue) ROLE_VICTIM else ROLE_RESCUE
    }

    fun buildBleCounterpartyDisplayName(
        preferredName: String?,
        addressForFallback: String?,
        isCurrentUserRescue: Boolean
    ): String = buildBleCounterpartyDisplayName(
        preferredName = preferredName,
        addressForFallback = addressForFallback,
        isCurrentUserRescue = isCurrentUserRescue,
        locale = Locale.getDefault()
    )

    fun buildBleCounterpartyDisplayName(
        preferredName: String?,
        addressForFallback: String?,
        isCurrentUserRescue: Boolean,
        context: Context
    ): String {
        return buildDisplayNameForContact(
            preferredName = preferredName,
            roleValue = counterpartyRoleValue(isCurrentUserRescue),
            addressForFallback = addressForFallback,
            context = context
        )
    }

    fun buildBleCounterpartyDisplayName(
        preferredName: String?,
        addressForFallback: String?,
        isCurrentUserRescue: Boolean,
        locale: Locale
    ): String {
        return buildDisplayNameForContact(
            preferredName = preferredName,
            roleValue = counterpartyRoleValue(isCurrentUserRescue),
            addressForFallback = addressForFallback,
            locale = locale
        )
    }

    fun buildDisplayNameForContact(
        preferredName: String?,
        roleValue: String?,
        addressForFallback: String?,
        context: Context
    ): String {
        val safeRoleValue = normalizePeerRoleValue(roleValue)
        val safeRoleLabel = roleLabel(safeRoleValue, context)
        val preferred = sanitizeIncomingName(preferredName.orEmpty(), addressForFallback)
        if (preferred.isNotBlank()) {
            return buildLabeledNameInternal(preferred, safeRoleLabel, addressForFallback)
        }
        return safeRoleLabel
    }

    fun buildDisplayNameForContact(
        preferredName: String?,
        roleValue: String?,
        addressForFallback: String?,
        locale: Locale
    ): String {
        val safeRoleValue = normalizePeerRoleValue(roleValue)
        val safeRoleLabel = roleLabel(safeRoleValue, locale)
        val preferred = sanitizeIncomingName(preferredName.orEmpty(), addressForFallback)
        if (preferred.isNotBlank()) {
            return buildLabeledName(preferred, safeRoleValue, addressForFallback, locale)
        }
        return safeRoleLabel
    }

    private fun buildLabeledNameInternal(
        rawName: String,
        safeRoleLabel: String,
        sessionCode: String?
    ): String {
        val baseFromName = sanitizeIncomingName(rawName, sessionCode)
        val deduplicatedName = sanitizeLabeledNameCandidate(baseFromName, safeRoleLabel)
        val baseName = sanitizeIncomingName(deduplicatedName, sessionCode).ifBlank { safeRoleLabel }
        if (baseName.equals(safeRoleLabel, ignoreCase = true)) {
            return baseName
        }
        if (ROLE_LABEL_PATTERN.containsMatchIn(baseName)) {
            return baseName
        }
        return "$baseName ($safeRoleLabel)"
    }

    private fun buildUnverifiedPeerDisplayNameInternal(
        rawName: String,
        sessionCode: String?,
        fallbackLabel: String
    ): String {
        return sanitizeUnverifiedPeerName(rawName, sessionCode)
            .ifBlank { fallbackLabel }
    }

    private fun labelPattern(label: String): String = label
        .trim()
        .split(Regex("\\s+"))
        .joinToString("\\s+") { Regex.escape(it) }

    private fun cleanupTrailingNameArtifacts(rawName: String): String {
        var candidate = rawName.trim()
        if (candidate.isEmpty()) {
            return ""
        }
        while (true) {
            val previous = candidate
            candidate = candidate
                .replace(EMPTY_TRAILING_BRACKET_GROUP_PATTERN, "")
                .trimEnd()
            candidate = trimUnmatchedTrailingClosers(candidate, '(', ')')
            candidate = trimUnmatchedTrailingClosers(candidate, '[', ']')
            candidate = trimUnmatchedTrailingOpeners(candidate, '(', ')')
            candidate = trimUnmatchedTrailingOpeners(candidate, '[', ']')
            candidate = candidate.trim()
            if (candidate == previous) {
                return candidate
            }
        }
    }

    private fun trimUnmatchedTrailingClosers(input: String, open: Char, close: Char): String {
        var candidate = input.trimEnd()
        while (
            candidate.endsWith(close) &&
                candidate.count { it == close } > candidate.count { it == open }
        ) {
            candidate = candidate.dropLast(1).trimEnd()
        }
        return candidate
    }

    private fun trimUnmatchedTrailingOpeners(input: String, open: Char, close: Char): String {
        var candidate = input.trimEnd()
        while (
            candidate.endsWith(open) &&
                candidate.count { it == open } > candidate.count { it == close }
        ) {
            candidate = candidate.dropLast(1).trimEnd()
        }
        return candidate
    }

    private fun normalizeResolvedBleContactName(
        candidate: String,
        identifier: String?
    ): String {
        val trimmed = cleanupTrailingNameArtifacts(candidate)
        if (trimmed.isBlank()) {
            return ""
        }
        val roleValue = when {
            RESCUER_LABEL_PATTERN.containsMatchIn(trimmed) -> ROLE_RESCUE
            ROLE_LABEL_PATTERN.containsMatchIn(trimmed) -> ROLE_VICTIM
            else -> return trimmed
        }
        val labelLocale = localeForEmbeddedRoleLabel(trimmed) ?: Locale.getDefault()
        return buildLabeledName(
            rawName = trimmed,
            roleValue = roleValue,
            sessionCode = identifier,
            locale = labelLocale
        )
    }

    private fun localeForEmbeddedRoleLabel(input: String): Locale? {
        return when {
            input.contains(RESCUER_LABEL, ignoreCase = true) ||
                input.contains(VICTIM_LABEL, ignoreCase = true) -> Locale("tr", "TR")

            input.contains(RESCUER_LABEL_EN, ignoreCase = true) ||
                input.contains(VICTIM_LABEL_EN, ignoreCase = true) -> Locale.US

            else -> null
        }
    }

    private fun localizedRescuerLabel(locale: Locale): String {
        return if (locale.language.equals("tr", ignoreCase = true)) {
            RESCUER_LABEL
        } else {
            RESCUER_LABEL_EN
        }
    }

    private fun localizedVictimLabel(locale: Locale): String {
        return if (locale.language.equals("tr", ignoreCase = true)) {
            VICTIM_LABEL
        } else {
            VICTIM_LABEL_EN
        }
    }

    private fun parsePeerLocation(json: JSONObject?): PeerLocationSnapshot? {
        json ?: return null
        val latitude = json.optDouble("lat", Double.NaN)
        val longitude = json.optDouble("lon", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            return null
        }
        return normalizePeerLocation(
            PeerLocationSnapshot(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = json.optDouble("accuracyMeters", Double.NaN)
                    .takeIf { it.isFinite() && it > 0.0 }
                    ?.toFloat(),
                capturedAtMillis = json.optLong("capturedAtMillis", System.currentTimeMillis()),
                source = json.optString("source", "unknown")
            )
        )
    }

    private fun parseSignalLocation(json: JSONObject?): PeerLocationSnapshot? {
        json ?: return null
        return parsePeerLocation(json.optJSONObject("gps"))
    }

    private fun PeerLocationSnapshot.toJson(): JSONObject {
        return JSONObject().apply {
            put("lat", latitude)
            put("lon", longitude)
            put("capturedAtMillis", capturedAtMillis)
            put("source", source)
            accuracyMeters?.let { accuracy ->
                put("accuracyMeters", accuracy.toDouble())
            }
        }
    }

    private fun normalizePeerLocation(location: PeerLocationSnapshot?): PeerLocationSnapshot? {
        location ?: return null
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 ||
            location.longitude !in -180.0..180.0
        ) {
            return null
        }
        return location.copy(
            accuracyMeters = location.accuracyMeters?.takeIf { it.isFinite() && it > 0f },
            capturedAtMillis = location.capturedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            source = location.source.trim().ifEmpty { "unknown" }
        )
    }

    private fun normalizeRelativeVictimEstimate(
        estimate: RelativeVictimEstimate?
    ): RelativeVictimEstimate? {
        estimate ?: return null
        if (!estimate.distanceMeters.isFinite() || estimate.distanceMeters <= 0.0) {
            return null
        }
        if (!estimate.originLatitude.isFinite() || !estimate.originLongitude.isFinite() ||
            estimate.originLatitude !in -90.0..90.0 ||
            estimate.originLongitude !in -180.0..180.0
        ) {
            return null
        }
        val normalizedBearing = ((estimate.bearingDegrees % 360.0) + 360.0) % 360.0
        return estimate.copy(
            bearingDegrees = normalizedBearing,
            confidence = estimate.confidence?.coerceIn(0f, 1f),
            originAccuracyMeters = estimate.originAccuracyMeters?.takeIf { it.isFinite() && it > 0f },
            headingSource = estimate.headingSource.trim().ifEmpty { "unknown" },
            sampledAtMillis = estimate.sampledAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            originCapturedAtMillis = estimate.originCapturedAtMillis.takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )
    }

    private fun normalizeRegistryAddress(address: String): String? {
        return address.trim().uppercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    private fun normalizeRegistrySignalId(signalId: String): String? {
        val trimmed = signalId.trim().lowercase(Locale.US)
        return trimmed.takeIf { it.startsWith("cc-") && it.length > 3 }
    }

    object SignalLocationRegistry {
        private val lock = Any()
        private val signalIdByAddress = mutableMapOf<String, String>()
        private val metadataBySignalId = mutableMapOf<String, SignalLocationMetadata>()
        private val pendingMetadataByAddress = mutableMapOf<String, SignalLocationMetadata>()

        fun bindSignalId(address: String, signalId: String) {
            val normalizedAddress = normalizeRegistryAddress(address) ?: return
            val normalizedSignalId = normalizeRegistrySignalId(signalId) ?: return
            synchronized(lock) {
                signalIdByAddress[normalizedAddress] = normalizedSignalId
                val pending = pendingMetadataByAddress.remove(normalizedAddress) ?: return
                val previous = metadataBySignalId[normalizedSignalId]
                val merged = mergeSignalMetadata(previous, pending)
                val updated = finalizeMetadata(previous, merged)
                if (updated == null) {
                    metadataBySignalId.remove(normalizedSignalId)
                } else {
                    metadataBySignalId[normalizedSignalId] = updated
                }
            }
        }

        fun updateVictimLocation(address: String, location: PeerLocationSnapshot?) {
            val normalizedAddress = normalizeRegistryAddress(address) ?: return
            val normalizedLocation = normalizePeerLocation(location)
            updateForAddress(normalizedAddress) { current ->
                val base = current ?: SignalLocationMetadata()
                val next = base.copy(
                    victimLocation = normalizedLocation,
                    estimatedVictimLocation = if (normalizedLocation != null) null else base.estimatedVictimLocation,
                    relativeEstimate = if (normalizedLocation != null) null else base.relativeEstimate
                )
                next.takeIf { it.hasUsefulData() }
            }
        }

        fun updateRelativeEstimate(
            address: String,
            estimatedVictimLocation: PeerLocationSnapshot?,
            relativeEstimate: RelativeVictimEstimate?
        ) {
            val normalizedAddress = normalizeRegistryAddress(address) ?: return
            val normalizedEstimatedLocation = normalizePeerLocation(estimatedVictimLocation)
            val normalizedRelativeEstimate = normalizeRelativeVictimEstimate(relativeEstimate)
            updateForAddress(normalizedAddress) { current ->
                if (current?.victimLocation != null) {
                    return@updateForAddress current
                }
                val base = current ?: SignalLocationMetadata()
                val next = base.copy(
                    estimatedVictimLocation = normalizedEstimatedLocation,
                    relativeEstimate = normalizedRelativeEstimate
                )
                next.takeIf { it.hasUsefulData() }
            }
        }

        fun snapshotForSignalId(signalId: String): SignalLocationMetadata? {
            val normalizedSignalId = normalizeRegistrySignalId(signalId) ?: return null
            return synchronized(lock) {
                metadataBySignalId[normalizedSignalId]
            }
        }

        private fun updateForAddress(
            normalizedAddress: String,
            transform: (SignalLocationMetadata?) -> SignalLocationMetadata?
        ) {
            synchronized(lock) {
                val boundSignalId = signalIdByAddress[normalizedAddress]
                if (boundSignalId != null) {
                    val previous = metadataBySignalId[boundSignalId]
                    val updated = finalizeMetadata(previous, transform(previous))
                    if (updated == null) {
                        metadataBySignalId.remove(boundSignalId)
                    } else {
                        metadataBySignalId[boundSignalId] = updated
                    }
                } else {
                    val previous = pendingMetadataByAddress[normalizedAddress]
                    val updated = finalizeMetadata(previous, transform(previous))
                    if (updated == null) {
                        pendingMetadataByAddress.remove(normalizedAddress)
                    } else {
                        pendingMetadataByAddress[normalizedAddress] = updated
                    }
                }
            }
        }

        private fun finalizeMetadata(
            previous: SignalLocationMetadata?,
            candidate: SignalLocationMetadata?
        ): SignalLocationMetadata? {
            val normalizedCandidate = candidate?.takeIf { it.hasUsefulData() } ?: return null
            val normalizedPrevious = previous?.normalizedForComparison()
            val comparisonCandidate = normalizedCandidate.normalizedForComparison()
            return if (normalizedPrevious == comparisonCandidate) {
                previous
            } else {
                comparisonCandidate.copy(updatedAtMillis = System.currentTimeMillis())
            }
        }

        private fun mergeSignalMetadata(
            existing: SignalLocationMetadata?,
            incoming: SignalLocationMetadata?
        ): SignalLocationMetadata? {
            val normalizedIncoming = incoming?.normalizedForComparison() ?: return existing
            val existingVictimLocation = existing?.victimLocation
            val victimLocation = normalizedIncoming.victimLocation ?: existingVictimLocation
            val estimatedVictimLocation = if (victimLocation != null) {
                null
            } else {
                normalizedIncoming.estimatedVictimLocation ?: existing?.estimatedVictimLocation
            }
            val relativeEstimate = if (victimLocation != null) {
                null
            } else {
                normalizedIncoming.relativeEstimate ?: existing?.relativeEstimate
            }
            return SignalLocationMetadata(
                victimLocation = victimLocation,
                estimatedVictimLocation = estimatedVictimLocation,
                relativeEstimate = relativeEstimate,
                updatedAtMillis = maxOf(existing?.updatedAtMillis ?: 0L, normalizedIncoming.updatedAtMillis)
            ).takeIf { it.hasUsefulData() }
        }
    }

    data class PeerIdentity(
        val name: String,
        val role: String,
        val batteryPercent: Int? = null,
        val avatarBase64: String? = null,
        val location: PeerLocationSnapshot? = null
    )

    data class PeerLocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float? = null,
        val capturedAtMillis: Long,
        val source: String
    )

    data class RelativeVictimEstimate(
        val bearingDegrees: Double,
        val distanceMeters: Double,
        val confidence: Float? = null,
        val originLatitude: Double,
        val originLongitude: Double,
        val originAccuracyMeters: Float? = null,
        val originCapturedAtMillis: Long,
        val headingSource: String,
        val rssi: Int? = null,
        val sampledAtMillis: Long
    )

    data class SignalLocationMetadata(
        val victimLocation: PeerLocationSnapshot? = null,
        val estimatedVictimLocation: PeerLocationSnapshot? = null,
        val relativeEstimate: RelativeVictimEstimate? = null,
        val updatedAtMillis: Long = 0L
    ) {
        fun hasUsefulData(): Boolean {
            return victimLocation != null || estimatedVictimLocation != null || relativeEstimate != null
        }

        fun normalizedForComparison(): SignalLocationMetadata {
            return copy(updatedAtMillis = 0L)
        }
    }
}
