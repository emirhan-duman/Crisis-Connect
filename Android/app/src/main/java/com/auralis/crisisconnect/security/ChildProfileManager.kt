package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Outcome of a PIN-gated child profile operation. */
sealed interface ChildPinResult {
    data object Success : ChildPinResult
    data class WrongPin(val attemptsLeft: Int) : ChildPinResult
    data class LockedOut(val remainingSeconds: Long) : ChildPinResult
}

/**
 * Child profile mode: an app-wide flag that can only be turned off with a
 * parent-chosen PIN.
 *
 * The PIN is never stored; only a salted PBKDF2 hash is kept, and the backing
 * preference file is additionally encrypted through [KeystoreBackedPreferences].
 * Wrong guesses are throttled: after [FREE_ATTEMPTS] consecutive failures each
 * further attempt is locked behind a growing cool-down.
 */
object ChildProfileManager {

    const val PIN_MIN_LENGTH = 4
    const val PIN_MAX_LENGTH = 8

    private const val PREFS_NAME = "child_profile_prefs"
    private const val KEY_ALIAS = "child_profile_prefs_key"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    private const val KEY_PARENTS = "parent_session_codes"
    private const val KEY_PENDING_PARENTS = "pending_parent_session_codes"
    private const val KEY_CHILDREN = "child_session_codes"

    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val FREE_ATTEMPTS = 5
    private const val LOCKOUT_STEP_MS = 30_000L

    @Volatile
    private var prefs: KeystoreBackedPreferences? = null
    private val enabledState = MutableStateFlow(false)
    private val parentsState = MutableStateFlow<Set<String>>(emptySet())
    private val pendingParentsState = MutableStateFlow<Set<String>>(emptySet())
    private val childrenState = MutableStateFlow<Set<String>>(emptySet())

    /** Whether child profile mode is currently on. Safe to collect from any screen. */
    fun enabledFlow(context: Context): StateFlow<Boolean> {
        prefsFor(context)
        return enabledState.asStateFlow()
    }

    fun isEnabled(context: Context): Boolean {
        prefsFor(context)
        return enabledState.value
    }

    fun isValidPin(pin: String): Boolean {
        return pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all(Char::isDigit)
    }

    /** Turns the mode on with a freshly chosen PIN. */
    suspend fun enable(context: Context, pin: String): Boolean = withContext(Dispatchers.Default) {
        if (!isValidPin(pin)) return@withContext false
        val prefs = prefsFor(context)
        val salt = ByteArray(SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)
        val hash = hashPin(pin, salt) ?: return@withContext false
        prefs.putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
        prefs.putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
        prefs.remove(KEY_FAILED_ATTEMPTS, KEY_LOCKOUT_UNTIL)
        prefs.putString(KEY_ENABLED, "true")
        enabledState.value = true
        true
    }

    /** Turns the mode off if [pin] matches; wrong guesses count toward the lockout. */
    suspend fun disable(context: Context, pin: String): ChildPinResult =
        withContext(Dispatchers.Default) {
            val result = verifyPin(context, pin)
            if (result is ChildPinResult.Success) {
                // The parents list intentionally survives, so re-enabling later
                // doesn't force the parent to rebuild it.
                prefsFor(context).remove(
                    KEY_ENABLED,
                    KEY_PIN_HASH,
                    KEY_PIN_SALT,
                    KEY_FAILED_ATTEMPTS,
                    KEY_LOCKOUT_UNTIL
                )
                enabledState.value = false
            }
            result
        }

    /**
     * Checks [pin] against the stored hash without changing the mode — used to gate
     * parent-list edits while the mode is on. Wrong guesses share the disable lockout.
     */
    suspend fun verifyPin(context: Context, pin: String): ChildPinResult =
        withContext(Dispatchers.Default) {
            val prefs = prefsFor(context)

            val lockedForSeconds = remainingLockoutSeconds(prefs)
            if (lockedForSeconds > 0) {
                return@withContext ChildPinResult.LockedOut(lockedForSeconds)
            }

            val salt = prefs.getString(KEY_PIN_SALT)?.let(::decodeBase64)
            val expected = prefs.getString(KEY_PIN_HASH)?.let(::decodeBase64)
            if (salt == null || expected == null) {
                // Storage was lost or tampered with; failing open would defeat the
                // lock, so treat it as a wrong PIN until the mode is re-set.
                return@withContext registerFailure(prefs)
            }

            val actual = hashPin(pin, salt)
            if (actual != null && MessageDigest.isEqual(expected, actual)) {
                prefs.remove(KEY_FAILED_ATTEMPTS, KEY_LOCKOUT_UNTIL)
                ChildPinResult.Success
            } else {
                registerFailure(prefs)
            }
        }

    /**
     * Session codes of the CONFIRMED parents — contacts that approved the parent request
     * from their own device. Local-only; child-mode features (e.g. SOS recipients) use
     * only this set, never the pending one.
     */
    fun parentsFlow(context: Context): StateFlow<Set<String>> {
        prefsFor(context)
        return parentsState.asStateFlow()
    }

