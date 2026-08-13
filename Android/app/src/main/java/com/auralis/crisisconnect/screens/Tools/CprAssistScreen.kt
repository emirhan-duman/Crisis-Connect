package com.auralis.crisisconnect.screens.Tools

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.service.sos.EmergencyNumberResolver
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CprAssistScreen(navController: NavController) {
    val viewModel: CprAssistViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val emergencyNumber = remember(context) { EmergencyNumberResolver.resolve(context) }
    var showInfo by remember { mutableStateOf(false) }
    var showEndConfirmation by remember { mutableStateOf(false) }

    val activity = context as? android.app.Activity
    DisposableEffect(state.isSessionRunning) {
        if (state.isSessionRunning) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler(enabled = state.isSessionRunning) {
        showEndConfirmation = true
    }

    if (showEndConfirmation) {
        AlertDialog(
            onDismissRequest = { showEndConfirmation = false },
            title = { Text(stringResource(R.string.cpr_end_confirm_title)) },
            text = { Text(stringResource(R.string.cpr_end_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndConfirmation = false
                        viewModel.endSession()
                    }
                ) { Text(stringResource(R.string.cpr_end_session)) }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirmation = false }) {
                    Text(stringResource(R.string.cpr_continue_session))
                }
            }
        )
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            CprInfoSheet()
        }
    }

    if (state.isAedGuideOpen) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeAedGuideBeforeAnalysis
        ) {
            CprAedGuide(
                state = state,
                onAdvance = viewModel::advanceAedGuide,
                onDecision = viewModel::recordAedDecision,
                onResume = viewModel::resumeAfterAed,
                onClose = viewModel::closeAedGuideBeforeAnalysis
            )
        }
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_cpr_assist_title,
                onNavigateBack = {
                    if (state.isSessionRunning) showEndConfirmation = true
                    else navController.popBackStack()
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.info))
                    }
                }
            )
        },
        bottomBar = {
            if (!state.isSessionRunning) AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = state.phase,
                label = "cpr-assist-state"
            ) { phase ->
                when (phase) {
                    CprAssistPhase.READY -> CprReadyContent(
                        state = state,
                        emergencyNumber = emergencyNumber,
                        onCallEmergency = {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyNumber"))
                            )
                        },
                        onSelectMode = viewModel::selectMode,
                        onStart = viewModel::startSession
                    )
                    CprAssistPhase.COMPRESSIONS,
                    CprAssistPhase.BREATHS -> CprActiveContent(
                        state = state,
                        emergencyNumber = emergencyNumber,
                        onCallEmergency = {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyNumber"))
                            )
                        },
                        onTogglePause = viewModel::togglePause,
                        onResumeEarly = viewModel::resumeCompressionsEarly,
                        onOpenAed = viewModel::openAedGuide,
                        onSoundChanged = viewModel::setSoundEnabled,
                        onVoiceChanged = viewModel::setVoiceEnabled,
                        onHapticsChanged = viewModel::setHapticsEnabled,
                        onEnd = { showEndConfirmation = true }
                    )
                    CprAssistPhase.ENDED -> CprEndedContent(
                        state = state,
                        onReset = viewModel::resetSession
                    )
                }
            }
        }
    }
}

