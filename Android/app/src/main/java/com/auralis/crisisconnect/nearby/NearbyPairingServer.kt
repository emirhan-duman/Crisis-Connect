package com.auralis.crisisconnect.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.getContactByPeerUid
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.messaging.MessagingIdentity
import com.auralis.crisisconnect.service.GattSOSServerService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts the SPAKE2 nearby-pairing GATT service as the RESPONDER (uses our OWN phone number as the
 * password). The connector proves it knows our number only by completing the handshake — a wrong
 * number fails at key confirmation and reveals nothing, and no number is ever broadcast. Even after
 * a valid handshake we do NOT auto-add: the peer is parked PENDING and an Accept/Decline
 * notification is raised; the contact (and the reveal of our identity) happens ONLY on Accept.
 *
 * GATT shape (write REQUEST → read RESPONSE, twice): write msg1 → read msg2 (pB,cB); write msg3
 * (cA + encrypted initiator identity) → read msg4 (PENDING until consent, then OK + our identity).
 *
 * ⚠️ UNTESTED on real hardware — validate the two-device GATT + approval flow. Requires a GATT
 * server (GattSOSServerService) running to host the service (Faz N4 lifecycle).
 */
object NearbyPairingServer : GattSOSServerService.SharedGattDelegate {
    private const val TAG = "NearbyPairingServer"
    private const val CHANNEL_ID = "nearby_pairing"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Session(val responder: NearbySpakePairing.Responder) {
        @Volatile var peer: NearbySpakePairing.NearbyIdentity? = null
    }

