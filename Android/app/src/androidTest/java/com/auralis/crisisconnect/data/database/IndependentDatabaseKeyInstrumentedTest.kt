package com.auralis.crisisconnect.data.database

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.data.toContact
import com.auralis.crisisconnect.data.toEntity
import com.auralis.crisisconnect.data.AppDatabase
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndependentDatabaseKeyInstrumentedTest {
    @Test fun qrVersionMarkerNeverUpgradesLegacyOrCoercedPayloads() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        for (marker in listOf("", ",\"offlineProtocolVersion\":\"2\"", ",\"offlineProtocolVersion\":2.5", ",\"offlineProtocolVersion\":3")) {
            val raw = "{\"v\":1,\"code\":\"OLD123\",\"key\":\"$key\",\"platform\":\"android\"$marker}"
            assertFalse(com.auralis.crisisconnect.screens.QR.parseQrPayload(raw).supportsSecureOfflinePairing)
        }
        val fresh = "{\"v\":1,\"code\":\"NEW123\",\"key\":\"$key\",\"platform\":\"ios\",\"offlineProtocolVersion\":2}"
        assertTrue(com.auralis.crisisconnect.screens.QR.parseQrPayload(fresh).supportsSecureOfflinePairing)
    }

    @Test fun privateQrControlReassemblesOnAndroidAndBindsSessionProofWithoutPublicIdentity() {
        val wire = com.auralis.crisisconnect.service.p2p.P2pPrivacyWire
        val key = ByteArray(32) { it.toByte() }
        val plaintext = "{\"clientDeviceId\":\"private-device\",\"clientName\":\"Ayşe\",\"clientPeerUid\":\"private-account\"}".toByteArray()
        val sealed = wire.seal(plaintext, key, com.auralis.crisisconnect.service.p2p.P2pPrivacyWire.Purpose.CONTROL)!!
        val framed = wire.frameControl(sealed)!!
        val receiver = com.auralis.crisisconnect.service.p2p.P2pPrivacyWire.ControlReceiver()
        var assembled: ByteArray? = null
        framed.asList().chunked(20).forEach { chunk -> receiver.receive(chunk.toByteArray())?.let { assembled = it } }
        assertArrayEquals(plaintext, wire.open(assembled!!, key, com.auralis.crisisconnect.service.p2p.P2pPrivacyWire.Purpose.CONTROL))
        assertFalse(sealed.toString(Charsets.UTF_8).contains("private-device"))
        assertNull(wire.open(sealed, ByteArray(32), com.auralis.crisisconnect.service.p2p.P2pPrivacyWire.Purpose.CONTROL))
        val alias = wire.alias(key, sealed)
        assertTrue(wire.matches(alias, key, sealed))
        assertFalse(wire.matches(alias, key, sealed + 1.toByte()))
        val challenge = com.auralis.crisisconnect.service.p2p.P2pPrivacyWire.Challenge()
        assertFalse(challenge.accept("captured-response"))
        assertTrue(challenge.accept(challenge.nonce))
        assertFalse(challenge.accept(challenge.nonce))
        val canonical = "CC-00112233445566778899AABB"
        val info = com.auralis.crisisconnect.service.BlePeerIdentityUtils.buildPeerInfoPayload("Victim", "victim", broadcastId = canonical)
        assertEquals(canonical, com.auralis.crisisconnect.service.BlePeerIdentityUtils.parsePeerInfoPayload(info)?.broadcastId)
    }

    @Test fun authorityKeyCacheRejectsLegacyKeysAndStopsAtLeaseExpiry() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = com.auralis.crisisconnect.security.AuthorityMeshKeyStore
        try {
            store.clearGroupKey(context)
            val key = Base64.getEncoder().encodeToString(ByteArray(32) { 19 })
            com.auralis.crisisconnect.security.KeystoreBackedPreferences(context, "authority_mesh_keys", "cc_authority_mesh_key_store")
                .putString("authority_mesh_group_key_v1", key)
            assertNull("Unbounded legacy group keys must never be loaded", store.loadGroupKey(context))
            val now = System.currentTimeMillis()
            val period = com.auralis.crisisconnect.security.AuthorityMeshKeyLease.PERIOD_MS
            val epoch = now / period
            val owner = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            store.saveGroupKey(context, com.auralis.crisisconnect.security.AuthorityMeshKeyLease(
                "authority-v2-$epoch", key, owner, epoch * period, minOf(now + 1_000, (epoch + 1) * period)))
            assertArrayEquals(ByteArray(32) { 19 }, store.loadGroupKey(context))
            Thread.sleep(1_100)
            assertNull("An in-memory key must stop working at expiry too", store.loadGroupKey(context))
        } finally { store.clearGroupKey(context); context.close() }
    }
    @Test fun authenticatedReceiptCannotAcknowledgeAnotherConversation() {
        System.loadLibrary("sqlcipher")
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            open(context, "isolated-receipt-database").withDatabase { database ->
                kotlinx.coroutines.runBlocking {
                    val contacts = database.contactDao()
                    val messages = database.messageDao()
                    for (session in listOf("ALICE1", "BOB123")) {
                        contacts.saveContact(com.auralis.crisisconnect.data.ContactEntity(
                            sessionCode = session, name = session, aesKey = "", address = ""))
                        messages.insert(com.auralis.crisisconnect.data.MessageEntity(
                            sessionCode = session, messageUuid = "message-$session", text = "emergency",
                            messageType = com.auralis.crisisconnect.data.MessageType.TEXT,
                            isLocal = true, isRead = false, timestampMillis = 1_000,
                            deliveryStatus = com.auralis.crisisconnect.data.MessageDeliveryStatus.SENT))
                    }
                    messages.markLocalMessagesDelivered("BOB123", listOf("message-ALICE1"))
                    messages.markLocalMessagesRead("BOB123", listOf("message-ALICE1"))
                    assertEquals(com.auralis.crisisconnect.data.MessageDeliveryStatus.SENT,
                        messages.getMessageByUuid("message-ALICE1")?.deliveryStatus)
                    messages.markLocalMessagesDelivered("ALICE1", listOf("message-ALICE1"))
                    assertEquals(com.auralis.crisisconnect.data.MessageDeliveryStatus.DELIVERED,
                        messages.getMessageByUuid("message-ALICE1")?.deliveryStatus)
                    messages.markLocalMessagesRead("ALICE1", listOf("message-ALICE1"))
                    assertEquals(com.auralis.crisisconnect.data.MessageDeliveryStatus.READ,
                        messages.getMessageByUuid("message-ALICE1")?.deliveryStatus)
                }
            }
        } finally { context.close() }
    }
    /** Every test owns its DB and preferences; never touch the running application's database. */
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "security_key_test_${UUID.randomUUID()}_"
        private val root = File(base.cacheDir, prefix).also { check(it.mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)
        override fun deleteSharedPreferences(name: String): Boolean = super.deleteSharedPreferences(prefix + name)
        fun close() {
            root.deleteRecursively()
            for (name in listOf("crisisconnect_secure_prefs_v2", "crisisconnect_secure_prefs", "__androidx_security_crypto_encrypted_prefs__", "authority_mesh_keys")) {
                deleteSharedPreferences(name)
            }
        }
    }

    private fun open(context: Context, key: String): AppDatabase {
        val method = AppDatabase.Companion.javaClass.getDeclaredMethod("createAndOpenDatabase", Context::class.java, ByteArray::class.java)
        method.isAccessible = true
        try { return method.invoke(AppDatabase.Companion, context, key.toByteArray()) as AppDatabase }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    private inline fun <T> AppDatabase.withDatabase(block: (AppDatabase) -> T): T {
        try { return block(this) } finally { close() }
    }

    private fun migrate(context: Context): AppDatabase {
        val method = AppDatabase.Companion.javaClass.getDeclaredMethod("openWithIndependentKey", Context::class.java)
        method.isAccessible = true
        try { return method.invoke(AppDatabase.Companion, context) as AppDatabase }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    @Test fun legacyDatabaseRekeysWithoutLosingDataAcrossEitherCrashWindow() {
        System.loadLibrary("sqlcipher")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for ((schemaVersion, crashAfterRekey) in listOf(29 to false, 29 to true, 30 to false, 30 to true)) {
            val context = IsolatedContext(base)
            try {
                val old = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
                LocalKeyStorage.saveAesKey(context, old)
                open(context, old).withDatabase { legacy ->
                    legacy.openHelper.writableDatabase.execSQL("CREATE TABLE security_key_probe(value TEXT NOT NULL)")
                    legacy.openHelper.writableDatabase.execSQL("INSERT INTO security_key_probe VALUES ('retained-message')")
                    for (session in listOf("LEGACY1", "LEGACY2")) {
                        legacy.contactDao().saveContact(com.auralis.crisisconnect.data.ContactEntity(
                            sessionCode = session, name = session, aesKey = old, address = ""))
                    }
                    kotlinx.coroutines.runBlocking {
                        legacy.messageDao().insert(com.auralis.crisisconnect.data.MessageEntity(
                            messageUuid = "badge-legacy", sessionCode = "LEGACY1", text = "Retained emergency", timestampMillis = 100,
                            isLocal = false, isRead = false, originVerifiedRole = if (schemaVersion == 29) "fieldteam" else null,
                            originVerifiedAtMillis = if (schemaVersion == 29) 100 else null))
                    }
                    // Build the real old schema: provenance did not exist in v29/v30.
                    legacy.openHelper.writableDatabase.execSQL("ALTER TABLE contacts DROP COLUMN offlineKeyVersion")
                    legacy.openHelper.writableDatabase.execSQL("PRAGMA user_version = $schemaVersion")
                }
                assertEquals(old, LocalKeyStorage.getOrCreateSqlCipherKey(context))
                val pending = LocalKeyStorage.prepareIndependentSqlCipherKey(context)
                assertNotEquals(old, pending)
                if (crashAfterRekey) open(context, old).withDatabase {
                    (it.openHelper.writableDatabase as net.zetetic.database.sqlcipher.SQLiteDatabase)
                        .changePassword(pending.toByteArray())
                }
                migrate(context).withDatabase { upgraded ->
                    upgraded.openHelper.writableDatabase.query("SELECT value FROM security_key_probe").use {
                        assertTrue(it.moveToFirst()); assertEquals("retained-message", it.getString(0))
                    }
                    kotlinx.coroutines.runBlocking {
                        val message = upgraded.messageDao().getMessageByUuid("badge-legacy")!!
                        assertEquals("Retained emergency", message.text)
                        assertNull(message.originVerifiedRole); assertNull(message.originVerifiedAtMillis)
                    }
                    assertEquals(2, upgraded.contactDao().getContacts().size)
                    assertTrue(upgraded.contactDao().getContacts().all { it.aesKey == old && it.offlineKeyVersion == 1 && !it.verified })
                    val dao = upgraded.contactDao()
                    val legacyRow = dao.getContactBySessionCode("LEGACY1")!!
                    assertTrue(legacyRow.toContact().offlineRepairRequired)
                    assertEquals("", legacyRow.toContact().aesKey)
                    dao.updateContactAesKey("LEGACY1", "remote-handshake-must-not-repair")
                    assertEquals(old, dao.getContactBySessionCode("LEGACY1")!!.aesKey)
                    val renamed = legacyRow.toContact().copy(name = "Family")
                    dao.saveContact(com.auralis.crisisconnect.data.preserveLegacyOfflineStorage(renamed.toEntity(), legacyRow))
                    assertEquals(old, dao.getContactBySessionCode("LEGACY1")!!.aesKey)
                    dao.saveContact(legacyRow.copy(offlineKeyVersion = 2, verified = true, verifiedIdentityKey = "device:claimed"))
                    assertTrue(dao.getContactBySessionCode("LEGACY1")!!.toContact().offlineRepairRequired)
                    assertEquals("", dao.getContactBySessionCode("LEGACY1")!!.toContact().aesKey)
                    val fresh = Base64.getEncoder().encodeToString(ByteArray(32) { 55 })
                    dao.saveContact(legacyRow.copy(aesKey = fresh, offlineKeyVersion = 2, verified = false, verifiedIdentityKey = "", verifiedAt = null))
                    val repaired = dao.getContactBySessionCode("LEGACY1")!!.toContact()
                    assertEquals(fresh, repaired.aesKey); assertFalse(repaired.offlineRepairRequired)
                    dao.saveContact(legacyRow.copy(name = "Delayed legacy metadata"))
                    assertEquals(fresh, dao.getContactBySessionCode("LEGACY1")!!.toContact().aesKey)
                    assertEquals(2, dao.getContactBySessionCode("LEGACY1")!!.offlineKeyVersion)
                    assertEquals(old, dao.getContactBySessionCode("LEGACY2")!!.aesKey)
                }
                assertEquals(pending, LocalKeyStorage.getOrCreateSqlCipherKey(context))
                assertEquals(pending, LocalKeyStorage.prepareIndependentSqlCipherKey(context))
                var rejected = false
                try { open(context, old).close() } catch (_: Exception) { rejected = true }
                assertTrue("A contact's former key must no longer unlock the DB", rejected)
                open(context, pending).withDatabase { reopened ->
                    assertEquals(old, reopened.contactDao().getContactBySessionCode("LEGACY2")!!.aesKey)
                    assertTrue(reopened.contactDao().getContactBySessionCode("LEGACY2")!!.toContact().offlineRepairRequired)
                }
            } finally { context.close() }
        }
    }

    @Test fun unreadableKeyStateIsRetainedAndNeverReplaced() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val prefs = context.getSharedPreferences("crisisconnect_secure_prefs_v2", Context.MODE_PRIVATE)
            check(prefs.edit().putString("local_sqlcipher_key", "broken-ciphertext").commit())
            var rejected = false
            try { LocalKeyStorage.getOrCreateSqlCipherKey(context) } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertEquals("broken-ciphertext", prefs.getString("local_sqlcipher_key", null))
        } finally { context.close() }
    }

    @Test fun freshDatabaseKeyIsIndependentEvenWhenDeviceKeyAlreadyExists() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val aes = LocalKeyStorage.getOrCreateAesKey(context)
            assertNotEquals(aes, LocalKeyStorage.getOrCreateSqlCipherKey(context))
        } finally { context.close() }
    }

    @Test fun signalBindingIsScopedAndCannotBeSilentlyReplacedOrReplayedBack() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val binding = com.auralis.crisisconnect.messaging.signal.SignalPeerBinding(context)
            val first = Base64.getEncoder().encodeToString(ByteArray(33) { 5 })
            val replacement = Base64.getEncoder().encodeToString(ByteArray(33) { 7 })
            assertTrue(binding.markMigrationPending("owner", "peer", "p256"))
            assertTrue(com.auralis.crisisconnect.messaging.signal.SignalPeerBinding(context).migrationPending("owner", "peer", "p256"))
            assertTrue(binding.accept("owner", "peer", "p256", first))
            assertFalse(binding.migrationPending("owner", "peer", "p256"))
            assertEquals(first, binding.read("owner", "peer", "p256"))
            assertNull(binding.read("different-owner", "peer", "p256"))
            assertNull(binding.read("owner", "different-peer", "p256"))
            assertNull(binding.read("owner", "peer", "different-p256"))
            assertFalse(binding.accept("owner", "peer", "p256", replacement))
            assertTrue(binding.changed("owner", "peer", "p256"))
            assertFalse(binding.accept("owner", "peer", "p256", first))
            assertEquals(first, binding.read("owner", "peer", "p256"))
            binding.reset("owner", "peer", "p256")
            assertTrue(binding.accept("owner", "peer", "p256", replacement))
        } finally { context.close() }
    }
}
