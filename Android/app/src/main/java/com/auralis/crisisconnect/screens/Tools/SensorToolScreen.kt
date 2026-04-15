package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val BATTERY_TEMPERATURE_PROPERTY_ID = 5 // ID for BatteryManager.BATTERY_PROPERTY_TEMPERATURE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorToolScreen(navController: NavController, viewModel: SensorToolViewModel = viewModel()) {
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(context.hasFineLocationPermission())
    }
    var gpsRefreshToken by remember { mutableIntStateOf(0) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    val uiState by viewModel.screenState.collectAsStateWithLifecycle()
    val gpsSnapshot by rememberGpsSnapshot(
        hasLocationPermission = hasLocationPermission,
        refreshToken = gpsRefreshToken
    )
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(hasLocationPermission) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasLocationPermission = context.hasFineLocationPermission()
                    gpsRefreshToken += 1
                    viewModel.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onPause()
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val cards by rememberDisasterSensorCards(
        sensors = uiState.sensors,
        gpsSnapshot = gpsSnapshot
    )

    Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = colorScheme.onBackground,
            topBar = {
                AppBackTopBar(
                    titleRes = R.string.sensor_monitor_title,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        ) { innerPadding ->
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                DisasterDashboardHeader(
                    isMonitoring = uiState.isMonitoring,
                    gpsSnapshot = gpsSnapshot
                )

                cards.forEach { data ->
                    DisasterSensorCard(data = data)
                }
            }
        }
    }
}

@Composable
private fun rememberDisasterSensorCards(
    sensors: List<SensorDisplayState>,
    gpsSnapshot: GpsSnapshot
): State<List<DisasterSensorCardData>> {
    val context = LocalContext.current
    val batterySnapshot by rememberBatteryTemperatureSnapshot()
    return produceState(initialValue = emptyList(), sensors, gpsSnapshot, batterySnapshot, context) {
        val sensorMap = sensors.associateBy { it.sensorType }
        val list = listOf(
            gpsCard(gpsSnapshot, context),
            pressureCard(sensorMap[Sensor.TYPE_PRESSURE], context),
            accelerometerCard(sensorMap[Sensor.TYPE_ACCELEROMETER], context),
            gyroscopeCard(sensorMap[Sensor.TYPE_GYROSCOPE], context),
            lightCard(sensorMap[Sensor.TYPE_LIGHT], context),
            batteryCard(batterySnapshot, context)
        )
        value = list
    }
}

@Composable
private fun rememberBatteryTemperatureSnapshot(): State<BatterySnapshot> {
    val context = LocalContext.current
    return produceState(initialValue = BatterySnapshot(null, SensorStatus.PAUSED), context) {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (manager == null) {
            value = BatterySnapshot(null, SensorStatus.NOT_AVAILABLE)
            return@produceState
        }
        while (isActive) {
            val raw = manager.getIntProperty(BATTERY_TEMPERATURE_PROPERTY_ID)
            if (raw != Int.MIN_VALUE) {
                value = BatterySnapshot(raw / 10f, SensorStatus.AVAILABLE)
            } else {
                value = BatterySnapshot(null, SensorStatus.NOT_AVAILABLE)
            }
            delay(5_000)
        }
    }
}

