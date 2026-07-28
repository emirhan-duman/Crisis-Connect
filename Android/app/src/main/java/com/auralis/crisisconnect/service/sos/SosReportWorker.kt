package com.auralis.crisisconnect.service.sos

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Store-and-forward tail of the SOS session: once a network appears it delivers whatever an
 * offline stop left behind — the dashboard's "resolved" report and the contacts' all-clear
 * messages. Active-state reporting does not need this worker; the foreground service's loops
 * keep retrying for as long as the SOS is running.
 */
class SosReportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val resolveDelivered = runCatching {
            SosCloudReporter.deliverPendingResolve(applicationContext)
        }.onFailure { Log.w(TAG, "Pending SOS resolve delivery crashed", it) }
            .getOrDefault(false)
        val allClearDelivered = runCatching {
            SosContactNotifier.deliverPendingAllClear(applicationContext)
        }.onFailure { Log.w(TAG, "Pending SOS all-clear delivery crashed", it) }
            .getOrDefault(false)
        return when {
            resolveDelivered && allClearDelivered -> Result.success()
            runAttemptCount >= MAX_ATTEMPTS -> {
                Log.w(TAG, "Giving up on pending SOS follow-ups after $runAttemptCount attempts")
                SosCloudReporter.clearPendingResolve(applicationContext)
                SosContactNotifier.clearPendingAllClear(applicationContext)
                Result.failure()
            }
            else -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "SosReportWorker"
        private const val UNIQUE_WORK_NAME = "sos_cloud_pending_resolve"
        private const val MAX_ATTEMPTS = 12

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SosReportWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
