package com.auralis.crisisconnect.screens.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.auralis.crisisconnect.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.database.AgencyRouter
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.data.profile.ProfilePhotoUploadWorker
import com.auralis.crisisconnect.data.profile.ProfilePhotoDeleteWorker
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.messaging.ContactDirectoryCache
import com.auralis.crisisconnect.security.CertificateProvisioningNotifier
import com.auralis.crisisconnect.security.FirebaseAppCheckFailures
import com.auralis.crisisconnect.security.RescueDeviceRegistry
import com.auralis.crisisconnect.security.RoleCertificate
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.sync.MobileSyncClient
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.FirebaseException
import com.google.firebase.Timestamp
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
import com.google.firebase.auth.UserProfileChangeRequest
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
    val emailSignInError: String? = null,
    val isDeletingAccount: Boolean = false
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

class ProfileViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

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

    // Kept in SavedStateHandle because the reCAPTCHA browser trip often gets the app
    // process killed in the background; with a plain field the restored UI would still
    // show the code entry, but a correct code would fail with "session missing".
    private var pendingPhoneVerificationId: String?
        get() = savedState[KEY_PHONE_VERIFICATION_ID]
        set(value) {
            savedState[KEY_PHONE_VERIFICATION_ID] = value
        }

    // The E.164 number the pending verification belongs to, so confirm-time callers that
    // don't pass the number (e.g. the profile screen) still get the "already linked to
    // this same number" success treatment.
    private var pendingPhoneVerificationNumber: String?
        get() = savedState[KEY_PHONE_VERIFICATION_NUMBER]
        set(value) {
            savedState[KEY_PHONE_VERIFICATION_NUMBER] = value
        }

    // Non-null while the pending code came from the server-side (Twilio) fallback rather
    // than the primary Firebase Phone Auth path. confirmPhoneSignInCode must then check
    // the code through the backend instead of a Firebase verification session.
    private var pendingServerOtpPhone: String?
        get() = savedState[KEY_OTP_FALLBACK_PHONE]
        set(value) {
            savedState[KEY_OTP_FALLBACK_PHONE] = value
        }
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
        observeCertificateProvisioning()
    }

    /**
     * Keeps the certificate card in sync with the **background** auto-provisioner
     * ([com.auralis.crisisconnect.CrisisConnectApp] `attachCertificateAutoProvisioner`). The card
     * loads its state only once on entry, so a certificate issued right after sign-in used to stay in
     * the "Missing" (tap-to-provision) state until a full app restart re-read it. Reacting to the
     * provisioning events refreshes the card live instead.
     */
    private fun observeCertificateProvisioning() {
        viewModelScope.launch(exceptionHandler) {
            CertificateProvisioningNotifier.events.collect { event ->
                when (event) {
                    CertificateProvisioningNotifier.Event.InProgress ->
                        _certificateState.update { it.copy(provisioning = true, errorMessage = null) }

                    CertificateProvisioningNotifier.Event.Success -> {
                        _certificateState.update { it.copy(provisioning = false) }
                        refreshCertificate()
                    }

                    is CertificateProvisioningNotifier.Event.Failure ->
                        _certificateState.update { it.copy(provisioning = false) }
                }
            }
        }
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
                val remoteUsername = firstNonBlank(
                    documentSnapshot.getString("username"),
                    documentSnapshot.getString("name"),
                    documentSnapshot.getString("displayName")
                )
                val remoteEmail = documentSnapshot.getString("email")
                val remotePhoneNumber = documentSnapshot.getString("phoneNumber")
                val hasRemotePhotoField = documentSnapshot.data?.containsKey("photoURL") == true ||
                    documentSnapshot.data?.containsKey("photoDeletedAt") == true
                val remotePhotoUrl = documentSnapshot.getString("photoURL")?.trim()?.takeIf { it.isNotEmpty() }
                val remotePhotoVersion = (documentSnapshot.get("photoUpdatedAt") as? Timestamp)?.let {
                    "${it.seconds}-${it.nanoseconds}"
                } ?: remotePhotoUrl?.hashCode()?.toString()
                val localUploadPending = ProfileImageStorage.isUploadPending(appContext)
                val remotePhotoChanged = remotePhotoVersion != null &&
                    remotePhotoVersion != ProfileImageStorage.getRemoteVersion(appContext)
                val displayPhotoUrl = remotePhotoUrl?.let { appendPhotoVersion(it, remotePhotoVersion) }
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
                        profileBitmap = when {
                            hasRemotePhotoField && remotePhotoUrl == null -> null
                            remotePhotoChanged && !localUploadPending -> null
                            else -> state.profileBitmap
                        },
                        photoUrl = if (hasRemotePhotoField) displayPhotoUrl else state.photoUrl,
                        isLoading = false,
                        isSignedIn = hasAuthenticatedUser
                    )
                }

                when {
                    hasRemotePhotoField && remotePhotoUrl == null && !localUploadPending -> {
                        ProfileImageStorage.clearProfileImage(appContext)
                    }
                    remotePhotoUrl != null && !localUploadPending && remotePhotoChanged -> {
                        // ContactAvatar/Coil performs the single network fetch. Clearing only the old
                        // app-specific bitmap prevents it from masking the newly versioned URL.
                        ProfileImageStorage.clearProfileImage(appContext)
                        ProfileImageStorage.setRemoteVersion(appContext, remotePhotoVersion ?: remotePhotoUrl)
                    }
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
                val hasLocalPending = ProfileImageStorage.isUploadPending(appContext)
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

    fun removeProfilePhoto() {
        ProfileImageStorage.clearProfileImage(appContext)
        _uiState.update { it.copy(profileBitmap = null, photoUrl = null) }
        ProfilePhotoDeleteWorker.enqueue(appContext)
        notifyMessage(appContext.getString(R.string.profile_photo_removed))
    }

    private fun appendPhotoVersion(url: String, version: String?): String {
        if (version.isNullOrBlank()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}ccv=$version"
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
            .set(
                mapOf(
                    "username" to trimmedName,
                    "name" to trimmedName,
                    "displayName" to trimmedName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener {
                auth.currentUser?.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(trimmedName).build()
                )
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
        // Cached directory matches belong to the account that scanned for them.
        ContactDirectoryCache.clear(appContext)
        clearSignedOutProfileState()
        notifyMessage(appContext.getString(R.string.profile_logout_success))
    }

    private fun clearSignedOutProfileState() {
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
    }

    /**
     * Erases the account and everything attached to it — the in-app deletion path Google Play
     * requires of any app that lets users create an account.
     *
     * The cloud half deliberately runs in the deleteAccountAndData callable rather than here: the
     * collections that make a person identifiable (messagingKeys, the phone-hash discovery
     * directory, push tokens, prekey bundles) are `allow read, write: if false` in Firestore rules,
     * and users/{uid} is `allow delete: if false`. A client cannot touch any of them however
     * privileged its session — deleting only what a client CAN reach looks like it worked while
     * leaving the discoverable phone hash behind.
     *
     * The local wipe deliberately spares two things: the SQLCipher/AES keys in [LocalKeyStorage]
     * (clearing those leaves an unopenable database, not a clean one) and downloaded offline maps,
     * which contain nothing personal and are the one asset that cannot be re-fetched mid-disaster.
     */
    fun deleteAccountAndData(activity: Activity? = null) {
        if (_uiState.value.isDeletingAccount) {
            return
        }
        if (auth.currentUser == null) {
            notifyMessage(appContext.getString(R.string.profile_delete_account_error_signed_out))
            return
        }
        _uiState.update { it.copy(isDeletingAccount = true) }

        viewModelScope.launch(exceptionHandler) {
            var serverWipe = runCatching {
                functions.getHttpsCallable(CALLABLE_DELETE_ACCOUNT).call().awaitResult()
            }

            // The server refuses to erase an account on a stale session — the same rule Firebase
            // itself puts on user.delete(). Prove identity, then retry exactly once.
            if (serverWipe.exceptionOrNull()?.let(::requiresRecentLogin) == true) {
                if (reauthenticateForDeletion(activity)) {
                    serverWipe = runCatching {
                        functions.getHttpsCallable(CALLABLE_DELETE_ACCOUNT).call().awaitResult()
                    }
                } else {
                    _uiState.update { it.copy(isDeletingAccount = false) }
                    notifyMessage(appContext.getString(R.string.profile_delete_account_reauth_needed))
                    return@launch
                }
            }

            val error = serverWipe.exceptionOrNull()
            if (error != null) {
                Log.e(TAG, "Account deletion failed", error)
                runCatching { FirebaseCrashlytics.getInstance().recordException(error) }
                _uiState.update { it.copy(isDeletingAccount = false) }
                notifyMessage(
                    if (FirebaseAppCheckFailures.isLikelyAppCheckFailure(error)) {
                        appContext.getString(R.string.profile_delete_account_error_verification)
                    } else {
                        appContext.getString(R.string.profile_delete_account_error)
                    }
                )
                return@launch
            }

            // Server state is gone. The on-device half must follow even if a step of it fails —
            // stopping here would leave local history for an account that no longer exists.
            wipeLocalUserData()
            auth.signOut()
            clearSignedOutProfileState()
            _uiState.update { it.copy(isDeletingAccount = false, profileBitmap = null, photoUrl = null) }
            notifyMessage(appContext.getString(R.string.profile_delete_account_success))
        }
    }

    /** True when the callable rejected the erase because the caller's sign-in is too old. */
    private fun requiresRecentLogin(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { candidate ->
            candidate is FirebaseFunctionsException &&
                candidate.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
                candidate.message?.contains(REAUTH_REQUIRED_MARKER) == true
        }

    /**
     * Refreshes the sign-in so the erase can proceed, without making the user hunt for the button
     * again.
     *
     * Only the browser-based providers can be refreshed in place: Firebase gives them a single
     * reauthenticate call. Phone accounts have no client-side credential to replay — the number is
     * attached server-side by the OTP flow — so they get told to sign in again, which mints a fresh
     * session through the same OTP path and satisfies the server on the next attempt. Apple permits
     * exactly this kind of verification step; what it forbids is making deletion hard to reach, and
     * this costs one sign-in on an account the user is about to destroy.
     */
    private suspend fun reauthenticateForDeletion(activity: Activity?): Boolean {
        val user = auth.currentUser ?: return false
        val providerId = user.providerData
            .map { it.providerId }
            .firstOrNull { it in REAUTH_PROVIDER_IDS }
            ?: return false
        if (activity == null) return false

        // Same custom auth domain the sign-in path pins, so the browser hop stays on our domain.
        auth.app.options.projectId?.let { projectId ->
            runCatching { auth.setCustomAuthDomain("$projectId.firebaseapp.com") }
        }

        return runCatching {
            user.startActivityForReauthenticateWithProvider(
                activity,
                buildOAuthProvider(providerId, emptyList(), emptyMap())
            ).awaitResult()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Reauthentication before deletion failed", error)
            false
        }
    }

    /** On-device erase: chat history, contacts, Signal sessions, cached profile and role state. */
    private suspend fun wipeLocalUserData() = withContext(Dispatchers.IO) {
        // Contacts, messages, call log, authority threads and the Signal session/prekey store all
        // live in this one database.
        runCatching { AppDatabase.getInstance(appContext).clearAllTables() }
            .onFailure { Log.w(TAG, "Local database wipe failed", it) }
        runCatching { securityRepository.clearStoredCertificate() }
            .onFailure { Log.w(TAG, "Certificate wipe failed", it) }
        runCatching { ProfileImageStorage.clearProfileImage(appContext) }
            .onFailure { Log.w(TAG, "Profile image wipe failed", it) }
        runCatching { ContactDirectoryCache.clear(appContext) }
            .onFailure { Log.w(TAG, "Directory cache wipe failed", it) }
        runCatching {
            LocalKeyStorage.clearUid(appContext)
            LocalKeyStorage.clearRole(appContext)
            // A fresh rescue id, because that id is also the SOS signal id: keeping it would let a
            // future report re-attach this device to the incidents just anonymised server-side.
            LocalKeyStorage.rotateRescueDeviceId(appContext)
        }.onFailure { Log.w(TAG, "Local key state wipe failed", it) }
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
            .ifBlank { "https://crisisconnect.network" }

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
        // SMS User Consent must only be started for the server/Twilio path. Firebase
        // Phone Auth listens to the same SMS_RETRIEVED_ACTION but expects a different
        // payload; feeding it a User Consent broadcast crashes firebase-auth.
        onServerCodeSent: () -> Unit = {},
        onResult: (Boolean) -> Unit = {},
        // Callers that render their own error UI (e.g. the welcome step, which doesn't
        // collect this ViewModel's snackbar channel) receive the failure text here too.
        onError: (String) -> Unit = {}
    ) {
        val normalizedPhoneNumber = phoneNumber.trim()
        if (!isLikelyE164PhoneNumber(normalizedPhoneNumber)) {
            val message = appContext.getString(R.string.profile_phone_invalid)
            notifyMessage(message)
            onError(message)
            onResult(false)
            return
        }

        _uiState.update { it.copy(isLoading = true, emailSignInError = null) }
        pendingPhoneVerificationId = null
        pendingPhoneVerificationNumber = normalizedPhoneNumber
        pendingServerOtpPhone = null


        // PRIMARY: native Firebase Phone Auth (Play Integrity -> reCAPTCHA). If Firebase
        // cannot send the code, fall back to the guarded server-side Twilio OTP callable.
        startFirebasePhoneVerification(
            activity = activity,
            normalizedPhoneNumber = normalizedPhoneNumber,
            onCodeSent = onCodeSent,
            onResult = onResult,
            onError = onError,
            onFallbackToServer = {
                requestServerOtp(
                    phoneE164 = normalizedPhoneNumber,
                    onCodeSent = {
                        onServerCodeSent()
                        onCodeSent()
                    },
                    onError = onError,
                    onResult = onResult
                )
            }
        )
    }

    /**
     * PRIMARY phone verification through Firebase Phone Auth (Play Integrity ->
     * reCAPTCHA). If both Firebase attempts fail, the caller starts the Twilio fallback.
     */
    private fun startFirebasePhoneVerification(
        activity: Activity,
        normalizedPhoneNumber: String,
        onCodeSent: () -> Unit,
        onResult: (Boolean) -> Unit,
        onError: (String) -> Unit,
        onFallbackToServer: () -> Unit
    ) {

        // If the silent Play Integrity check fails and the reCAPTCHA fallback opens a
        // browser, open it on the branded domain (hosted on Firebase Hosting, so it serves
        // the auth handler) instead of *.firebaseapp.com. Scoped to phone auth only —
        // OAuth flows restore the default handler (see signInWithOAuthProvider) because
        // their redirect URIs are registered with the identity providers.
        runCatching { auth.setCustomAuthDomain(PHONE_AUTH_BRANDED_DOMAIN) }
        // Localize the verification SMS (and any reCAPTCHA page) to the app's language
        // instead of Firebase's default English template.
        runCatching { auth.useAppLanguage() }

        // Play-recognized builds can hit a backend rejection of the SILENT Play Integrity
        // send ("Error code:39" / status 17499) and the SDK does NOT fall back to the web
        // reCAPTCHA flow on its own — it only does that when no Integrity token could be
        // produced at all. Verified server-side (Cloud Logging, 2026-07-07): the very same
        // send succeeds once it carries a reCAPTCHA token (sideloaded builds recover
        // exactly this way). So on the first 39 we force the reCAPTCHA flow and retry
        // once; silent verification stays the default wherever it works.
        fun attemptVerification(forcedRecaptcha: Boolean) {
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    if (forcedRecaptcha) {
                        runCatching { auth.firebaseAuthSettings.forceRecaptchaFlowForTesting(false) }
                    }
                    // Don't clear pendingPhoneVerificationId here: if this automatic attempt
                    // fails, the user must still be able to confirm the code manually.
                    signInWithPhoneCredential(
                        credential,
                        onResult,
                        onError,
                        verifiedNumberE164 = normalizedPhoneNumber
                    )
                }

                override fun onVerificationFailed(error: FirebaseException) {
                    val silentPathRejected = (error.message ?: "").contains("Error code:39")
                    if (!forcedRecaptcha && silentPathRejected) {
                        runCatching { auth.firebaseAuthSettings.forceRecaptchaFlowForTesting(true) }
                        attemptVerification(forcedRecaptcha = true)
                        return
                    }
                    if (forcedRecaptcha) {
                        runCatching { auth.firebaseAuthSettings.forceRecaptchaFlowForTesting(false) }
                    }
                    pendingPhoneVerificationId = null
                    Log.w(TAG, "Firebase Phone Auth send failed; trying Twilio fallback", error)
                    onFallbackToServer()
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    if (forcedRecaptcha) {
                        runCatching { auth.firebaseAuthSettings.forceRecaptchaFlowForTesting(false) }
                    }
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

        attemptVerification(forcedRecaptcha = false)
    }

    /**
     * FALLBACK phone verification: send the OTP via the guarded Twilio-backed callable.
     * This is reached only after Firebase Phone Auth cannot send a code.
     */
    private fun requestServerOtp(
        phoneE164: String,
        onCodeSent: () -> Unit,
        onError: (String) -> Unit,
        onResult: (Boolean) -> Unit
    ) {
        functions.getHttpsCallable("requestPhoneOtp")
            .call(
                mapOf(
                    "phone" to phoneE164,
                    // Localizes the SMS to the device language (Twilio otherwise falls
                    // back to the phone number's country default).
                    "locale" to Locale.getDefault().language
                )
            )
            .addOnSuccessListener {
                pendingServerOtpPhone = phoneE164
                _uiState.update { it.copy(isLoading = false) }
                notifyMessage(appContext.getString(R.string.profile_phone_code_sent))
                onCodeSent()
            }
            .addOnFailureListener { error ->
                reportServerOtpError(error, onError, onResult)
            }
    }

    private fun verifyServerOtp(
        phoneE164: String,
        code: String,
        onError: (String) -> Unit,
        onResult: (Boolean) -> Unit
    ) {
        functions.getHttpsCallable("verifyPhoneOtp")
            .call(mapOf("phone" to phoneE164, "code" to code))
            .addOnSuccessListener { result ->
                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any?> ?: emptyMap<String, Any?>()
                val outcome = data["outcome"] as? String
                val customToken = data["customToken"] as? String
                when {
                    // Number was attached to the caller's existing account server-side.
                    outcome == "linked" -> {
                        pendingServerOtpPhone = null
                        val user = auth.currentUser
                        if (user == null) {
                            _uiState.update { it.copy(isLoading = false) }
                            onResult(true)
                            return@addOnSuccessListener
                        }
                        user.reload().addOnCompleteListener {
                            val refreshed = auth.currentUser ?: user
                            LocalKeyStorage.saveUid(appContext, refreshed.uid)
                            // Force a fresh ID token so the server-attached phone number
                            // lands in its claims — backend directory discovery treats a
                            // phone-bearing token as a full (non-anonymous) account.
                            refreshed.getIdToken(true).addOnCompleteListener {
                                saveProviderAccount(
                                    firebaseUser = refreshed,
                                    platform = "phone",
                                    successMessageRes = R.string.profile_phone_sign_in_success,
                                    onResult = onResult
                                )
                            }
                        }
                    }
                    // The number already belongs to another account: sign into it, matching
                    // Firebase phone-auth semantics (possession of the number = that account).
                    outcome == "signin" && !customToken.isNullOrBlank() -> {
                        auth.signInWithCustomToken(customToken)
                            .addOnSuccessListener { signIn ->
                                pendingServerOtpPhone = null
                                val firebaseUser = signIn.user
                                if (firebaseUser == null) {
                                    _uiState.update { it.copy(isLoading = false) }
                                    onResult(true)
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
                                reportServerOtpError(error, onError, onResult)
                            }
                    }
                    else -> reportServerOtpError(
                        IllegalStateException("unexpected verifyPhoneOtp outcome: $outcome"),
                        onError,
                        onResult
                    )
                }
            }
            .addOnFailureListener { error ->
                // Most likely a wrong/expired code; keep pendingServerOtpPhone so the
                // user can correct the code and retry against the same verification.
                _uiState.update { it.copy(isLoading = false) }
                val message = appContext.getString(R.string.profile_phone_code_invalid)
                notifyMessage(message)
                onError(message)
                onResult(false)
            }
    }

    private fun reportServerOtpError(
        error: Throwable,
        onError: (String) -> Unit,
        onResult: (Boolean) -> Unit
    ) {
        _uiState.update { it.copy(isLoading = false) }
        val message = appContext.getString(
            R.string.profile_phone_sign_in_error_with_reason,
            error.localizedMessage ?: appContext.getString(R.string.unknown_error)
        )
        notifyMessage(message)
        onError(message)
        onResult(false)
    }

    fun confirmPhoneSignInCode(
        code: String,
        // The E.164 number this code belongs to; lets an "already linked" outcome for the
        // same number be recognized as success instead of an error.
        verifiedNumberE164: String? = null,
        // Callers that render their own error UI (e.g. the welcome step) receive the real
        // failure reason here — a missing/expired session is not the same as a wrong code.
        // Declared before onResult so existing trailing-lambda call sites keep binding it.
        onError: (String) -> Unit = {},
        onResult: (Boolean) -> Unit = {}
    ) {
        // Server-side (Twilio) fallback session: the code lives in the backend, not in a
        // Firebase verification session, so check it through the callable.
        val serverOtpPhone = pendingServerOtpPhone
        if (!serverOtpPhone.isNullOrBlank()) {
            val normalizedOtp = code.trim()
            if (normalizedOtp.length < MIN_SMS_CODE_LENGTH) {
                val message = appContext.getString(R.string.profile_phone_code_invalid)
                notifyMessage(message)
                onError(message)
                onResult(false)
                return
            }
            _uiState.update { it.copy(isLoading = true, emailSignInError = null) }
            verifyServerOtp(serverOtpPhone, normalizedOtp, onError, onResult)
            return
        }

        val verificationId = pendingPhoneVerificationId
        if (verificationId.isNullOrBlank()) {
            val message = appContext.getString(R.string.profile_phone_code_missing)
            notifyMessage(message)
            onError(message)
            onResult(false)
            return
        }
        val normalizedCode = code.trim()
        if (normalizedCode.length < MIN_SMS_CODE_LENGTH) {
            val message = appContext.getString(R.string.profile_phone_code_invalid)
            notifyMessage(message)
            onError(message)
            onResult(false)
            return
        }

        _uiState.update { it.copy(isLoading = true, emailSignInError = null) }
        val credential = PhoneAuthProvider.getCredential(verificationId, normalizedCode)
        signInWithPhoneCredential(
            credential,
            onResult,
            onError,
            verifiedNumberE164 ?: pendingPhoneVerificationNumber
        )
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
        // Phone auth may have pointed the auth domain at the branded host; OAuth redirect
        // URIs are registered against the default *.firebaseapp.com handler, so restore it
        // before opening the provider's browser flow.
        auth.app.options.projectId?.let { projectId ->
            runCatching { auth.setCustomAuthDomain("$projectId.firebaseapp.com") }
        }

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
        onResult: (Boolean) -> Unit,
        onError: (String) -> Unit = {},
        verifiedNumberE164: String? = null
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
                    onError(appContext.getString(R.string.profile_provider_user_missing))
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
                // Re-verifying a number the signed-in account already owns (e.g. running
                // onboarding again) fails the link with "provider already linked" — but the
                // user just proved possession of that same number, so treat it as success.
                // Matched by error code AND message because the SDK does not always wrap
                // this in FirebaseAuthException. Checked before the collision branch: it is
                // the more specific case, and AsPrimary would burn the already-used code.
                val isAlreadyLinked =
                    (error as? FirebaseAuthException)?.errorCode == "ERROR_PROVIDER_ALREADY_LINKED" ||
                        error.message?.contains("already been linked", ignoreCase = true) == true
                if (currentUser != null && isAlreadyLinked) {
                    val linkedNumber = currentUser.phoneNumber
                        ?: currentUser.providerData
                            .firstOrNull { it.providerId == PhoneAuthProvider.PROVIDER_ID }
                            ?.phoneNumber
                    if (samePhoneNumber(linkedNumber, verifiedNumberE164)) {
                        pendingPhoneVerificationId = null
                        LocalKeyStorage.saveUid(appContext, currentUser.uid)
                        saveProviderAccount(
                            firebaseUser = currentUser,
                            platform = "phone",
                            successMessageRes = R.string.profile_phone_sign_in_success,
                            onResult = onResult
                        )
                        return@addOnFailureListener
                    }
                    Log.w(
                        TAG,
                        "Phone provider already linked but numbers differ " +
                            "(error=${error.javaClass.simpleName})"
                    )
                }
                if (currentUser != null && error is FirebaseAuthUserCollisionException) {
                    signInWithPhoneCredentialAsPrimary(credential, onResult, onError)
                    return@addOnFailureListener
                }
                handleProviderSignInFailure(
                    appContext.getString(R.string.profile_phone_provider_label),
                    error
                )
                onError(
                    error.localizedMessage ?: appContext.getString(R.string.unknown_error)
                )
                onResult(false)
            }
    }

    /** Digits-only comparison so "+90 544..." and "+90544..." match. */
    private fun samePhoneNumber(a: String?, b: String?): Boolean {
        val left = a?.filter { it.isDigit() } ?: return false
        val right = b?.filter { it.isDigit() } ?: return false
        return left.isNotBlank() && left == right
    }

    private fun signInWithPhoneCredentialAsPrimary(
        credential: PhoneAuthCredential,
        onResult: (Boolean) -> Unit,
        onError: (String) -> Unit = {}
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
                    onError(appContext.getString(R.string.profile_provider_user_missing))
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
                onError(
                    error.localizedMessage ?: appContext.getString(R.string.unknown_error)
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
                "platform" to platform,
                "lastLinked" to FieldValue.serverTimestamp()
            )
            // A blank display name must not blank the cloud username — it may have been set on
            // the web panel or iOS; explicit renames go through updateUsername() instead.
            if (resolvedDisplayName.isNotBlank()) {
                userData["username"] = resolvedDisplayName
            }
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
                // Register the device-ownership doc (rescueDevices/{deviceId}) that the
                // backend's requestAttestationChallenge requires. Capture — but don't yet
                // surface — any failure: the doc may already exist from a prior session, so
                // we still attempt provisioning with the known local id.
                val registration = runCatching {
                    RescueDeviceRegistry.registerLocalDevice(
                        firestore = firestore,
                        context = appContext,
                        uid = uid,
                    )
                }
                val deviceId = registration.getOrElse {
                    LocalKeyStorage.getOrCreateRescueDeviceId(appContext)
                }
                // Provision AND persist; the raw CertificateProvisioningFlow does not store the cert,
                // so the card stayed on "Missing" until an app restart re-read storage.
                runCatching {
                    securityRepository.provisionAndStoreCertificate(deviceId)
                }.getOrElse { provisionError ->
                    // If we never managed to register the device, that is almost certainly
                    // the real cause: the backend rejects the challenge with
                    // failed-precondition when rescueDevices/{deviceId} is missing. Surface
                    // the registration failure instead of the opaque challenge error.
                    val registrationError = registration.exceptionOrNull()
                    if (registrationError != null) {
                        throw DeviceRegistrationFailed(registrationError)
                    }
                    throw provisionError
                }
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

    /**
     * Raised when the `rescueDevices/{deviceId}` ownership document could not be
     * written. The backend requires that document before it will issue an
     * attestation challenge, so this is usually the real reason a provisioning
     * attempt fails with the otherwise-opaque "challenge request" error.
     */
    private class DeviceRegistrationFailed(cause: Throwable) :
        IllegalStateException("Rescue device registration failed.", cause)

    private fun certificateOperationErrorMessage(throwable: Throwable): String {
        if (FirebaseAppCheckFailures.isLikelyAppCheckFailure(
                throwable = throwable,
                allowGenericUnauthenticated = true
            )
        ) {
            return appContext.getString(R.string.app_check_install_play_store_message)
        }
        if (throwable is DeviceRegistrationFailed) {
            val reason = (throwable.cause?.message ?: throwable.cause?.javaClass?.simpleName)
                ?.take(160)
                ?: throwable.javaClass.simpleName
            return appContext.getString(
                R.string.profile_cert_device_registration_failed_message,
                reason
            )
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
        private const val CALLABLE_DELETE_ACCOUNT = "deleteAccountAndData"

        // Marker the deleteAccountAndData callable puts in its rejection message when the caller's
        // session predates the freshness window.
        private const val REAUTH_REQUIRED_MARKER = "requires-recent-login"

        // Providers whose sign-in can be replayed in place through Firebase's reauthenticate flow.
        // Phone accounts are absent on purpose: their credential lives server-side (see
        // reauthenticateForDeletion).
        private val REAUTH_PROVIDER_IDS = setOf("google.com", "apple.com", "microsoft.com")
        private const val MICROSOFT_PROVIDER_ID = "microsoft.com"
        private const val APPLE_PROVIDER_ID = "apple.com"
        private const val KEY_PHONE_VERIFICATION_ID = "pending_phone_verification_id"
        private const val KEY_PHONE_VERIFICATION_NUMBER = "pending_phone_verification_number"
        private const val KEY_OTP_FALLBACK_PHONE = "pending_otp_fallback_phone"
        private const val MIN_SMS_CODE_LENGTH = 4
        private const val MIN_PHONE_NUMBER_LENGTH = 8
        private const val MAX_PHONE_NUMBER_LENGTH = 16

        // Branded auth domain for the phone-auth reCAPTCHA fallback. Must stay a Firebase
        // Hosting custom domain of this project (it serves /__/auth/handler) and be listed
        // under Authentication → Authorized domains.
        private const val PHONE_AUTH_BRANDED_DOMAIN = "crisisconnect.network"
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
