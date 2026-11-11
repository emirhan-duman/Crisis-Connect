package com.auralis.crisisconnect.feature

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus as Status
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RescueFeatureManagerTest {
    private val context = mockk<Context>(relaxed = true)
    private val split = mockk<SplitInstallManager>(relaxed = true)
    private val confirmation = mockk<ActivityResultLauncher<IntentSenderRequest>>(relaxed = true)
    private val listeners = mutableListOf<SplitInstallStateUpdatedListener>()
    private lateinit var manager: RescueFeatureManager
    private val states = mutableListOf<RescueFeatureManager.InstallState>()
    private var installed = 0
    private var errors = 0

    @Before fun setup() {
        every { split.installedModules } returns emptySet()
        every { split.sessionStates } returns Tasks.forResult(emptyList())
        every { split.startInstall(any()) } returns Tasks.forResult(41)
        every { split.getSessionState(41) } returns Tasks.forResult(session(Status.DOWNLOADING))
        every { split.registerListener(any()) } answers { listeners.add(firstArg()); Unit }
        every { split.unregisterListener(any()) } answers { listeners.remove(firstArg()); Unit }
        every { context.applicationContext } returns context
        every { context.getString(any()) } returns "Installation failed"
        manager = RescueFeatureManager(context, split)
    }

    private fun session(status: Int, id: Int = 41): SplitInstallSessionState {
        // Play's public create() intentionally excludes confirmation states.
        if (status == Status.REQUIRES_USER_CONFIRMATION) return mockk {
            every { sessionId() } returns id
            every { status() } returns status
            every { moduleNames() } returns listOf(RescueFeatureManager.MODULE_NAME)
            every { bytesDownloaded() } returns 25L
            every { totalBytesToDownload() } returns 100L
        }
        return SplitInstallSessionState.create(
            id, status, 0, 25, 100, listOf(RescueFeatureManager.MODULE_NAME), emptyList()
        )
    }
    private fun prepare(requestIfMissing: Boolean = true): () -> Unit = manager.installIfNeeded(
        confirmation, requestIfMissing, states::add, { installed++ }, { errors++ }
    )
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    private fun emit(status: Int, id: Int = 41) {
        listeners.toList().forEach { it.onStateUpdate(session(status, id)) }
    }

    @Test fun `preparation requests immediate delivery and never opens an activity`() {
        prepare()
        drain()
        verify(exactly = 1) { split.startInstall(match { it.moduleNames == listOf("feature_rescue") }) }
        verify(exactly = 0) { split.deferredInstall(any()) }
        assertEquals(RescueFeatureManager.InstallState.Installing(25), states.last())
        assertEquals(0, installed)
        emit(Status.INSTALLED)
        assertEquals(1, installed)
        assertEquals(0, errors)
        assertTrue(listeners.isEmpty())
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test fun `existing download is reattached without starting a second session`() {
        every { split.sessionStates } returns Tasks.forResult(listOf(session(Status.DOWNLOADING)))
        prepare()
        drain()
        verify(exactly = 0) { split.startInstall(any()) }
        emit(Status.INSTALLED, id = 99)
        assertEquals(0, installed)
        emit(Status.INSTALLED)
        assertEquals(1, installed)
    }

    @Test fun `already installed feature needs no network or confirmation`() {
        every { split.installedModules } returns setOf(RescueFeatureManager.MODULE_NAME)
        prepare()
        assertEquals(1, installed)
        verify(exactly = 0) { split.sessionStates }
        verify(exactly = 0) { split.startInstall(any()) }
    }

    @Test fun `leaving screen detaches callbacks while download continues`() {
        val detach = prepare()
        drain()
        detach()
        emit(Status.INSTALLED)
        assertEquals(0, installed)
        assertTrue(listeners.isEmpty())
        verify(exactly = 0) { split.cancelInstall(any()) }
    }

    @Test fun `leaving before session lookup completes does not start download`() {
        val detach = prepare()
        detach()
        drain()
        verify(exactly = 0) { split.startInstall(any()) }
        assertEquals(0, installed)
    }

    @Test fun `automatic and manual observers request confirmation only once`() {
        every { split.sessionStates } returns Tasks.forResult(listOf(session(Status.REQUIRES_USER_CONFIRMATION)))
        every { split.startConfirmationDialogForResult(any(), confirmation) } returns true
        prepare()
        prepare()
        drain()
        verify(exactly = 1) { split.startConfirmationDialogForResult(any(), confirmation) }
        emit(Status.CANCELED)
        assertEquals(2, errors)
        assertTrue(listeners.isEmpty())
    }

    @Test fun `concurrent callers share a request while Play assigns its session`() {
        val pending = TaskCompletionSource<Int>()
        every { split.startInstall(any()) } returns pending.task
        prepare()
        prepare()
        drain()
        verify(exactly = 1) { split.startInstall(any()) }
        pending.setResult(41)
        drain()
        emit(Status.INSTALLED)
        assertEquals(2, installed)
    }

    @Test fun `start request failure clears progress and listener`() {
        every { split.startInstall(any()) } returns Tasks.forException(IllegalStateException("offline"))
        prepare()
        drain()
        assertEquals(1, errors)
        assertEquals(RescueFeatureManager.InstallState.NotInstalling, states.last())
        assertTrue(listeners.isEmpty())
    }

    @Test fun `failed installation allows explicit retry`() {
        prepare()
        drain()
        emit(Status.FAILED)
        assertEquals(1, errors)
        assertEquals(RescueFeatureManager.InstallState.NotInstalling, states.last())
        prepare()
        drain()
        verify(exactly = 2) { split.startInstall(any()) }
        emit(Status.INSTALLED)
        assertEquals(1, installed)
    }

    @Test fun `resume after cancellation does not automatically request another download`() {
        prepare(requestIfMissing = false)
        drain()
        verify(exactly = 0) { split.startInstall(any()) }
        assertTrue(listeners.isEmpty())
        assertEquals(0, errors)
    }

    @Test fun `resume still observes active session when automatic retry is disabled`() {
        every { split.sessionStates } returns Tasks.forResult(listOf(session(Status.CANCELING)))
        prepare(requestIfMissing = false)
        drain()
        assertEquals(0, errors)
        assertEquals(1, listeners.size)
        emit(Status.CANCELED)
        assertEquals(1, errors)
        assertTrue(listeners.isEmpty())
    }
}
