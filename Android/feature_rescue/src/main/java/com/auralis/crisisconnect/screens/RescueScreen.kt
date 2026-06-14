package com.auralis.crisisconnect.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ai.CrisisSentinelResponse
import com.auralis.crisisconnect.ai.CrisisSentinelResponseSource
import com.auralis.crisisconnect.ai.CrisisSentinelUserMode
import com.auralis.crisisconnect.ui.components.AppBottomBar
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueScreen(
    navController: NavController,
    onBackPressed: () -> Unit = { navController.popBackStack() },
    onBottomBarRouteSelected: ((String) -> Unit)? = null,
    onConversationSelected: ((String) -> Unit)? = null
) {
    val viewModel: RescueScreenViewModel = viewModel()
    val settingsViewModel: RescueSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val settingsUiState by settingsViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scanPermissions = remember { requiredRescueScanPermissions() }
    val meshPermissions = remember { requiredRescueMeshPermissions() }
    val openWifiSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.rescue_mesh_error_wifi_disabled),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val openAppPermissionSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.rescue_error_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val scanPermissionRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val missingPermissions = context.missingPermissions(scanPermissions)
        if (missingPermissions.isEmpty()) {
            viewModel.refresh()
        } else {
            openAppPermissionSettings()
        }
    }
    val meshPermissionRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val missingPermissions = context.missingPermissions(meshPermissions)
        if (missingPermissions.isEmpty()) {
            viewModel.setMeshEnabled(true)
        } else {
            openAppPermissionSettings()
        }
    }
    val requestScanPermissions: () -> Unit = {
        val missingPermissions = context.missingPermissions(scanPermissions)
        if (missingPermissions.isEmpty()) {
            viewModel.refresh()
        } else {
            scanPermissionRequestLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    val requestMeshPermissions: () -> Unit = {
        val missingPermissions = context.missingPermissions(meshPermissions)
        if (missingPermissions.isEmpty()) {
            viewModel.setMeshEnabled(true)
        } else {
            meshPermissionRequestLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    LaunchedEffect(
        settingsUiState.isLoaded,
        settingsUiState.autoStartScanning,
        settingsUiState.autoConnectToScannedBroadcasts,
    ) {
        if (!settingsUiState.isLoaded) return@LaunchedEffect
        viewModel.setAutoConnectToScannedBroadcasts(settingsUiState.autoConnectToScannedBroadcasts)
        if (settingsUiState.autoStartScanning) {
            viewModel.startScanning()
        } else {
            viewModel.stopScanning()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshFieldAiAccess()
    }
    LaunchedEffect(uiState.peerAuthEvent?.eventId) {
        val event = uiState.peerAuthEvent ?: return@LaunchedEffect
        Toast.makeText(
            context,
            context.getString(
                R.string.rescue_mesh_unauthorized_peer_attempt,
                event.peerLabel,
            ),
            Toast.LENGTH_LONG,
        ).show()
        viewModel.consumeMeshPeerAuthEvent(event)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopScanning() }
    }

    val activeStatus = stringResource(R.string.rescue_status_active)
    val acknowledgedStatus = stringResource(R.string.rescue_status_acknowledged)
    val clearedStatus = stringResource(R.string.rescue_status_cleared)
    val visibleBroadcasts = if (settingsUiState.showOnlyActiveSignals) {
        uiState.broadcasts.filter { it.status == activeStatus }
    } else {
        uiState.broadcasts
    }
    val showMeshStatusCard =
        uiState.canControlMesh && (uiState.isMeshEnabled || uiState.isMeshBusy || uiState.meshErrorMessage != null)

    val totalSignals = uiState.broadcasts.size
    val activeSignals = uiState.broadcasts.count { it.status == activeStatus }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.rescue_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate("rescue_settings") {
                                launchSingleTop = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.rescue_settings_open_content_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            AppBottomBar(
                navController = navController,
                onRouteSelected = onBottomBarRouteSelected
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                RescueMissionOverview(
                    isScanning = uiState.isScanning,
                    activeSignals = activeSignals,
                    totalSignals = totalSignals,
                    lastUpdated = uiState.lastUpdated,
                    canControlMesh = uiState.canControlMesh,
                    isMeshEnabled = uiState.isMeshEnabled,
                    isMeshBusy = uiState.isMeshBusy,
                    onMeshToggle = { enabled ->
                        if (enabled) {
                            requestMeshPermissions()
                        } else {
                            viewModel.setMeshEnabled(false)
                        }
                    },
                    onRefresh = {
                        if (uiState.isScanning) {
                            viewModel.stopScanning()
                        } else {
                            requestScanPermissions()
                        }
                    }
                )
            }
            item {
                RescueFieldAiCard(
                    canUseFieldAi = uiState.canUseFieldAi,
                    mode = uiState.fieldAiMode,
                    certificateRole = uiState.fieldAiCertificateRole,
                    prompt = uiState.fieldAiPrompt,
                    response = uiState.fieldAiResponse,
                    isGenerating = uiState.isFieldAiGenerating,
                    isModelReady = uiState.isFieldAiModelReady,
                    messageId = uiState.fieldAiMessage,
                    onPromptChanged = viewModel::onFieldAiPromptChanged,
                    onDraftFromSignals = viewModel::generateFieldAiFromSignals,
                    onAsk = viewModel::generateFieldAiFromPrompt,
                    onCancel = viewModel::cancelFieldAiGeneration,
                )
            }
            item {
                AnimatedVisibility(
                    visible = showMeshStatusCard,
                    enter = fadeIn(animationSpec = tween(durationMillis = 260)) +
                        expandVertically(animationSpec = tween(durationMillis = 320)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                        shrinkVertically(animationSpec = tween(durationMillis = 220)),
                ) {
                    RescueMeshStatusCard(
                        isMeshEnabled = uiState.isMeshEnabled,
                        isMeshBusy = uiState.isMeshBusy,
                        connectedPeerCount = uiState.meshConnectedPeerCount,
                        errorMessageId = uiState.meshErrorMessage,
                        onOpenWifiSettings = openWifiSettings,
                        onResolvePermissions = requestMeshPermissions,
                    )
                }
            }

            uiState.errorMessage?.let { messageId ->
                item {
                    RescueErrorCard(
                        messageId = messageId,
                        actionLabel = if (messageId == R.string.rescue_error_permission_required) {
                            stringResource(R.string.settings_missing_permissions_request_button)
                        } else {
                            null
                        },
                        onAction = if (messageId == R.string.rescue_error_permission_required) {
                            requestScanPermissions
                        } else {
                            null
                        }
                    )
                }
            }

            if (!showMeshStatusCard) {
                uiState.meshErrorMessage?.let { messageId ->
                    item {
                        RescueErrorCard(
                            messageId = messageId,
                            actionLabel = when (messageId) {
                                R.string.rescue_mesh_error_wifi_disabled -> {
                                    stringResource(R.string.rescue_open_wifi_settings)
                                }

                                R.string.rescue_mesh_error_permission_required -> {
                                    stringResource(R.string.settings_missing_permissions_request_button)
                                }

                                else -> null
                            },
                            onAction = when (messageId) {
                                R.string.rescue_mesh_error_wifi_disabled -> openWifiSettings
                                R.string.rescue_mesh_error_permission_required -> requestMeshPermissions
                                else -> null
                            }
                        )
                    }
                }
            }

            if (visibleBroadcasts.isEmpty()) {
                item {
                    RescueEmptyState()
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.rescue_signal_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                items(visibleBroadcasts, key = { it.sessionCode }) { broadcast ->
                    val lastSeenText = remember(broadcast.lastSeen) {
                        DateUtils.getRelativeTimeSpanString(
                            broadcast.lastSeen,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    }
                    val hasAddress = broadcast.address.isNotBlank()
                    val sessionCode = broadcast.sessionCode
                    val encodedCode = remember(sessionCode) {
                        Uri.encode(sessionCode)
                    }
                    RescueSignalCard(
                        broadcast = broadcast,
                        lastSeenText = lastSeenText,
                        activeStatus = activeStatus,
                        acknowledgedStatus = acknowledgedStatus,
                        clearedStatus = clearedStatus,
                        showAddress = settingsUiState.showDeviceAddress,
                        onClick = if (hasAddress) {
                            {
                                if (onConversationSelected != null) {
                                    onConversationSelected(sessionCode)
                                } else {
                                    val route = if (sessionCode.startsWith("ble:", ignoreCase = true)) {
                                        "ble_chat/$encodedCode"
                                    } else {
                                        "chat/$encodedCode"
                                    }
                                    navController.navigate(route)
                                }
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RescueMeshToggleAction(
    isMeshEnabled: Boolean,
    isMeshBusy: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.rescue_mesh_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isMeshBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            // Always visible so authorities can find it; disabled until the device is a verified
            // admin/fieldteam (canControlMesh), matching the iOS rescue-screen toggle.
            Switch(
                checked = isMeshEnabled,
                onCheckedChange = onToggle,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun RescueErrorCard(
    messageId: Int,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(id = messageId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun RescueFieldAiCard(
    canUseFieldAi: Boolean,
    mode: CrisisSentinelUserMode,
    certificateRole: String?,
    prompt: String,
    response: CrisisSentinelResponse?,
    isGenerating: Boolean,
    isModelReady: Boolean,
    messageId: Int?,
    onPromptChanged: (String) -> Unit,
    onDraftFromSignals: () -> Unit,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rescue_ai_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.rescue_ai_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RescueAiPill(
                    text = stringResource(R.string.rescue_ai_role_format, stringResource(mode.labelRes())),
                    isPrimary = true,
                    modifier = Modifier.weight(1f)
                )
                RescueAiPill(
                    text = if (isModelReady) {
                        stringResource(R.string.crisis_sentinel_model_active)
                    } else {
                        stringResource(R.string.crisis_sentinel_source_offline_rules)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            if (!canUseFieldAi) {
                Text(
                    text = stringResource(R.string.rescue_ai_requires_role),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }

            certificateRole?.takeIf { it.isNotBlank() }?.let { role ->
                Text(
                    text = stringResource(R.string.rescue_ai_certificate_role_format, role),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChanged,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating,
                minLines = 2,
                maxLines = 5,
                label = { Text(text = stringResource(R.string.rescue_ai_prompt_label)) },
                placeholder = { Text(text = stringResource(R.string.rescue_ai_prompt_placeholder)) },
                shape = RoundedCornerShape(14.dp)
            )

            messageId?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onDraftFromSignals,
                    enabled = !isGenerating,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rescue_ai_draft_from_signals),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = onAsk,
                    enabled = !isGenerating,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(text = stringResource(R.string.crisis_sentinel_ask))
                }
            }

            if (isGenerating) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.crisis_sentinel_generation_working),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCancel) {
                            Text(text = stringResource(R.string.crisis_sentinel_generation_cancel))
                        }
                    }
                }
            }

            response?.let {
                RescueAiResponseBlock(response = it)
            }
        }
    }
}

@Composable
private fun RescueAiPill(
    text: String,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier
) {
    val container = if (isPrimary) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (isPrimary) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = container,
        border = BorderStroke(
            width = 1.dp,
            color = content.copy(alpha = 0.12f)
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RescueAiResponseBlock(response: CrisisSentinelResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.crisis_sentinel_response_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(response.source.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = response.answer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (response.safetyNotices.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.rescue_ai_safety_format,
                    response.safetyNotices.take(2).joinToString(" • ")
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (response.followUpQuestions.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.rescue_ai_followup_format,
                    response.followUpQuestions.take(2).joinToString(" • ")
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun CrisisSentinelUserMode.labelRes(): Int = when (this) {
    CrisisSentinelUserMode.Public -> R.string.crisis_sentinel_mode_public
    CrisisSentinelUserMode.FieldTeam -> R.string.crisis_sentinel_mode_field
    CrisisSentinelUserMode.Coordinator -> R.string.crisis_sentinel_mode_coordinator
}

private fun CrisisSentinelResponseSource.labelRes(): Int = when (this) {
    CrisisSentinelResponseSource.LocalModel -> R.string.crisis_sentinel_source_local_model
    CrisisSentinelResponseSource.OfflineRules -> R.string.crisis_sentinel_source_offline_rules
}

@Composable
private fun RescueMissionOverview(
    isScanning: Boolean,
    activeSignals: Int,
    totalSignals: Int,
    lastUpdated: Long?,
    canControlMesh: Boolean,
    isMeshEnabled: Boolean,
    isMeshBusy: Boolean,
    onMeshToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit
) {
    val statusTint = if (isScanning) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val refreshIconTransition = rememberInfiniteTransition(label = "rescue_refresh_icon")
    val refreshIconRotation by refreshIconTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rescue_refresh_rotation"
    )
    val refreshButtonLabel = if (isScanning) {
        stringResource(R.string.rescue_overview_scanning_label)
    } else {
        stringResource(R.string.rescue_overview_refresh_label)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rescue_overview_heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.rescue_overview_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                RescueMeshToggleAction(
                    isMeshEnabled = isMeshEnabled,
                    isMeshBusy = isMeshBusy,
                    enabled = canControlMesh,
                    onToggle = onMeshToggle,
                    modifier = Modifier
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RescueStatPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.rescue_overview_active_label),
                    value = activeSignals.toString()
                )
                RescueStatPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.rescue_overview_total_label),
                    value = totalSignals.toString()
                )
            }

            lastUpdated?.let { timestamp ->
                val formattedTime = remember(timestamp) {
                    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
                    formatter.format(Date(timestamp))
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusTint.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = stringResource(R.string.rescue_last_refresh_format, formattedTime),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.rescue_overview_deployment_status),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    textAlign = TextAlign.Start
                )
                FilledTonalButton(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                rotationZ = if (isScanning) refreshIconRotation else 0f
                            }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = refreshButtonLabel)
                }
            }
        }
    }
}

@Composable
private fun RescueMeshStatusCard(
    isMeshEnabled: Boolean,
    isMeshBusy: Boolean,
    connectedPeerCount: Int,
    errorMessageId: Int?,
    onOpenWifiSettings: (() -> Unit)? = null,
    onResolvePermissions: (() -> Unit)? = null,
) {
    val statusTone = when {
        isMeshEnabled -> MeshStatusTone.Active
        isMeshBusy -> MeshStatusTone.Starting
        else -> MeshStatusTone.Alert
    }
    val statusLabel = when (statusTone) {
        MeshStatusTone.Active -> stringResource(R.string.rescue_mesh_status_active)
        MeshStatusTone.Starting -> stringResource(R.string.rescue_mesh_status_starting)
        MeshStatusTone.Alert -> stringResource(R.string.rescue_mesh_status_inactive)
    }
    val accentTarget = when (statusTone) {
        MeshStatusTone.Active -> Color(0xFF24A464)
        MeshStatusTone.Starting -> Color(0xFFE3A91A)
        MeshStatusTone.Alert -> Color(0xFFE24B4B)
    }
    val accentColor by animateColorAsState(
        targetValue = accentTarget,
        animationSpec = tween(durationMillis = 320),
        label = "mesh_status_accent"
    )
    val progressFill by animateFloatAsState(
        targetValue = when (statusTone) {
            MeshStatusTone.Active -> 1f
            MeshStatusTone.Starting -> 0.58f
            MeshStatusTone.Alert -> 0.2f
        },
        animationSpec = tween(durationMillis = 380),
        label = "mesh_status_fill"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.26f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = accentColor.copy(alpha = 0.14f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.24f)
                    )
                ) {
                    when (statusTone) {
                        MeshStatusTone.Starting -> {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(14.dp),
                                strokeWidth = 1.8.dp,
                                color = accentColor
                            )
                        }

                        MeshStatusTone.Alert -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(7.dp)
                                    .size(16.dp),
                                tint = accentColor
                            )
                        }

                        MeshStatusTone.Active -> {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(7.dp)
                                    .size(16.dp),
                                tint = accentColor
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rescue_mesh_status_card_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    AnimatedContent(
                        targetState = statusLabel,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(180)) togetherWith
                                fadeOut(animationSpec = tween(120))
                        },
                        label = "mesh_status_label_compact"
                    ) { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.24f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = accentColor
                        )
                        AnimatedContent(
                            targetState = connectedPeerCount,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(160)) togetherWith
                                    fadeOut(animationSpec = tween(100))
                            },
                            label = "mesh_connected_count_compact"
                        ) { count ->
                            Text(
                                text = stringResource(R.string.rescue_mesh_connected_count, count),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(999.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFill)
                        .height(4.dp)
                        .background(
                            color = accentColor,
                            shape = RoundedCornerShape(999.dp)
                        )
                )
            }

            Text(
                text = stringResource(R.string.mesh_service_notification_text),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            errorMessageId?.let { messageId ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(messageId),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val actionLabel = when {
                        messageId == R.string.rescue_mesh_error_wifi_disabled && onOpenWifiSettings != null -> {
                            stringResource(R.string.rescue_open_wifi_settings)
                        }

                        messageId == R.string.rescue_mesh_error_permission_required && onResolvePermissions != null -> {
                            stringResource(R.string.settings_missing_permissions_request_button)
                        }

                        else -> null
                    }
                    val action = when {
                        messageId == R.string.rescue_mesh_error_wifi_disabled && onOpenWifiSettings != null -> {
                            onOpenWifiSettings
                        }

                        messageId == R.string.rescue_mesh_error_permission_required && onResolvePermissions != null -> {
                            onResolvePermissions
                        }

                        else -> null
                    }
                    if (actionLabel != null && action != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilledTonalButton(
                                onClick = action,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(text = actionLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class MeshStatusTone {
    Active,
    Starting,
    Alert,
}

private fun requiredRescueScanPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun requiredRescueMeshPermissions(): List<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.NEARBY_WIFI_DEVICES
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
    } else {
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        permissions += Manifest.permission.ACCESS_COARSE_LOCATION
    }
    return permissions.distinct()
}

private fun Context.missingPermissions(permissions: List<String>): List<String> {
    return permissions.filter { permission ->
        ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
    }
}
@Composable
private fun RescueStatPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RescueEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = stringResource(R.string.rescue_empty_state_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.rescue_empty_state_support),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RescueSignalCard(
    broadcast: RescueScreenViewModel.SOSBroadcast,
    lastSeenText: String,
    activeStatus: String,
    acknowledgedStatus: String,
    clearedStatus: String,
    showAddress: Boolean,
    onClick: (() -> Unit)? = null
) {
    val (statusContainer, statusContent, statusOutline) = when (broadcast.status) {
        activeStatus -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error
        )
        acknowledgedStatus -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiary
        )
        clearedStatus -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.secondary
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outline
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            width = 1.dp,
            color = statusOutline.copy(alpha = 0.22f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = broadcast.userId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.rescue_signal_last_contact_format, lastSeenText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusContainer,
                    border = BorderStroke(
                        width = 1.dp,
                        color = statusOutline.copy(alpha = 0.28f)
                    )
                ) {
                    Text(
                        text = broadcast.status,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            broadcast.deviceName?.let { name ->
                RescueDetailRow(
                    icon = Icons.Default.Bluetooth,
                    contentDescription = stringResource(R.string.rescue_content_desc_device),
                    text = stringResource(
                        R.string.rescue_device_label,
                        name.ifBlank { stringResource(R.string.rescue_unknown_device) }
                    )
                )
            } ?: RescueDetailRow(
                icon = Icons.Default.Bluetooth,
                contentDescription = stringResource(R.string.rescue_content_desc_device),
                text = stringResource(
                    R.string.rescue_device_label,
                    stringResource(R.string.rescue_unknown_device)
                )
            )

            if (showAddress) {
                RescueDetailRow(
                    icon = Icons.Default.LocationOn,
                    contentDescription = stringResource(R.string.rescue_content_desc_location),
                    text = stringResource(R.string.rescue_address_label, broadcast.address)
                )
            }

            broadcast.rssi?.let { rssi ->
                RescueDetailRow(
                    icon = Icons.Default.SignalCellularAlt,
                    contentDescription = stringResource(R.string.rescue_content_desc_signal_strength),
                    text = stringResource(R.string.rescue_signal_strength_format, rssi)
                )
            }

            RescueDetailRow(
                icon = Icons.Default.AccessTime,
                contentDescription = stringResource(R.string.rescue_content_desc_time),
                text = stringResource(R.string.rescue_last_seen_format, lastSeenText)
            )

            RescueDetailRow(
                icon = Icons.Default.Link,
                contentDescription = stringResource(R.string.rescue_content_desc_channel_id),
                text = stringResource(R.string.rescue_channel_id_format, broadcast.channelId)
            )

            broadcast.serviceUuid?.let { uuid ->
                RescueDetailRow(
                    icon = Icons.Default.Bluetooth,
                    contentDescription = stringResource(R.string.rescue_content_desc_service_uuid),
                    text = stringResource(R.string.rescue_service_uuid_format, uuid)
                )
            }
        }
    }
}

@Composable
private fun RescueDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier
                    .padding(7.dp)
                    .size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
