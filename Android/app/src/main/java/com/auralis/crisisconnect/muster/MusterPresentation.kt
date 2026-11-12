package com.auralis.crisisconnect.muster

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import java.security.MessageDigest

/** Muster presentation is an account-specific choice, separate from incoming call permissions. */
internal object MusterPresentation {
    private val tracking = AtomicBoolean(false)
    @Volatile private var resumed: WeakReference<Activity>? = null

    fun trackForeground(application: Application) {
        if (!tracking.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (resumed?.get() === activity) resumed = null }
            override fun onActivityDestroyed(activity: Activity) { if (resumed?.get() === activity) resumed = null }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
    }

    /** Normal in-app navigation only; background/other apps are left to Android's FSI policy. */
    fun presentWithinApp(context: Context, uid: String, panel: String, session: String, kind: String, alertProfile: String = "standard", expiresAtMillis: Long = 0) {
        val activity = resumed?.get() ?: return
        if (activity is MusterActivity || activity.isFinishing || activity.isDestroyed) return
        if (!shouldPresentWithinApp(enabled(context, uid), allowed(context), notificationsEnabled(context), highImportance(context), activity.hasWindowFocus())) return
        activity.runOnUiThread {
            if (resumed?.get() === activity && activity.hasWindowFocus() && !activity.isFinishing && !activity.isDestroyed) {
                runCatching { activity.startActivity(Intent(activity, MusterActivity::class.java)
                    .putExtra("panelId", panel).putExtra("sessionId", session).putExtra("kind", kind).putExtra("uid", uid).putExtra("alertProfile", alertProfile).putExtra("expiresAtMillis", expiresAtMillis)) }
            }
        }
    }

    internal fun shouldPresentWithinApp(optedIn: Boolean, permission: Boolean, notifications: Boolean, highPriority: Boolean, ownResumedWindow: Boolean) =
        optedIn && permission && notifications && highPriority && ownResumedWindow

    private fun key(uid: String) = MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun enabled(context: Context, uid: String): Boolean = context
        .getSharedPreferences("muster_presentation_v1", Context.MODE_PRIVATE).getBoolean(key(uid), true)

    fun setEnabled(context: Context, uid: String, enabled: Boolean) {
        context.getSharedPreferences("muster_presentation_v1", Context.MODE_PRIVATE)
            .edit().putBoolean(key(uid), enabled).apply()
    }

    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 34 || runCatching {
        context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
    }.getOrDefault(false)

    fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun highImportance(context: Context): Boolean = Build.VERSION.SDK_INT < 26 ||
        (context.getSystemService(NotificationManager::class.java)?.getNotificationChannel("personnel-muster")?.importance
            ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH

    fun openPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
