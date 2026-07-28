package com.auralis.crisisconnect.messaging.call

import org.json.JSONArray
import org.json.JSONObject

/**
 * One ICE server parsed from the dashboard's `/api/turn-credentials` response. Deliberately free of
 * any Android/WebRTC types so the parsing is a plain JVM unit test (see TurnCredentialsParseTest).
 */
data class IceServerSpec(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object TurnCredentials {
    /**
     * Parse the `/api/turn-credentials` JSON body into ICE server specs.
     *
     * Body shape: `{ "iceServers": [ { "urls": [..]|"..", "username"?, "credential"? }, .. ], "ttl": n }`.
     * Tolerates `iceServers` being a single object, `urls` being a string or array, and absent creds.
     * Returns an empty list on any malformed/empty input — the caller then uses STUN-only.
     */
    fun parse(json: String): List<IceServerSpec> {
        return try {
            val raw = JSONObject(json).opt("iceServers")
            val arr = when (raw) {
                is JSONArray -> raw
                is JSONObject -> JSONArray().put(raw)
                else -> return emptyList()
            }
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val urls = parseUrls(obj.opt("urls"))
                if (urls.isEmpty()) return@mapNotNull null
                IceServerSpec(
                    urls = urls,
                    username = obj.optString("username").ifEmpty { null },
                    credential = obj.optString("credential").ifEmpty { null },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseUrls(raw: Any?): List<String> = when (raw) {
        is String -> if (raw.isNotEmpty()) listOf(raw) else emptyList()
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).ifEmpty { null } }
        else -> emptyList()
    }
}
