package com.auralis.crisisconnect.security

import com.google.firebase.functions.FirebaseFunctionsException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirebaseAppCheckFailuresTest {
    @Test
    fun detectsExplicitAppCheckCallableFailure() {
        val error = functionsException(
            "Firebase App Check token is invalid.",
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            null
        )

        assertTrue(FirebaseAppCheckFailures.isLikelyAppCheckFailure(error))
    }

    @Test
    fun detectsNestedAppCheckCallableFailure() {
        val error = IllegalStateException(
            "Could not request attestation challenge.",
            functionsException(
                "Request rejected by App Check.",
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                null
            )
        )

        assertTrue(FirebaseAppCheckFailures.isLikelyAppCheckFailure(error))
    }

    @Test
    fun allowsGenericUnauthenticatedWhenCallableGateAlreadyRequiresAppCheck() {
        val error = functionsException(
            "UNAUTHENTICATED",
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            null
        )

        assertTrue(
            FirebaseAppCheckFailures.isLikelyAppCheckFailure(
                throwable = error,
                allowGenericUnauthenticated = true
            )
        )
    }

    @Test
    fun doesNotTreatGenericUnauthenticatedAsAppCheckByDefault() {
        val error = functionsException(
            "UNAUTHENTICATED",
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            null
        )

        assertFalse(FirebaseAppCheckFailures.isLikelyAppCheckFailure(error))
    }

    @Test
    fun doesNotTreatNormalAuthRequirementAsAppCheck() {
        val error = functionsException(
            "Authentication is required.",
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            null
        )

        assertFalse(
            FirebaseAppCheckFailures.isLikelyAppCheckFailure(
                throwable = error,
                allowGenericUnauthenticated = true
            )
        )
    }

    private fun functionsException(
        message: String,
        code: FirebaseFunctionsException.Code,
        details: Any?
    ): FirebaseFunctionsException {
        val constructor = FirebaseFunctionsException::class.java.getConstructor(
            String::class.java,
            FirebaseFunctionsException.Code::class.java,
            Any::class.java
        )
        return constructor.newInstance(message, code, details)
    }
}
