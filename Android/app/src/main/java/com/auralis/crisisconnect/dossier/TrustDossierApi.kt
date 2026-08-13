package com.auralis.crisisconnect.dossier

import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DossierComponent(
    val id: String,
    val fileName: String,
    val mediaType: String,
    val bytes: Long,
    val sha256: String,
)

data class DossierPolicy(
    val status: String,
    val jurisdiction: String,
    val policyId: String?,
    val signatureRequirement: String,
    val deliveryReceipt: String,
)

data class TrustDossier(
    val dossierId: String,
    val title: String,
    val description: String,
    val purpose: String,
    val classification: String,
    val state: String,
    val revision: Int,
    val policy: DossierPolicy,
    val retentionClass: String,
    val components: List<DossierComponent>,
    val manifestSha256: String?,
)

data class PolicyPackContent(
    val signatureRequirement: String,
    val organizationSeal: String,
    val signerRoles: List<String>,
    val deliveryReceipt: String,
    val retentionClass: String,
    val retentionDays: Int,
    val filePlanCode: String?,
)

data class TrustDossierPolicyPack(
    val packId: String,
    val status: String,
    val name: String,
    val jurisdiction: String,
    val purpose: String,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val content: PolicyPackContent,
)

class TrustDossierApiException(val status: Int?, message: String) : IOException(message)

