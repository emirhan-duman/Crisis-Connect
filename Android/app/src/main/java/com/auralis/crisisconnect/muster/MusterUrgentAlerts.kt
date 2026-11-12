package com.auralis.crisisconnect.muster

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.security.MessageDigest

/** User-owned account preferences. Never changes global volume, ringer or Do Not Disturb. */
internal object MusterUrgentAlerts {
    private fun key(uid: String) = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun prefs(context: Context) = context.getSharedPreferences("muster_urgent_v1", Context.MODE_PRIVATE)
    fun enabled(context: Context, uid: String) = prefs(context).getBoolean("sound:${key(uid)}", false)
    fun setEnabled(context: Context, uid: String, value: Boolean) { prefs(context).edit().putBoolean("sound:${key(uid)}", value).apply() }
    fun duration(context: Context, uid: String) = prefs(context).getInt("duration:${key(uid)}", 10).takeIf { it in listOf(5, 10, 20) } ?: 10
    fun setDuration(context: Context, uid: String, seconds: Int) { require(seconds in listOf(5, 10, 20)); prefs(context).edit().putInt("duration:${key(uid)}", seconds).apply() }
    fun policyAllows(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val filter = manager.currentInterruptionFilter
        // Priority-only and total silence are respected; no request to bypass DND.
        return filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }
    fun ready(context: Context): Boolean = policyAllows(context) &&
        (context.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0) > 0 &&
        MusterPresentation.notificationsEnabled(context) && MusterPresentation.highImportance(context)
    internal fun shouldSound(urgent: Boolean, drill: Boolean, optedIn: Boolean, allowed: Boolean, silent: Boolean) = urgent && !drill && optedIn && allowed && silent

    /** Bounded deduplication of FCM wakes (initial and at most two reminder rounds). */
    @Synchronized fun firstWake(context: Context, uid: String, panel: String, session: String, round: Int): Boolean {
        val id = key("$uid:$panel:$session:$round")
        val storage = prefs(context)
        val seen = storage.getString("seen", "")!!.split(',').filter { it.isNotEmpty() }
        if (id in seen) return false
        // Commit before ringing so process restart cannot replay the same wake.
        storage.edit().putString("seen", (seen.takeLast(63) + id).joinToString(",")).commit()
        return true // Storage failure must never suppress the first emergency alert.
    }
    fun openFlashSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}

/** Plays only while the real check-in activity is visible; stops on touch, background or timeout. */
internal class MusterUrgentTone(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var started = false
    private val stopTask = Runnable { stop() }
    fun start(uid: String, urgent: Boolean, drill: Boolean) {
        if (started) return
        started = true
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (!MusterUrgentAlerts.shouldSound(urgent, drill, MusterUrgentAlerts.enabled(context, uid), MusterUrgentAlerts.ready(context), audio.ringerMode != AudioManager.RINGER_MODE_NORMAL)) return
        runCatching {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            handler.postDelayed(stopTask, MusterUrgentAlerts.duration(context, uid) * 1000L)
        }.onFailure { stop() }
    }
    fun stop() { handler.removeCallbacks(stopTask); runCatching { ringtone?.stop() }; ringtone = null }
}