    private var appContext: Context? = null
    @Volatile private var ownDisplayName: String = ""
    private val sessions = ConcurrentHashMap<String, Session>()             // address -> handshake state
    private val responseByDevice = ConcurrentHashMap<String, ByteArray>()   // address -> current READ payload
    @Volatile private var registered = false
    // Fallback host: the shared GATT server (GattSOSServerService) only runs during SOS / BLE-chat,
    // but nearby-add must answer while the app just sits opted-in. When the shared server isn't up,
    // the pairing service is hosted on this lightweight process-owned GATT server instead (no extra
    // foreground service needed — the Rfcomm FGS already keeps the process alive).
    @Volatile private var ownGattServer: BluetoothGattServer? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            ownDisplayName = runCatching { getSavedUserName(context).first().trim() }.getOrNull().orEmpty()
        }
        if (!registered) {
            registered = GattSOSServerService.registerSharedGattDelegate(this)
        }
        if (registered) {
            Log.i(TAG, "pairing GATT service registered on shared server")
            closeOwnGattServer()
        } else {
            startOwnGattServer(context.applicationContext)
        }
    }

    fun stop() {
        if (registered) {
            runCatching { GattSOSServerService.unregisterSharedGattDelegate(NearbySpakePairing.SERVICE_UUID) }
            registered = false
        }
        closeOwnGattServer()
        sessions.clear()
        responseByDevice.clear()
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    private fun startOwnGattServer(context: Context) {
        if (ownGattServer != null) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        if (manager.adapter?.isEnabled != true) {
            Log.w(TAG, "pairing GATT service not hosted: Bluetooth off")
            return
        }
        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                this@NearbyPairingServer.onConnectionStateChange(device, status, newState)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val status = this@NearbyPairingServer.onCharacteristicWriteRequest(
                    device, characteristic, preparedWrite, responseNeeded, offset, value
                ) ?: BluetoothGatt.GATT_FAILURE
                if (responseNeeded) {
                    runCatching { ownGattServer?.sendResponse(device, requestId, status, offset, null) }
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                val result = this@NearbyPairingServer.onCharacteristicReadRequest(device, offset, characteristic)
                runCatching {
                    ownGattServer?.sendResponse(
                        device, requestId, result?.status ?: BluetoothGatt.GATT_FAILURE, offset, result?.value
                    )
                }
            }
        }
        val server = runCatching { manager.openGattServer(context, callback) }.getOrNull()
        if (server == null) {
            Log.w(TAG, "pairing GATT service not hosted: openGattServer failed")
            return
        }
        val added = runCatching { server.addService(createService()) }.getOrDefault(false)
        if (!added) {
            Log.w(TAG, "pairing GATT service not hosted: addService failed")
            runCatching { server.close() }
            return
        }
        ownGattServer = server
        Log.i(TAG, "pairing GATT service hosted on own server")
    }

    @SuppressLint("MissingPermission")
    private fun closeOwnGattServer() {
        ownGattServer?.let { runCatching { it.close() } }
        ownGattServer = null
    }

    override val serviceUuid: UUID = NearbySpakePairing.SERVICE_UUID

    override fun createService(): BluetoothGattService {
        val service = BluetoothGattService(NearbySpakePairing.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                NearbySpakePairing.REQUEST_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                NearbySpakePairing.RESPONSE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        return service
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ): Int? {
        if (characteristic.uuid != NearbySpakePairing.REQUEST_UUID) return null
        val bytes = value ?: return BluetoothGatt.GATT_FAILURE
        val address = device.address
        val existing = sessions[address]

        if (existing == null) {
            // First write = SPAKE2 message 1. We answer with message 2 (pB, cB).
            val ownPhone = NearbyPairingSupport.ownPhoneE164()
            if (ownPhone == null) {
                responseByDevice[address] = NearbySpakePairing.rejectMessage()
                return BluetoothGatt.GATT_SUCCESS
            }
            val me = NearbySpakePairing.NearbyIdentity(
                uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
                publicKeyBase64 = runCatching { MessagingIdentity(appContext ?: return BluetoothGatt.GATT_FAILURE).publicKeyBase64() }.getOrElse { "" },
                displayName = ownDisplayName
            )
            val responder = NearbySpakePairing.Responder(NearbySpakePairing.deriveW(ownPhone), me)
            val msg2 = runCatching { responder.onMessage1(bytes) }.getOrElse {
                Log.w(TAG, "Bad pairing message 1", it)
                return BluetoothGatt.GATT_FAILURE
            }
            sessions[address] = Session(responder)
            responseByDevice[address] = msg2
            return BluetoothGatt.GATT_SUCCESS
        }

        if (existing.peer == null) {
            // Second write = SPAKE2 message 3. Verify + decrypt the connector's identity, then park
            // PENDING and ask the user to approve before revealing ours / adding a contact.
            val peer = runCatching { existing.responder.onMessage3(bytes) }.getOrElse {
                Log.w(TAG, "Initiator confirmation failed (wrong number / MITM)", it)
                responseByDevice[address] = existing.responder.responseMessage(NearbySpakePairing.STATUS_REJECT)
                return BluetoothGatt.GATT_SUCCESS
            }
            existing.peer = peer
            // A connector whose handshake identity matches a contact we ALREADY saved is just
            // bootstrapping the offline BT route for that existing contact — the user made the
            // trust decision when they added them, so accept without interrupting. Anyone unknown
            // (or a known uid presenting a DIFFERENT key) still gets the explicit Accept/Decline
            // notification — the anti-harvesting guard is unchanged for strangers.
            if (isAlreadySavedContact(peer)) {
                Log.i(TAG, "auto-accepting pairing: connector matches an already-saved contact")
                accept(address)
                return BluetoothGatt.GATT_SUCCESS
            }
            responseByDevice[address] = existing.responder.responseMessage(NearbySpakePairing.STATUS_PENDING)
            postApprovalNotification(address, peer.displayName)
            return BluetoothGatt.GATT_SUCCESS
        }

        return BluetoothGatt.GATT_SUCCESS
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ): GattSOSServerService.SharedGattReadResult? {
        if (characteristic.uuid != NearbySpakePairing.RESPONSE_UUID) return null
        val response = responseByDevice[device.address]
            ?: return GattSOSServerService.SharedGattReadResult(BluetoothGatt.GATT_FAILURE)
        val slice = if (offset in 0..response.size) response.copyOfRange(offset, response.size) else ByteArray(0)
        return GattSOSServerService.SharedGattReadResult(BluetoothGatt.GATT_SUCCESS, slice)
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            sessions.remove(device.address)
            responseByDevice.remove(device.address)
        }
    }

    /**
     * True when [peer]'s SPAKE2-exchanged identity (uid AND identity key, both required) matches a
     * contact we already hold — i.e. this pairing only adds a Bluetooth route to a trust decision
     * the user already made by saving them.
     */
    private fun isAlreadySavedContact(peer: NearbySpakePairing.NearbyIdentity): Boolean {
        val ctx = appContext ?: return false
        if (peer.uid.isBlank() || peer.publicKeyBase64.isBlank()) return false
        val stored = runCatching { getContactByPeerUid(ctx, peer.uid) }.getOrNull() ?: return false
        return stored.peerPublicKey.trim() == peer.publicKeyBase64.trim()
    }

    /** User tapped "Accept": reveal our identity, save the contact (internet + offline BT capable). */
    fun accept(address: String) {
        val session = sessions[address] ?: return
        val peer = session.peer ?: return
        val ctx = appContext
        responseByDevice[address] = session.responder.responseMessage(NearbySpakePairing.STATUS_OK)
        if (ctx != null) {
            scope.launch {
                runCatching {
                    NearbyPairingSupport.savePairedContact(
                        context = ctx,
                        peer = peer,
                        contactKey = session.responder.contactKey(),
                        bluetoothAddress = address
                    )
                }.onFailure { Log.w(TAG, "Failed to save paired contact", it) }
            }
            runCatching { NotificationManagerCompat.from(ctx).cancel(address.hashCode()) }
        }
    }

    /** User tapped "Decline": reject the pairing. */
    fun decline(address: String) {
        val session = sessions[address]
        responseByDevice[address] =
            session?.responder?.responseMessage(NearbySpakePairing.STATUS_REJECT)
                ?: NearbySpakePairing.rejectMessage()
        appContext?.let { runCatching { NotificationManagerCompat.from(it).cancel(address.hashCode()) } }
    }

    @SuppressLint("MissingPermission")
    private fun postApprovalNotification(address: String, connectorName: String) {
        val ctx = appContext ?: return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.nearby_pairing_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val name = connectorName.ifBlank { ctx.getString(R.string.internet_message_unknown_sender) }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.nearby_pairing_request_title, name))
            .setContentText(ctx.getString(R.string.nearby_pairing_request_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(
                0,
                ctx.getString(R.string.nearby_pairing_accept),
                actionIntent(ctx, NearbyPairingActionReceiver.ACTION_ACCEPT, address)
            )
            .addAction(
                0,
                ctx.getString(R.string.nearby_pairing_decline),
                actionIntent(ctx, NearbyPairingActionReceiver.ACTION_DECLINE, address)
            )
            .build()
        if (!hasPostNotificationsPermission(ctx)) {
            Log.w(TAG, "Skipping nearby pairing approval notification: POST_NOTIFICATIONS not granted")
            return
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(address.hashCode(), notification) }
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun actionIntent(ctx: Context, action: String, address: String): PendingIntent {
        val intent = Intent(ctx, NearbyPairingActionReceiver::class.java)
            .setAction(action)
            .putExtra(NearbyPairingActionReceiver.EXTRA_ADDRESS, address)
        return PendingIntent.getBroadcast(
            ctx,
            (action + address).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
