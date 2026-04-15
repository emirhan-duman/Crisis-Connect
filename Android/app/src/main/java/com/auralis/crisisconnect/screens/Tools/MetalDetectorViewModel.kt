package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.screens.Tools.data.MetalDetectorSettings
import com.auralis.crisisconnect.screens.Tools.data.MetalDetectorSettingsDataStore
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

class MetalDetectorViewModel(application: Application) : AndroidViewModel(application),
    SensorEventListener {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val settingsStore = MetalDetectorSettingsDataStore(application)

    private val _hasMagnetometer = MutableStateFlow(magnetometer != null)
    val hasMagnetometer: StateFlow<Boolean> = _hasMagnetometer.asStateFlow()

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var isVibrating = false
    private var hasBeeped = false

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    private val _settings = MutableStateFlow(MetalDetectorSettings())
    val settingsFlow: StateFlow<MetalDetectorSettings> = _settings.asStateFlow()

    private val thresholdOn: Float
        get() = settingsFlow.value.thresholdOn
    private val thresholdOff: Float
        get() = settingsFlow.value.thresholdOff
    private val alphaBaseline: Float
        get() = settingsFlow.value.alphaBaseline
    private val alphaDelta: Float
        get() = settingsFlow.value.alphaDelta
    private val freezeBaselineOnDetection: Boolean
        get() = settingsFlow.value.freezeBaselineOnDetection
    private val sensorDelay: Int
        get() = settingsFlow.value.sensorDelay
    private val soundEnabled: Boolean
        get() = settingsFlow.value.soundEnabled
    private val baselineAdaptationEnabled: Boolean
        get() = settingsFlow.value.baselineAdaptationEnabled

    private val onDebounceMs = ON_DEBOUNCE_MS
    private val offDebounceMs = OFF_DEBOUNCE_MS

    private var baseline: Float? = null
    private var deltaEma = 0f
    private var nearStartTime: Long? = null
    private var farStartTime: Long? = null
    private var isRegistered = false

    private val _showCalibrationHint = MutableStateFlow(false)
    val showCalibrationHint: StateFlow<Boolean> = _showCalibrationHint.asStateFlow()

    private val _fieldStrength = MutableStateFlow(0f)
    val fieldStrength: StateFlow<Float> = _fieldStrength.asStateFlow()

    private val _isNear = MutableStateFlow(false)
    val isNearFlow: StateFlow<Boolean> = _isNear.asStateFlow()

    private val _history = mutableStateListOf<Float>()
    val fieldStrengthHistory: SnapshotStateList<Float> = _history

    private val maxHistorySize = 100

    private var calibrationHintSuppressed = false

    init {
        viewModelScope.launch(exceptionHandler) {
            settingsStore.settings.collect { _settings.value = it }
        }
        if (magnetometer == null) {
            Log.d(TAG, "Magnetometer not available")
        }
    }

    fun setSensitivityMode(mode: Int) {
        viewModelScope.launch(exceptionHandler) { settingsStore.update { it.copy(sensitivityMode = mode) } }
    }

    fun setThresholds(on: Float, off: Float) {
        val clampedOn = max(0f, on)
        val clampedOff = off.coerceIn(0f, clampedOn)
        viewModelScope.launch(exceptionHandler) {
            settingsStore.update {
                it.copy(thresholdOn = clampedOn, thresholdOff = clampedOff)
            }
        }
    }

    fun setFreezeBaseline(freeze: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            settingsStore.update { it.copy(freezeBaselineOnDetection = freeze) }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch(exceptionHandler) { settingsStore.update { it.copy(soundEnabled = enabled) } }
    }

    fun setBaselineAdaptation(enabled: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            settingsStore.update { it.copy(baselineAdaptationEnabled = enabled) }
        }
    }

    fun setSensorDelay(delay: Int) {
        viewModelScope.launch(exceptionHandler) {
            settingsStore.update { it.copy(sensorDelay = delay) }
            if (isRegistered) {
                stop()
                start()
            }
        }
    }

    fun applySensitivity(mode: SensitivityMode) {
        setSensitivityMode(mode.id)
        when (mode) {
            SensitivityMode.LOW -> setThresholds(12f, 6f)
            SensitivityMode.MEDIUM -> setThresholds(8f, 4f)
            SensitivityMode.HIGH -> setThresholds(5f, 2f)
            SensitivityMode.CUSTOM -> {}
        }
    }

    fun start() {
        if (!isRegistered) {
            magnetometer?.let { sensor ->
                var delay = sensorDelay
                if (delay == SensorManager.SENSOR_DELAY_FASTEST) {
                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(
                            getApplication(),
                            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else {
                        true
                    }
                    if (!hasPermission) {
                        delay = SensorManager.SENSOR_DELAY_GAME
                    }
                }
                val handler = Handler(Looper.getMainLooper())
                sensorManager.registerListener(this, sensor, delay, handler)
                isRegistered = true
            }
        }
    }

    fun stop() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }
        if (isVibrating) {
            vibrator.cancel()
            isVibrating = false
        }
        hasBeeped = false
    }

    /**
     * Resets the baseline so the next sensor reading becomes the new baseline.
     * Clears previous history and vibration state to start fresh.
     */
    fun recalibrate() {
        baseline = null
        deltaEma = 0f
        _isNear.value = false
        nearStartTime = null
        farStartTime = null
        _fieldStrength.value = 0f
        _history.clear()
        _showCalibrationHint.value = false
        hasBeeped = false
        if (isVibrating) {
            vibrator.cancel()
            isVibrating = false
        }
    }

    fun dismissCalibrationHint() {
        _showCalibrationHint.value = false
        calibrationHintSuppressed = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            if (!calibrationHintSuppressed) {
                if (!_showCalibrationHint.value) {
                    Log.d(TAG, "Sensor accuracy unreliable")
                }
                _showCalibrationHint.value = true
            }
        }

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val raw = sqrt(x * x + y * y + z * z)

        if (baseline == null) {
            baseline = raw
        }

        val freeze = freezeBaselineOnDetection && _isNear.value
        val alpha = if (baselineAdaptationEnabled) alphaBaseline else 0f
        if (!freeze) {
            baseline = baseline!! + alpha * (raw - baseline!!)
        }

        val delta = raw - baseline!!
        deltaEma += alphaDelta * (delta - deltaEma)

        val displayStrength = max(deltaEma, 0f)
        _fieldStrength.value = displayStrength
        _history.add(displayStrength)
        if (_history.size > maxHistorySize) {
            _history.removeAt(0)
        }

        val now = System.currentTimeMillis()
        if (!_isNear.value) {
            if (deltaEma > thresholdOn) {
                if (nearStartTime == null) {
                    nearStartTime = now
                }
                if (now - (nearStartTime ?: now) >= onDebounceMs) {
                    _isNear.value = true
                    Log.d(TAG, "isNear=true")
                    nearStartTime = null
                    farStartTime = null
                    if (!isVibrating) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val effect = VibrationEffect.createWaveform(longArrayOf(0, 200), 0)
                            vibrator.vibrate(effect)
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(longArrayOf(0, 200), 0)
                        }
                        isVibrating = true
                    }
                    if (soundEnabled && !hasBeeped) {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                        hasBeeped = true
                    }
                }
            } else {
                nearStartTime = null
            }
        } else {
            if (deltaEma < thresholdOff) {
                if (farStartTime == null) {
                    farStartTime = now
                }
                if (now - (farStartTime ?: now) >= offDebounceMs) {
                    _isNear.value = false
                    Log.d(TAG, "isNear=false")
                    farStartTime = null
                    nearStartTime = null
                    if (isVibrating) {
                        vibrator.cancel()
                        isVibrating = false
                    }
                    hasBeeped = false
                }
            } else {
                farStartTime = null
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE) {
            if (_showCalibrationHint.value) {
                Log.d(TAG, "Sensor accuracy restored")
            }
            _showCalibrationHint.value = false
            calibrationHintSuppressed = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { stop() }
        runCatching { toneGenerator.release() }
    }

    companion object {
        private const val TAG = "MetalDetectorViewModel"
        const val ON_DEBOUNCE_MS = 120L
        const val OFF_DEBOUNCE_MS = 350L
    }
}

enum class SensitivityMode(val id: Int) {
    LOW(0), MEDIUM(1), HIGH(2), CUSTOM(3);

    companion object {
        fun from(id: Int): SensitivityMode = values().firstOrNull { it.id == id } ?: MEDIUM
    }
}