    /** Session codes with a parent request sent but not yet answered by the peer. */
    fun pendingParentsFlow(context: Context): StateFlow<Set<String>> {
        prefsFor(context)
        return pendingParentsState.asStateFlow()
    }

    /** Marks a parent request as sent; the contact becomes a parent only on [confirmParent]. */
    suspend fun addPendingParent(context: Context, sessionCode: String) =
        withContext(Dispatchers.Default) {
            val code = sessionCode.trim()
            if (code.isEmpty() || code in parentsState.value) return@withContext
            persistPendingParents(prefsFor(context), pendingParentsState.value + code)
        }

    suspend fun removePendingParent(context: Context, sessionCode: String) =
        withContext(Dispatchers.Default) {
            persistPendingParents(prefsFor(context), pendingParentsState.value - sessionCode.trim())
        }

    /**
     * Promotes a pending request to a confirmed parent (the peer accepted). Returns false when
     * there was no pending request for [sessionCode] — an unsolicited "accept" changes nothing,
     * and a duplicate delivery of the same response is a no-op.
     */
    suspend fun confirmParent(context: Context, sessionCode: String): Boolean =
        withContext(Dispatchers.Default) {
            val code = sessionCode.trim()
            val prefs = prefsFor(context)
            if (code !in pendingParentsState.value) return@withContext false
            persistPendingParents(prefs, pendingParentsState.value - code)
            persistParents(prefs, parentsState.value + code)
            true
        }

    suspend fun removeParent(context: Context, sessionCode: String) =
        withContext(Dispatchers.Default) {
            persistParents(prefsFor(context), parentsState.value - sessionCode.trim())
        }

    /**
     * Session codes of the CHILDREN this device is a parent of — contacts whose parent request
     * this device accepted. Independent of whether THIS device is itself in child mode; a parent
     * views their children here.
     */
    fun childrenFlow(context: Context): StateFlow<Set<String>> {
        prefsFor(context)
        return childrenState.asStateFlow()
    }

    /** Records that we accepted [sessionCode]'s parent request (they are now our child). */
    suspend fun addChild(context: Context, sessionCode: String) =
        withContext(Dispatchers.Default) {
            val code = sessionCode.trim()
            if (code.isEmpty()) return@withContext
            persistChildren(prefsFor(context), childrenState.value + code)
        }

    suspend fun removeChild(context: Context, sessionCode: String) =
        withContext(Dispatchers.Default) {
            persistChildren(prefsFor(context), childrenState.value - sessionCode.trim())
        }

    private fun persistParents(prefs: KeystoreBackedPreferences, parents: Set<String>) {
        // Session codes never contain newlines, so a newline join is a safe encoding.
        prefs.putString(KEY_PARENTS, parents.joinToString("\n").ifEmpty { null })
        parentsState.value = parents
    }

    private fun persistPendingParents(prefs: KeystoreBackedPreferences, pending: Set<String>) {
        prefs.putString(KEY_PENDING_PARENTS, pending.joinToString("\n").ifEmpty { null })
        pendingParentsState.value = pending
    }

    private fun persistChildren(prefs: KeystoreBackedPreferences, children: Set<String>) {
        prefs.putString(KEY_CHILDREN, children.joinToString("\n").ifEmpty { null })
        childrenState.value = children
    }

    private fun registerFailure(prefs: KeystoreBackedPreferences): ChildPinResult {
        val failures = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        prefs.putInt(KEY_FAILED_ATTEMPTS, failures)
        return if (failures >= FREE_ATTEMPTS) {
            // Every failure past the free attempts lengthens the cool-down.
            val lockoutMs = LOCKOUT_STEP_MS * (failures - FREE_ATTEMPTS + 1)
            prefs.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + lockoutMs)
            ChildPinResult.LockedOut(lockoutMs / 1000)
        } else {
            ChildPinResult.WrongPin(attemptsLeft = FREE_ATTEMPTS - failures)
        }
    }

    private fun remainingLockoutSeconds(prefs: KeystoreBackedPreferences): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val remainingMs = lockoutUntil - System.currentTimeMillis()
        // Round up so we never show "0 seconds" while still locked.
        return if (remainingMs > 0) (remainingMs + 999) / 1000 else 0
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray? {
        return runCatching {
            val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }.getOrNull()
    }

    private fun decodeBase64(value: String): ByteArray? {
        return runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()
    }

    private fun readSessionCodeSet(prefs: KeystoreBackedPreferences, key: String): Set<String> {
        return prefs.getString(key)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            ?: emptySet()
    }

    private fun prefsFor(context: Context): KeystoreBackedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val created = KeystoreBackedPreferences(
                context.applicationContext,
                PREFS_NAME,
                KEY_ALIAS
            )
            enabledState.value = created.getString(KEY_ENABLED) == "true"
            parentsState.value = readSessionCodeSet(created, KEY_PARENTS)
            pendingParentsState.value = readSessionCodeSet(created, KEY_PENDING_PARENTS)
            childrenState.value = readSessionCodeSet(created, KEY_CHILDREN)
            prefs = created
            return created
        }
    }
}
