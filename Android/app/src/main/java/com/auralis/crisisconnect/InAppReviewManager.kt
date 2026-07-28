package com.auralis.crisisconnect

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Thin wrapper around Google Play's In-App Review API.
 *
 * Call [onPositiveEvent] from genuinely positive, non-emergency moments (e.g. the user just added
 * a contact). The manager applies its own gating so the rating card is only ever requested for
 * engaged users and never more than once in a long while. Play itself decides whether the card
 * actually appears (it enforces its own per-app/per-user quota), so this is strictly best-effort:
 * it never blocks, never interrupts, and never surfaces an error to the user.
 *
 * Notes:
 * - The real rating card only shows for builds installed from Google Play (Play internal/closed
 *   testing tracks included). On debug/sideloaded builds the flow completes silently with no UI —
 *   that is expected, not a bug.
 * - We deliberately never trigger this from SOS/emergency flows; only from calm success paths.
 */
object InAppReviewManager {
    private const val PREFS = "in_app_review"
    private const val KEY_POSITIVE_EVENTS = "positive_events"
    private const val KEY_LAST_REQUEST_MS = "last_request_ms"
    private const val KEY_FIRST_SEEN_MS = "first_seen_ms"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Require a couple of successful actions before we ever ask. */
    private const val MIN_POSITIVE_EVENTS = 2

    /** Give the user time to actually experience the app (Play's own guidance). */
    private const val MIN_AGE_MS = 1 * DAY_MS

    /** Never pester: once we ask, don't ask again for a long time. */
    private const val REQUEST_COOLDOWN_MS = 90 * DAY_MS

    /**
     * Records a positive milestone and, if the gating conditions are met, quietly launches the
     * In-App Review flow. Safe to call from any UI thread with any [Context]; if no host [Activity]
     * can be resolved (the API needs one) the call is a no-op.
     */
    fun onPositiveEvent(context: Context) {
        val activity = context.findActivity() ?: return
        val prefs = activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val firstSeen = prefs.getLong(KEY_FIRST_SEEN_MS, 0L).let { stored ->
            if (stored == 0L) {
                prefs.edit().putLong(KEY_FIRST_SEEN_MS, now).apply()
                now
            } else {
                stored
            }
        }
        val events = prefs.getInt(KEY_POSITIVE_EVENTS, 0) + 1
        prefs.edit().putInt(KEY_POSITIVE_EVENTS, events).apply()

        val lastRequest = prefs.getLong(KEY_LAST_REQUEST_MS, 0L)
        val eligible = events >= MIN_POSITIVE_EVENTS &&
            (now - firstSeen) >= MIN_AGE_MS &&
            (lastRequest == 0L || now - lastRequest >= REQUEST_COOLDOWN_MS)
        if (!eligible) return

        // Mark the attempt up-front so rapid repeat successes don't re-request even though Play may
        // show nothing this time.
        prefs.edit().putLong(KEY_LAST_REQUEST_MS, now).apply()

        val manager = ReviewManagerFactory.create(activity.applicationContext)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful && !activity.isFinishing) {
                // Result intentionally ignored: Play never reveals whether the user rated.
                manager.launchReviewFlow(activity, task.result)
            }
        }
    }

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