@Suppress("MissingPermission")
@Composable
private fun rememberGpsSnapshot(hasLocationPermission: Boolean, refreshToken: Int): State<GpsSnapshot> {
    val context = LocalContext.current
    return produceState(
        initialValue = if (hasLocationPermission) {
            GpsSnapshot(status = SensorStatus.PAUSED, reason = GpsUnavailableReason.SEARCHING_SIGNAL)
        } else {
            GpsSnapshot(status = SensorStatus.NOT_AVAILABLE, reason = GpsUnavailableReason.PERMISSION_REQUIRED)
        },
        context,
        hasLocationPermission,
        refreshToken
    ) {
        if (!hasLocationPermission) {
            value = GpsSnapshot(
                status = SensorStatus.NOT_AVAILABLE,
                reason = GpsUnavailableReason.PERMISSION_REQUIRED
            )
            return@produceState
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            value = GpsSnapshot(
                status = SensorStatus.NOT_AVAILABLE,
                reason = GpsUnavailableReason.GPS_NOT_SUPPORTED
            )
            return@produceState
        }

        val hasGpsProvider = runCatching {
            locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (!hasGpsProvider) {
            value = GpsSnapshot(
                status = SensorStatus.NOT_AVAILABLE,
                reason = GpsUnavailableReason.GPS_NOT_SUPPORTED
            )
            return@produceState
        }

        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (!gpsEnabled) {
            value = GpsSnapshot(
                status = SensorStatus.NOT_AVAILABLE,
                reason = GpsUnavailableReason.GPS_DISABLED
            )
            return@produceState
        }

        val updateFromLocation: (Location) -> Unit = { location ->
            value = location.toGpsSnapshot()
        }

        runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()?.let(updateFromLocation)

        if (value.status != SensorStatus.AVAILABLE) {
            value = GpsSnapshot(
                status = SensorStatus.PAUSED,
                reason = GpsUnavailableReason.SEARCHING_SIGNAL
            )
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                updateFromLocation(location)
            }

            override fun onProviderEnabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER && value.status != SensorStatus.AVAILABLE) {
                    value = GpsSnapshot(
                        status = SensorStatus.PAUSED,
                        reason = GpsUnavailableReason.SEARCHING_SIGNAL
                    )
                }
            }

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) {
                    value = GpsSnapshot(
                        status = SensorStatus.NOT_AVAILABLE,
                        reason = GpsUnavailableReason.GPS_DISABLED
                    )
                }
            }
        }

        val registered = runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2_000L,
                0f,
                listener,
                Looper.getMainLooper()
            )
        }.isSuccess

        if (!registered && value.status != SensorStatus.AVAILABLE) {
            value = GpsSnapshot(
                status = SensorStatus.NOT_AVAILABLE,
                reason = GpsUnavailableReason.UNKNOWN
            )
        }

        try {
            awaitCancellation()
        } finally {
            if (registered) {
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }
}

