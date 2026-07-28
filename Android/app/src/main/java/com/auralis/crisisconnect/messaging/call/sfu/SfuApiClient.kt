package com.auralis.crisisconnect.messaging.call.sfu

import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Thin client for the Cloudflare Realtime (Serverless SFU) Connection API, reached through our own
 * `/api/realtime` proxy (which injects the App Secret server-side). The three ops the proxy allows:
 * `sessions/new`, `sessions/{id}/tracks/new`, `sessions/{id}/renegotiate`. Auth is the Firebase ID token
 * (the proxy accepts `Authorization: Bearer <idToken>` for mobile). Mirrors the web's `api()` in
 * sfu-call.ts. Every call returns the parsed JSON or throws.
 */
class SfuApiClient(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // The Cloudflare proxy can exceed OkHttp's default 10s read timeout when the edge is cold (observed
    // >10s on first call of the day, which used to kill the whole call). Longer read window + one retry.
    private val http by lazy {
        PinnedOkHttpClient.newClient().newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** POST `sessions/new` with our first offer → `{ sessionId, sessionDescription:{type:answer,sdp} }`. */
    suspend fun createSession(offerSdp: String): JSONObject = post(
        "sessions/new",
        JSONObject().put("sessionDescription", sdpObj("offer", offerSdp)),
    )

    /** POST `sessions/{id}/tracks/new` to PUBLISH local tracks (we offer, the SFU answers). */
    suspend fun publishTracks(sessionId: String, offerSdp: String, tracks: List<JSONObject>): JSONObject = post(
        "sessions/$sessionId/tracks/new",
        JSONObject()
            .put("sessionDescription", sdpObj("offer", offerSdp))
            .put("tracks", org.json.JSONArray(tracks)),
    )

    /** POST `sessions/{id}/tracks/new` to PULL remote tracks (the SFU offers, we answer via renegotiate). */
    suspend fun pullTracks(sessionId: String, tracks: List<JSONObject>): JSONObject = post(
        "sessions/$sessionId/tracks/new",
        JSONObject().put("tracks", org.json.JSONArray(tracks)),
    )

    /** PUT `sessions/{id}/renegotiate` with our answer to the SFU's pull offer. */
    suspend fun renegotiate(sessionId: String, answerSdp: String): JSONObject = put(
        "sessions/$sessionId/renegotiate",
        JSONObject().put("sessionDescription", sdpObj("answer", answerSdp)),
    )

    private fun sdpObj(type: String, sdp: String) = JSONObject().put("type", type).put("sdp", sdp)

    private suspend fun post(path: String, body: JSONObject) = execute(path, body, "POST")
    private suspend fun put(path: String, body: JSONObject) = execute(path, body, "PUT")

    private suspend fun execute(path: String, body: JSONObject, method: String): JSONObject =
        withContext(Dispatchers.IO) {
            val url = requireBaseUrl().newBuilder().addPathSegments("api/realtime/$path").build()
            val token = idToken()
            val reqBody = body.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, reqBody)
                .build()
            android.util.Log.i("SfuApiClient", "$method $path → $url")
            var lastTimeout: IOException? = null
            repeat(2) { attempt ->
                try {
                    return@withContext http.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        android.util.Log.i("SfuApiClient", "$path ← HTTP ${response.code} (${text.take(600)})")
                        val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                        if (!response.isSuccessful || json.optString("errorCode").isNotBlank()) {
                            val detail = json.optString("errorDescription").ifBlank { "HTTP ${response.code}" }
                            throw IOException("SFU $path failed: $detail")
                        }
                        json
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    lastTimeout = e
                    android.util.Log.w("SfuApiClient", "$path timed out (attempt ${attempt + 1}/2)")
                }
            }
            throw lastTimeout ?: IOException("SFU $path failed")
        }

    private suspend fun idToken(): String {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in.")
        return user.getIdToken(false).await().token?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ID token missing.")
    }

    private fun requireBaseUrl(): HttpUrl {
        val base = BuildConfig.MOBILE_SYNC_BASE_URL.trim().trimEnd('/')
        val parsed = base.toHttpUrlOrNull()
        require(base.isNotBlank() && parsed != null && (parsed.isHttps || BuildConfig.DEBUG)) {
            "MOBILE_SYNC_BASE_URL is not configured."
        }
        return parsed
    }
}
