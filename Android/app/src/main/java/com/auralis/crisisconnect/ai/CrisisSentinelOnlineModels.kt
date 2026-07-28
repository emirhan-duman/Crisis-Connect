package com.auralis.crisisconnect.ai

/** A point the model asked to show on the map (showLocationOnMap functionCall args). */
data class CrisisSentinelMapPoint(
    val lat: Double,
    val lng: Double,
    val label: String,
    val details: String? = null,
    val type: String? = null
)

/**
 * Everything a single online chat turn produced. [text] is the streamed answer; the rest are
 * the structured extras carried on the final `done` event.
 */
data class CrisisSentinelOnlineStreamResult(
    val text: String,
    val modelName: String? = null,
    /** The `_card` payload verbatim (JSON object string), or null when the reply has no card. */
    val cardJson: String? = null,
    val mapPoints: List<CrisisSentinelMapPoint> = emptyList(),
    /** functionCall names the mobile client cannot render (SOS/team/sitrep widgets etc.). */
    val unsupportedTools: List<String> = emptyList(),
    val groundingMetadataJson: String? = null
)

data class CrisisSentinelOnlineModelInfo(
    val id: String,
    val label: String,
    val contextWindow: Long? = null
)

data class CrisisSentinelOnlineProvider(
    val id: String,
    val label: String,
    val defaultModel: String?,
    val source: String,
    val models: List<CrisisSentinelOnlineModelInfo>
)

/** The user's explicit model pick; null everywhere means "server default". */
data class CrisisSentinelOnlineModelChoice(
    val providerId: String,
    val modelId: String,
    val modelLabel: String
)
