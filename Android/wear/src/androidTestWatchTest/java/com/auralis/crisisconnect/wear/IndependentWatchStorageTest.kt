package com.auralis.crisisconnect.wear

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.wear.independent.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import android.util.Base64

class IndependentWatchStorageTest {
    @Test fun deviceKeySignsWithoutPhoneAndEncryptedJournalSurvivesReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceID = UUID.randomUUID().toString()
        val namespace = "independent-test-$deviceID"
        val journalFile = File(context.noBackupFilesDir, "$namespace.enc")
        val identity = IndependentWatchIdentity(deviceID)
        try {
            val enrollment = identity.enrollment("watchTestOwner")
            val publicKey = enrollment.getString("publicKey")
            val payload = IndependentWatchIdentity.b64("{\"version\":1,\"ownerUid\":\"watchTestOwner\",\"deviceID\":\"$deviceID\",\"publicKey\":\"$publicKey\",\"scopes\":[\"shortMessage\",\"sos\"]}".toByteArray())
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKey,Base64.NO_WRAP))))
            verifier.update((IndependentWatchIdentity.DOMAIN + payload).toByteArray())
            assertTrue(verifier.verify(Base64.decode(enrollment.getString("signature"),Base64.NO_WRAP)))
            assertEquals(publicKey, IndependentWatchIdentity(deviceID).publicKey())
            val store = IndependentJournalFile(context,namespace)
            val journal = IndependentWatchJournal(store)
            val message = journal.reserve("shortMessage","fixture-contact","confidential fixture payload")
            assertFalse(journalFile.readBytes().toString(Charsets.UTF_8).contains("confidential"))
            val restored = IndependentWatchJournal(IndependentJournalFile(context,namespace))
            assertEquals(message,restored.snapshot().single())
            restored.reconcile(message.id,"delivered")
            restored.reconcile(message.id,"accepted")
            assertEquals("delivered",IndependentWatchJournal(store).snapshot().single().state)
            val start=restored.reserve("startSOS","fixture-session","start")
            restored.reconcile(start.id,"accepted")
            val stop=restored.reserve("stopSOS","fixture-session","stop")
            restored.reconcile(stop.id,"accepted")
            assertEquals(listOf(message.id,start.id,stop.id),IndependentWatchJournal(store).snapshot().map{it.id})
            val corrupted=journalFile.readBytes();corrupted[corrupted.lastIndex]=(corrupted.last().toInt() xor 1).toByte();journalFile.writeBytes(corrupted)
            assertThrows(Exception::class.java){IndependentWatchJournal(store)}
            val vector=JSONObject(enrollment.toString()).put("payload",payload)
            File(context.filesDir,"independent-watch-signature.json").writeText(vector.toString())
        } finally {
            journalFile.delete()
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            keys.deleteEntry("crisis.watch.request.v1.$deviceID")
            keys.deleteEntry("crisis.watch.journal.v1.$namespace")
        }
    }
}
