package com.auralis.crisisconnect.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.data.offline.OfflineRegionRepository
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.data.offline.OfflineServiceLocator
import com.auralis.crisisconnect.domain.offline.DeleteRegionUseCase
import com.auralis.crisisconnect.domain.offline.PauseDownloadUseCase
import com.auralis.crisisconnect.domain.offline.ResumeDownloadUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps offline downloads running reliably in the background.
 */
class OfflineDownloadService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private lateinit var repository: OfflineRegionRepository
    private lateinit var pauseUseCase: PauseDownloadUseCase
    private lateinit var resumeUseCase: ResumeDownloadUseCase
    private lateinit var deleteUseCase: DeleteRegionUseCase
    private lateinit var notification: OfflineDownloadNotification

    private var collectorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        repository = OfflineServiceLocator.provideRepository(applicationContext)
        pauseUseCase = OfflineServiceLocator.providePauseUseCase(applicationContext)
        resumeUseCase = OfflineServiceLocator.provideResumeUseCase(applicationContext)
        deleteUseCase = OfflineServiceLocator.provideDeleteUseCase(applicationContext)
        notification = OfflineDownloadNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause(intent.getLongExtra(EXTRA_REGION_ID, INVALID_REGION_ID))
            ACTION_RESUME -> handleResume(intent.getLongExtra(EXTRA_REGION_ID, INVALID_REGION_ID))
            ACTION_CANCEL -> handleCancel(intent.getLongExtra(EXTRA_REGION_ID, INVALID_REGION_ID))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectorJob?.cancel()
        serviceScope.cancel()
        notification.cancel()
        super.onDestroy()
    }

    private fun handleStart() {
        if (collectorJob != null) return
        startForeground(
            OfflineDownloadNotification.NOTIFICATION_ID,
            NotificationCompat.Builder(this, OfflineDownloadNotification.CHANNEL_ID)
                .setSmallIcon(com.auralis.crisisconnect.R.drawable.ic_stat_download)
                .setContentTitle(getString(com.auralis.crisisconnect.R.string.offline_notification_preparing))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        collectorJob = serviceScope.launch {
            repository.regionsFlow.collectLatest { regions ->
                val active = regions.firstOrNull { it.status == OfflineRegionStatus.Downloading && !it.isPaused }
                    ?: regions.firstOrNull { it.status == OfflineRegionStatus.Downloading }
                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val notificationInstance = notification.build(
                        active,
                        pauseIntentFactory(),
                        resumeIntentFactory(),
                        cancelIntentFactory()
                    )
                    startForeground(OfflineDownloadNotification.NOTIFICATION_ID, notificationInstance)
                }
            }
        }
    }

    private fun handlePause(regionId: Long) {
        if (regionId == INVALID_REGION_ID) return
        serviceScope.launch { pauseUseCase(regionId) }
    }

    private fun handleResume(regionId: Long) {
        if (regionId == INVALID_REGION_ID) return
        serviceScope.launch { resumeUseCase(regionId) }
    }

    private fun handleCancel(regionId: Long) {
        if (regionId == INVALID_REGION_ID) return
        serviceScope.launch { deleteUseCase(regionId) }
    }

    private fun pauseIntentFactory(): OfflineDownloadNotification.PendingIntentFactory =
        OfflineDownloadNotification.PendingIntentFactory { regionId ->
            PendingIntent.getService(
                this,
                (regionId * 3L).toInt(),
                newIntent(this, ACTION_PAUSE, regionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    private fun resumeIntentFactory(): OfflineDownloadNotification.PendingIntentFactory =
        OfflineDownloadNotification.PendingIntentFactory { regionId ->
            PendingIntent.getService(
                this,
                (regionId * 5L).toInt(),
                newIntent(this, ACTION_RESUME, regionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    private fun cancelIntentFactory(): OfflineDownloadNotification.PendingIntentFactory =
        OfflineDownloadNotification.PendingIntentFactory { regionId ->
            PendingIntent.getService(
                this,
                (regionId * 7L).toInt(),
                newIntent(this, ACTION_CANCEL, regionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    companion object {
        private const val ACTION_START = "com.auralis.crisisconnect.service.action.START"
        private const val ACTION_PAUSE = "com.auralis.crisisconnect.service.action.PAUSE"
        private const val ACTION_RESUME = "com.auralis.crisisconnect.service.action.RESUME"
        private const val ACTION_CANCEL = "com.auralis.crisisconnect.service.action.CANCEL"
        private const val EXTRA_REGION_ID = "extra_region_id"
        private const val INVALID_REGION_ID = -1L

        /** Starts or updates the foreground service. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, newIntent(context, ACTION_START, INVALID_REGION_ID))
        }

        private fun newIntent(context: Context, action: String, regionId: Long): Intent {
            return Intent(context, OfflineDownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_REGION_ID, regionId)
            }
        }
    }
}