class TrustDossierApi(
    baseUrl: String = BuildConfig.TRUST_DOSSIER_BASE_URL.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL },
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    client: OkHttpClient = PinnedOkHttpClient.newClient(),
) {
    private val base: HttpUrl = baseUrl.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        ?: throw IllegalArgumentException("Secure dossier service URL must be HTTPS")
    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun list(): Pair<List<TrustDossier>, List<TrustDossierPolicyPack>> =
        listDossiers() to listPolicyPacks()

    suspend fun create(
        title: String,
        description: String,
        purpose: String,
        classification: String,
        jurisdiction: String,
        filePlanCode: String?,
    ): TrustDossier {
        val dossier = JSONObject()
            .put("title", title.trim())
            .put("description", description.trim())
            .put("purpose", purpose)
            .put("classification", classification)
            .put("jurisdiction", jurisdiction.trim().uppercase())
            .put("source", JSONObject().put("kind", "standalone")
                .put("systemKey", JSONObject.NULL).put("integrationId", JSONObject.NULL)
                .put("externalId", JSONObject.NULL).put("externalVersion", JSONObject.NULL))
            .put("recordPlan", JSONObject().put("filePlanCode", filePlanCode ?: JSONObject.NULL)
                .put("retentionClass", "policy_pending"))
        return parseDossier(postJson(endpoint("api", "dashboard", "dossiers"),
            JSONObject().put("dossier", dossier), operationId()).getJSONObject("dossier"))
    }

    suspend fun applyPolicy(dossier: TrustDossier, pack: TrustDossierPolicyPack): TrustDossier {
        val retentionUntil = Instant.now().plus(pack.content.retentionDays.toLong(), ChronoUnit.DAYS).toString()
        val body = JSONObject()
            .put("action", "accept_policy")
            .put("expectedRevision", dossier.revision)
            .put("policyPackId", pack.packId)
            .put("policyId", pack.packId)
            .put("signatureRequirement", pack.content.signatureRequirement)
            .put("organizationSeal", pack.content.organizationSeal)
            .put("signerRoles", JSONArray(pack.content.signerRoles))
            .put("deliveryReceipt", pack.content.deliveryReceipt)
            .put("retentionClass", pack.content.retentionClass)
            .put("retentionUntil", retentionUntil)
            .put("filePlanCode", pack.content.filePlanCode ?: JSONObject.NULL)
        return mutate(dossier, body)
    }

    suspend fun freeze(dossier: TrustDossier): TrustDossier = mutate(
        dossier,
        JSONObject().put("action", "freeze").put("expectedRevision", dossier.revision),
    )

    suspend fun upload(
        dossier: TrustDossier,
        bytes: ByteArray,
        fileName: String,
        mediaType: String,
    ): TrustDossier {
        require(bytes.size <= MAX_UPLOAD_BYTES) { "file-too-large" }
        require(mediaType in ALLOWED_MEDIA_TYPES) { "unsupported-media-type" }
        val safeName = fileName.replace(Regex("[\\p{Cc}\\\\\"]"), "_").take(180)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("operationId", operationId())
            .addFormDataPart("expectedRevision", dossier.revision.toString())
            .addFormDataPart("document", safeName, bytes.toRequestBody(mediaType.toMediaType()))
            .build()
        val response = authenticated { token ->
            Request.Builder().url(endpoint("api", "dashboard", "dossiers", dossier.dossierId, "components"))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(multipart)
                .build()
        }
        return parseDossier(response.getJSONObject("dossier"))
    }

    private suspend fun listDossiers(): List<TrustDossier> {
        val url = endpoint("api", "dashboard", "dossiers").newBuilder()
            .addQueryParameter("limit", "60").build()
        val root = authenticated { token -> Request.Builder().url(url)
            .header("Authorization", "Bearer $token").header("Accept", "application/json").get().build() }
        return root.getJSONArray("dossiers").objects().map(::parseDossier)
    }

    private suspend fun listPolicyPacks(): List<TrustDossierPolicyPack> {
        val root = authenticated { token -> Request.Builder()
            .url(endpoint("api", "dashboard", "dossier-policy-packs"))
            .header("Authorization", "Bearer $token").header("Accept", "application/json").get().build() }
        return root.getJSONArray("policyPacks").objects().map(::parsePolicyPack)
    }

    private suspend fun mutate(dossier: TrustDossier, body: JSONObject): TrustDossier =
        parseDossier(postJson(endpoint("api", "dashboard", "dossiers", dossier.dossierId),
            body, operationId()).getJSONObject("dossier"))

    private suspend fun postJson(url: HttpUrl, body: JSONObject, idempotencyKey: String): JSONObject =
        authenticated { token -> Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("x-cc-idempotency-key", idempotencyKey)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build() }

    private suspend fun authenticated(build: (String) -> Request): JSONObject {
        val user = auth.currentUser?.takeUnless { it.isAnonymous }
            ?: throw TrustDossierApiException(401, "Sign in with an authorized institutional account")
        suspend fun token(force: Boolean): String = user.getIdToken(force).await().token
            ?.trim()?.takeIf(String::isNotEmpty)
            ?: throw TrustDossierApiException(401, "Could not obtain a secure session")
        return try {
            execute(build(token(false)))
        } catch (rejected: TrustDossierApiException) {
            if (rejected.status != 401) throw rejected
            execute(build(token(true)))
        }
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                throw TrustDossierApiException(response.code,
                    json.optString("error").ifBlank { "Secure dossier request rejected (${response.code})" })
            }
            json
        }
    }

    private fun endpoint(vararg path: String): HttpUrl = base.newBuilder().apply {
        path.forEach(::addPathSegment)
    }.build()

    private fun parseDossier(json: JSONObject): TrustDossier {
        val policy = json.getJSONObject("policy")
        val recordPlan = json.getJSONObject("recordPlan")
        return TrustDossier(
            dossierId = json.getString("dossierId"),
            title = json.getString("title"),
            description = json.optString("description"),
            purpose = json.getString("purpose"),
            classification = json.getString("classification"),
            state = json.getString("state"),
            revision = json.getInt("revision"),
            policy = DossierPolicy(
                status = policy.getString("status"),
                jurisdiction = policy.getString("jurisdiction"),
                policyId = policy.nullableString("policyId"),
                signatureRequirement = policy.getString("signatureRequirement"),
                deliveryReceipt = policy.getString("deliveryReceipt"),
            ),
            retentionClass = recordPlan.getString("retentionClass"),
            components = json.getJSONArray("components").objects().map { component ->
                DossierComponent(component.getString("id"), component.getString("fileName"),
                    component.getString("mediaType"), component.getLong("bytes"), component.getString("sha256"))
            },
            manifestSha256 = json.nullableString("manifestSha256"),
        )
    }

    private fun parsePolicyPack(json: JSONObject): TrustDossierPolicyPack {
        val content = json.getJSONObject("content")
        return TrustDossierPolicyPack(
            packId = json.getString("packId"), status = json.getString("status"),
            name = json.getString("name"), jurisdiction = json.getString("jurisdiction"),
            purpose = json.getString("purpose"), effectiveFrom = Instant.parse(json.getString("effectiveFrom")),
            effectiveUntil = json.nullableString("effectiveUntil")?.let(Instant::parse),
            content = PolicyPackContent(
                signatureRequirement = content.getString("signatureRequirement"),
                organizationSeal = content.getString("organizationSeal"),
                signerRoles = content.getJSONArray("signerRoles").strings(),
                deliveryReceipt = content.getString("deliveryReceipt"),
                retentionClass = content.getString("retentionClass"),
                retentionDays = content.getInt("retentionDays"),
                filePlanCode = content.nullableString("filePlanCode"),
            ),
        )
    }

    private fun operationId(): String = "android:${UUID.randomUUID()}"

    companion object {
        const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
        val ALLOWED_MEDIA_TYPES = setOf("application/pdf", "image/png", "image/jpeg")
        private const val DEFAULT_BASE_URL = "https://crisisconnect.network"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
