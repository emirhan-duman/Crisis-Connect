package com.auralis.crisisconnect.muster

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.util.UUID

internal fun musterLocalizedContext(context: Context): Context {
    val config = android.content.res.Configuration(context.resources.configuration).apply {
        setLocale(com.auralis.crisisconnect.localeForLanguageCode(com.auralis.crisisconnect.getSavedLanguageSync(context)))
    }
    return context.createConfigurationContext(config)
}

internal fun validMusterAlertExpiry(profile: String, round: Int, expires: Long?, now: Long): Boolean =
    if (profile == "urgent" || round > 0) expires != null && expires > now else true

internal object MusterNotifications {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun receive(context: Context, data: Map<String, String>) {
        val kind = data["kind"] ?: return
        if (data["type"] != "personnel_muster" || kind !in setOf("live", "drill")) return
        val uid = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }?.uid ?: return
        if (data["recipientUid"] != uid) return
        val panel = data["panelId"] ?: return; val session = data["sessionId"] ?: return
        if (!Regex("^[A-Za-z0-9_-]{1,128}$").matches(panel) || !Regex("^[a-f0-9]{32}$").matches(session)) return
        val profile = data["alertProfile"] ?: "standard"
        if (profile !in setOf("standard", "urgent") || kind == "drill" && profile == "urgent") return
        val round = data["reminderRound"]?.toIntOrNull() ?: if (data.containsKey("reminderRound")) return else 0
        if (round !in 0..2) return
        if (!validMusterAlertExpiry(profile, round, data["expiresAtMillis"]?.toLongOrNull(), System.currentTimeMillis())) return
        runCatching { MusterClient.cacheWake(context, uid, panel, session, kind, data["responseConfigVersion"] == "1") }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!MusterUrgentAlerts.firstWake(context, uid, panel, session, round)) return
        manager.notify("muster:$uid:$panel:$session".hashCode(), buildNotification(context, uid, panel, session, kind = kind, alertProfile = profile, reminderRound = round, expiresAtMillis = data["expiresAtMillis"]?.toLongOrNull() ?: 0, legacyActions = data["responseConfigVersion"] != "1"))
        MusterPresentation.presentWithinApp(context, uid, panel, session, kind, profile, data["expiresAtMillis"]?.toLongOrNull() ?: 0)
        scope.launch { runCatching { MusterClient.refresh(context, panel, session) } }
    }

    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localized = musterLocalizedContext(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel("personnel-muster")
            ?: NotificationChannel("personnel-muster", localized.getString(R.string.muster_title), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        // Preserve existing sound, importance and vibration choices. Only labels follow the app language.
        channel.name = localized.getString(R.string.muster_title)
        channel.description = localized.getString(R.string.muster_alert_settings_help)
        manager.createNotificationChannel(channel)
    }

    internal fun buildNotification(context: Context, uid: String, panel: String, session: String,
        fullScreenAllowed: Boolean = MusterPresentation.allowed(context), kind: String = "live", alertProfile: String = "standard", reminderRound: Int = 0, expiresAtMillis: Long = 0, legacyActions: Boolean = true): Notification {
        val localized = musterLocalizedContext(context)
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, "$uid:$panel:$session".hashCode(), Intent(context, MusterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("panelId", panel).putExtra("sessionId", session).putExtra("kind", kind).putExtra("uid", uid).putExtra("expiresAtMillis", expiresAtMillis).putExtra("alertProfile", if (kind == "live" && alertProfile == "urgent") "urgent" else "standard"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "personnel-muster").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(if (kind == "drill") R.string.muster_drill_title else R.string.muster_title))
            .setContentText(localized.getString(if (kind == "drill") R.string.muster_drill_notification else R.string.muster_notification))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(reminderRound == 0)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (fullScreenAllowed && MusterPresentation.enabled(context, uid)) {
            notification.setFullScreenIntent(open, true)
        }
        if (legacyActions) listOf("safe" to R.string.muster_safe, "help" to R.string.muster_help, "at_muster" to R.string.muster_at_point).forEach { (answer, label) ->
            val intent = Intent(context, MusterAnswerReceiver::class.java).setAction("muster.$panel.$session.$answer")
                .putExtra("uid", uid).putExtra("panelId", panel).putExtra("sessionId", session).putExtra("answer", answer).putExtra("operationId", UUID.randomUUID().toString())
            val pending = PendingIntent.getBroadcast(context, "$uid:$panel:$session:$answer".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(NotificationCompat.Action.Builder(0, localized.getString(label), pending).setAuthenticationRequired(true).build())
        }
        return notification.build()
    }
}

class MusterAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(baseContext: Context, intent: Intent) {
        val context = musterLocalizedContext(baseContext)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val uid = intent.getStringExtra("uid") ?: return@launch
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@launch
                val panel = intent.getStringExtra("panelId") ?: return@launch; val session = intent.getStringExtra("sessionId") ?: return@launch
                MusterClient.enqueue(context, uid, panel, session, intent.getStringExtra("answer") ?: return@launch, "", intent.getStringExtra("operationId"))
                val manager = context.getSystemService(NotificationManager::class.java)
                val open = PendingIntent.getActivity(context, session.hashCode(), Intent(context, MusterActivity::class.java).putExtra("panelId", panel).putExtra("sessionId", session), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, "personnel-muster").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.muster_title)).setContentText(context.getString(R.string.muster_queued)).setContentIntent(open).setAutoCancel(true).build()
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { manager.notify("muster:$uid:$panel:$session".hashCode(), notification) }
                }
            } catch (_: Exception) {
                val notification = NotificationCompat.Builder(context, "personnel-muster").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.muster_title)).setContentText(context.getString(R.string.muster_failed)).build()
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { context.getSystemService(NotificationManager::class.java).notify("muster-save-error".hashCode(), notification) }
                }
            } finally { pending.finish() }
        }
    }
}
