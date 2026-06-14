package com.auralis.crisisconnect.service.gattmesh

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Optional Wi-Fi Aware "fast lane" for authority-mesh media blobs (Phase 4B).
 *
 * Dual-lane model: blobs always ride the BLE mesh (works on every device); on devices/pairs that
 * support Wi-Fi Aware, the same encrypted blob is also pushed over a Wi-Fi Aware TCP socket and
 * typically lands in well under a second. Receivers dedupe by blob id, so whichever lane arrives
 * first wins and the other copy is dropped.
 *
 * Security: the datapath PSK is derived from the authority group key, so unprovisioned devices
 * cannot even establish the L2 datapath — and the payload itself stays group-key AES-GCM
 * encrypted end-to-end exactly like the BLE lane (same INIT envelope + cipher).
 *
 * Wire frame per blob (one TCP stream carries many frames):
 * [u32 MAGIC][u32 initLen][init packet JSON (same as BLE IMAGE_INIT)][u32 cipherLen][cipher].
 */
@SuppressLint("NewApi")
internal class AuthorityMeshAwareAccelerator(
    context: Context,
    private val groupKeyProvider: () -> ByteArray?,
    private val onBlobReceived: (initPacketPayload: ByteArray, cipher: ByteArray) -> Unit,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var started = false
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var serverSocket: ServerSocket? = null
    private var messageIdCounter = 1

    private val links = CopyOnWriteArrayList<AwareLink>()
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val respondedPeerIds = mutableSetOf<Int>()
    private val initiatedPeerIds = mutableSetOf<Int>()

    fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            return false
        }
        val manager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        return manager?.isAvailable == true
    }

    fun hasFastPeers(): Boolean = links.isNotEmpty()

    fun start() {
        synchronized(lock) {
            if (started) {
                return
            }
            started = true
        }
        if (!isSupported() || !hasAwarePermissions() || groupKeyProvider() == null) {
            synchronized(lock) { started = false }
            return
        }
        val manager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
            ?: run {
                synchronized(lock) { started = false }
                return
            }
        runCatching {
            manager.attach(
                object : AttachCallback() {
                    override fun onAttached(session: WifiAwareSession) {
                        synchronized(lock) {
                            if (!started) {
                                runCatching { session.close() }
                                return
                            }
                            awareSession = session
                        }
                        startServerSocket()
                        startPublish(session)
                        startSubscribe(session)
                    }

                    override fun onAttachFailed() {
                        Log.w(TAG, "Wi-Fi Aware attach failed; accelerator stays off")
                        synchronized(lock) { started = false }
                    }
                },
                mainHandler
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to attach Wi-Fi Aware accelerator", throwable)
            synchronized(lock) { started = false }
        }
    }

    fun stop() {
        val callbacks: List<ConnectivityManager.NetworkCallback>
        synchronized(lock) {
            if (!started) {
                return
            }
            started = false
            callbacks = networkCallbacks.toList()
            networkCallbacks.clear()
            respondedPeerIds.clear()
            initiatedPeerIds.clear()
        }
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        callbacks.forEach { callback ->
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        links.forEach { it.close() }
        links.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { publishSession?.close() }
        publishSession = null
        runCatching { subscribeSession?.close() }
        subscribeSession = null
        runCatching { awareSession?.close() }
        awareSession = null
    }

    /** Pushes one blob to every open fast-lane socket. Fire-and-forget; BLE remains the baseline. */
    fun offerBlob(initPacketPayload: ByteArray, cipher: ByteArray) {
        if (links.isEmpty()) {
            return
        }
        if (initPacketPayload.size > MAX_INIT_BYTES || cipher.size > MAX_CIPHER_BYTES) {
            return
        }
        links.forEach { link ->
            scope.launch {
                link.writeFrame(initPacketPayload, cipher)
            }
        }
    }

    private fun startServerSocket() {
        runCatching {
            val server = ServerSocket(0)
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
            Log.w(TAG, "Unable to open accelerator server socket", throwable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPublish(session: WifiAwareSession) {
        if (!hasAwarePermissions()) {
            return
        }
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        runCatching {
            session.publish(
                config,
                object : DiscoverySessionCallback() {
                    override fun onPublishStarted(publish: PublishDiscoverySession) {
                        synchronized(lock) { publishSession = publish }
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        // A subscriber said hello; open the responder side of the datapath for it.
                        respondToPeer(peerHandle)
                    }
                },
                mainHandler
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Accelerator publish failed", throwable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSubscribe(session: WifiAwareSession) {
        if (!hasAwarePermissions()) {
            return
        }
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        runCatching {
            session.subscribe(
                config,
                object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(subscribe: SubscribeDiscoverySession) {
                        synchronized(lock) { subscribeSession = subscribe }
                    }

                    override fun onServiceDiscovered(
                        peerHandle: PeerHandle,
                        serviceSpecificInfo: ByteArray?,
                        matchFilter: MutableList<ByteArray>?
                    ) {
                        initiateToPeer(peerHandle)
                    }
                },
                mainHandler
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Accelerator subscribe failed", throwable)
        }
    }

    private fun respondToPeer(peerHandle: PeerHandle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val publish = synchronized(lock) { publishSession } ?: return
        val port = serverSocket?.localPort?.takeIf { it > 0 } ?: return
        val passphrase = datapathPassphrase() ?: return
        synchronized(lock) {
            if (!respondedPeerIds.add(peerHandle.hashCode())) {
                return
            }
        }
        val specifier = runCatching {
            WifiAwareNetworkSpecifier.Builder(publish, peerHandle)
                .setPskPassphrase(passphrase)
                .setPort(port)
                .build()
        }.getOrNull() ?: return
        requestDatapath(specifier, connectOnAvailable = false)
    }

    private fun initiateToPeer(peerHandle: PeerHandle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val subscribe = synchronized(lock) { subscribeSession } ?: return
        val passphrase = datapathPassphrase() ?: return
        synchronized(lock) {
            if (!initiatedPeerIds.add(peerHandle.hashCode())) {
                return
            }
        }
        // Say hello so the publisher learns our PeerHandle and opens its responder side.
        runCatching {
            subscribe.sendMessage(peerHandle, messageIdCounter++, HELLO_MESSAGE)
        }
        val specifier = runCatching {
            WifiAwareNetworkSpecifier.Builder(subscribe, peerHandle)
                .setPskPassphrase(passphrase)
                .build()
        }.getOrNull() ?: return
        requestDatapath(specifier, connectOnAvailable = true)
    }

    private fun requestDatapath(specifier: WifiAwareNetworkSpecifier, connectOnAvailable: Boolean) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!connectOnAvailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return
                }
                val info = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr ?: return
                val port = info.port.takeIf { it > 0 } ?: return
                scope.launch {
                    runCatching {
                        val socket = network.socketFactory.createSocket(address, port)
                        attachLink(socket)
                    }.onFailure { throwable ->
                        Log.w(TAG, "Unable to open accelerator socket", throwable)
                    }
                }
            }
        }
        synchronized(lock) {
            if (!started) {
                return
            }
            networkCallbacks.add(callback)
        }
        runCatching {
            cm.requestNetwork(request, callback)
        }.onFailure { throwable ->
            Log.w(TAG, "Accelerator datapath request failed", throwable)
            synchronized(lock) { networkCallbacks.remove(callback) }
        }
    }

    private fun attachLink(socket: Socket) {
        val link = runCatching {
            socket.tcpNoDelay = true
            AwareLink(socket)
        }.getOrNull() ?: return
        links.add(link)
        Log.i(TAG, "Accelerator fast lane open (links=${links.size})")
        scope.launch {
            link.readLoop()
            links.remove(link)
            link.close()
            Log.i(TAG, "Accelerator fast lane closed (links=${links.size})")
        }
    }

    /** 8–63 char PSK derived from the group key; identical on every provisioned authority. */
    private fun datapathPassphrase(): String? {
        val groupKey = groupKeyProvider() ?: return null
        val digest = runCatching {
            MessageDigest.getInstance("SHA-256").digest(groupKey + PSK_SALT.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }.take(32)
    }

    private fun hasAwarePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
    }

    private inner class AwareLink(private val socket: Socket) {
        private val output = DataOutputStream(socket.getOutputStream().buffered())
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val writeLock = Any()

        fun writeFrame(initPayload: ByteArray, cipher: ByteArray) {
            runCatching {
                synchronized(writeLock) {
                    output.writeInt(FRAME_MAGIC)
                    output.writeInt(initPayload.size)
                    output.write(initPayload)
                    output.writeInt(cipher.size)
                    output.write(cipher)
                    output.flush()
                }
            }.onFailure {
                close()
                links.remove(this)
            }
        }

        fun readLoop() {
            runCatching {
                while (true) {
                    val magic = input.readInt()
                    if (magic != FRAME_MAGIC) {
                        return
                    }
                    val initLength = input.readInt()
                    if (initLength !in 1..MAX_INIT_BYTES) {
                        return
                    }
                    val initPayload = ByteArray(initLength)
                    input.readFully(initPayload)
                    val cipherLength = input.readInt()
                    if (cipherLength !in 1..MAX_CIPHER_BYTES) {
                        return
                    }
                    val cipher = ByteArray(cipherLength)
                    input.readFully(cipher)
                    onBlobReceived(initPayload, cipher)
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        private const val TAG = "AuthorityMeshAware"
        private const val SERVICE_NAME = "ccauthblobv1"
        private const val FRAME_MAGIC = 0x43434142 // "CCAB"
        private const val MAX_INIT_BYTES = 8_192
        private const val MAX_CIPHER_BYTES = 400_064
        private const val PSK_SALT = "cc-authmesh-aware-psk-v1"
        private val HELLO_MESSAGE = "cc-authblob-hello".toByteArray(Charsets.UTF_8)
    }
}
