package com.auralis.crisisconnect.screens.Tools

import android.app.Application
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

class CompassViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVectorSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val activeRotationSensor: Sensor? = rotationVectorSensor ?: gameRotationVectorSensor

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var hasGravity = false
    private var hasGeomagnetic = false

    private var displayRotation = Surface.ROTATION_0
    private var geomagneticDeclination = 0f
    private var _declination = 0f
    private val _declinationState = MutableStateFlow(0f)
    val declination: StateFlow<Float> = _declinationState.asStateFlow()

    private var lastRotationVector: FloatArray? = null
    private var lastMagneticHeading = 0f
    private var hasHeading = false

    private var filteredHeading = 0f
    private var filterInitialized = false

    private var isRegistered = false

    private val _hasRequiredSensors = MutableStateFlow(
        activeRotationSensor != null || (accelerometer != null && magnetometer != null)
    )
    val hasRequiredSensors: StateFlow<Boolean> = _hasRequiredSensors.asStateFlow()

    private val _azimuth = MutableStateFlow(0f)
    val azimuth: StateFlow<Float> = _azimuth.asStateFlow()

    private val _accuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    val accuracy: StateFlow<Int> = _accuracy.asStateFlow()

    private val _trueNorth = MutableStateFlow(true)
    val trueNorth: StateFlow<Boolean> = _trueNorth.asStateFlow()

    private val _targetBearing = MutableStateFlow<Float?>(null)
    val targetBearing: StateFlow<Float?> = _targetBearing.asStateFlow()

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (event.sensor.type != activeRotationSensor?.type) return
                val copy = lastRotationVector?.takeIf { it.size == event.values.size } ?: FloatArray(event.values.size)
                System.arraycopy(event.values, 0, copy, 0, event.values.size)
                lastRotationVector = copy
                _accuracy.value = event.accuracy
                processRotationVector(copy)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (lowPass(event.values, gravity)) {
                    hasGravity = true
                    if (hasGeomagnetic) {
                        processAccelMag()
                    }
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (lowPass(event.values, geomagnetic)) {
                    hasGeomagnetic = true
                    if (hasGravity) {
                        processAccelMag()
                    }
                }
                _accuracy.value = event.accuracy
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == activeRotationSensor?.type || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            _accuracy.value = accuracy
        }
    }

    fun start() {
        if (!_hasRequiredSensors.value || isRegistered) return

        when {
            activeRotationSensor != null -> {
                sensorManager.registerListener(this, activeRotationSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            accelerometer != null && magnetometer != null -> {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        isRegistered = true
    }

    fun stop() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    fun setTrueNorth(enabled: Boolean) {
        if (_trueNorth.value == enabled) return
        _trueNorth.value = enabled
        recalculateHeading(force = true)
    }

    fun autoSetTrueNorth(
        lat: Double,
        lon: Double,
        altMeters: Double = 0.0,
        emergencyMode: Boolean = false
    ) {
        if (!lat.isFinite() || !lon.isFinite()) {
            updateDeclinationValue(0f)
            applyAutoTrueNorth(emergencyMode)
            return
        }

        val field = GeomagneticField(
            lat.toFloat(),
            lon.toFloat(),
            altMeters.toFloat(),
            System.currentTimeMillis()
        )
        val declination = field.declination
        updateDeclinationValue(declination)
        applyAutoTrueNorth(emergencyMode)
    }

    fun updateGeomagneticDeclination(lat: Double, lon: Double, altMeters: Double = 0.0) {
        val field = GeomagneticField(
            lat.toFloat(),
            lon.toFloat(),
            altMeters.toFloat(),
            System.currentTimeMillis()
        )
        updateDeclinationValue(field.declination)
        if (_trueNorth.value) {
            recalculateHeading(force = true)
        }
    }

    fun updateDisplayRotation(rotation: Int) {
        if (displayRotation == rotation) return
        displayRotation = rotation
        when {
            lastRotationVector != null -> processRotationVector(lastRotationVector!!, force = true)
            hasGravity && hasGeomagnetic -> processAccelMag(force = true)
        }
    }

    fun setTargetBearing(bearing: Float?) {
        _targetBearing.value = bearing?.let { normalizeDegrees(it) }
    }

    private fun processRotationVector(values: FloatArray, force: Boolean = false) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        if (!remapRotationMatrix(rotationMatrix, remappedMatrix)) return
        SensorManager.getOrientation(remappedMatrix, orientation)
        val heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
        handleNewHeading(heading, force)
    }

    private fun processAccelMag(force: Boolean = false) {
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) return
        if (!remapRotationMatrix(rotationMatrix, remappedMatrix)) return
        SensorManager.getOrientation(remappedMatrix, orientation)
        val heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
        handleNewHeading(heading, force)
    }

    private fun handleNewHeading(rawHeading: Float, force: Boolean = false) {
        // Degenerate accelerometer/magnetometer vectors can yield a non-finite heading; letting it
        // propagate makes roundToInt() below throw "Cannot round NaN value" and crash the screen.
        if (!rawHeading.isFinite()) return
        lastMagneticHeading = normalizeDegrees(rawHeading)
        hasHeading = true
        val adjustedHeading = if (_trueNorth.value) {
            normalizeDegrees(lastMagneticHeading + geomagneticDeclination)
        } else {
            lastMagneticHeading
        }
        if (force) {
            filteredHeading = adjustedHeading
            filterInitialized = true
            _azimuth.value = adjustedHeading
            return
        }
        if (!filterInitialized) {
            filteredHeading = adjustedHeading
            filterInitialized = true
            _azimuth.value = adjustedHeading
            return
        }

        val delta = shortestDelta(filteredHeading, adjustedHeading)
        val alpha = if (abs(delta) > LARGE_DELTA_THRESHOLD) FAST_FILTER_ALPHA else SLOW_FILTER_ALPHA
        filteredHeading = normalizeDegrees(filteredHeading + delta * alpha)

        val emitDelta = shortestDelta(_azimuth.value, filteredHeading)
        if (abs(emitDelta) >= MIN_EMIT_DELTA || _azimuth.value.roundToInt() != filteredHeading.roundToInt()) {
            _azimuth.value = normalizeDegrees(filteredHeading)
        }
    }

    private fun recalculateHeading(force: Boolean) {
        if (!hasHeading) return
        if (force) {
            val adjustedHeading = if (_trueNorth.value) {
                normalizeDegrees(lastMagneticHeading + geomagneticDeclination)
            } else {
                lastMagneticHeading
            }
            filteredHeading = adjustedHeading
            filterInitialized = true
            _azimuth.value = adjustedHeading
        } else {
            handleNewHeading(lastMagneticHeading, force = false)
        }
    }

    private fun remapRotationMatrix(source: FloatArray, destination: FloatArray): Boolean {
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        return SensorManager.remapCoordinateSystem(source, axisX, axisY, destination)
    }

    private fun lowPass(input: FloatArray, output: FloatArray): Boolean {
        var updated = false
        for (i in input.indices) {
            val newValue = output[i] + LOW_PASS_ALPHA * (input[i] - output[i])
            if (abs(newValue - output[i]) > 0.0001f) {
                updated = true
            }
            output[i] = newValue
        }
        return updated
    }

    private fun normalizeDegrees(degrees: Float): Float {
        var value = degrees % 360f
        if (value < 0f) value += 360f
        return value
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var delta = to - from
        delta = (delta + 540f) % 360f - 180f
        return delta
    }

    companion object {
        private const val LOW_PASS_ALPHA = 0.15f
        private const val LARGE_DELTA_THRESHOLD = 10f
        private const val FAST_FILTER_ALPHA = 0.35f
        private const val SLOW_FILTER_ALPHA = 0.12f
        private const val MIN_EMIT_DELTA = 0.5f
        private const val TRUE_NORTH_THRESHOLD_DEGREES = 3f
    }

    private fun updateDeclinationValue(value: Float) {
        _declination = value
        geomagneticDeclination = value
        if (_declinationState.value != value) {
            _declinationState.value = value
        }
    }

    private fun applyAutoTrueNorth(emergencyMode: Boolean) {
        val shouldEnable = when {
            emergencyMode -> true
            abs(_declination) >= TRUE_NORTH_THRESHOLD_DEGREES -> true
            else -> false
        }

        val modeChanged = _trueNorth.value != shouldEnable
        if (modeChanged) {
            _trueNorth.value = shouldEnable
            recalculateHeading(force = true)
        } else if (shouldEnable) {
            recalculateHeading(force = true)
        }
    }
}
