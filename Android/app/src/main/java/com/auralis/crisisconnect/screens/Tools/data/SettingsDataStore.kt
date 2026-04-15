package com.auralis.crisisconnect.screens.Tools.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.content.Context
import com.auralis.crisisconnect.settingsDataStore

object MetalDetectorPrefs {
    val SENSITIVITY_MODE = intPreferencesKey("sensitivityMode")
    val THRESHOLD_ON = floatPreferencesKey("thresholdOn")
    val THRESHOLD_OFF = floatPreferencesKey("thresholdOff")
    val ALPHA_BASELINE = floatPreferencesKey("alphaBaseline")
    val ALPHA_DELTA = floatPreferencesKey("alphaDelta")
    val FREEZE_BASELINE_ON_DETECTION = booleanPreferencesKey("freezeBaselineOnDetection")
    val SENSOR_DELAY = intPreferencesKey("sensorDelay")
    val SOUND_ENABLED = booleanPreferencesKey("soundEnabled")
    val BASELINE_ADAPTATION_ENABLED = booleanPreferencesKey("baselineAdaptationEnabled")
}

data class MetalDetectorSettings(
    val sensitivityMode: Int = 1,
    val thresholdOn: Float = 8f,
    val thresholdOff: Float = 4f,
    val alphaBaseline: Float = 0.02f,
    val alphaDelta: Float = 0.30f,
    val freezeBaselineOnDetection: Boolean = true,
    val sensorDelay: Int = android.hardware.SensorManager.SENSOR_DELAY_GAME,
    val soundEnabled: Boolean = false,
    val baselineAdaptationEnabled: Boolean = true,
)

class MetalDetectorSettingsDataStore(private val context: Context) {
    val settings: Flow<MetalDetectorSettings> = context.settingsDataStore.data.map { prefs ->
        MetalDetectorSettings(
            sensitivityMode = prefs[MetalDetectorPrefs.SENSITIVITY_MODE] ?: 1,
            thresholdOn = prefs[MetalDetectorPrefs.THRESHOLD_ON] ?: 8f,
            thresholdOff = prefs[MetalDetectorPrefs.THRESHOLD_OFF] ?: 4f,
            alphaBaseline = prefs[MetalDetectorPrefs.ALPHA_BASELINE] ?: 0.02f,
            alphaDelta = prefs[MetalDetectorPrefs.ALPHA_DELTA] ?: 0.30f,
            freezeBaselineOnDetection = prefs[MetalDetectorPrefs.FREEZE_BASELINE_ON_DETECTION] ?: true,
            sensorDelay = prefs[MetalDetectorPrefs.SENSOR_DELAY] ?: android.hardware.SensorManager.SENSOR_DELAY_GAME,
            soundEnabled = prefs[MetalDetectorPrefs.SOUND_ENABLED] ?: false,
            baselineAdaptationEnabled = prefs[MetalDetectorPrefs.BASELINE_ADAPTATION_ENABLED] ?: true,
        )
    }

    suspend fun update(transform: (MetalDetectorSettings) -> MetalDetectorSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = MetalDetectorSettings(
                sensitivityMode = prefs[MetalDetectorPrefs.SENSITIVITY_MODE] ?: 1,
                thresholdOn = prefs[MetalDetectorPrefs.THRESHOLD_ON] ?: 8f,
                thresholdOff = prefs[MetalDetectorPrefs.THRESHOLD_OFF] ?: 4f,
                alphaBaseline = prefs[MetalDetectorPrefs.ALPHA_BASELINE] ?: 0.02f,
                alphaDelta = prefs[MetalDetectorPrefs.ALPHA_DELTA] ?: 0.30f,
                freezeBaselineOnDetection = prefs[MetalDetectorPrefs.FREEZE_BASELINE_ON_DETECTION] ?: true,
                sensorDelay = prefs[MetalDetectorPrefs.SENSOR_DELAY] ?: android.hardware.SensorManager.SENSOR_DELAY_GAME,
                soundEnabled = prefs[MetalDetectorPrefs.SOUND_ENABLED] ?: false,
                baselineAdaptationEnabled = prefs[MetalDetectorPrefs.BASELINE_ADAPTATION_ENABLED] ?: true,
            )
            val new = transform(current)
            prefs[MetalDetectorPrefs.SENSITIVITY_MODE] = new.sensitivityMode
            prefs[MetalDetectorPrefs.THRESHOLD_ON] = new.thresholdOn
            prefs[MetalDetectorPrefs.THRESHOLD_OFF] = new.thresholdOff
            prefs[MetalDetectorPrefs.ALPHA_BASELINE] = new.alphaBaseline
            prefs[MetalDetectorPrefs.ALPHA_DELTA] = new.alphaDelta
            prefs[MetalDetectorPrefs.FREEZE_BASELINE_ON_DETECTION] = new.freezeBaselineOnDetection
            prefs[MetalDetectorPrefs.SENSOR_DELAY] = new.sensorDelay
            prefs[MetalDetectorPrefs.SOUND_ENABLED] = new.soundEnabled
            prefs[MetalDetectorPrefs.BASELINE_ADAPTATION_ENABLED] = new.baselineAdaptationEnabled
        }
    }
}

