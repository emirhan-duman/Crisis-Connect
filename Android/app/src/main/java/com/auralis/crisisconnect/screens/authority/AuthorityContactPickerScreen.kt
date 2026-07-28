package com.auralis.crisisconnect.screens.authority

import android.app.Application
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.AuthorityRosterClient
import com.auralis.crisisconnect.messaging.AuthorityRosterMember
import com.auralis.crisisconnect.messaging.HierarchyChannel
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Picker grouping, top-down like the web dashboard (up → sibling → down → own management → field). */
enum class AuthorityPickerGroup { UP, SIBLING, DOWN, MANAGEMENT, FIELD }

/** One selectable person: a cross-panel peer (has [channelId]) or an own-agency roster [member]. */
data class AuthorityPickerEntry(
    val uid: String,
    val name: String,
    val subtitle: String,
    val photoUrl: String?,
    val group: AuthorityPickerGroup,
    val channelId: String?,
    val member: AuthorityRosterMember?,
)

private val AUTHORITY_PICKER_SECTIONS = listOf(
    AuthorityPickerGroup.UP to R.string.authority_picker_hq_units,
    AuthorityPickerGroup.SIBLING to R.string.authority_picker_sibling_units,
    AuthorityPickerGroup.DOWN to R.string.authority_picker_sub_units,
    AuthorityPickerGroup.MANAGEMENT to R.string.authority_picker_management,
    AuthorityPickerGroup.FIELD to R.string.authority_picker_field_teams,
)

/**
 * Merges the same-agency roster and the cross-panel (hierarchy) peers into one grouped list, exactly
 * like the web dashboard's messages "add" picker (DashboardMessages `addableContacts` + `pickerSections`):
 * cross-panel peers grouped by direction (up/sibling/down); own-agency members split into management
 * (admin/authority) vs field teams by role.
 */
fun buildAuthorityPickerEntries(
    roster: List<AuthorityRosterMember>,
    channels: List<HierarchyChannel>,
): List<AuthorityPickerEntry> {
    val out = mutableListOf<AuthorityPickerEntry>()
    for (channel in channels) {
        val group = when (channel.group) {
            "up" -> AuthorityPickerGroup.UP
            "sibling" -> AuthorityPickerGroup.SIBLING
            else -> AuthorityPickerGroup.DOWN
        }
        for (peer in channel.peers) {
            if (peer.uid.isBlank()) continue
            out.add(
                AuthorityPickerEntry(
                    uid = peer.uid,
                    name = peer.name,
                    subtitle = peer.agency ?: channel.peerPanelName,
                    photoUrl = peer.photoUrl,
                    group = group,
                    channelId = channel.channelId,
                    member = null,
                )
            )
        }
    }
    for (m in roster) {
        if (m.uid.isBlank()) continue
        val roleKey = m.role.lowercase().replace(Regex("[-_]"), "")
        val group = if (roleKey == "admin" || roleKey == "authority") {
            AuthorityPickerGroup.MANAGEMENT
        } else {
            AuthorityPickerGroup.FIELD
        }
        out.add(
            AuthorityPickerEntry(
                uid = m.uid,
                name = m.name,
                subtitle = m.role,
                photoUrl = m.photoUrl,
                group = group,
                channelId = null,
                member = m,
            )
        )
    }
    return out
}

/**
 * Loads who the signed-in authority can start a conversation with — their own agency roster
 * ([AuthorityRosterClient.listRoster]) plus every cross-panel (hierarchy) peer
 * ([HierarchyMessagingClient.fetchChannels], the same `/api/messaging/hierarchy` the web uses). Unlike
 * the old inline picker, a cross-panel fetch failure is surfaced (not silently swallowed), which is why
 * "I can only add my own agency" happened when that HTTPS call failed.
 */
class AuthorityContactPickerViewModel(app: Application) : AndroidViewModel(app) {
    sealed interface UiState {
        data object Loading : UiState
        data object Error : UiState
        data class Loaded(
            val entries: List<AuthorityPickerEntry>,
            val crossPanelFailed: Boolean,
        ) : UiState
    }

