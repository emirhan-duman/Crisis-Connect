package com.auralis.crisisconnect.security

import android.app.Application
import android.content.Context
import android.os.Looper
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class RescueDeviceRegistryTest {
    private val context = mockk<Context>()
    private val registry = mockk<FirebaseFirestore>()
    private val collection = mockk<CollectionReference>()
    private val document = mockk<DocumentReference>()
    private val device = "cc-0123456789abcdef01234567"
    private val rotated = "cc-abcdef0123456789abcdef01"

    @Before fun setUp() {
        mockkStatic(FirebaseFirestore::class)
        mockkObject(LocalKeyStorage)
        every { context.applicationContext } returns context
        every { FirebaseFirestore.getInstance() } returns registry
        every { registry.collection("rescueDevices") } returns collection
        every { collection.document(any()) } returns document
        every { LocalKeyStorage.getOrCreateRescueDeviceId(context) } returns device
        every { LocalKeyStorage.markRescueDeviceOwner(context, any()) } just Runs
        every { LocalKeyStorage.rotateRescueDeviceId(context, "owner") } returns rotated
    }
    @After fun tearDown() = unmockkAll()

    @Test fun `ownership is registered in the default database used by certificate callables`() = runTest {
        every { document.set(any<Map<String, Any>>(), any<SetOptions>()) } returns Tasks.forResult(null)
        val registration = async { RescueDeviceRegistry.registerLocalDevice(context, "owner") }
        repeat(3) { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(device, registration.await())
        verify(exactly = 1) { FirebaseFirestore.getInstance() }
        verify(exactly = 0) { FirebaseFirestore.getInstance(any<String>()) }
        verify { document.set(match<Map<String, Any>> { it["uid"] == "owner" && it["deviceId"] == device }, any<SetOptions>()) }
        verify { LocalKeyStorage.markRescueDeviceOwner(context, "owner") }
    }

    @Test fun `account switch rotates a denied device id before returning it to the issuer`() = runTest {
        val denied = FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        val writes = listOf<Task<Void>>(Tasks.forException(denied), Tasks.forResult(null))
        every { document.set(any<Map<String, Any>>(), any<SetOptions>()) } returnsMany writes
        every { LocalKeyStorage.getRescueDeviceOwnerUid(context) } returns "previous-owner"
        val registration = async { RescueDeviceRegistry.registerLocalDevice(context, "owner") }
        repeat(5) { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(rotated, registration.await())
        verify { collection.document(rotated) }
        verify { LocalKeyStorage.markRescueDeviceOwner(context, "owner") }
    }

    @Test fun `permission denial for the same owner is surfaced without rotating identities`() = runTest {
        val denied = FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        every { document.set(any<Map<String, Any>>(), any<SetOptions>()) } returns Tasks.forException(denied)
        every { LocalKeyStorage.getRescueDeviceOwnerUid(context) } returns "owner"
        val registration = async { runCatching { RescueDeviceRegistry.registerLocalDevice(context, "owner") } }
        repeat(3) { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
        assertSame(denied, registration.await().exceptionOrNull())
        verify(exactly = 0) { LocalKeyStorage.rotateRescueDeviceId(any(), any()) }
        verify(exactly = 0) { LocalKeyStorage.markRescueDeviceOwner(any(), any()) }
    }

    @Test fun `network failure does not create a replacement device identity`() = runTest {
        val offline = FirebaseFirestoreException("Offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        every { document.set(any<Map<String, Any>>(), any<SetOptions>()) } returns Tasks.forException(offline)
        val registration = async { runCatching { RescueDeviceRegistry.registerLocalDevice(context, "owner") } }
        repeat(3) { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
        assertSame(offline, registration.await().exceptionOrNull())
        verify(exactly = 0) { LocalKeyStorage.rotateRescueDeviceId(any(), any()) }
    }
}
