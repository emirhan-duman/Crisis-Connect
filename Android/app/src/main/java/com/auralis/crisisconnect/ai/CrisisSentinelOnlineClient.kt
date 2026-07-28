package com.auralis.crisisconnect.ai

import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One chat turn in the dashboard chat API's wire format. */
data class CrisisSentinelOnlineMessage(
    val sender: String,
    val text: String
) {
    companion object {
        const val SENDER_USER = "user"
        const val SENDER_BOT = "bot"
    }
}

class CrisisSentinelOnlineException(
    val kind: Kind,
    val httpStatus: Int? = null,
    message: String
) : Exception(message) {
    enum class Kind {
        /** No signed-in (non-anonymous) Firebase user, or the server rejected the token. */
        Unauthorized,

        /** The account lacks dashboard access (role/panel) server-side. */
        Forbidden,

        /** The agency panel's AI quota is exhausted. */
        Quota,

        /** Transport-level failure (unreachable, reset, timeout between events). */
        Network,

        /** The server answered but with an unexpected status or payload. */
        Server,

        /** The panel has no AI provider configured (server ended with needsApiKey). */
        NotConfigured
    }
}

/**
 * Client for the web dashboard's Crisis Sentinel chat API: `POST /api/chat/stream` (SSE) and
 * `GET /api/chat/providers`. Field-team only: callers must gate on the role certificate before
 * invoking; the server enforces the same gate via the Firestore user document (role + agency
 * panel).
 *
 * The base URL is the dashboard's Cloud Run origin, not a certificate-pinned custom domain:
 * `*.run.app` leaves are Google-managed and rotate like `*.googleapis.com`, so the NSC
 * "custom hosts must be pinned" rule does not apply (same brick-risk rationale documented in
 * network_security_config.xml).
 */
