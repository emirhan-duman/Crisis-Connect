package com.auralis.crisisconnect.service.mesh

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import com.auralis.crisisconnect.service.gattmesh.GattMeshServiceState
import com.auralis.crisisconnect.service.gattmesh.MeshProfile
import com.auralis.crisisconnect.service.gattmesh.MeshProfiles
import com.auralis.crisisconnect.service.gattmesh.MeshServiceController
import com.auralis.crisisconnect.service.gattmesh.MeshServiceRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * Authority-only mesh.
 *
 * A second [GattMeshForegroundService] instance that speaks the AUTHORITY [MeshProfile]: a separate
 * service UUID and a provisioned shared group key, so only admin/fieldteam devices can join, read,
 * or relay readable traffic. It reuses the entire public-mesh transport/crypto/relay stack and runs
 * concurrently with the public mesh — they share the process-wide scan coordinator and GATT server
 * (keyed by service UUID) while keeping independent peer state, advertisers and notifications.
 *
 * On unprovisioned (civilian) devices the runtime self-stops because the profile cannot derive a
 * group key, so the authority advertiser/scanner never turns on there.
 */
class AuthorityMeshForegroundService : GattMeshForegroundService() {

    override val profile: MeshProfile get() = MeshProfiles.AUTHORITY

    companion object {
        val isRunning: StateFlow<Boolean> = MeshServiceRegistry.runningState(MeshProfiles.AUTHORITY.id)

        fun isRuntimeActive(): Boolean = MeshServiceRegistry.isRuntimeActive(MeshProfiles.AUTHORITY.id)

        fun currentStateSnapshot(): GattMeshServiceState? =
            MeshServiceRegistry.instance(MeshProfiles.AUTHORITY.id)?.state?.value

        fun start(context: Context) {
            val appContext = context.applicationContext
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, AuthorityMeshForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, AuthorityMeshForegroundService::class.java))
        }
    }
}

/** [MeshServiceController] that drives the authority mesh service. */
object AuthorityMeshServiceController : MeshServiceController {
    override val serviceClass: Class<out GattMeshForegroundService> =
        AuthorityMeshForegroundService::class.java

    override fun start(context: Context) = AuthorityMeshForegroundService.start(context)
    override fun stop(context: Context) = AuthorityMeshForegroundService.stop(context)
    override fun isRunning(): Boolean = AuthorityMeshForegroundService.isRunning.value
    override fun currentStateSnapshot(): GattMeshServiceState? =
        AuthorityMeshForegroundService.currentStateSnapshot()
}
