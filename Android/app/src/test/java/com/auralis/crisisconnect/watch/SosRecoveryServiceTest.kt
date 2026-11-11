package com.auralis.crisisconnect.watch

import android.app.Application
import android.content.Intent
import android.util.AtomicFile
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.service.GattSOSServerService
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import com.auralis.crisisconnect.service.sos.SosCloudReporter
import com.auralis.crisisconnect.service.sos.SosContactNotifier
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production declaration logic, with external effects replaced by strict spies. No test hooks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SosRecoveryServiceTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var store: WatchRecoveryStore
    private lateinit var service: GattSOSServerService
    @Before fun setup() {
        store = WatchRecoveryStore(AtomicFile(File(folder.root, "state.json")))
        mockkObject(WatchRecoveryStore.Companion, SosCloudReporter, SosContactNotifier, Analytics, GattMeshForegroundService.Companion)
        every { WatchRecoveryStore.get(any()) } returns store
        every { SosCloudReporter.onSosStarted(any(), any(), any(), any()) } just Runs
        every { SosContactNotifier.onSosStarted(any(), any(), any()) } just Runs
        every { SosCloudReporter.onSosStopped(any()) } just Runs
        every { SosContactNotifier.onSosStopped(any()) } just Runs
        every { Analytics.sosActivated() } just Runs
        every { GattMeshForegroundService.requestSosModeReconcile(any()) } just Runs
        resetLiveState()
        // Deliberately skip onCreate: BLE hardware startup is outside this JVM test.
        service = Robolectric.buildService(GattSOSServerService::class.java).get()
        (GattSOSServerService.isRunning as MutableStateFlow).value = true
        assertFalse(WatchTestHooks.enabled)
    }
    @After fun cleanup() { resetLiveState(); unmockkAll() }
    private fun resetLiveState() {
        (GattSOSServerService.isDeclared as MutableStateFlow).value = false
        (GattSOSServerService.isRunning as MutableStateFlow).value = false
        (GattSOSServerService.startTimestampMillis as MutableStateFlow).value = null
    }
    private fun intent(resume: String? = null) = Intent().putExtra(GattSOSServerService.EXTRA_USER_DECLARED, true)
        .putExtra(GattSOSServerService.EXTRA_RESUME_SESSION_ID, resume)

    @Test fun `null restart hosting and stale declaration never announce a new SOS`() {
        store.begin(1234)
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(Intent(), 0, 2)
        service.onStartCommand(intent(), 0, 3)
        assertFalse(GattSOSServerService.isDeclared.value)
        assertEquals(1234L, store.state.value.startedAt)
        verify(exactly = 0) { SosCloudReporter.onSosStarted(any(), any(), any(), any()) }
        verify(exactly = 0) { SosContactNotifier.onSosStarted(any(), any(), any()) }
        verify(exactly = 0) { Analytics.sosActivated() }
    }
    @Test fun `explicit resume keeps identity and uses resume effects exactly once`() {
        store.begin(1234)
        service.onStartCommand(intent("1234"), 0, 1)
        service.onStartCommand(intent("1234"), 0, 2)
        assertTrue(GattSOSServerService.isDeclared.value)
        assertEquals(1234L, GattSOSServerService.startTimestampMillis.value)
        verify(exactly = 1) { SosCloudReporter.onSosStarted(any(), any(), any(), true) }
        verify(exactly = 1) { SosContactNotifier.onSosStarted(any(), any(), true) }
        verify(exactly = 0) { Analytics.sosActivated() }
    }
    @Test fun `ending a saved session prevents delayed resume even without a running service`() {
        store.begin(1234)
        (GattSOSServerService.isRunning as MutableStateFlow).value = false
        GattSOSServerService.stopBroadcast(service)
        assertNull(store.state.value.startedAt)
        (GattSOSServerService.isRunning as MutableStateFlow).value = true
        service.onStartCommand(intent("1234"), 0, 1)
        assertFalse(GattSOSServerService.isDeclared.value)
        verify(exactly = 0) { SosCloudReporter.onSosStarted(any(), any(), any(), any()) }
        verify(exactly = 1) { SosCloudReporter.onSosStopped(any()) }
    }
    @Test fun `fresh explicit SOS is persisted before effects and repeated start is idempotent`() {
        every { SosCloudReporter.onSosStarted(any(), any(), any(), false) } answers {
            assertNotNull(store.state.value.startedAt)
        }
        service.onStartCommand(intent(), 0, 1)
        val id = store.state.value.startedAt
        service.onStartCommand(intent(), 0, 2)
        assertEquals(id, GattSOSServerService.startTimestampMillis.value)
        assertTrue(GattSOSServerService.isDeclared.value)
        verify(exactly = 1) { SosContactNotifier.onSosStarted(any(), any(), false) }
        verify(exactly = 1) { Analytics.sosActivated() }
    }
    @Test fun `failed hosting cannot mark a new SOS active`() {
        (GattSOSServerService.isRunning as MutableStateFlow).value = false
        service.onStartCommand(intent(), 0, 1)
        assertNull(store.state.value.startedAt)
        assertFalse(GattSOSServerService.isDeclared.value)
        verify(exactly = 0) { SosContactNotifier.onSosStarted(any(), any(), any()) }
    }
}
