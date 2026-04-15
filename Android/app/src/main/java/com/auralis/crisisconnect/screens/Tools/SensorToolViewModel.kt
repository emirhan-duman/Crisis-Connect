package com.auralis.crisisconnect.screens.Tools

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Represents the availability/streaming status of a hardware sensor.
 */
enum class SensorStatus {
    AVAILABLE,
    PAUSED,
    NOT_AVAILABLE
}

/**
 * UI-friendly representation of a sensor reading for the dashboard layer.
 */
data class SensorDisplayState(
    val sensorType: Int,
    val title: String,
    val formattedValue: String,
    val unit: String,
    val status: SensorStatus
)

/**
 * Aggregate UI state emitted by [SensorToolViewModel] for the composable screen.
 */
data class SensorToolUiState(
    val isMonitoring: Boolean,
    val sensors: List<SensorDisplayState>
)

private data class SensorDescriptor(
    val type: Int,
    val title: String,
    val unit: String,
    val optional: Boolean = false
)

private data class SensorHolder(
    val descriptor: SensorDescriptor,
    val sensor: Sensor?,
    val state: MutableStateFlow<SensorDisplayState>
)

/**
 * ViewModel responsible for managing hardware sensor subscriptions and exposing
 * real-time readings to the UI layer using Kotlin Flows.
 */
class SensorToolViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometerTitle = application.getString(R.string.sensor_title_accelerometer)
    private val magnetometerTitle = application.getString(R.string.sensor_title_magnetometer)
    private val gyroscopeTitle = application.getString(R.string.sensor_title_gyroscope)
    private val barometerTitle = application.getString(R.string.sensor_title_barometer)
    private val lightTitle = application.getString(R.string.sensor_title_light)

    private val accelerometerUnit = application.getString(R.string.sensor_unit_accelerometer)
    private val magnetometerUnit = application.getString(R.string.sensor_unit_magnetometer)
    private val gyroscopeUnit = application.getString(R.string.sensor_unit_gyroscope)
    private val barometerUnit = application.getString(R.string.sensor_unit_barometer)
    private val lightUnit = application.getString(R.string.sensor_unit_light)

    private val optionalSensorMissing = application.getString(R.string.sensor_monitor_optional_missing)
    private val sensorNotAvailable = application.getString(R.string.sensor_monitor_not_available)
    private val preparingReading = application.getString(R.string.sensor_monitor_preparing)
    private val updatesPaused = application.getString(R.string.sensor_monitor_updates_paused)
    private val unknownValue = application.getString(R.string.sensor_monitor_unknown_value)

    private val accelerometerFormat = application.getString(R.string.sensor_monitor_accelerometer_format)
    private val magnetometerFormat = application.getString(R.string.sensor_monitor_magnetometer_format)
    private val gyroscopeFormat = application.getString(R.string.sensor_monitor_gyroscope_format)
    private val pressureFormat = application.getString(R.string.sensor_monitor_pressure_format)
    private val lightFormat = application.getString(R.string.sensor_monitor_light_format)

    private val sensorDescriptors = listOf(
        SensorDescriptor(Sensor.TYPE_ACCELEROMETER, accelerometerTitle, accelerometerUnit),
        SensorDescriptor(Sensor.TYPE_MAGNETIC_FIELD, magnetometerTitle, magnetometerUnit),
        SensorDescriptor(Sensor.TYPE_GYROSCOPE, gyroscopeTitle, gyroscopeUnit),
        SensorDescriptor(Sensor.TYPE_PRESSURE, barometerTitle, barometerUnit),
        SensorDescriptor(Sensor.TYPE_LIGHT, lightTitle, lightUnit, optional = true)
    )

    private val sensorHolders: List<SensorHolder> = sensorDescriptors.map { descriptor ->
        val sensor = sensorManager.getDefaultSensor(descriptor.type)
        SensorHolder(
            descriptor = descriptor,
            sensor = sensor,
            state = MutableStateFlow(initialState(descriptor, sensor))
        )
    }

    private val holderByType: Map<Int, SensorHolder> = sensorHolders.associateBy { it.descriptor.type }

    private val accelerometerHolder get() = holderByType.getValue(Sensor.TYPE_ACCELEROMETER)
    private val magnetometerHolder get() = holderByType.getValue(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscopeHolder get() = holderByType.getValue(Sensor.TYPE_GYROSCOPE)
    private val barometerHolder get() = holderByType.getValue(Sensor.TYPE_PRESSURE)
    private val lightHolder get() = holderByType.getValue(Sensor.TYPE_LIGHT)

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    val accelerometerState: StateFlow<SensorDisplayState> = accelerometerHolder.state.asStateFlow()
    val magnetometerState: StateFlow<SensorDisplayState> = magnetometerHolder.state.asStateFlow()
    val gyroscopeState: StateFlow<SensorDisplayState> = gyroscopeHolder.state.asStateFlow()
    val barometerState: StateFlow<SensorDisplayState> = barometerHolder.state.asStateFlow()
    val lightState: StateFlow<SensorDisplayState> = lightHolder.state.asStateFlow()

    private val sensorsState: Flow<List<SensorDisplayState>> = combine(
        accelerometerState,
        magnetometerState,
        gyroscopeState,
        barometerState,
        lightState
    ) { accelerometer, magnetometer, gyroscope, barometer, light ->
        listOf(accelerometer, magnetometer, gyroscope, barometer, light)
    }

    val screenState: StateFlow<SensorToolUiState> = combine(
        isMonitoring,
        sensorsState
    ) { monitoring, sensors ->
        SensorToolUiState(
            isMonitoring = monitoring,
            sensors = sensors
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = SensorToolUiState(
            isMonitoring = _isMonitoring.value,
            sensors = sensorHolders.map { it.state.value }
        )
    )

    private var isRegistered = false
    private var hasStreamedAtLeastOnce = false

    /**
     * Begins live monitoring for all available sensors.
     */
    fun startMonitoring() {
        if (_isMonitoring.value) return
        _isMonitoring.value = true
        hasStreamedAtLeastOnce = true
        updateStatusesForMonitoring(isMonitoring = true)
        registerListeners()
    }

    /**
     * Stops live monitoring for all sensors.
     */
    fun stopMonitoring() {
        if (!_isMonitoring.value) return
        _isMonitoring.value = false
        updateStatusesForMonitoring(isMonitoring = false)
        unregisterListeners()
    }

    /**
     * Lifecycle hook that should be called from the UI when the screen gains focus.
     */
    fun onResume() {
        startMonitoring()
    }

    /**
     * Lifecycle hook that should be called from the UI when the screen leaves focus.
     */
    fun onPause() {
        stopMonitoring()
    }

    override fun onCleared() {
        super.onCleared()
        unregisterListeners()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val holder = holderByType[event.sensor.type] ?: return
        val formatted = formatReading(holder.descriptor.type, event.values)
        holder.state.update { current ->
            current.copy(
                formattedValue = formatted,
                status = SensorStatus.AVAILABLE
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerListeners() {
        if (isRegistered) return
        sensorHolders.forEach { holder ->
            holder.sensor?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
        isRegistered = true
    }

    private fun unregisterListeners() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
    }

    private fun updateStatusesForMonitoring(isMonitoring: Boolean) {
        sensorHolders.forEach { holder ->
            holder.state.update { current ->
                when {
                    holder.sensor == null -> current.copy(status = SensorStatus.NOT_AVAILABLE)
                    isMonitoring -> current.copy(status = SensorStatus.AVAILABLE)
                    hasStreamedAtLeastOnce -> current.copy(
                        status = SensorStatus.PAUSED,
                        formattedValue = updatesPaused
                    )
                    else -> current
                }
            }
        }
    }

    private fun initialState(descriptor: SensorDescriptor, sensor: Sensor?): SensorDisplayState {
        return if (sensor == null) {
            SensorDisplayState(
                sensorType = descriptor.type,
                title = descriptor.title,
                formattedValue = if (descriptor.optional) {
                    optionalSensorMissing
                } else {
                    sensorNotAvailable
                },
                unit = descriptor.unit,
                status = SensorStatus.NOT_AVAILABLE
            )
        } else {
            SensorDisplayState(
                sensorType = descriptor.type,
                title = descriptor.title,
                formattedValue = preparingReading,
                unit = descriptor.unit,
                status = SensorStatus.PAUSED
            )
        }
    }

    private fun formatReading(type: Int, values: FloatArray): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> String.format(
                Locale.US,
                accelerometerFormat,
                values.getOrNull(0) ?: 0f,
                values.getOrNull(1) ?: 0f,
                values.getOrNull(2) ?: 0f
            )

            Sensor.TYPE_MAGNETIC_FIELD -> String.format(
                Locale.US,
                magnetometerFormat,
                values.getOrNull(0) ?: 0f,
                values.getOrNull(1) ?: 0f,
                values.getOrNull(2) ?: 0f
            )

            Sensor.TYPE_GYROSCOPE -> String.format(
                Locale.US,
                gyroscopeFormat,
                values.getOrNull(0) ?: 0f,
                values.getOrNull(1) ?: 0f,
                values.getOrNull(2) ?: 0f
            )

            Sensor.TYPE_PRESSURE -> String.format(Locale.US, pressureFormat, values.getOrNull(0) ?: 0f)

            Sensor.TYPE_LIGHT -> String.format(Locale.US, lightFormat, values.getOrNull(0) ?: 0f)

            else -> unknownValue
        }
    }
}
