package com.auralis.crisisconnect.messaging

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID

/** Explicit consent is account-scoped, versioned and excluded from device backup. */
class ContactAccessConsent(
    context: Context,
    private val ownerId: () -> String = {
        runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: "signed-out"
    }
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private fun key(online: Boolean) = "v$VERSION:${ownerId()}:${if (online) "online" else "nearby"}"

    fun token(online: Boolean): String? {
        val key = key(online)
        return prefs.getString(key, null)?.let { "$key:$it" }
    }
    fun isGranted(online: Boolean) = token(online) != null
    fun isCurrent(online: Boolean, token: String) = this.token(online) == token

    fun accept(online: Boolean): Boolean =
        prefs.edit().putString(key(online), UUID.randomUUID().toString()).commit()

    fun revoke(online: Boolean) {
        prefs.edit().remove(key(online)).commit()
        if (online) ContactDirectoryCache.clear(appContext)
    }

    companion object {
        const val PREFS = "contact_access_consent"
        // Increment when disclosed data use changes. Old consent cannot authorize the new use.
        const val VERSION = 2
    }
}