@Composable
private fun CprReadyContent(
    state: CprAssistUiState,
    emergencyNumber: String,
    onCallEmergency: () -> Unit,
    onSelectMode: (CprAssistMode) -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CprEmergencyCard(emergencyNumber, onCallEmergency)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.cpr_adult_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    stringResource(R.string.cpr_adult_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CprChecklistRow(R.string.cpr_check_scene)
                CprChecklistRow(R.string.cpr_check_response)
                CprChecklistRow(R.string.cpr_check_help)
            }
        }

        Text(
            stringResource(R.string.cpr_choose_mode),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        CprModeCard(
            selected = state.mode == CprAssistMode.HANDS_ONLY,
            title = stringResource(R.string.cpr_mode_hands_only),
            description = stringResource(R.string.cpr_mode_hands_only_description),
            onClick = { onSelectMode(CprAssistMode.HANDS_ONLY) }
        )
        CprModeCard(
            selected = state.mode == CprAssistMode.THIRTY_TO_TWO,
            title = stringResource(R.string.cpr_mode_30_2),
            description = stringResource(R.string.cpr_mode_30_2_description),
            onClick = { onSelectMode(CprAssistMode.THIRTY_TO_TWO) }
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Timer, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column {
                    Text(
                        stringResource(R.string.cpr_quality_target),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(R.string.cpr_quality_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cpr_start), fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(R.string.cpr_disclaimer_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CprActiveContent(
    state: CprAssistUiState,
    emergencyNumber: String,
    onCallEmergency: () -> Unit,
    onTogglePause: () -> Unit,
    onResumeEarly: () -> Unit,
    onOpenAed: () -> Unit,
    onSoundChanged: (Boolean) -> Unit,
    onVoiceChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onEnd: () -> Unit
) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(state.beatSequence) {
        if (state.beatSequence > 0 && state.phase == CprAssistPhase.COMPRESSIONS && !state.isPaused) {
            pulse.snapTo(1.075f)
            pulse.animateTo(1f, tween(210))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CprStatusPill(
                text = if (state.isPaused) stringResource(R.string.cpr_status_paused)
                else stringResource(R.string.cpr_status_active),
                color = if (state.isPaused) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onCallEmergency) {
                Icon(Icons.Filled.Call, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cpr_call_number, emergencyNumber))
            }
        }

        CprRhythmHero(state = state, scale = pulse.value, onResumeEarly = onResumeEarly)

        val setProgress = state.compressionInSet.toFloat() / CprAssistTiming.COMPRESSIONS_PER_SET
        LinearProgressIndicator(
            progress = { setProgress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp),
            color = MaterialTheme.colorScheme.error,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        Text(
            stringResource(
                R.string.cpr_set_progress,
                state.compressionInSet,
                CprAssistTiming.COMPRESSIONS_PER_SET
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            CprMetricCard(
                value = CprAssistTiming.formatDuration(state.elapsedMillis),
                label = stringResource(R.string.cpr_metric_duration),
                modifier = Modifier.weight(1f)
            )
            CprMetricCard(
                value = state.totalCompressions.toString(),
                label = stringResource(R.string.cpr_metric_total),
                modifier = Modifier.weight(1f)
            )
            CprMetricCard(
                value = CprAssistTiming.formatDuration(state.roundRemainingMillis),
                label = stringResource(R.string.cpr_metric_round),
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.phase == CprAssistPhase.BREATHS) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    if (state.phase == CprAssistPhase.BREATHS) Icons.Filled.Timer else Icons.Filled.Favorite,
                    null,
                    tint = if (state.phase == CprAssistPhase.BREATHS) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else MaterialTheme.colorScheme.error
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.phase == CprAssistPhase.BREATHS) {
                            stringResource(R.string.cpr_instruction_breaths_title)
                        } else stringResource(R.string.cpr_instruction_compressions_title),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (state.phase == CprAssistPhase.BREATHS) {
                            stringResource(R.string.cpr_instruction_breaths_body)
                        } else stringResource(R.string.cpr_instruction_compressions_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onTogglePause,
                enabled = state.pauseReason != CprPauseReason.AED_ANALYSIS,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Icon(if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.isPaused) stringResource(R.string.cpr_resume)
                    else stringResource(R.string.cpr_pause)
                )
            }
            Button(
                onClick = onOpenAed,
                modifier = Modifier
                    .weight(1.35f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.MedicalServices, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cpr_aed_arrived), fontWeight = FontWeight.SemiBold)
            }
        }

        CprAssistSettings(
            state = state,
            onSoundChanged = onSoundChanged,
            onVoiceChanged = onVoiceChanged,
            onHapticsChanged = onHapticsChanged
        )

        OutlinedButton(
            onClick = onEnd,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Filled.Stop, null)
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.cpr_end_session))
        }
    }
}

@Composable
private fun CprRhythmHero(
    state: CprAssistUiState,
    scale: Float,
    onResumeEarly: () -> Unit
) {
    val isBreathing = state.phase == CprAssistPhase.BREATHS
    val heroColor = when {
        state.isPaused -> MaterialTheme.colorScheme.tertiary
        isBreathing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(206.dp)
            .scale(scale)
            .background(heroColor.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = {
                if (isBreathing) {
                    state.breathRemainingMillis.toFloat() / CprAssistTiming.BREATH_PAUSE_MILLIS
                } else {
                    state.compressionInSet.toFloat() / CprAssistTiming.COMPRESSIONS_PER_SET
                }
            },
            modifier = Modifier.size(188.dp),
            color = heroColor,
            trackColor = heroColor.copy(alpha = 0.14f),
            strokeWidth = 9.dp
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    state.isPaused -> stringResource(R.string.cpr_status_paused)
                    isBreathing -> ceil(state.breathRemainingMillis / 1_000.0).toInt().toString()
                    else -> state.compressionInSet.toString()
                },
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Black,
                color = heroColor
            )
            Text(
                when {
                    state.isPaused -> stringResource(R.string.cpr_tap_resume)
                    isBreathing -> stringResource(R.string.cpr_breaths_label)
                    else -> stringResource(R.string.cpr_compressions_label)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (!isBreathing && !state.isPaused) {
                Text(
                    stringResource(R.string.cpr_bpm_value, CprAssistTiming.TARGET_BPM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isBreathing) {
                TextButton(onClick = onResumeEarly) {
                    Text(stringResource(R.string.cpr_resume_now))
                }
            }
        }
    }
}

@Composable
private fun CprAssistSettings(
    state: CprAssistUiState,
    onSoundChanged: (Boolean) -> Unit,
    onVoiceChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            CprSettingRow(
                icon = Icons.Filled.VolumeUp,
                title = stringResource(R.string.cpr_setting_beat),
                checked = state.soundEnabled,
                onCheckedChange = onSoundChanged
            )
            CprSettingRow(
                icon = Icons.Filled.VolumeUp,
                title = stringResource(R.string.cpr_setting_voice),
                subtitle = if (!state.speechAvailable) stringResource(R.string.cpr_voice_unavailable) else null,
                checked = state.voiceEnabled && state.speechAvailable,
                enabled = state.speechAvailable,
                onCheckedChange = onVoiceChanged
            )
            CprSettingRow(
                icon = Icons.Filled.Vibration,
                title = stringResource(R.string.cpr_setting_haptics),
                checked = state.hapticsEnabled,
                onCheckedChange = onHapticsChanged
            )
        }
    }
}

