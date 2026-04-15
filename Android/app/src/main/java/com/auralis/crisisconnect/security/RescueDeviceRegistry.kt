package com.auralis.crisisconnect.security

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object RescueDeviceRegistry {
    private const val COLLECTION = "rescueDevices"

    suspend fun registerDevice(
        firestore: FirebaseFirestore,
        uid: String,
        deviceId: String
    ) {
        val normalizedUid = uid.trim()
        val normalizedDeviceId = deviceId.trim()
        require(normalizedUid.isNotEmpty()) { "uid is required" }
        require(normalizedDeviceId.isNotEmpty()) { "deviceId is required" }

        firestore.collection(COLLECTION)
            .document(normalizedDeviceId)
            .set(
                mapOf(
                    "deviceId" to normalizedDeviceId,
                    "uid" to normalizedUid,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .awaitResult()
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        addOnFailureListener { throwable ->
            if (continuation.isActive) {
                continuation.resumeWithException(throwable)
            }
        }
        addOnCanceledListener {
            if (continuation.isActive) {
                continuation.cancel()
            }
        }
    }
}