@Composable
private fun DisasterDashboardHeader(isMonitoring: Boolean, gpsSnapshot: GpsSnapshot) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.sensor_monitor_dashboard_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.sensor_monitor_dashboard_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onBackground.copy(alpha = 0.7f)
        )
        val statusColor = if (isMonitoring) colorScheme.primaryContainer else colorScheme.secondaryContainer
        val statusContent = if (isMonitoring) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = statusColor,
                contentColor = statusContent
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (isMonitoring) {
                            stringResource(R.string.sensor_monitor_dashboard_status_active_title)
                        } else {
                            stringResource(R.string.sensor_monitor_dashboard_status_paused_title)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isMonitoring) {
                            stringResource(R.string.sensor_monitor_dashboard_status_active_body)
                        } else {
                            stringResource(R.string.sensor_monitor_dashboard_status_paused_body)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = gpsHeaderStatusText(gpsSnapshot),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(statusContent.copy(alpha = 0.2f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isMonitoring) {
                            stringResource(R.string.sensor_monitor_dashboard_chip_active)
                        } else {
                            stringResource(R.string.sensor_monitor_dashboard_chip_passive)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun gpsHeaderStatusText(snapshot: GpsSnapshot): String {
    val localTime = formatGpsLocalTime(snapshot.timestampMillis)
    return when (snapshot.reason) {
        GpsUnavailableReason.PERMISSION_REQUIRED ->
            stringResource(R.string.sensor_monitor_gps_header_permission_required)
        GpsUnavailableReason.GPS_DISABLED ->
            stringResource(R.string.sensor_monitor_gps_header_disabled)
        GpsUnavailableReason.GPS_NOT_SUPPORTED ->
            stringResource(R.string.sensor_monitor_gps_header_not_supported)
        GpsUnavailableReason.SEARCHING_SIGNAL ->
            stringResource(R.string.sensor_monitor_gps_header_searching)
        GpsUnavailableReason.UNKNOWN ->
            stringResource(R.string.sensor_monitor_gps_header_unavailable)
        null -> stringResource(
            R.string.sensor_monitor_gps_header_time_format,
            localTime ?: stringResource(R.string.sensor_monitor_time_unavailable)
        )
    }
}

@Composable
private fun DisasterSensorCard(data: DisasterSensorCardData) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceColorAtElevation(4.dp),
            contentColor = colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = data.measurement,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = data.commentary,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = 0.8f)
            )
            data.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            if (data.status != SensorStatus.AVAILABLE) {
                Text(
                    text = when (data.status) {
                        SensorStatus.PAUSED -> stringResource(R.string.sensor_status_paused)
                        SensorStatus.NOT_AVAILABLE -> stringResource(R.string.sensor_status_unavailable)
                        SensorStatus.AVAILABLE -> stringResource(R.string.sensor_status_streaming)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}

private fun gpsCard(snapshot: GpsSnapshot, context: Context): DisasterSensorCardData {
    val measurement = if (
        snapshot.status == SensorStatus.AVAILABLE &&
        snapshot.latitude != null &&
        snapshot.longitude != null
    ) {
        context.getString(
            R.string.sensor_monitor_coordinates_format,
            snapshot.latitude,
            snapshot.longitude
        )
    } else {
        when (snapshot.reason) {
            GpsUnavailableReason.PERMISSION_REQUIRED ->
                context.getString(R.string.sensor_monitor_gps_measurement_permission_required)
            GpsUnavailableReason.GPS_DISABLED ->
                context.getString(R.string.sensor_monitor_gps_measurement_disabled)
            GpsUnavailableReason.GPS_NOT_SUPPORTED ->
                context.getString(R.string.sensor_monitor_gps_measurement_not_supported)
            GpsUnavailableReason.SEARCHING_SIGNAL ->
                context.getString(R.string.sensor_monitor_gps_measurement_searching)
            GpsUnavailableReason.UNKNOWN ->
                context.getString(R.string.sensor_monitor_gps_measurement_unavailable)
            null ->
                context.getString(R.string.sensor_monitor_waiting_data)
        }
    }

    if (snapshot.status != SensorStatus.AVAILABLE || snapshot.latitude == null || snapshot.longitude == null) {
        val commentary = when (snapshot.reason) {
            GpsUnavailableReason.PERMISSION_REQUIRED ->
                context.getString(R.string.sensor_monitor_gps_comment_permission_required)
            GpsUnavailableReason.GPS_DISABLED ->
                context.getString(R.string.sensor_monitor_gps_comment_disabled)
            GpsUnavailableReason.GPS_NOT_SUPPORTED ->
                context.getString(R.string.sensor_monitor_gps_comment_not_supported)
            GpsUnavailableReason.SEARCHING_SIGNAL ->
                context.getString(R.string.sensor_monitor_gps_comment_searching)
            GpsUnavailableReason.UNKNOWN ->
                context.getString(R.string.sensor_monitor_gps_comment_unavailable)
            null ->
                context.getString(R.string.sensor_monitor_gps_comment_waiting)
        }
        return DisasterSensorCardData(
            key = "gps",
            title = context.getString(R.string.sensor_monitor_gps_title),
            measurement = measurement,
            commentary = commentary,
            status = snapshot.status
        )
    }
    val accuracy = snapshot.accuracyMeters

    val detailParts = buildList {
        add(
            if (accuracy != null) {
                context.getString(R.string.sensor_monitor_gps_detail_accuracy_format, accuracy.roundToInt())
            } else {
                context.getString(R.string.sensor_monitor_gps_detail_accuracy_missing)
            }
        )
        snapshot.altitudeMeters?.let { altitude ->
            add(context.getString(R.string.sensor_monitor_gps_detail_altitude_format, altitude))
        }
        snapshot.speedMetersPerSecond?.let { speed ->
            add(context.getString(R.string.sensor_monitor_gps_detail_speed_format, speed * 3.6f))
        }
        add(
            context.getString(
                R.string.sensor_monitor_gps_detail_time_format,
                formatGpsLocalTime(snapshot.timestampMillis)
                    ?: context.getString(R.string.sensor_monitor_time_unavailable)
            )
        )
        formatGpsUtcTime(snapshot.timestampMillis)?.let { utcTime ->
            add(context.getString(R.string.sensor_monitor_gps_detail_utc_format, utcTime))
        }
    }

    return DisasterSensorCardData(
        key = "gps",
        title = context.getString(R.string.sensor_monitor_gps_title),
        measurement = measurement,
        commentary = context.getString(R.string.sensor_monitor_gps_comment_live),
        status = snapshot.status,
        detail = detailParts.joinToString(" | ")
    )
}

private fun pressureCard(state: SensorDisplayState?, context: Context): DisasterSensorCardData {
    val status = state?.status ?: SensorStatus.NOT_AVAILABLE
    val value = state?.formattedValue?.toFloatOrNull()
    val unit = state?.unit?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sensor_unit_barometer)
    val measurement = value?.let {
        context.getString(
            R.string.sensor_monitor_measurement_with_unit_one_decimal,
            it,
            unit
        )
    }
        ?: (state?.formattedValue?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sensor_monitor_waiting_data))

    if (status != SensorStatus.AVAILABLE || value == null) {
        return DisasterSensorCardData(
            key = "pressure",
            title = state?.title ?: context.getString(R.string.sensor_title_barometer),
            measurement = measurement,
            commentary = context.getString(R.string.sensor_monitor_pressure_comment_unavailable),
            status = status
        )
    }

    return DisasterSensorCardData(
        key = "pressure",
        title = state.title,
        measurement = measurement,
        commentary = context.getString(R.string.sensor_monitor_pressure_comment_live),
        status = status
    )
}

private fun accelerometerCard(state: SensorDisplayState?, context: Context): DisasterSensorCardData {
    val status = state?.status ?: SensorStatus.NOT_AVAILABLE
    val axes = state?.formattedValue
    val parsed = axes?.let { parseTriple(it) }
    val magnitude = parsed?.let { (x, y, z) -> sqrt(x * x + y * y + z * z) }
    val gForce = magnitude?.div(SensorManager.GRAVITY_EARTH)
    val measurement = when {
        gForce != null -> context.getString(R.string.sensor_monitor_measurement_g_force, gForce)
        axes != null -> axes
        else -> context.getString(R.string.sensor_monitor_waiting_data)
    }

    if (status != SensorStatus.AVAILABLE || gForce == null) {
        return DisasterSensorCardData(
            key = "accelerometer",
            title = state?.title ?: context.getString(R.string.sensor_title_accelerometer),
            measurement = measurement,
            commentary = context.getString(R.string.sensor_monitor_accelerometer_comment_unavailable),
            status = status,
            detail = axes?.let { context.getString(R.string.sensor_monitor_axes_values_format, it) }
        )
    }

    return DisasterSensorCardData(
        key = "accelerometer",
        title = state?.title ?: context.getString(R.string.sensor_title_accelerometer),
        measurement = measurement,
        commentary = context.getString(R.string.sensor_monitor_accelerometer_comment_live),
        status = status,
        detail = axes?.let { context.getString(R.string.sensor_monitor_axes_values_format, it) }
    )
}

private fun gyroscopeCard(state: SensorDisplayState?, context: Context): DisasterSensorCardData {
    val status = state?.status ?: SensorStatus.NOT_AVAILABLE
    val axes = state?.formattedValue
    val parsed = axes?.let { parseTriple(it) }
    val rotationSpeed = parsed?.let { (x, y, z) -> sqrt(x * x + y * y + z * z) }
    val measurement = rotationSpeed?.let {
        context.getString(R.string.sensor_monitor_measurement_rotation_speed, it)
    }
        ?: axes ?: context.getString(R.string.sensor_monitor_waiting_data)

    if (status != SensorStatus.AVAILABLE || rotationSpeed == null) {
        return DisasterSensorCardData(
            key = "gyroscope",
            title = state?.title ?: context.getString(R.string.sensor_title_gyroscope),
            measurement = measurement,
            commentary = context.getString(R.string.sensor_monitor_gyroscope_comment_unavailable),
            status = status,
            detail = axes?.let { context.getString(R.string.sensor_monitor_axes_values_format, it) }
        )
    }

    return DisasterSensorCardData(
        key = "gyroscope",
        title = state?.title ?: context.getString(R.string.sensor_title_gyroscope),
        measurement = measurement,
        commentary = context.getString(R.string.sensor_monitor_gyroscope_comment_live),
        status = status,
        detail = axes?.let { context.getString(R.string.sensor_monitor_axes_values_format, it) }
    )
}

private fun lightCard(state: SensorDisplayState?, context: Context): DisasterSensorCardData {
    val status = state?.status ?: SensorStatus.NOT_AVAILABLE
    val value = state?.formattedValue?.toFloatOrNull()
    val unit = state?.unit?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sensor_unit_light)
    val measurement = value?.let {
        context.getString(
            R.string.sensor_monitor_measurement_with_unit_one_decimal,
            it,
            unit
        )
    }
        ?: (state?.formattedValue?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sensor_monitor_waiting_data))

    if (status != SensorStatus.AVAILABLE || value == null) {
        return DisasterSensorCardData(
            key = "light",
            title = state?.title ?: context.getString(R.string.sensor_title_light),
            measurement = measurement,
            commentary = context.getString(R.string.sensor_monitor_light_comment_unavailable),
            status = status
        )
    }

    return DisasterSensorCardData(
        key = "light",
        title = state?.title ?: context.getString(R.string.sensor_title_light),
        measurement = measurement,
        commentary = context.getString(R.string.sensor_monitor_light_comment_live),
        status = status
    )
}

