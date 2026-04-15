package com.auralis.crisisconnect.data.database

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalKeyStorageInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        clearSecurePreferences()
    }

    @After
    fun tearDown() {
        clearSecurePreferences()
    }

    @Test
    fun saveRole_isBoundToUid_andClearedWhenUidChanges() {
        LocalKeyStorage.saveUid(context, "uid-1")
        LocalKeyStorage.saveRole(context, "admin")
        assertEquals("admin", LocalKeyStorage.getSavedRole(context))

        LocalKeyStorage.saveUid(context, "uid-2")

        assertNull(LocalKeyStorage.getSavedRole(context))
    }

    @Test
    fun rescueDeviceId_isStableAndDecodable() {
        val first = LocalKeyStorage.getOrCreateRescueDeviceId(context)
        val second = LocalKeyStorage.getOrCreateRescueDeviceId(context)

        assertEquals(first, second)
        assertTrue(first.startsWith("cc-"))
        assertEquals(27, first.length)

        val bytes = LocalKeyStorage.decodeRescueDeviceIdToBytes(first)
        assertNotNull(bytes)
        assertEquals(12, bytes?.size)

        val generatedBytes = LocalKeyStorage.getRescueDeviceIdBytes(context)
        assertEquals(12, generatedBytes.size)
    }

    @Test
    fun aesKeyAndSqlCipherKey_areStoredAsValidBase64() {
        val keyBytes = ByteArray(32) { index -> (index + 10).toByte() }
        val keyBase64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        LocalKeyStorage.saveAesKey(context, keyBase64)
        val storedAesKey = LocalKeyStorage.getOrCreateAesKey(context)
        val storedSqlCipherKey = LocalKeyStorage.getOrCreateSqlCipherKey(context)

        val aesDecoded = Base64.decode(storedAesKey, Base64.DEFAULT)
        val sqlDecoded = Base64.decode(storedSqlCipherKey, Base64.DEFAULT)
        assertEquals(32, aesDecoded.size)
        assertTrue(sqlDecoded.size == 16 || sqlDecoded.size == 32)
    }

    @Test
    fun invalidAesKeyInput_isIgnoredAndDoesNotPoisonStorage() {
        LocalKeyStorage.saveAesKey(context, "%%%invalid%%%")
        val generated = LocalKeyStorage.getOrCreateAesKey(context)
        val decoded = Base64.decode(generated, Base64.DEFAULT)

        assertEquals(32, decoded.size)
        assertNotEquals("%%%invalid%%%", generated)
    }

    private fun clearSecurePreferences() {
        context.deleteSharedPreferences("crisisconnect_secure_prefs_v2")
        context.deleteSharedPreferences("crisisconnect_secure_prefs")
        context.deleteSharedPreferences("__androidx_security_crypto_encrypted_prefs__")
    }
}
