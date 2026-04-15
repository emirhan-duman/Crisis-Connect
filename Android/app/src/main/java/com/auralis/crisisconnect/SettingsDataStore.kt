package com.auralis.crisisconnect

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private const val PREFS_NAME = "settings"

val Context.settingsDataStore by preferencesDataStore(name = PREFS_NAME)