package com.auralis.crisisconnect.security

import com.auralis.crisisconnect.R
import com.google.firebase.functions.FirebaseFunctionsException
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuthorityMeshKeyAccessTest {
    private fun failure(code: FirebaseFunctionsException.Code): AuthorityMeshKeyAccess {
        val error = mockk<FirebaseFunctionsException>()
        every { error.code } returns code
        return authorityMeshKeyFailure(error)
    }

    @Test fun `missing backend is not a rejected identity`() {
        val result = failure(FirebaseFunctionsException.Code.NOT_FOUND)
        assertEquals(AuthorityMeshKeyAccess.ServiceUnavailable, result)
        assertEquals(R.string.rescue_mesh_key_service_unavailable, result.errorMessageResource())
        assertNotEquals(R.string.rescue_mesh_error_unauthorized, result.errorMessageResource())
    }

    @Test fun `rollout gate is distinct from authorization and connectivity`() {
        val result = failure(FirebaseFunctionsException.Code.FAILED_PRECONDITION)
        assertEquals(AuthorityMeshKeyAccess.NotEnabled, result)
        assertEquals(R.string.rescue_mesh_key_not_enabled, result.errorMessageResource())
    }

    @Test fun `only permission rejection displays unauthorized`() {
        val result = failure(FirebaseFunctionsException.Code.PERMISSION_DENIED)
        assertEquals(AuthorityMeshKeyAccess.NotAuthorized, result)
        assertEquals(R.string.rescue_mesh_error_unauthorized, result.errorMessageResource())
    }

    @Test fun `expired sign in and missing certificate have actionable messages`() {
        assertEquals(AuthorityMeshKeyAccess.SignInRequired, failure(FirebaseFunctionsException.Code.UNAUTHENTICATED))
        assertEquals(AuthorityMeshKeyAccess.CertificateRequired,
            authorityMeshKeyFailure(MissingRoleCertificateException("missing")))
    }

    @Test fun `connection failure and unknown failure do not claim unauthorized`() {
        assertEquals(AuthorityMeshKeyAccess.NetworkError, failure(FirebaseFunctionsException.Code.UNAVAILABLE))
        assertEquals(AuthorityMeshKeyAccess.NetworkError, failure(FirebaseFunctionsException.Code.DEADLINE_EXCEEDED))
        val result = authorityMeshKeyFailure(IllegalStateException("invalid response"))
        assertEquals(AuthorityMeshKeyAccess.Failed, result)
        assertEquals(R.string.rescue_mesh_key_fetch_failed, result.errorMessageResource())
    }
}
