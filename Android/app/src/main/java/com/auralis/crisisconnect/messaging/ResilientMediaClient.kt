package com.auralis.crisisconnect.messaging

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseApp
import java.io.IOException
import java.util.UUID
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object ResilientMediaClient {
    private val policy = RegionalRoutePolicy()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).build()

    suspend fun uploadAvatar(bytes: ByteArray, generation: String): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Not signed in")
        val stable = runCatching { UUID.fromString(generation) }.getOrElse { UUID.nameUUIDFromBytes(generation.toByteArray()) }
        val result = request("users/$uid/avatars/$stable.jpg", "PUT", bytes)
        return JSONObject(String(result, Charsets.UTF_8)).getString("downloadUrl")
    }
    suspend fun uploadAttachment(path: String, bytes: ByteArray) { request(path, "PUT", bytes) }
    suspend fun downloadAttachment(path: String): ByteArray = request(path, "GET")
    suspend fun delete(path: String) { request(path, "DELETE") }
    fun avatarPath(url: String): String? = runCatching {
        val uri = URI(url)
        val prefix = "/v0/b/crisis-connect-1-media-eu/o/"
        if (uri.host != "firebasestorage.googleapis.com" || !uri.rawPath.startsWith(prefix)) null
        else URLDecoder.decode(uri.rawPath.removePrefix(prefix), "UTF-8").takeIf { "/avatars/" in it }
    }.getOrNull()

    internal suspend fun request(path: String, method: String, bytes: ByteArray? = null,
        app: FirebaseApp = FirebaseApp.getInstance(), route: RegionalRoutePolicy = policy): ByteArray = withContext(Dispatchers.IO) {
        require(path.startsWith("users/") || path.startsWith("authorityMessageAttachments/"))
        val auth = FirebaseAuth.getInstance(app)
        val uid = auth.currentUser?.uid ?: error("Not signed in")
        val token = auth.currentUser!!.getIdToken(false).await().token ?: error("Missing authentication token")
        val appCheck = FirebaseAppCheck.getInstance(app).getAppCheckToken(false).await().token
        fun attempt(region: String): ByteArray {
            check(auth.currentUser?.uid == uid) { "Account changed" }
            val name = if (region == RegionalRoutePolicy.EUROPE) "applicationMediaEurope" else "applicationMediaUS"
            val url = HttpUrl.Builder().scheme("https").host("$region-crisis-connect-1.cloudfunctions.net")
                .addPathSegment(name).addQueryParameter("path", path).build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .header("X-Firebase-AppCheck", appCheck)
                .method(method, bytes?.toRequestBody("application/octet-stream".toMediaType())).build()
            return http.newCall(request).execute().use { response ->
                if (response.code >= 500) throw IOException("Media region unavailable")
                check(response.isSuccessful) { "Media request rejected: ${response.code}" }
                val body = response.body ?: error("Empty media response")
                check(body.contentLength() <= 25 * 1024 * 1024 + 4096) { "Media response exceeds size limit" }
                val data = body.bytes()
                check(data.size <= 25 * 1024 * 1024 + 4096 && auth.currentUser?.uid == uid)
                data
            }
        }
        val first = route.preferred()
        try { attempt(first) } catch (error: IOException) {
            val alternate = route.alternate(first) ?: throw error
            route.failed(first)
            attempt(alternate)
        }
    }
}
