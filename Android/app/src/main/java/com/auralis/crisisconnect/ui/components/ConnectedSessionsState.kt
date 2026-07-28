package com.auralis.crisisconnect.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.isScreenshotDemoModeEnabledSync
import com.auralis.crisisconnect.screens.Chat.ChatScreenshotDemoScenario
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.p2p.P2pGattChatManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The set of session codes with a live Bluetooth link right now, merged across all three offline
 * transports: RFCOMM classic (via the foreground service binder), P2P GATT client links, and BLE
 * server peers. Shared by the home list's connected pills and the authority thread's transport
 * badge so "connected" always means the same thing everywhere.
 */
@Composable
fun rememberConnectedSessions(): Set<String> {
    val context = LocalContext.current
    var connectedSessions by remember { mutableStateOf<Set<String>>(emptySet()) }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        val p2pGattChatManager = P2pGattChatManager.shared(appContext)
        val collectScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        var sessionsJob: Job? = null
        var bleClientSessionsJob: Job? = null
        var bleServerSessionsJob: Job? = null
        var rfcommSessions = emptySet<String>()
        var bleClientSessions = emptySet<String>()
        var bleServerSessions = emptySet<String>()
        fun updateConnectedSessions() {
            connectedSessions = rfcommSessions + bleClientSessions + bleServerSessions
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val boundService = (binder as? RfcommForegroundService.LocalBinder)?.getService() ?: return
                sessionsJob?.cancel()
                sessionsJob = collectScope.launch {
                    boundService.activeSessions.collectLatest { sessions ->
                        rfcommSessions = sessions
                        updateConnectedSessions()
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                sessionsJob?.cancel()
                sessionsJob = null
                rfcommSessions = emptySet()
                updateConnectedSessions()
            }
        }
        bleClientSessionsJob = collectScope.launch {
            p2pGattChatManager.connectedSessions.collectLatest { sessions ->
                bleClientSessions = sessions
                updateConnectedSessions()
            }
        }
        bleServerSessionsJob = collectScope.launch {
            BlePeerStore.peers.collectLatest { peers ->
                bleServerSessions = peers.values
                    .mapNotNull { peer ->
                        peer.sessionCode.trim().takeIf { it.isNotEmpty() }
                    }
                    .toSet()
                updateConnectedSessions()
            }
        }
        val bound = runCatching {
            appContext.bindService(
                Intent(appContext, RfcommForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)

        onDispose {
            sessionsJob?.cancel()
            bleClientSessionsJob?.cancel()
            bleServerSessionsJob?.cancel()
            collectScope.cancel()
            connectedSessions = emptySet()
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    // Debug screenshot demo: always report the scripted contact as connected
    // so the connected pill appears on its row in the Messages list. Gated on
    // BuildConfig.DEBUG via `isScreenshotDemoModeEnabledSync`; in release
    // builds this call returns `false` and the set is untouched.
    return if (isScreenshotDemoModeEnabledSync(context)) {
        connectedSessions + ChatScreenshotDemoScenario.DEMO_SESSION_CODE
    } else {
        connectedSessions
    }
}
