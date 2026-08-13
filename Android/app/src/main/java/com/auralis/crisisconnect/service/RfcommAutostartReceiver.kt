package com.auralis.crisisconnect.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import java.util.concurrent.Executors

class RfcommAutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RfcommForegroundService::class.java)
                )
            }.onFailure { throwable ->
                if (isForegroundStartBlocked(throwable)) {
                    return
                }
                throw throwable
            }
        } else if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    runNotificationWork(context) { cancelBluetoothDisabledNotification(it) }
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, RfcommForegroundService::class.java)
                        )
                    }.onFailure { throwable ->
                        if (isForegroundStartBlocked(throwable)) {
                            return@onFailure
                        }
                        throw throwable
                    }
                }

                BluetoothAdapter.STATE_OFF -> {
                    runNotificationWork(context) { showBluetoothDisabledNotification(it) }
                }
            }
        }
    }

    /**
     * NotificationManager calls cross a system-server Binder boundary and can stall on
     * Android 16. Keep that work off BroadcastReceiver's main thread while retaining the
     * process long enough for this short, best-effort notification operation to finish.
     */
    private fun runNotificationWork(context: Context, work: (Context) -> Unit) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        runCatching {
            notificationExecutor.execute {
                try {
                    work(appContext)
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Bluetooth notification work failed", throwable)
                } finally {
                    pendingResult.finish()
                }
            }
        }.onFailure { throwable ->
            pendingResult.finish()
            Log.e(TAG, "Could not schedule Bluetooth notification work", throwable)
        }
    }

    private fun isForegroundStartBlocked(throwable: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        return throwable.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun showBluetoothDisabledNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureBluetoothAlertNotificationChannel(context)
        val notification = NotificationCompat.Builder(context, BLUETOOTH_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.bluetooth_disabled_notification_title))
            .setContentText(context.getString(R.string.bluetooth_disabled_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.bluetooth_disabled_notification_text)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(bluetoothSettingsIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                BLUETOOTH_DISABLED_NOTIFICATION_ID,
                notification
            )
        }.onFailure { throwable ->
            if (throwable is SecurityException) {
                return
            }
            throw throwable
        }
    }

    private fun cancelBluetoothDisabledNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(BLUETOOTH_DISABLED_NOTIFICATION_ID)
        }
    }

    private fun ensureBluetoothAlertNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            BLUETOOTH_ALERT_CHANNEL_ID,
            context.getString(R.string.bluetooth_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.bluetooth_alert_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun bluetoothSettingsIntent(context: Context): PendingIntent {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    companion object {
        private const val TAG = "RfcommAutostart"
        private const val BLUETOOTH_ALERT_CHANNEL_ID = "bluetooth_alert_channel"
        private const val BLUETOOTH_DISABLED_NOTIFICATION_ID = 1002
        private val notificationExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "rfcomm-notification").apply { isDaemon = true }
        }
    }
}
