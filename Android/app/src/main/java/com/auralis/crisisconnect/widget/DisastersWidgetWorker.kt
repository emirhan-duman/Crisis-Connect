package com.auralis.crisisconnect.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.auralis.crisisconnect.screens.Tools.data.CountryLocator
import com.auralis.crisisconnect.screens.Tools.data.DisasterFeed
import com.auralis.crisisconnect.screens.Tools.data.DisasterRegion
import com.auralis.crisisconnect.screens.Tools.data.DisasterRepository

/**
 * Fetch half of the disasters widget: detects the user's region, refreshes the
 * repository cache from the network, then re-renders the widget. The widget's
 * own render path never touches the network (see [DisastersWidget]).
 */
class DisastersWidgetWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val detected = runCatching { CountryLocator(context).detect() }.getOrNull()
        if (detected != null) {
            DisastersWidgetState.saveRegion(context, detected)
        }
        val region = detected ?: DisastersWidgetState.loadRegion(context)
        val feed = if (region != null) DisasterFeed.LOCAL else DisasterFeed.GLOBAL

        // load() serves a fresh cache without network and falls back to a stale
        // cache when every source fails, so this never throws for feed reasons.
        DisasterRepository(context).load(feed, region, forceRefresh = false)

        DisastersWidget().updateAll(context)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "disasters_widget_refresh"
        private const val ONE_TIME_WORK = "disasters_widget_refresh_now"

        /** Called when the first widget instance is pinned. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DisastersWidgetWorker>(30, java.util.concurrent.TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()
            )
            refreshNow(context, keepExisting = true)
        }

        /**
         * One-shot refresh. [keepExisting] = true is the self-heal/pin path (a
         * queued fetch is left alone); false is the manual refresh tap.
         */
        fun refreshNow(context: Context, keepExisting: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK,
                if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DisastersWidgetWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            )
        }

        /** Called when the last widget instance is removed. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}

/**
 * Last successfully detected region, persisted so the widget can resolve the
 * right cache file (and the worker can survive offline detection failures)
 * without re-running GPS/IP lookups on the render path.
 */
internal object DisastersWidgetState {
    private const val PREFS = "disasters_widget_prefs"
    private const val KEY_REGION = "last_region"

    fun saveRegion(context: Context, region: DisasterRegion) {
        val encoded = listOf(
            region.countryCode,
            region.countryName,
            region.minLat,
            region.maxLat,
            region.minLon,
            region.maxLon
        ).joinToString("|")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REGION, encoded)
            .apply()
    }

    fun loadRegion(context: Context): DisasterRegion? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REGION, null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 6) return null
        return runCatching {
            DisasterRegion(
                countryCode = parts[0],
                countryName = parts[1],
                minLat = parts[2].toDouble(),
                maxLat = parts[3].toDouble(),
                minLon = parts[4].toDouble(),
                maxLon = parts[5].toDouble()
            )
        }.getOrNull()
    }
}
