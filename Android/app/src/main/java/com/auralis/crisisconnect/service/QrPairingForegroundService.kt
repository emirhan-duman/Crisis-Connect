package com.auralis.crisisconnect.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.QR.QrPairingCoordinator

/** A short, user-initiated lease for the in-flight handshake. No QR secrets are sent in Intents. */
class QrPairingForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.qr_pairing_notification_title), NotificationManager.IMPORTANCE_LOW))
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.qr_pairing_notification_title))
            .setContentText(getString(R.string.qr_pairing_notification_message))
            .setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true).build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: SecurityException) {
            QrPairingCoordinator.get(this).failLease(com.auralis.crisisconnect.data.DeviceError.MISSING_PERMISSIONS)
            stopSelf()
        } catch (_: IllegalStateException) {
            QrPairingCoordinator.get(this).failLease(com.auralis.crisisconnect.data.DeviceError.PAIRING_FAILED)
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A quick terminal result may precede the OS delivering the service start.
        if (!QrPairingCoordinator.get(this).state.value.running) stopSelf(startId)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "qr_contact_pairing"
        private const val NOTIFICATION = 7316
    }
}