@Composable
private fun CprSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun CprAedGuide(
    state: CprAssistUiState,
    onAdvance: () -> Unit,
    onDecision: () -> Unit,
    onResume: () -> Unit,
    onClose: () -> Unit
) {
    val stepNumber = state.aedStep.ordinal + 1
    val titleRes = when (state.aedStep) {
        CprAedStep.POWER_ON -> R.string.cpr_aed_power_title
        CprAedStep.ATTACH_PADS -> R.string.cpr_aed_pads_title
        CprAedStep.ANALYZE -> R.string.cpr_aed_analyze_title
        CprAedStep.SHOCK_DECISION -> R.string.cpr_aed_decision_title
        CprAedStep.RESUME_CPR -> R.string.cpr_aed_resume_title
    }
    val bodyRes = when (state.aedStep) {
        CprAedStep.POWER_ON -> R.string.cpr_aed_power_body
        CprAedStep.ATTACH_PADS -> R.string.cpr_aed_pads_body
        CprAedStep.ANALYZE -> R.string.cpr_aed_analyze_body
        CprAedStep.SHOCK_DECISION -> R.string.cpr_aed_decision_body
        CprAedStep.RESUME_CPR -> R.string.cpr_aed_resume_body
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.MedicalServices, null, tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cpr_aed_guide_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.cpr_aed_step_count, stepNumber, CprAedStep.entries.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.aedStep == CprAedStep.POWER_ON || state.aedStep == CprAedStep.ATTACH_PADS) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            }
        }
        LinearProgressIndicator(
            progress = { stepNumber.toFloat() / CprAedStep.entries.size },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.error
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.aedStep == CprAedStep.ANALYZE || state.aedStep == CprAedStep.SHOCK_DECISION) {
                    MaterialTheme.colorScheme.errorContainer
                } else MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            stringResource(R.string.cpr_aed_device_priority),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (state.aedStep) {
            CprAedStep.POWER_ON,
            CprAedStep.ATTACH_PADS,
            CprAedStep.ANALYZE -> Button(
                onClick = onAdvance,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.aedStep == CprAedStep.ANALYZE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    when (state.aedStep) {
                        CprAedStep.POWER_ON -> stringResource(R.string.cpr_aed_next_pads)
                        CprAedStep.ATTACH_PADS -> stringResource(R.string.cpr_aed_start_analysis)
                        CprAedStep.ANALYZE -> stringResource(R.string.cpr_aed_analysis_complete)
                        else -> ""
                    },
                    fontWeight = FontWeight.Bold
                )
            }
            CprAedStep.SHOCK_DECISION -> {
                Button(
                    onClick = onDecision,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.cpr_aed_shock_delivered), fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onDecision, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cpr_aed_no_shock))
                }
            }
            CprAedStep.RESUME_CPR -> Button(
                onClick = onResume,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.cpr_aed_resume_now), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CprEndedContent(state: CprAssistUiState, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.cpr_session_ended), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.cpr_session_summary,
                CprAssistTiming.formatDuration(state.elapsedMillis),
                state.totalCompressions
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cpr_new_session))
        }
    }
}

@Composable
private fun CprEmergencyCard(number: String, onCall: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.Call, null, tint = MaterialTheme.colorScheme.error)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cpr_call_first), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.cpr_call_speaker_hint), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onCall,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(number, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CprChecklistRow(textRes: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CprModeCard(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CprMetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CprStatusPill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp)
    )
}

@Composable
private fun CprInfoSheet() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.cpr_info_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        CprInfoSection(R.string.cpr_info_scope_title, R.string.cpr_info_scope_body)
        CprInfoSection(R.string.cpr_info_quality_title, R.string.cpr_info_quality_body)
        CprInfoSection(R.string.cpr_info_aed_title, R.string.cpr_info_aed_body)
        CprInfoSection(R.string.cpr_info_offline_title, R.string.cpr_info_offline_body)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(15.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Warning, null)
                Text(stringResource(R.string.cpr_disclaimer_long), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CprInfoSection(titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(stringResource(titleRes), fontWeight = FontWeight.SemiBold)
        Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
