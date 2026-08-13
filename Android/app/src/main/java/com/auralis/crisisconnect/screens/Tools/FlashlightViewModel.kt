package com.auralis.crisisconnect.screens.Tools

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

enum class FlashlightMode(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
) {
    NORMAL(R.string.flashlight_mode_normal, R.string.flashlight_mode_normal_description),
    SOS(R.string.flashlight_mode_sos, R.string.flashlight_mode_sos_description),
    STROBE(R.string.flashlight_mode_strobe, R.string.flashlight_mode_strobe_description),
    LOW_POWER(R.string.flashlight_mode_low_power, R.string.flashlight_mode_low_power_description),
    SCREEN_LIGHT(R.string.flashlight_mode_screen, R.string.flashlight_mode_screen_description),
    EMERGENCY_BEACON(
        R.string.flashlight_mode_beacon,
        R.string.flashlight_mode_beacon_description
    )
}

enum class FlashlightScreenColor(@StringRes val titleRes: Int) {
    WHITE(R.string.flashlight_screen_color_white),
    WARM(R.string.flashlight_screen_color_warm),
    RED(R.string.flashlight_screen_color_red)
}

enum class FlashlightAutoOff(
    val minutes: Int?,
    @StringRes val titleRes: Int
) {
    OFF(null, R.string.flashlight_auto_off_never),
    FIVE_MINUTES(5, R.string.flashlight_auto_off_5),
    FIFTEEN_MINUTES(15, R.string.flashlight_auto_off_15),
    THIRTY_MINUTES(30, R.string.flashlight_auto_off_30)
}

data class FlashlightUiState(
    val hasTorch: Boolean = false,
    val supportsStrengthControl: Boolean = false,
    val mode: FlashlightMode = FlashlightMode.NORMAL,
    val isActive: Boolean = false,
    val intensity: Float = 0.75f,
    val screenBrightness: Float = 1f,
    val screenColor: FlashlightScreenColor = FlashlightScreenColor.WHITE,
    val strobeRate: Int = 2,
    val autoOff: FlashlightAutoOff = FlashlightAutoOff.FIFTEEN_MINUTES,
    val showStrobeWarning: Boolean = false,
    @StringRes val errorMessageRes: Int? = null
)

class FlashlightViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val torch = AndroidTorchController(application)
    private val _uiState = MutableStateFlow(
        FlashlightUiState(
            hasTorch = torch.hasTorch,
            supportsStrengthControl = torch.maxStrength > 1,
            intensity = preferences.getFloat(KEY_INTENSITY, 0.75f).coerceIn(0.1f, 1f),
            screenBrightness = preferences.getFloat(KEY_SCREEN_BRIGHTNESS, 1f).coerceIn(0.2f, 1f),
            strobeRate = preferences.getInt(KEY_STROBE_RATE, 2).coerceIn(1, 3),
            autoOff = preferences.getString(KEY_AUTO_OFF, null)
                ?.let { stored -> FlashlightAutoOff.entries.firstOrNull { it.name == stored } }
                ?: FlashlightAutoOff.FIFTEEN_MINUTES
        )
    )
    val uiState: StateFlow<FlashlightUiState> = _uiState.asStateFlow()

    private var patternJob: Job? = null
    private var autoOffJob: Job? = null

    fun requestToggle() {
        if (_uiState.value.isActive) {
            stop()
            return
        }
        if (_uiState.value.mode == FlashlightMode.STROBE &&
            !preferences.getBoolean(KEY_STROBE_WARNING_ACKNOWLEDGED, false)
        ) {
            _uiState.update { it.copy(showStrobeWarning = true) }
            return
        }
        start()
    }

    fun confirmStrobeWarning() {
        preferences.edit().putBoolean(KEY_STROBE_WARNING_ACKNOWLEDGED, true).apply()
        _uiState.update { it.copy(showStrobeWarning = false) }
        start()
    }

    fun dismissStrobeWarning() {
        _uiState.update { it.copy(showStrobeWarning = false) }
    }

    fun selectMode(mode: FlashlightMode) {
        if (_uiState.value.mode == mode) return
        val restart = _uiState.value.isActive
        stop()
        _uiState.update { it.copy(mode = mode, errorMessageRes = null) }
        if (restart) start()
    }

    fun setIntensity(value: Float) {
        val intensity = value.coerceIn(0.1f, 1f)
        preferences.edit().putFloat(KEY_INTENSITY, intensity).apply()
        _uiState.update { it.copy(intensity = intensity) }
        if (_uiState.value.isActive && _uiState.value.mode == FlashlightMode.NORMAL) {
            applyTorch(enabled = true, intensity = intensity)
        }
    }

    fun setScreenBrightness(value: Float) {
        val brightness = value.coerceIn(0.2f, 1f)
        preferences.edit().putFloat(KEY_SCREEN_BRIGHTNESS, brightness).apply()
        _uiState.update { it.copy(screenBrightness = brightness) }
    }

    fun setScreenColor(color: FlashlightScreenColor) {
        _uiState.update { it.copy(screenColor = color) }
    }

    fun setStrobeRate(rate: Int) {
        val safeRate = rate.coerceIn(1, 3)
        preferences.edit().putInt(KEY_STROBE_RATE, safeRate).apply()
        _uiState.update { it.copy(strobeRate = safeRate) }
        if (_uiState.value.isActive && _uiState.value.mode == FlashlightMode.STROBE) {
            restartActiveMode()
        }
    }

    fun setAutoOff(option: FlashlightAutoOff) {
        preferences.edit().putString(KEY_AUTO_OFF, option.name).apply()
        _uiState.update { it.copy(autoOff = option) }
        if (_uiState.value.isActive) scheduleAutoOff()
    }

    fun reportPermissionDenied() {
        _uiState.update { it.copy(errorMessageRes = R.string.flashlight_error_camera_permission) }
    }

    fun stop() {
        patternJob?.cancel()
        patternJob = null
        autoOffJob?.cancel()
        autoOffJob = null
        torch.disableBestEffort()
        _uiState.update { it.copy(isActive = false, showStrobeWarning = false) }
    }

    private fun start() {
        val state = _uiState.value
        if (state.mode != FlashlightMode.SCREEN_LIGHT && !state.hasTorch) {
            _uiState.update { it.copy(errorMessageRes = R.string.flashlight_error_unavailable) }
            return
        }

        patternJob?.cancel()
        _uiState.update { it.copy(isActive = true, errorMessageRes = null) }
        scheduleAutoOff()

        when (state.mode) {
            FlashlightMode.NORMAL -> applyTorch(enabled = true, intensity = state.intensity)
            FlashlightMode.LOW_POWER -> applyTorch(enabled = true, intensity = 0.1f)
            FlashlightMode.SOS -> runPattern(FlashlightPatterns.sos)
            FlashlightMode.STROBE -> runPattern(FlashlightPatterns.strobe(state.strobeRate))
            FlashlightMode.EMERGENCY_BEACON -> runPattern(FlashlightPatterns.emergencyBeacon)
            FlashlightMode.SCREEN_LIGHT -> torch.disableBestEffort()
        }
    }

    private fun restartActiveMode() {
        if (!_uiState.value.isActive) return
        patternJob?.cancel()
        torch.disableBestEffort()
        _uiState.update { it.copy(isActive = false) }
        start()
    }

    private fun runPattern(pattern: List<FlashlightPulse>) {
        patternJob = viewModelScope.launch {
            while (isActive && _uiState.value.isActive) {
                for (pulse in pattern) {
                    if (!isActive || !_uiState.value.isActive) return@launch
                    if (!applyTorch(pulse.isOn, _uiState.value.intensity)) return@launch
                    delay(pulse.durationMillis)
                }
            }
        }
    }

    private fun scheduleAutoOff() {
        autoOffJob?.cancel()
        val minutes = _uiState.value.autoOff.minutes ?: return
        autoOffJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            stop()
        }
    }

    private fun applyTorch(enabled: Boolean, intensity: Float): Boolean {
        return try {
            torch.set(enabled = enabled, intensity = intensity)
            true
        } catch (_: SecurityException) {
            handleTorchError(R.string.flashlight_error_camera_permission)
            false
        } catch (_: CameraAccessException) {
            handleTorchError(R.string.flashlight_error_camera_in_use)
            false
        } catch (_: IllegalArgumentException) {
            handleTorchError(R.string.flashlight_error_unavailable)
            false
        }
    }

    private fun handleTorchError(@StringRes messageRes: Int) {
        patternJob?.cancel()
        patternJob = null
        autoOffJob?.cancel()
        autoOffJob = null
        torch.disableBestEffort()
        _uiState.update { it.copy(isActive = false, errorMessageRes = messageRes) }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        const val PREFERENCES_NAME = "flashlight_preferences"
        const val KEY_INTENSITY = "intensity"
        const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
        const val KEY_STROBE_RATE = "strobe_rate"
        const val KEY_AUTO_OFF = "auto_off"
        const val KEY_STROBE_WARNING_ACKNOWLEDGED = "strobe_warning_acknowledged"
    }
}

private class AndroidTorchController(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val torchCameraId: String? = findTorchCameraId()

    val hasTorch: Boolean = torchCameraId != null
    val maxStrength: Int = resolveMaxStrength()

    @Throws(CameraAccessException::class, SecurityException::class, IllegalArgumentException::class)
    fun set(enabled: Boolean, intensity: Float) {
        val cameraId = torchCameraId ?: throw IllegalArgumentException("No torch camera")
        if (!enabled) {
            cameraManager.setTorchMode(cameraId, false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxStrength > 1) {
            val level = (1 + (maxStrength - 1) * intensity.coerceIn(0f, 1f))
                .roundToInt()
                .coerceIn(1, maxStrength)
            cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
        } else {
            cameraManager.setTorchMode(cameraId, true)
        }
    }

    fun disableBestEffort() {
        val cameraId = torchCameraId ?: return
        runCatching { cameraManager.setTorchMode(cameraId, false) }
    }

    private fun findTorchCameraId(): String? {
        val candidates = runCatching {
            cameraManager.cameraIdList.mapNotNull { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!hasFlash) return@mapNotNull null
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                cameraId to facing
            }
        }.getOrDefault(emptyList())

        return candidates.firstOrNull { it.second == CameraCharacteristics.LENS_FACING_BACK }?.first
            ?: candidates.firstOrNull()?.first
    }

    private fun resolveMaxStrength(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 1
        val cameraId = torchCameraId ?: return 1
        return runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                ?.coerceAtLeast(1)
                ?: 1
        }.getOrDefault(1)
    }
}
