package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalFinderScreen(navController: NavController) {
    val viewModel: SignalFinderViewModel = viewModel()
    val context = LocalContext.current
    val cellSignals by viewModel.cells.collectAsState()
    val wifiSignals by viewModel.wifi.collectAsState()
    val bluetoothSignals by viewModel.bluetooth.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSignals()
    }
    val refreshOrRequestPermissions = {
        val missingPermissions = context.missingSignalFinderPermissions()
        if (missingPermissions.isNotEmpty() && !context.hasSignalFinderLocationPermission()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            viewModel.refreshSignals()
        }
    }

    LaunchedEffect(Unit) {
        refreshOrRequestPermissions()
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_signal_finder_title,
                onNavigateBack = { navController.popBackStack() }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.signal_finder_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(onClick = refreshOrRequestPermissions, enabled = !isRefreshing) {
                            Text(text = stringResource(R.string.signal_finder_refresh))
                        }
                        if (lastUpdated != null) {
                            val formattedTime = DateFormat.getTimeInstance(DateFormat.SHORT)
                                .format(Date(lastUpdated!!))
                            AssistChip(onClick = {}, label = {
                                Text(stringResource(R.string.signal_finder_last_updated, formattedTime))
                            })
                        }
                    }
                    if (errorMessage != null) {
                        Text(
                            text = stringResource(errorMessage!!),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start
                        )
                    }
                    if (isRefreshing) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.signal_finder_section_cells)) }
            if (cellSignals.isEmpty()) {
                item {
                    EmptyState(text = stringResource(R.string.signal_finder_no_cells))
                }
            } else {
                items(cellSignals) { cell ->
                    SignalCard {
                        Text(
                            text = stringResource(
                                R.string.signal_finder_cell_title,
                                cell.technology,
                                cell.cellId ?: stringResource(R.string.signal_unknown)
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AssistChip(onClick = {}, label = {
                                Text(
                                    cell.strengthDbm?.let {
                                        stringResource(R.string.signal_strength_dbm, it)
                                    } ?: stringResource(R.string.signal_unknown_strength)
                                )
                            })
                            AssistChip(onClick = {}, label = {
                                Text(
                                    cell.level?.let {
                                        stringResource(R.string.signal_level, it)
                                    } ?: stringResource(R.string.signal_unknown_level)
                                )
                            })
                            AssistChip(onClick = {}, label = {
                                Text(
                                    if (cell.isRegistered) stringResource(R.string.signal_registered)
                                    else stringResource(R.string.signal_not_registered)
                                )
                            })
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!cell.operator.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.signal_operator_format, cell.operator),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!cell.additionalInfo.isNullOrBlank()) {
                            Text(
                                text = cell.additionalInfo,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.signal_finder_section_wifi)) }
            if (wifiSignals.isEmpty()) {
                item { EmptyState(text = stringResource(R.string.signal_finder_no_wifi)) }
            } else {
                items(wifiSignals) { wifi ->
                    SignalCard {
                        Text(
                            text = wifi.ssid,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.signal_wifi_bssid, wifi.bssid),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = {
                                Text(stringResource(R.string.signal_strength_dbm, wifi.level))
                            })
                            AssistChip(onClick = {}, label = {
                                Text(stringResource(R.string.signal_wifi_frequency, wifi.frequencyMhz))
                            })
                            AssistChip(onClick = {}, label = {
                                Text(stringResource(R.string.signal_wifi_channel_width, wifi.channelWidth))
                            })
                        }
                        if (wifi.capabilities.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.signal_wifi_security, wifi.capabilities),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.signal_finder_section_bluetooth)) }
            if (bluetoothSignals.isEmpty()) {
                item { EmptyState(text = stringResource(R.string.signal_finder_no_bluetooth)) }
            } else {
                items(bluetoothSignals) { bt ->
                    SignalCard {
                        Text(
                            text = bt.name ?: stringResource(R.string.signal_bluetooth_unknown_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.signal_bluetooth_address, bt.address),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (bt.rssi != null) {
                            Spacer(Modifier.height(6.dp))
                            AssistChip(onClick = {}, label = {
                                Text(stringResource(R.string.signal_strength_dbm, bt.rssi))
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SignalCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

private fun Context.missingSignalFinderPermissions(): List<String> {
    return requiredSignalFinderPermissions().filter { permission ->
        ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.hasSignalFinderLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun requiredSignalFinderPermissions(): List<String> {
    return buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.distinct()
}
