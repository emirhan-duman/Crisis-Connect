package com.auralis.crisisconnect.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Tools.BreadcrumbTrailMode
import com.auralis.crisisconnect.screens.Tools.BreadcrumbTrailRepository

class BreadcrumbTrailService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        BreadcrumbTrailRepository.initialize(applicationContext)
        locationManager = getSystemService(LocationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BreadcrumbTrailRepository.pause()
            stopTracking()
            return START_NOT_STICKY
        }
        val mode = BreadcrumbTrailRepository.state.value.session?.mode
        if (mode != BreadcrumbTrailMode.RECORDING && mode != BreadcrumbTrailMode.RETURNING) {
            stopTracking()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        requestUpdates()
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        BreadcrumbTrailRepository.acceptLocation(location)
        val mode = BreadcrumbTrailRepository.state.value.session?.mode
        if (mode == BreadcrumbTrailMode.ARRIVED || mode == BreadcrumbTrailMode.PAUSED) {
            stopTracking()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            BreadcrumbTrailRepository.pause()
            stopTracking()
            return
        }
        runCatching { locationManager.removeUpdates(this) }
        val providers = buildList {
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            BreadcrumbTrailRepository.pause()
            stopTracking()
            return
        }
        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    if (provider == LocationManager.GPS_PROVIDER) 5_000L else 10_000L,
                    if (provider == LocationManager.GPS_PROVIDER) 8f else 15f,
                    this,
                    Looper.getMainLooper(),
                )
            }
        }
    }

    private fun stopTracking() {
        runCatching { locationManager.removeUpdates(this) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): android.app.Notification {
        val session = BreadcrumbTrailRepository.state.value.session
        val returning = session?.mode == BreadcrumbTrailMode.RETURNING
        val stopIntent = PendingIntent.getService(
            this,
            9122,
            Intent(this, BreadcrumbTrailService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            9123,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.breadcrumb_notification_title))
            .setContentText(
                getString(
                    if (returning) R.string.breadcrumb_notification_returning
                    else R.string.breadcrumb_notification_recording,
                    session?.points?.size ?: 0,
                )
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.breadcrumb_pause), stopIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.breadcrumb_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.breadcrumb_notification_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "breadcrumb_trail_tracking"
        private const val NOTIFICATION_ID = 9121
        private const val ACTION_START = "com.auralis.crisisconnect.breadcrumb.START"
        private const val ACTION_STOP = "com.auralis.crisisconnect.breadcrumb.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BreadcrumbTrailService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BreadcrumbTrailService::class.java))
        }
    }
}
