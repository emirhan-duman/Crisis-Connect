package com.auralis.crisisconnect.sync

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MobileSyncClient {
    private const val TAG = "MobileSyncClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun syncProfile(
        context: Context,
        user: FirebaseUser,
        username: String,
        email: String,
        phoneNumber: String,
        country: String,
        agency: String,
        role: String,
        verified: Boolean,
        platform: String,
    ) {
        val baseUrl = BuildConfig.MOBILE_SYNC_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return

        val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
        if (parsedBaseUrl == null || !isAllowedBaseUrl(parsedBaseUrl)) {
            Log.w(TAG, "Ignoring invalid MOBILE_SYNC_BASE_URL")
            return
        }

        val token = user.getIdToken(false).awaitTask().token?.trim()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Skipping mobile sync because ID token is missing")
            return
        }

        val appContext = context.applicationContext
        val deviceId = runCatching {
            LocalKeyStorage.getOrCreateRescueDeviceId(appContext)
        }.getOrDefault("")
        val now = System.currentTimeMillis()
        val clientEventId = "android-profile-${user.uid}-$now"
        val payload = JSONObject()
            .put("uid", user.uid)
            .put("username", username)
            .put("displayName", username)
            .put("email", email.ifBlank { user.email.orEmpty() })
            .put("phoneNumber", phoneNumber.ifBlank { user.phoneNumber.orEmpty() })
            .put("country", country)
            .put("agency", agency)
            .put("role", role)
            .put("verified", verified)
            .put("platform", platform)

        val event = JSONObject()
            .put("id", clientEventId)
            .put("type", "profile")
            .put("occurredAt", now)
            .put("payload", payload)

        val body = JSONObject()
            .put("clientEventId", clientEventId)
            .put("source", "android")
            .put("deviceId", deviceId)
            .put("events", JSONArray().put(event))

        BuildConfig.MOBILE_SYNC_PANEL_ID.trim().takeIf { it.isNotBlank() }?.let {
            body.put("panelId", it)
        }

        postEvents(token = token, body = body)
    }

    suspend fun syncEvents(
        context: Context,
        panelId: String,
        deviceId: String,
        clientEventId: String,
        events: JSONArray,
    ) {
        val baseUrl = BuildConfig.MOBILE_SYNC_BASE_URL.trim()
        if (baseUrl.isBlank() || events.length() == 0) return
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous } ?: return
        val token = user.getIdToken(false).awaitTask().token?.trim()
        if (token.isNullOrBlank()) return

        val resolvedDeviceId = deviceId.ifBlank {
            runCatching {
                LocalKeyStorage.getOrCreateRescueDeviceId(context.applicationContext)
            }.getOrDefault("")
        }
        val body = JSONObject()
            .put("clientEventId", clientEventId)
            .put("source", "android")
            .put("deviceId", resolvedDeviceId)
            .put("events", events)

        // The caller's panel wins; the BuildConfig panel is only a fallback for blank callers so
        // events land on the rescuer's own panel instead of the build-wide default.
        panelId.trim()
            .ifBlank { BuildConfig.MOBILE_SYNC_PANEL_ID.trim() }
            .takeIf { it.isNotBlank() }
            ?.let { body.put("panelId", it) }

        postEvents(token = token, body = body)
    }

    private suspend fun postEvents(token: String, body: JSONObject) {
        val baseUrl = BuildConfig.MOBILE_SYNC_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return

        val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
        if (parsedBaseUrl == null || !isAllowedBaseUrl(parsedBaseUrl)) {
            Log.w(TAG, "Ignoring invalid MOBILE_SYNC_BASE_URL")
            return
        }

        val syncUrl = parsedBaseUrl.newBuilder()
            .addPathSegments("api/mobile/sync")
            .build()
        val request = Request.Builder()
            .url(syncUrl)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        runCatching {
            withContext(Dispatchers.IO) {
                PinnedOkHttpClient.newClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Mobile sync failed with HTTP ${response.code}")
                    }
                }
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Mobile profile sync failed", throwable)
        }
    }

    private fun isAllowedBaseUrl(url: HttpUrl): Boolean {
        if (url.isHttps) return true
        if (!BuildConfig.DEBUG) return false
        return url.host == "10.0.2.2" ||
            url.host == "127.0.0.1" ||
            url.host == "localhost" ||
            url.host.endsWith(".local")
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val result = task.result
            if (result != null) {
                continuation.resume(result)
            } else {
                continuation.resumeWithException(IllegalStateException("Task returned null"))
            }
        } else {
            continuation.resumeWithException(
                task.exception ?: IllegalStateException("Task failed without exception")
            )
        }
    }
}
