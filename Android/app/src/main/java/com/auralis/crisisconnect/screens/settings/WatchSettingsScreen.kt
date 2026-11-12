package com.auralis.crisisconnect.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Quickreply
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sos
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.screens.GroupedSettingsCard
import com.auralis.crisisconnect.screens.SettingsSectionHeader
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.watch.IndependentPhoneInbox
import com.auralis.crisisconnect.watch.QuickReplyItem
import com.auralis.crisisconnect.watch.WatchBatteryStore
import com.auralis.crisisconnect.watch.WatchContactMessaging
import com.auralis.crisisconnect.watch.WatchQuickRepliesStore
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern Jetpack Compose screen for managing paired Wear OS watch standalone messaging,
 * authorization, sync, and mutual contact device approval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToQrScan: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authUser = remember { FirebaseAuth.getInstance().currentUser }
    val isSignedOut = authUser == null || authUser.isAnonymous

    val inbox = remember(authUser?.uid) {
        if (!isSignedOut && authUser != null) {
            IndependentPhoneInbox(context.applicationContext, authUser.uid)
        } else {
            null
        }
    }

    val batteryStore = remember { WatchBatteryStore(context) }
    var batteryInfo by remember { mutableStateOf(batteryStore.getBattery()) }

    val quickRepliesStore = remember { WatchQuickRepliesStore(context) }
    var quickReplies by remember { mutableStateOf(quickRepliesStore.getAllReplies()) }
    var showAddReplyDialog by remember { mutableStateOf(false) }
    var newReplyText by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }

    var isEnabled by remember { mutableStateOf(inbox?.isEnabled() == true) }
    var pairedWatchId by remember { mutableStateOf(inbox?.pairedWatchId()) }
    var lastSyncMillis by remember { mutableStateOf(inbox?.lastSyncMillis() ?: 0L) }
    var isBusy by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf<String?>(null) }
    var isErrorNotice by remember { mutableStateOf(false) }

    // Wear OS nearby connected node name
    var connectedWatchNodeName by remember { mutableStateOf<String?>(null) }
    var isCheckingHardware by remember { mutableStateOf(true) }

    // Check connected Wear OS hardware
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                connectedWatchNodeName = nodes.firstOrNull()?.displayName
            }
            isCheckingHardware = false
        }
    }

    // Expanded contacts and cached devices
    val expandedContacts = remember { mutableStateMapOf<String, Boolean>() }
    val contactDevices = remember { mutableStateMapOf<String, List<JSONObject>>() }
    val loadingContacts = remember { mutableStateMapOf<String, Boolean>() }

    // Selected device for modal dialog approval/revocation
    var approvalDialogState by remember { mutableStateOf<Triple<Contact, JSONObject, String>?>(null) }
    var showUnpairWatchDialog by remember { mutableStateOf(false) }

    // Eligible contacts for watch messaging
    val eligibleContacts by produceState(initialValue = emptyList<Contact>(), isSignedOut) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                getContacts(context).filter { WatchContactMessaging.eligible(it) }
            }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
        }
    }

    fun showNotice(msg: String, isError: Boolean = false) {
        statusNotice = msg
        isErrorNotice = isError
    }

    fun launchSafe(action: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                showNotice(e.message ?: "Operation failed", isError = true)
            } finally {
                isBusy = false
            }
        }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val lastSyncText = remember(lastSyncMillis) {
        if (lastSyncMillis > 0L) {
            context.getString(R.string.watch_settings_last_sync, timeFormatter.format(Date(lastSyncMillis)))
        } else {
            context.getString(R.string.watch_settings_never_synced)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.watch_settings_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Explanation Card
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Watch,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.watch_settings_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.watch_settings_header_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Signed out warning banner if applicable
            if (isSignedOut || inbox == null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.watch_settings_sign_in_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            } else {
                // Status Notice Banner (if any message was produced)
                statusNotice?.let { notice ->
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isErrorNotice) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isErrorNotice) MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isErrorNotice) Icons.Outlined.Info else Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isErrorNotice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = notice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isErrorNotice) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Section 1: Status & Controls
                item {
                    SettingsSectionHeader(title = stringResource(R.string.watch_settings_section_status))
                    GroupedSettingsCard {
                        // Phone Receiver Status Row
                        WatchStatusRow(
                            icon = Icons.Outlined.PhoneAndroid,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.watch_settings_enable_phone),
                            subtitle = if (isEnabled) {
                                stringResource(R.string.watch_settings_status_receiver_active)
                            } else {
                                stringResource(R.string.watch_settings_status_receiver_inactive)
                            },
                            statusText = if (isEnabled) stringResource(R.string.watch_settings_device_approved) else stringResource(R.string.child_profile_status_off),
                            isActive = isEnabled
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Paired Watch Status Row
                        val isPaired = !pairedWatchId.isNullOrEmpty()
                        WatchStatusRow(
                            icon = Icons.Outlined.Watch,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.watch_settings_status_watch),
                            subtitle = if (isPaired) {
                                "ID: ${pairedWatchId!!.take(8)} · $lastSyncText"
                            } else {
                                stringResource(R.string.watch_settings_status_watch_unpaired)
                            },
                            statusText = if (isPaired) stringResource(R.string.watch_settings_status_watch_paired) else stringResource(R.string.watch_settings_status_watch_unpaired),
                            isActive = isPaired
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Wear OS Hardware Link Row
                        val isHwConnected = connectedWatchNodeName != null
                        WatchStatusRow(
                            icon = Icons.Outlined.Devices,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.watch_settings_hw_title),
                            subtitle = connectedWatchNodeName ?: stringResource(R.string.watch_settings_hw_disconnected),
                            statusText = if (isHwConnected) stringResource(R.string.watch_settings_hw_connected) else stringResource(R.string.watch_settings_hw_disconnected),
                            isActive = isHwConnected
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Watch Battery Status Row
                        val hasBattery = batteryInfo != null
                        val batteryTint = when {
                            !hasBattery -> MaterialTheme.colorScheme.onSurfaceVariant
                            batteryInfo!!.percent <= 20 -> MaterialTheme.colorScheme.error
                            batteryInfo!!.percent <= 50 -> Color(0xFFF59E0B)
                            else -> Color(0xFF10B981)
                        }
                        val batterySubtitle = if (hasBattery) {
                            val statusDesc = if (batteryInfo!!.isCharging) {
                                stringResource(R.string.watch_settings_battery_charging)
                            } else if (batteryInfo!!.percent <= 20) {
                                stringResource(R.string.watch_settings_battery_low)
                            } else {
                                stringResource(R.string.watch_settings_battery_normal)
                            }
                            stringResource(R.string.watch_settings_battery_level, batteryInfo!!.percent, statusDesc)
                        } else {
                            stringResource(R.string.watch_settings_battery_unknown)
                        }
                        WatchStatusRow(
                            icon = Icons.Filled.BatterySaver,
                            iconTint = batteryTint,
                            title = stringResource(R.string.watch_settings_battery_title),
                            subtitle = batterySubtitle,
                            statusText = if (hasBattery) "%${batteryInfo!!.percent}" else stringResource(R.string.watch_settings_battery_unknown),
                            isActive = hasBattery && batteryInfo!!.percent > 20
                        )
                    }
                }

                // Section 2: Actions
                item {
                    SettingsSectionHeader(title = stringResource(R.string.watch_settings_section_actions))
                    GroupedSettingsCard {
                        if (!isEnabled) {
                            WatchActionItem(
                                icon = Icons.Outlined.Link,
                                title = stringResource(R.string.watch_settings_enable_phone),
                                subtitle = stringResource(R.string.watch_settings_enable_phone_subtitle),
                                isBusy = isBusy,
                                onClick = {
                                    launchSafe {
                                        inbox.enable()
                                        isEnabled = inbox.isEnabled()
                                        showNotice(context.getString(R.string.watch_settings_phone_ready))
                                    }
                                }
                            )
                        } else {
                            // Pair Watch Action
                            WatchActionItem(
                                icon = Icons.Outlined.Devices,
                                title = stringResource(R.string.watch_settings_pair_watch),
                                subtitle = stringResource(R.string.watch_settings_pair_watch_subtitle),
                                isBusy = isBusy,
                                onClick = {
                                    launchSafe {
                                        inbox.pairWatch()
                                        pairedWatchId = inbox.pairedWatchId()
                                        showNotice(context.getString(R.string.watch_settings_watch_paired_msg))
                                    }
                                }
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )

                            // Pair Watch via QR Code Action
                            WatchActionItem(
                                icon = Icons.Filled.QrCodeScanner,
                                title = stringResource(R.string.watch_settings_scan_qr_title),
                                subtitle = stringResource(R.string.watch_settings_scan_qr_desc),
                                isBusy = isBusy,
                                onClick = {
                                    onNavigateToQrScan()
                                }
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )

                            // Check & Sync Action
                            WatchActionItem(
                                icon = Icons.Outlined.Refresh,
                                title = stringResource(R.string.watch_settings_refresh),
                                subtitle = "$lastSyncText · ${stringResource(R.string.watch_settings_refresh_subtitle)}",
                                isBusy = isBusy,
                                onClick = {
                                    launchSafe {
                                        inbox.refresh()
                                        lastSyncMillis = inbox.lastSyncMillis()
                                        showNotice(context.getString(R.string.watch_settings_sync_success))
                                    }
                                }
                            )

                            // Unpair Watch Action (only visible when a watch is paired)
                            if (!pairedWatchId.isNullOrEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                WatchActionItem(
                                    icon = Icons.Outlined.LinkOff,
                                    iconTint = MaterialTheme.colorScheme.error,
                                    title = stringResource(R.string.watch_settings_unpair_watch),
                                    subtitle = stringResource(R.string.watch_settings_unpair_watch_subtitle),
                                    isBusy = isBusy,
                                    onClick = {
                                        showUnpairWatchDialog = true
                                    }
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )

                            // Revoke Receiver Action
                            WatchActionItem(
                                icon = Icons.Outlined.DeleteOutline,
                                iconTint = MaterialTheme.colorScheme.error,
                                title = stringResource(R.string.watch_settings_disable_phone),
                                subtitle = stringResource(R.string.watch_settings_disable_phone_subtitle),
                                isBusy = isBusy,
                                onClick = {
                                    launchSafe {
                                        inbox.disable()
                                        isEnabled = inbox.isEnabled()
                                        showNotice(context.getString(R.string.watch_settings_revoked_msg))
                                    }
                                }
                            )
                        }
                    }
                }

                // Section 3: Registered Contacts & Device Verification
                item {
                    SettingsSectionHeader(title = stringResource(R.string.watch_settings_section_contacts))
                    Text(
                        text = stringResource(R.string.watch_settings_contacts_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )

                    if (eligibleContacts.isEmpty()) {
                        GroupedSettingsCard {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.watch_settings_no_contacts),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        GroupedSettingsCard {
                            eligibleContacts.forEachIndexed { index, contact ->
                                val isExpanded = expandedContacts[contact.sessionCode] == true
                                val isLoadingThis = loadingContacts[contact.sessionCode] == true
                                val devices = contactDevices[contact.sessionCode] ?: emptyList()

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateContentSize()
                                ) {
                                    if (index > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                        )
                                    }

                                    // Contact row header
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (!isEnabled) {
                                                    showNotice(
                                                        context.getString(R.string.watch_settings_phone_not_enabled_hint),
                                                        isError = true
                                                    )
                                                    return@clickable
                                                }
                                                val nextExpanded = !isExpanded
                                                expandedContacts[contact.sessionCode] = nextExpanded
                                                if (nextExpanded && !contactDevices.containsKey(contact.sessionCode)) {
                                                    loadingContacts[contact.sessionCode] = true
                                                    scope.launch {
                                                        try {
                                                            val fetched = inbox.devices(contact)
                                                            contactDevices[contact.sessionCode] = fetched
                                                            if (fetched.isEmpty()) {
                                                                showNotice(context.getString(R.string.watch_settings_no_devices))
                                                            }
                                                        } catch (e: Exception) {
                                                            showNotice(e.message ?: "Error", isError = true)
                                                        } finally {
                                                            loadingContacts[contact.sessionCode] = false
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        ContactAvatar(
                                            displayName = contact.name,
                                            stableKey = contact.sessionCode,
                                            photoUrl = contact.peerPhotoUrl,
                                            modifier = Modifier.size(40.dp)
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = contact.sessionCode,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (isLoadingThis) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // Expanded devices list
                                    if (isExpanded) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (devices.isEmpty() && !isLoadingThis) {
                                                Text(
                                                    text = stringResource(R.string.watch_settings_no_devices),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                )
                                            } else {
                                                devices.forEach { device ->
                                                    val kind = device.optString("kind", "watch")
                                                    val isPhone = kind == "phoneInbox"
                                                    val deviceId = device.optString("deviceID", "")
                                                    val isApproved = inbox.isDeviceApproved(deviceId)
                                                    val bundle = device.optJSONObject("bundle")
                                                    val identityKey = bundle?.optString("identityKey", "") ?: ""

                                                    val fingerprint = remember(identityKey) {
                                                        if (identityKey.isNotEmpty()) {
                                                            runCatching {
                                                                MessageDigest.getInstance("SHA-256")
                                                                    .digest(android.util.Base64.decode(identityKey, android.util.Base64.NO_WRAP))
                                                                    .joinToString("") { "%02x".format(it) }
                                                                    .chunked(8)
                                                                    .joinToString(" ")
                                                            }.getOrDefault("")
                                                        } else {
                                                            ""
                                                        }
                                                    }

                                                    Surface(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .clickable {
                                                                approvalDialogState = Triple(contact, device, fingerprint)
                                                            },
                                                        shape = RoundedCornerShape(10.dp),
                                                        color = MaterialTheme.colorScheme.surface,
                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = if (isPhone) Icons.Outlined.PhoneAndroid else Icons.Outlined.Watch,
                                                                contentDescription = null,
                                                                tint = if (isApproved) Color(0xFF059669) else MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(20.dp)
                                                            )

                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = "${if (isPhone) stringResource(R.string.watch_settings_device_phone) else stringResource(R.string.watch_settings_device_watch)} · ${deviceId.take(8)}",
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                if (fingerprint.isNotEmpty()) {
                                                                    Text(
                                                                        text = fingerprint.take(24) + "…",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        fontFamily = FontFamily.Monospace,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                }
                                                            }

                                                            Surface(
                                                                shape = RoundedCornerShape(6.dp),
                                                                color = if (isApproved) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                            ) {
                                                                Text(
                                                                    text = if (isApproved) stringResource(R.string.watch_settings_device_approved) else stringResource(R.string.watch_settings_device_pending),
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = if (isApproved) Color(0xFF059669) else MaterialTheme.colorScheme.primary,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 4: Watch SOS & Quick Replies Information Card
                item {
                    SettingsSectionHeader(title = stringResource(R.string.watch_settings_companion_title))
                    GroupedSettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Sos,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.watch_settings_companion_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.watch_settings_companion_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.watch_settings_quick_replies_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.watch_settings_quick_replies_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Section 5: Custom Quick Replies Management
                item {
                    SettingsSectionHeader(title = stringResource(R.string.watch_settings_quick_replies_manage))
                    Text(
                        text = stringResource(R.string.watch_settings_quick_replies_manage_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )
                    GroupedSettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            quickReplies.forEach { item ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = "“${item.text}”",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (item.isDefault) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                                            ) {
                                                Text(
                                                    text = if (item.isDefault) stringResource(R.string.watch_settings_quick_reply_default_tag) else stringResource(R.string.watch_settings_quick_reply_custom_tag),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (item.isDefault) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        if (!item.isDefault) {
                                            IconButton(
                                                onClick = {
                                                    quickRepliesStore.removeReply(item.text)
                                                    quickReplies = quickRepliesStore.getAllReplies()
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        newReplyText = ""
                                        replyError = null
                                        showAddReplyDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.watch_settings_quick_reply_add), style = MaterialTheme.typography.labelMedium)
                                }

                                TextButton(
                                    onClick = {
                                        quickRepliesStore.resetToDefaults()
                                        quickReplies = quickRepliesStore.getAllReplies()
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.watch_settings_quick_reply_reset), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Dialog for Device Approval & Revocation
    approvalDialogState?.let { (contact, device, fingerprint) ->
        val kind = device.optString("kind", "watch")
        val isPhone = kind == "phoneInbox"
        val kindLabel = if (isPhone) stringResource(R.string.watch_settings_device_phone) else stringResource(R.string.watch_settings_device_watch)
        val deviceId = device.optString("deviceID", "")
        val isAlreadyApproved = inbox?.isDeviceApproved(deviceId) == true

        AlertDialog(
            onDismissRequest = { approvalDialogState = null },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = if (isAlreadyApproved) Color(0xFF059669) else MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = if (isAlreadyApproved) {
                        stringResource(R.string.watch_settings_device_already_approved)
                    } else {
                        stringResource(R.string.watch_settings_device_dialog_title, contact.name, kindLabel)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isAlreadyApproved) {
                            stringResource(R.string.watch_settings_device_already_approved_msg, fingerprint)
                        } else {
                            stringResource(R.string.watch_settings_device_dialog_message, fingerprint)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                if (!isAlreadyApproved) {
                    Button(
                        onClick = {
                            val toApprove = device
                            approvalDialogState = null
                            launchSafe {
                                inbox?.approve(contact, toApprove)
                                showNotice(context.getString(R.string.watch_settings_contact_approved_msg))
                            }
                        }
                    ) {
                        Text(stringResource(R.string.watch_settings_approve_btn))
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isAlreadyApproved) {
                        Button(
                            onClick = {
                                val toRevoke = device
                                approvalDialogState = null
                                launchSafe {
                                    inbox?.revokeContact(toRevoke)
                                    showNotice(context.getString(R.string.watch_settings_contact_revoked_msg))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.watch_settings_revoke_btn))
                        }
                    }
                    TextButton(onClick = { approvalDialogState = null }) {
                        Text(stringResource(R.string.watch_settings_cancel_btn))
                    }
                }
            }
        )
    }

    // Unpair Watch Confirmation Dialog
    if (showUnpairWatchDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairWatchDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.watch_settings_unpair_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.watch_settings_unpair_confirm_msg),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpairWatchDialog = false
                        launchSafe {
                            inbox?.unpairWatch()
                            pairedWatchId = inbox?.pairedWatchId()
                            showNotice(context.getString(R.string.watch_settings_unpaired_notice))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.watch_settings_unpair_watch))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairWatchDialog = false }) {
                    Text(stringResource(R.string.watch_settings_cancel_btn))
                }
            }
        )
    }

    // Add Custom Quick Reply Dialog
    if (showAddReplyDialog) {
        AlertDialog(
            onDismissRequest = { showAddReplyDialog = false },
            title = {
                Text(stringResource(R.string.watch_settings_quick_reply_add))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newReplyText,
                        onValueChange = {
                            if (it.length <= WatchQuickRepliesStore.MAX_REPLY_LENGTH) {
                                newReplyText = it
                                replyError = null
                            }
                        },
                        placeholder = {
                            Text(stringResource(R.string.watch_settings_quick_reply_add_placeholder))
                        },
                        singleLine = true,
                        isError = replyError != null,
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(replyError ?: "")
                                Text("${newReplyText.length}/${WatchQuickRepliesStore.MAX_REPLY_LENGTH}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newReplyText.trim()
                        if (trimmed.isEmpty()) {
                            replyError = context.getString(R.string.watch_settings_quick_reply_limit_notice)
                            return@Button
                        }
                        val added = quickRepliesStore.addCustomReply(trimmed)
                        if (added) {
                            quickReplies = quickRepliesStore.getAllReplies()
                            showAddReplyDialog = false
                        } else {
                            replyError = context.getString(R.string.watch_settings_quick_reply_limit_notice)
                        }
                    }
                ) {
                    Text(stringResource(R.string.sos_ec_prompt_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddReplyDialog = false }) {
                    Text(stringResource(R.string.watch_settings_cancel_btn))
                }
            }
        )
    }
}

@Composable
private fun WatchStatusRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    statusText: String,
    isActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = iconTint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = CircleShape,
            color = if (isActive) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun WatchActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isBusy: Boolean,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isBusy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = iconTint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = iconTint
            )
        } else {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
