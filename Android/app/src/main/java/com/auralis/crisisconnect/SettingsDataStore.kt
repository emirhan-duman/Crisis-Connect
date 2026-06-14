package com.auralis.crisisconnect

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private const val PREFS_NAME = "settings"
private const val ONBOARDING_PREFS_NAME = "popup_prefs"

val Context.settingsDataStore by preferencesDataStore(name = PREFS_NAME)
val Context.onboardingDataStore by preferencesDataStore(name = ONBOARDING_PREFS_NAME)
