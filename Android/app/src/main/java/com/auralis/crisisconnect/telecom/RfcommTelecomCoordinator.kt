package com.auralis.crisisconnect.telecom

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.auralis.crisisconnect.service.CallAudioRoute
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.orderedCallAudioRoutes
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object RfcommTelecomCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val managedCalls = ConcurrentHashMap<String, ManagedCall>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var callsManager: CallsManager? = null

    @Volatile
    private var registered = false

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        appContext = context.applicationContext
        if (callsManager == null) {
            callsManager = runCatching { CallsManager(context.applicationContext) }
                .onFailure { Log.w(TAG, "Unable to create CallsManager", it) }
                .getOrNull()
        }
        registerAppIfNeeded()
    }

    fun onIncomingCall(sessionCode: String, callId: String, displayName: String) {
        addCallIfNeeded(
            sessionCode = sessionCode,
            callId = callId,
            displayName = displayName,
            direction = CallAttributesCompat.DIRECTION_INCOMING
        )
    }

    fun onOutgoingCall(sessionCode: String, callId: String, displayName: String) {
        addCallIfNeeded(
            sessionCode = sessionCode,
            callId = callId,
            displayName = displayName,
            direction = CallAttributesCompat.DIRECTION_OUTGOING
        )
    }

    fun prefersSystemIncomingUi(): Boolean {
        return registered && isSupported()
    }

    fun answerIncomingCall(sessionCode: String, callId: String, callback: (Boolean) -> Unit) {
        val managedCall = managedCalls[sessionCode]
        if (!isSupported() || managedCall == null || managedCall.callId != callId) {
            callback(true)
            return
        }
        scope.launch {
            val callScope = awaitScope(managedCall)
            if (callScope == null) {
                callback(false)
                return@launch
            }
            val result = runCatching {
                callScope.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
            }.getOrElse {
                Log.w(TAG, "Telecom answer failed for $sessionCode/$callId", it)
                callback(false)
                return@launch
            }
            val success = result is CallControlResult.Success
            if (success) {
                managedCall.lifecycle.set(TelecomLifecycle.ACTIVE)
            }
            callback(success)
        }
    }

    fun onCallActive(sessionCode: String, callId: String) {
        val managedCall = managedCalls[sessionCode]
        if (!isSupported() || managedCall == null || managedCall.callId != callId) {
            return
        }
        if (managedCall.direction != CallAttributesCompat.DIRECTION_OUTGOING) {
            managedCall.lifecycle.set(TelecomLifecycle.ACTIVE)
            return
        }
        scope.launch {
            val callScope = awaitScope(managedCall) ?: return@launch
            when (runCatching { callScope.setActive() }.getOrNull()) {
                is CallControlResult.Success -> managedCall.lifecycle.set(TelecomLifecycle.ACTIVE)
                is CallControlResult.Error -> Log.w(TAG, "Telecom could not activate outgoing call $sessionCode/$callId")
                null -> Log.w(TAG, "Telecom activation failed for $sessionCode/$callId")
            }
        }
    }

    fun onCallEnded(sessionCode: String, callId: String, cause: DisconnectCause) {
        val managedCall = managedCalls[sessionCode]
        if (!isSupported() || managedCall == null || managedCall.callId != callId) {
            return
        }
        val lifecycle = managedCall.lifecycle.get()
        if (lifecycle == TelecomLifecycle.ENDING_FROM_TELECOM || lifecycle == TelecomLifecycle.DISCONNECTED) {
            return
        }
        managedCall.lifecycle.set(TelecomLifecycle.ENDING_LOCALLY)
        scope.launch {
            val callScope = awaitScope(managedCall)
            if (callScope == null) {
                managedCall.job.cancel()
                managedCalls.remove(sessionCode, managedCall)
                return@launch
            }
            runCatching { callScope.disconnect(cause) }
                .onFailure { Log.w(TAG, "Telecom disconnect failed for $sessionCode/$callId", it) }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun requestSpeakerRoute(sessionCode: String, enabled: Boolean) {
        val managedCall = managedCalls[sessionCode] ?: return
        if (!isSupported()) {
            return
        }
        scope.launch {
            val callScope = awaitScope(managedCall) ?: return@launch
            val current = managedCall.currentEndpoint.get()
            if ((current?.type == CallEndpointCompat.TYPE_SPEAKER) == enabled) {
                return@launch
            }
            val endpoint = selectSpeakerEndpoint(managedCall, enabled) ?: return@launch
            runCatching { callScope.requestEndpointChange(endpoint) }
                .onFailure { Log.w(TAG, "Endpoint change failed for $sessionCode/${managedCall.callId}", it) }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun requestAudioRoute(sessionCode: String, route: CallAudioRoute) {
        val managedCall = managedCalls[sessionCode] ?: return
        if (!isSupported()) {
            return
        }
        scope.launch {
            val callScope = awaitScope(managedCall) ?: return@launch
            val currentRoute = managedCall.currentEndpoint.get()?.toCallAudioRoute()
            if (currentRoute == route) {
                return@launch
            }
            val endpoint = selectEndpoint(managedCall, route) ?: return@launch
            runCatching { callScope.requestEndpointChange(endpoint) }
                .onFailure { Log.w(TAG, "Endpoint change failed for $sessionCode/${managedCall.callId}", it) }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun addCallIfNeeded(
        sessionCode: String,
        callId: String,
        displayName: String,
        direction: Int
    ) {
        if (!isSupported()) {
            return
        }
        val existing = managedCalls[sessionCode]
        if (existing != null) {
            if (existing.callId == callId) {
                return
            }
            onCallEnded(sessionCode, existing.callId, DisconnectCause(DisconnectCause.LOCAL))
        }
        registerAppIfNeeded()
        val manager = callsManager ?: return
        val managedCall = ManagedCall(
            sessionCode = sessionCode,
            callId = callId,
            direction = direction
        )
        val previous = managedCalls.putIfAbsent(sessionCode, managedCall)
        if (previous != null) {
            return
        }
        managedCall.job = scope.launch {
            val attributes = CallAttributesCompat(
                displayName = displayName,
                address = buildCallAddress(sessionCode),
                direction = direction,
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = 0
            )
            managedCall.lifecycle.set(
                if (direction == CallAttributesCompat.DIRECTION_INCOMING) {
                    TelecomLifecycle.RINGING
                } else {
                    TelecomLifecycle.CONNECTING
                }
            )
            runCatching {
                manager.addCall(
                    callAttributes = attributes,
                    onAnswer = {
                        managedCall.lifecycle.set(TelecomLifecycle.ACTIVE)
                        dispatchServiceIntent(
                            sessionCode = sessionCode,
                            callId = callId,
                            action = RfcommForegroundService.ACTION_ACCEPT_CALL,
                            flag = RfcommForegroundService.CALL_ANSWERED_BY_TELECOM_FLAG
                        )
                    },
                    onDisconnect = { disconnectCause ->
                        val previousLifecycle =
                            managedCall.lifecycle.getAndSet(TelecomLifecycle.ENDING_FROM_TELECOM)
                        dispatchDisconnectIntent(managedCall, disconnectCause, previousLifecycle)
                    },
                    onSetActive = {
                        managedCall.lifecycle.set(TelecomLifecycle.ACTIVE)
                    },
                    onSetInactive = {
                        Log.i(TAG, "Ignoring Telecom inactive request for $sessionCode/$callId")
                    }
                ) {
                    managedCall.scope.complete(this)
                    launch {
                        availableEndpoints.collectLatest { endpoints ->
                            managedCall.availableEndpoints.set(endpoints)
                            dispatchAudioRouteState(managedCall)
                        }
                    }
                    launch {
                        currentCallEndpoint.collectLatest { endpoint ->
                            managedCall.currentEndpoint.set(endpoint)
                            dispatchAudioRouteState(managedCall)
                        }
                    }
                    launch {
                        isMuted.collectLatest { muted ->
                            val previousMuted = managedCall.muted.getAndSet(muted)
                            if (previousMuted == muted) {
                                return@collectLatest
                            }
                            dispatchServiceIntent(
                                sessionCode = sessionCode,
                                callId = callId,
                                action = if (muted) {
                                    RfcommForegroundService.ACTION_MUTE
                                } else {
                                    RfcommForegroundService.ACTION_UNMUTE
                                }
                            )
                        }
                    }
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Telecom addCall failed for $sessionCode/$callId", throwable)
            }
            managedCall.lifecycle.set(TelecomLifecycle.DISCONNECTED)
            managedCalls.remove(sessionCode, managedCall)
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun registerAppIfNeeded() {
        if (registered || !isSupported()) {
            return
        }
        val manager = callsManager ?: return
        runCatching {
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            registered = true
        }.onFailure { throwable ->
            Log.w(TAG, "registerAppWithTelecom failed", throwable)
        }
    }

    private fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            appContext != null &&
            callsManager != null
    }

    private suspend fun awaitScope(managedCall: ManagedCall): CallControlScope? {
        return withTimeoutOrNull(SCOPE_TIMEOUT_MS) {
            managedCall.scope.await()
        }
    }

    private fun buildCallAddress(sessionCode: String): Uri {
        return Uri.parse("sip:${Uri.encode(sessionCode)}@crisisconnect.local")
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun dispatchAudioRouteState(managedCall: ManagedCall) {
        val currentRoute = managedCall.currentEndpoint.get()?.toCallAudioRoute()
        val availableRoutes = orderedCallAudioRoutes(
            managedCall.availableEndpoints.get().map { it.toCallAudioRoute() } + listOfNotNull(currentRoute)
        )
        dispatchServiceIntent(
            sessionCode = managedCall.sessionCode,
            callId = managedCall.callId,
            action = RfcommForegroundService.ACTION_SYNC_CALL_AUDIO_ROUTE
        ) {
            currentRoute?.let {
                putExtra(RfcommForegroundService.EXTRA_CALL_AUDIO_ROUTE, it.name)
            }
            putStringArrayListExtra(
                RfcommForegroundService.EXTRA_CALL_AVAILABLE_AUDIO_ROUTES,
                ArrayList(availableRoutes.map { it.name })
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun selectEndpoint(
        managedCall: ManagedCall,
        route: CallAudioRoute
    ): CallEndpointCompat? {
        val endpoints = managedCall.availableEndpoints.get()
        if (endpoints.isEmpty()) {
            return null
        }
        return when (route) {
            CallAudioRoute.Speaker -> {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
            }

            CallAudioRoute.Earpiece -> {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
                    ?: endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                    ?: endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
                    ?: endpoints.firstOrNull { it.type != CallEndpointCompat.TYPE_SPEAKER }
            }

            CallAudioRoute.Bluetooth -> {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
            }

            CallAudioRoute.WiredHeadset -> {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
            }

            CallAudioRoute.Streaming -> {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_STREAMING }
            }

            CallAudioRoute.Unknown -> null
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun selectSpeakerEndpoint(
        managedCall: ManagedCall,
        speakerEnabled: Boolean
    ): CallEndpointCompat? {
        val endpoints = managedCall.availableEndpoints.get()
        if (endpoints.isEmpty()) {
            return null
        }
        if (speakerEnabled) {
            return endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        }
        return endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
            ?: endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
            ?: endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
            ?: endpoints.firstOrNull { it.type != CallEndpointCompat.TYPE_SPEAKER }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun CallEndpointCompat.toCallAudioRoute(): CallAudioRoute {
        return when (type) {
            CallEndpointCompat.TYPE_EARPIECE -> CallAudioRoute.Earpiece
            CallEndpointCompat.TYPE_SPEAKER -> CallAudioRoute.Speaker
            CallEndpointCompat.TYPE_BLUETOOTH -> CallAudioRoute.Bluetooth
            CallEndpointCompat.TYPE_WIRED_HEADSET -> CallAudioRoute.WiredHeadset
            CallEndpointCompat.TYPE_STREAMING -> CallAudioRoute.Streaming
            else -> CallAudioRoute.Unknown
        }
    }

    private fun dispatchDisconnectIntent(
        managedCall: ManagedCall,
        disconnectCause: DisconnectCause,
        previousLifecycle: TelecomLifecycle
    ) {
        val action = if (
            managedCall.direction == CallAttributesCompat.DIRECTION_INCOMING &&
            previousLifecycle != TelecomLifecycle.ACTIVE
        ) {
            RfcommForegroundService.ACTION_REJECT_CALL
        } else {
            RfcommForegroundService.ACTION_END_CALL
        }
        dispatchServiceIntent(
            sessionCode = managedCall.sessionCode,
            callId = managedCall.callId,
            action = action
        ) {
            putExtra(RfcommForegroundService.EXTRA_CALL_FLAG, disconnectCause.code.toString())
        }
    }

    private fun dispatchServiceIntent(
        sessionCode: String,
        callId: String,
        action: String,
        flag: String? = null,
        extras: Intent.() -> Unit = {}
    ) {
        val context = appContext ?: return
        val intent = Intent(context, RfcommForegroundService::class.java).apply {
            this.action = action
            putExtra(RfcommForegroundService.EXTRA_SESSION_CODE, sessionCode)
            putExtra(RfcommForegroundService.EXTRA_CALL_ID, callId)
            if (!flag.isNullOrBlank()) {
                putExtra(RfcommForegroundService.EXTRA_CALL_FLAG, flag)
            }
            extras()
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private data class ManagedCall(
        val sessionCode: String,
        val callId: String,
        val direction: Int,
        val scope: CompletableDeferred<CallControlScope> = CompletableDeferred(),
        val lifecycle: AtomicReference<TelecomLifecycle> = AtomicReference(TelecomLifecycle.NEW),
        val availableEndpoints: AtomicReference<List<CallEndpointCompat>> = AtomicReference(emptyList()),
        val currentEndpoint: AtomicReference<CallEndpointCompat?> = AtomicReference(null),
        val muted: AtomicReference<Boolean> = AtomicReference(false)
    ) {
        @Volatile
        var job: kotlinx.coroutines.Job = kotlinx.coroutines.Job()
    }

    private enum class TelecomLifecycle {
        NEW,
        RINGING,
        CONNECTING,
        ACTIVE,
        ENDING_LOCALLY,
        ENDING_FROM_TELECOM,
        DISCONNECTED
    }

    private const val TAG = "RfcommTelecom"
    private const val SCOPE_TIMEOUT_MS = 4_000L
}
