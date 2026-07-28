package com.auralis.crisisconnect.ui.components

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

// Only Google Play Services holds this permission, so the exported receiver below can only be
// triggered by the SMS User Consent broadcast — not by other apps.
private const val SMS_CONSENT_SEND_PERMISSION =
    "com.google.android.gms.auth.api.phone.permission.SEND"

/** Pulls the first 4–8 digit run out of a verification SMS, preferring a 6-digit code. */
private fun extractOtp(message: String): String? =
    Regex("\\d{6}").find(message)?.value ?: Regex("\\d{4,8}").find(message)?.value

/**
 * Runs the SMS User Consent flow while [isListening] is true: when a verification SMS arrives,
 * the system shows a one-tap "allow" prompt, and on approval the code is parsed out and passed
 * to [onCodeReceived].
 *
 * The verification SMS often arrives while the app is behind Firebase's reCAPTCHA browser tab,
 * where Android blocks launching the consent prompt. In that case the consent intent is held
 * and launched as soon as the app returns to the foreground, so the one-tap prompt still shows.
 *
 * Purely additive and safe by design — it needs no SMS permission and no manifest changes. If
 * Play Services is missing, the SMS never arrives, or the user declines, nothing happens and
 * both manual entry and Firebase's own auto-retrieval keep working.
 */
@Composable
fun SmsOtpAutoFillEffect(
    isListening: Boolean,
    onCodeReceived: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCodeReceived = rememberUpdatedState(onCodeReceived)
    // Consent intent captured while the app wasn't resumed (e.g. reCAPTCHA tab on top).
    var pendingConsentIntent by remember { mutableStateOf<Intent?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
            val code = message?.let { extractOtp(it) }
            if (!code.isNullOrBlank()) latestOnCodeReceived.value(code)
        }
    }

    // Flush a held consent prompt once the app is back in the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pendingConsentIntent?.let { held ->
                    pendingConsentIntent = null
                    runCatching { consentLauncher.launch(held) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(isListening) {
        if (!isListening) return@DisposableEffect onDispose { }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val status = IntentCompat.getParcelableExtra(
                    intent, SmsRetriever.EXTRA_STATUS, Status::class.java
                )
                if (status?.statusCode != CommonStatusCodes.SUCCESS) return
                val consentIntent = IntentCompat.getParcelableExtra(
                    intent, SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java
                ) ?: return
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    runCatching { consentLauncher.launch(consentIntent) }
                } else {
                    // App is behind the reCAPTCHA tab (or otherwise backgrounded): Android
                    // would block the prompt now, so hold it for the next ON_RESUME.
                    pendingConsentIntent = consentIntent
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SMS_CONSENT_SEND_PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        // null = accept a code SMS from any sender (Firebase's sender varies by region).
        runCatching { SmsRetriever.getClient(context).startSmsUserConsent(null) }

        onDispose {
            pendingConsentIntent = null
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
