package com.auralis.crisisconnect

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val KEY_USER_NAME = "user_name"
private val USER_NAME_KEY = stringPreferencesKey(KEY_USER_NAME)

fun getSavedUserName(context: Context): Flow<String> {
    return context.settingsDataStore.data.map { prefs ->
        prefs[USER_NAME_KEY] ?: ""
    }
}

suspend fun saveUserName(context: Context, name: String) {
    context.settingsDataStore.edit { prefs ->
        prefs[USER_NAME_KEY] = name
    }
}