package com.auralis.crisisconnect.screens.settings

import android.app.Application
import android.os.Looper
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.MultiFactorInfo
import com.google.firebase.auth.MultiFactorResolver
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ProfileSecondFactorSignInTest {
    private val resolver = mockk<MultiFactorResolver>()
    private val flow = ProfileSecondFactorSignIn(mockk<FirebaseAuth>()) {}
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    private fun factor(id: String, type: String) = mockk<MultiFactorInfo> {
        every { uid } returns id
        every { factorId } returns type
        every { displayName } returns null
    }
    private fun challenge(vararg factors: MultiFactorInfo): com.google.android.gms.tasks.Task<AuthResult> {
        every { resolver.hints } returns factors.toList()
        val error = mockk<FirebaseAuthMultiFactorException>(relaxed = true) {
            every { this@mockk.resolver } returns this@ProfileSecondFactorSignInTest.resolver
        }
        return flow.continueSignIn(Tasks.forException<AuthResult>(error)).also { drain() }
    }

    @Test fun `ordinary success reaches the original success handler without a challenge`() {
        val user = mockk<AuthResult>()
        val result = flow.continueSignIn(Tasks.forResult(user))
        drain()
        assertSame(user, result.result)
        assertNull(flow.state.value)
    }

    @Test fun `ordinary failure preserves the original error`() {
        val error = IllegalArgumentException("Invalid credentials")
        val result = flow.continueSignIn(Tasks.forException<AuthResult>(error))
        drain()
        assertSame(error, result.exception)
        assertNull(flow.state.value)
    }

    @Test fun `MFA holds provider completion and prefers authenticator over SMS`() {
        val result = challenge(factor("phone", "phone"), factor("authenticator", "totp"))
        assertFalse(result.isComplete)
        assertEquals("authenticator", flow.state.value?.selectedId)
        assertFalse(flow.state.value!!.isPhone)
    }

    @Test fun `wrong OTP can be retried and only verified result completes original task`() {
        val original = challenge(factor("authenticator", "totp"))
        val attempt = TaskCompletionSource<AuthResult>()
        every { resolver.resolveSignIn(any()) } returns attempt.task
        flow.verify("123456")
        assertTrue(flow.state.value!!.busy)
        attempt.setException(IllegalArgumentException("Invalid code"))
        drain()
        assertFalse(original.isComplete)
        assertEquals("Invalid code", flow.state.value?.error)
        assertFalse(flow.state.value!!.busy)
        val verified = mockk<AuthResult>()
        every { resolver.resolveSignIn(any()) } returns Tasks.forResult(verified)
        flow.verify("654321")
        drain()
        assertSame(verified, original.result)
        assertNull(flow.state.value)
    }

    @Test fun `cancel abandons the provider task and a fresh login gets a fresh challenge`() {
        val original = challenge(factor("first", "totp"))
        flow.cancel()
        drain()
        assertTrue(original.isCanceled)
        assertNull(flow.state.value)
        val next = challenge(factor("next", "totp"))
        assertFalse(next.isComplete)
        assertEquals("next", flow.state.value?.selectedId)
    }

    @Test fun `unsupported factors never complete authentication`() {
        val result = challenge(factor("unknown", "unsupported"))
        assertFalse(result.isComplete)
        assertTrue(flow.state.value!!.unsupported)
    }

    @Test fun `a late verification result cannot complete a cancelled challenge`() {
        val original = challenge(factor("authenticator", "totp"))
        val attempt = TaskCompletionSource<AuthResult>()
        every { resolver.resolveSignIn(any()) } returns attempt.task
        flow.verify("123456")
        flow.cancel()
        attempt.setResult(mockk())
        drain()
        assertTrue(original.isCanceled)
        assertNull(flow.state.value)
    }
}
