package com.auralis.crisisconnect.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.updateContactPeerIdentity
import io.mockk.*
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RfcommPayloadSecurityTest {
    @Test fun untrustedFramesCannotPinIdentityOrInjectCleartextButPairedEncryptedChatWorks() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = ByteArray(32) { 12 }
        val contact = Contact(name = "Peer", aesKey = Base64.getEncoder().encodeToString(key), sessionCode = "pair")
        mockkStatic("com.auralis.crisisconnect.data.ContactRepositoryKt", "com.auralis.crisisconnect.data.MessageRepositoryKt")
        try {
            every { getContact(any(), any()) } returns contact
            every { updateContactPeerIdentity(any(), any(), any(), any()) } just Runs
            coEvery { saveRemoteMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            val notified = mutableListOf<String?>()
            val writes = mutableListOf<String>()
            val delegate = delegate(context, this, notified, writes)
            for (payload in listOf("PEER_IDENTITY|attacker|fake-key", "MSG|forged|help", "MSG|incomplete", "unframed text", "VOICE|forged", "DECRYPT_FAIL|message")) {
                delegate.handleIncomingPayload("pair", payload)
            }
            advanceUntilIdle()
            verify(exactly = 0) { updateContactPeerIdentity(any(), any(), any(), any()) }
            coVerify(exactly = 0) { saveRemoteMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertTrue(notified.isEmpty()); assertTrue(writes.isEmpty())
            val wrongKey = requireNotNull(SecureRfcommText.seal(ByteArray(32), "forged", "injected"))
            delegate.handleIncomingPayload("pair", wrongKey.trimEnd())
            advanceUntilIdle(); assertTrue(notified.isEmpty())
            delegate.handleIncomingPayload("pair", requireNotNull(SecureRfcommText.seal(key, "legitimate", "paired help")).trimEnd())
            advanceUntilIdle()
            assertEquals(listOf("paired help"), notified)
            coVerify(exactly = 1) {
                saveRemoteMessage(context, "pair", "legitimate", "paired help", any(), any(), any(), any(), any(), any(), any(), route = "rfcomm")
            }
            assertTrue(writes.any { it.startsWith("SEC_RECEIPT|") })
        } finally { unmockkAll() }
    }

    private fun delegate(context: Context, scope: CoroutineScope, notified: MutableList<String?>, writes: MutableList<String>) = RfcommIncomingPayloadDelegate(
        appContext = context, serviceScope = scope, isSessionConnected = { true },
        writeToSession = { _, bytes -> writes += bytes.toString(Charsets.UTF_8); true },
        onSessionActive = {}, onHandleHandshakeRequest = { _, _ -> }, onHandleHandshakeAck = { _, _ -> },
        onHandleContactInfo = { _, _ -> }, onHandleCallFrame = { _, _ -> false },
        maybeNotifyIncomingMessage = { _, _, text -> notified += text },
        voiceKey = { a, b -> "$a:$b" }, imageKey = { a, b -> "$a:$b" }, fileKey = { a, b -> "$a:$b" }, mapShareKey = { a, b -> "$a:$b" },
        voiceSenders = ConcurrentHashMap(), voiceReceivers = ConcurrentHashMap(), imageSenders = ConcurrentHashMap(), imageReceivers = ConcurrentHashMap(),
        fileSenders = ConcurrentHashMap(), fileReceivers = ConcurrentHashMap(), mapShareSenders = ConcurrentHashMap(), mapShareReceivers = ConcurrentHashMap(),
        updateVoiceProgress = { _, _ -> }, clearVoiceProgress = {}, updateImageProgress = { _, _ -> }, clearImageProgress = {}, offlineMapShareMime = "application/test"
    )
}
