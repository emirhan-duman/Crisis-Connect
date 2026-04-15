package com.auralis.crisisconnect.security

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Centralized helper that gates access to Rescue tooling by inspecting the authenticated user's
 * Firebase custom claims.
 */
object FirebaseRoleHelper {

    private const val TAG = "FirebaseRoleHelper"
    private val ALLOWED_ROLES = setOf("admin", "fieldteam")

    /**
     * Represents the result of checking a user's Rescue role from their Firebase ID token claims.
     */
    sealed class RescueRoleResult {
        data class Authorized(val role: String) : RescueRoleResult()
        object Unauthenticated : RescueRoleResult()
        object Unauthorized : RescueRoleResult()
        data class Failure(val exception: Throwable) : RescueRoleResult()
    }

    /**
     * Represents the result of rescue role authorization after re-checking Firebase ID token claims.
     */
    sealed class RescuePermissionResult {
        object Authorized : RescuePermissionResult()
        object Forbidden : RescuePermissionResult()
        object Unauthenticated : RescuePermissionResult()
        data class Failure(val exception: Throwable) : RescuePermissionResult()
    }

    suspend fun fetchRescueKeyFor(
        userId: String,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    ): ByteArray = withContext(Dispatchers.IO) {
        val caller = auth.currentUser?.takeUnless { it.isAnonymous }
            ?: throw IllegalStateException("User must be authenticated")
        val sanitizedUserId = userId.trim()
        require(sanitizedUserId.isNotEmpty()) { "User ID is required to fetch rescue key" }

        // Refresh the caller token to ensure Firestore security rules evaluate the latest claims.
        caller.getIdToken(true).awaitResult()
        val keyBytes = AesCipherHelper.loadAesKeyBase64(
            firestore = firestore,
            deviceId = sanitizedUserId,
            collectionPath = "users"
        )
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Fetched ${keyBytes.size * 8}-bit rescue key for $sanitizedUserId")
        }
        keyBytes
    }

    /**
     * Refreshes the caller's Firebase ID token and extracts the normalized `role` custom claim.
     * This executes on a background dispatcher to avoid touching the main thread.
     */
    suspend fun fetchRescueRole(
        auth: FirebaseAuth = FirebaseAuth.getInstance()
    ): RescueRoleResult = withContext(Dispatchers.IO) {
        val user = auth.currentUser?.takeUnless { it.isAnonymous }
            ?: return@withContext RescueRoleResult.Unauthenticated

        return@withContext try {
            val tokenResult = user.getIdToken(true).awaitResult()
            val rawRole = tokenResult.claims["role"] as? String
            val normalizedRole = rawRole?.lowercase(Locale.US)?.trim()

            if (normalizedRole != null && normalizedRole in ALLOWED_ROLES) {
                RescueRoleResult.Authorized(normalizedRole)
            } else {
                Log.i(TAG, "User lacks Rescue privileges. Claim=$rawRole")
                RescueRoleResult.Unauthorized
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (authException: FirebaseAuthException) {
            Log.w(TAG, "Firebase rejected role fetch", authException)
            RescueRoleResult.Failure(authException)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unexpected error while verifying role", throwable)
            RescueRoleResult.Failure(throwable)
        }
    }

    /**
     * Re-checks the current Firebase ID token for Rescue privilege.
     */
    suspend fun verifyRescuePermission(
        auth: FirebaseAuth = FirebaseAuth.getInstance()
    ): RescuePermissionResult = withContext(Dispatchers.IO) {
        val user = auth.currentUser?.takeUnless { it.isAnonymous }
            ?: return@withContext RescuePermissionResult.Unauthenticated
        when (val roleResult = fetchRescueRole(auth)) {
            is RescueRoleResult.Authorized -> RescuePermissionResult.Authorized
            RescueRoleResult.Unauthorized -> RescuePermissionResult.Forbidden
            RescueRoleResult.Unauthenticated -> RescuePermissionResult.Unauthenticated
            is RescueRoleResult.Failure -> RescuePermissionResult.Failure(roleResult.exception)
        }
    }
}

/**
 * Lightweight coroutine bridge so we can await Play Services tasks without extra dependencies.
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val result = task.result
            if (result != null) {
                continuation.resume(result)
            } else {
                continuation.resumeWithException(
                    IllegalStateException("Firebase returned a null result")
                )
            }
        } else {
            val error = task.exception ?: IllegalStateException("Unknown Firebase task failure")
            continuation.resumeWithException(error)
        }
    }

    continuation.invokeOnCancellation {
        // Tasks API does not expose cancellation, but invokeOnCancellation allows cooperative cleanup.
    }
}