private fun batteryCard(snapshot: BatterySnapshot, context: Context): DisasterSensorCardData {
    val temperature = snapshot.temperatureCelsius
    val measurement = temperature?.let {
        context.getString(R.string.sensor_monitor_measurement_temperature_celsius, it)
    }
        ?: context.getString(R.string.sensor_monitor_waiting_data)

    if (snapshot.status != SensorStatus.AVAILABLE || temperature == null) {
        return DisasterSensorCardData(
            key = "battery",
            title = context.getString(R.string.sensor_monitor_battery_title),
            measurement = measurement,
            commentary = context.getString(R.string.sensor_monitor_battery_comment_unavailable),
            status = snapshot.status
        )
    }

    return DisasterSensorCardData(
        key = "battery",
        title = context.getString(R.string.sensor_monitor_battery_title),
        measurement = measurement,
        commentary = context.getString(R.string.sensor_monitor_battery_comment_live),
        status = snapshot.status
    )
}

private fun formatGpsLocalTime(timestampMillis: Long?): String? {
    val ts = timestampMillis?.takeIf { it > 0L } ?: return null
    return runCatching {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(ts))
    }.getOrNull()
}

private fun formatGpsUtcTime(timestampMillis: Long?): String? {
    val ts = timestampMillis?.takeIf { it > 0L } ?: return null
    return runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ts))
    }.getOrNull()
}

