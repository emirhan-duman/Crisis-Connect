package com.auralis.crisisconnect.service.gattmesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry that tracks each running mesh profile separately.
 *
 * [GattMeshForegroundService] is a single Android Service class, but the authority mesh runs a
 * second instance (a subclass) concurrently with the public mesh. Routing the per-profile "active
 * instance / running / runtime-active" state through this registry — keyed by [MeshProfile.id] —
 * keeps the two instances from clobbering each other's static bookkeeping.
 */
object MeshServiceRegistry {

    private val instances = ConcurrentHashMap<String, GattMeshForegroundService>()
    private val runningStates = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val runtimeActive = ConcurrentHashMap<String, Boolean>()

    private fun runningFlow(profileId: String): MutableStateFlow<Boolean> =
        runningStates.getOrPut(profileId) { MutableStateFlow(false) }

    fun runningState(profileId: String): StateFlow<Boolean> = runningFlow(profileId).asStateFlow()

    fun isRunning(profileId: String): Boolean = runningFlow(profileId).value

    fun instance(profileId: String): GattMeshForegroundService? = instances[profileId]

    fun register(profileId: String, instance: GattMeshForegroundService) {
        instances[profileId] = instance
        runningFlow(profileId).value = true
    }

    fun unregister(profileId: String, instance: GattMeshForegroundService) {
        instances.remove(profileId, instance)
        runningFlow(profileId).value = false
    }

    fun setRuntimeActive(profileId: String, active: Boolean) {
        runtimeActive[profileId] = active
    }

    fun isRuntimeActive(profileId: String): Boolean = runtimeActive[profileId] ?: false
}
