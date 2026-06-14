package com.auralis.crisisconnect.security

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges the `crisisconnect://sso/callback` deep link (caught by MainActivity after the web SSO
 * flow) to ProfileViewModel, which exchanges the one-time code for a Firebase custom token.
 */
object EnterpriseSsoBridge {
    sealed interface Result {
        data class Code(val code: String) : Result
        data class Error(val reason: String?) : Result
    }

    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 4)
    val results: SharedFlow<Result> = _results.asSharedFlow()

    fun emit(result: Result) {
        _results.tryEmit(result)
    }
}
