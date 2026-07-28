package com.auralis.crisisconnect.screens.Tools

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ai.CrisisSentinelCard
import com.auralis.crisisconnect.ai.CrisisSentinelMapPoint
import com.auralis.crisisconnect.ai.parseCrisisSentinelCard
import java.util.Locale

/**
 * Renders the online engine's `_card` payload beneath an assistant reply. Unknown/manager-only
 * kinds render nothing (web parity). [onShowOnMap] opens the app's map with the given points.
 */
@Composable
fun CrisisSentinelCardView(
    cardJson: String,
    onShowOnMap: (List<CrisisSentinelMapPoint>) -> Unit
) {
    val card = remember(cardJson) { parseCrisisSentinelCard(cardJson) } ?: return
    when (card) {
        is CrisisSentinelCard.Weather -> WeatherCard(card)
        is CrisisSentinelCard.Quake -> QuakeCard(card, onShowOnMap)
        is CrisisSentinelCard.AirQuality -> AirQualityCard(card)
        is CrisisSentinelCard.HazardEvents -> HazardEventsCard(card, onShowOnMap)
        is CrisisSentinelCard.Flood -> FloodCard(card)
        is CrisisSentinelCard.Alerts -> AlertsCard(card)
        is CrisisSentinelCard.QuakeImpact -> QuakeImpactCard(card)
        is CrisisSentinelCard.Route -> RouteCard(card)
        is CrisisSentinelCard.Marine -> MarineCard(card)
        is CrisisSentinelCard.Satellite -> SatelliteCard(card)
        is CrisisSentinelCard.Facility -> FacilityCard(card, onShowOnMap)
        is CrisisSentinelCard.Damage -> DamageCard(card)
    }
}

@Composable
fun ShowOnMapButton(
    points: List<CrisisSentinelMapPoint>,
    onShowOnMap: (List<CrisisSentinelMapPoint>) -> Unit
) {
    if (points.isEmpty()) return
    FilledTonalButton(onClick = { onShowOnMap(points) }) {
        Icon(
            imageVector = Icons.Outlined.Map,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(R.string.crisis_sentinel_card_show_on_map))
    }
}

/** Muted note for tools the phone can't render (SOS/team widgets need the web dashboard). */
@Composable
fun UnsupportedToolNotice() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(R.string.crisis_sentinel_tool_unsupported),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------- shared pieces

