package com.auralis.crisisconnect.screens.Chat

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ImuMotionEstimate(
    val stepCount: Int,
    val distanceMeters: Float,
    val headingDegrees: Float?,
    val headingStdDevDegrees: Float?
)

internal class LowPowerBleImuEstimator(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    suspend fun captureMotion(durationMillis: Long = DEFAULT_CAPTURE_DURATION_MS): ImuMotionEstimate? =
        withContext(Dispatchers.Default) {
            val manager = sensorManager ?: return@withContext null
            val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return@withContext null
            val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return@withContext null
            val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            val collector = MotionCollector(manager)
            val handlerThread = HandlerThread("cc-imu-capture").apply { start() }
            val handler = Handler(handlerThread.looper)

            val registered = runCatching {
                var anyRegistered = false
                anyRegistered = manager.registerListener(
                    collector,
                    accelerometer,
                    SENSOR_SAMPLE_PERIOD_US,
                    SENSOR_BATCH_LATENCY_US,
                    handler
                ) || anyRegistered
                anyRegistered = manager.registerListener(
                    collector,
                    gyroscope,
                    SENSOR_SAMPLE_PERIOD_US,
                    SENSOR_BATCH_LATENCY_US,
                    handler
                ) || anyRegistered
                if (magnetometer != null) {
                    anyRegistered = manager.registerListener(
                        collector,
                        magnetometer,
                        SENSOR_SAMPLE_PERIOD_US,
                        SENSOR_BATCH_LATENCY_US,
                        handler
                    ) || anyRegistered
                }
                anyRegistered
            }.getOrDefault(false)

            if (!registered) {
                runCatching { handlerThread.quitSafely() }
                return@withContext null
            }

            try {
                delay(durationMillis.coerceIn(MIN_CAPTURE_DURATION_MS, MAX_CAPTURE_DURATION_MS))
            } finally {
                runCatching { manager.unregisterListener(collector) }
                runCatching { handlerThread.quitSafely() }
            }

            collector.snapshot()
        }

    private class MotionCollector(
        private val sensorManager: SensorManager
    ) : SensorEventListener {

        private val madgwick = MadgwickFilter(beta = 0.085f)
        private var lastGyroTimestampNs: Long = 0L

        private val accel = FloatArray(3)
        private val magnetic = FloatArray(3)
        private var hasAccel = false
        private var hasMagnetic = false

        private var gravityMagnitude = EARTH_GRAVITY_MPS2
        private var wasAboveStepThreshold = false
        private var lastStepTimestampNs: Long = 0L
        private var steps = 0

        private var yawOffsetDegrees: Float? = null
        private val headingSamples = ArrayList<Float>(64)

        override fun onSensorChanged(event: SensorEvent?) {
            val sample = event ?: return
            when (sample.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    updateAccel(sample.values)
                    detectStep(sample.timestamp, sample.values)
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    updateMagnetic(sample.values)
                }

                Sensor.TYPE_GYROSCOPE -> {
                    handleGyro(sample)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun updateAccel(values: FloatArray) {
            if (values.size < 3) return
            accel[0] = values[0]
            accel[1] = values[1]
            accel[2] = values[2]
            hasAccel = true
        }

        private fun updateMagnetic(values: FloatArray) {
            if (values.size < 3) return
            magnetic[0] = values[0]
            magnetic[1] = values[1]
            magnetic[2] = values[2]
            hasMagnetic = true
        }

        private fun detectStep(timestampNs: Long, values: FloatArray) {
            if (values.size < 3) return
            val magnitude = sqrt(
                (values[0] * values[0]) +
                    (values[1] * values[1]) +
                    (values[2] * values[2])
            )
            gravityMagnitude = (gravityMagnitude * STEP_GRAVITY_ALPHA) + (magnitude * (1f - STEP_GRAVITY_ALPHA))
            val linearMagnitude = magnitude - gravityMagnitude

            if (!wasAboveStepThreshold && linearMagnitude >= STEP_PEAK_THRESHOLD_MPS2) {
                if (timestampNs - lastStepTimestampNs >= MIN_STEP_INTERVAL_NS) {
                    steps += 1
                    lastStepTimestampNs = timestampNs
                }
                wasAboveStepThreshold = true
            } else if (wasAboveStepThreshold && linearMagnitude <= STEP_RESET_THRESHOLD_MPS2) {
                wasAboveStepThreshold = false
            }
        }

        private fun handleGyro(sample: SensorEvent) {
            if (!hasAccel) {
                lastGyroTimestampNs = sample.timestamp
                return
            }
            val dtSeconds = if (lastGyroTimestampNs > 0L) {
                (sample.timestamp - lastGyroTimestampNs) / 1_000_000_000f
            } else {
                0f
            }
            lastGyroTimestampNs = sample.timestamp
            if (dtSeconds <= 0f || dtSeconds > 0.06f) {
                return
            }

            madgwick.update(
                gyroX = sample.values.getOrElse(0) { 0f },
                gyroY = sample.values.getOrElse(1) { 0f },
                gyroZ = sample.values.getOrElse(2) { 0f },
                accelX = accel[0],
                accelY = accel[1],
                accelZ = accel[2],
                dtSeconds = dtSeconds
            )

            val madgwickHeading = madgwick.headingDegrees() ?: return
            val magneticHeading = computeMagneticHeading()
            if (yawOffsetDegrees == null && magneticHeading != null) {
                yawOffsetDegrees = shortestSignedDeltaDegrees(madgwickHeading, magneticHeading)
            }
            val fusedHeading = when {
                yawOffsetDegrees != null -> normalizeDegrees(madgwickHeading + yawOffsetDegrees!!)
                magneticHeading != null -> magneticHeading
                else -> madgwickHeading
            }
            headingSamples += fusedHeading
        }

        private fun computeMagneticHeading(): Float? {
            if (!hasAccel || !hasMagnetic) {
                return null
            }
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            val ok = SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                accel,
                magnetic
            )
            if (!ok) return null
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuthRad = orientation[0]
            return normalizeDegrees(Math.toDegrees(azimuthRad.toDouble()).toFloat())
        }

        fun snapshot(): ImuMotionEstimate? {
            if (steps <= 0 && headingSamples.isEmpty()) {
                return null
            }
            val heading = circularMeanDegrees(headingSamples)
            val stdDev = circularStdDevDegrees(headingSamples)
            val distance = (steps * DEFAULT_STEP_LENGTH_METERS).coerceAtLeast(0f)
            return ImuMotionEstimate(
                stepCount = steps,
                distanceMeters = distance,
                headingDegrees = heading,
                headingStdDevDegrees = stdDev
            )
        }
    }

    private class MadgwickFilter(
        private val beta: Float
    ) {
        private var q0 = 1f
        private var q1 = 0f
        private var q2 = 0f
        private var q3 = 0f

        fun update(
            gyroX: Float,
            gyroY: Float,
            gyroZ: Float,
            accelX: Float,
            accelY: Float,
            accelZ: Float,
            dtSeconds: Float
        ) {
            if (dtSeconds <= 0f) return

            var gx = gyroX
            var gy = gyroY
            var gz = gyroZ
            var ax = accelX
            var ay = accelY
            var az = accelZ

            val accelNorm = sqrt((ax * ax) + (ay * ay) + (az * az))
            if (accelNorm <= 0f || !accelNorm.isFinite()) {
                integrateGyroOnly(gx, gy, gz, dtSeconds)
                return
            }

            ax /= accelNorm
            ay /= accelNorm
            az /= accelNorm

            val twoQ0 = 2f * q0
            val twoQ1 = 2f * q1
            val twoQ2 = 2f * q2
            val twoQ3 = 2f * q3
            val fourQ0 = 4f * q0
            val fourQ1 = 4f * q1
            val fourQ2 = 4f * q2
            val eightQ1 = 8f * q1
            val eightQ2 = 8f * q2
            val q0q0 = q0 * q0
            val q1q1 = q1 * q1
            val q2q2 = q2 * q2
            val q3q3 = q3 * q3

            var s0 = fourQ0 * q2q2 + twoQ2 * ax + fourQ0 * q1q1 - twoQ1 * ay
            var s1 = fourQ1 * q3q3 - twoQ3 * ax + 4f * q0q0 * q1 - twoQ0 * ay - fourQ1 + eightQ1 * q1q1 + eightQ1 * q2q2 + fourQ1 * az
            var s2 = 4f * q0q0 * q2 + twoQ0 * ax + fourQ2 * q3q3 - twoQ3 * ay - fourQ2 + eightQ2 * q1q1 + eightQ2 * q2q2 + fourQ2 * az
            var s3 = 4f * q1q1 * q3 - twoQ1 * ax + 4f * q2q2 * q3 - twoQ2 * ay

            val stepNorm = sqrt((s0 * s0) + (s1 * s1) + (s2 * s2) + (s3 * s3))
            if (stepNorm > 0f && stepNorm.isFinite()) {
                s0 /= stepNorm
                s1 /= stepNorm
                s2 /= stepNorm
                s3 /= stepNorm
            }

            val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - beta * s0
            val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - beta * s1
            val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - beta * s2
            val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx) - beta * s3

            q0 += qDot0 * dtSeconds
            q1 += qDot1 * dtSeconds
            q2 += qDot2 * dtSeconds
            q3 += qDot3 * dtSeconds

            normalizeQuaternion()
        }

        private fun integrateGyroOnly(gx: Float, gy: Float, gz: Float, dtSeconds: Float) {
            val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
            val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
            val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
            val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)
            q0 += qDot0 * dtSeconds
            q1 += qDot1 * dtSeconds
            q2 += qDot2 * dtSeconds
            q3 += qDot3 * dtSeconds
            normalizeQuaternion()
        }

        private fun normalizeQuaternion() {
            val norm = sqrt((q0 * q0) + (q1 * q1) + (q2 * q2) + (q3 * q3))
            if (norm <= 0f || !norm.isFinite()) {
                q0 = 1f
                q1 = 0f
                q2 = 0f
                q3 = 0f
                return
            }
            q0 /= norm
            q1 /= norm
            q2 /= norm
            q3 /= norm
        }

        fun headingDegrees(): Float? {
            val yaw = atan2(
                2f * ((q0 * q3) + (q1 * q2)),
                1f - (2f * ((q2 * q2) + (q3 * q3)))
            )
            if (!yaw.isFinite()) return null
            return normalizeDegrees(Math.toDegrees(yaw.toDouble()).toFloat())
        }
    }

    private companion object {
        const val DEFAULT_CAPTURE_DURATION_MS = 2_200L
        const val MIN_CAPTURE_DURATION_MS = 1_300L
        const val MAX_CAPTURE_DURATION_MS = 3_600L
        const val SENSOR_SAMPLE_PERIOD_US = 40_000
        const val SENSOR_BATCH_LATENCY_US = 180_000
        const val DEFAULT_STEP_LENGTH_METERS = 0.72f
        const val EARTH_GRAVITY_MPS2 = 9.80665f
        const val STEP_GRAVITY_ALPHA = 0.90f
        const val STEP_PEAK_THRESHOLD_MPS2 = 1.05f
        const val STEP_RESET_THRESHOLD_MPS2 = 0.35f
        const val MIN_STEP_INTERVAL_NS = 260_000_000L
    }
}

