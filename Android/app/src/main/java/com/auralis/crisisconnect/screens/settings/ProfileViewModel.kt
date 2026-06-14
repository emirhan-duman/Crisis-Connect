package com.auralis.crisisconnect.screens.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.auralis.crisisconnect.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.database.AgencyRouter
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.data.profile.ProfilePhotoUploadWorker
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.CertificateProvisioningFlow
import com.auralis.crisisconnect.security.FirebaseAppCheckFailures
import com.auralis.crisisconnect.security.RescueDeviceRegistry
import com.auralis.crisisconnect.security.RoleCertificate
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.sync.MobileSyncClient
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.auralis.crisisconnect.security.EnterpriseSsoBridge
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * UI state of the profile screen.
 */
data class ProfileUiState(
    val username: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val country: String = "",
    val agency: String = "",
    val role: String = "user",
    val verified: Boolean = false,
    val profileBitmap: Bitmap? = null,
    val photoUrl: String? = null,
    val isLoading: Boolean = true,
    val isSignedIn: Boolean = false,
    val emailSignInError: String? = null
)

sealed class ProfileEvent {
    data class ShowMessage(val message: String) : ProfileEvent()
}

/**
 * UI representation of the device-bound role certificate. Owned by
 * [ProfileViewModel] so the profile screen and the certificate card share
 * a single source of truth (e.g. revoking the cert immediately clears the
 * role chip on the profile header).
 */
sealed class CertificateStatus {
    data object Loading : CertificateStatus()
    data object Missing : CertificateStatus()
    data class Loaded(
        val certificate: RoleCertificate,
        val isExpired: Boolean,
        val isRevoked: Boolean,
    ) : CertificateStatus()
    data class Failure(val message: String) : CertificateStatus()
}

