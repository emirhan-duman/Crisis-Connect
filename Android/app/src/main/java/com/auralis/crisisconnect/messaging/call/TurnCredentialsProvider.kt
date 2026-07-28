package com.auralis.crisisconnect.messaging.call

import android.util.Log
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.webrtc.PeerConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Supplies ICE servers for [InternetCallManager]. The relay is Cloudflare Realtime's global-anycast
 * TURN, minted per-session by the dashboard's `/api/turn-credentials` endpoint and authenticated with
 * the user's Firebase ID token — so the TURN secret never lives in the app. Results are cached with a
 * TTL and always include the public Google STUN servers, so a failed fetch still allows direct-P2P
 * calls.
 *
 * The fetch is asynchronous and [current] never blocks: it returns STUN-only until the first
 * successful [refresh]. [InternetCallManager.init] warms the cache at startup so the first call
 * already has a relay; call sites also kick a background refresh to keep long sessions fresh.
 */
object TurnCredentialsProvider {
    private const val TAG = "InternetCall"

    // Falls back to the Firebase Hosting URL when MOBILE_SYNC_BASE_URL is unset (TURN is a generic
    // dashboard endpoint, not panel-specific), so relay works without extra build config.
    private const val DEFAULT_BASE_URL = "https://crisis-connect-1.web.app"
    private const val CACHE_TTL_MS = 50 * 60 * 1000L // creds are minted for 60 min; refresh at 50.

    private val stunFallback: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    @Volatile private var cached: List<PeerConnection.IceServer> = stunFallback
    @Volatile private var cachedAt: Long = 0L

    /** Best-known ICE servers right now. Never blocks; STUN-only until the first refresh lands. */
    fun current(): List<PeerConnection.IceServer> = cached

    /** Refresh the cache from the endpoint. Fire-and-forget safe; a no-op while the cache is fresh. */
    suspend fun refresh(force: Boolean = false) {
        val isFresh = cached !== stunFallback && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
        if (!force && isFresh) return
        val servers = fetchIceServers() ?: return
        cached = stunFallback + servers
        cachedAt = System.currentTimeMillis()
    }

    private suspend fun fetchIceServers(): List<PeerConnection.IceServer>? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BuildConfig.MOBILE_SYNC_BASE_URL.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
            val parsed = baseUrl.toHttpUrlOrNull() ?: return@withContext null
            if (!parsed.isHttps) return@withContext null

            // Any authenticated account may mint relay creds — INCLUDING an anonymous (QR-only) user:
            // it still holds a valid Firebase ID token, and the server verifies that token (plus App
            // Check) before returning creds. Requiring a non-anonymous user here would leave QR-added
            // contacts on STUN only, so their calls fail behind carrier CGNAT / symmetric NAT — the
            // common case on mobile data, exactly when the relay matters most.
            val user = FirebaseAuth.getInstance().currentUser
                ?: return@withContext null
            val token = user.getIdToken(false).awaitTask().token?.trim().takeUnless { it.isNullOrBlank() }
                ?: return@withContext null

            val url = parsed.newBuilder().addPathSegments("api/turn-credentials").build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            PinnedOkHttpClient.newClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                TurnCredentials.parse(body).map { it.toIceServer() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TURN credential fetch failed: ${e.message}")
            null
        }
    }

    private fun IceServerSpec.toIceServer(): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(urls)
        username?.let { builder.setUsername(it) }
        credential?.let { builder.setPassword(it) }
        return builder.createIceServer()
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val result = task.result
            if (result != null) continuation.resume(result)
            else continuation.resumeWithException(IllegalStateException("Task returned null"))
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
        }
    }
}
