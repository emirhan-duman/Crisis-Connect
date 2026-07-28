package com.auralis.crisisconnect.messaging.call

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.drawable.IconCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager
import com.auralis.crisisconnect.messaging.call.sfu.SfuCallState
import com.auralis.crisisconnect.messaging.call.sfu.SfuUiCall
import com.auralis.crisisconnect.service.NotificationAvatarFormatter
import com.auralis.crisisconnect.service.RfcommForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps an active internet (WebRTC) call alive when the app is backgrounded, and renders the call as
 * proper call notifications (parity with the Bluetooth call stack's [RfcommForegroundService]):
 *
 *  - INCOMING → a ringing [NotificationCompat.CallStyle] notification with answer/decline actions, a
 *    full-screen intent and the device ringtone looping — so an internet call rings even when no
 *    activity is showing.
 *  - DIALING/CONNECTING/ACTIVE → an ongoing CallStyle notification with the peer's name + avatar, a
 *    live chronometer once connected, and mute/hang-up actions.
 *
 * The notification re-renders from [InternetCallManager.call] as the call changes. Android 14 requires
 * a `microphone` foreground service for background mic capture, so that type is added once the call is
 * answered (never while only ringing — background starts of mic-typed services are blocked). Start
 * failures are caught and degrade to today's behavior (call runs while the app is foreground).
 */
class InternetCallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ringtone: Ringtone? = null
    private var foregroundActive = false
    private var avatarSessionCode: String? = null
    private var avatarBitmap: Bitmap? = null

    // Ring for which the direct screen-wake fallback already ran (see maybeWakeScreenForIncomingCall).
    private var wakeLaunchedCallKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Live notification: re-render whenever the call changes (state, mute, video, peer), and keep
        // the ringtone in lockstep with the INCOMING state.
        scope.launch {
            InternetCallManager.call.collect { call -> onCallChanged(call) }
        }
        // Authority SFU calls live in their own manager; without this collector they rendered the
        // generic "call in progress" placeholder instead of a proper CallStyle notification.
        scope.launch {
            SfuAuthorityCallManager.uiCall.collect { if (foregroundActive) onCallChanged(InternetCallManager.call.value) }
        }
    }

    override fun onDestroy() {
        stopRingtone()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            mediaProjectionActive = false
            stopRingtone()
            foregroundActive = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Screen sharing needs the mediaProjection FGS type ACTIVE before the projection is created
        // (Android 14+ enforces this). MainActivity restarts us with ACTION_ENABLE_MEDIA_PROJECTION
        // right after the user grants the capture consent; once on, keep it for the rest of the call.
        if (intent?.action == ACTION_ENABLE_MEDIA_PROJECTION) mediaProjectionActive = true
        val call = InternetCallManager.call.value
        // No live call in either engine → nothing to keep alive. This is the post-process-death
        // restart (call state died with the process) or a start that raced the call's end. Honour
        // the startForegroundService contract, then tear straight down — without this the service
        // sat forever on the placeholder "call in progress" notification.
        val p2pLive = call != null &&
            call.state != InternetCallManager.State.ENDED &&
            call.state != InternetCallManager.State.IDLE
        if (!p2pLive && sfuUiCall() == null) {
            runCatching {
                ServiceCompat.startForeground(
                    this, FOREGROUND_ID, servicePlaceholderNotification(),
                    foregroundServiceType(includeMicrophone = false)
                )
            }
            stopRingtone()
            foregroundActive = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // While only ringing nothing captures the mic yet — and Android 14+ blocks starting a
        // microphone-typed FGS from the background — so ring as a plain phoneCall FGS; the mic type is
        // added when the service restarts on accept. If even that fails, retry without the mic type.
        val ringingOnly = call?.state == InternetCallManager.State.INCOMING ||
            (call == null && sfuUiCall()?.state == SfuCallState.INCOMING)
        val started = runCatching {
            ServiceCompat.startForeground(
                this, FOREGROUND_ID, buildNotification(call),
                foregroundServiceType(includeMicrophone = !ringingOnly)
            )
        }.recoverCatching {
            ServiceCompat.startForeground(
                this, FOREGROUND_ID, buildNotification(call),
                foregroundServiceType(includeMicrophone = false)
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        foregroundActive = true
        if (ringingOnly) {
            postRingNotification()
            startRingtone()
            maybeWakeScreenForIncomingCall(incomingCallKey(call))
        } else {
            stopRingtone()
        }
        // Now that the mediaProjection FGS type is live, it's safe to create the screen capture — doing
        // it here (rather than racing the async service start from the UI) avoids the Android 14+
        // "media projections require a mediaProjection foreground service" crash.
        if (intent?.action == ACTION_ENABLE_MEDIA_PROJECTION) {
            IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)?.let { data ->
                if (sfuUiCall() != null && InternetCallManager.call.value == null) {
                    SfuAuthorityCallManager.startScreenShare(data)
                } else {
                    InternetCallManager.startScreenShare(data)
                }
            }
        }
        // Control actions from the notification / system call UI (Telecom); route them to whichever
        // engine owns the call (P2P WebRTC or authority SFU). cleanup() then sends ACTION_STOP for
        // reject/end, which stops this service.
        val sfuOwns = (call == null ||
            call.state == InternetCallManager.State.ENDED ||
            call.state == InternetCallManager.State.IDLE) && sfuUiCall() != null
        when (intent?.action) {
            RfcommForegroundService.ACTION_ACCEPT_CALL -> {
                val hasMic = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    // No mic permission → open the app; the in-app overlay requests it and answers.
                    runCatching { openAppIntent().send() }
                } else {
                    // Still reached for answers that do NOT come from the ring notification (Telecom,
                    // a headset button). The notification's own answer action is an activity
                    // PendingIntent instead — see answerCallPendingIntent — because starting the UI
                    // from here is BAL-blocked.
                    if (sfuOwns) SfuAuthorityCallManager.accept() else InternetCallManager.accept()
                }
            }
            RfcommForegroundService.ACTION_REJECT_CALL ->
                if (sfuOwns) SfuAuthorityCallManager.reject() else InternetCallManager.reject()
            RfcommForegroundService.ACTION_END_CALL ->
                if (sfuOwns) SfuAuthorityCallManager.end() else InternetCallManager.end()
            RfcommForegroundService.ACTION_MUTE ->
                if (sfuOwns) SfuAuthorityCallManager.setMuted(true) else InternetCallManager.setMuted(true)
            RfcommForegroundService.ACTION_UNMUTE ->
                if (sfuOwns) SfuAuthorityCallManager.setMuted(false) else InternetCallManager.setMuted(false)
        }
        // Never sticky: a call cannot survive process death (all state is in-process), so a system
        // restart with a null intent could only ever produce a zombie placeholder notification.
        return START_NOT_STICKY
    }

    private fun onCallChanged(call: InternetCallManager.CallInfo?) {
        if (call == null ||
            call.state == InternetCallManager.State.ENDED ||
            call.state == InternetCallManager.State.IDLE
        ) {
            // The P2P slot is empty — an authority SFU call may own this service instead.
            val sfu = sfuUiCall()
            if (sfu != null) {
                if (sfu.state == SfuCallState.INCOMING) {
                    postRingNotification()
                    startRingtone()
                    maybeWakeScreenForIncomingCall(incomingCallKey(null))
                } else {
                    stopRingtone()
                }
                if (foregroundActive) {
                    runCatching {
                        NotificationManagerCompat.from(this).notify(FOREGROUND_ID, buildNotification(null))
                    }
                }
                return
            }
            // cleanup() also sends ACTION_STOP, but don't depend on that intent arriving: stop
            // ourselves so the ongoing-call notification can never outlive the call.
            stopRingtone()
            if (foregroundActive) {
                foregroundActive = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        if (call.state == InternetCallManager.State.INCOMING) {
            postRingNotification()
            startRingtone()
            maybeWakeScreenForIncomingCall(incomingCallKey(call))
        } else {
            stopRingtone()
        }
        if (foregroundActive) {
            runCatching {
                NotificationManagerCompat.from(this).notify(FOREGROUND_ID, buildNotification(call))
            }
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.internet_call_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        if (manager.getNotificationChannel(RING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    RING_CHANNEL_ID,
                    getString(R.string.internet_call_ring_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    // The service loops the device ringtone itself (parity with Bluetooth calls); a
                    // channel sound on top would double-ring.
                    setSound(null, null)
                    enableVibration(true)
                }
            )
        }
    }

    private fun foregroundServiceType(includeMicrophone: Boolean): Int {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (mediaProjectionActive) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        }
        if (includeMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    /** Mirrors RfcommForegroundService: on a locked device the ring's full-screen intent owns the UI,
     *  so a plain activity launch would be swallowed and must be skipped. */
    private fun isDeviceLockedForIncomingUi(): Boolean {
        val keyguardManager = getSystemService(KeyguardManager::class.java) ?: return false
        return keyguardManager.isDeviceLocked
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            launch ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Launch intent for an incoming ring. Unlike [openAppIntent] this must be the trusted
     * MainActivity launch carrying EXTRA_WAKE_FOR_INCOMING_CALL: that's the only path that arms
     * setShowWhenLocked/setTurnScreenOn, so when the system fires the full-screen intent with the
     * screen off the call UI actually lights up over the keyguard instead of launching behind it
     * (parity with RfcommCallNotificationDelegate.createCallScreenPendingIntent).
     */
    private fun incomingCallLaunchIntent(): Intent =
        MainActivity.createTrustedLaunchIntent(this) {
            putExtra(MainActivity.EXTRA_WAKE_FOR_INCOMING_CALL, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /// Answer action for the ring notification.
    ///
    /// This has to be an ACTIVITY PendingIntent, not a service one. When the answer action targeted
    /// the service, the service answered and then tried to start MainActivity itself — and Android
    /// refused it outright ("START ... (BAL_BLOCK) result code=102" in a device trace): a background
    /// activity launch from a service is blocked, and the notification tap's privilege does NOT carry
    /// over to a fresh PendingIntent the service creates and sends. The call went live with no UI.
    /// Launching the activity FROM the notification action is the user's own tap, so BAL never
    /// applies; the same creator-mode options the full-screen ring intent already uses cover the
    /// screen-off case. MainActivity performs the accept once it is up.
    private fun answerCallPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_ANSWER_CALL_SCREEN,
            incomingCallLaunchIntent().putExtra(MainActivity.EXTRA_ANSWER_INTERNET_CALL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            incomingCallPendingIntentOptions()
        )

    private fun incomingCallPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_INCOMING_CALL_SCREEN,
            incomingCallLaunchIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            incomingCallPendingIntentOptions()
        )

    private fun incomingCallPendingIntentOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        return ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            .toBundle()
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false)
    }

    /**
     * Narrow fallback (parity with RfcommForegroundService.showIncomingCallNotification): if
     * Android 14+ full-screen-intent access is revoked and the screen is off, attempt a direct
     * launch so the ring still surfaces the call UI. At most once per ring — the notification
     * re-renders on every state change and must not re-launch the activity each time.
     */
    private fun maybeWakeScreenForIncomingCall(callKey: String) {
        if (wakeLaunchedCallKey == callKey) return
        wakeLaunchedCallKey = callKey
        if (canUseFullScreenIntent()) return
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (interactive) return
        runCatching { startActivity(incomingCallLaunchIntent()) }
    }

    /** Stable identity of the ring currently being surfaced — P2P callId, or the SFU peer. */
    private fun incomingCallKey(call: InternetCallManager.CallInfo?): String =
        call?.callId ?: sfuUiCall()?.let { "sfu:${it.peerUid}" } ?: "ring"

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, InternetCallForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun resolveAvatar(sessionCode: String, name: String): Bitmap? {
        if (sessionCode.isBlank()) return null
        if (avatarSessionCode == sessionCode) return avatarBitmap
        val resolved = runCatching {
            NotificationAvatarFormatter.resolveContactAvatarBitmap(this, sessionCode, name)
        }.getOrNull()
        avatarSessionCode = sessionCode
        avatarBitmap = resolved
        return resolved
    }

    private fun servicePlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.internet_call_service_notification_title))
            .setContentText(getString(R.string.internet_call_service_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun callPerson(name: String, avatar: Bitmap?): Person =
        Person.Builder()
            .setName(name)
            .setIcon(avatar?.let(IconCompat::createWithBitmap))
            .setImportant(true)
            .build()

    /**
     * The dedicated high-priority ring notification. Posted SEPARATELY from the foreground-service
     * notification (own id, plain notify()): a full-screen intent attached to a startForeground()
     * post is unreliably fired on several OEMs with the screen off — the Bluetooth call stack
     * (RfcommCallUiDelegate.showIncomingCallNotification) avoids exactly that, and the internet ring
     * must match or it silently rings behind a dark screen.
     */
    private fun buildRingNotification(name: String, person: Person): Notification {
        val ringIntent = incomingCallPendingIntent()
        return NotificationCompat.Builder(this, RING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.chat_call_incoming_title, name))
            .setContentText(getString(R.string.chat_call_incoming_message))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(ringIntent)
            .setFullScreenIntent(ringIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addPerson(person)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    actionPendingIntent(RfcommForegroundService.ACTION_REJECT_CALL, 3),
                    answerCallPendingIntent()
                )
            )
            .build()
    }

    /** Posts the dedicated ring notification for whichever engine (P2P / SFU) is ringing. */
    private fun postRingNotification() {
        ensureChannels()
        val call = InternetCallManager.call.value
        val notification = if (call?.state == InternetCallManager.State.INCOMING) {
            val name = call.peerName.ifBlank { getString(R.string.internet_call_unknown_peer) }
            buildRingNotification(name, callPerson(name, resolveAvatar(call.sessionCode, name)))
        } else {
            val sfu = sfuUiCall()?.takeIf { it.state == SfuCallState.INCOMING } ?: return
            val name = sfu.peerName.ifBlank { getString(R.string.internet_call_unknown_peer) }
            buildRingNotification(name, callPerson(name, resolveAvatar(sfu.peerUid, name)))
        }
        runCatching { NotificationManagerCompat.from(this).notify(RING_NOTIFICATION_ID, notification) }
    }

    private fun cancelRingNotification() {
        runCatching { NotificationManagerCompat.from(this).cancel(RING_NOTIFICATION_ID) }
    }

    private fun buildNotification(call: InternetCallManager.CallInfo?): Notification {
        ensureChannels()
        if (call == null ||
            call.state == InternetCallManager.State.ENDED ||
            call.state == InternetCallManager.State.IDLE
        ) {
            // Authority SFU calls carry their state in their own manager — render them as a proper
            // CallStyle notification instead of the generic placeholder.
            sfuUiCall()?.let { return buildSfuNotification(it) }
            // Transitional frame (service starting while the call is already gone).
            return servicePlaceholderNotification()
        }
        val name = call.peerName.ifBlank { getString(R.string.internet_call_unknown_peer) }
        val avatar = resolveAvatar(call.sessionCode, name)
        val person = callPerson(name, avatar)
        val contentIntent = openAppIntent()
        if (call.state == InternetCallManager.State.INCOMING) {
            // The ring rides its own dedicated notification (postRingNotification); the FGS slot
            // stays a silent placeholder so full-screen handling is never tied to startForeground.
            return servicePlaceholderNotification()
        }
        val stateTitle = when (call.state) {
            InternetCallManager.State.DIALING -> getString(R.string.internet_call_dialing)
            InternetCallManager.State.CONNECTING -> getString(R.string.internet_call_connecting)
            else -> getString(R.string.chat_call_ongoing_banner)
        }
        val muteIntent = actionPendingIntent(
            if (call.muted) RfcommForegroundService.ACTION_UNMUTE else RfcommForegroundService.ACTION_MUTE,
            4
        )
        val muteTitle = getString(
            if (call.muted) R.string.internet_call_unmute else R.string.internet_call_mute
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(stateTitle)
            .setContentText(name)
            .setSubText(getString(R.string.chat_call_via_internet))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addPerson(person)
            .apply {
                call.connectedAt?.let {
                    setWhen(it)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                if (avatar != null) setLargeIcon(avatar)
                addAction(R.drawable.ic_notification, muteTitle, muteIntent)
            }
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person,
                    actionPendingIntent(RfcommForegroundService.ACTION_END_CALL, 1)
                ).setIsVideo(call.cameraOn || call.sharingScreen || call.remoteVideo)
            )
            .build()
    }

    /** The active authority SFU call, if that's what this service is keeping alive. */
    private fun sfuUiCall(): SfuUiCall? =
        SfuAuthorityCallManager.uiCall.value?.takeIf {
            it.state != SfuCallState.IDLE && it.state != SfuCallState.ENDED
        }

    /**
     * CallStyle notification for an authority SFU call — same look as the P2P path: peer name +
     * deterministic avatar, dialing/connecting state line, live chronometer once connected, and
     * mute/hang-up actions (routed to [SfuAuthorityCallManager] in onStartCommand).
     */
    private fun buildSfuNotification(call: SfuUiCall): Notification {
        val name = call.peerName.ifBlank { getString(R.string.internet_call_unknown_peer) }
        val avatar = resolveAvatar(sessionCode = call.peerUid, name = name)
        val person = Person.Builder()
            .setName(name)
            .setIcon(avatar?.let(IconCompat::createWithBitmap))
            .setImportant(true)
            .build()
        val contentIntent = openAppIntent()
        if (call.state == SfuCallState.INCOMING) {
            // Same as the P2P path: the ring is a dedicated notification, not the FGS one.
            return servicePlaceholderNotification()
        }
        val stateTitle = when (call.state) {
            SfuCallState.OUTGOING -> getString(R.string.internet_call_dialing)
            SfuCallState.CONNECTING -> getString(R.string.internet_call_connecting)
            else -> getString(R.string.chat_call_ongoing_banner)
        }
        val muteIntent = actionPendingIntent(
            if (call.muted) RfcommForegroundService.ACTION_UNMUTE else RfcommForegroundService.ACTION_MUTE,
            4
        )
        val muteTitle = getString(
            if (call.muted) R.string.internet_call_unmute else R.string.internet_call_mute
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(stateTitle)
            .setContentText(name)
            .setSubText(getString(R.string.chat_call_via_internet))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addPerson(person)
            .apply {
                call.connectedAtMillis?.let {
                    setWhen(it)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                if (avatar != null) setLargeIcon(avatar)
                addAction(R.drawable.ic_notification, muteTitle, muteIntent)
            }
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person,
                    actionPendingIntent(RfcommForegroundService.ACTION_END_CALL, 1)
                ).setIsVideo(call.cameraOn || call.sharingScreen)
            )
            .build()
    }

    private fun startRingtone() {
        if (ringtone?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val tone = ringtone ?: RingtoneManager.getRingtone(applicationContext, uri)?.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
            }
        } ?: return
        ringtone = tone
        runCatching { tone.play() }
    }

    private fun stopRingtone() {
        runCatching { ringtone?.stop() }
        cancelRingNotification()
        // The ring is over; let a future ring (possibly from the same SFU peer) wake the screen again.
        wakeLaunchedCallKey = null
    }

    companion object {
        private const val ACTION_STOP = "com.auralis.crisisconnect.messaging.call.ACTION_STOP"
        private const val ACTION_ENABLE_MEDIA_PROJECTION =
            "com.auralis.crisisconnect.messaging.call.ACTION_ENABLE_MEDIA_PROJECTION"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val FOREGROUND_ID = 4301
        // The ring's own notification id — deliberately NOT the FGS notification (see
        // buildRingNotification for why the full-screen intent must not ride startForeground).
        private const val RING_NOTIFICATION_ID = 4302
        private const val CHANNEL_ID = "internet_call"
        private const val RING_CHANNEL_ID = "internet_call_ring"

        // Distinct from openAppIntent's request code 0 and the action codes 1-4, so the wake extra
        // never leaks into the plain open-app pending intent (FLAG_UPDATE_CURRENT shares by code).
        private const val REQUEST_INCOMING_CALL_SCREEN = 5
        private const val REQUEST_ANSWER_CALL_SCREEN = 6

        // Sticky across restarts: once screen sharing turns the mediaProjection FGS type on, keep it
        // for the call's lifetime so a START_STICKY redelivery doesn't drop the type mid-share.
        @Volatile
        private var mediaProjectionActive = false

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, InternetCallForegroundService::class.java)
                )
            }
        }

        /**
         * Promote this service to a mediaProjection foreground service and hand off the capture consent
         * [projectionData]; the screen capture is started inside the service once the FGS type is live.
         */
        fun startWithScreenShare(context: Context, projectionData: Intent) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, InternetCallForegroundService::class.java)
                        .setAction(ACTION_ENABLE_MEDIA_PROJECTION)
                        .putExtra(EXTRA_PROJECTION_DATA, projectionData)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, InternetCallForegroundService::class.java)
                        .setAction(ACTION_STOP)
                )
            }
        }
    }
}