private fun Location.toGpsSnapshot(): GpsSnapshot {
    val validLatitude = latitude.takeIf { it.isFinite() }
    val validLongitude = longitude.takeIf { it.isFinite() }
    return GpsSnapshot(
        latitude = validLatitude,
        longitude = validLongitude,
        accuracyMeters = if (hasAccuracy()) accuracy.takeIf { it.isFinite() && it >= 0f } else null,
        altitudeMeters = if (hasAltitude()) altitude.takeIf { it.isFinite() } else null,
        speedMetersPerSecond = if (hasSpeed()) speed.takeIf { it.isFinite() && it >= 0f } else null,
        timestampMillis = time.takeIf { it > 0L },
        status = if (validLatitude != null && validLongitude != null) {
            SensorStatus.AVAILABLE
        } else {
            SensorStatus.PAUSED
        },
        reason = if (validLatitude != null && validLongitude != null) {
            null
        } else {
            GpsUnavailableReason.SEARCHING_SIGNAL
        }
    )
}

private fun Context.hasFineLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun parseTriple(value: String): Triple<Float, Float, Float>? {
    val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(value).mapNotNull { matchResult ->
        matchResult.value.toFloatOrNull()
    }.toList()
    return if (numbers.size >= 3) {
        Triple(numbers[0], numbers[1], numbers[2])
    } else {
        null
    }
}