data class CertificateUiState(
    val status: CertificateStatus = CertificateStatus.Loading,
    val provisioning: Boolean = false,
    val revoking: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val securityRepository: SecurityRepository = SecurityRepository(appContext)
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            profileBitmap = ProfileImageStorage.loadProfileImage(appContext)
        )
    )
    val uiState: StateFlow<ProfileUiState> = _uiState

    private val _certificateState = MutableStateFlow(CertificateUiState())
    val certificateState: StateFlow<CertificateUiState> = _certificateState.asStateFlow()

    private val eventChannel = Channel<ProfileEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var syncedAuthUid: String? = null
    private var pendingPhoneVerificationId: String? = null
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser?.takeUnless { it.isAnonymous }
        if (user == null) {
            syncedAuthUid = null
            _uiState.update {
                it.copy(
                    username = "",
                    isSignedIn = false,
                    isLoading = false,
                    email = "",
                    phoneNumber = "",
                    country = "",
                    agency = "",
                    role = "user",
                    verified = false,
                    emailSignInError = null
                )
            }
            return@AuthStateListener
        }

        val isAuthenticatedUser = isSignedInUser(user)
        LocalKeyStorage.saveUid(appContext, user.uid)
        _uiState.update { state ->
            state.copy(
                isSignedIn = isAuthenticatedUser,
                username = firstNonBlank(state.username, user.displayName),
                email = firstNonBlank(state.email, user.email),
                phoneNumber = firstNonBlank(state.phoneNumber, user.phoneNumber)
            )
        }
        if (syncedAuthUid != user.uid) {
            syncedAuthUid = user.uid
            syncRemoteIdentity(user.uid)
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
        loadProfile()
        observeLocalName()
        observeEnterpriseSsoBridge()
    }

    private fun observeLocalName() {
        viewModelScope.launch(exceptionHandler) {
            getSavedUserName(appContext).collect { savedName ->
                if (savedName.isBlank()) return@collect
                _uiState.update { state ->
                    if (state.username.isBlank()) {
                        state.copy(username = savedName)
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun loadProfile() {
        _uiState.update { it.copy(isLoading = true) }
        val authUser = auth.currentUser?.takeUnless { it.isAnonymous }
        val uid = authUser?.uid ?: LocalKeyStorage.getSavedUid(appContext)
        val hasAuthenticatedUser = isSignedInUser(authUser)
        val localRole = LocalKeyStorage.getSavedRole(appContext)
        val localCountry = normalizeCountryCode(LocalKeyStorage.getSavedCountry(appContext).first)
        if (uid == null) {
            _uiState.update {
                it.copy(isLoading = false, isSignedIn = false, email = "", phoneNumber = "")
            }
            return
        }

        if (!hasAuthenticatedUser) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isSignedIn = false,
                    role = resolveRole(localRole, remoteRole = null, fallbackRole = "user"),
                    country = firstNonBlank(it.country, localCountry),
                    email = "",
                    phoneNumber = ""
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isSignedIn = hasAuthenticatedUser,
                role = resolveRole(localRole, remoteRole = null, fallbackRole = "user")
            )
        }

        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { documentSnapshot ->
                val remoteRole = documentSnapshot.getString("role")
                val remoteCountry = normalizeCountryCode(documentSnapshot.getString("country"))
                val remoteAgency = documentSnapshot.getString("agency")
                val remoteAgencySlug = documentSnapshot.getString("agencySlug")
                val remoteAgencyKey = documentSnapshot.getString("agencyKey")
                val remoteUsername = documentSnapshot.getString("username")
                val remoteEmail = documentSnapshot.getString("email")
                val remotePhoneNumber = documentSnapshot.getString("phoneNumber")
                val remotePhotoUrl = documentSnapshot.getString("photoURL")?.trim()?.takeIf { it.isNotEmpty() }
                _uiState.update { state ->
                    val resolvedRole = resolveRole(localRole, remoteRole, state.role)
                    val resolvedEmail = firstNonBlank(
                        remoteEmail,
                        state.email,
                        authUser?.email
                    )
                    val resolvedPhoneNumber = firstNonBlank(
                        remotePhoneNumber,
                        state.phoneNumber,
                        authUser?.phoneNumber
                    )
                    val resolvedCountry = firstNonBlank(remoteCountry, state.country, localCountry)
                    val resolvedAgency = resolveAgency(
                        role = resolvedRole,
                        country = resolvedCountry,
                        email = resolvedEmail,
                        remoteAgency = remoteAgency,
                        fallbackAgency = state.agency
                    )
                    state.copy(
                        username = firstNonBlank(
                            remoteUsername,
                            state.username,
                            authUser?.displayName
                        ),
                        email = resolvedEmail,
                        phoneNumber = resolvedPhoneNumber,
                        country = resolvedCountry,
                        agency = resolvedAgency,
                        role = resolvedRole,
                        verified = documentSnapshot.getBoolean("verified") ?: false,
                        photoUrl = remotePhotoUrl ?: state.photoUrl,
                        isLoading = false,
                        isSignedIn = hasAuthenticatedUser
                    )
                }

                val resolvedState = _uiState.value
                syncRescueIdentityMetadataIfNeeded(
                    uid = uid,
                    role = resolvedState.role,
                    email = resolvedState.email,
                    country = resolvedState.country,
                    resolvedAgency = resolvedState.agency,
                    remoteEmail = remoteEmail,
                    remoteCountry = remoteCountry,
                    remoteAgency = remoteAgency,
                    remoteAgencySlug = remoteAgencySlug,
                    remoteAgencyKey = remoteAgencyKey
                )

                // If a local photo was previously cached but never made it to
                // the bucket (offline at pick time, or role granted later),
                // drain the queue now that we know the gate may have opened.
                val hasLocalPending = ProfileImageStorage.getProfileImageBase64(appContext) != null
                val remoteEmpty = remotePhotoUrl.isNullOrEmpty()
                if (hasLocalPending && remoteEmpty
                    && canSyncPhotoUpload(resolvedState.role, resolvedState.agency)) {
                    ProfilePhotoUploadWorker.enqueue(appContext)
                }
            }
            .addOnFailureListener {
                notifyMessage(appContext.getString(R.string.profile_load_error))
                _uiState.update { it.copy(isLoading = false, isSignedIn = hasAuthenticatedUser) }
            }
    }

    fun onProfileImageSelected(uri: Uri) {
        viewModelScope.launch(exceptionHandler) {
            val normalizedBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    ProfileImageStorage.decodeProfileImage(appContext, uri)?.also { bitmap ->
                        ProfileImageStorage.saveProfileImage(appContext, bitmap)
                    }
                }.onFailure { throwable ->
                    Log.w(TAG, "Failed to process selected profile image", throwable)
                }.getOrNull()
            }

            if (normalizedBitmap == null) {
                onProfileImageSelectionFailed()
                return@launch
            }

            _uiState.update { it.copy(profileBitmap = normalizedBitmap) }
            notifyMessage(appContext.getString(R.string.profile_photo_saved))

            val currentState = _uiState.value
            if (canSyncPhotoUpload(currentState.role, currentState.agency)) {
                ProfilePhotoUploadWorker.enqueue(appContext)
            }
        }
    }

    private fun canSyncPhotoUpload(role: String, agency: String): Boolean {
        val normalizedRole = role.trim().lowercase(Locale.ROOT)
        if (normalizedRole.isEmpty() || normalizedRole == "user") return false
        return agency.trim().isNotEmpty()
    }

    fun onProfileImageSelectionFailed() {
        notifyMessage(appContext.getString(R.string.profile_photo_error))
    }

    fun onGoogleSignInSuccess(idToken: String, displayName: String, email: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        _uiState.update { it.copy(isLoading = true) }

        val currentUser = auth.currentUser?.takeUnless { it.isAnonymous }
        if (currentUser == null) {
            authenticateWithGoogleCredential(credential, displayName, email)
            return
        }

        currentUser.linkWithCredential(credential)
            ?.addOnSuccessListener {
                val uid = auth.currentUser?.uid ?: currentUser.uid
                LocalKeyStorage.saveUid(appContext, uid)
                saveGoogleAccount(uid, displayName, email)
            }
            ?.addOnFailureListener { e ->
                if (e is FirebaseAuthUserCollisionException) {
                    authenticateWithGoogleCredential(credential, displayName, email)
                    return@addOnFailureListener
                }
                _uiState.update { it.copy(isLoading = false) }
                notifyMessage(
                    appContext.getString(
                        R.string.profile_google_link_failure,
                        e.message ?: appContext.getString(R.string.unknown_error)
                    )
                )
            }
    }

    fun updateUsername(newUsername: String, onResult: (Boolean) -> Unit = {}) {
        val trimmedName = newUsername.trim()
        if (trimmedName.isBlank()) {
            notifyMessage(appContext.getString(R.string.profile_username_required))
            onResult(false)
            return
        }

        val uid = auth.currentUser?.takeUnless { it.isAnonymous }?.uid
        if (uid == null) {
            notifyMessage(appContext.getString(R.string.profile_username_update_error))
            onResult(false)
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        firestore.collection("users").document(uid)
            .set(mapOf("username" to trimmedName), SetOptions.merge())
            .addOnSuccessListener {
                _uiState.update { state ->
                    state.copy(username = trimmedName, isLoading = false)
                }
                enqueueMobileProfileSync(platform = "profile_update")
                notifyMessage(appContext.getString(R.string.profile_username_update_success))
                onResult(true)
            }
            .addOnFailureListener {
                _uiState.update { it.copy(isLoading = false) }
                notifyMessage(appContext.getString(R.string.profile_username_update_error))
                onResult(false)
            }
    }

    fun signInWithEmailPassword(
        email: String,
        password: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) {
            notifyMessage(appContext.getString(R.string.profile_email_required))
            onResult(false)
            return
        }
        if (password.isBlank()) {
            notifyMessage(appContext.getString(R.string.profile_password_required))
            onResult(false)
            return
        }

        _uiState.update { it.copy(isLoading = true, emailSignInError = null) }
        auth.signInWithEmailAndPassword(normalizedEmail, password)
            .addOnSuccessListener { result ->
                val firebaseUser = result.user
                if (firebaseUser == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            emailSignInError = appContext.getString(R.string.profile_email_sign_in_error)
                        )
                    }
                    onResult(false)
                    return@addOnSuccessListener
                }
                LocalKeyStorage.saveUid(appContext, firebaseUser.uid)
                saveEmailPasswordAccount(
                    uid = firebaseUser.uid,
                    email = firstNonBlank(firebaseUser.email, normalizedEmail),
                    displayName = firstNonBlank(
                        firebaseUser.displayName,
                        _uiState.value.username,
                        normalizedEmail.substringBefore("@")
                    ),
                    onResult = onResult
                )
            }
            .addOnFailureListener { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        emailSignInError = resolveEmailSignInErrorMessage(error)
                    )
                }
                onResult(false)
            }
    }

    fun clearEmailSignInError() {
        _uiState.update { state ->
            if (state.emailSignInError == null) {
                state
            } else {
                state.copy(emailSignInError = null)
            }
        }
    }

    fun canApproveRole(role: String = _uiState.value.role): Boolean {
        val normalizedRole = role.trim().lowercase(Locale.US)
        return normalizedRole in APPROVER_ROLES
    }

    fun approveCurrentProfile() {
        if (_uiState.value.verified) {
            return
        }
        notifyMessage(appContext.getString(R.string.profile_verify_server_managed))
    }

    fun logout() {
        auth.signOut()
        LocalKeyStorage.clearUid(appContext)
        LocalKeyStorage.clearRole(appContext)
        _uiState.update {
            it.copy(
                username = "",
                email = "",
                phoneNumber = "",
                country = "",
                agency = "",
                role = "user",
                verified = false,
                isSignedIn = false,
                isLoading = false,
                emailSignInError = null
            )
        }
        notifyMessage(appContext.getString(R.string.profile_logout_success))
    }

    fun onGoogleSignInFailed(error: Throwable? = null) {
        _uiState.update { it.copy(isLoading = false) }
        val message = error?.localizedMessage
        if (!message.isNullOrBlank()) {
            notifyMessage(
                appContext.getString(
                    R.string.profile_google_sign_in_error_with_reason,
                    message
                )
            )
        } else {
            notifyMessage(appContext.getString(R.string.profile_google_sign_in_error))
        }
    }

    fun signInWithMicrosoft(activity: Activity) {
        signInWithOAuthProvider(
            activity = activity,
            providerId = MICROSOFT_PROVIDER_ID,
            platform = "microsoft",
            providerLabel = appContext.getString(R.string.profile_microsoft_provider_label),
            successMessageRes = R.string.profile_microsoft_sign_in_success,
            customParameters = mapOf("prompt" to "select_account")
        )
    }

    fun signInWithAppleProvider(activity: Activity) {
        signInWithOAuthProvider(
            activity = activity,
            providerId = APPLE_PROVIDER_ID,
            platform = "apple",
            providerLabel = appContext.getString(R.string.profile_apple_provider_label),
            successMessageRes = R.string.profile_apple_sign_in_success,
            scopes = listOf("email", "name")
        )
    }

    // Enterprise SSO = full web parity: open the dashboard's own custom-OIDC login page so adding a
    // tenant on the web works on mobile with no rebuild and no Firebase IdP registration. The web
    // callback returns a one-time code on the crisisconnect:// deep link, which we exchange for a
    // Firebase custom token.
    fun signInWithEnterpriseSso(activity: Activity) {
        val base = enterpriseSsoWebBase()
        _uiState.update { it.copy(isLoading = true, emailSignInError = null) }
        val localeCode = if (Locale.getDefault().language.equals("tr", ignoreCase = true)) "tr" else "en"
        val url = "$base/login?client=mobile&locale=$localeCode"
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            _uiState.update { it.copy(isLoading = false) }
            notifyMessage(appContext.getString(R.string.profile_enterprise_sso_not_configured))
        }
    }

    private fun observeEnterpriseSsoBridge() {
        viewModelScope.launch {
            EnterpriseSsoBridge.results.collect { result ->
                when (result) {
                    is EnterpriseSsoBridge.Result.Code -> completeEnterpriseSsoWithCode(result.code)
                    is EnterpriseSsoBridge.Result.Error -> handleProviderSignInFailure(
                        appContext.getString(R.string.profile_enterprise_sso_provider_label),
                        IllegalStateException(result.reason ?: "sso-failed")
                    )
                }
            }
        }
    }

    // Dashboard origin: gradle secret override, else the deployed default so SSO works out of the box.
    private fun enterpriseSsoWebBase(): String =
        BuildConfig.MOBILE_SYNC_BASE_URL.trim().trimEnd('/')
            .ifBlank { "https://crisis-connect-1.web.app" }

    private fun completeEnterpriseSsoWithCode(code: String) {
        val base = enterpriseSsoWebBase()
        if (code.isBlank()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) { exchangeEnterpriseSsoCode(base, code) }
            val providerLabel = appContext.getString(R.string.profile_enterprise_sso_provider_label)
            if (token.isNullOrBlank()) {
                handleProviderSignInFailure(providerLabel, IllegalStateException("sso-exchange-failed"))
                return@launch
            }
            auth.signInWithCustomToken(token)
                .addOnSuccessListener { result ->
                    val firebaseUser = result.user
                    if (firebaseUser == null) {
                        handleProviderSignInFailure(
                            providerLabel,
                            IllegalStateException(appContext.getString(R.string.profile_provider_user_missing))
                        )
                        return@addOnSuccessListener
                    }
                    LocalKeyStorage.saveUid(appContext, firebaseUser.uid)
                    saveProviderAccount(
                        firebaseUser = firebaseUser,
                        platform = "enterprise_sso",
                        successMessageRes = R.string.profile_enterprise_sso_sign_in_success
                    )
                }
                .addOnFailureListener { error ->
                    handleProviderSignInFailure(providerLabel, error)
                }
        }
    }

    private fun exchangeEnterpriseSsoCode(base: String, code: String): String? {
        return runCatching {
            val payload = JSONObject().put("code", code).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/auth/sso/mobile-token")
                .post(payload)
                .build()
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                JSONObject(body).optString("token").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun onAuthProviderUnavailable() {
        _uiState.update { it.copy(isLoading = false) }
        notifyMessage(appContext.getString(R.string.profile_auth_presenter_error))
    }

    fun startPhoneSignIn(
        activity: Activity,
        phoneNumber: String,
        onCodeSent: () -> Unit,
        onResult: (Boolean) -> Unit = {}
    ) {
        val normalizedPhoneNumber = phoneNumber.trim()
        if (!isLikelyE164PhoneNumber(normalizedPhoneNumber)) {
            notifyMessage(appContext.getString(R.string.profile_phone_invalid))
            onResult(false)
            return
        }

        _uiState.update { it.copy(isLoading = true, emailSignInError = null) }
        pendingPhoneVerificationId = null

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                pendingPhoneVerificationId = null
                signInWithPhoneCredential(credential, onResult)
            }

            override fun onVerificationFailed(error: FirebaseException) {
                _uiState.update { it.copy(isLoading = false) }
                notifyMessage(
                    appContext.getString(
                        R.string.profile_phone_sign_in_error_with_reason,
                        error.localizedMessage ?: appContext.getString(R.string.unknown_error)
                    )
                )
                onResult(false)
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                pendingPhoneVerificationId = verificationId
                _uiState.update { it.copy(isLoading = false) }
                notifyMessage(appContext.getString(R.string.profile_phone_code_sent))
                onCodeSent()
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(normalizedPhoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    fun confirmPhoneSignInCode(
        code: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        val verificationId = pendingPhoneVerificationId
        if (verificationId.isNullOrBlank()) {
            notifyMessage(appContext.getString(R.string.profile_phone_code_missing))
            onResult(false)
            return
        }
        val normalizedCode = code.trim()
        if (normalizedCode.length < MIN_SMS_CODE_LENGTH) {
            notifyMessage(appContext.getString(R.string.profile_phone_code_invalid))
            onResult(false)
            return
        }

        _uiState.update { it.copy(isLoading = true, emailSignInError = null) }
        val credential = PhoneAuthProvider.getCredential(verificationId, normalizedCode)
        signInWithPhoneCredential(credential, onResult)
    }

    private fun notifyMessage(message: String) {
        viewModelScope.launch(exceptionHandler) {
            eventChannel.send(ProfileEvent.ShowMessage(message))
        }
    }

    private fun signInWithOAuthProvider(
        activity: Activity,
        providerId: String,
        platform: String,
        providerLabel: String,
        successMessageRes: Int,
        scopes: List<String> = emptyList(),
        customParameters: Map<String, String> = emptyMap()
    ) {
        val provider = buildOAuthProvider(providerId, scopes, customParameters)
        _uiState.update { it.copy(isLoading = true, emailSignInError = null) }

        val currentUser = auth.currentUser?.takeUnless { it.isAnonymous }
        val authTask = if (currentUser == null) {
            auth.startActivityForSignInWithProvider(activity, provider)
        } else {
            currentUser.startActivityForLinkWithProvider(activity, provider)
        }

        authTask
            .addOnSuccessListener { result ->
                val firebaseUser = result.user
                if (firebaseUser == null) {
                    handleProviderSignInFailure(
                        providerLabel,
                        IllegalStateException(appContext.getString(R.string.profile_provider_user_missing))
                    )
                    return@addOnSuccessListener
                }
                LocalKeyStorage.saveUid(appContext, firebaseUser.uid)
                saveProviderAccount(
                    firebaseUser = firebaseUser,
                    platform = platform,
                    successMessageRes = successMessageRes
                )
            }
            .addOnFailureListener { error ->
                if (currentUser != null && error is FirebaseAuthUserCollisionException) {
                    signInWithOAuthProviderAsPrimary(
                        activity = activity,
                        providerId = providerId,
                        platform = platform,
                        providerLabel = providerLabel,
                        successMessageRes = successMessageRes,
                        scopes = scopes,
                        customParameters = customParameters
                    )
                    return@addOnFailureListener
                }
                handleProviderSignInFailure(providerLabel, error)
            }
    }

    private fun signInWithOAuthProviderAsPrimary(
        activity: Activity,
        providerId: String,
        platform: String,
        providerLabel: String,
        successMessageRes: Int,
        scopes: List<String>,
        customParameters: Map<String, String>
    ) {
        auth.startActivityForSignInWithProvider(
            activity,
            buildOAuthProvider(providerId, scopes, customParameters)
        )
            .addOnSuccessListener { result ->
                val firebaseUser = result.user
                if (firebaseUser == null) {
                    handleProviderSignInFailure(
                        providerLabel,
                        IllegalStateException(appContext.getString(R.string.profile_provider_user_missing))
                    )
                    return@addOnSuccessListener
                }
                LocalKeyStorage.saveUid(appContext, firebaseUser.uid)
                saveProviderAccount(
                    firebaseUser = firebaseUser,
                    platform = platform,
                    successMessageRes = successMessageRes
                )
            }
            .addOnFailureListener { error ->
                handleProviderSignInFailure(providerLabel, error)
            }
    }

    private fun buildOAuthProvider(
        providerId: String,
        scopes: List<String>,
        customParameters: Map<String, String>
    ): OAuthProvider {
        val builder = OAuthProvider.newBuilder(providerId, auth)
        if (scopes.isNotEmpty()) {
            builder.setScopes(scopes)
        }
        if (customParameters.isNotEmpty()) {
            builder.addCustomParameters(customParameters)
        }
        return builder.build()
    }

    private fun signInWithPhoneCredential(
        credential: PhoneAuthCredential,
        onResult: (Boolean) -> Unit
    ) {
        val currentUser = auth.currentUser?.takeUnless { it.isAnonymous }
        val authTask = if (currentUser == null) {
            auth.signInWithCredential(credential)
        } else {
            currentUser.linkWithCredential(credential)
        }

        authTask
            .addOnSuccessListener { result ->
                pendingPhoneVerificationId = null
                val firebaseUser = result.user
                if (firebaseUser == null) {
                    handleProviderSignInFailure(
                        appContext.getString(R.string.profile_phone_provider_label),
                        IllegalStateException(appContext.getString(R.string.profile_provider_user_missing))
                    )
                    onResult(false)
                    return@addOnSuccessListener
                }
                LocalKeyStorage.saveUid(appContext, firebaseUser.uid)
                saveProviderAccount(
                    firebaseUser = firebaseUser,
                    platform = "phone",
                    successMessageRes = R.string.profile_phone_sign_in_success,
                    onResult = onResult
                )
            }
            .addOnFailureListener { error ->
                if (currentUser != null && error is FirebaseAuthUserCollisionException) {
                    signInWithPhoneCredentialAsPrimary(credential, onResult)
                    return@addOnFailureListener
                }
                handleProviderSignInFailure(
                    appContext.getString(R.string.profile_phone_provider_label),
                    error
                )
                onResult(false)
            }
    }

    private fun signInWithPhoneCredentialAsPrimary(
        credential: PhoneAuthCredential,
        onResult: (Boolean) -> Unit
    ) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                pendingPhoneVerificationId = null
                val firebaseUser = result.user
                if (firebaseUser == null) {
                    handleProviderSignInFailure(
                        appContext.getString(R.string.profile_phone_provider_label),
                        IllegalStateException(appContext.getString(R.string.profile_provider_user_missing))
                    )
                    onResult(false)
                    return@addOnSuccessListener
                }
                LocalKeyStorage.saveUid(appContext, firebaseUser.uid)
                saveProviderAccount(
                    firebaseUser = firebaseUser,
                    platform = "phone",
                    successMessageRes = R.string.profile_phone_sign_in_success,
                    onResult = onResult
                )
            }
            .addOnFailureListener { error ->
                handleProviderSignInFailure(
                    appContext.getString(R.string.profile_phone_provider_label),
                    error
                )
                onResult(false)
            }
    }

    private fun handleProviderSignInFailure(providerLabel: String, error: Throwable) {
        _uiState.update { it.copy(isLoading = false) }
        notifyMessage(
            appContext.getString(
                R.string.profile_provider_sign_in_error_with_reason,
                providerLabel,
                error.localizedMessage ?: appContext.getString(R.string.unknown_error)
            )
        )
    }

    private fun authenticateWithGoogleCredential(
        credential: AuthCredential,
        displayName: String,
        email: String
    ) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val firebaseUser = result.user
                if (firebaseUser == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    notifyMessage(appContext.getString(R.string.profile_google_sign_in_error))
                    return@addOnSuccessListener
                }

                LocalKeyStorage.saveUid(appContext, firebaseUser.uid)
                saveGoogleAccount(firebaseUser.uid, displayName, email)
            }
            .addOnFailureListener { e ->
                _uiState.update { it.copy(isLoading = false) }
                notifyMessage(
                    appContext.getString(
                        R.string.profile_google_link_failure,
                        e.message ?: appContext.getString(R.string.unknown_error)
                    )
                )
            }
    }

    private fun saveGoogleAccount(uid: String, displayName: String, email: String) {
        val resolvedRole = resolveRole(
            localRole = LocalKeyStorage.getSavedRole(appContext),
            remoteRole = null,
            fallbackRole = "user"
        )
        val resolvedEmail = firstNonBlank(email, auth.currentUser?.email)
        val resolvedDisplayName = firstNonBlank(
            displayName,
            auth.currentUser?.displayName,
            _uiState.value.username
        )
        val localCountry = normalizeCountryCode(LocalKeyStorage.getSavedCountry(appContext).first)
        val resolvedAgency = resolveAgency(
            role = resolvedRole,
            country = localCountry,
            email = resolvedEmail,
            remoteAgency = _uiState.value.agency,
            fallbackAgency = _uiState.value.agency
        )
        persistLinkedAccount(
            uid = uid,
            platform = "android",
            resolvedEmail = resolvedEmail,
            resolvedDisplayName = resolvedDisplayName,
            localCountry = localCountry
        )
            .addOnSuccessListener {
                val currentUser = auth.currentUser
                _uiState.update {
                    it.copy(
                        username = firstNonBlank(
                            resolvedDisplayName,
                            currentUser?.displayName,
                            it.username
                        ),
                        email = firstNonBlank(email, currentUser?.email, it.email),
                        country = firstNonBlank(localCountry, it.country),
                        agency = firstNonBlank(resolvedAgency, it.agency),
                        isLoading = false,
                        isSignedIn = true
                    )
                }
                syncRemoteIdentity(uid)
                notifyMessage(appContext.getString(R.string.profile_google_link_success))
            }
            .addOnFailureListener { error ->
                val currentUser = auth.currentUser
                _uiState.update {
                    it.copy(
                        username = firstNonBlank(displayName, currentUser?.displayName, it.username),
                        email = firstNonBlank(email, currentUser?.email, it.email),
                        isLoading = false,
                        isSignedIn = isSignedInUser(currentUser)
                    )
                }
                if (isPermissionDenied(error)) {
                    notifyMessage(appContext.getString(R.string.profile_google_sync_warning))
                } else {
                    notifyMessage(appContext.getString(R.string.profile_google_link_error))
                }
                syncRemoteIdentity(uid)
            }
    }

    private fun saveEmailPasswordAccount(
        uid: String,
        email: String,
        displayName: String,
        onResult: (Boolean) -> Unit
    ) {
        val resolvedRole = resolveRole(
            localRole = LocalKeyStorage.getSavedRole(appContext),
            remoteRole = null,
            fallbackRole = "user"
        )
        val localCountry = normalizeCountryCode(LocalKeyStorage.getSavedCountry(appContext).first)
        val resolvedDisplayName = firstNonBlank(
            displayName,
            _uiState.value.username,
            email.substringBefore("@")
        )
        val resolvedAgency = resolveAgency(
            role = resolvedRole,
            country = localCountry,
            email = email,
            remoteAgency = _uiState.value.agency,
            fallbackAgency = _uiState.value.agency
        )
        persistLinkedAccount(
            uid = uid,
            platform = "email_password",
            resolvedEmail = email,
            resolvedDisplayName = resolvedDisplayName,
            localCountry = localCountry
        )
            .addOnSuccessListener {
                _uiState.update {
                    it.copy(
                        username = firstNonBlank(resolvedDisplayName, it.username),
                        email = firstNonBlank(email, it.email),
                        country = firstNonBlank(localCountry, it.country),
                        agency = firstNonBlank(resolvedAgency, it.agency),
                        isLoading = false,
                        isSignedIn = true
                    )
                }
                syncRemoteIdentity(uid)
                notifyMessage(appContext.getString(R.string.profile_email_sign_in_success))
                onResult(true)
            }
            .addOnFailureListener { error ->
                val currentUser = auth.currentUser
                _uiState.update {
                    it.copy(
                        username = firstNonBlank(displayName, currentUser?.displayName, it.username),
                        email = firstNonBlank(email, currentUser?.email, it.email),
                        isLoading = false,
                        isSignedIn = isSignedInUser(currentUser)
                    )
                }
                if (isPermissionDenied(error)) {
                    notifyMessage(appContext.getString(R.string.profile_email_sync_warning))
                } else {
                    notifyMessage(
                        appContext.getString(
                            R.string.profile_email_sign_in_error_with_reason,
                            error.localizedMessage ?: appContext.getString(R.string.unknown_error)
                        )
                    )
                }
                syncRemoteIdentity(uid)
                onResult(isSignedInUser(currentUser))
            }
    }

    private fun saveProviderAccount(
        firebaseUser: FirebaseUser,
        platform: String,
        successMessageRes: Int,
        onResult: (Boolean) -> Unit = {}
    ) {
        val resolvedRole = resolveRole(
            localRole = LocalKeyStorage.getSavedRole(appContext),
            remoteRole = null,
            fallbackRole = "user"
        )
        val resolvedEmail = firstNonBlank(firebaseUser.email, _uiState.value.email)
        val resolvedPhoneNumber = firstNonBlank(firebaseUser.phoneNumber, _uiState.value.phoneNumber)
        val resolvedDisplayName = firstNonBlank(
            firebaseUser.displayName,
            _uiState.value.username,
            resolvedEmail.substringBefore("@"),
            resolvedPhoneNumber,
            "User ${firebaseUser.uid.take(8)}"
        )
        val localCountry = normalizeCountryCode(LocalKeyStorage.getSavedCountry(appContext).first)
        val resolvedAgency = resolveAgency(
            role = resolvedRole,
            country = localCountry,
            email = resolvedEmail,
            remoteAgency = _uiState.value.agency,
            fallbackAgency = _uiState.value.agency
        )

        persistLinkedAccount(
            uid = firebaseUser.uid,
            platform = platform,
            resolvedEmail = resolvedEmail,
            resolvedDisplayName = resolvedDisplayName,
            localCountry = localCountry,
            resolvedPhoneNumber = resolvedPhoneNumber
        )
            .addOnSuccessListener {
                _uiState.update {
                    it.copy(
                        username = firstNonBlank(resolvedDisplayName, it.username),
                        email = firstNonBlank(resolvedEmail, it.email),
                        phoneNumber = firstNonBlank(resolvedPhoneNumber, it.phoneNumber),
                        country = firstNonBlank(localCountry, it.country),
                        agency = firstNonBlank(resolvedAgency, it.agency),
                        isLoading = false,
                        isSignedIn = true
                    )
                }
                syncRemoteIdentity(firebaseUser.uid)
                notifyMessage(appContext.getString(successMessageRes))
                onResult(true)
            }
            .addOnFailureListener { error ->
                val currentUser = auth.currentUser
                _uiState.update {
                    it.copy(
                        username = firstNonBlank(resolvedDisplayName, currentUser?.displayName, it.username),
                        email = firstNonBlank(resolvedEmail, currentUser?.email, it.email),
                        phoneNumber = firstNonBlank(
                            resolvedPhoneNumber,
                            currentUser?.phoneNumber,
                            it.phoneNumber
                        ),
                        isLoading = false,
                        isSignedIn = isSignedInUser(currentUser)
                    )
                }
                if (isPermissionDenied(error)) {
                    notifyMessage(appContext.getString(R.string.profile_provider_sync_warning))
                } else {
                    notifyMessage(
                        appContext.getString(
                            R.string.profile_provider_profile_save_error,
                            error.localizedMessage ?: appContext.getString(R.string.unknown_error)
                        )
                    )
                }
                syncRemoteIdentity(firebaseUser.uid)
                onResult(isSignedInUser(currentUser))
            }
    }

    private fun persistLinkedAccount(
        uid: String,
        platform: String,
        resolvedEmail: String,
        resolvedDisplayName: String,
        localCountry: String,
        resolvedPhoneNumber: String = ""
    ) = firestore.collection("users").document(uid)
        .get()
        .continueWithTask { snapshotTask ->
            val snapshot = snapshotTask.result ?: throw snapshotTask.exception
                ?: IllegalStateException("Unable to load profile before persisting linked account")
            val userData = hashMapOf<String, Any>(
                "username" to resolvedDisplayName,
                "platform" to platform,
                "lastLinked" to FieldValue.serverTimestamp()
            )
            if (resolvedEmail.isNotBlank()) {
                userData["email"] = resolvedEmail
            }
            if (resolvedPhoneNumber.isNotBlank()) {
                userData["phoneNumber"] = resolvedPhoneNumber
            }
            if (localCountry.isNotBlank()) {
                userData["country"] = localCountry
            }
            if (!snapshot.exists()) {
                userData["id"] = uid
                userData["role"] = "user"
                userData["verified"] = false
                userData["createdAt"] = FieldValue.serverTimestamp()
            }
            firestore.collection("users").document(uid).set(userData, SetOptions.merge())
        }

    private fun syncRemoteIdentity(uid: String) {
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { snapshot ->
                val remoteCountry = normalizeCountryCode(snapshot.getString("country"))
                val remoteAgency = snapshot.getString("agency")
                val remoteAgencySlug = snapshot.getString("agencySlug")
                val remoteAgencyKey = snapshot.getString("agencyKey")
                val remoteRole = snapshot.getString("role")
                val remoteVerified = snapshot.getBoolean("verified")
                val localRole = LocalKeyStorage.getSavedRole(appContext)
                val localCountry = normalizeCountryCode(LocalKeyStorage.getSavedCountry(appContext).first)
                val remoteUsername = snapshot.getString("username")
                val remoteEmail = snapshot.getString("email")
                val remotePhoneNumber = snapshot.getString("phoneNumber")
                val authEmail = auth.currentUser?.email
                val authPhoneNumber = auth.currentUser?.phoneNumber
                _uiState.update { state ->
                    val resolvedRole = resolveRole(localRole, remoteRole, state.role)
                    val resolvedEmail = firstNonBlank(remoteEmail, state.email, authEmail)
                    val resolvedPhoneNumber = firstNonBlank(
                        remotePhoneNumber,
                        state.phoneNumber,
                        authPhoneNumber
                    )
                    val resolvedCountry = firstNonBlank(remoteCountry, state.country, localCountry)
                    val resolvedAgency = resolveAgency(
                        role = resolvedRole,
                        country = resolvedCountry,
                        email = resolvedEmail,
                        remoteAgency = remoteAgency,
                        fallbackAgency = state.agency
                    )
                    state.copy(
                        username = firstNonBlank(remoteUsername, state.username, auth.currentUser?.displayName),
                        email = resolvedEmail,
                        phoneNumber = resolvedPhoneNumber,
                        country = resolvedCountry,
                        agency = resolvedAgency,
                        role = resolvedRole,
                        verified = remoteVerified ?: state.verified,
                        isSignedIn = isSignedInUser(auth.currentUser)
                    )
                }

                val resolvedState = _uiState.value
                syncRescueIdentityMetadataIfNeeded(
                    uid = uid,
                    role = resolvedState.role,
                    email = resolvedState.email,
                    country = resolvedState.country,
                    resolvedAgency = resolvedState.agency,
                    remoteEmail = remoteEmail,
                    remoteCountry = remoteCountry,
                    remoteAgency = remoteAgency,
                    remoteAgencySlug = remoteAgencySlug,
                    remoteAgencyKey = remoteAgencyKey
                )
                enqueueMobileProfileSync(platform = "profile")
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to sync remote profile state", error)
            }
    }

    private fun enqueueMobileProfileSync(platform: String) {
        val user = auth.currentUser?.takeUnless { it.isAnonymous } ?: return
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            MobileSyncClient.syncProfile(
                context = appContext,
                user = user,
                username = state.username,
                email = state.email,
                phoneNumber = state.phoneNumber,
                country = state.country,
                agency = state.agency,
                role = state.role,
                verified = state.verified,
                platform = platform,
            )
        }
    }

    /**
     * Loads the cached role certificate (if any) for display. Uses
     * allowExpired=true so the user can still see an expired cert and decide
     * to renew/revoke. This is UI gating only; signing paths use strict checks.
     */
    fun refreshCertificate() {
        viewModelScope.launch(exceptionHandler) {
            _certificateState.update { it.copy(status = CertificateStatus.Loading, errorMessage = null) }
            runCatching {
                val certificateBytes = securityRepository.getStoredCertificate(allowExpired = true)
                    ?: return@runCatching null
                RoleCertificate.fromStorageBytes(certificateBytes)
            }.onSuccess { cert ->
                if (cert == null) {
                    _certificateState.update { it.copy(status = CertificateStatus.Missing) }
                } else {
                    val now = System.currentTimeMillis()
                    _certificateState.update {
                        it.copy(
                            status = CertificateStatus.Loaded(
                                certificate = cert,
                                isExpired = cert.expiresAtMillis < now,
                                isRevoked = false,
                            )
                        )
                    }
                }
            }.onFailure { throwable ->
                _certificateState.update {
                    it.copy(
                        status = CertificateStatus.Failure(
                            throwable.message ?: throwable::class.java.simpleName
                        )
                    )
                }
            }
        }
    }

    /**
     * Runs the full provisioning flow (challenge → attested key → Play
     * Integrity → backend issue). On success, refreshes the cert state.
     */
    fun provisionCertificate() {
        viewModelScope.launch(exceptionHandler) {
            _certificateState.update { it.copy(provisioning = true, errorMessage = null) }
            runCatching {
                val uid = auth.currentUser
                    ?.takeUnless { it.isAnonymous }
                    ?.uid
                    ?.trim()
                    .orEmpty()
                require(uid.isNotEmpty()) {
                    appContext.getString(R.string.profile_cert_signin_required_message)
                }
                val deviceId = runCatching {
                    RescueDeviceRegistry.registerLocalDevice(
                        firestore = firestore,
                        context = appContext,
                        uid = uid,
                    )
                }.getOrElse {
                    LocalKeyStorage.getOrCreateRescueDeviceId(appContext)
                }
                val flow = CertificateProvisioningFlow(appContext, functions)
                flow.provisionCertificate(deviceId)
            }.onSuccess {
                _certificateState.update {
                    it.copy(
                        provisioning = false,
                        statusMessage = appContext.getString(R.string.profile_cert_provision_success_message),
                    )
                }
                refreshCertificate()
            }.onFailure { throwable ->
                _certificateState.update {
                    it.copy(
                        provisioning = false,
                        errorMessage = certificateOperationErrorMessage(throwable),
                    )
                }
            }
        }
    }

    fun revokeCertificate(reason: String?) {
        viewModelScope.launch(exceptionHandler) {
            _certificateState.update { it.copy(revoking = true, errorMessage = null) }
            runCatching {
                val callable = functions.getHttpsCallable("revokeRoleCertificate")
                callable.call(
                    mapOf("reason" to (reason?.trim()?.takeIf { it.isNotEmpty() }))
                ).awaitResult()
                securityRepository.wipeCertificate("self-revoke from profile screen")
            }.onSuccess {
                _certificateState.update {
                    it.copy(
                        revoking = false,
                        statusMessage = appContext.getString(R.string.profile_cert_revoke_success_message),
                    )
                }
                refreshCertificate()
            }.onFailure { throwable ->
                _certificateState.update {
                    it.copy(
                        revoking = false,
                        errorMessage = certificateOperationErrorMessage(throwable),
                    )
                }
            }
        }
    }

    private fun certificateOperationErrorMessage(throwable: Throwable): String {
        if (FirebaseAppCheckFailures.isLikelyAppCheckFailure(
                throwable = throwable,
                allowGenericUnauthenticated = true
            )
        ) {
            return appContext.getString(R.string.app_check_install_play_store_message)
        }
        return throwable.message?.take(180) ?: throwable::class.java.simpleName
    }

    fun consumeCertificateMessages() {
        _certificateState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    private fun syncRescueIdentityMetadataIfNeeded(
        uid: String,
        role: String,
        email: String,
        country: String,
        resolvedAgency: String,
        remoteEmail: String?,
        remoteCountry: String?,
        remoteAgency: String?,
        remoteAgencySlug: String?,
        remoteAgencyKey: String?
    ) {
        if (!isRescueRole(role)) {
            return
        }

        viewModelScope.launch(exceptionHandler) {
            runCatching {
                RescueDeviceRegistry.registerLocalDevice(
                    firestore = firestore,
                    context = appContext,
                    uid = uid
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to register rescue device ownership", error)
            }
        }

        val updates = hashMapOf<String, Any>()
        if (remoteCountry.isNullOrBlank() && country.isNotBlank()) {
            updates["country"] = country
        }
        if (remoteEmail.isNullOrBlank() && email.isNotBlank()) {
            updates["email"] = email
        }
        if (updates.isEmpty()) {
            return
        }

        firestore.collection("users").document(uid)
            .set(updates, SetOptions.merge())
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to synchronize rescue metadata", error)
            }
    }

    private fun resolveAgency(
        role: String,
        country: String,
        email: String,
        remoteAgency: String?,
        fallbackAgency: String?
    ): String {
        val normalizedCountry = normalizeCountryCode(country)
        val emailAgency = AgencyRouter.getAgencyForEmail(email)
        val countryAgency = AgencyRouter.getAgencyForCountry(normalizedCountry)
        val explicitAgency = remoteAgency?.trim().orEmpty()
        val cachedAgency = fallbackAgency?.trim().orEmpty()

        return if (isRescueRole(role)) {
            // Keep explicitly assigned agency authoritative for rescue users.
            firstNonBlank(explicitAgency, emailAgency, countryAgency, cachedAgency)
        } else {
            firstNonBlank(explicitAgency, countryAgency, emailAgency, cachedAgency)
        }
    }

    private fun resolveRole(localRole: String?, remoteRole: String?, fallbackRole: String?): String {
        val normalizedLocal = normalizeRoleOrNull(localRole)
        val normalizedRemote = normalizeRoleOrNull(remoteRole)
        val normalizedFallback = normalizeRoleOrNull(fallbackRole)

        return when {
            normalizedLocal in APPROVER_ROLES -> normalizedLocal ?: "user"
            !normalizedRemote.isNullOrBlank() -> normalizedRemote
            !normalizedFallback.isNullOrBlank() -> normalizedFallback
            else -> "user"
        }
    }

    private fun isRescueRole(role: String?): Boolean {
        return normalizeRoleOrNull(role) in APPROVER_ROLES
    }

    private fun normalizeRoleOrNull(role: String?): String? {
        val normalized = role?.trim()?.lowercase(Locale.US).orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return when (normalized) {
            "field_team", "field-team", "ft" -> "fieldteam"
            else -> normalized
        }
    }

    private fun normalizeCountryCode(countryCode: String?): String {
        return countryCode?.trim()?.uppercase(Locale.US).orEmpty()
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.orEmpty() ?: ""
    }

    private fun isSignedInUser(user: FirebaseUser?): Boolean {
        return user != null &&
            !user.isAnonymous &&
            (
                !user.email.isNullOrBlank() ||
                    !user.phoneNumber.isNullOrBlank() ||
                    user.providerData.any { it.providerId != "firebase" }
                )
    }

    private fun isLikelyE164PhoneNumber(value: String): Boolean {
        return value.startsWith("+") &&
            value.length in MIN_PHONE_NUMBER_LENGTH..MAX_PHONE_NUMBER_LENGTH &&
            value.drop(1).all { it.isDigit() }
    }

    private fun isPermissionDenied(error: Throwable): Boolean {
        return (error as? FirebaseFirestoreException)?.code ==
            FirebaseFirestoreException.Code.PERMISSION_DENIED
    }

    private fun resolveEmailSignInErrorMessage(error: Throwable): String {
        val invalidCredentialsMessage =
            appContext.getString(R.string.profile_email_sign_in_invalid_credentials)
        return when (error) {
            is FirebaseAuthInvalidCredentialsException,
            is FirebaseAuthInvalidUserException -> invalidCredentialsMessage
            is FirebaseAuthException -> when (error.errorCode) {
                "ERROR_WRONG_PASSWORD",
                "ERROR_INVALID_CREDENTIAL",
                "ERROR_USER_NOT_FOUND",
                "ERROR_INVALID_EMAIL" -> invalidCredentialsMessage
                else -> appContext.getString(R.string.profile_email_sign_in_error)
            }
            else -> appContext.getString(R.string.profile_email_sign_in_error)
        }
    }

    companion object {
        private const val TAG = "ProfileViewModel"
        private const val FUNCTIONS_REGION = "us-central1"
        private const val MICROSOFT_PROVIDER_ID = "microsoft.com"
        private const val APPLE_PROVIDER_ID = "apple.com"
        private const val MIN_SMS_CODE_LENGTH = 4
        private const val MIN_PHONE_NUMBER_LENGTH = 8
        private const val MAX_PHONE_NUMBER_LENGTH = 16
        private val APPROVER_ROLES = setOf("admin", "fieldteam")
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val result = task.result
            if (result != null) {
                continuation.resume(result)
            } else {
                continuation.resumeWithException(
                    IllegalStateException("Firebase callable returned a null result")
                )
            }
        } else {
            continuation.resumeWithException(
                task.exception ?: IllegalStateException("Firebase callable failed")
            )
        }
    }
}
