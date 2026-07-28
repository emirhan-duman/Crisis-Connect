package com.auralis.crisisconnect.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ResourceColorProvider
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Tools.data.DisasterEvent
import com.auralis.crisisconnect.screens.Tools.data.DisasterFeed
import com.auralis.crisisconnect.screens.Tools.data.DisasterFetchResult
import com.auralis.crisisconnect.screens.Tools.data.DisasterRegion
import com.auralis.crisisconnect.screens.Tools.data.DisasterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DisastersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DisastersWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DisastersWidgetWorker.schedule(context)
    }

    // Some launchers restore widgets without a fresh onEnabled; scheduling is
    // idempotent (KEEP), so re-assert it on every host-driven update too.
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        DisastersWidgetWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DisastersWidgetWorker.cancel(context)
    }
}

/** Tapping the refresh icon fetches the feed off-widget and re-renders when done. */
class RefreshDisastersAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        DisastersWidgetWorker.refreshNow(context)
    }
}

/**
 * Home-screen feed of the latest nearby disasters. Renders purely from the
 * repository's disk cache so the widget appears instantly; the network fetch
 * lives in [DisastersWidgetWorker], which re-renders the widget when new data
 * lands. Tapping the widget opens the full Recent Disasters screen.
 *
 * All colors come from resources (values-v31 maps them to the Material You
 * system palette) so theme and wallpaper changes apply without a re-render.
 */
class DisastersWidget : GlanceAppWidget() {

    // Exact sizing: row count adapts to the real widget height on resize.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = withContext(Dispatchers.IO) {
            val repository = DisasterRepository(context)
            val region = DisastersWidgetState.loadRegion(context)
            val result = if (region != null) {
                repository.peekCache(DisasterFeed.LOCAL, region)
                    ?: repository.peekCache(DisasterFeed.GLOBAL, null)
            } else {
                repository.peekCache(DisasterFeed.GLOBAL, null)
            }
            WidgetSnapshot(region, result)
        }

        // Self-heal: an empty widget means the first fetch hasn't landed (or was
        // missed). KEEP policy makes this a no-op while a fetch is already queued.
        if (snapshot.result == null) {
            DisastersWidgetWorker.refreshNow(context, keepExisting = true)
        }

        val launchIntent = MainActivity.createTrustedLaunchIntent(context) {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_ROUTE, "recent_disasters")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        provideContent {
            DisastersWidgetContent(snapshot, launchIntent)
        }
    }
}

private data class WidgetSnapshot(
    val region: DisasterRegion?,
    val result: DisasterFetchResult?
)

private val CardTextPrimary = ResourceColorProvider(R.color.widget_text_primary)
private val CardTextSecondary = ResourceColorProvider(R.color.widget_text_secondary)

@Composable
private fun DisastersWidgetContent(snapshot: WidgetSnapshot, launchIntent: Intent) {
    val context = LocalContext.current
    val size = LocalSize.current

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.disasters_widget_bg))
            .clickable(actionStartActivity(launchIntent))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        WidgetHeader(context, snapshot, showUpdated = size.width >= 280.dp)

        val events = snapshot.result?.events.orEmpty()
        if (events.isEmpty()) {
            EmptyState(context, GlanceModifier.fillMaxWidth().defaultWeight())
        } else {
            // Header ≈ 36dp + vertical padding 20dp; each row needs ~42dp.
            val maxRows = (((size.height.value - 58f) / 42f).toInt()).coerceIn(2, 8)
            Spacer(GlanceModifier.height(4.dp))
            events.take(maxRows).forEachIndexed { index, event ->
                if (index > 0) Spacer(GlanceModifier.height(6.dp))
                EventRow(context, event)
            }
        }
    }
}

@Composable
private fun WidgetHeader(context: Context, snapshot: WidgetSnapshot, showUpdated: Boolean) {
    val baseTitle = context.getString(R.string.tool_recent_disasters_title)
    val title = snapshot.region?.countryName
        ?.takeIf { it.isNotBlank() }
        ?.let { "$baseTitle · $it" }
        ?: baseTitle

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = CardTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
        val updatedMillis = snapshot.result?.lastUpdatedMillis ?: 0L
        if (showUpdated && updatedMillis > 0L) {
            Text(
                text = DateUtils.getRelativeTimeSpanString(updatedMillis).toString(),
                maxLines = 1,
                style = TextStyle(color = CardTextSecondary, fontSize = 10.sp)
            )
        }
        // Generous tap target around the small glyph (widget quality guidance).
        Box(
            modifier = GlanceModifier
                .size(36.dp)
                .clickable(actionRunCallback<RefreshDisastersAction>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = context.getString(R.string.recent_disasters_refresh_cd),
                modifier = GlanceModifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(context: Context, modifier: GlanceModifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = null,
                modifier = GlanceModifier.size(22.dp)
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = context.getString(R.string.widget_disasters_empty),
                style = TextStyle(
                    color = CardTextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

@Composable
private fun EventRow(context: Context, event: DisasterEvent) {
    val badge = badgeStyle(event)
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(36.dp)
                .background(ImageProvider(badge.background)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badgeText(event),
                maxLines = 1,
                style = TextStyle(
                    color = ResourceColorProvider(badge.foreground),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = event.title,
                maxLines = 1,
                style = TextStyle(
                    color = CardTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = eventSubLine(event),
                maxLines = 1,
                style = TextStyle(color = CardTextSecondary, fontSize = 11.sp)
            )
        }
    }
}

private fun eventSubLine(event: DisasterEvent): String = buildString {
    if (event.eventTimeMillis > 0L) {
        append(DateUtils.getRelativeTimeSpanString(event.eventTimeMillis).toString())
    }
    if (event.source.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(event.source)
    }
}

/** Mirrors the in-app badge: "M5.2" for earthquakes, a type glyph otherwise. */
private fun badgeText(event: DisasterEvent): String {
    val magnitude = event.magnitude
    if (event.eventType.equals("EQ", ignoreCase = true) && magnitude != null && magnitude > 0.0) {
        return "M%.1f".format(magnitude)
    }
    return when (event.eventType.uppercase()) {
        "FL" -> "🌊"
        "TC" -> "🌀"
        "VO" -> "🌋"
        "WF" -> "🔥"
        "DR" -> "🏜"
        else -> "⚠️"
    }
}

private data class BadgeStyle(val foreground: Int, val background: Int)

/** Same palette as the in-app alertColorFor(), with a magnitude fallback for plain quake feeds. */
private fun badgeStyle(event: DisasterEvent): BadgeStyle {
    val level = event.alertLevel.lowercase()
    val magnitude = event.magnitude ?: 0.0
    return when {
        level == "red" || (level !in KNOWN_LEVELS && magnitude >= 6.0) ->
            BadgeStyle(R.color.widget_severity_red, R.drawable.widget_badge_red)
        level == "orange" || (level !in KNOWN_LEVELS && magnitude >= 5.0) ->
            BadgeStyle(R.color.widget_severity_orange, R.drawable.widget_badge_orange)
        level == "green" || (level !in KNOWN_LEVELS && magnitude > 0.0) ->
            BadgeStyle(R.color.widget_severity_green, R.drawable.widget_badge_green)
        else ->
            BadgeStyle(R.color.widget_severity_neutral, R.drawable.widget_badge_neutral)
    }
}

private val KNOWN_LEVELS = setOf("red", "orange", "green")
