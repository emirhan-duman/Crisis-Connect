package com.auralis.crisisconnect.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.saveContact
import kotlinx.coroutines.delay

/**
 * Best-effort automatic bootstrap of an offline Bluetooth link for a contact that was added by
 * number over the internet. When the two are physically nearby (the peer advertises the SPAKE2
 * pairing service because they opted into nearby discovery), this runs the SAME targeted SPAKE2
 * handshake the "Nearby" tab does — using the peer's stored number as the password — so the contact
 * silently gains a Bluetooth key + address at the same [Contact.sessionCode].
 *
 * What is NOT automatic: the peer still confirms once via an Accept/Decline notification
 * ([NearbyPairingServer]) — that consent step is a deliberate anti-harvesting guard, identical to
 * the manual flow. Purely additive: on any failure the internet transport keeps working unchanged.
 *
 * Security: a SPAKE2 handshake authenticates by *possession of the phone number*, and pairing
 * overwrites the contact's identity with whatever the handshake yielded. Because we auto-initiate
 * (no user action), a nearby party who knows the number (and runs a tampered app) could otherwise
 * silently substitute their identity for our server-verified one. So we ONLY accept an auto-link
 * whose exchanged identity still matches the internet identity we already trusted; a mismatch is
 * rejected (fail-closed) and the authenticated identity is restored.
 */
object NearbyAutoLink {
    private const val TAG = "NearbyAutoLink"
    // How long to keep looking for a candidate device before giving up. A matching peer, once found,
    // can still hold the handshake open longer while they tap Accept (NearbyPairingClient's timeout).
    private const val DISCOVER_WINDOW_MS = 12_000L
    private const val POLL_MS = 1_200L

    /** True when [contact] is an internet contact that could still gain an automatic Bluetooth link. */
    fun isEligible(contact: Contact?): Boolean {
        if (contact == null) return false
        return contact.supportsInternet &&
            contact.peerPhone.isNotBlank() &&
            contact.aesKey.isBlank() &&
            contact.address.isBlank()
    }

    /**
     * Scans briefly for nearby pairing candidates and runs SPAKE2 against each with the contact's
     * number. Returns true once a link is established (the paired contact is persisted to the same
     * sessionCode by [NearbyPairingServer.accept]/[NearbyPairingSupport.savePairedContact]). Safe to
     * call off the main thread; fully cancellable.
     */
    suspend fun tryEstablish(context: Context, contact: Contact): Boolean {
        if (!isEligible(contact)) return false
        val appContext = context.applicationContext
        if (!hasBluetoothConnectPermission(appContext)) return false
        val phone = contact.peerPhone.trim()

        // The scanner is a shared singleton (also driven by RfcommForegroundService while nearby
        // discovery is opted in). Only stop it on the way out if we were the ones who started it,
        // so we never kill a scan the service owns.
        Log.d(TAG, "Auto-link attempt for ${contact.sessionCode}")
        val wasRunning = NearbyContactScanner.isRunning()
        NearbyContactScanner.start(appContext)
        // Couldn't scan at all (Bluetooth off or scan permission missing) — nothing to do.
        if (!NearbyContactScanner.isRunning()) {
            Log.w(TAG, "Auto-link aborted: BLE scan unavailable (Bluetooth off or no scan permission)")
            return false
        }
        val startedByUs = !wasRunning
        // The sweep is short — listen continuously so a LOW_POWER beacon can't slip through the
        // baseline scan's ~10% duty cycle.
        NearbyContactScanner.boostStart(appContext)
        try {
            val deadline = SystemClock.elapsedRealtime() + DISCOVER_WINDOW_MS
            val tried = HashSet<String>()
            // Keep looking for candidates until the discovery window elapses. A pair() call against
            // the real peer isn't cut off by the deadline — it's only checked between polls — so the
            // peer still has the full handshake timeout to tap Accept.
            while (SystemClock.elapsedRealtime() < deadline) {
                val candidates = NearbyContactScanner.currentAddresses().filterNot { it in tried }
                for (address in candidates) {
                    tried += address
                    val result = runCatching {
                        NearbyPairingClient.pair(
                            context = appContext,
                            bluetoothAddress = address,
                            candidatePhone = phone,
                            candidateName = contact.name
                        )
                    }.getOrElse { NearbyPairingClient.Result.Failed }
                    if (result !is NearbyPairingClient.Result.Paired) continue
                    // pair() has already overwritten our contact with the handshake's identity. Accept
                    // it only if that identity is the SAME server-verified peer we started with; a
                    // mismatch means someone nearby (who knows the number) answered — reject it,
                    // restore the authenticated identity + drop the bogus BT link, and keep looking.
                    val stored = getContact(appContext, contact.sessionCode)
                    if (isSameAuthenticatedIdentity(expected = contact, stored = stored)) {
                        Log.d(TAG, "Auto-linked Bluetooth for ${contact.sessionCode}")
                        return true
                    }
                    Log.w(TAG, "Auto-link identity mismatch for ${contact.sessionCode}; rejecting")
                    saveContact(appContext, contact)
                }
                delay(POLL_MS)
            }
            Log.d(TAG, "Auto-link window elapsed for ${contact.sessionCode}: ${tried.size} candidate(s) tried")
            return false
        } finally {
            NearbyContactScanner.boostStop()
            if (startedByUs) NearbyContactScanner.stop()
        }
    }

    /**
     * True when the identity [stored] after a nearby pairing is still the same server-verified peer
     * described by [expected] (same Firebase uid AND same identity public key). Used to reject an
     * auto-link that silently swapped a known internet identity for a different one.
     */
    internal fun isSameAuthenticatedIdentity(expected: Contact, stored: Contact?): Boolean {
        if (stored == null) return false
        if (expected.peerUid.isBlank() || expected.peerPublicKey.isBlank()) return false
        return stored.peerUid.trim() == expected.peerUid.trim() &&
            stored.peerPublicKey.trim() == expected.peerPublicKey.trim()
    }

    private fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
