package com.auralis.crisisconnect.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.auralis.crisisconnect.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user's optional emergency medical details (blood type, allergies, medication, note).
 *
 * Deliberately LOCAL + BLE-ONLY: the fields ride the encrypted SOS peer_info to a verified
 * rescuer during an emergency and are never uploaded to the cloud. Empty fields are never sent.
 */
data class MedicalInfo(
    val bloodType: String = "",
    val allergies: String = "",
    val medication: String = "",
    val notes: String = ""
) {
    val isEmpty: Boolean
        get() = bloodType.isBlank() && allergies.isBlank() &&
            medication.isBlank() && notes.isBlank()

    fun sanitized(): MedicalInfo = MedicalInfo(
        bloodType = bloodType.trim().take(MedicalInfoStore.MAX_BLOOD_LENGTH),
        allergies = allergies.trim().take(MedicalInfoStore.MAX_FIELD_LENGTH),
        medication = medication.trim().take(MedicalInfoStore.MAX_FIELD_LENGTH),
        notes = notes.trim().take(MedicalInfoStore.MAX_FIELD_LENGTH)
    )
}

object MedicalInfoStore {
    const val MAX_BLOOD_LENGTH = 8
    const val MAX_FIELD_LENGTH = 200

    private val BLOOD_KEY = stringPreferencesKey("medical_blood_type")
    private val ALLERGIES_KEY = stringPreferencesKey("medical_allergies")
    private val MEDICATION_KEY = stringPreferencesKey("medical_medication")
    private val NOTES_KEY = stringPreferencesKey("medical_notes")

    fun observe(context: Context): Flow<MedicalInfo> {
        return context.applicationContext.settingsDataStore.data.map { prefs ->
            MedicalInfo(
                bloodType = prefs[BLOOD_KEY] ?: "",
                allergies = prefs[ALLERGIES_KEY] ?: "",
                medication = prefs[MEDICATION_KEY] ?: "",
                notes = prefs[NOTES_KEY] ?: ""
            )
        }
    }

    suspend fun save(context: Context, info: MedicalInfo) {
        val sanitized = info.sanitized()
        context.applicationContext.settingsDataStore.edit { prefs ->
            prefs[BLOOD_KEY] = sanitized.bloodType
            prefs[ALLERGIES_KEY] = sanitized.allergies
            prefs[MEDICATION_KEY] = sanitized.medication
            prefs[NOTES_KEY] = sanitized.notes
        }
    }
}