@Composable
private fun CardShell(
    icon: ImageVector,
    title: String?,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricChipRow(chips: List<Pair<ImageVector, String>>) {
    if (chips.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { (icon, text) -> MetricChip(icon, text) }
    }
}

@Composable
private fun MetricChip(icon: ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CardListRow(
    primary: String,
    secondary: String? = null,
    leadingBadge: (@Composable () -> Unit)? = null,
    mapPoint: CrisisSentinelMapPoint? = null,
    onShowOnMap: ((List<CrisisSentinelMapPoint>) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        leadingBadge?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (!secondary.isNullOrBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (mapPoint != null && onShowOnMap != null) {
            IconButton(
                onClick = { onShowOnMap(listOf(mapPoint)) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Place,
                    contentDescription = stringResource(R.string.crisis_sentinel_card_show_on_map),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun fmt(value: Double?): String? {
    value ?: return null
    return if (value % 1.0 == 0.0) {
        String.format(Locale.getDefault(), "%.0f", value)
    } else {
        String.format(Locale.getDefault(), "%.1f", value)
    }
}

// ---------------------------------------------------------------------------- the 12 cards

@Composable
private fun WeatherCard(card: CrisisSentinelCard.Weather) {
    CardShell(icon = Icons.Filled.Thermostat, title = card.label) {
        fmt(card.tempC)?.let { temp ->
            Text(
                text = "$temp°C",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        MetricChipRow(
            listOfNotNull(
                fmt(card.feelsLikeC)?.let { Icons.Filled.Thermostat to "≈ $it°C" },
                fmt(card.humidity)?.let { Icons.Filled.WaterDrop to "$it%" },
                fmt(card.windKmh)?.let { Icons.Filled.Air to "$it km/h" },
                fmt(card.gustKmh)?.let { Icons.Filled.Air to "max $it km/h" },
                fmt(card.rainMm ?: card.precipMm)?.let { Icons.Filled.WaterDrop to "$it mm" }
            )
        )
    }
}

@Composable
private fun QuakeCard(
    card: CrisisSentinelCard.Quake,
    onShowOnMap: (List<CrisisSentinelMapPoint>) -> Unit
) {
    CardShell(icon = Icons.Filled.Public, title = card.label ?: card.region) {
        card.events.forEach { event ->
            val magnitude = fmt(event.magnitude)?.let { "M$it" }
            val depth = fmt(event.depth)?.let { "$it km" }
            CardListRow(
                primary = listOfNotNull(magnitude, event.place).joinToString(" · ")
                    .ifBlank { event.occurredAt.orEmpty() },
                secondary = listOfNotNull(depth, event.occurredAt).joinToString(" · ")
                    .takeIf { it.isNotBlank() },
                mapPoint = if (event.lat != null && event.lon != null) {
                    CrisisSentinelMapPoint(
                        lat = event.lat,
                        lng = event.lon,
                        label = listOfNotNull(magnitude, event.place).joinToString(" ")
                    )
                } else null,
                onShowOnMap = onShowOnMap
            )
        }
    }
}

@Composable
private fun AirQualityCard(card: CrisisSentinelCard.AirQuality) {
    CardShell(icon = Icons.Filled.Air, title = card.label, subtitle = card.level) {
        fmt(card.usAqi)?.let { aqi ->
            Text(
                text = "AQI $aqi",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        MetricChipRow(
            listOfNotNull(
                fmt(card.pm25)?.let { Icons.Filled.Air to "PM2.5 $it" },
                fmt(card.pm10)?.let { Icons.Filled.Air to "PM10 $it" },
                fmt(card.ozone)?.let { Icons.Filled.Air to "O₃ $it" },
                fmt(card.no2)?.let { Icons.Filled.Air to "NO₂ $it" },
                fmt(card.so2)?.let { Icons.Filled.Air to "SO₂ $it" },
                fmt(card.co)?.let { Icons.Filled.Air to "CO $it" }
            )
        )
    }
}

@Composable
private fun HazardEventsCard(
    card: CrisisSentinelCard.HazardEvents,
    onShowOnMap: (List<CrisisSentinelMapPoint>) -> Unit
) {
    CardShell(
        icon = Icons.Filled.Warning,
        title = card.label ?: card.category,
        subtitle = card.place
    ) {
        card.events.forEach { event ->
            CardListRow(
                primary = event.title ?: event.category.orEmpty(),
                secondary = event.date,
                mapPoint = if (event.lat != null && event.lon != null) {
                    CrisisSentinelMapPoint(
                        lat = event.lat,
                        lng = event.lon,
                        label = event.title.orEmpty()
                    )
                } else null,
                onShowOnMap = onShowOnMap
            )
        }
    }
}

@Composable
private fun FloodCard(card: CrisisSentinelCard.Flood) {
    CardShell(icon = Icons.Filled.Waves, title = card.label) {
        val unit = card.unit.orEmpty()
        MetricChipRow(
            listOfNotNull(
                fmt(card.todayDischarge)?.let { Icons.Filled.Waves to "$it $unit".trim() },
                fmt(card.peakDischarge)?.let { peak ->
                    val peakText = listOfNotNull("max $peak $unit".trim(), card.peakDate)
                        .joinToString(" · ")
                    Icons.Filled.Waves to peakText
                },
                card.rising?.let { rising ->
                    if (rising) Icons.Filled.TrendingUp to "↑" else Icons.Filled.TrendingDown to "↓"
                }
            )
        )
    }
}

@Composable
private fun AlertsCard(card: CrisisSentinelCard.Alerts) {
    CardShell(icon = Icons.Filled.NotificationsActive, title = card.title, subtitle = card.source) {
        card.items.forEach { item ->
            CardListRow(
                primary = item.label ?: item.detail.orEmpty(),
                secondary = listOfNotNull(
                    item.detail.takeIf { item.label != null },
                    item.whenText
                ).joinToString(" · ").takeIf { it.isNotBlank() },
                leadingBadge = item.severity?.let { severity -> { SeverityBadge(severity) } }
            )
        }
    }
}

@Composable
private fun SeverityBadge(severity: String) {
    val color = when (severity.lowercase(Locale.ROOT)) {
        "extreme", "severe", "critical", "red", "high" -> MaterialTheme.colorScheme.error
        "moderate", "orange", "medium", "warning" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            text = severity,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun QuakeImpactCard(card: CrisisSentinelCard.QuakeImpact) {
    CardShell(icon = Icons.Filled.Public, title = null) {
        card.events.forEach { event ->
            CardListRow(
                primary = listOfNotNull(fmt(event.mag)?.let { "M$it" }, event.place)
                    .joinToString(" · "),
                secondary = listOfNotNull(
                    event.alert?.let { "alert: $it" },
                    fmt(event.mmi)?.let { "MMI $it" },
                    event.felt?.let { "felt: $it" },
                    if (event.tsunami == true) "tsunami" else null,
                    event.whenText
                ).joinToString(" · ").takeIf { it.isNotBlank() }
            )
        }
    }
}

@Composable
private fun RouteCard(card: CrisisSentinelCard.Route) {
    var expanded by remember { mutableStateOf(false) }
    CardShell(
        icon = Icons.Filled.Directions,
        title = listOfNotNull(card.from, card.to).joinToString(" → ")
            .ifBlank { card.mode.orEmpty() },
        subtitle = listOfNotNull(
            fmt(card.distanceKm)?.let { "$it km" },
            fmt(card.durationMin)?.let { "$it dk" },
            card.mode
        ).joinToString(" · ").takeIf { it.isNotBlank() }
    ) {
        val visibleSteps = if (expanded) card.steps else card.steps.take(4)
        visibleSteps.forEachIndexed { index, step ->
            CardListRow(
                primary = "${index + 1}. ${step.text.orEmpty()}",
                secondary = fmt(step.distanceKm)?.let { "$it km" }
            )
        }
        val hiddenCount = card.steps.size - visibleSteps.size
        if (hiddenCount > 0) {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = stringResource(
                        R.string.crisis_sentinel_card_route_steps_more,
                        hiddenCount
                    )
                )
            }
        }
    }
}

@Composable
private fun MarineCard(card: CrisisSentinelCard.Marine) {
    CardShell(icon = Icons.Filled.Waves, title = card.label, subtitle = card.severity) {
        MetricChipRow(
            listOfNotNull(
                fmt(card.waveHeight)?.let { Icons.Filled.Waves to "$it m" },
                fmt(card.wavePeriod)?.let { Icons.Filled.Waves to "$it s" },
                fmt(card.waveDirection)?.let { Icons.Filled.Air to "$it°" },
                fmt(card.windWaveHeight)?.let { Icons.Filled.Air to "$it m" },
                fmt(card.swellWaveHeight)?.let { Icons.Filled.Waves to "swell $it m" },
                fmt(card.seaSurfaceTemp)?.let { Icons.Filled.Thermostat to "$it°C" }
            )
        )
    }
}

@Composable
private fun SatelliteCard(card: CrisisSentinelCard.Satellite) {
    val context = LocalContext.current
    CardShell(
        icon = Icons.Filled.SatelliteAlt,
        title = card.label,
        subtitle = listOfNotNull(card.date, card.imageKind, fmt(card.widthKm)?.let { "$it km" })
            .joinToString(" · ").takeIf { it.isNotBlank() }
    ) {
        val url = card.url
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = card.label,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.crisis_sentinel_card_open_image))
            }
        }
    }
}

@Composable
private fun FacilityCard(
    card: CrisisSentinelCard.Facility,
    onShowOnMap: (List<CrisisSentinelMapPoint>) -> Unit
) {
    CardShell(
        icon = Icons.Filled.LocalHospital,
        title = card.label ?: card.facilityType
    ) {
        card.facilities.forEach { facility ->
            CardListRow(
                primary = facility.name.orEmpty(),
                secondary = fmt(facility.distanceKm)?.let { "$it km" },
                mapPoint = if (facility.lat != null && facility.lon != null) {
                    CrisisSentinelMapPoint(
                        lat = facility.lat,
                        lng = facility.lon,
                        label = facility.name.orEmpty()
                    )
                } else null,
                onShowOnMap = onShowOnMap
            )
        }
        val allPoints = card.facilities.mapNotNull { facility ->
            if (facility.lat != null && facility.lon != null) {
                CrisisSentinelMapPoint(
                    lat = facility.lat,
                    lng = facility.lon,
                    label = facility.name.orEmpty()
                )
            } else null
        }
        if (allPoints.size > 1) {
            ShowOnMapButton(points = allPoints, onShowOnMap = onShowOnMap)
        }
    }
}

@Composable
private fun DamageCard(card: CrisisSentinelCard.Damage) {
    CardShell(icon = Icons.Filled.ReportProblem, title = card.rating) {
        if (!card.summary.isNullOrBlank()) {
            Text(
                text = card.summary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        card.hazards.forEach { hazard ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp)
                )
                Text(text = hazard, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}