    private val rosterClient = AuthorityRosterClient()
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val slug = resolveAgencySlug()
            if (slug == null) {
                _state.value = UiState.Loaded(emptyList(), crossPanelFailed = false)
                return@launch
            }
            val (rosterResult, channelsResult) = withContext(Dispatchers.IO) {
                val rosterDeferred = async { runCatching { rosterClient.listRoster(slug) } }
                val channelsDeferred = async { runCatching { HierarchyMessagingClient().fetchChannels() } }
                rosterDeferred.await() to channelsDeferred.await()
            }
            if (rosterResult.isFailure && channelsResult.isFailure) {
                _state.value = UiState.Error
                return@launch
            }
            _state.value = UiState.Loaded(
                entries = buildAuthorityPickerEntries(
                    rosterResult.getOrDefault(emptyList()),
                    channelsResult.getOrDefault(emptyList()),
                ),
                crossPanelFailed = channelsResult.isFailure,
            )
        }
    }

    /** Turns an own-agency roster member into a stored contact, returning its session code (or null). */
    suspend fun addContact(member: AuthorityRosterMember): String? =
        runCatching { rosterClient.addContact(getApplication(), member) }.getOrNull()

    private suspend fun resolveAgencySlug(): String? = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous } ?: return@withContext null
        val fromDoc = runCatching {
            FirebaseFirestore.getInstance().document("users/${user.uid}").get().await()
                .getString("agencySlug")?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
        fromDoc ?: runCatching {
            SecurityRepository(getApplication()).getUsableStoredCertificateAgency()
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorityContactPickerScreen(
    navController: NavHostController,
    viewModel: AuthorityContactPickerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var addingKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.new_chat_add_from_agency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.authority_picker_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is AuthorityContactPickerViewModel.UiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is AuthorityContactPickerViewModel.UiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.authority_picker_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { viewModel.load() }) {
                            Text(stringResource(R.string.authority_msg_retry))
                        }
                    }
                }
                is AuthorityContactPickerViewModel.UiState.Loaded -> {
                    val filtered = remember(s.entries, query) {
                        val q = query.trim().lowercase()
                        if (q.isEmpty()) s.entries
                        else s.entries.filter {
                            it.name.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
                        }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            placeholder = { Text(stringResource(R.string.authority_picker_search_hint)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        )

                        if (s.crossPanelFailed) {
                            CrossPanelWarningBanner(onRetry = { viewModel.load() })
                        }

                        if (filtered.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.authority_roster_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for ((group, labelRes) in AUTHORITY_PICKER_SECTIONS) {
                                    val entries = filtered.filter { it.group == group }
                                    if (entries.isEmpty()) continue
                                    item(key = "hdr_${group.name}") {
                                        Text(
                                            text = stringResource(labelRes),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                                        )
                                    }
                                    items(entries, key = { "${it.channelId ?: "ag"}:${it.uid}" }) { entry ->
                                        val entryKey = "${entry.channelId ?: "ag"}:${entry.uid}"
                                        AuthorityPickerRow(
                                            entry = entry,
                                            adding = addingKey == entryKey,
                                            onClick = {
                                                if (addingKey != null) return@AuthorityPickerRow
                                                val channelId = entry.channelId
                                                val member = entry.member
                                                if (channelId != null) {
                                                    navController.navigate(
                                                        "authority_channel/${Uri.encode(channelId)}/" +
                                                            "${Uri.encode(entry.uid)}?title=${Uri.encode(entry.name)}" +
                                                            "&agency=${Uri.encode(entry.subtitle)}"
                                                    )
                                                } else if (member != null) {
                                                    addingKey = entryKey
                                                    scope.launch {
                                                        val sessionCode = viewModel.addContact(member)
                                                        addingKey = null
                                                        if (!sessionCode.isNullOrBlank()) {
                                                            navController.navigate(
                                                                "chat/${Uri.encode(sessionCode)}" +
                                                                    "?displayName=${Uri.encode(entry.name)}"
                                                            )
                                                        }
                                                    }
                                                }
                                            },
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

@Composable
private fun AuthorityPickerRow(
    entry: AuthorityPickerEntry,
    adding: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !adding,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContactAvatar(
                displayName = entry.name,
                stableKey = "authpick:${entry.channelId ?: "ag"}:${entry.uid}",
                photoUrl = entry.photoUrl,
                modifier = Modifier.size(46.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.subtitle.isNotBlank()) {
                    Text(
                        text = entry.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (adding) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CrossPanelWarningBanner(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.authority_picker_cross_panel_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.authority_msg_retry))
            }
        }
    }
}
