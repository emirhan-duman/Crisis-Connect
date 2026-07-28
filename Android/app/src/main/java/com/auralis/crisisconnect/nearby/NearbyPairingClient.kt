package com.auralis.crisisconnect.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Connector (initiator) side of the SPAKE2 nearby pairing. The user is LOOKING FOR a specific
 * contact, so we run SPAKE2 with w = deriveW(that contact's number) against a candidate device: if
 * the device is really that person (its own number → same w) the handshake completes and we exchange
 * messaging identities; otherwise it fails at key confirmation and we learn nothing (one guess per
 * handshake — the harvesting defense). No phone number is ever sent.
 *
 * GATT: write msg1 → read msg2 → write msg3 → read msg4 (polling while the peer's approval is PENDING).
 *
 * ⚠️ UNTESTED on real hardware — validate the two-device GATT flow. Uses the app's established
 * (deprecated-but-functional) single-write/single-read GATT calls; msgs fit inside MTU 512.
 */
object NearbyPairingClient {
    private const val TAG = "NearbyPairingClient"
    private const val TIMEOUT_MS = 65_000L // long enough for the other person to tap Accept
    private const val POLL_MS = 2_500L
    private const val REQUEST_MTU = 512

    sealed interface Result {
        data class Paired(val sessionCode: String, val displayName: String) : Result
        data object NotMatched : Result // wrong person / declined — nothing revealed
        data object Failed : Result     // transport error
    }

    private enum class Phase { AWAIT_MSG2, AWAIT_MSG4 }

    @SuppressLint("MissingPermission")
    suspend fun pair(
        context: Context,
        bluetoothAddress: String,
        candidatePhone: String,
        candidateName: String = ""
    ): Result = withContext(Dispatchers.IO) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return@withContext Result.Failed
        // Prefer the scan-time device object: the beacon uses a resolvable private address, and
        // getRemoteDevice(addressString) assumes a public one — connecting via it never completes.
        val device = NearbyContactScanner.deviceFor(bluetoothAddress)
            ?: runCatching { adapter.getRemoteDevice(bluetoothAddress) }.getOrNull()
            ?: return@withContext Result.Failed

        val me = NearbyPairingSupport.localIdentity(context)
        val initiator = NearbySpakePairing.Initiator(NearbySpakePairing.deriveW(candidatePhone), me)

        var gatt: BluetoothGatt? = null
        val handler = Handler(Looper.getMainLooper())
        var phase = Phase.AWAIT_MSG2
        var refreshedOnce = false

        val outcome = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Result> { cont ->
                fun fail() { if (cont.isActive) cont.resume(Result.Failed) }

                @Suppress("DEPRECATION")
                fun write(g: BluetoothGatt, payload: ByteArray) {
                    val ch = g.getService(NearbySpakePairing.SERVICE_UUID)
                        ?.getCharacteristic(NearbySpakePairing.REQUEST_UUID)
                    if (ch == null) { fail(); return }
                    ch.value = payload
                    g.writeCharacteristic(ch)
                }

                fun read(g: BluetoothGatt) {
                    val ch = g.getService(NearbySpakePairing.SERVICE_UUID)
                        ?.getCharacteristic(NearbySpakePairing.RESPONSE_UUID)
                    if (ch == null) { fail(); return }
                    g.readCharacteristic(ch)
                }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        Log.d(TAG, "pair connectionState status=$status newState=$newState")
                        if (newState == BluetoothGatt.STATE_CONNECTED) {
                            g.requestMtu(REQUEST_MTU)
                        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                            fail()
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        g.discoverServices()
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (g.getService(NearbySpakePairing.SERVICE_UUID) == null) {
                            if (!refreshedOnce) {
                                // Android caches a peer's GATT database across connections; if the
                                // peer registered the pairing service after we last talked to it
                                // (BLE chat / mesh), discovery serves the STALE list. Clear the
                                // cache via the hidden-but-stable refresh() and re-discover once.
                                refreshedOnce = true
                                val refreshed = runCatching {
                                    g.javaClass.getMethod("refresh").invoke(g) as? Boolean
                                }.getOrNull() == true
                                Log.d(TAG, "pair: service missing, gatt refresh=$refreshed, rediscovering")
                                handler.postDelayed({ runCatching { g.discoverServices() } }, 600)
                                return
                            }
                            Log.d(TAG, "pair: peer has no pairing GATT service")
                            fail(); return
                        }
                        write(g, initiator.message1())
                    }

                    override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                        if (ch.uuid != NearbySpakePairing.REQUEST_UUID) return
                        if (status != BluetoothGatt.GATT_SUCCESS) { fail(); return }
                        read(g)
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                        if (ch.uuid != NearbySpakePairing.RESPONSE_UUID) return
                        val value = ch.value
                        if (status != BluetoothGatt.GATT_SUCCESS || value == null) { fail(); return }
                        when (phase) {
                            Phase.AWAIT_MSG2 -> {
                                val msg3 = runCatching { initiator.onMessage2(value) }.getOrNull()
                                if (msg3 == null) {
                                    // Responder confirmation failed → not the person we're looking for.
                                    if (cont.isActive) cont.resume(Result.NotMatched)
                                    return
                                }
                                phase = Phase.AWAIT_MSG4
                                write(g, msg3)
                            }
                            Phase.AWAIT_MSG4 -> when (NearbySpakePairing.responseStatus(value)) {
                                NearbySpakePairing.STATUS_PENDING ->
                                    handler.postDelayed({ runCatching { read(g) } }, POLL_MS)

                                NearbySpakePairing.STATUS_OK -> {
                                    val peer = runCatching { initiator.onMessage4(value) }.getOrNull()
                                    if (peer == null) { fail(); return }
                                    val sessionCode = runCatching {
                                        NearbyPairingSupport.savePairedContact(
                                            context = context,
                                            peer = peer,
                                            contactKey = initiator.contactKey(),
                                            bluetoothAddress = bluetoothAddress
                                        )
                                    }.getOrElse { Log.w(TAG, "Failed to save paired contact", it); null }
                                    if (cont.isActive) {
                                        cont.resume(
                                            if (sessionCode != null)
                                                Result.Paired(sessionCode, peer.displayName.ifBlank { candidateName })
                                            else Result.Failed
                                        )
                                    }
                                }

                                else -> if (cont.isActive) cont.resume(Result.NotMatched) // REJECT
                            }
                        }
                    }
                }
                gatt = device.connectGatt(context, false, callback)
                cont.invokeOnCancellation { runCatching { gatt?.disconnect(); gatt?.close() } }
            }
        } ?: Result.Failed

        runCatching { gatt?.disconnect(); gatt?.close() }
        outcome
    }
}
