package com.auralis.crisisconnect.security

import com.google.firebase.functions.FirebaseFunctionsException
import java.util.Locale

object FirebaseAppCheckFailures {
    private val appCheckMarkers = listOf(
        "app check",
        "appcheck",
        "app-check",
        "x-firebase-appcheck",
        "firebase app check",
        "play integrity",
        "app attestation"
    )

    private val genericUnauthenticatedMarkers = listOf(
        "unauthenticated",
        "valid authentication credentials"
    )

    fun isLikelyAppCheckFailure(
        throwable: Throwable,
        allowGenericUnauthenticated: Boolean = false
    ): Boolean {
        return throwable
            .causeSequence()
            .any { candidate ->
                val evidence = candidate.evidenceText()
                if (appCheckMarkers.any(evidence::contains)) return@any true

                val functionsError = candidate as? FirebaseFunctionsException ?: return@any false
                val code = functionsError.code
                val rejectedByCallableGate = code == FirebaseFunctionsException.Code.UNAUTHENTICATED ||
                    code == FirebaseFunctionsException.Code.PERMISSION_DENIED
                if (!rejectedByCallableGate) return@any false

                appCheckMarkers.any(evidence::contains) ||
                    (
                        allowGenericUnauthenticated &&
                            code == FirebaseFunctionsException.Code.UNAUTHENTICATED &&
                            genericUnauthenticatedMarkers.any(evidence::contains)
                        )
            }
    }

    private fun Throwable.causeSequence(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private fun Throwable.evidenceText(): String {
        val functionsError = this as? FirebaseFunctionsException
        return listOfNotNull(
            message,
            functionsError?.details?.toString(),
            cause?.message
        )
            .joinToString(separator = " ")
            .lowercase(Locale.US)
    }
}
