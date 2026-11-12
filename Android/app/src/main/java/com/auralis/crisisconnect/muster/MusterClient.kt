package com.auralis.crisisconnect.muster

import android.content.Context
import androidx.work.*
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object MusterClient {
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { PinnedOkHttpClient.newClient().newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build() }
    private val voiceHttp by lazy { http.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build() }
    private var store: KeystoreBackedPreferences? = null
    private var queueInstance: MusterQueue? = null
    private var started = false
    private val sendLock = Mutex()
    private val languageLock = Mutex()
    private val syncedLanguages = mutableMapOf<String, String>()
    @Synchronized private fun storage(context: Context): KeystoreBackedPreferences = store ?: KeystoreBackedPreferences(context.applicationContext, "personnel_muster_v1", "personnel_muster_key_v1").also { store = it }
    @Synchronized fun queue(context: Context): MusterQueue = queueInstance ?: MusterQueue(object : MusterPersistence {
        override fun read(key: String) = storage(context).getString(key)
        override fun write(key: String, value: String) = storage(context).putStringCommitted(key, value)
    }, musterVoiceStorage(context.applicationContext)).also { queueInstance = it }
    private fun cacheKey(uid: String) = "assignments-" + java.security.MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun cached(context: Context, uid: String): List<JSONObject> {
        val array = JSONArray(storage(context).getString(cacheKey(uid)) ?: "[]")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    @Synchronized private fun cache(context: Context, uid: String, panel: String, received: List<JSONObject>) {
        val previous = cached(context, uid).associateBy { it.getString("panelId") + ":" + it.getJSONObject("session").getString("id") }.toMutableMap()
        received.forEach { item -> item.put("panelId", panel); previous[panel + ":" + item.getJSONObject("session").getString("id")] = item }
        check(storage(context).putStringCommitted(cacheKey(uid), JSONArray(previous.values.toList().takeLast(100)).toString()))
        revision.update { it + 1 }
    }
    fun cacheWake(context: Context, uid: String, panel: String, session: String, kind: String = "live", configured: Boolean = false) {
        require(kind == "live" || kind == "drill")
        val localized = musterLocalizedContext(context)
        if (cached(context, uid).any { it.optString("panelId") == panel && it.getJSONObject("session").optString("id") == session }) return
        cache(context, uid, panel, listOf(JSONObject().put("receivedAtMillis", System.currentTimeMillis()).put("session", JSONObject().put("id", session).put("kind", kind).put("status", "active").put("responseConfigPending", configured)
            .put("title", localized.getString(com.auralis.crisisconnect.R.string.muster_title)).put("instructions", localized.getString(com.auralis.crisisconnect.R.string.muster_connect_instructions)))))
    }
    fun start(context: Context) {
        if (started) return; started = true
        (context.applicationContext as? android.app.Application)?.let(MusterPresentation::trackForeground)
        FirebaseAuth.getInstance().addAuthStateListener { auth -> auth.currentUser?.takeUnless { it.isAnonymous }?.let { user ->
            scope.launch { runCatching { schedule(context, user.uid) } }
        } }
    }
    private fun endpoint() = (BuildConfig.MOBILE_SYNC_BASE_URL.trim().ifBlank { "https://crisisconnect.network" }).toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.newBuilder()?.addPathSegments("api/mobile/muster")?.build()
        ?: error("muster-invalid-endpoint")
    private suspend fun request(uid: String, panel: String, session: String? = null, after: String? = null, event: MusterEvent? = null, language: String? = null, voiceData: String? = null): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser?.takeIf { it.uid == uid && !it.isAnonymous } ?: error("muster-account-changed")
        val url = endpoint().newBuilder().apply { if (event == null && language == null) { addQueryParameter("panelId", panel); session?.let { addQueryParameter("sessionId", it) }; after?.let { addQueryParameter("after", it) } } }.build()
        suspend fun send(force: Boolean): Pair<Int, JSONObject?> {
            val token = user.getIdToken(force).await().token ?: error("muster-token-unavailable")
            check(FirebaseAuth.getInstance().currentUser?.uid == uid)
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            if (event != null) builder.post(event.request(voiceData).toString().toRequestBody("application/json".toMediaType()))
            else if (language != null) builder.post(JSONObject().put("action", "set_language").put("panelId", panel).put("language", language).toString().toRequestBody("application/json".toMediaType()))
            return (if (event?.voice != null) voiceHttp else http).newCall(builder.build()).execute().use { response -> response.code to runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull() }
        }
        val first = send(false); if (first.first == 401) send(true) else first
    }
    suspend fun refresh(context: Context, explicitPanel: String? = null, session: String? = null) {
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous } ?: error("muster-sign-in-required")
        val profile = if (explicitPanel == null) FirebaseFirestore.getInstance().collection("users").document(user.uid).get().await() else null
        val panel = explicitPanel ?: listOf(profile?.getString("agencySlug"), profile?.getString("panelId"), BuildConfig.MOBILE_SYNC_PANEL_ID).firstOrNull { !it.isNullOrBlank() } ?: error("muster-no-membership")
        var after: String? = null; val seen = mutableSetOf<String>()
        do {
            val (code, data) = request(user.uid, panel, session, after)
            check(code in 200..299 && data != null) { "muster-refresh-failed" }
            check(FirebaseAuth.getInstance().currentUser?.uid == user.uid)
            val received = if (session != null) listOf(data) else data.optJSONArray("records")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: error("muster-invalid-reply")
            cache(context, user.uid, panel, received)
            after = if (session == null) data.optString("nextCursor").takeUnless { it.isBlank() || it == "null" } else null
            if (after != null) check(seen.add(after) && seen.size <= 20) { "muster-more-pages" }
        } while (after != null)
        scope.launch { synchronizeLanguagePreference(context, panel) }
    }
    /** Cosmetic account preference; no polling and no entry in the safety-response queue. */
    suspend fun synchronizeLanguagePreference(context: Context, panel: String) = languageLock.withLock {
        val uid = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }?.uid ?: return@withLock
        val code = musterLocalizedContext(context).resources.configuration.locales[0].language
        val language = if (code == "tl") "fil" else code
        if (syncedLanguages[uid] == language) return@withLock
        runCatching {
            val (status, receipt) = request(uid, panel, language = language)
            if (status in 200..299 && receipt?.optBoolean("ok") == true && receipt.optString("language") == language && FirebaseAuth.getInstance().currentUser?.uid == uid) syncedLanguages[uid] = language
        }
        Unit
    }
    fun enqueue(context: Context, uid: String, panel: String, session: String, answer: String, note: String, operationId: String? = null) {
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        val savedSession = cached(context, uid).firstOrNull { it.optString("panelId") == panel && it.optJSONObject("session")?.optString("id") == session }?.optJSONObject("session")
        check(savedSession?.optBoolean("responseConfigPending") != true)
        check(savedSession?.has("responseConfig") != true || savedSession.optJSONObject("responseConfig") != null)
        val config = savedSession?.optJSONObject("responseConfig")?.let(MusterResponseConfig::from)
        if (config != null) {
            val submission = MusterSubmission(answer, note, optionId = answer)
            queue(context).enqueueFlexible(uid, panel, session, config, submission, System.currentTimeMillis(), operationId ?: java.util.UUID.randomUUID().toString())
        } else if (operationId == null) queue(context).enqueue(uid, panel, session, answer, note, System.currentTimeMillis())
        else queue(context).enqueue(uid, panel, session, answer, note, System.currentTimeMillis(), operationId)
        revision.update { it + 1 }
        schedule(context, uid)
    }
    fun enqueue(context: Context, uid: String, row: MusterCardModel, submission: MusterSubmission, operationId: String) {
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        check(row.configurationAvailable)
        val config = row.responseConfig
        if (config == null) {
            require(submission.optionId == null && submission.voice == null)
            queue(context).enqueue(uid, row.panel, row.session, submission.answer, submission.note, System.currentTimeMillis(), operationId)
        } else queue(context).enqueueFlexible(uid, row.panel, row.session, config, submission, System.currentTimeMillis(), operationId)
        revision.update { it + 1 }; schedule(context, uid)
    }
    suspend fun loadVoice(context: Context, uid: String, row: MusterCardModel): MusterVoiceDraft = withContext(Dispatchers.IO) {
        fun checkAccount() { check(FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }?.uid == uid) }
        checkAccount()
        row.localVoice?.let { ref ->
            val bytes = musterVoiceStorage(context.applicationContext).read(uid, ref)
            checkAccount(); return@withContext MusterVoiceDraft(bytes, ref.durationMillis)
        }
        val voice = checkNotNull(row.remoteVoice)
        val user = checkNotNull(FirebaseAuth.getInstance().currentUser)
        val url = endpoint().newBuilder().addPathSegment("voice").addQueryParameter("panelId", row.panel)
            .addQueryParameter("sessionId", row.session).addQueryParameter("eventId", voice.eventId).build()
        suspend fun fetch(force: Boolean): Pair<Int, ByteArray?> {
            val token = user.getIdToken(force).await().token ?: error("muster-token-unavailable")
            checkAccount()
            return voiceHttp.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { response ->
                if (response.code != 200) return@use response.code to null
                val body = checkNotNull(response.body)
                require(body.contentLength() <= MUSTER_VOICE_MAX_BYTES)
                val bytes = body.byteStream().use { stream ->
                    val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; require(result.size() + count <= MUSTER_VOICE_MAX_BYTES); result.write(buffer, 0, count) }
                    result.toByteArray()
                }
                200 to bytes
            }
        }
        val first = fetch(false); val result = if (first.first == 401) fetch(true) else first
        checkAccount(); check(result.first == 200)
        val bytes = checkNotNull(result.second); require(bytes.size == voice.bytes && musterHash(bytes) == voice.sha256)
        MusterVoiceDraft(bytes, voice.durationMillis)
    }
    fun schedule(context: Context, uid: String, runningRetryAt: Long = 0L): Operation? =
        scheduleMusterSendWork(context, MusterSendWorker::class.java, queue(context), uid, runningRetryAt)
    suspend fun drain(context: Context, uid: String): Boolean = sendLock.withLock {
        drainMusterQueue(queue(context), uid,
            currentAccount = { FirebaseAuth.getInstance().currentUser?.uid },
            transport = { event -> request(uid, event.panel, event = event, voiceData = queue(context).voiceBytes(event)?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }) },
            changed = { revision.update { it + 1 } })
    }
}

class MusterSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runMusterSendWork(inputData,
        drain = { uid -> MusterClient.drain(applicationContext, uid) },
        schedule = { uid, retryAt -> MusterClient.schedule(applicationContext, uid, retryAt) })
}
