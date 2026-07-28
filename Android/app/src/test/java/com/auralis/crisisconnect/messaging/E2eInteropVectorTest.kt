package com.auralis.crisisconnect.messaging

import com.auralis.crisisconnect.security.Crypto
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Not a pass/fail assertion of behavior so much as a CROSS-PLATFORM VECTOR GENERATOR: it seals a
 * message with the real [E2eEnvelope] and dumps the wire fields + keys to a JSON file so the iOS
 * (CryptoKit) and web (WebCrypto) ports can be proven to decrypt the exact same bytes. Run with:
 *   ./gradlew :app:testInternalUnitTest --tests "*E2eInteropVectorTest"
 */
@RunWith(RobolectricTestRunner::class)
class E2eInteropVectorTest {

    @Test
    fun emitAndroidSealedVector() {
        val recipient = Crypto.generateEphemeralEcKeyPair()
        val sender = Crypto.generateEphemeralEcKeyPair()

        val senderUid = "uidSENDER"
        val recipientUid = "uidRECIP"
        val conversationId = "conv-interop-1"
        val content = MessageContent(templateCode = 0, text = "Enkaz altındayım, 2 kişi mahsur")

        val sealed = E2eEnvelope.seal(
            content = content,
            recipientPublicKey = recipient.public,
            senderPrivateKey = sender.private,
            senderUid = senderUid,
            recipientUid = recipientUid,
            conversationId = conversationId
        )

        // Self-check the round trip so the vector is known-good before other platforms consume it.
        val opened = E2eEnvelope.open(
            sealed = sealed,
            recipientPrivateKey = recipient.private,
            senderStaticPublicKey = sender.public,
            senderUid = senderUid,
            recipientUid = recipientUid,
            conversationId = conversationId
        )
        assertEquals(content.text, opened.text)
        assertEquals(content.templateCode, opened.templateCode)

        val json = buildString {
            append("{\n")
            append("  \"recipientPrivatePkcs8B64\": \"${MessagingKeyCodec.encodePrivateKey(recipient.private)}\",\n")
            append("  \"senderPublicSpkiB64\": \"${MessagingKeyCodec.encodePublicKey(sender.public)}\",\n")
            append("  \"ephemeralPublicKey\": \"${sealed.ephemeralPublicKey}\",\n")
            append("  \"nonce\": \"${sealed.nonce}\",\n")
            append("  \"ciphertext\": \"${sealed.ciphertext}\",\n")
            append("  \"alg\": \"${sealed.alg}\",\n")
            append("  \"senderUid\": \"$senderUid\",\n")
            append("  \"recipientUid\": \"$recipientUid\",\n")
            append("  \"conversationId\": \"$conversationId\",\n")
            append("  \"expectedTemplateCode\": ${content.templateCode},\n")
            append("  \"expectedText\": \"${content.text}\"\n")
            append("}\n")
        }

        val out = File(
            System.getProperty("e2e.vector.out")
                ?: (System.getProperty("java.io.tmpdir") + "/e2e_interop_vector.json")
        )
        out.writeText(json)
        println("E2E_INTEROP_VECTOR_WRITTEN=${out.absolutePath}")
    }
}
