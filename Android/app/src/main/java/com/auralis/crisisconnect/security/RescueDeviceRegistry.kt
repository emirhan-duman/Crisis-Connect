package com.auralis.crisisconnect.security

import android.content.Context
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object RescueDeviceRegistry {
    private const val COLLECTION = "rescueDevices"

    suspend fun registerLocalDevice(
        firestore: FirebaseFirestore,
        context: Context,
        uid: String
    ): String {
        val appContext = context.applicationContext
        val normalizedUid = uid.trim()
        require(normalizedUid.isNotEmpty()) { "uid is required" }

        val currentDeviceId = LocalKeyStorage.getOrCreateRescueDeviceId(appContext)
        try {
            registerDevice(
                firestore = firestore,
                uid = normalizedUid,
                deviceId = currentDeviceId
            )
            LocalKeyStorage.markRescueDeviceOwner(appContext, normalizedUid)
            return currentDeviceId
        } catch (throwable: Throwable) {
            if (!shouldRotateDeviceIdAfterFailure(appContext, normalizedUid, throwable)) {
                throw throwable
            }
        }

        val rotatedDeviceId = LocalKeyStorage.rotateRescueDeviceId(appContext, normalizedUid)
        registerDevice(
            firestore = firestore,
            uid = normalizedUid,
            deviceId = rotatedDeviceId
        )
        LocalKeyStorage.markRescueDeviceOwner(appContext, normalizedUid)
        return rotatedDeviceId
    }

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

    private fun shouldRotateDeviceIdAfterFailure(
        context: Context,
        uid: String,
        throwable: Throwable
    ): Boolean {
        val firestoreError = throwable as? FirebaseFirestoreException ?: return false
        if (firestoreError.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            return false
        }
        val ownerUid = LocalKeyStorage.getRescueDeviceOwnerUid(context)
        return ownerUid != uid
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
