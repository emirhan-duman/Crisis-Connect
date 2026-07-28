package com.auralis.crisisconnect.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sports
import androidx.compose.ui.graphics.vector.ImageVector
import com.auralis.crisisconnect.R

class ToolsMainScreenViewModel {
    data class ToolItem(
        @StringRes val title: Int,
        @StringRes val description: Int,
        val route: String,
        val icon: ImageVector? = null,
        @DrawableRes val drawableIcon: Int? = null
    )

    companion object {
        val DEFAULT_TOOLS = listOf(
            ToolItem(
                R.string.tool_crisis_sentinel_title,
                R.string.tool_crisis_sentinel_description,
                route = "crisis_sentinel",
                drawableIcon = R.drawable.ic_tool_crisis_sentinel_shine
            ),
            ToolItem(
                R.string.tool_metal_detector_title,
                R.string.tool_metal_detector_description,
                route = "metal_detector",
                drawableIcon = R.drawable.ic_tool_metal_detector
            ),
            ToolItem(
                R.string.tool_signal_finder_title,
                R.string.tool_signal_finder_description,
                route = "signal_finder",
                icon = Icons.Filled.Radio
            ),
            ToolItem(
                R.string.tool_whistle_title,
                R.string.tool_whistle_description,
                route = "whistle",
                icon = Icons.Filled.Sports
            ),
            ToolItem(
                R.string.tool_sensor_monitor_title,
                R.string.tool_sensor_monitor_description,
                route = "sensor_tool",
                icon = Icons.Filled.Sensors
            ),
            ToolItem(
                R.string.tool_offline_maps_title,
                R.string.tool_offline_maps_description,
                route = "offline_map",
                icon = Icons.Filled.Map
            ),
            ToolItem(
                R.string.tool_compass_title,
                R.string.tool_compass_description,
                route = "compass",
                icon = Icons.Filled.Explore
            ),
            ToolItem(
                R.string.tool_recent_disasters_title,
                R.string.tool_recent_disasters_description,
                route = "recent_disasters",
                drawableIcon = R.drawable.ic_disaster
            )
        )

        fun getVisibleTools(context: Context): List<ToolItem> {
            val capabilities = resolveToolDeviceCapabilities(context.applicationContext)
            return DEFAULT_TOOLS.filter { tool ->
                isToolSupported(tool.route, capabilities)
            }
        }
    }
}

internal data class ToolDeviceCapabilities(
    val hasRotationVector: Boolean,
    val hasGameRotationVector: Boolean,
    val hasAccelerometer: Boolean,
    val hasMagnetometer: Boolean
)

internal fun isToolSupported(
    route: String,
    capabilities: ToolDeviceCapabilities
): Boolean {
    return when (route) {
        "metal_detector" -> capabilities.hasMagnetometer
        "compass" -> {
            capabilities.hasRotationVector ||
                capabilities.hasGameRotationVector ||
                (capabilities.hasAccelerometer && capabilities.hasMagnetometer)
        }
        else -> true
    }
}

private fun resolveToolDeviceCapabilities(context: Context): ToolDeviceCapabilities {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    return ToolDeviceCapabilities(
        hasRotationVector = sensorManager.hasSensor(Sensor.TYPE_ROTATION_VECTOR),
        hasGameRotationVector = sensorManager.hasSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
        hasAccelerometer = sensorManager.hasSensor(Sensor.TYPE_ACCELEROMETER),
        hasMagnetometer = sensorManager.hasSensor(Sensor.TYPE_MAGNETIC_FIELD)
    )
}

private fun SensorManager?.hasSensor(sensorType: Int): Boolean {
    return this?.getDefaultSensor(sensorType) != null
}
