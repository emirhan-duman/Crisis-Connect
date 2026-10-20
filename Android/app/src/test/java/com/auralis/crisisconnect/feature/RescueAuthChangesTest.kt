package com.auralis.crisisconnect.feature

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RescueAuthChangesTest {
    @Test fun `login account switch and logout are emitted without screen recreation`() = runTest {
        val auth = mockk<FirebaseAuth>(relaxed = true)
        val listener = slot<FirebaseAuth.AuthStateListener>()
        var currentUser: FirebaseUser? = null
        every { auth.currentUser } answers { currentUser }
        every { auth.addAuthStateListener(capture(listener)) } answers {
            listener.captured.onAuthStateChanged(auth)
        }
        val accounts = mutableListOf<String?>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rescueAuthChanges(auth).collect { accounts.add(it) }
        }
        currentUser = mockk { every { uid } returns "field-team-account" }
        listener.captured.onAuthStateChanged(auth)
        listener.captured.onAuthStateChanged(auth)
        currentUser = mockk { every { uid } returns "civilian-account" }
        listener.captured.onAuthStateChanged(auth)
        currentUser = null
        listener.captured.onAuthStateChanged(auth)
        assertEquals(listOf(null, "field-team-account", "civilian-account", null), accounts)
        job.cancel()
        job.join()
        verify(exactly = 1) { auth.removeAuthStateListener(listener.captured) }
    }
}
