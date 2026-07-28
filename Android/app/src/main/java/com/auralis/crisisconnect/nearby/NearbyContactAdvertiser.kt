package com.auralis.crisisconnect.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Advertises a GENERIC, number-free "I'm a Crisis Connect user open to nearby-add" beacon: just the
 * SPAKE2 pairing service UUID, no service data. Crucially it broadcasts NOTHING derived from the
 * phone number — the retired 0xCCA2 beacon carried a truncated hash of the number, which was
 * harvestable (recover the number by brute force / cross-epoch correlation). Identity is now only
 * revealed inside the interactive SPAKE2 handshake to someone who already proves they know the
 * number, so a passive listener learns only "a CC user is nearby" (and BLE MAC randomization keeps
 * even that unlinkable over time).
 *
 * Runs only when the user opted in AND is signed in with a verified phone (needed to answer the
 * handshake as the responder). Connectable advertising (the SPAKE2 handshake is a GATT connection
 * back to this beacon) in LOW_POWER mode.
 */
object NearbyContactAdvertiser {
    private const val TAG = "NearbyAdvertiser"

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (callback != null) return
        val appContext = context.applicationContext
        if (!hasAdvertisePermission(appContext)) {
            Log.w(TAG, "not advertising: BLUETOOTH_ADVERTISE not granted")
            return
        }
        // Without our own verified number we can't act as the SPAKE2 responder, so there's no point
        // being discoverable.
        if (NearbyPairingSupport.ownPhoneE164() == null) {
            Log.w(TAG, "not advertising: no verified own phone number")
            return
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "not advertising: Bluetooth adapter off/unavailable")
            return
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "not advertising: device does not support BLE advertising")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            // MUST be connectable: the pairing handshake is a GATT connection BACK to this beacon's
            // address ([NearbyPairingClient.pair]) — a non-connectable set rejects the LE connect at
            // the link layer, so pairing could never start. Being connectable leaks nothing extra:
            // identity still only comes out of a completed SPAKE2 handshake.
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(NearbySpakePairing.SERVICE_UUID))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Nearby advertise failed: $errorCode")
            }
        }
        callback = cb
        advertiser = leAdvertiser
        runCatching { leAdvertiser.startAdvertising(settings, data, cb) }
            .onSuccess { Log.i(TAG, "Nearby pairing advertising started") }
            .onFailure { Log.w(TAG, "startAdvertising threw", it) }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback
        val adv = advertiser
        if (cb != null && adv != null) {
            runCatching { adv.stopAdvertising(cb) }
        }
        callback = null
        advertiser = null
    }

    private fun hasAdvertisePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED
    }
}
