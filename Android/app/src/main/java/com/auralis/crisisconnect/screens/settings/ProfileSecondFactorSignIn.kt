package com.auralis.crisisconnect.screens.settings

import android.app.Activity
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.FirebaseException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.MultiFactorAssertion
import com.google.firebase.auth.MultiFactorResolver
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneMultiFactorGenerator
import com.google.firebase.auth.PhoneMultiFactorInfo
import com.google.firebase.auth.TotpMultiFactorGenerator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ProfileSecondFactorOption(val id: String, val name: String?, val isPhone: Boolean)

internal data class ProfileSecondFactorState(
    val factors: List<ProfileSecondFactorOption> = emptyList(),
    val selectedId: String = "",
    val isPhone: Boolean = false,
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val unsupported: Boolean = false,
)

/** Keeps the original provider's success path pending until Firebase completes MFA. */
internal class ProfileSecondFactorSignIn(
    private val auth: FirebaseAuth,
    private val onChallenge: () -> Unit,
) {
    private val mutableState = MutableStateFlow<ProfileSecondFactorState?>(null)
    val state = mutableState.asStateFlow()
    private var resolver: MultiFactorResolver? = null
    private var completion: TaskCompletionSource<AuthResult>? = null
    private var cancellation: CancellationTokenSource? = null
    private var verificationId: String? = null
    private var smsRequest = 0

    fun continueSignIn(task: Task<AuthResult>): Task<AuthResult> {
        val token = CancellationTokenSource()
        val result = TaskCompletionSource<AuthResult>(token.token)
        task.addOnSuccessListener { result.trySetResult(it) }
            .addOnCanceledListener { token.cancel() }
            .addOnFailureListener { error ->
                if (error !is FirebaseAuthMultiFactorException) {
                    result.trySetException(error)
                    return@addOnFailureListener
                }
                cancel()
                resolver = error.resolver
                completion = result
                cancellation = token
                val options = error.resolver.hints.mapNotNull { hint ->
                    when (hint.factorId) {
                        TotpMultiFactorGenerator.FACTOR_ID -> ProfileSecondFactorOption(hint.uid, hint.displayName, false)
                        PhoneMultiFactorGenerator.FACTOR_ID -> ProfileSecondFactorOption(hint.uid, hint.displayName, true)
                        else -> null
                    }
                }
                val selected = options.firstOrNull { !it.isPhone } ?: options.firstOrNull()
                mutableState.value = ProfileSecondFactorState(
                    factors = options, selectedId = selected?.id.orEmpty(),
                    isPhone = selected?.isPhone == true, unsupported = options.isEmpty(),
                )
                onChallenge()
            }
        return result.task
    }

    fun select(id: String) {
        val current = mutableState.value ?: return
        if (current.busy) return
        val factor = current.factors.firstOrNull { it.id == id } ?: return
        smsRequest++
        verificationId = null
        mutableState.value = current.copy(selectedId = id, isPhone = factor.isPhone, codeSent = false, error = null)
    }

    fun sendSms(activity: Activity) {
        val pending = resolver ?: return
        val current = mutableState.value ?: return
        if (current.busy) return
        val hint = pending.hints.firstOrNull { it.uid == current.selectedId } as? PhoneMultiFactorInfo ?: return
        val request = ++smsRequest
        mutableState.value = current.copy(busy = true, error = null)
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                if (resolver === pending && request == smsRequest) {
                    resolve(PhoneMultiFactorGenerator.getAssertion(credential), pending)
                }
            }
            override fun onVerificationFailed(error: FirebaseException) {
                if (resolver === pending && request == smsRequest) fail(error)
            }
            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                if (resolver !== pending || request != smsRequest) return
                verificationId = id
                mutableState.update { it?.copy(busy = false, codeSent = true) }
            }
        }
        try {
            PhoneAuthProvider.verifyPhoneNumber(
                PhoneAuthOptions.newBuilder(auth).setActivity(activity)
                    .setMultiFactorSession(pending.session).setMultiFactorHint(hint)
                    .setTimeout(60L, TimeUnit.SECONDS).setCallbacks(callbacks).build()
            )
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun verify(code: String) {
        val pending = resolver ?: return
        val current = mutableState.value ?: return
        if (current.busy || current.unsupported || !code.matches(Regex("[0-9]{6}"))) return
        val assertion = if (current.isPhone) {
            val id = verificationId ?: return
            PhoneMultiFactorGenerator.getAssertion(PhoneAuthProvider.getCredential(id, code))
        } else {
            TotpMultiFactorGenerator.getAssertionForSignIn(current.selectedId, code)
        }
        resolve(assertion, pending)
    }

    private fun resolve(assertion: MultiFactorAssertion, pending: MultiFactorResolver) {
        smsRequest++
        mutableState.update { it?.copy(busy = true, error = null) }
        pending.resolveSignIn(assertion)
            .addOnSuccessListener { result ->
                if (resolver !== pending) return@addOnSuccessListener
                val original = completion
                clear()
                original?.trySetResult(result)
            }
            .addOnFailureListener { error ->
                if (resolver === pending) fail(error)
            }
    }

    private fun fail(error: Exception) {
        mutableState.update { it?.copy(busy = false, error = error.localizedMessage) }
    }

    fun cancel() {
        cancellation?.cancel()
        clear()
    }

    private fun clear() {
        smsRequest++
        resolver = null
        completion = null
        cancellation = null
        verificationId = null
        mutableState.value = null
    }
}
