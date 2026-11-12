package com.auralis.crisisconnect.messaging.signal

import android.app.Application
import android.content.Context
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContactByPeerUid
import com.auralis.crisisconnect.messaging.InternetMessagingClient
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class SignalLegacySessionGateTest {
    @After fun cleanup() = unmockkAll()

    @Test fun unboundLegacyRatchetsDoNotBlockPinnedP256ButExplicitMigrationStillDoes() {
        val context: Context = RuntimeEnvironment.getApplication()
        val client = mockk<InternetMessagingClient>()
        every { client.currentUid() } returns "alice"
        mockkStatic("com.auralis.crisisconnect.data.ContactRepositoryKt")
        every { getContactByPeerUid(context, "bob") } returns Contact("Bob", "pair", "thread",
            peerUid = "bob", peerPublicKey = "pinned-p256")
        mockkConstructor(KeystoreBackedPreferences::class)
        every { anyConstructed<KeystoreBackedPreferences>().getString(any(), any()) } returns null
        var pending = false
        every { anyConstructed<KeystoreBackedPreferences>().contains(any()) } answers { pending }
        every { anyConstructed<KeystoreBackedPreferences>().putStringCommitted(any(), any()) } answers { pending = true; true }
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(any()) } throws IllegalStateException("An unbound raw legacy session is not a protocol pin")
        val constructor = SignalSessionGate::class.java.getDeclaredConstructor(Context::class.java,
            SignalMessageTransport::class.java, InternetMessagingClient::class.java).apply { isAccessible = true }
        val gate = constructor.newInstance(context, mockk<SignalMessageTransport>(), client)
        assertFalse(gate.peerUpgradeRequired("bob"))
        for (ciphertext in listOf("AA==", "not base64")) {
            assertTrue(runCatching { gate.decrypt("bob", "prekey", ciphertext) }.isFailure)
            assertFalse("Unauthenticated input must not start a migration", pending)
            assertFalse(gate.peerUpgradeRequired("bob"))
        }
        pending = true
        assertTrue(gate.peerUpgradeRequired("bob"))
    }
}
