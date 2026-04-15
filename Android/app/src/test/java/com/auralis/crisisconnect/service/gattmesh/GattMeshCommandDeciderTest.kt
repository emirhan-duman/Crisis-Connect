package com.auralis.crisisconnect.service.gattmesh

import org.junit.Assert.assertEquals
import org.junit.Test

class GattMeshCommandDeciderTest {

    @Test
    fun `stop command always stops service`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.STOP,
            settingsEnabled = true,
            runtimeActive = true
        )

        assertEquals(GattMeshRuntimeDecision.STOP_SERVICE, decision)
    }

    @Test
    fun `start command stops service when disabled in settings`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.START,
            settingsEnabled = false,
            runtimeActive = false
        )

        assertEquals(GattMeshRuntimeDecision.STOP_SERVICE, decision)
    }

    @Test
    fun `start command starts runtime when enabled and inactive`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.START,
            settingsEnabled = true,
            runtimeActive = false
        )

        assertEquals(GattMeshRuntimeDecision.START_RUNTIME, decision)
    }

    @Test
    fun `start command no-op when runtime already active`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.START,
            settingsEnabled = true,
            runtimeActive = true
        )

        assertEquals(GattMeshRuntimeDecision.NO_OP, decision)
    }

    @Test
    fun `reconcile command stops service when disabled in settings`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.RECONCILE,
            settingsEnabled = false,
            runtimeActive = true
        )

        assertEquals(GattMeshRuntimeDecision.STOP_SERVICE, decision)
    }

    @Test
    fun `reconcile command reconciles runtime when enabled`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.RECONCILE,
            settingsEnabled = true,
            runtimeActive = true
        )

        assertEquals(GattMeshRuntimeDecision.RECONCILE_RUNTIME, decision)
    }

    @Test
    fun `start command stops service when disabled even if runtime already active`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.START,
            settingsEnabled = false,
            runtimeActive = true
        )

        assertEquals(GattMeshRuntimeDecision.STOP_SERVICE, decision)
    }

    @Test
    fun `reconcile command reconciles runtime even when runtime currently inactive`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.RECONCILE,
            settingsEnabled = true,
            runtimeActive = false
        )

        assertEquals(GattMeshRuntimeDecision.RECONCILE_RUNTIME, decision)
    }

    @Test
    fun `reconcile command stops service when disabled and inactive`() {
        val decision = GattMeshCommandDecider.decide(
            command = GattMeshRuntimeCommand.RECONCILE,
            settingsEnabled = false,
            runtimeActive = false
        )

        assertEquals(GattMeshRuntimeDecision.STOP_SERVICE, decision)
    }

    @Test
    fun `stop command ignores settings and runtime state`() {
        listOf(false, true).forEach { settingsEnabled ->
            listOf(false, true).forEach { runtimeActive ->
                val decision = GattMeshCommandDecider.decide(
                    command = GattMeshRuntimeCommand.STOP,
                    settingsEnabled = settingsEnabled,
                    runtimeActive = runtimeActive
                )
                assertEquals(
                    "Expected STOP_SERVICE for settings=$settingsEnabled runtime=$runtimeActive",
                    GattMeshRuntimeDecision.STOP_SERVICE,
                    decision
                )
            }
        }
    }

    @Test
    fun `start command decision table is stable`() {
        val cases = listOf(
            Triple(false, false, GattMeshRuntimeDecision.STOP_SERVICE),
            Triple(false, true, GattMeshRuntimeDecision.STOP_SERVICE),
            Triple(true, false, GattMeshRuntimeDecision.START_RUNTIME),
            Triple(true, true, GattMeshRuntimeDecision.NO_OP)
        )

        cases.forEach { (settingsEnabled, runtimeActive, expected) ->
            val decision = GattMeshCommandDecider.decide(
                command = GattMeshRuntimeCommand.START,
                settingsEnabled = settingsEnabled,
                runtimeActive = runtimeActive
            )
            assertEquals(expected, decision)
        }
    }

    @Test
    fun `reconcile command decision table is stable`() {
        val cases = listOf(
            Triple(false, false, GattMeshRuntimeDecision.STOP_SERVICE),
            Triple(false, true, GattMeshRuntimeDecision.STOP_SERVICE),
            Triple(true, false, GattMeshRuntimeDecision.RECONCILE_RUNTIME),
            Triple(true, true, GattMeshRuntimeDecision.RECONCILE_RUNTIME)
        )

        cases.forEach { (settingsEnabled, runtimeActive, expected) ->
            val decision = GattMeshCommandDecider.decide(
                command = GattMeshRuntimeCommand.RECONCILE,
                settingsEnabled = settingsEnabled,
                runtimeActive = runtimeActive
            )
            assertEquals(expected, decision)
        }
    }
}
