package com.auralis.crisisconnect.screens.QR

import com.auralis.crisisconnect.data.DeviceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QrPairingOperationTest {
    @Test fun screenScopeCanBeDestroyedWhileOperationCompletesAndResultIsReadOnce() = runTest {
        var leases = 0
        val operation = QrPairingOperation(StandardTestDispatcher(testScheduler), { leases++ }, { leases-- })
        lateinit var finish: (QrConnectResult) -> Unit
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler))
        val observation = screen.launch { operation.state.collect { } }
        assertTrue(operation.start { _, status, done -> status(QrConnectionStage.PAIRING); finish = done })
        observation.cancel(); screen.cancel()
        assertTrue(operation.state.value.running)
        assertEquals(1, leases)
        finish(QrConnectResult.Saved("peer"))
        assertEquals(0, leases)
        assertFalse(operation.state.value.running)
        val reattached = operation.state.value
        assertEquals(QrConnectResult.Saved("peer"), reattached.result)
        assertTrue(operation.consumeSuccess(reattached.id))
        assertFalse(operation.consumeSuccess(reattached.id))
    }

    @Test fun duplicateScanAndLateCallbacksCannotOverwriteAnotherAttempt() = runTest {
        val operation = QrPairingOperation(StandardTestDispatcher(testScheduler), {}, {})
        lateinit var oldFinish: (QrConnectResult) -> Unit
        lateinit var oldStatus: (QrConnectionStage) -> Unit
        var starts = 0
        operation.start { _, status, result -> starts++; oldFinish = result; oldStatus = status }
        assertFalse(operation.start { _, _, _ -> starts++ })
        assertEquals(1, starts)
        oldFinish(QrConnectResult.Failure(DeviceError.DEVICE_NOT_FOUND))
        operation.acknowledgeError()
        operation.start { _, status, _ -> status(QrConnectionStage.SEARCHING) }
        oldFinish(QrConnectResult.Saved("stale")); oldStatus(QrConnectionStage.SAVING)
        assertTrue(operation.state.value.running)
        assertNull(operation.state.value.result)
        assertEquals(QrConnectionStage.SEARCHING, operation.state.value.stage)
    }

    @Test fun workerExceptionReleasesForegroundLeaseAndPublishesFailure() = runTest {
        var leases = 0
        val operation = QrPairingOperation(StandardTestDispatcher(testScheduler), { leases++ }, { leases-- })
        operation.start { scope, _, _ -> scope.launch { error("transport failed") } }
        runCurrent()
        assertEquals(0, leases)
        assertEquals(QrConnectResult.Failure(DeviceError.PAIRING_FAILED), operation.state.value.result)
    }

    @Test fun terminalCompletionCancelsRemainingOperationChildren() = runTest {
        val operation = QrPairingOperation(StandardTestDispatcher(testScheduler), {}, {})
        lateinit var scope: CoroutineScope
        lateinit var finish: (QrConnectResult) -> Unit
        operation.start { running, _, done -> scope = running; finish = done }
        finish(QrConnectResult.AlreadyRegistered("peer"))
        var lateWrite = false
        scope.launch { lateWrite = true }
        runCurrent()
        assertFalse(lateWrite)
    }
    @Test fun terminalObserverCanStartNextOperationWithoutOldCleanupCancellingIt() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val operation = QrPairingOperation(dispatcher, {}, {})
        lateinit var firstFinish: (QrConnectResult) -> Unit
        lateinit var nextScope: CoroutineScope
        val collector = backgroundScope.launch(dispatcher) {
            operation.state.collect { state ->
                if (state.result is QrConnectResult.Saved && operation.consumeSuccess(state.id)) {
                    operation.start { scope, _, _ -> nextScope = scope }
                }
            }
        }
        operation.start { _, _, finish -> firstFinish = finish }
        firstFinish(QrConnectResult.Saved("first"))
        assertTrue(operation.state.value.running)
        assertTrue(nextScope.coroutineContext[Job]!!.isActive)
        operation.failLease(DeviceError.DEVICE_NOT_FOUND)
        collector.cancel()
    }

}
