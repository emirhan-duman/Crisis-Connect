package com.auralis.crisisconnect.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.BleChatStore
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.data.saveLocalMessage
import com.auralis.crisisconnect.data.upsertLocalTextMessage
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.observeContacts
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.service.GattSOSServerService
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Wearable
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object PhoneWatchBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var started = false
    private val initialContactsReady = CompletableDeferred<Unit>()
    @Volatile private var savedContacts = emptyList<WatchSavedContact>()
    @Volatile private var savedContactCount = 0
    private const val CHANNEL = "watch_sos_action"
    private const val NOTIFICATION = 7304

    @Synchronized fun start(context: Context) {
        if (WatchTestHooks.enabled || started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            while (isActive) {
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (user != null && !user.isAnonymous) runCatching {
                    val receiver = IndependentPhoneInbox(app, user.uid)
                    if (receiver.isEnabled()) receiver.refresh()
                }
                delay(30_000) // Read/ack only. Never re-enqueue a message or start SOS.
            }
        }
        scope.launch {
            combine(observeContacts(app), AppDatabase.getInstance(app).messageDao().observeWatchContactPreviews(), WatchRecoveryStore.get(app).state) { contacts, messages, journal ->
                val visible = contacts.filter { !it.isAuthorityBridge }.sortedWith(compareBy({ it.name.lowercase() }, { it.sessionCode }))
                savedContactCount = visible.size
                savedContacts = visible.take(20).map { contact ->
                    val saved = journal.replies.lastOrNull { it.peer == contact.sessionCode }
                    val confirmed = messages.firstOrNull { it.messageUuid == saved?.id }?.deliveryStatus
                    WatchSavedContact(contact.sessionCode, contact.name.take(60), messages.firstOrNull { it.sessionCode == contact.sessionCode }?.text.orEmpty().take(100),
                        WatchContactMessaging.eligible(contact), contact.supportsInternet, contact.peerKeyChanged,
                        when (confirmed) { MessageDeliveryStatus.READ -> "read"; MessageDeliveryStatus.DELIVERED -> "delivered"; else -> saved?.result.orEmpty() })
                }
            }.collect {
                initialContactsReady.complete(Unit)
                publish(app)
            }
        }
        scope.launch { WatchRecoveryStore.get(app).state.collect { WatchReplyChat.reconcile(app); publish(app) } }
        scope.launch {
            combine(GattSOSServerService.isDeclared, GattSOSServerService.isAdvertising, BlePeerStore.peers) { _, _, _ -> Unit }
                .collectLatest {
                    publish(app)
                    val peer = GattSOSServerService.watchRescuePeer()
                    if (peer != null) BleChatStore.observeMessages(peer.sessionCode).collect { publish(app) }
                }
        }
    }

    private fun state(context: Context): PhoneWatchState {
        val peer = GattSOSServerService.watchRescuePeer()
        val state = PhoneWatchState(GattSOSServerService.isDeclared.value, GattSOSServerService.isAdvertising.value,
            GattSOSServerService.startTimestampMillis.value?.toString().orEmpty(), peer?.sessionCode.orEmpty(), peer?.name.orEmpty(),
            peer?.let { BleChatStore.observeMessages(it.sessionCode).value.lastOrNull()?.text }.orEmpty(), System.currentTimeMillis())
        if (WatchTestHooks.enabled && WatchTestHooks.delegate?.productionRecovery() != true) return requireNotNull(WatchTestHooks.delegate).state(state)
        val saved = WatchRecoveryStore.get(context).state.value
        val last = saved.replies.lastOrNull { it.kind != "contact" && (peer == null || it.peer == peer.sessionCode) }
        val contactInternetAvailable = savedContacts.isNotEmpty() && InternetChatTransport(context).isAvailable(allowSatellite = true)
        val restored = state.copy(
            emergencyID = state.emergencyID.ifBlank { saved.startedAt?.toString().orEmpty() },
            recoveryRequired = !state.sosActive && saved.startedAt != null,
            lastMessage = state.lastMessage.ifBlank { last?.text.orEmpty() },
            lastReplyResult = last?.result.orEmpty(),
            contacts = savedContacts.map { it.copy(canSend = it.canSend && contactInternetAvailable) },
            contactCount = savedContactCount
        )
        return if (WatchTestHooks.enabled) requireNotNull(WatchTestHooks.delegate).state(restored) else restored
    }

    private suspend fun publish(context: Context) {
        if (!initialContactsReady.isCompleted) return
        try {
            withTimeout(3_000) {
                val bytes = state(context).json().toString().toByteArray()
                Wearable.getNodeClient(context).connectedNodes.awaitWatch().forEach {
                    Wearable.getMessageClient(context).sendMessage(it.id, WatchPaths.STATE, bytes).awaitWatch()
                }
            }
        } catch (_: TimeoutCancellationException) {
            // A later status request can recover; never block the database observer indefinitely.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { }
    }

    suspend fun handle(context: Context, node: String, command: WatchCommand) = mutex.withLock {
        start(context)
        // Data Layer authenticates the same signed app on the paired network, including cloud routes.
        val connected = try {
            Wearable.getNodeClient(context).connectedNodes.awaitWatch().any { it.id == node }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
        if (!connected) return@withLock
        initialContactsReady.await()
        val receipt = processCommand(context, command)
        runCatching { Wearable.getMessageClient(context).sendMessage(node, WatchPaths.RECEIPT, receipt.toString().toByteArray()).awaitWatch() }
    }

    /** Only the isolated emulator APK may use the local developer transport. */
    suspend fun handleDeveloperCommand(context: Context, command: WatchCommand): JSONObject = mutex.withLock {
        check(WatchTestHooks.enabled && WatchTestHooks.delegate != null)
        processCommand(context, command).put("mode", "crisis-watch-dev-link-v1")
    }

    private suspend fun processCommand(context: Context, command: WatchCommand): JSONObject {
        val result = if (!command.valid(System.currentTimeMillis())) "invalid" else if (command.action == WatchAction.status) "accepted" else {
            val prefs = context.getSharedPreferences("watch_commands_v1", Context.MODE_PRIVATE)
            val previous = prefs.getString(command.id, null)
            if (previous != null) previous else {
                // Time-bounded ledger survives process death; never replay a reserved command.
                val expired = prefs.all.keys.filter { prefs.getLong("time_" + it, Long.MAX_VALUE) < System.currentTimeMillis() - 60_000 }
                val editor = prefs.edit()
                expired.forEach { editor.remove(it).remove("time_" + it) }
                if (!editor.putString(command.id, "uncertain").putLong("time_" + command.id, command.expiresAt).commit()) "failed"
                else {
                    val outcome = runCatching { execute(context, command) }.getOrElse { "failed" }
                    prefs.edit().putString(command.id, outcome).commit()
                    outcome
                }
            }
        }
        val receipt = JSONObject().put("id", command.id).put("result", result).put("state", state(context).json())
        if (WatchTestHooks.enabled) WatchTestHooks.delegate?.record(command, receipt)
        return receipt
    }

    private suspend fun execute(context: Context, command: WatchCommand): String = withContext(Dispatchers.Main) {
        when (command.action) {
            WatchAction.contactReply -> {
                if (WatchTestHooks.enabled) return@withContext WatchTestHooks.delegate?.contactReply(command) ?: "noPeer"
                WatchContactMessaging.send(context, command)
            }
            WatchAction.status -> "accepted"
            WatchAction.startSOS -> {
                if (GattSOSServerService.isDeclared.value) return@withContext "accepted"
                if (WatchTestHooks.enabled && WatchTestHooks.delegate?.productionRecovery() != true) {
                    context.startService(Intent(context, GattSOSServerService::class.java)
                        .putExtra(GattSOSServerService.EXTRA_USER_DECLARED, true))
                    return@withContext if (withTimeoutOrNull(3_000) {
                        combine(GattSOSServerService.isDeclared, GattSOSServerService.isRunning) { a, b -> a && b }.first { it }
                    } == true) "accepted" else "uncertain"
                }
                if (WatchRecoveryStore.get(context).state.value.unavailable) return@withContext "openPhone"
                if (state(context).recoveryRequired) {
                    if (command.emergencyID != state(context).emergencyID) return@withContext "stateChanged"
                    return@withContext requestPhoneStart(context, command)
                }
                if (command.emergencyID.isNotEmpty()) return@withContext "stateChanged"
                if (!hasSosPermissions(context)) return@withContext "openPhone"
                // The service includes location. A background receiver needs background location;
                // otherwise use a user-visible notification to enter the foreground legally.
                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    return@withContext requestPhoneStart(context, command)
                }
                try {
                    ContextCompat.startForegroundService(context, Intent(context, GattSOSServerService::class.java)
                        .putExtra(GattSOSServerService.EXTRA_USER_DECLARED, true))
                } catch (_: Exception) { return@withContext requestPhoneStart(context, command) }
                val activated = withTimeoutOrNull(3_000) {
                    combine(GattSOSServerService.isDeclared, GattSOSServerService.isRunning) { a, b -> a && b }.first { it }
                } == true
                if (activated) "accepted" else requestPhoneStart(context, command)
            }
            WatchAction.stopSOS -> {
                if ((!state(context).sosActive && !state(context).recoveryRequired) || state(context).emergencyID != command.emergencyID) return@withContext "stateChanged"
                context.getSharedPreferences("watch_approval", Context.MODE_PRIVATE).edit().clear().commit()
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                GattSOSServerService.stopBroadcast(context)
                val stopped = withTimeoutOrNull(2_000) { GattSOSServerService.isRunning.first { !it } } != null
                if (stopped) "accepted" else "uncertain"
            }
            WatchAction.quickReply -> {
                val reply = command.reply ?: return@withContext "invalid"
                if (WatchTestHooks.enabled) return@withContext requireNotNull(WatchTestHooks.delegate).reply(command)
                val peer = GattSOSServerService.watchRescuePeer()?.takeIf { it.sessionCode == command.peerID } ?: return@withContext "noPeer"
                val text = if (Locale.getDefault().language == "tr") reply.turkish else reply.english
                WatchReplyChat.send(context, command.id, peer.sessionCode, text) {
                    GattSOSServerService.sendWatchReply(peer.sessionCode, command.id, text)
                }
            }
        }
    }

    private fun hasSosPermissions(context: Context): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) permissions += listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPhoneStart(context: Context, command: WatchCommand): String {
        if (WatchTestHooks.enabled && WatchTestHooks.delegate?.productionRecovery() == true) {
            return requireNotNull(WatchTestHooks.delegate).requestRecoveryApproval(command)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "openPhone"
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.watch_sos_channel), NotificationManager.IMPORTANCE_HIGH))
        // Only this private Activity receives the confirmed, expiring watch command.
        val intent = Intent(context, WatchSosApprovalActivity::class.java).putExtra("command", command.encode())
        val pending = PendingIntent.getActivity(context, 7304, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSharedPreferences("watch_approval", Context.MODE_PRIVATE).edit().putString("id", command.id).commit()
        manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.watch_sos_notification_title)).setContentText(context.getString(R.string.watch_sos_notification_body))
            .setContentIntent(pending).setAutoCancel(true).setTimeoutAfter((command.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)).build())
        return "phoneConfirmation"
    }

    suspend fun approveOnPhone(context: Context, command: WatchCommand): String = mutex.withLock {
        val prefs = context.getSharedPreferences("watch_approval", Context.MODE_PRIVATE)
        if (prefs.getString("id", null) != command.id || command.action != WatchAction.startSOS || !command.valid(System.currentTimeMillis())) return@withLock "invalid"
        val savedId = WatchRecoveryStore.get(context).state.value.startedAt?.toString()
        if (savedId != null && command.emergencyID != savedId) return@withLock "stateChanged"
        if (savedId == null && command.emergencyID.isNotEmpty()) return@withLock "stateChanged"
        prefs.edit().remove("id").commit()
        if (!hasSosPermissions(context)) return@withLock "openPhone"
        withContext(Dispatchers.Main) {
            ContextCompat.startForegroundService(context, Intent(context, GattSOSServerService::class.java)
                .putExtra(GattSOSServerService.EXTRA_USER_DECLARED, true)
                .putExtra(GattSOSServerService.EXTRA_RESUME_SESSION_ID, savedId))
        }
        if (withTimeoutOrNull(3_000) {
            combine(GattSOSServerService.isDeclared, GattSOSServerService.isRunning) { declared, running -> declared && running }.first { it }
        } == true) "accepted" else "uncertain"
    }
}

internal suspend fun <T> Task<T>.awaitWatch(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
