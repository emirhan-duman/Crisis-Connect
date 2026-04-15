package com.auralis.crisisconnect.ui.tools.offline

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Tools.OfflineRegionEditorState
import kotlin.math.roundToInt

/**
 * Bottom sheet content that lets the user configure a new offline region before downloading.
 */
@Composable
fun OfflineRegionEditorSheet(
    state: OfflineRegionEditorState,
    onNameChange: (String) -> Unit,
    onMinZoomChange: (Float) -> Unit,
    onMaxZoomChange: (Float) -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val estimatedSize = if (state.estimatedBytes > 0) {
        Formatter.formatShortFileSize(context, state.estimatedBytes)
    } else {
        stringResource(id = R.string.offline_region_size_unknown)
    }
    val estimatedTimeMinutes = if (state.estimatedBytes > 0) {
        (state.estimatedBytes / ESTIMATED_THROUGHPUT_BYTES_PER_MINUTE.toFloat()).roundToInt().coerceAtLeast(1)
    } else {
        0
    }
    val hasBounds = state.bounds != null
    val boundsText = if (hasBounds) {
        stringResource(
            id = R.string.offline_map_editor_bounds,
            state.bounds?.latitudeNorth ?: 0.0,
            state.bounds?.longitudeWest ?: 0.0,
            state.bounds?.latitudeSouth ?: 0.0,
            state.bounds?.longitudeEast ?: 0.0,
        )
    } else {
        stringResource(id = R.string.offline_map_editor_bounds_unavailable)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(id = R.string.offline_map_editor_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(id = R.string.offline_map_editor_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(text = stringResource(id = R.string.offline_map_editor_name_label)) },
            modifier = Modifier.fillMaxWidth()
        )
        Column {
            val maxForMin = maxOf(state.maxZoom.toFloat() - 0.1f, MIN_ZOOM)
            Text(text = stringResource(id = R.string.offline_map_editor_min_zoom, state.minZoom))
            Slider(
                value = state.minZoom.toFloat(),
                onValueChange = onMinZoomChange,
                valueRange = MIN_ZOOM..maxForMin,
            )
        }
        Column {
            val minForMax = minOf(state.minZoom.toFloat() + 0.1f, MAX_ZOOM)
            Text(text = stringResource(id = R.string.offline_map_editor_max_zoom, state.maxZoom))
            Slider(
                value = state.maxZoom.toFloat(),
                onValueChange = onMaxZoomChange,
                valueRange = minForMax..MAX_ZOOM,
            )
        }

        Text(
            text = boundsText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasBounds) {
            Text(
                text = stringResource(id = R.string.offline_map_editor_wait_for_bounds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.offline_map_estimated_size, estimatedSize),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (estimatedTimeMinutes > 0) {
                    Text(
                        text = stringResource(id = R.string.offline_map_estimated_time, estimatedTimeMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = onDownload,
            enabled = hasBounds,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.offline_map_start_download))
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.cancel))
        }
    }
}

private const val MIN_ZOOM = 5f
private const val MAX_ZOOM = 18f
private const val ESTIMATED_THROUGHPUT_BYTES_PER_MINUTE = 15_000_000L
