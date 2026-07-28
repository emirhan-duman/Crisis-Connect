package com.auralis.crisisconnect.feature

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

class RescueFeatureManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val splitInstallManager = SplitInstallManagerFactory.create(appContext)

    fun isInstalled(): Boolean = MODULE_NAME in splitInstallManager.installedModules

    fun prefetchIfNeeded() {
        if (isInstalled()) {
            return
        }
        splitInstallManager.deferredInstall(listOf(MODULE_NAME))
    }

    fun launchInstalled(
        context: Context,
        startDestination: String = START_DESTINATION_HOME
    ): Boolean {
        if (!isInstalled()) {
            Log.w(
                LOG_TAG,
                "Rescue feature launch requested before install. installedModules=${splitInstallManager.installedModules}"
            )
            return false
        }
        val launchIntent = buildActivityIntent(
            context = context,
            className = RESCUE_ACTIVITY_CLASS_NAME,
            startDestination = startDestination
        ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val resolvedActivity = context.packageManager.resolveActivity(launchIntent, 0)
        if (resolvedActivity == null) {
            Log.e(
                LOG_TAG,
                "Rescue feature installed but activity could not be resolved. " +
                    "installedModules=${splitInstallManager.installedModules} " +
                    "target=$RESCUE_ACTIVITY_CLASS_NAME"
            )
            return false
        }

        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrElse { throwable ->
            Log.e(
                LOG_TAG,
                "Failed to launch rescue feature activity $RESCUE_ACTIVITY_CLASS_NAME",
                throwable
            )
            false
        }
    }

    fun installAndLaunch(
        activity: Activity,
        confirmationLauncher: ActivityResultLauncher<IntentSenderRequest>,
        startDestination: String = START_DESTINATION_HOME,
        onStateChanged: (InstallState) -> Unit,
        onError: (String) -> Unit
    ) {
        if (launchInstalled(activity, startDestination = startDestination)) {
            onStateChanged(InstallState.NotInstalling)
            return
        }

        val request = SplitInstallRequest.newBuilder()
            .addModule(MODULE_NAME)
            .build()

        lateinit var listener: SplitInstallStateUpdatedListener
        var confirmationRequested = false
        fun cleanup() {
            splitInstallManager.unregisterListener(listener)
        }

        listener = SplitInstallStateUpdatedListener { state ->
            if (!state.moduleNames().contains(MODULE_NAME)) {
                return@SplitInstallStateUpdatedListener
            }

            val progressPercent = state.totalBytesToDownload()
                .takeIf { it > 0L }
                ?.let { total ->
                    ((state.bytesDownloaded() * 100L) / total).toInt().coerceIn(0, 100)
                }

            when (state.status()) {
                SplitInstallSessionStatus.PENDING,
                SplitInstallSessionStatus.DOWNLOADING,
                SplitInstallSessionStatus.DOWNLOADED,
                SplitInstallSessionStatus.INSTALLING -> {
                    onStateChanged(InstallState.Installing(progressPercent))
                }

                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> {
                    onStateChanged(InstallState.Installing(progressPercent))
                    if (confirmationRequested) {
                        return@SplitInstallStateUpdatedListener
                    }
                    confirmationRequested = true
                    if (!startConfirmationDialog(state, confirmationLauncher)) {
                        cleanup()
                        onStateChanged(InstallState.NotInstalling)
                        onError(appContext.getString(R.string.rescue_module_install_failed))
                    }
                }

                SplitInstallSessionStatus.INSTALLED -> {
                    cleanup()
                    onStateChanged(InstallState.NotInstalling)
                    if (!launchInstalled(activity, startDestination = startDestination)) {
                        onError(appContext.getString(R.string.rescue_module_unavailable))
                    }
                }

                SplitInstallSessionStatus.CANCELED,
                SplitInstallSessionStatus.CANCELING,
                SplitInstallSessionStatus.FAILED -> {
                    cleanup()
                    onStateChanged(InstallState.NotInstalling)
                    onError(appContext.getString(R.string.rescue_module_install_failed))
                }

                else -> Unit
            }
        }

        splitInstallManager.registerListener(listener)
        onStateChanged(InstallState.Installing(progressPercent = 0))
        splitInstallManager.startInstall(request)
            .addOnFailureListener {
                cleanup()
                onStateChanged(InstallState.NotInstalling)
                val message = when (it) {
                    is ActivityNotFoundException -> {
                        appContext.getString(R.string.rescue_module_unavailable)
                    }

                    else -> appContext.getString(R.string.rescue_module_install_failed)
                }
                onError(message)
            }
    }

    fun startRescueClientService(): Boolean {
        if (!isInstalled()) {
            Log.w(LOG_TAG, "Rescue client service start skipped because feature is not installed")
            return false
        }
        return startForegroundService(RESCUE_CLIENT_SERVICE_CLASS_NAME)
    }

    fun setMeshEnabled(enabled: Boolean): Boolean {
        if (!isInstalled()) {
            Log.w(LOG_TAG, "Mesh service command skipped because feature is not installed")
            return false
        }
        val intent = buildServiceIntent(MESH_AWARE_SERVICE_CLASS_NAME).apply {
            action = ACTION_SET_MESH_ENABLED
            putExtra(EXTRA_MESH_ENABLED, enabled)
        }
        return runCatching {
            ContextCompat.startForegroundService(appContext, intent)
            true
        }.getOrElse { throwable ->
            Log.e(LOG_TAG, "Unable to send mesh toggle command enabled=$enabled", throwable)
            false
        }
    }

    fun stopMeshService(): Boolean = stopService(MESH_AWARE_SERVICE_CLASS_NAME)

    /** Start the authority (yetkili) GATT mesh foreground service so it connects in the background. */
    fun startAuthorityMeshService(): Boolean {
        // Only start when the split is genuinely installed: the platform must have the feature_rescue
        // dex on the service process's classpath, which it does for a Play-installed module (reported
        // by SplitInstallManager) but NOT for a sideloaded (adb) debug split — starting it there would
        // crash the service process with ClassNotFoundException. Queue a deferred install otherwise.
        if (!isInstalled()) {
            Log.w(LOG_TAG, "Authority mesh background start skipped; feature module not installed")
            prefetchIfNeeded()
            return false
        }
        return startForegroundService(AUTHORITY_MESH_SERVICE_CLASS_NAME)
    }

    fun stopAuthorityMeshService(): Boolean = stopService(AUTHORITY_MESH_SERVICE_CLASS_NAME)

    fun startCrisisLinkService(): Boolean {
        if (!isInstalled()) {
            Log.w(LOG_TAG, "Crisis Link start skipped because feature is not installed")
            return false
        }
        return startForegroundService(CRISIS_LINK_SERVICE_CLASS_NAME)
    }

    fun stopCrisisLinkService(): Boolean = stopService(CRISIS_LINK_SERVICE_CLASS_NAME)

    fun notifySignalLocationUpdated(): Boolean {
        if (!isInstalled()) {
            Log.w(LOG_TAG, "Crisis Link notify skipped because feature is not installed")
            return false
        }
        val intent = buildServiceIntent(CRISIS_LINK_SERVICE_CLASS_NAME).apply {
            action = ACTION_SIGNAL_LOCATION_UPDATED
            putExtra(EXTRA_IMMEDIATE_SYNC_REASON, IMMEDIATE_SYNC_REASON_SIGNAL_LOCATION_CHANGED)
        }
        return runCatching {
            ContextCompat.startForegroundService(appContext, intent)
            true
        }.getOrElse { throwable ->
            Log.e(LOG_TAG, "Unable to notify Crisis Link about signal location update", throwable)
            false
        }
    }

    private fun startForegroundService(className: String): Boolean {
        val intent = buildServiceIntent(className)
        return runCatching {
            ContextCompat.startForegroundService(appContext, intent)
            true
        }.getOrElse { throwable ->
            Log.e(LOG_TAG, "Unable to start service $className", throwable)
            false
        }
    }

    private fun stopService(className: String): Boolean {
        val intent = buildServiceIntent(className)
        return runCatching {
            appContext.stopService(intent)
        }.getOrElse { throwable ->
            Log.e(LOG_TAG, "Unable to stop service $className", throwable)
            false
        }
    }

    private fun buildActivityIntent(
        context: Context,
        className: String,
        startDestination: String
    ): Intent {
        return Intent().setClassName(
            context.packageName,
            className
        ).apply {
            if (startDestination != START_DESTINATION_HOME) {
                putExtra(EXTRA_START_DESTINATION, startDestination)
            }
        }
    }

    fun registerListener(listener: SplitInstallStateUpdatedListener) {
        splitInstallManager.registerListener(listener)
    }

    fun unregisterListener(listener: SplitInstallStateUpdatedListener) {
        splitInstallManager.unregisterListener(listener)
    }

    private fun buildServiceIntent(className: String): Intent {
        return Intent().setClassName(appContext.packageName, className)
    }

    private fun startConfirmationDialog(
        state: SplitInstallSessionState,
        confirmationLauncher: ActivityResultLauncher<IntentSenderRequest>
    ): Boolean {
        return runCatching {
            splitInstallManager.startConfirmationDialogForResult(state, confirmationLauncher)
        }.onFailure { throwable ->
            Log.e(LOG_TAG, "Unable to show split install confirmation dialog", throwable)
        }.getOrDefault(false)
    }

    sealed interface InstallState {
        data class Installing(val progressPercent: Int?) : InstallState
        data object NotInstalling : InstallState
    }

    companion object {
        const val MODULE_NAME = "feature_rescue"
        const val RESCUE_ACTIVITY_CLASS_NAME =
            "com.auralis.crisisconnect.feature.rescue.RescueActivity"
        const val RESCUE_CLIENT_SERVICE_CLASS_NAME =
            "com.auralis.crisisconnect.service.GattRescueClientService"
        const val MESH_AWARE_SERVICE_CLASS_NAME =
            "com.auralis.crisisconnect.service.mesh.MeshAwareService"
        const val AUTHORITY_MESH_SERVICE_CLASS_NAME =
            "com.auralis.crisisconnect.service.mesh.AuthorityMeshForegroundService"
        const val CRISIS_LINK_SERVICE_CLASS_NAME =
            "com.auralis.crisisconnect.service.CrisisLinkForegroundService"
        const val EXTRA_START_DESTINATION = "extra_rescue_start_destination"
        const val START_DESTINATION_HOME = "rescue_home"
        const val START_DESTINATION_SETTINGS = "rescue_settings"
        const val START_DESTINATION_MESH_CHAT = "mesh_chat"
        const val ACTION_SET_MESH_ENABLED = "com.auralis.crisisconnect.action.SET_MESH_ENABLED"
        const val EXTRA_MESH_ENABLED = "extra_mesh_enabled"
        const val ACTION_SIGNAL_LOCATION_UPDATED =
            "com.auralis.crisisconnect.action.SIGNAL_LOCATION_UPDATED"
        const val EXTRA_IMMEDIATE_SYNC_REASON = "extra_immediate_sync_reason"
        const val IMMEDIATE_SYNC_REASON_SIGNAL_LOCATION_CHANGED = "signal_location_changed"
        private const val LOG_TAG = "RescueFeatureManager"
    }
}
