package com.auralis.crisisconnect.screens

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.auralis.crisisconnect.messaging.ContactAccessConsent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ContactDiscoveryConsentTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clearConsent() {
        app.getSharedPreferences(ContactAccessConsent.PREFS, 0).edit().clear().commit()
    }

    @Test
    fun `existing system permission never authorizes initial resume retry or forced refresh`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val model = AddFromContactsViewModel(app)
        // No Firebase initialization: any accidental lookup would fail this test.
        model.load()
        model.load()
        model.load(force = true)
        assertFalse(model.hasDiscoveryConsent)
        assertEquals(AddFromContactsViewModel.UiState.Idle, model.state.value)
    }

    @Test
    fun `acceptance without system permission cannot read or upload contacts`() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        val model = AddFromContactsViewModel(app)
        model.acceptDiscoveryDisclosure()
        model.load(force = true)
        assertTrue(model.hasDiscoveryConsent)
        assertEquals(AddFromContactsViewModel.UiState.Idle, model.state.value)
    }

    @Test
    fun `new screen remembers explicit consent and revocation blocks stale view model`() {
        val previous = AddFromContactsViewModel(app)
        previous.acceptDiscoveryDisclosure()
        assertTrue(AddFromContactsViewModel(app).hasDiscoveryConsent)
        ContactAccessConsent(app).revoke(true)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        previous.load(force = true)
        assertFalse(previous.hasDiscoveryConsent)
        assertEquals(AddFromContactsViewModel.UiState.Idle, previous.state.value)
    }

    @Test
    fun `nearby consent cannot authorize upload and accounts remain isolated`() {
        val first = ContactAccessConsent(app) { "alice" }
        first.accept(false)
        assertTrue(first.isGranted(false))
        assertFalse(first.isGranted(true))
        first.accept(true)
        assertFalse(ContactAccessConsent(app) { "bob" }.isGranted(true))
        assertTrue(ContactAccessConsent(app) { "alice" }.isGranted(true))
    }

    @Test
    fun `revoke then accept cannot resume an earlier batch token`() {
        val store = ContactAccessConsent(app) { "alice" }
        store.accept(true)
        val token = store.token(true)!!
        assertTrue(store.isCurrent(true, token))
        store.revoke(true)
        assertFalse(store.isCurrent(true, token))
        store.accept(true)
        assertFalse(store.isCurrent(true, token))
    }

    @Test
    fun `old disclosure version does not grant current consent`() {
        app.getSharedPreferences(ContactAccessConsent.PREFS, 0).edit()
            .putString("v1:alice:online", "old-consent").commit()
        assertFalse(ContactAccessConsent(app) { "alice" }.isGranted(true))
    }
}
