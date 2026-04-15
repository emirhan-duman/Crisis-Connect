package com.auralis.crisisconnect.ui.tools.offline

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.screens.Tools.OfflineRegionUiModel
import java.util.Date

/**
 * Bottom sheet content that lists all downloaded or in-progress offline regions.
 */
@Composable
fun OfflineRegionListScreen(
    regions: List<OfflineRegionUiModel>,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onViewOnMap: (OfflineRegionUiModel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val renameState = remember { mutableStateOf<OfflineRegionUiModel?>(null) }
    val deleteState = remember { mutableStateOf<OfflineRegionUiModel?>(null) }
    val sortedRegions = remember(regions) { regions.sortedByDescending { it.createdAtMillis } }
    val readyCount = regions.count { it.status == OfflineRegionStatus.Complete }
    val activeCount = regions.count { it.status == OfflineRegionStatus.Downloading && !it.isPaused }
    val downloadedBytes = regions.sumOf { it.bytesDownloaded.coerceAtLeast(0L) }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.offline_regions_title),
                style = MaterialTheme.typography.titleLarge
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(id = R.string.close)
                )
            }
        }
        Text(
            text = stringResource(
                id = R.string.offline_regions_summary,
                regions.size,
                readyCount,
                activeCount
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(
                id = R.string.offline_regions_storage_used,
                Formatter.formatShortFileSize(context, downloadedBytes)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (regions.isEmpty()) {
            Text(
                text = stringResource(id = R.string.offline_regions_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                items(sortedRegions, key = { it.localId }) { region ->
                    OfflineRegionRow(
                        region = region,
                        onPause = onPause,
                        onResume = onResume,
                        onDelete = { deleteState.value = region },
                        onRename = { renameState.value = region },
                        onViewOnMap = { onViewOnMap(region) }
                    )
                }
            }
        }
    }

    val renameTarget = renameState.value
    if (renameTarget != null && renameTarget.regionId != null) {
        val textState = remember(renameTarget.regionId) { mutableStateOf(renameTarget.name) }
        AlertDialog(
            onDismissRequest = { renameState.value = null },
            title = { Text(text = stringResource(id = R.string.offline_region_rename_title)) },
            text = {
                OutlinedTextField(
                    value = textState.value,
                    onValueChange = { textState.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(id = R.string.offline_region_rename)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = textState.value.trim()
                    if (name.isNotEmpty()) {
                        onRename(renameTarget.regionId, name)
                    }
                    renameState.value = null
                }) {
                    Text(text = stringResource(id = R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameState.value = null }) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }

    val deleteTarget = deleteState.value
    if (deleteTarget != null && deleteTarget.regionId != null) {
        AlertDialog(
            onDismissRequest = { deleteState.value = null },
            title = { Text(text = stringResource(id = R.string.offline_region_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        id = R.string.offline_region_delete_message,
                        deleteTarget.name
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(deleteTarget.regionId)
                        deleteState.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(text = stringResource(id = R.string.offline_region_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteState.value = null }) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun OfflineRegionRow(
    region: OfflineRegionUiModel,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onViewOnMap: () -> Unit,
) {
    val context = LocalContext.current
    val isDeleted = region.status == OfflineRegionStatus.Deleted
    val id = region.regionId
    val canPause = id != null && region.status == OfflineRegionStatus.Downloading && !region.isPaused
    val canResume = id != null && (region.isPaused || region.status == OfflineRegionStatus.Idle || region.status == OfflineRegionStatus.Failed)
    val showView = id != null && region.status == OfflineRegionStatus.Complete
    val statusLabel = if (region.isPaused) {
        stringResource(id = R.string.offline_region_status_idle)
    } else {
        statusText(region.status)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = region.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isDeleted) {
                    IconButton(onClick = onRename) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(id = R.string.offline_region_rename)
                        )
                    }
                }
            }

            val progressPercent = (region.progress * 100).toInt().coerceIn(0, 100)
            LinearProgressIndicator(progress = region.progress, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.offline_region_progress_percentage, progressPercent),
                    style = MaterialTheme.typography.bodySmall
                )
                val etaText = region.etaSeconds?.let { formatEta(context, it) }
                if (etaText != null) {
                    Text(text = etaText, style = MaterialTheme.typography.bodySmall)
                }
            }

            val downloaded = Formatter.formatShortFileSize(context, region.bytesDownloaded.coerceAtLeast(0L))
            val required = if (region.bytesRequired > 0) {
                Formatter.formatShortFileSize(context, region.bytesRequired)
            } else {
                null
            }
            Text(
                text = if (required != null) {
                    stringResource(id = R.string.offline_region_size_with_total, downloaded, required)
                } else {
                    stringResource(id = R.string.offline_region_size_downloaded, downloaded)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    id = R.string.offline_region_created,
                    DateFormat.getDateFormat(context).format(Date(region.createdAtMillis))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (id != null && !isDeleted) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (showView) {
                        FilledTonalButton(
                            onClick = onViewOnMap,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Map, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(id = R.string.offline_region_view_on_map))
                        }
                    }
                    if (canPause) {
                        OutlinedButton(
                            onClick = { onPause(id) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(id = R.string.offline_region_pause))
                        }
                    }
                    if (canResume) {
                        OutlinedButton(
                            onClick = { onResume(id) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(id = R.string.offline_region_resume))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(id = R.string.offline_region_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: OfflineRegionStatus): String {
    val res = when (status) {
        OfflineRegionStatus.Downloading -> R.string.offline_region_status_downloading
        OfflineRegionStatus.Complete -> R.string.offline_region_status_complete
        OfflineRegionStatus.Failed -> R.string.offline_region_status_failed
        OfflineRegionStatus.Idle -> R.string.offline_region_status_idle
        OfflineRegionStatus.Deleted -> R.string.offline_region_status_deleted
    }
    return stringResource(id = res)
}

private fun formatEta(context: android.content.Context, seconds: Long): String {
    val minutes = (seconds / 60).coerceAtLeast(1)
    return if (minutes < 60) {
        context.getString(R.string.offline_map_eta_minutes, minutes)
    } else {
        val hours = (minutes / 60).coerceAtLeast(1)
        context.getString(R.string.offline_map_eta_hours, hours)
    }
}
