package com.auralis.crisisconnect.data.profile

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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** Publishes an authoritative deletion tombstone and retries safely after offline removal. */
class ProfilePhotoDeleteWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }
            ?: return Result.failure()
        val doc = FirebaseFirestore.getInstance().collection("users").document(user.uid)

        try {
            doc.set(
                mapOf(
                    "photoURL" to "",
                    "photoUpdatedAt" to FieldValue.serverTimestamp(),
                    "photoDeletedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not publish profile-photo deletion; will retry", error)
            return Result.retry()
        }

        runCatching {
            FirebaseStorage.getInstance().reference
                .child("users/${user.uid}/avatar.jpg")
                .delete()
                .await()
        }.onFailure { error ->
            // Firestore is authoritative; orphan cleanup can fail without reviving the photo.
            Log.d(TAG, "Profile-photo object cleanup skipped", error)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ProfilePhotoDelete"
        const val UNIQUE_WORK_NAME = "ProfilePhotoDelete"

        fun enqueue(context: Context) {
            val appContext = context.applicationContext
            WorkManager.getInstance(appContext).cancelUniqueWork(ProfilePhotoUploadWorker.UNIQUE_WORK_NAME)
            val request = OneTimeWorkRequestBuilder<ProfilePhotoDeleteWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