class CrisisSentinelOnlineClient(
    baseUrl: String = BuildConfig.CRISIS_SENTINEL_ONLINE_BASE_URL
) {
    private val baseHttpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?.takeIf { it.isHttps }

    private val client: OkHttpClient = PinnedOkHttpClient.newClient().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Watchdog between SSE frames; the server emits an early `status` event, so a healthy
        // stream never stays silent this long. The overall cap is the ViewModel's timeout.
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    /**
     * Sends the conversation and streams the assistant reply. [onDelta] receives the full
     * accumulated text after each server delta (invoked from an OkHttp thread). [modelChoice]
     * pins a provider/model; null lets the server pick its default. Cancelling the calling
     * coroutine aborts the HTTP call immediately.
     */
    suspend fun streamChat(
        messages: List<CrisisSentinelOnlineMessage>,
        modelChoice: CrisisSentinelOnlineModelChoice? = null,
        onDelta: (accumulated: String) -> Unit
    ): CrisisSentinelOnlineStreamResult = withIdToken { token ->
        executeStream(token, messages, modelChoice, onDelta)
    }

    /** Providers/models the signed-in user's panel can use. Server omits unconfigured ones. */
    suspend fun fetchProviders(): List<CrisisSentinelOnlineProvider> = withIdToken { token ->
        executeFetchProviders(token)
    }

    /**
     * Runs [block] with a Firebase ID token; on an Unauthorized failure retries exactly once
     * with a force-refreshed token (covers the cached-token revocation window). Throws
     * [CrisisSentinelOnlineException] with kind Unauthorized when no non-anonymous user exists.
     */
    private suspend fun <T> withIdToken(block: suspend (token: String) -> T): T {
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }
            ?: throw CrisisSentinelOnlineException(
                kind = CrisisSentinelOnlineException.Kind.Unauthorized,
                message = "No signed-in Firebase user"
            )
        val token = fetchIdToken(user::getIdToken, forceRefresh = false)
        return try {
            block(token)
        } catch (unauthorized: CrisisSentinelOnlineException) {
            if (unauthorized.kind != CrisisSentinelOnlineException.Kind.Unauthorized) throw unauthorized
            val freshToken = fetchIdToken(user::getIdToken, forceRefresh = true)
            block(freshToken)
        }
    }

    private suspend fun fetchIdToken(
        getIdToken: (Boolean) -> Task<com.google.firebase.auth.GetTokenResult>,
        forceRefresh: Boolean
    ): String {
        val token = runCatching { getIdToken(forceRefresh).awaitTask().token?.trim() }
            .getOrNull()
        if (token.isNullOrBlank()) {
            throw CrisisSentinelOnlineException(
                kind = CrisisSentinelOnlineException.Kind.Unauthorized,
                message = "Could not obtain Firebase ID token"
            )
        }
        return token
    }

    private fun endpoint(vararg segments: String): HttpUrl {
        val base = baseHttpUrl ?: throw CrisisSentinelOnlineException(
            kind = CrisisSentinelOnlineException.Kind.NotConfigured,
            message = "Online chat base URL is not configured"
        )
        return base.newBuilder().apply { segments.forEach(::addPathSegments) }.build()
    }

    private suspend fun executeFetchProviders(token: String): List<CrisisSentinelOnlineProvider> {
        val request = Request.Builder()
            .url(endpoint("api/chat/providers"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val body = executeCall(request) { response ->
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(response.body?.string().orEmpty()).optString("error")
                }.getOrNull().orEmpty()
                throw mapStatus(response.code, detail.ifBlank { "HTTP ${response.code}" })
            }
            response.body?.string().orEmpty()
        }
        return parseProviders(body)
    }

    private suspend fun executeStream(
        token: String,
        messages: List<CrisisSentinelOnlineMessage>,
        modelChoice: CrisisSentinelOnlineModelChoice?,
        onDelta: (accumulated: String) -> Unit
    ): CrisisSentinelOnlineStreamResult {
        val messagesJson = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            messagesJson.put(
                JSONObject()
                    .put("sender", message.sender)
                    .put("text", message.text.take(MAX_MESSAGE_CHARS))
            )
        }
        val body = JSONObject()
            .put("messages", messagesJson)
            .put("context", JSONObject())
        if (modelChoice != null) {
            body.put("providerId", modelChoice.providerId)
            body.put("model", modelChoice.modelId)
        }
        val request = Request.Builder()
            .url(endpoint("api/chat/stream"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return executeCall(request) { response -> readStream(response, onDelta) }
    }

    /** Enqueues [request] and runs [handler] on OkHttp's thread with coroutine cancellation. */
    private suspend fun <T> executeCall(
        request: Request,
        handler: (Response) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                continuation.resumeWithException(
                    CrisisSentinelOnlineException(
                        kind = CrisisSentinelOnlineException.Kind.Network,
                        message = e.message ?: "Network failure"
                    )
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = runCatching { response.use(handler) }
                if (!continuation.isActive) return
                outcome
                    .onSuccess(continuation::resume)
                    .onFailure { throwable ->
                        continuation.resumeWithException(
                            throwable as? CrisisSentinelOnlineException
                                ?: CrisisSentinelOnlineException(
                                    kind = CrisisSentinelOnlineException.Kind.Network,
                                    message = throwable.message ?: "Request failed"
                                )
                        )
                    }
            }
        })
    }

    private fun readStream(
        response: Response,
        onDelta: (String) -> Unit
    ): CrisisSentinelOnlineStreamResult {
        if (!response.isSuccessful) {
            val detail = runCatching {
                JSONObject(response.body?.string().orEmpty()).optString("error")
            }.getOrNull().orEmpty()
            throw mapStatus(response.code, detail.ifBlank { "HTTP ${response.code}" })
        }
        val source = response.body?.source()
            ?: throw CrisisSentinelOnlineException(
                kind = CrisisSentinelOnlineException.Kind.Server,
                message = "Empty response body"
            )

        val accumulated = StringBuilder()
        var providerIdFromMetadata: String? = null
        var eventName = ""
        val dataLines = mutableListOf<String>()

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    val data = dataLines.joinToString("\n")
                    dataLines.clear()
                    val name = eventName
                    eventName = ""
                    when (name) {
                        "metadata" -> {
                            providerIdFromMetadata = runCatching {
                                JSONObject(data).optJSONObject("_provider")?.optString("id")
                            }.getOrNull()?.takeIf { it.isNotBlank() }
                        }

                        "text_delta" -> {
                            val delta = runCatching { JSONObject(data).optString("delta") }
                                .getOrDefault("")
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                                onDelta(accumulated.toString())
                            }
                        }

                        "done" -> return finishFromDone(
                            data = data,
                            streamedText = accumulated.toString().trim(),
                            providerIdFromMetadata = providerIdFromMetadata
                        )

                        "error" -> {
                            val json = runCatching { JSONObject(data) }.getOrNull()
                            val status = json?.optInt("status", 0)?.takeIf { it > 0 }
                            throw mapStatus(
                                status ?: 0,
                                json?.optString("error").orEmpty().ifBlank { "Stream error" }
                            )
                        }
                        // "status" phases and "tool_call"/"tool_result" notifications are
                        // informational; the substance arrives as text and the done payload.
                    }
                }

                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
            }
        }

        // EOF without a `done` event: keep whatever streamed rather than discarding a
        // mostly-complete reply over a dropped connection tail.
        val text = accumulated.toString().trim()
        if (text.isNotEmpty()) return CrisisSentinelOnlineStreamResult(text = text)
        throw CrisisSentinelOnlineException(
            kind = CrisisSentinelOnlineException.Kind.Network,
            message = "Stream ended before any reply"
        )
    }

    private fun finishFromDone(
        data: String,
        streamedText: String,
        providerIdFromMetadata: String?
    ): CrisisSentinelOnlineStreamResult {
        val payload = runCatching { JSONObject(data).optJSONObject("data") }.getOrNull()
        if (payload?.optBoolean("needsApiKey") == true) {
            throw CrisisSentinelOnlineException(
                kind = CrisisSentinelOnlineException.Kind.NotConfigured,
                message = "Panel has no AI provider configured"
            )
        }

        val candidate = payload?.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")

        val partText = StringBuilder()
        val mapPoints = mutableListOf<CrisisSentinelMapPoint>()
        val unsupportedTools = mutableListOf<String>()
        for (index in 0 until (parts?.length() ?: 0)) {
            val part = parts?.optJSONObject(index) ?: continue
            part.optString("text").takeIf { it.isNotBlank() }?.let(partText::append)
            val functionCall = part.optJSONObject("functionCall") ?: continue
            val toolName = functionCall.optString("name")
            if (toolName == TOOL_SHOW_LOCATION_ON_MAP) {
                mapPoints += parseMapPoints(functionCall.optJSONObject("args"))
            } else if (toolName.isNotBlank()) {
                unsupportedTools += toolName
            }
        }

        val text = streamedText.ifEmpty { partText.toString().trim() }
        val cardJson = payload?.optJSONObject("_card")?.toString()
        if (text.isEmpty() && cardJson == null && mapPoints.isEmpty() && unsupportedTools.isEmpty()) {
            throw CrisisSentinelOnlineException(
                kind = CrisisSentinelOnlineException.Kind.Server,
                message = "Stream finished without reply content"
            )
        }
        return CrisisSentinelOnlineStreamResult(
            text = text,
            modelName = payload?.optString("model")?.takeIf { it.isNotBlank() }
                ?: providerIdFromMetadata,
            cardJson = cardJson,
            mapPoints = mapPoints,
            unsupportedTools = unsupportedTools,
            groundingMetadataJson = candidate?.optJSONObject("groundingMetadata")?.toString()
        )
    }

    private fun parseMapPoints(args: JSONObject?): List<CrisisSentinelMapPoint> {
        val points = args?.optJSONArray("points") ?: return emptyList()
        return buildList {
            for (index in 0 until points.length()) {
                val point = points.optJSONObject(index) ?: continue
                val lat = point.optDouble("lat", Double.NaN)
                val lng = point.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                add(
                    CrisisSentinelMapPoint(
                        lat = lat,
                        lng = lng,
                        label = point.optString("label"),
                        details = point.optString("details").takeIf { it.isNotBlank() },
                        type = point.optString("type").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun parseProviders(body: String): List<CrisisSentinelOnlineProvider> {
        val providers = runCatching { JSONObject(body).optJSONArray("providers") }.getOrNull()
            ?: throw CrisisSentinelOnlineException(
                kind = CrisisSentinelOnlineException.Kind.Server,
                message = "Malformed providers response"
            )
        return buildList {
            for (index in 0 until providers.length()) {
                val provider = providers.optJSONObject(index) ?: continue
                val id = provider.optString("id").takeIf { it.isNotBlank() } ?: continue
                val models = buildList {
                    val modelsJson = provider.optJSONArray("models")
                    for (modelIndex in 0 until (modelsJson?.length() ?: 0)) {
                        val model = modelsJson?.optJSONObject(modelIndex) ?: continue
                        val modelId = model.optString("id").takeIf { it.isNotBlank() } ?: continue
                        add(
                            CrisisSentinelOnlineModelInfo(
                                id = modelId,
                                label = model.optString("label").ifBlank { modelId },
                                contextWindow = model.optLong("contextWindow", -1L)
                                    .takeIf { it > 0L }
                            )
                        )
                    }
                }
                add(
                    CrisisSentinelOnlineProvider(
                        id = id,
                        label = provider.optString("label").ifBlank { id },
                        defaultModel = provider.optString("defaultModel")
                            .takeIf { it.isNotBlank() },
                        source = provider.optString("source"),
                        models = models
                    )
                )
            }
        }
    }

    private fun mapStatus(status: Int, message: String): CrisisSentinelOnlineException {
        val kind = when (status) {
            401 -> CrisisSentinelOnlineException.Kind.Unauthorized
            403 -> CrisisSentinelOnlineException.Kind.Forbidden
            429 -> CrisisSentinelOnlineException.Kind.Quota
            else -> CrisisSentinelOnlineException.Kind.Server
        }
        return CrisisSentinelOnlineException(kind = kind, httpStatus = status, message = message)
    }

    private companion object {
        // Client-side truncation keeps requests inside the server's validation limits
        // (50 messages / 20k chars per message) with margin.
        const val MAX_MESSAGES = 30
        const val MAX_MESSAGE_CHARS = 20_000
        const val TOOL_SHOW_LOCATION_ON_MAP = "showLocationOnMap"
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
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
