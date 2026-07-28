package com.auralis.crisisconnect.service.gattmesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Optional Wi-Fi Direct "fast lane" for authority-mesh media blobs — the broad-coverage sibling of
 * [AuthorityMeshAwareAccelerator]. Wi-Fi Aware support is fragmented; Wi-Fi Direct is near-universal
 * on Android, so this lane lights up the fast path on the many devices that lack Aware.
 *
 * Mutual exclusion: on most devices Wi-Fi Aware becomes unavailable while a Wi-Fi Direct group is up
 * (and vice versa), so this lane **defers to Aware** — if the device has working Wi-Fi Aware it stays
 * off and lets the Aware lane win. It only engages on Aware-less devices. BLE GATT is always the
 * baseline, so if this lane never forms, blobs still arrive over BLE (receivers dedupe by blob id).
 *
 * Topology (dialog-free on the host, one-time consent possible on clients):
 *  - All authority peers exchange a persisted random nodeId over Wi-Fi Direct DNS-SD service discovery.
 *  - The **lowest nodeId** becomes Group Owner: `createGroup()` with a `networkName`/`passphrase`
 *    deterministically derived from the authority group key (API 29+), and opens a [ServerSocket] on a
 *    fixed port. A Wi-Fi Direct GO is always reachable at 192.168.49.1.
 *  - The other peers join that derived SSID as Wi-Fi clients ([WifiNetworkSpecifier]) and connect a
 *    TCP socket to 192.168.49.1:[FIXED_PORT].
 *  - Both sides carry the identical [BlobLink] wire frame, so the receive + dedupe path is unchanged.
 *
 * Security: SSID + passphrase derive from the authority group key (same trust model as the Aware PSK),
 * so unprovisioned/civilian devices cannot derive the credentials or join the group; the payload stays
 * group-key AES-GCM encrypted end-to-end exactly like the BLE and Aware lanes.
 *
 * NOTE: This is a compiled, fail-soft first cut. Wi-Fi Direct/P2P behaviour varies across OEMs and
 * needs on-device bring-up (logcat tag [TAG]); nothing here can break the BLE baseline.
 */
