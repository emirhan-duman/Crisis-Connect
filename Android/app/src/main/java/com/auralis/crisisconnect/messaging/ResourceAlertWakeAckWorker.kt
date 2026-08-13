package com.auralis.crisisconnect.messaging

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ResourceAlertWakeAckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = if (
        ResourceAlertWakeClient.drainPending(applicationContext, maximumItems = 16)
    ) {
        Result.success()
    } else {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ResourceAlertWakeAck"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ResourceAlertWakeAckWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
