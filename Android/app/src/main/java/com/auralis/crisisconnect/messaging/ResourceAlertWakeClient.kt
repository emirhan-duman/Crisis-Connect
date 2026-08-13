package com.auralis.crisisconnect.messaging

import android.content.Context
import android.content.Intent
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

internal data class ResourceAlertWakePayload(
    val panelId: String,
    val attemptId: String,
    val receiptNonce: String,
)

private val RESOURCE_PANEL_ID = Regex("^[a-z0-9._-]{1,96}$")
private val RESOURCE_ATTEMPT_ID = Regex("^[A-Za-z0-9:_-]{1,128}$")
private val RESOURCE_RECEIPT_NONCE = Regex("^[A-Za-z0-9_-]{16,128}$")

internal fun parseResourceAlertWake(data: Map<String, String>): ResourceAlertWakePayload? {
    if (data["type"] != "resource_alert_wake") return null
    val panelId = data["panelId"]?.trim().orEmpty()
    val attemptId = data["attemptId"]?.trim().orEmpty()
    val receiptNonce = data["receiptNonce"]?.trim().orEmpty()
    if (!RESOURCE_PANEL_ID.matches(panelId) || !RESOURCE_ATTEMPT_ID.matches(attemptId) ||
        !RESOURCE_RECEIPT_NONCE.matches(receiptNonce)
    ) return null
    return ResourceAlertWakePayload(panelId, attemptId, receiptNonce)
}

internal fun buildResourceAlertWakeAck(payload: ResourceAlertWakePayload): JSONObject = JSONObject()
    .put("panelId", payload.panelId)
    .put("action", "ackWake")
    .put("attemptId", payload.attemptId)
    .put("receiptNonce", payload.receiptNonce)
    .put("source", "native")

internal object ResourceAlertWakeClient {
    private const val DEFAULT_BASE_URL = "https://crisisconnect.network"
    private const val REFRESH_ACTION = "com.auralis.crisisconnect.RESOURCE_ALERT_INBOX_REFRESH"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun acknowledge(context: Context, data: Map<String, String>): Boolean {
        val payload = parseResourceAlertWake(data) ?: return false
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous } ?: return false
        val queue = ResourceAlertWakeQueue.shared(context)
        val now = System.currentTimeMillis()
        val key = queue.enqueue(payload, user.uid, now) ?: return false
        ResourceAlertWakeAckWorker.enqueue(context)
        val claimed = queue.claim(user.uid, now, key) ?: return false
        val acknowledged = deliver(user.uid, context, claimed)
        if (queue.hasPending(user.uid, System.currentTimeMillis())) {
            ResourceAlertWakeAckWorker.enqueue(context)
        }
        return acknowledged
    }

    suspend fun drainPending(context: Context, maximumItems: Int = 8): Boolean {
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous } ?: return false
        val queue = ResourceAlertWakeQueue.shared(context)
        var processed = 0
        while (processed < maximumItems.coerceIn(1, 32)) {
            val claimed = queue.claim(user.uid, System.currentTimeMillis()) ?: break
            deliver(user.uid, context, claimed)
            processed += 1
        }
        return !queue.hasPending(user.uid, System.currentTimeMillis())
    }

    private suspend fun deliver(
        recipientUid: String,
        context: Context,
        pending: PendingResourceAlertWake,
    ): Boolean {
        val user = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { it.isAnonymous }
            ?.takeIf { it.uid == recipientUid }
            ?: return false
        val endpoint = endpointUrl() ?: return false
        val token = runCatching { user.getIdToken(false).await().token?.trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        var status = post(endpoint, token, pending.payload)
        if (status == 401) {
            val refreshed = runCatching { user.getIdToken(true).await().token?.trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return false
            status = post(endpoint, refreshed, pending.payload)
        }
        if (status in 200..299) {
            if (!ResourceAlertWakeQueue.shared(context).complete(
                    recipientUid,
                    pending.key,
                    System.currentTimeMillis(),
                )
            ) return false
            context.applicationContext.sendBroadcast(
                Intent(REFRESH_ACTION)
                    .setPackage(context.packageName)
                    .putExtra("panelId", pending.panelId)
            )
            return true
        }
        if (isPermanentFailure(status)) {
            ResourceAlertWakeQueue.shared(context).complete(
                recipientUid,
                pending.key,
                System.currentTimeMillis(),
            )
        }
        return false
    }

    private fun isPermanentFailure(status: Int): Boolean = status in 400..499 &&
        status !in setOf(401, 408, 425, 429)

    private suspend fun post(endpoint: HttpUrl, token: String, payload: ResourceAlertWakePayload): Int =
        withContext(Dispatchers.IO) {
            val body = buildResourceAlertWakeAck(payload).toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            runCatching {
                PinnedOkHttpClient.newClient().newCall(request).execute().use { it.code }
            }.getOrDefault(503)
        }

    private fun endpointUrl(): HttpUrl? {
        val base = BuildConfig.MOBILE_SYNC_BASE_URL.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val parsed = base.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps && !(BuildConfig.DEBUG && isLocalDebugHost(parsed.host))) return null
        return parsed.newBuilder()
            .addPathSegments("api/dashboard/resource-alert-inbox")
            .build()
    }

    private fun isLocalDebugHost(host: String): Boolean = host == "10.0.2.2" ||
        host == "127.0.0.1" || host == "localhost" || host.endsWith(".local")
}
