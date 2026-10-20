package com.auralis.crisisconnect.analytics

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.CustomKeysAndValues
import java.util.concurrent.ConcurrentHashMap

/** Reports a rate-limited, privacy-safe summary for failed satellite relay operations. */
object SatelliteCrashReporter {
    private const val REPORT_COOLDOWN_MS = 5 * 60 * 1_000L
    private val lastReportAt = ConcurrentHashMap<String, Long>()

    fun recordFailure(feature: String, failureCategory: String, cause: Throwable? = null) {
        val safeFeature = feature.take(24)
        val safeCategory = failureCategory.take(32)
        val throttleKey = "$safeFeature:$safeCategory"
        val now = System.currentTimeMillis()
        var shouldRecord = false
        lastReportAt.compute(throttleKey) { _, previous ->
            if (previous == null || now - previous >= REPORT_COOLDOWN_MS) {
                shouldRecord = true
                now
            } else {
                previous
            }
        }
        if (!shouldRecord) return

        runCatching {
            val report = IllegalStateException("Satellite $safeFeature failed: $safeCategory")
            val keys = CustomKeysAndValues.Builder()
                .putString("satellite_transport", "satellite")
                .putString("satellite_feature", safeFeature)
                .putString("satellite_failure_category", safeCategory)
            cause?.let { keys.putString("satellite_cause_type", it.javaClass.simpleName.take(48)) }
            FirebaseCrashlytics.getInstance().recordException(report, keys.build())
        }
    }
}
