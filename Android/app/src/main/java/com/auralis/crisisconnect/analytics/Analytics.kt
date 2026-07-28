package com.auralis.crisisconnect.analytics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Product-metric events on top of Firebase Analytics. Device counts, sessions and engagement time
 * come from the SDK's automatic collection; this wrapper only adds the app-specific events the
 * dashboard can't infer (SOS, messages per transport, calls, sentinel usage, …).
 *
 * Analytics must never break a feature path — every call is guarded and becomes a no-op when
 * [init] hasn't run yet (or Firebase is unavailable, e.g. in unit tests).
 */
object Analytics {

    @SuppressLint("StaticFieldLeak") // holds the application context only
    @Volatile
    private var instance: FirebaseAnalytics? = null

    fun init(context: Context) {
        runCatching {
            instance = FirebaseAnalytics.getInstance(context.applicationContext)
        }
    }

    /** SOS mode switched on (BLE broadcast started). */
    fun sosActivated() = log("sos_activated")

    /** SOS self-report accepted by the `reportSosSignal` callable. */
    fun sosCloudReported(status: String) = log("sos_cloud_reported") {
        putString("status", status.take(36))
    }

    /**
     * A user-authored chat message left the composer.
     * [kind] text / voice / image; [transport] bluetooth / internet / ble_gatt / ble_mesh;
     * [chat] direct / authority_mesh.
     */
    fun messageSent(kind: String, transport: String, chat: String = "direct") = log("message_sent") {
        putString("kind", kind)
        putString("transport", transport.take(36))
        putString("chat", chat)
    }

    /** Outgoing voice call placed. [transport] bluetooth / internet / p2p_gatt / authority_sfu / authority_p2p. */
    fun callStarted(transport: String) = log("call_started") {
        putString("transport", transport)
    }

    /** Call media actually connected (fires on both legs, once per call). */
    fun callConnected(transport: String) = log("call_connected") {
        putString("transport", transport)
    }

    /** Call finished. [result] answered / missed / rejected / canceled; duration 0 unless answered. */
    fun callEnded(transport: String, durationSeconds: Long, result: String) = log("call_ended") {
        putString("transport", transport)
        putLong("duration_seconds", durationSeconds)
        putString("result", result.take(36))
    }

    /** Crisis Sentinel prompt submitted. [engine] edge / online. */
    fun sentinelPrompt(engine: String) = log("sentinel_prompt") {
        putString("engine", engine.take(36))
    }

    /**
     * A brand-new contact row was created by a deliberate user action (updates, hidden bridge
     * contacts and internal bookkeeping saves excluded).
     * [method] qr / directory / authority_roster / nearby / manual_mac.
     */
    fun contactAdded(method: String, transport: String) = log("contact_added") {
        putString("method", method.take(36))
        putString("transport", transport.take(36))
    }

    /**
     * A contact row was auto-created because a PEER added this user (the passive side of an add).
     * [via] internet_message / ble_gatt_peer.
     */
    fun contactReceived(via: String, transport: String) = log("contact_received") {
        putString("via", via.take(36))
        putString("transport", transport.take(36))
    }

    /** Push-to-talk floor claimed on the authority mesh. */
    fun pttTransmit() = log("ptt_transmit")

    /** Navigation moved to [route] (the route pattern, never argument values). */
    fun screenView(route: String) = log(FirebaseAnalytics.Event.SCREEN_VIEW) {
        // Route patterns like "chat/{sessionCode}" carry no user data; strip nothing but length.
        putString(FirebaseAnalytics.Param.SCREEN_NAME, route.take(100))
    }

    private inline fun log(event: String, crossinline params: Bundle.() -> Unit = {}) {
        val analytics = instance ?: return
        runCatching {
            analytics.logEvent(event, Bundle().apply(params))
        }
    }
}
