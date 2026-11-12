package com.auralis.crisisconnect.screens.settings

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.GroupedSettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BluetoothCapabilitiesCard() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshVersion by remember { mutableIntStateOf(0) }
    var capabilities by remember { mutableStateOf(BluetoothCapabilities()) }
    var refreshing by remember { mutableStateOf(true) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        refreshVersion++
    }

    LaunchedEffect(appContext, refreshVersion) {
        refreshing = true
        capabilities = withContext(Dispatchers.IO) { readBluetoothCapabilities(appContext) }
        refreshing = false
    }
    DisposableEffect(appContext, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refreshVersion++ }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            appContext.unregisterReceiver(receiver)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BluetoothCapabilitiesContent(
        capabilities = capabilities,
        refreshing = refreshing,
        onRefresh = { refreshVersion++ },
        onResolveAvailability = {
            val intent = when (capabilities.availability) {
                BluetoothCapabilityAvailability.PERMISSION_REQUIRED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !permissionDenied) {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        null
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    }
                }
                BluetoothCapabilityAvailability.BLUETOOTH_OFF -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                else -> null
            }
            intent?.let { runCatching { context.startActivity(it) } }
        },
        permissionDenied = permissionDenied,
    )
}

@Composable
internal fun BluetoothCapabilitiesContent(
    capabilities: BluetoothCapabilities,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onResolveAvailability: () -> Unit,
    permissionDenied: Boolean = false,
) {
    GroupedSettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Default.Bluetooth, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.bluetooth_capabilities_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.bluetooth_capabilities_device, Build.MODEL, Build.VERSION.RELEASE),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.bluetooth_capabilities_refresh))
                }
            }
        }

        if (capabilities.availability != BluetoothCapabilityAvailability.READY) {
            val message = when (capabilities.availability) {
                BluetoothCapabilityAvailability.LOADING -> R.string.bluetooth_capabilities_loading
                BluetoothCapabilityAvailability.BLUETOOTH_OFF -> R.string.bluetooth_capabilities_off
                BluetoothCapabilityAvailability.PERMISSION_REQUIRED -> R.string.bluetooth_capabilities_permission
                BluetoothCapabilityAvailability.NO_ADAPTER -> R.string.bluetooth_capabilities_no_adapter
                else -> R.string.bluetooth_capabilities_unavailable
            }
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                    val actionLabel = when (capabilities.availability) {
                        BluetoothCapabilityAvailability.BLUETOOTH_OFF -> R.string.bluetooth_capabilities_settings
                        BluetoothCapabilityAvailability.PERMISSION_REQUIRED -> if (permissionDenied) {
                            R.string.bluetooth_capabilities_app_settings
                        } else R.string.bluetooth_capabilities_grant
                        else -> null
                    }
                    actionLabel?.let {
                        TextButton(onClick = onResolveAvailability) { Text(stringResource(it)) }
                    }
                }
            }
        }

        CodedPhySpotlight(capabilities[BluetoothCapability.CODED_PHY])
        CapabilityRow(R.string.bluetooth_capabilities_le, R.string.bluetooth_capabilities_le_description, capabilities[BluetoothCapability.LOW_ENERGY])
        CapabilityRow(R.string.bluetooth_capabilities_2m, R.string.bluetooth_capabilities_2m_description, capabilities[BluetoothCapability.PHY_2M])
        CapabilityRow(
            R.string.bluetooth_capabilities_extended, R.string.bluetooth_capabilities_extended_description,
            capabilities[BluetoothCapability.EXTENDED_ADVERTISING],
            detail = capabilities.maximumAdvertisingBytes?.let { stringResource(R.string.bluetooth_capabilities_max_bytes, it) },
        )
        CapabilityRow(R.string.bluetooth_capabilities_periodic, R.string.bluetooth_capabilities_periodic_description, capabilities[BluetoothCapability.PERIODIC_ADVERTISING])
        CapabilityRow(R.string.bluetooth_capabilities_multiple, R.string.bluetooth_capabilities_multiple_description, capabilities[BluetoothCapability.MULTIPLE_ADVERTISEMENTS])
        CapabilityRow(R.string.bluetooth_capabilities_filtering, R.string.bluetooth_capabilities_filtering_description, capabilities[BluetoothCapability.OFFLOADED_FILTERING])
        CapabilityRow(R.string.bluetooth_capabilities_batching, R.string.bluetooth_capabilities_batching_description, capabilities[BluetoothCapability.OFFLOADED_BATCHING])

        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Text(stringResource(R.string.bluetooth_capabilities_api_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.bluetooth_capabilities_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CodedPhySpotlight(supported: Boolean?) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
            .background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f), MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f))),
                RoundedCornerShape(16.dp),
            ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.bluetooth_capabilities_long_range), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.bluetooth_capabilities_coded), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        CapabilityStatus(supported)
        Text(stringResource(R.string.bluetooth_capabilities_coded_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CodingScheme(R.string.bluetooth_capabilities_s8, R.string.bluetooth_capabilities_s8_description)
            CodingScheme(R.string.bluetooth_capabilities_s2, R.string.bluetooth_capabilities_s2_description)
        }
        Text(stringResource(R.string.bluetooth_capabilities_coding_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodingScheme(title: Int, description: Int) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CapabilityRow(title: Int, description: Int, supported: Boolean?, detail: String? = null) {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            CapabilityStatus(supported)
        }
        Text(detail ?: stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CapabilityStatus(supported: Boolean?) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = if (supported == true) {
        if (isDark) Color(0xFF8BDAB5) else Color(0xFF176B49)
    } else MaterialTheme.colorScheme.onSurfaceVariant
    val label = when (supported) {
        true -> R.string.bluetooth_capabilities_supported
        false -> R.string.bluetooth_capabilities_unsupported
        null -> R.string.bluetooth_capabilities_unknown
    }
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.1f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(
                if (supported == true) Icons.Default.CheckCircle else if (supported == false) Icons.Default.RemoveCircleOutline else Icons.Default.Info,
                contentDescription = null, modifier = Modifier.size(14.dp), tint = tint,
            )
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Medium)
        }
    }
}