internal class WifiDirectAccelerator(
    context: Context,
    private val groupKeyProvider: () -> ByteArray?,
    private val onBlobReceived: (initPacketPayload: ByteArray, cipher: ByteArray) -> Unit,
) : MeshAccelerator {

    override val laneId: String = "wifi-direct"

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var started = false
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private var isGroupOwner = false
    private var groupRequested = false
    private var clientJoinRequested = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val links = CopyOnWriteArrayList<BlobLink>()
    private val peerNodeIds = HashSet<String>()

    override fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return false
        }
        // Prefer Wi-Fi Aware where it exists: Aware coexists with internet and sets up faster, and the
        // two transports are mutually exclusive on most radios. This lane is the Aware-less fallback.
        if (awareIsUsable()) {
            return false
        }
        return (appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager) != null
    }

    override fun hasFastPeers(): Boolean = links.isNotEmpty()

    override fun start() {
        synchronized(lock) {
            if (started) {
                return
            }
            started = true
        }
        if (!isSupported() || !hasDirectPermissions() || groupKeyProvider() == null) {
            synchronized(lock) { started = false }
            return
        }
        val p2pManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: run {
                synchronized(lock) { started = false }
                return
            }
        val p2pChannel = runCatching {
            p2pManager.initialize(appContext, Looper.getMainLooper(), null)
        }.getOrNull() ?: run {
            synchronized(lock) { started = false }
            return
        }
        synchronized(lock) {
            manager = p2pManager
            channel = p2pChannel
        }
        registerReceiver()
        startServiceDiscovery(p2pManager, p2pChannel)
    }

    override fun stop() {
        val cb: ConnectivityManager.NetworkCallback?
        val rcv: BroadcastReceiver?
        val p2pManager: WifiP2pManager?
        val p2pChannel: WifiP2pManager.Channel?
        synchronized(lock) {
            if (!started) {
                return
            }
            started = false
            cb = networkCallback
            networkCallback = null
            rcv = receiver
            receiver = null
            p2pManager = manager
            p2pChannel = channel
            manager = null
            channel = null
            isGroupOwner = false
            groupRequested = false
            clientJoinRequested = false
            peerNodeIds.clear()
        }
        cb?.let { callback ->
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        rcv?.let { runCatching { appContext.unregisterReceiver(it) } }
        links.forEach { it.close() }
        links.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (p2pManager != null && p2pChannel != null) {
            runCatching { p2pManager.clearLocalServices(p2pChannel, null) }
            runCatching { p2pManager.clearServiceRequests(p2pChannel, null) }
            runCatching { p2pManager.removeGroup(p2pChannel, null) }
        }
    }

    /** Pushes one blob to every open fast-lane socket. Fire-and-forget; BLE remains the baseline. */
    override fun offerBlob(initPacketPayload: ByteArray, cipher: ByteArray) {
        if (links.isEmpty()) {
            return
        }
        if (initPacketPayload.size > BlobLink.MAX_INIT_BYTES || cipher.size > BlobLink.MAX_CIPHER_BYTES) {
            return
        }
        links.forEach { link ->
            scope.launch { link.writeFrame(initPacketPayload, cipher) }
        }
    }

    // --- Discovery + GO election -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(p2pManager: WifiP2pManager, p2pChannel: WifiP2pManager.Channel) {
        val record = mapOf(TXT_NODE_ID to localNodeId())
        val serviceInfo = runCatching {
            WifiP2pDnsSdServiceInfo.newInstance(INSTANCE_NAME, SERVICE_TYPE, record)
        }.getOrNull() ?: return

        p2pManager.setDnsSdResponseListeners(
            p2pChannel,
            { _, _, _ -> /* DNS-SD service available; the TXT listener carries the nodeId. */ },
            { _, txtRecord, _ ->
                val peerId = txtRecord[TXT_NODE_ID] ?: return@setDnsSdResponseListeners
                val changed = synchronized(lock) {
                    if (!started) false else peerNodeIds.add(peerId)
                }
                if (changed) {
                    mainHandler.postDelayed({ evaluateElection() }, ELECTION_DEBOUNCE_MS)
                }
            }
        )

        runCatching {
            p2pManager.addLocalService(p2pChannel, serviceInfo, null)
            p2pManager.addServiceRequest(p2pChannel, WifiP2pDnsSdServiceRequest.newInstance(), null)
            p2pManager.discoverServices(p2pChannel, null)
            // Some OEMs only surface DNS-SD records once peer discovery is also running.
            p2pManager.discoverPeers(p2pChannel, null)
        }.onFailure { throwable ->
            Log.w(TAG, "Wi-Fi Direct service discovery failed", throwable)
        }
    }

    private fun evaluateElection() {
        val self = localNodeId()
        val shouldHost = synchronized(lock) {
            if (!started || groupRequested || clientJoinRequested) {
                return
            }
            if (peerNodeIds.isEmpty()) {
                return
            }
            WifiDirectGroup.shouldHostGroup(self, peerNodeIds.toSet())
        }
        if (shouldHost) {
            hostGroup()
        } else {
            joinHostNetwork()
        }
    }

    @SuppressLint("MissingPermission")
    private fun hostGroup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val p2pManager: WifiP2pManager
        val p2pChannel: WifiP2pManager.Channel
        synchronized(lock) {
            if (!started || groupRequested) return
            p2pManager = manager ?: return
            p2pChannel = channel ?: return
            groupRequested = true
            isGroupOwner = true
        }
        val groupKey = groupKeyProvider() ?: run {
            synchronized(lock) { groupRequested = false; isGroupOwner = false }
            return
        }
        val networkName = WifiDirectGroup.networkName(groupKey)
        val passphrase = WifiDirectGroup.passphrase(groupKey)
        startServerSocket()
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .enablePersistentMode(false)
                .build()
        }.getOrNull() ?: return
        runCatching {
            p2pManager.createGroup(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct GO created ($networkName)")
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi Direct createGroup failed reason=$reason")
                    synchronized(lock) { groupRequested = false; isGroupOwner = false }
                }
            })
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to create Wi-Fi Direct group", throwable)
            synchronized(lock) { groupRequested = false; isGroupOwner = false }
        }
    }

    private fun joinHostNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        synchronized(lock) {
            if (!started || clientJoinRequested) return
            clientJoinRequested = true
        }
        val groupKey = groupKeyProvider() ?: run {
            synchronized(lock) { clientJoinRequested = false }
            return
        }
        val networkName = WifiDirectGroup.networkName(groupKey)
        val passphrase = WifiDirectGroup.passphrase(groupKey)
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val specifier = runCatching {
            WifiNetworkSpecifier.Builder()
                .setSsid(networkName)
                .setWpa2Passphrase(passphrase)
                .build()
        }.getOrNull() ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    runCatching {
                        val socket = network.socketFactory.createSocket(GROUP_OWNER_ADDRESS, FIXED_PORT)
                        attachLink(socket)
                    }.onFailure { throwable ->
                        Log.w(TAG, "Unable to open Wi-Fi Direct client socket", throwable)
                    }
                }
            }
        }
        synchronized(lock) {
            if (!started) return
            networkCallback = callback
        }
        runCatching {
            cm.requestNetwork(request, callback)
        }.onFailure { throwable ->
            Log.w(TAG, "Wi-Fi Direct client requestNetwork failed", throwable)
            synchronized(lock) { networkCallback = null; clientJoinRequested = false }
        }
    }

    private fun startServerSocket() {
        runCatching {
            val server = ServerSocket(FIXED_PORT)
            synchronized(lock) {
                if (!started) {
                    runCatching { server.close() }
                    return
                }
                serverSocket = server
            }
            scope.launch {
                while (true) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    attachLink(socket)
                }
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to open Wi-Fi Direct server socket", throwable)
        }
    }

    private fun attachLink(socket: Socket) {
        val link = runCatching {
            socket.tcpNoDelay = true
            BlobLink(socket, onBlobReceived)
        }.getOrNull() ?: return
        links.add(link)
        Log.i(TAG, "Wi-Fi Direct fast lane open (links=${links.size})")
        scope.launch {
            link.readLoop()
            links.remove(link)
            link.close()
            Log.i(TAG, "Wi-Fi Direct fast lane closed (links=${links.size})")
        }
    }

    // --- Receiver ----------------------------------------------------------------------------

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Connection/state changes are surfaced for logging + future link reconciliation; the
                // GO accept loop and client requestNetwork callback already drive socket setup.
                if (intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        Log.w(TAG, "Wi-Fi Direct disabled at system level")
                    }
                }
            }
        }
        synchronized(lock) { receiver = rcv }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                rcv,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    // --- Derivations + capability checks -----------------------------------------------------

    /** Persisted per-install random id used purely to deterministically elect the group owner. */
    private fun localNodeId(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_NODE_ID, null)?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString(separator = "") { "%02x".format(it) }
        prefs.edit().putString(KEY_NODE_ID, id).apply()
        return id
    }

    private fun awareIsUsable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            return false
        }
        val awareManager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        return awareManager?.isAvailable == true
    }

    private fun hasDirectPermissions(): Boolean {
        val fineOrNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
        return fineOrNearby
    }

    private companion object {
        private const val TAG = "AuthorityMeshDirect"
        private const val INSTANCE_NAME = "ccauthdirect"
        private const val SERVICE_TYPE = "_ccauthblob._tcp"
        private const val TXT_NODE_ID = "nid"
        private const val FIXED_PORT = 52395 // 0xCCAB
        private const val GROUP_OWNER_ADDRESS = "192.168.49.1"
        private const val ELECTION_DEBOUNCE_MS = 1_500L
        private const val PREFS_NAME = "cc_authmesh_direct"
        private const val KEY_NODE_ID = "node_id"
    }
}
