package com.auralis.crisisconnect.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.security.ChildProfileManager
import com.auralis.crisisconnect.service.sos.SosEmergencyContactsStore
import com.auralis.crisisconnect.ui.components.ContactAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picker for the user's SOS emergency contacts (up to [SosEmergencyContactsStore.MAX_CONTACTS]).
 * Only internet-capable contacts qualify — an SOS alert can only be delivered over the E2E
 * internet transport. An empty selection means the automatic ranking chooses the recipients.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosEmergencyContactsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by produceState(initialValue = emptyList<Contact>(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                getContacts(context).filter {
                    it.supportsInternet && !it.peerIsChild && !it.isAuthorityBridge
                }
            }
                .getOrDefault(emptyList())
                .sortedBy { it.name.lowercase() }
        }
    }
    val selection by SosEmergencyContactsStore.observe(context)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val childProfileEnabled by ChildProfileManager.enabledFlow(context).collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sos_ec_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sos_screen_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // On a child device the confirmed parents are the SOS recipients; the manual
            // picker below stays usable only as the no-parents-yet fallback, so say so.
            if (childProfileEnabled) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ) {
                    Text(
                        modifier = Modifier.padding(12.dp),
                        text = stringResource(R.string.child_profile_sos_parents_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = stringResource(R.string.sos_ec_screen_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.sos_ec_counter,
                    selection.size,
                    SosEmergencyContactsStore.MAX_CONTACTS
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (contacts.isEmpty()) {
                Text(
                    text = stringResource(R.string.sos_ec_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(contacts, key = { it.sessionCode }) { contact ->
                        val checked = contact.sessionCode in selection
                        val selectionFull =
                            selection.size >= SosEmergencyContactsStore.MAX_CONTACTS
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (checked || !selectionFull) {
                                    val next = if (checked) {
                                        selection - contact.sessionCode
                                    } else {
                                        selection + contact.sessionCode
                                    }
                                    scope.launch {
                                        SosEmergencyContactsStore.save(context, next)
                                    }
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ContactAvatar(
                                    displayName = contact.name,
                                    stableKey = contact.sessionCode,
                                    photoUrl = contact.peerPhotoUrl.takeIf { it.isNotBlank() },
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(
                                    checked = checked,
                                    enabled = checked || !selectionFull,
                                    onCheckedChange = { isChecked ->
                                        val next = if (isChecked) {
                                            selection + contact.sessionCode
                                        } else {
                                            selection - contact.sessionCode
                                        }
                                        scope.launch {
                                            SosEmergencyContactsStore.save(context, next)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
