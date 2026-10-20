package com.auralis.crisisconnect.watch

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/** Explicit account and saved-contact consent. Device fingerprints are never accepted silently. */
class IndependentMessagingActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(TextView(this).apply { setText(R.string.watch_independent_title); textSize = 22f })
        notice = TextView(this)
        content.addView(notice)
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.isAnonymous) {
            notice.setText(R.string.watch_independent_sign_in)
            return
        }
        val inbox = IndependentPhoneInbox(applicationContext, user.uid)
        notice.setText(R.string.watch_independent_explanation)
        button(getString(R.string.watch_independent_enable_phone)) {
            inbox.enable(); notice.setText(R.string.watch_independent_phone_ready)
        }
        button(getString(R.string.watch_independent_authorize_watch)) {
            inbox.pairWatch(); notice.setText(R.string.watch_independent_watch_ready)
        }
        button(getString(R.string.watch_independent_refresh)) {
            inbox.refresh(); notice.setText(R.string.watch_independent_refreshed)
        }
        button(getString(R.string.watch_independent_disable_phone)) {
            inbox.disable(); notice.setText(R.string.watch_independent_phone_disabled)
        }
        scope.launch {
            val contacts = withContext(Dispatchers.IO) { observeContacts(applicationContext).first() }
                .filter(WatchContactMessaging::eligible)
            for (contact in contacts) button(contact.name) {
                require(inbox.isEnabled()) { getString(R.string.watch_independent_enable_first) }
                val devices = inbox.devices(contact)
                if (devices.isEmpty()) notice.setText(R.string.watch_independent_contact_unavailable)
                for (device in devices) {
                    val identity = device.getJSONObject("bundle").getString("identityKey")
                    val fingerprint = MessageDigest.getInstance("SHA-256")
                        .digest(android.util.Base64.decode(identity, android.util.Base64.NO_WRAP))
                        .joinToString("") { "%02x".format(it) }.chunked(8).joinToString(" ")
                    AlertDialog.Builder(this@IndependentMessagingActivity)
                        .setTitle(getString(R.string.watch_independent_device_title, contact.name, device.optString("kind", "watch")))
                        .setMessage(getString(R.string.watch_independent_device_consent, fingerprint))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.watch_independent_revoke_device) { _, _ ->
                            launchAction { inbox.revokeContact(device); notice.setText(R.string.watch_independent_device_revoked) }
                        }
                        .setPositiveButton(R.string.watch_independent_approve_device) { _, _ ->
                            launchAction { inbox.approve(contact, device); notice.setText(R.string.watch_independent_device_approved) }
                        }.show()
                }
            }
        }
    }

    private fun button(title: String, action: suspend () -> Unit) {
        content.addView(Button(this).apply { text = title; setOnClickListener { launchAction(action) } })
    }

    private fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try { action() } catch (e: Exception) {
                notice.text = e.message ?: getString(R.string.watch_independent_action_failed)
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