private fun circularMeanDegrees(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    var sumSin = 0.0
    var sumCos = 0.0
    values.forEach { angle ->
        val rad = Math.toRadians(angle.toDouble())
        sumSin += sin(rad)
        sumCos += cos(rad)
    }
    if (sumSin == 0.0 && sumCos == 0.0) {
        return null
    }
    return normalizeDegrees(Math.toDegrees(atan2(sumSin, sumCos)).toFloat())
}

private fun circularStdDevDegrees(values: List<Float>): Float? {
    if (values.size < 2) return null
    var sumSin = 0.0
    var sumCos = 0.0
    values.forEach { angle ->
        val rad = Math.toRadians(angle.toDouble())
        sumSin += sin(rad)
        sumCos += cos(rad)
    }
    val n = values.size.toDouble()
    val meanSin = sumSin / n
    val meanCos = sumCos / n
    val r = sqrt(meanSin.pow(2.0) + meanCos.pow(2.0)).coerceIn(0.0, 1.0)
    if (r <= 1e-6) return 180f
    val stdRad = sqrt((-2.0 * ln(r)).coerceAtLeast(0.0))
    return Math.toDegrees(stdRad).toFloat().coerceIn(0f, 180f)
}

private fun normalizeDegrees(value: Float): Float {
    var normalized = value % 360f
    if (normalized < 0f) {
        normalized += 360f
    }
    return normalized
}

private fun shortestSignedDeltaDegrees(from: Float, to: Float): Float {
    val diff = (normalizeDegrees(to) - normalizeDegrees(from) + 540f) % 360f - 180f
    return diff
}