private data class DisasterSensorCardData(
    val key: String,
    val title: String,
    val measurement: String,
    val commentary: String,
    val status: SensorStatus,
    val detail: String? = null
)

private data class BatterySnapshot(
    val temperatureCelsius: Float?,
    val status: SensorStatus
)

private enum class GpsUnavailableReason {
    PERMISSION_REQUIRED,
    GPS_DISABLED,
    GPS_NOT_SUPPORTED,
    SEARCHING_SIGNAL,
    UNKNOWN
}

private data class GpsSnapshot(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val timestampMillis: Long? = null,
    val status: SensorStatus = SensorStatus.PAUSED,
    val reason: GpsUnavailableReason? = null
)

@Preview(showBackground = true)
@Composable
private fun SensorToolScreenPreview() {
    val context = LocalContext.current
    val gpsPreviewDetail = listOf(
        context.getString(R.string.sensor_monitor_gps_detail_accuracy_format, 6),
        context.getString(R.string.sensor_monitor_gps_detail_time_format, "21.02.2026 14:32:11"),
        context.getString(R.string.sensor_monitor_gps_detail_utc_format, "11:32:11")
    ).joinToString(" | ")
    val accelerometerPreviewDetail = context.getString(
        R.string.sensor_monitor_axes_values_format,
        "x: 6.1, y: 0.4, z: 8.9"
    )
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                DisasterDashboardHeader(
                    isMonitoring = true,
                    gpsSnapshot = GpsSnapshot(
                        latitude = 41.01514,
                        longitude = 28.97953,
                        accuracyMeters = 6.2f,
                        timestampMillis = 1_708_516_123_000L,
                        status = SensorStatus.AVAILABLE
                    )
                )
                val previewCards = listOf(
                    DisasterSensorCardData(
                        key = "gps",
                        title = stringResource(R.string.sensor_monitor_gps_title),
                        measurement = context.getString(
                            R.string.sensor_monitor_coordinates_format,
                            41.01514,
                            28.97953
                        ),
                        commentary = stringResource(R.string.sensor_monitor_gps_comment_live),
                        status = SensorStatus.AVAILABLE,
                        detail = gpsPreviewDetail
                    ),
                    DisasterSensorCardData(
                        key = "pressure",
                        title = stringResource(R.string.sensor_title_barometer),
                        measurement = context.getString(
                            R.string.sensor_monitor_measurement_with_unit_one_decimal,
                            1012.4f,
                            stringResource(R.string.sensor_unit_barometer)
                        ),
                        commentary = stringResource(R.string.sensor_monitor_pressure_comment_live),
                        status = SensorStatus.AVAILABLE
                    ),
                    DisasterSensorCardData(
                        key = "accelerometer",
                        title = stringResource(R.string.sensor_title_accelerometer),
                        measurement = context.getString(
                            R.string.sensor_monitor_measurement_g_force,
                            1.12f
                        ),
                        commentary = stringResource(R.string.sensor_monitor_accelerometer_comment_live),
                        status = SensorStatus.AVAILABLE,
                        detail = accelerometerPreviewDetail
                    ),
                    DisasterSensorCardData(
                        key = "light",
                        title = stringResource(R.string.sensor_title_light),
                        measurement = context.getString(
                            R.string.sensor_monitor_measurement_with_unit_one_decimal,
                            2.5f,
                            stringResource(R.string.sensor_unit_light)
                        ),
                        commentary = stringResource(R.string.sensor_monitor_light_comment_live),
                        status = SensorStatus.AVAILABLE
                    ),
                    DisasterSensorCardData(
                        key = "battery",
                        title = stringResource(R.string.sensor_monitor_battery_title),
                        measurement = context.getString(
                            R.string.sensor_monitor_measurement_temperature_celsius,
                            45.5f
                        ),
                        commentary = stringResource(R.string.sensor_monitor_battery_comment_live),
                        status = SensorStatus.AVAILABLE
                    )
                )
                previewCards.forEach { card ->
                    DisasterSensorCard(data = card)
                }
            }
        }
    }
}
