package com.auralis.crisisconnect.screens.settings

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.screens.SettingsViewModel
import com.auralis.crisisconnect.security.ChildPinResult
import com.auralis.crisisconnect.security.ChildProfileManager
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import com.auralis.crisisconnect.ui.components.ContactAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A parent-list edit; removal is PIN-gated while the mode is on. */
private sealed interface ParentAction {
    data class Add(val sessionCode: String) : ParentAction
    data class Remove(val sessionCode: String) : ParentAction
}

/**
 * Dedicated settings screen for the child profile mode, laid out like the main settings screen
 * (section headers over cards). Three sections:
 *  - the on/off toggle for THIS device;
 *  - "Parents" — trusted adults for this child, shown only while the mode is on; added by a request
 *    the adult approves on their own device;
 *  - "My children" — contacts whose parent request this device accepted (shown to any parent,
 *    regardless of whether this device is itself in child mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProfileScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showPinDisableDialog by remember { mutableStateOf(false) }
    var showParentPicker by remember { mutableStateOf(false) }
    var pendingParentAction by remember { mutableStateOf<ParentAction?>(null) }
    val enabled = settingsViewModel.childProfileEnabled
    val parents = settingsViewModel.childParents
    val pendingParents = settingsViewModel.childPendingParents
    val children = settingsViewModel.childProfileChildren
    val requestFailedText = stringResource(R.string.child_profile_generic_error)

    // Saved contacts back every section. Keyed on all three sets so add/remove/approval refresh
    // what each section shows. Hidden authority-bridge contacts never qualify.
    val contacts by produceState(
        initialValue = emptyList<Contact>(),
        parents, pendingParents, children
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { getContacts(context) }.getOrDefault(emptyList())
                .filter { !it.isAuthorityBridge }
                .sortedBy { it.name.lowercase() }
        }
    }
    val parentContacts = contacts.filter { it.sessionCode in parents }
    val pendingContacts = contacts.filter { it.sessionCode in pendingParents }
    val childContacts = contacts.filter { it.sessionCode in children }
    // The request travels over the E2E internet transport, so only internet-capable,
    // non-child contacts qualify as parent candidates.
    val eligibleContacts = contacts.filter {
        it.sessionCode !in parents && it.sessionCode !in pendingParents &&
            !it.peerIsChild && it.supportsInternet
    }

    fun openChat(sessionCode: String) {
        navController.navigate("chat/${Uri.encode(sessionCode)}")
    }

    fun performParentAction(action: ParentAction) {
        when (action) {
            is ParentAction.Add -> {
                val contact = contacts.firstOrNull { it.sessionCode == action.sessionCode } ?: return
                settingsViewModel.requestChildParent(contact) { sent ->
                    if (!sent) {
                        Toast.makeText(context, requestFailedText, Toast.LENGTH_LONG).show()
                    }
                }
            }
            is ParentAction.Remove -> settingsViewModel.removeChildParent(action.sessionCode)
        }
    }

    // Removing a parent while the mode is on is PIN-gated so the child can't quietly drop their
    // supervision; adding one is free (the adult still has to approve on their side).
    fun requestParentAction(action: ParentAction) {
        if (enabled && action is ParentAction.Remove) {
            pendingParentAction = action
        } else {
            performParentAction(action)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppBackTopBar(
                titleRes = R.string.child_profile_title,
                onNavigateBack = { navController.popBackStack() }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                ChildProfileToggleCard(
                    enabled = enabled,
                    onToggle = {
                        if (enabled) showPinDisableDialog = true else showPinSetupDialog = true
                    }
                )

                if (enabled) {
                    SettingsSection(
                        title = stringResource(R.string.child_profile_parents_title),
                        description = stringResource(R.string.child_profile_parents_description)
                    ) {
                        ParentsCard(
                            parentContacts = parentContacts,
                            pendingContacts = pendingContacts,
                            onOpenChat = { openChat(it) },
                            onRemove = { requestParentAction(ParentAction.Remove(it)) },
                            onAdd = { showParentPicker = true }
                        )
                    }
                }

                if (childContacts.isNotEmpty()) {
                    SettingsSection(
                        title = stringResource(R.string.child_profile_children_title),
                        description = stringResource(R.string.child_profile_children_description)
                    ) {
                        ChildrenCard(
                            childContacts = childContacts,
                            onOpenChat = { openChat(it) },
                            onRemove = { settingsViewModel.removeChildProfileChild(it) }
                        )
                    }
                }
            }
        }
    }

    if (showParentPicker) {
        ParentPickerSheet(
            eligibleContacts = eligibleContacts,
            onDismiss = { showParentPicker = false },
            onPick = { sessionCode ->
                showParentPicker = false
                requestParentAction(ParentAction.Add(sessionCode))
            }
        )
    }

    if (showPinSetupDialog) {
        ChildPinSetupDialog(
            onDismiss = { showPinSetupDialog = false },
            onSubmit = { pin, onFailed ->
                settingsViewModel.enableChildProfile(pin) { success ->
                    if (success) showPinSetupDialog = false else onFailed()
                }
            }
        )
    }

    if (showPinDisableDialog) {
        ChildPinEntryDialog(
            titleRes = R.string.child_profile_enter_pin_title,
            messageRes = R.string.child_profile_enter_pin_message,
            onDismiss = { showPinDisableDialog = false },
            onSubmit = { pin, onResult ->
                settingsViewModel.disableChildProfile(pin) { result ->
                    if (result is ChildPinResult.Success) {
                        showPinDisableDialog = false
                    } else {
                        onResult(result)
                    }
                }
            }
        )
    }

    pendingParentAction?.let { action ->
        ChildPinEntryDialog(
            titleRes = R.string.child_profile_pin_prompt_title,
            messageRes = R.string.child_profile_pin_prompt_message,
            onDismiss = { pendingParentAction = null },
            onSubmit = { pin, onResult ->
                settingsViewModel.verifyChildPin(pin) { result ->
                    if (result is ChildPinResult.Success) {
                        performParentAction(action)
                        pendingParentAction = null
                    } else {
                        onResult(result)
                    }
                }
            }
        )
    }
}

/** Settings-style section: a title + description above [content] (usually a card). */
@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ChildProfileToggleCard(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                ) {
                    Icon(
                        modifier = Modifier.padding(10.dp),
                        imageVector = Icons.Outlined.ChildCare,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.child_profile_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            if (enabled) {
                                R.string.child_profile_status_on
                            } else {
                                R.string.child_profile_status_off
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Switch(checked = enabled, onCheckedChange = { onToggle() })
            }
            Text(
                text = stringResource(R.string.child_profile_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ParentsCard(
    parentContacts: List<Contact>,
    pendingContacts: List<Contact>,
    onOpenChat: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (parentContacts.isEmpty() && pendingContacts.isEmpty()) {
                Text(
                    text = stringResource(R.string.child_profile_no_parents),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            } else {
                parentContacts.forEach { contact ->
                    ContactListRow(
                        contact = contact,
                        subtitle = null,
                        onClick = { onOpenChat(contact.sessionCode) },
                        onRemove = { onRemove(contact.sessionCode) }
                    )
                }
                pendingContacts.forEach { contact ->
                    ContactListRow(
                        contact = contact,
                        subtitle = stringResource(R.string.child_profile_parent_pending),
                        onClick = { onOpenChat(contact.sessionCode) },
                        onRemove = { onRemove(contact.sessionCode) }
                    )
                }
            }

            FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Outlined.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.child_profile_add_parent_button))
            }
        }
    }
}

