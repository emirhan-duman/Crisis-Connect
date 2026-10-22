package com.auralis.crisisconnect

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.crisisconnect.messaging.AuthorityMlsCredential
import com.auralis.crisisconnect.messaging.AuthorityMlsMessagePayload
import com.auralis.crisisconnect.messaging.AuthorityMlsMessagePayloadCodec
import com.auralis.crisisconnect.messaging.AuthorityMlsPendingApplicationStore
import com.auralis.crisisconnect.messaging.AuthorityMlsStagedApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthorityMlsPendingApplicationStoreTest {
    @Test
    fun stagesLoadsAndRemovesKeystoreSealedApplication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accountUid = "instrumented-account"
        val conversationId = "am2_" + "A".repeat(43)
        val messageId = "instrumented-message"
        val credential = AuthorityMlsCredential.encode(accountUid, "instrumented-device")
        val plaintext = AuthorityMlsMessagePayloadCodec.encode(
            AuthorityMlsMessagePayload(
                recipientUid = "instrumented-peer",
                recipientName = "Demo",
                senderName = "Test",
                text = "sealed outbox",
                sentAtMillis = 1,
            ),
        )

        AuthorityMlsPendingApplicationStore.remove(context, accountUid, conversationId, messageId)
        AuthorityMlsPendingApplicationStore.stage(
            context,
            accountUid,
            conversationId,
            AuthorityMlsStagedApplication(messageId, credential, plaintext),
        )
        val loaded = AuthorityMlsPendingApplicationStore.load(context, accountUid, conversationId)
        assertEquals(1, loaded.size)
        assertEquals(messageId, loaded.single().messageId)
        assertTrue(plaintext.contentEquals(loaded.single().plaintext))

        AuthorityMlsPendingApplicationStore.remove(context, accountUid, conversationId, messageId)
        assertTrue(AuthorityMlsPendingApplicationStore.load(context, accountUid, conversationId).isEmpty())
    }

    @Test
    fun migratesRetiredOutboxAndCleansItsRecoveryCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accountUid = "instrumented-migration-account"
        val oldConversationId = "am2_" + "B".repeat(43)
        val currentConversationId = "am2_" + "C".repeat(43)
        val messageId = "instrumented-migration-message"
        val credential = AuthorityMlsCredential.encode(accountUid, "instrumented-device")
        val plaintext = AuthorityMlsMessagePayloadCodec.encode(
            AuthorityMlsMessagePayload(
                recipientUid = "instrumented-peer",
                recipientName = "Demo",
                senderName = "Test",
                text = "migrate me",
                sentAtMillis = 2,
            ),
        )

        AuthorityMlsPendingApplicationStore.remove(context, accountUid, oldConversationId, messageId)
        AuthorityMlsPendingApplicationStore.remove(context, accountUid, currentConversationId, messageId)
        AuthorityMlsPendingApplicationStore.stage(
            context,
            accountUid,
            oldConversationId,
            AuthorityMlsStagedApplication(messageId, credential, plaintext),
        )

        assertEquals(
            1,
            AuthorityMlsPendingApplicationStore.migrate(
                context,
                accountUid,
                listOf(oldConversationId),
                currentConversationId,
            ),
        )
        assertEquals(
            0,
            AuthorityMlsPendingApplicationStore.migrate(
                context,
                accountUid,
                listOf(oldConversationId),
                currentConversationId,
            ),
        )
        assertEquals(messageId, AuthorityMlsPendingApplicationStore.load(
            context, accountUid, currentConversationId,
        ).single().messageId)
        assertTrue(AuthorityMlsPendingApplicationStore.load(
            context, accountUid, oldConversationId,
        ).isEmpty())

        AuthorityMlsPendingApplicationStore.remove(context, accountUid, oldConversationId, messageId)
        AuthorityMlsPendingApplicationStore.remove(context, accountUid, currentConversationId, messageId)
    }
}
