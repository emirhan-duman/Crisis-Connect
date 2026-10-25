package com.auralis.crisisconnect

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentProviderOperation
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.Person
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.auralis.crisisconnect.messaging.DeviceContactsReader
import com.auralis.crisisconnect.service.RfcommCallNotificationDelegate
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.util.LocalNetworkPermissions
import com.auralis.crisisconnect.verification.ContactPickerVerificationActivity
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a clean API 37 emulator with the synthetic TCP server described in the migration notes. */
@RunWith(AndroidJUnit4::class)
class Android17PlatformInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before
    fun requireAndroid17() {
        assumeTrue(Build.VERSION.SDK_INT >= 37)
        assertEquals(37, context.applicationInfo.targetSdkVersion)
    }

    @Test
    fun pickerReadsSyntheticSelectionWithoutBroadPermission() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS))
        val rawContact = seedSyntheticContact()
        try {
            ActivityScenario.launch(ContactPickerVerificationActivity::class.java).use { scenario ->
                lateinit var activity: ContactPickerVerificationActivity
                scenario.onActivity { activity = it; it.pickPhone() }
                val row = device.wait(Until.findObject(By.text("CC SDK37 Synthetic Alice")), 15_000)
                assertNotNull("System picker must show synthetic contact", row)
                row.click()
                // The system requires an explicit Done action even for a single phone selection.
                if (activity.resultCode == null) {
                    val done = device.wait(Until.findObject(By.desc("Done")), 5_000)
                    assertNotNull("System picker confirmation must be present", done)
                    done.click()
                }
                waitForResult(activity)
                assertEquals(Activity.RESULT_OK, activity.resultCode)
                val selected = DeviceContactsReader.readSelected(context, requireNotNull(activity.selectedUri))
                assertNotNull(selected)
                assertEquals("+12025550123", selected?.e164)
                assertEquals("CC SDK37 Synthetic Alice", selected?.displayName)
                assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS))
                Log.i("CCAndroid17Verification", "Selected URI read passed without READ_CONTACTS")
            }
        } finally {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            try { context.contentResolver.delete(rawContact, null, null) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
    }

    @Test
    fun cancellingSystemPickerDoesNotReturnContact() {
        ActivityScenario.launch(ContactPickerVerificationActivity::class.java).use { scenario ->
            lateinit var activity: ContactPickerVerificationActivity
            scenario.onActivity { activity = it; it.pickPhone() }
            assertTrue(device.wait(Until.gone(By.pkg(context.packageName)), 10_000))
            device.pressBack()
            waitForResult(activity)
            assertEquals(Activity.RESULT_CANCELED, activity.resultCode)
            assertNull(activity.selectedUri)
        }
    }

    @Test
    fun localNetworkPermissionEnforcesRealTcpConnection() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(LocalNetworkPermissions.ACCESS_LOCAL_NETWORK))
        val denied = runCatching { connectToSyntheticServer() }.exceptionOrNull()
        assertNotNull("LAN connection must fail without local network access", denied)
        Log.i("CCAndroid17Verification", "Denied LAN connection: $denied")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, LocalNetworkPermissions.ACCESS_LOCAL_NETWORK)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.NEARBY_WIFI_DEVICES)
        assertTrue(LocalNetworkPermissions.hasWifiPermissions(context))
        assertEquals(42, connectToSyntheticServer())
        Log.i("CCAndroid17Verification", "LAN connection succeeded after permission grant")
    }

    @Test
    fun incomingCallNotificationCanBePostedWithFullScreenAccessDenied() {
        val manager = context.getSystemService(NotificationManager::class.java)
        assertFalse("Run on a clean emulator with full-screen intent access denied", manager.canUseFullScreenIntent())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val channel = "cc_android17_verification"
        manager.createNotificationChannel(NotificationChannel(channel, "SDK 37 synthetic call test", NotificationManager.IMPORTANCE_HIGH))
        val delegate = RfcommCallNotificationDelegate(context, RfcommForegroundService::class.java, channel, channel, 8800,
            "test.mute", "test.unmute", "test.speaker", "test.hangup", { true })
        val pending = delegate.createCallScreenPendingIntent("synthetic-session", "synthetic-call", PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, 0)
        val notification = delegate.buildIncomingCallNotification("Synthetic caller", Person.Builder().setName("Synthetic caller").build(),
            pending, pending, pending, true).build()
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertNotNull(notification.fullScreenIntent)
        try {
            manager.notify(8801, notification)
            assertTrue(manager.activeNotifications.any { it.id == 8801 })
            Log.i("CCAndroid17Verification", "CallStyle posted; full-screen access=${manager.canUseFullScreenIntent()}")
        } finally { manager.cancel(8801); pending.cancel() }
    }

    private fun connectToSyntheticServer(): Int = Socket().use {
        it.soTimeout = 5_000
        it.connect(InetSocketAddress("10.0.2.2", 18837), 5_000)
        it.getInputStream().read()
    }

    private fun seedSyntheticContact(): android.net.Uri {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
        try {
            val operations = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "CC SDK37 Synthetic Alice").build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, "+12025550123").build()
            )
            return requireNotNull(context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri)
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun waitForResult(activity: ContactPickerVerificationActivity) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (activity.resultCode == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertNotNull("System picker result must be delivered", activity.resultCode)
    }
}
