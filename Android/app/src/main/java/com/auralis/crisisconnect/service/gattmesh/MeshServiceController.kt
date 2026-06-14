package com.auralis.crisisconnect.service.gattmesh

import android.content.Context

/**
 * Abstracts the static entry points of a concrete mesh foreground service so a single
 * [GattMeshServiceBinding] can drive either the public mesh or the authority mesh.
 *
 * Each profile has its own Android Service component (and therefore its own companion start/stop +
 * registry slot); this interface lets the binding target the right one without knowing which.
 */
interface MeshServiceController {
    val serviceClass: Class<out GattMeshForegroundService>
    fun start(context: Context)
    fun stop(context: Context)
    fun isRunning(): Boolean
    fun currentStateSnapshot(): GattMeshServiceState?
}

/** Controller for the public, open mesh ([GattMeshForegroundService]). */
object PublicMeshServiceController : MeshServiceController {
    override val serviceClass: Class<out GattMeshForegroundService> = GattMeshForegroundService::class.java
    override fun start(context: Context) = GattMeshForegroundService.start(context)
    override fun stop(context: Context) = GattMeshForegroundService.stop(context)
    override fun isRunning(): Boolean = GattMeshForegroundService.isRunning.value
    override fun currentStateSnapshot(): GattMeshServiceState? =
        GattMeshForegroundService.currentStateSnapshot()
}
