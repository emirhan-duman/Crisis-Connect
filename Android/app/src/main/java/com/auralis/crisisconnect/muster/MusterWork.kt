package com.auralis.crisisconnect.muster

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

private const val WORK_UID = "uid"
private const val WORK_RETRY_AT = "retryAt"

internal fun scheduleMusterSendWork(
    context: Context,
    worker: Class<out ListenableWorker>,
    queue: MusterQueue,
    uid: String,
    runningRetryAt: Long = 0L,
): Operation? {
    val now = System.currentTimeMillis()
    val delay = nextMusterSendDelay(queue, uid, now) ?: return null
    val retryAt = if (delay > 0L) now + delay else 0L
    val request = OneTimeWorkRequest.Builder(worker)
        .setInputData(workDataOf(WORK_UID to uid, WORK_RETRY_AT to retryAt))
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    // A ready request needs a follow-up during worker completion. An early retry
    // may need its own successor if the device's wall clock changed.
    val policy = if (retryAt == 0L || retryAt == runningRetryAt) {
        ExistingWorkPolicy.APPEND_OR_REPLACE
    } else {
        ExistingWorkPolicy.KEEP
    }
    return WorkManager.getInstance(context).enqueueUniqueWork(musterSendWorkName(uid, retryAt), policy, request)
}

internal suspend fun runMusterSendWork(
    inputData: Data,
    drain: suspend (String) -> Boolean,
    schedule: (String, Long) -> Operation?,
): ListenableWorker.Result {
    val uid = inputData.getString(WORK_UID) ?: return ListenableWorker.Result.failure()
    return try {
        // Keep the follow-up durable before completing this worker.
        if (!drain(uid)) schedule(uid, inputData.getLong(WORK_RETRY_AT, 0L))?.await()
        ListenableWorker.Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        ListenableWorker.Result.retry()
    }
}
