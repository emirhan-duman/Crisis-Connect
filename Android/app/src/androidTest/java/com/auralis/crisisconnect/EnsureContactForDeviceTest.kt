package com.auralis.crisisconnect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.ContactEntity
import com.auralis.crisisconnect.data.ensureContactForDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnsureContactForDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        resetDatabaseSingleton()
        context.deleteDatabase("app_database")
    }

    @After
    fun tearDown() {
        resetDatabaseSingleton()
        context.deleteDatabase("app_database")
    }

    private fun resetDatabaseSingleton() {
        val instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        (instanceField.get(null) as? AppDatabase)?.close()
        instanceField.set(null, null)
    }

    @Test
    fun ensureContactForDevice_normalizesExistingLowercaseAddress() {
        val dao = AppDatabase.getInstance(context).contactDao()
        dao.saveContact(
            ContactEntity(
                sessionCode = "TEST01",
                name = "Test Contact",
                aesKey = "",
                address = "aa:bb:cc:dd:ee:ff"
            )
        )

        val sessionCode = ensureContactForDevice(
            context = context,
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = null,
            isBonded = true
        )

        assertEquals("TEST01", sessionCode)

        val updated = dao.getContactBySessionCode("TEST01")
        assertEquals("AA:BB:CC:DD:EE:FF", updated?.address)
    }

    @Test
    fun ensureContactForDevice_prefersNonBleSessionWhenAddressMatches() {
        val dao = AppDatabase.getInstance(context).contactDao()
        dao.saveContact(
            ContactEntity(
                sessionCode = "ble:AA:BB:CC:DD:EE:FF",
                name = "BLE Contact",
                aesKey = "",
                address = "AA:BB:CC:DD:EE:FF"
            )
        )
        dao.saveContact(
            ContactEntity(
                sessionCode = "CLASSIC-CHAT",
                name = "Classic Contact",
                aesKey = "",
                address = "AA:BB:CC:DD:EE:FF"
            )
        )

        val sessionCode = ensureContactForDevice(
            context = context,
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "Device Name",
            isBonded = true
        )

        assertEquals("CLASSIC-CHAT", sessionCode)
    }

    @Test
    fun ensureContactForDevice_createsClassicSessionWhenOnlyBleExists() {
        val dao = AppDatabase.getInstance(context).contactDao()
        dao.saveContact(
            ContactEntity(
                sessionCode = "ble:AA:BB:CC:DD:EE:FF",
                name = "BLE Contact",
                aesKey = "",
                address = "AA:BB:CC:DD:EE:FF"
            )
        )

        val sessionCode = ensureContactForDevice(
            context = context,
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "CLASSIC-CHAT",
            isBonded = true
        )

        assertEquals("CLASSIC-CHAT", sessionCode)
        val classicContact = dao.getContactBySessionCode("CLASSIC-CHAT")
        assertEquals("AA:BB:CC:DD:EE:FF", classicContact?.address)
    }

    @Test
    fun ensureContactForDevice_reusesClassicQrContactWhenBleRuntimeAddressMatches() {
        val dao = AppDatabase.getInstance(context).contactDao()
        dao.saveContact(
            ContactEntity(
                sessionCode = "0GVZVG",
                name = "Mehmet Demir",
                aesKey = "base64-key",
                address = "",
                remoteSessionCode = "0GVZVG",
                bleShareId = "M9J7X49Q7X",
                lastKnownBleAddress = "14:96:E5:1D:A8:E7",
                remoteDeviceId = "remote-device-1"
            )
        )

        val sessionCode = ensureContactForDevice(
            context = context,
            address = "14:96:E5:1D:A8:E7",
            deviceName = "BLNAKF",
            isBonded = true
        )

        assertEquals("0GVZVG", sessionCode)
        val updated = dao.getContactBySessionCode("0GVZVG")
        assertEquals("14:96:E5:1D:A8:E7", updated?.address)
        assertEquals(null, dao.getContactBySessionCode("BLNAKF"))
    }

    @Test
    fun ensureContactForDevice_mergesWeakClassicAliasIntoQrBackedSession() {
        val dao = AppDatabase.getInstance(context).contactDao()
        dao.saveContact(
            ContactEntity(
                sessionCode = "0GVZVG",
                name = "Mehmet Demir",
                aesKey = "base64-key",
                address = "",
                remoteSessionCode = "0GVZVG",
                bleShareId = "M9J7X49Q7X",
                lastKnownBleAddress = "14:96:E5:1D:A8:E7",
                remoteDeviceId = "remote-device-1"
            )
        )
        dao.saveContact(
            ContactEntity(
                sessionCode = "BLNAKF",
                name = "BLNAKF",
                aesKey = "",
                address = "14:96:E5:1D:A8:E7"
            )
        )

        val sessionCode = ensureContactForDevice(
            context = context,
            address = "14:96:E5:1D:A8:E7",
            deviceName = "BLNAKF",
            isBonded = true
        )

        assertEquals("0GVZVG", sessionCode)
        val canonical = dao.getContactBySessionCode("0GVZVG")
        assertEquals("14:96:E5:1D:A8:E7", canonical?.address)
        assertEquals(null, dao.getContactBySessionCode("BLNAKF"))
    }
}
