package com.auralis.crisisconnect.muster

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import java.text.DateFormat
import java.util.Date

private data class MusterPalette(val accent: Color, val band: Color, val amber: Color, val green: Color, val red: Color, val safeFill: Color, val onSafe: Color)
@Composable private fun musterPalette(): MusterPalette = if (MaterialTheme.colorScheme.background.luminance() < .3f) {
    MusterPalette(Color(0xFFBCD4F2), Color(0xFF182633), Color(0xFFFFC875), Color(0xFF98DFB9), Color(0xFFFFB4AB), Color(0xFF98DFB9), Color(0xFF073922))
} else {
    MusterPalette(Color(0xFF304A76), Color(0xFFEDF4FD), Color(0xFF946000), Color(0xFF176B45), Color(0xFFB3261E), Color(0xFF176B45), Color.White)
}

/** Same avatar, typography and spacing as the pinned conversation rows. Local observation only. */
@Composable
internal fun MusterHomeSection(state: MusterInboxState, onOpen: (String?, String?) -> Unit) {
    if (state.cards.isEmpty() && !state.unreadable) return
    val now = System.currentTimeMillis()
    val priority = state.priority(now)
    val awaiting = state.cards.count { it.needsResponse(now) }
    val subtitle = when {
        priority == null -> R.string.muster_ui_history_settings
        priority.needsResponse(now) -> if (priority.kind == "drill") R.string.muster_ui_drill_awaiting else R.string.muster_ui_awaiting
        priority.replyState == "failed" -> R.string.muster_ui_not_delivered
        priority.replyState == "pending" -> R.string.muster_ui_home_pending
        else -> R.string.muster_ui_help_status
    }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
        onOpen(priority?.panel ?: state.cards.firstOrNull()?.panel, priority?.session)
    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FactCheck, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.muster_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium,
                color = if (priority?.replyState == "failed") musterPalette().red else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (awaiting > 0) {
            Badge(Modifier.padding(start = 10.dp), containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) { Text(if (awaiting > 99) "99+" else awaiting.toString()) }
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null,
            Modifier.padding(start = 12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusterWorkspace(state: MusterInboxState, refreshing: Boolean, saving: Boolean,
    refreshError: Boolean, saveError: Boolean, initialPanel: String?, initialSession: String?,
    onBack: () -> Unit, onRefresh: () -> Unit, onAnswer: (MusterCardModel, String, String) -> Unit,
    onFlexibleAnswer: (MusterCardModel, MusterSubmission) -> Unit = { row, reply -> onAnswer(row, reply.answer, reply.note) }) {
    var selected by remember(state.uid, initialPanel, initialSession) { mutableStateOf(initialSession?.let { "$initialPanel:$it" }) }
    var outbox by remember(state.uid) { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val row = state.cards.firstOrNull { it.key == selected }
    val back = { if (selected != null && initialSession == null) selected = null else onBack() }
    BackHandler(enabled = selected != null && initialSession == null) { selected = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        AppBackTopBar(titleRes = if (selected != null) R.string.muster_ui_your_status else R.string.muster_title,
            onNavigateBack = back,
            actions = {
                if (state.uid != null) {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, stringResource(R.string.muster_refresh), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Tune, stringResource(R.string.muster_ui_settings), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            })
    }) { padding ->
        when {
            state.uid == null -> MusterEmpty(Modifier.padding(padding), Icons.Outlined.Lock, R.string.muster_ui_sign_in_title, R.string.muster_sign_in)
            selected != null && row == null -> MusterEmpty(Modifier.padding(padding), Icons.Outlined.CloudOff, R.string.muster_title, R.string.muster_connect_instructions)
            row != null -> key(state.uid) { MusterResponseContent(row, refreshing, saving, refreshError, saveError, Modifier.padding(padding), onFlexibleAnswer) }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !outbox, onClick = { outbox = false }, label = { Text(stringResource(R.string.muster_ui_checkins)) })
                    FilterChip(selected = outbox, onClick = { outbox = true }, label = { Text(stringResource(R.string.muster_ui_outbox_count, state.outbox.size)) })
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (refreshError || state.unreadable) item { MusterNotice(R.string.muster_ui_refresh_error, Icons.Outlined.CloudOff) }
                    if (outbox) {
                        item { Text(stringResource(R.string.muster_ui_delivery_explanation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                        if (state.outbox.isEmpty()) item { MusterEmpty(Modifier.fillParentMaxWidth().padding(top = 50.dp), Icons.Outlined.DoneAll, R.string.muster_ui_outbox_empty, R.string.muster_ui_outbox_empty_detail) }
                        items(state.outbox, key = { it.operationId }) { event ->
                            val item = state.cards.firstOrNull { it.panel == event.panel && it.session == event.session }
                            Surface(onClick = { selected = "${event.panel}:${event.session}" }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(item?.title ?: stringResource(R.string.muster_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    MusterStatus(event.state)
                                    Text(item?.responseConfig?.options?.firstOrNull { it.id == event.optionId }?.label?.takeIf { it.isNotBlank() } ?: musterAnswerLabel(event.answer), style = MaterialTheme.typography.bodyMedium)
                                    if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        item {
                            Column(Modifier.padding(top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.muster_ui_institutional), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = musterPalette().accent)
                                Text(stringResource(R.string.muster_ui_inbox_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.muster_ui_inbox_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.cards.isEmpty()) item { MusterEmpty(Modifier.fillParentMaxWidth().padding(top = 36.dp), Icons.Outlined.VerifiedUser, R.string.muster_empty, R.string.muster_ui_empty_detail) }
                        items(state.cards, key = { it.key }) { item ->
                            Surface(onClick = { selected = item.key }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Icon(Icons.Outlined.FactCheck, null, Modifier.size(24.dp), tint = musterPalette().accent)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        MusterStatus(if (item.closed) "closed" else item.replyState)
                                        if (item.kind == "drill") Text(stringResource(R.string.muster_drill), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (settings && state.uid != null) MusterSettingsSheet(state.uid, onDismiss = { settings = false })
}

@Composable
private fun MusterResponseContent(row: MusterCardModel, refreshing: Boolean, saving: Boolean, refreshError: Boolean, saveError: Boolean,
    modifier: Modifier, onAnswer: (MusterCardModel, MusterSubmission) -> Unit) {
    var editing by remember(row.key, row.replyState, row.answer) { mutableStateOf(row.replyState.isBlank()) }
    var showNote by remember(row.key) { mutableStateOf(false) }
    var note by remember(row.key) { mutableStateOf(row.note) }
    var voice by remember(row.key) { mutableStateOf<MusterVoiceDraft?>(null) }
    var voiceRecording by remember(row.key) { mutableStateOf(false) }
    var expanded by remember(row.key) { mutableStateOf(false) }
    var overflow by remember(row.key) { mutableStateOf(false) }
    val palette = musterPalette()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (row.kind == "drill") R.string.muster_drill else R.string.muster_ui_institutional),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = palette.amber)
                Text(row.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                if (row.instructions.isNotBlank()) {
                    Text(row.instructions, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
                    if (overflow || expanded) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(if (expanded) R.string.muster_ui_less else R.string.muster_ui_instructions))
                    }
                }
                if (row.deadlineAt > 0) {
                    val locale = LocalConfiguration.current.locales[0]
                    val formatted = remember(row.deadlineAt, locale) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
                            .format(Date(row.deadlineAt))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.muster_ui_deadline, formatted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (refreshError) item { MusterNotice(R.string.muster_ui_refresh_error, Icons.Outlined.CloudOff) }
        if (saveError) item { MusterNotice(R.string.muster_ui_save_error, Icons.Outlined.ErrorOutline, error = true) }
        if (row.helpOpen) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MusterNotice(R.string.muster_ui_help_open, Icons.Outlined.SupportAgent, error = true)
            if (row.helpVoice != null && row.helpVoice.eventId != row.remoteVoice?.eventId) MusterReceivedVoice(row.copy(localVoice = null, remoteVoice = row.helpVoice))
        } }
        if (row.replyState.isNotBlank() && !editing) item {
            Surface(shape = RoundedCornerShape(20.dp), color = if (row.replyState == "sent") palette.green.copy(alpha = .08f) else MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(if (row.replyState == "sent") Icons.Outlined.CheckCircle else Icons.Outlined.CloudUpload, null,
                        Modifier.size(32.dp), tint = if (row.replyState == "sent") palette.green else palette.amber)
                    Text(row.responseConfig?.options?.firstOrNull { it.id == row.optionId }?.label?.takeIf { it.isNotBlank() } ?: musterAnswerLabel(row.answer), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    MusterStatus(row.replyState)
                    Text(stringResource(when (row.replyState) { "sent" -> R.string.muster_ui_received_detail; "failed" -> R.string.muster_failed; else -> R.string.muster_ui_delivery_explanation }),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodyMedium)
                    if (row.localVoice != null || row.remoteVoice != null) MusterReceivedVoice(row)
                    if (!row.closed && row.configurationAvailable) OutlinedButton(onClick = { editing = true; note = row.note }, enabled = !saving) { Text(stringResource(R.string.muster_ui_change_answer)) }
                }
            }
        }
        if (row.closed) item { MusterNotice(R.string.muster_closed, Icons.Outlined.Lock) }
        else if (!row.configurationAvailable) item { MusterNotice(R.string.muster_connect_instructions, Icons.Outlined.CloudOff) }
        else if (editing) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val config = row.responseConfig
                    Text(config?.question?.takeIf { it.isNotBlank() } ?: stringResource(R.string.muster_ui_question), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val textAllowed = config?.allowText != false
                    val noteLimit = if (config == null) 500 else 2000
                    if (textAllowed && (showNote || config != null)) OutlinedTextField(value = note, onValueChange = { if (it.length <= noteLimit) note = it },
                        label = { Text(stringResource(R.string.muster_note)) }, supportingText = { Text("${note.length}/$noteLimit") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !saving && !voiceRecording)
                    else if (textAllowed) TextButton(onClick = { showNote = true }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.EditNote, null, Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.muster_ui_add_note))
                    }
                    if (config?.allowVoice == true) MusterVoiceComposer(!saving, voice, { voice = it }, { voiceRecording = it })
                    val options = config?.options ?: listOf(MusterOption("safe", "", "safe"), MusterOption("help", "", "help"), MusterOption("at_muster", "", "at_muster"))
                    options.forEach { option ->
                        val title = option.label.takeIf { it.isNotBlank() } ?: musterAnswerLabel(option.meaning)
                        val detail = when (option.meaning) { "safe" -> R.string.muster_ui_safe_detail; "help" -> R.string.muster_ui_help_detail; "at_muster" -> R.string.muster_ui_point_detail; else -> R.string.muster_response_message_hint }
                        val icon = when (option.meaning) { "safe" -> Icons.Outlined.CheckCircle; "help" -> Icons.Outlined.SupportAgent; "at_muster" -> Icons.Outlined.LocationOn; else -> Icons.Outlined.ChatBubbleOutline }
                        val fill = when (option.meaning) { "safe" -> palette.safeFill; "help" -> palette.red.copy(alpha = .08f); else -> palette.band }
                        val ink = when (option.meaning) { "safe" -> palette.onSafe; "help" -> palette.red; else -> palette.accent }
                        MusterAnswerButton(title, detail, icon, fill, ink, saving || voiceRecording) {
                            onAnswer(row, MusterSubmission(option.meaning, if (textAllowed) note else "", if (config != null) option.id else null, voice))
                        }
                    }
                    if (config != null && (config.allowText || config.allowVoice)) {
                        Button(onClick = { onAnswer(row, MusterSubmission("message", if (textAllowed) note else "", voice = voice)) },
                            enabled = !saving && !voiceRecording && (note.isNotBlank() && textAllowed || voice != null), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.muster_response_send_message))
                        }
                        Text(stringResource(R.string.muster_response_message_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.WifiOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.muster_ui_offline_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (refreshing) item { Text(stringResource(R.string.muster_ui_refreshing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun MusterAnswerButton(title: String, detail: Int, icon: ImageVector, fill: Color, ink: Color, saving: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink), contentPadding = PaddingValues(16.dp)) {
        Icon(icon, null, Modifier.size(27.dp)); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
    }
}

@Composable private fun MusterStatus(state: String) {
    val palette = musterPalette()
    val color = when (state) { "sent" -> palette.green; "failed" -> palette.red; "pending" -> palette.amber; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    val label = when (state) { "sent" -> R.string.muster_sent; "failed" -> R.string.muster_ui_not_delivered; "pending" -> R.string.muster_queued; "closed" -> R.string.muster_closed; else -> R.string.muster_ui_awaiting }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(when (state) { "sent" -> Icons.Outlined.DoneAll; "failed" -> Icons.Outlined.ErrorOutline; "closed" -> Icons.Outlined.Lock; else -> Icons.Outlined.Schedule }, null, Modifier.size(15.dp), tint = color)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable private fun musterAnswerLabel(answer: String?): String = stringResource(when (answer) {
    "safe" -> R.string.muster_safe; "help" -> R.string.muster_help; "at_muster" -> R.string.muster_at_point; "message" -> R.string.muster_response_message; else -> R.string.muster_ui_your_answer
})

@Composable private fun MusterNotice(text: Int, icon: ImageVector, error: Boolean = false) {
    val color = if (error) musterPalette().red else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = .06f), RoundedCornerShape(14.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable private fun MusterEmpty(modifier: Modifier, icon: ImageVector, title: Int, detail: Int) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(72.dp).background(musterPalette().band, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(32.dp), tint = musterPalette().accent) }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MusterSettingsSheet(uid: String, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var silentSound by remember(uid) { mutableStateOf(MusterUrgentAlerts.enabled(context, uid)) }
    var soundDuration by remember(uid) { mutableStateOf(MusterUrgentAlerts.duration(context, uid)) }
    var urgentReady by remember { mutableStateOf(MusterUrgentAlerts.ready(context)) }
    var enabled by remember(uid) { mutableStateOf(MusterPresentation.enabled(context, uid)) }
    var allowed by remember { mutableStateOf(MusterPresentation.allowed(context)) }
    var alertsEnabled by remember { mutableStateOf(MusterPresentation.notificationsEnabled(context)) }
    var highImportance by remember { mutableStateOf(MusterPresentation.highImportance(context)) }
    fun refreshAlertPermissions() {
        urgentReady = MusterUrgentAlerts.ready(context)
        allowed = MusterPresentation.allowed(context)
        alertsEnabled = MusterPresentation.notificationsEnabled(context)
        highImportance = MusterPresentation.highImportance(context)
    }
    var settingsError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { MusterNotifications.ensureChannel(context); refreshAlertPermissions() }.onFailure { settingsError = true } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshAlertPermissions() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.muster_ui_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Fullscreen, null, tint = musterPalette().accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.muster_full_screen), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (!enabled) R.string.muster_ui_disabled else if (!alertsEnabled) R.string.muster_alerts_blocked
                        else if (!highImportance) R.string.muster_alerts_quiet else if (!allowed) R.string.muster_ui_permission_required else R.string.muster_ui_enabled),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val label = stringResource(R.string.muster_full_screen)
                Switch(checked = enabled, onCheckedChange = { enabled = it; MusterPresentation.setEnabled(context, uid, it) }, modifier = Modifier.semantics { contentDescription = label })
            }
            Text(stringResource(R.string.muster_full_screen_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && !allowed) Button(onClick = { settingsError = !MusterPresentation.openPermissionSettings(context) }) { Text(stringResource(R.string.muster_full_screen_settings)) }
            HorizontalDivider()
            Text(stringResource(R.string.muster_alert_settings_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "personnel-muster")
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.fromParts("package", context.packageName, null))
                settingsError = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false }.getOrDefault(true)
            }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(21.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.muster_alert_settings_title))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.muster_urgent_sound), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.muster_urgent_sound_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val label = stringResource(R.string.muster_urgent_sound)
                Switch(checked = silentSound, onCheckedChange = { silentSound = it; MusterUrgentAlerts.setEnabled(context, uid, it) }, modifier = Modifier.semantics { contentDescription = label })
            }
            if (silentSound) {
                Text(stringResource(if (urgentReady) R.string.muster_urgent_ready else R.string.muster_urgent_not_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10, 20).forEach { seconds ->
                    FilterChip(selected = soundDuration == seconds, onClick = { soundDuration = seconds; MusterUrgentAlerts.setDuration(context, uid, seconds) }, label = { Text(stringResource(R.string.muster_urgent_seconds, seconds)) })
                } }
            }
            TextButton(onClick = { settingsError = !MusterUrgentAlerts.openFlashSettings(context) }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.muster_flash_settings)) }
            Text(stringResource(R.string.muster_flash_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.muster_ui_offline_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settingsError) MusterNotice(R.string.muster_error, Icons.Outlined.ErrorOutline, error = true)
        }
    }
}

@Composable
internal fun MusterLockedScreen(kind: String?, onUnlock: () -> Unit, onLater: () -> Unit) {
    val palette = musterPalette()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(if (kind == "drill") R.string.muster_drill else R.string.muster_ui_institutional), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = palette.amber)
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(80.dp).background(palette.band, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(40.dp), tint = palette.accent)
                }
                Text(stringResource(R.string.muster_ui_home_waiting), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.muster_ui_locked_detail), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.LockOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.muster_unlock))
                }
                TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.muster_later)) }
            }
        }
        }
    }
}
