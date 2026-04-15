package com.auralis.crisisconnect.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreBackedPreferencesInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefName = "it_keystore_backed_preferences"
    private val keyAlias = "it_keystore_backed_preferences_alias"

    @Before
    fun setUp() {
        clearState()
    }

    @After
    fun tearDown() {
        clearState()
    }

    @Test
    fun putAndGetString_roundTrips() {
        val prefs = KeystoreBackedPreferences(context, prefName, keyAlias)

        prefs.putString("uid", "user-123")

        assertEquals("user-123", prefs.getString("uid", null))
    }

    @Test
    fun rawSharedPreferencesValue_isEncrypted_notPlaintext() {
        val prefs = KeystoreBackedPreferences(context, prefName, keyAlias)
        prefs.putString("secret", "uid:fieldteam:41.0,29.0")

        val raw = context.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
            .getString("secret", null)

        assertNotNull(raw)
        raw ?: return
        assertFalse(raw.contains("fieldteam"))
        assertFalse(raw.contains("41.0,29.0"))
        val decoded = Base64.decode(raw, Base64.DEFAULT)
        assertNotNull(decoded)
        assertFalse(decoded.isEmpty())
    }

    @Test
    fun removeAndNumericHelpers_workAsExpected() {
        val prefs = KeystoreBackedPreferences(context, prefName, keyAlias)
        prefs.putInt("attempt", 7)
        prefs.putLong("timestamp", 123456789L)

        assertEquals(7, prefs.getInt("attempt", -1))
        assertEquals(123456789L, prefs.getLong("timestamp", -1L))

        prefs.remove("attempt", "timestamp")

        assertEquals(-1, prefs.getInt("attempt", -1))
        assertEquals(-1L, prefs.getLong("timestamp", -1L))
    }

    private fun clearState() {
        context.deleteSharedPreferences(prefName)
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }
    }
}