@Composable
private fun ChildrenCard(
    childContacts: List<Contact>,
    onOpenChat: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            childContacts.forEach { contact ->
                ContactListRow(
                    contact = contact,
                    subtitle = null,
                    onClick = { onOpenChat(contact.sessionCode) },
                    onRemove = { onRemove(contact.sessionCode) }
                )
            }
        }
    }
}

@Composable
private fun ContactListRow(
    contact: Contact,
    subtitle: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            displayName = contact.name,
            stableKey = contact.sessionCode,
            photoUrl = contact.peerPhotoUrl.takeIf { it.isNotBlank() },
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onClick),
            textStyle = MaterialTheme.typography.labelLarge
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.child_profile_remove_parent),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentPickerSheet(
    eligibleContacts: List<Contact>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.child_profile_parent_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (eligibleContacts.isEmpty()) {
                Text(
                    text = stringResource(R.string.child_profile_parent_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                eligibleContacts.forEach { contact ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPick(contact.sessionCode) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ContactAvatar(
                                displayName = contact.name,
                                stableKey = contact.sessionCode,
                                photoUrl = contact.peerPhotoUrl.takeIf { it.isNotBlank() },
                                modifier = Modifier.size(36.dp),
                                textStyle = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for choosing the child profile PIN while turning the mode on.
 * [onSubmit] receives the validated PIN plus a callback the caller invokes
 * if persisting the PIN fails, so the dialog can surface a retry error.
 */
@Composable
private fun ChildPinSetupDialog(
    onDismiss: () -> Unit,
    onSubmit: (pin: String, onFailed: () -> Unit) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.child_profile_set_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.child_profile_set_pin_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChildPinTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    labelRes = R.string.child_profile_pin_label
                )
                ChildPinTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it },
                    labelRes = R.string.child_profile_pin_confirm_label
                )
                errorRes?.let { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        !ChildProfileManager.isValidPin(pin) ->
                            errorRes = R.string.child_profile_pin_length_error
                        pin != confirmPin ->
                            errorRes = R.string.child_profile_pin_mismatch_error
                        else -> onSubmit(pin) {
                            errorRes = R.string.child_profile_generic_error
                        }
                    }
                }
            ) {
                Text(text = stringResource(R.string.child_profile_pin_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Generic PIN prompt used for turning the mode off and for PIN-gated parent removal.
 * [onSubmit] reports failures back through its callback; on [ChildPinResult.Success]
 * the caller closes the dialog itself.
 */
@Composable
private fun ChildPinEntryDialog(
    titleRes: Int,
    messageRes: Int,
    onDismiss: () -> Unit,
    onSubmit: (pin: String, onResult: (ChildPinResult) -> Unit) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<ChildPinResult?>(null) }

    val errorText = when (val result = failure) {
        is ChildPinResult.WrongPin ->
            stringResource(R.string.child_profile_wrong_pin, result.attemptsLeft)
        is ChildPinResult.LockedOut ->
            stringResource(R.string.child_profile_locked_out, result.remainingSeconds)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChildPinTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    labelRes = R.string.child_profile_pin_label
                )
                errorText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(pin) { result ->
                        failure = result
                        pin = ""
                    }
                }
            ) {
                Text(text = stringResource(R.string.child_profile_pin_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ChildPinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(
                input.filter(Char::isDigit).take(ChildProfileManager.PIN_MAX_LENGTH)
            )
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    )
}
