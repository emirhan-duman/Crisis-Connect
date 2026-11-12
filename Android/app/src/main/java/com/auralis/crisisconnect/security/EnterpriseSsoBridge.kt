package com.auralis.crisisconnect.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject

/**
 * Bridges the V2 verified HTTPS App Link (caught by MainActivity after the web SSO
 * flow) to ProfileViewModel, which exchanges the one-time code for a Firebase custom token.
 */
object EnterpriseSsoBridge {
    sealed interface Result {
        data class Code(val code: String, val state: String, val verifier: String) : Result
        data class Error(val reason: String?) : Result
    }

    data class Attempt(val state: String, val verifier: String) {
        val challenge: String get() = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
    private val queue = Channel<Result>(4)
    val results = queue.receiveAsFlow()
    private fun store(context: Context) = KeystoreBackedPreferences(context, "cc_sso_attempts_v2", "cc_sso_attempts_v2")

    @Synchronized
    fun begin(context: Context): Attempt {
        fun random(size: Int): String = ByteArray(size).also(SecureRandom()::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val attempt = Attempt(random(32), random(64))
        val json = JSONObject().put("state", attempt.state).put("verifier", attempt.verifier)
            .put("expiresAt", System.currentTimeMillis() + 600_000L)
        check(store(context).putStringCommitted("pending", json.toString()))
        return attempt
    }

    @Synchronized
    fun handleCallback(context: Context, code: String?, state: String?) {
        val prefs = store(context)
        val raw = prefs.getStringStrict("pending", "")
        val pending = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val expires = pending.optLong("expiresAt")
        if (state == null || state != pending.optString("state") || expires <= System.currentTimeMillis()) return
        val result = if (code != null && code.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            Result.Code(code, state, pending.getString("verifier"))
        } else Result.Error("sso-failed")
        check(prefs.putStringCommitted("pending", null))
        queue.trySend(result)
    }
}
