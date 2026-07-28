package com.auralis.crisisconnect.screens.Chat

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.ui.components.ContactAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(navController: NavController, sessionCode: String) {
    val context = LocalContext.current
    val viewModel: ChatScreenViewModel = viewModel()
    val contactName by viewModel.contactName.collectAsStateWithLifecycle()
    val bluetoothAddress by viewModel.contactAddressState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val transport by viewModel.transport.collectAsStateWithLifecycle()
    val contactPhotoUrl by viewModel.contactPhotoUrl.collectAsStateWithLifecycle()
    val safetyNumber by viewModel.safetyNumber.collectAsStateWithLifecycle()
    val peerKeyChanged by viewModel.peerKeyChanged.collectAsStateWithLifecycle()
    val safetyNumberForwardSecret by viewModel.safetyNumberForwardSecret.collectAsStateWithLifecycle()
    val signalInfo by viewModel.signalInfo.collectAsStateWithLifecycle()
    val signalPermissionMissing by viewModel.signalPermissionMissing.collectAsStateWithLifecycle()
    val isSessionEncrypted by viewModel.isSessionEncrypted.collectAsStateWithLifecycle()
    val storedAesKey by viewModel.sessionAesKey.collectAsStateWithLifecycle()
    val contactAvatarVersion by ContactAvatarStorage.observeAvatarVersion(sessionCode)
        .collectAsStateWithLifecycle(initialValue = 0L)
    val contactProfileBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = sessionCode,
        key2 = contactAvatarVersion
    ) {
        value = withContext(Dispatchers.IO) {
            ContactAvatarStorage.loadContactAvatar(context, sessionCode)
        }
    }

    LaunchedEffect(sessionCode) {
        viewModel.initialize(sessionCode)
    }

    val displayName = contactName ?: sessionCode
    val encodedSessionCode = remember(sessionCode) { Uri.encode(sessionCode) }
    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember(displayName) { mutableStateOf(displayName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.chat_info_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ContactAvatar(
                            displayName = displayName,
                            stableKey = sessionCode,
                            bitmap = contactProfileBitmap,
                            photoUrl = contactPhotoUrl,
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.displaySmall
                        )
                    }
                    CenteredNameWithEdit(
                        name = displayName,
                        onEditClick = {
                            editedName = displayName
                            isEditingName = true
                        }
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_info_status_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        ConnectionStatusBadge(connectionState, transport)
                    }
                }
            }

            ConnectionDetailsCard(
                connectionState = connectionState,
                transport = transport,
                bluetoothAddress = bluetoothAddress,
                signalInfo = signalInfo,
                signalPermissionMissing = signalPermissionMissing
            )

            SessionInfoCard(
                sessionCode = sessionCode,
                isEncrypted = isSessionEncrypted,
                hasStoredKey = !storedAesKey.isNullOrBlank(),
                onEnableEncryption = {
                    navController.navigate("chat/$encodedSessionCode/secure_setup")
                },
                onShowStoredCode = {
                    navController.navigate("chat/$encodedSessionCode/handshake_code")
                }
            )

            safetyNumber?.let {
                SafetyNumberCard(
                    safetyNumber = it,
                    keyChanged = peerKeyChanged,
                    forwardSecret = safetyNumberForwardSecret,
                    onVerify = { viewModel.acknowledgePeerKeyChange() }
                )
            }
        }

        if (isEditingName) {
            AlertDialog(
                onDismissRequest = { isEditingName = false },
                title = { Text(text = stringResource(R.string.edit_name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.chat_info_edit_name_description),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            label = { Text(text = stringResource(R.string.chat_info_edit_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val nameToSave = editedName.trim()
                            if (nameToSave.isNotEmpty()) {
                                viewModel.saveContactName(nameToSave)
                                isEditingName = false
                            }
                        },
                        enabled = editedName.isNotBlank()
                    ) {
                        Text(text = stringResource(R.string.save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isEditingName = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun CenteredNameWithEdit(
    name: String,
    onEditClick: () -> Unit
) {
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        content = {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.edit_name)
                )
            }
        }
    ) { measurables, constraints ->
        val iconPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0)
        )
        val spacing = 2.dp.roundToPx()
        val textMaxWidth = (constraints.maxWidth - iconPlaceable.width - spacing).coerceAtLeast(0)
        val textPlaceable = measurables[0].measure(
            constraints.copy(minWidth = 0, maxWidth = textMaxWidth)
        )

        val width = constraints.maxWidth
        val height = max(textPlaceable.height, iconPlaceable.height)
        val textX = ((width - textPlaceable.width) / 2).coerceAtLeast(0)
        val textY = (height - textPlaceable.height) / 2
        val iconX = (textX + textPlaceable.width + spacing).coerceAtMost(width - iconPlaceable.width)
        val iconY = (height - iconPlaceable.height) / 2

        layout(width, height) {
            textPlaceable.placeRelative(textX, textY)
            iconPlaceable.placeRelative(iconX, iconY)
        }
    }
}

@Composable
private fun SafetyNumberCard(
    safetyNumber: String,
    keyChanged: Boolean,
    forwardSecret: Boolean,
    onVerify: () -> Unit
) {
    val warn = keyChanged
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        color = if (warn) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (warn) Icons.Filled.Warning else Icons.Filled.Shield,
                    contentDescription = null,
                    tint = if (warn) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(
                        if (warn) R.string.safety_number_changed_title
                        else R.string.safety_number_title
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (warn) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            if (warn) {
                Text(
                    text = stringResource(R.string.safety_number_changed_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = safetyNumber,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                lineHeight = MaterialTheme.typography.headlineSmall.lineHeight,
                color = if (warn) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (forwardSecret && !warn) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.safety_number_forward_secret),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = stringResource(R.string.safety_number_description),
                style = MaterialTheme.typography.bodySmall,
                color = if (warn) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (warn) {
                Button(
                    onClick = onVerify,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(text = stringResource(R.string.safety_number_verify_action))
                }
            }
        }
    }
}

@Composable
private fun ConnectionDetailsCard(
    connectionState: ChatConnectionState,
    transport: ChatTransport,
    bluetoothAddress: String?,
    signalInfo: SignalStrengthInfo?,
    signalPermissionMissing: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_connection_details_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_info_status_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                ConnectionStatusBadge(connectionState, transport)
            }
            val addressText = bluetoothAddress?.takeIf { it.isNotBlank() }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_connection_address_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = addressText ?: stringResource(R.string.chat_connection_address_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (addressText != null) FontWeight.Medium else FontWeight.Normal,
                    color = if (addressText != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (connectionState == ChatConnectionState.Connected) {
                SignalStrengthSection(signalInfo, signalPermissionMissing)
            }
        }
    }
}

@Composable
private fun SignalStrengthSection(
    signalInfo: SignalStrengthInfo?,
    permissionMissing: Boolean
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.chat_signal_strength_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        when {
            permissionMissing -> {
                Text(
                    text = stringResource(R.string.chat_signal_permission_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            signalInfo != null -> {
                val level = signalInfo.level.coerceIn(0, 4)
                val levelDescription = when (level) {
                    4 -> R.string.chat_signal_level_excellent
                    3 -> R.string.chat_signal_level_good
                    2 -> R.string.chat_signal_level_fair
                    1 -> R.string.chat_signal_level_weak
                    else -> R.string.chat_signal_level_poor
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SignalCellularAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(levelDescription),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.signal_strength_dbm, signalInfo.rssi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = level / 4f,
                    modifier = Modifier.fillMaxWidth()
                )
                val lastUpdated = signalInfo.lastUpdated
                val lastUpdatedText = remember(lastUpdated) {
                    DateUtils.getRelativeTimeSpanString(
                        lastUpdated,
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS
                    ).toString()
                }
                Text(
                    text = stringResource(R.string.chat_signal_last_updated, lastUpdatedText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                Text(
                    text = stringResource(R.string.chat_signal_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SessionInfoCard(
    sessionCode: String,
    isEncrypted: Boolean,
    hasStoredKey: Boolean,
    onEnableEncryption: () -> Unit,
    onShowStoredCode: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val statusText = stringResource(
                if (isEncrypted) {
                    R.string.chat_session_status_encrypted
                } else {
                    R.string.chat_session_status_unencrypted
                }
            )
            val statusColor = if (isEncrypted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
            Text(
                text = stringResource(R.string.session_code_label, sessionCode),
                style = MaterialTheme.typography.bodyMedium
            )
            if (isEncrypted) {
                Text(
                    text = stringResource(R.string.chat_session_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.chat_session_unencrypted_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onEnableEncryption) {
                    Text(text = stringResource(R.string.chat_session_enable_encryption_button))
                }
            }
            if (hasStoredKey) {
                OutlinedButton(onClick = onShowStoredCode) {
                    Text(text = stringResource(R.string.chat_session_show_stored_code_button))
                }
            }
        }
    }
}
