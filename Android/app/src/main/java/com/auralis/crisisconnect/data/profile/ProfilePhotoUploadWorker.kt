package com.auralis.crisisconnect.data.profile

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Uploads the locally cached profile photo (stored in [ProfileImageStorage]) to
 * Firebase Storage at `users/{uid}/avatar.jpg`, then writes the resulting
 * download URL back to the user's Firestore document.
 *
 * Pre-flight role + agency gates mirror `storage.rules` and `firestore.rules`
 * — mobile clients without an assigned role / organization can pick a photo
 * locally, but the upload never actually leaves the device.
 *
 * The worker is enqueued with [NetworkType.CONNECTED] so a photo picked while
 * offline waits until connectivity returns. Replacement uses
 * [ExistingWorkPolicy.REPLACE] so the newest pick always wins.
 */
class ProfilePhotoUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser?.takeUnless { it.isAnonymous }
            ?: return Result.failure()

        val base64 = ProfileImageStorage.getProfileImageBase64(applicationContext)
            ?: return Result.failure()
        val bytes = runCatching { Base64.decode(base64, Base64.NO_WRAP) }
            .getOrNull()
            ?: return Result.failure()
        if (bytes.isEmpty()) return Result.failure()

        val firestore = FirebaseFirestore.getInstance()
        val docRef = firestore.collection("users").document(user.uid)

        val snapshot = runCatching { docRef.get().await() }
            .getOrElse {
                Log.w(TAG, "Could not read user doc for upload gate", it)
                return Result.retry()
            }

        if (!isAllowedToUpload(snapshot)) {
            // Don't blow up — keep the local cached photo. Try again later in
            // case the user is granted a role.
            Log.i(TAG, "User ${user.uid} is not yet authorized to upload a photo")
            return Result.success()
        }

        val storageRef = FirebaseStorage.getInstance()
            .reference
            .child("users/${user.uid}/avatar.jpg")
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCacheControl("public, max-age=300")
            .build()

        val downloadUrl = try {
            storageRef.putBytes(bytes, metadata).await()
            storageRef.downloadUrl.await().toString()
        } catch (t: Throwable) {
            Log.w(TAG, "Avatar upload failed; will retry", t)
            return Result.retry()
        }

        return try {
            docRef.set(
                mapOf(
                    "photoURL" to downloadUrl,
                    "photoUpdatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write photo URL to user doc; will retry", t)
            Result.retry()
        }
    }

    private fun isAllowedToUpload(snapshot: com.google.firebase.firestore.DocumentSnapshot?): Boolean {
        if (snapshot == null || !snapshot.exists()) return false
        val role = snapshot.getString("role")?.trim()?.lowercase() ?: return false
        if (role.isEmpty() || role == "user") return false
        val orgFields = listOf("agency", "agencyName", "agencyKey", "agencySlug",
            "authority", "institution", "organization")
        return orgFields.any { field ->
            !snapshot.getString(field).isNullOrBlank()
        }
    }

    companion object {
        const val TAG = "ProfilePhotoUpload"
        const val UNIQUE_WORK_NAME = "ProfilePhotoUpload"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ProfilePhotoUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
