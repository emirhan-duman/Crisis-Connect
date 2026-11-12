package com.auralis.crisisconnect.security

import com.auralis.crisisconnect.R
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctionsException

enum class AuthorityMeshKeyAccess {
    Available, SignInRequired, CertificateRequired, NotAuthorized,
    NotEnabled, ServiceUnavailable, NetworkError, Failed;

    fun errorMessageResource(): Int = when (this) {
        SignInRequired -> R.string.rescue_mesh_key_sign_in_required
        CertificateRequired -> R.string.rescue_mesh_error_auth_missing
        NotAuthorized -> R.string.rescue_mesh_error_unauthorized
        NotEnabled -> R.string.rescue_mesh_key_not_enabled
        ServiceUnavailable -> R.string.rescue_mesh_key_service_unavailable
        NetworkError -> R.string.rescue_mesh_key_network_error
        Available, Failed -> R.string.rescue_mesh_key_fetch_failed
    }
}

internal fun authorityMeshKeyFailure(error: Throwable): AuthorityMeshKeyAccess = when (error) {
    is MissingRoleCertificateException -> AuthorityMeshKeyAccess.CertificateRequired
    is FirebaseNetworkException -> AuthorityMeshKeyAccess.NetworkError
    is FirebaseFunctionsException -> when (error.code) {
        FirebaseFunctionsException.Code.NOT_FOUND,
        FirebaseFunctionsException.Code.UNIMPLEMENTED -> AuthorityMeshKeyAccess.ServiceUnavailable
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> AuthorityMeshKeyAccess.NotAuthorized
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> AuthorityMeshKeyAccess.SignInRequired
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> AuthorityMeshKeyAccess.NotEnabled
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> AuthorityMeshKeyAccess.NetworkError
        else -> AuthorityMeshKeyAccess.Failed
    }
    else -> AuthorityMeshKeyAccess.Failed
}
