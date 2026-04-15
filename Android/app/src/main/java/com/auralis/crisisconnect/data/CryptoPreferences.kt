package com.auralis.crisisconnect.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import javax.crypto.KeyGenerator

val Context.cryptoDataStore by preferencesDataStore(name = "crypto_prefs")

private val AES_KEY_PREF = stringPreferencesKey("aes_key")

suspend fun getOrCreateAesKey(context: Context): String {
    val prefs = context.cryptoDataStore.data.first()
    val existingKey = prefs[AES_KEY_PREF]
    if (!existingKey.isNullOrEmpty()) {
        return existingKey
    }

    val keyGenerator = KeyGenerator.getInstance("AES")
    keyGenerator.init(128)
    val secretKey = keyGenerator.generateKey()
    val encodedKey = Base64.encodeToString(secretKey.encoded, Base64.NO_WRAP)
    context.cryptoDataStore.edit { preferences ->
        preferences[AES_KEY_PREF] = encodedKey
    }
    return encodedKey
}

suspend fun getStoredAesKey(context: Context): String? {
    val prefs = context.cryptoDataStore.data.first()
    return prefs[AES_KEY_PREF]
}
