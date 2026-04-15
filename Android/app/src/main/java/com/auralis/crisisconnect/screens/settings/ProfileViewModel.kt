package com.auralis.crisisconnect.screens.settings

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.database.AgencyRouter
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.RescueDeviceRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withContext
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * UI state of the profile screen.
 */
data class ProfileUiState(
    val username: String = "",
    val email: String = "",
    val country: String = "",
    val agency: String = "",
    val role: String = "user",
    val verified: Boolean = false,
    val profileBitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val isSignedIn: Boolean = false,
    val emailSignInError: String? = null
)

sealed class ProfileEvent {
    data class ShowMessage(val message: String) : ProfileEvent()
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
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

    private val eventChannel = Channel<ProfileEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var syncedAuthUid: String? = null
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
                email = firstNonBlank(state.email, user.email)
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
            _uiState.update { it.copy(isLoading = false, isSignedIn = false, email = "") }
            return
        }

        if (!hasAuthenticatedUser) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isSignedIn = false,
                    role = resolveRole(localRole, remoteRole = null, fallbackRole = "user"),
                    country = firstNonBlank(it.country, localCountry),
                    email = ""
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
                _uiState.update { state ->
                    val resolvedRole = resolveRole(localRole, remoteRole, state.role)
                    val resolvedEmail = firstNonBlank(
                        remoteEmail,
                        state.email,
                        authUser?.email
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
                        country = resolvedCountry,
                        agency = resolvedAgency,
                        role = resolvedRole,
                        verified = documentSnapshot.getBoolean("verified") ?: false,
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
        }
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

    private fun notifyMessage(message: String) {
        viewModelScope.launch(exceptionHandler) {
            eventChannel.send(ProfileEvent.ShowMessage(message))
        }
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

    private fun persistLinkedAccount(
        uid: String,
        platform: String,
        resolvedEmail: String,
        resolvedDisplayName: String,
        localCountry: String
    ) = firestore.collection("users").document(uid)
        .get()
        .continueWithTask { snapshotTask ->
            val snapshot = snapshotTask.result ?: throw snapshotTask.exception
                ?: IllegalStateException("Unable to load profile before persisting linked account")
            val userData = hashMapOf<String, Any>(
                "email" to resolvedEmail,
                "username" to resolvedDisplayName,
                "platform" to platform,
                "lastLinked" to FieldValue.serverTimestamp()
            )
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
                val authEmail = auth.currentUser?.email
                _uiState.update { state ->
                    val resolvedRole = resolveRole(localRole, remoteRole, state.role)
                    val resolvedEmail = firstNonBlank(remoteEmail, state.email, authEmail)
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
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to sync remote profile state", error)
            }
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
                RescueDeviceRegistry.registerDevice(
                    firestore = firestore,
                    uid = uid,
                    deviceId = LocalKeyStorage.getOrCreateRescueDeviceId(appContext)
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
        return user != null && !user.isAnonymous && !user.email.isNullOrBlank()
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
        private val APPROVER_ROLES = setOf("admin", "fieldteam")
    }
}
