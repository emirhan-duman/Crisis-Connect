package com.auralis.crisisconnect.screens

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.messaging.ContactAccessConsent
import com.auralis.crisisconnect.messaging.ContactDirectoryCache
import com.auralis.crisisconnect.messaging.DeviceContactsReader
import com.auralis.crisisconnect.messaging.DiscoveredContact
import com.auralis.crisisconnect.messaging.InternetConversation
import com.auralis.crisisconnect.messaging.InternetMessagingClient
import android.widget.Toast
import com.auralis.crisisconnect.InAppReviewManager
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.nearby.NearbyContactScanner
import com.auralis.crisisconnect.nearby.NearbyDiscoveryPreferences
import com.auralis.crisisconnect.nearby.NearbyPairingClient
import com.auralis.crisisconnect.nearby.NearbyPairingSupport
import kotlinx.coroutines.flow.first
import com.auralis.crisisconnect.ui.components.ContactAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CONTACT_LOOKUP_BATCH = 1500

// The server rejects two scans less than 2s apart (anti-scrape throttle), so an address book that
// needs more than one batch must space them out — otherwise the second batch fails and takes the
// whole scan down with it.
private const val CONTACT_LOOKUP_BATCH_SPACING_MS = 2_500L

// Re-entering the screen (or coming back from recents) does not need a fresh directory query: the
// cached list is already on screen and a scan this soon only burns the per-user daily quota. The
// toolbar refresh button bypasses this for anyone who wants an answer right now.
private const val MIN_REFRESH_INTERVAL_MS = 60_000L

class AddFromContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val client by lazy { InternetMessagingClient() }

    private val consent = ContactAccessConsent(application)
    private var lastConsentToken: String? = consent.token(true)
    var hasDiscoveryConsent by mutableStateOf(consent.isGranted(online = true))
        private set

    fun acceptDiscoveryDisclosure() {
        consent.accept(online = true)
        refreshConsent()
    }

    fun refreshConsent() {
        val token = consent.token(online = true)
        hasDiscoveryConsent = token != null
        if (token == null || token != lastConsentToken) {
            _state.value = UiState.Idle
            cacheRestored = false
            lastScanAtMs = 0L
        }
        lastConsentToken = token
    }

    data class MatchItem(
        val displayName: String,
        val phone: String,
        val uid: String,
        val publicKey: String,
        val photoUrl: String,
        val isChild: Boolean = false
    )

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data object NotSignedIn : UiState
        data class Loaded(val matches: List<MatchItem>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state = _state.asStateFlow()
    /** True while a scan runs behind results that are already on screen. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private var loadInFlight = false
    private var cacheRestored = false
    private var lastScanAtMs = 0L

    /**
     * Shows the last known matches, then rescans the address book against the directory.
     *
     * WhatsApp-style: the previous result is restored from disk and rendered immediately, so the
     * screen is never a blank spinner for someone who has been here before — the scan (address-book
     * read + directory query, easily a few seconds on a big phone book) runs silently behind it,
     * and a failure (offline, server throttle) keeps the last good list instead of replacing it
     * with an error. Only the very first scan on a device blocks with a spinner.
     *
     * Pass [force] for an explicit user-initiated refresh, which skips the interval gate.
     */
    fun load(force: Boolean = false) {
        // Also protects toolbar refresh/retry/resume, independently of the disclosure UI.
        refreshConsent()
        val consentToken = consent.token(online = true) ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return
        val uid = client.currentUid()
        if (uid == null) {
            _state.value = UiState.NotSignedIn
            return
        }
        if (loadInFlight) return
        loadInFlight = true
        viewModelScope.launch {
            try {
                if (!consent.isCurrent(true, consentToken)) return@launch
                if (!cacheRestored) {
                    cacheRestored = true
                    val cached = withContext(Dispatchers.IO) { ContactDirectoryCache.read(appContext, uid) }
                    if (!consent.isCurrent(true, consentToken)) return@launch
                    if (cached != null && _state.value !is UiState.Loaded) {
                        lastScanAtMs = cached.scannedAtMs
                        _state.value = UiState.Loaded(
                            cached.entries.map {
                                MatchItem(
                                    displayName = it.displayName,
                                    phone = it.phone,
                                    uid = it.uid,
                                    publicKey = it.publicKey,
                                    photoUrl = it.photoUrl,
                                    isChild = it.isChild
                                )
                            }
                        )
                    }
                }
                val refreshingBehindResults = _state.value is UiState.Loaded
                val sinceLastScan = System.currentTimeMillis() - lastScanAtMs
                if (!force && refreshingBehindResults && sinceLastScan in 0 until MIN_REFRESH_INTERVAL_MS) {
                    loadInFlight = false
                    return@launch
                }
                if (refreshingBehindResults) _refreshing.value = true else _state.value = UiState.Loading
                val result = runCatching {
                    val contacts = withContext(Dispatchers.IO) {
                        check(consent.isCurrent(true, consentToken))
                        DeviceContactsReader.read(appContext)
                    }
                    check(consent.isCurrent(true, consentToken))
                    val nameByPhone = contacts.associate { it.e164 to it.displayName }
                    val batches = contacts.map { it.e164 }.chunked(CONTACT_LOOKUP_BATCH)
                    val matches = ArrayList<DiscoveredContact>()
                    batches.forEachIndexed { index, batch ->
                        if (index > 0) delay(CONTACT_LOOKUP_BATCH_SPACING_MS)
                        check(consent.isCurrent(true, consentToken))
                        matches += client.findContacts(batch)
                    }
                    matches.distinctBy { it.uid }
                        .map {
                            MatchItem(
                                displayName = nameByPhone[it.phone] ?: it.phone,
                                phone = it.phone,
                                uid = it.uid,
                                publicKey = it.publicKeyBase64,
                                photoUrl = it.photoUrl,
                                isChild = it.isChild
                            )
                        }
                        .sortedBy { it.displayName.lowercase() }
                }
                if (!consent.isCurrent(true, consentToken)) return@launch
                result.fold(
                    onSuccess = { matches ->
                        lastScanAtMs = System.currentTimeMillis()
                        _state.value = UiState.Loaded(matches)
                        withContext(Dispatchers.IO) {
                            if (!consent.isCurrent(true, consentToken)) return@withContext
                            ContactDirectoryCache.write(
                                context = appContext,
                                uid = uid,
                                entries = matches.map {
                                    ContactDirectoryCache.Entry(
                                        displayName = it.displayName,
                                        phone = it.phone,
                                        uid = it.uid,
                                        publicKey = it.publicKey,
                                        photoUrl = it.photoUrl,
                                        isChild = it.isChild
                                    )
                                },
                                scannedAtMs = lastScanAtMs
                            )
                        }
                    },
                    onFailure = {
                        if (it is CancellationException) throw it
                        if (!refreshingBehindResults) {
                            _state.value = UiState.Error(
                                it.localizedMessage ?: appContext.getString(R.string.unknown_error)
                            )
                        }
                    }
                )
            } finally {
                _refreshing.value = false
                loadInFlight = false
                refreshConsent()
            }
        }
    }

    /** Creates or reuses an internet contact for [item]; returns its sessionCode for navigation. */
    suspend fun addContact(item: MatchItem): String? {
        val myUid = client.currentUid() ?: return null
        return withContext(Dispatchers.IO) {
            val existing = getContacts(appContext).firstOrNull { it.peerUid == item.uid }
            if (existing != null) {
                val keyChanged = existing.peerPublicKey.isNotBlank() &&
                    existing.peerPublicKey != item.publicKey
                saveContact(
                    appContext,
                    existing.copy(
                        peerPublicKey = item.publicKey,
                        peerPhotoUrl = item.photoUrl.ifBlank { existing.peerPhotoUrl },
                        name = existing.name.ifBlank { item.displayName },
                        peerKeyChanged = existing.peerKeyChanged || keyChanged,
                        // Keep the number so we can auto-bootstrap a Bluetooth link later.
                        peerPhone = item.phone.ifBlank { existing.peerPhone },
                        peerIsChild = item.isChild
                    )
                )
                existing.sessionCode
            } else {
                val sessionCode = InternetConversation.pairId(myUid, item.uid)
                saveContact(
                    appContext,
                    Contact(
                        name = item.displayName,
                        aesKey = "",
                        sessionCode = sessionCode,
                        peerUid = item.uid,
                        peerPublicKey = item.publicKey,
                        peerPhotoUrl = item.photoUrl,
                        peerPhone = item.phone,
                        peerIsChild = item.isChild
                    ),
                    analyticsSource = "directory"
                )
                sessionCode
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFromContactsScreen(navController: NavController) {
    var selectedTab by remember { mutableIntStateOf(0) }
    // Same store owner as the tab below, so this is the one and only scan view model.
    val viewModel: AddFromContactsViewModel = viewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_from_contacts_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Cached results are shown instantly and only auto-rescanned once a minute;
                    // this is the way to ask for an answer right now.
                    if (selectedTab == 0) {
                        IconButton(
                            enabled = viewModel.hasDiscoveryConsent,
                            onClick = { viewModel.load(force = true) }
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.add_from_contacts_refresh)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.add_from_contacts_tab_online)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.add_from_contacts_tab_nearby)) }
                )
            }
            when (selectedTab) {
                0 -> InternetContactsTab(navController)
                else -> NearbyContactsTab(navController)
            }
        }
    }
}

@Composable
private fun InternetContactsTab(navController: NavController) {
    val context = LocalContext.current
    val viewModel: AddFromContactsViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    if (!viewModel.hasDiscoveryConsent) {
        ContactsDisclosure(
            online = true,
            onAccept = viewModel::acceptDiscoveryDisclosure,
            onDecline = { navController.popBackStack() }
        )
        return
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.load()
    }
    // Rescan on every ON_RESUME — both first entry (addObserver replays the current state) and
    // returning from background/recents — so someone who registered while this screen sat open
    // appears without any manual refresh. The system permission dialog is only auto-launched
    // once per visit: its dismissal itself resumes the activity, which would otherwise re-ask
    // in a loop after a denial (the empty state's own button still lets the user retry).
    var askedForPermission by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshConsent()
                if (!viewModel.hasDiscoveryConsent) return@LifecycleEventObserver
                hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    viewModel.load()
                } else if (!askedForPermission) {
                    askedForPermission = true
                    permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // A hairline instead of a full-screen spinner: the cached list stays readable (and
        // tappable) while the rescan runs. The strip keeps its height either way so nothing
        // below it jumps when a scan starts or ends.
        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            if (refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                is AddFromContactsViewModel.UiState.Loading ->
                    LoadingState(stringResource(R.string.add_from_contacts_loading))

                is AddFromContactsViewModel.UiState.NotSignedIn ->
                    EmptyState(
                        icon = Icons.Filled.PersonSearch,
                        title = stringResource(R.string.add_from_contacts_sign_in_required)
                    )

                is AddFromContactsViewModel.UiState.Error ->
                    EmptyState(
                        icon = Icons.Filled.WifiOff,
                        title = stringResource(R.string.add_from_contacts_error_title),
                        subtitle = s.message,
                        actionLabel = stringResource(R.string.retry),
                        onAction = { viewModel.load() }
                    )

                is AddFromContactsViewModel.UiState.Loaded -> {
                    if (s.matches.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.PersonSearch,
                            title = stringResource(R.string.add_from_contacts_empty),
                            subtitle = stringResource(R.string.add_from_contacts_empty_hint)
                        )
                    } else {
                        var query by remember { mutableStateOf("") }
                        val filtered = remember(s.matches, query) {
                            val q = query.trim()
                            if (q.isEmpty()) s.matches
                            else s.matches.filter {
                                it.displayName.contains(q, ignoreCase = true) ||
                                    it.phone.contains(q, ignoreCase = true)
                            }
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                singleLine = true,
                                shape = MaterialTheme.shapes.large,
                                placeholder = { Text(stringResource(R.string.add_from_contacts_search_hint)) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(
                                                    R.string.add_from_contacts_search_clear
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                            if (filtered.isEmpty()) {
                                EmptyState(
                                    icon = Icons.Filled.PersonSearch,
                                    title = stringResource(
                                        R.string.add_from_contacts_search_no_results,
                                        query.trim()
                                    )
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        SectionHeader(
                                            text = stringResource(
                                                R.string.add_from_contacts_found_count,
                                                filtered.size
                                            )
                                        )
                                    }
                                    items(filtered, key = { it.uid }) { item ->
                                        ContactMatchRow(
                                            displayName = item.displayName,
                                            phone = item.phone,
                                            stableKey = item.uid,
                                            photoUrl = item.photoUrl,
                                            onClick = {
                                                scope.launch {
                                                    val code = viewModel.addContact(item)
                                                    if (code != null) {
                                                        // Calm, positive moment: let Play decide
                                                        // whether to ask the user for a rating.
                                                        InAppReviewManager.onPositiveEvent(context)
                                                        navController.navigate(
                                                            "chat/${Uri.encode(code)}?displayName=${Uri.encode(item.displayName)}"
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is AddFromContactsViewModel.UiState.Idle ->
                    if (!hasPermission) {
                        EmptyState(
                            icon = Icons.Filled.PersonSearch,
                            title = stringResource(R.string.add_from_contacts_permission_needed),
                            actionLabel = stringResource(R.string.grant_permission),
                            onAction = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }
                        )
                    }
            }
        }
    }
}

@Composable
private fun NearbyContactsTab(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Local contact access must never opt the user into internet matching.
    val consent = remember { ContactAccessConsent(context) }
    var hasLocalConsent by remember { mutableStateOf(consent.isGranted(online = false)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasLocalConsent = consent.isGranted(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!hasLocalConsent) {
        ContactsDisclosure(
            online = false,
            onAccept = {
                consent.accept(online = false)
                hasLocalConsent = consent.isGranted(false)
            },
            onDecline = { navController.popBackStack() }
        )
        return
    }
    val devices by NearbyContactScanner.devices.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf(false) }
    var readingSelection by remember { mutableStateOf(false) }

    var hasScanPermission by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasScanPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasScanPermission) scanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
    }
    // Discovery (generic beacon + SPAKE2 responder + device scan) runs in RfcommForegroundService
    // while opted in; this tab drives the targeted search (pick who you're looking for → SPAKE2).
    val enabled by NearbyDiscoveryPreferences.isEnabled(context).collectAsStateWithLifecycle(initialValue = false)

    // Search a specific number by running SPAKE2 against each nearby device; only the real person
    // (same number → same password) completes. No number is broadcast; wrong devices learn nothing.
    fun search(phone: String, name: String) {
        if (pairing || !consent.isGranted(false) || !enabled || !hasScanPermission) return
        val consentToken = consent.token(false) ?: return
        pairing = true
        scope.launch {
            try {
                var paired: NearbyPairingClient.Result.Paired? = null
                for (address in NearbyContactScanner.currentAddresses()) {
                    if (!consent.isCurrent(false, consentToken)) return@launch
                    val result = NearbyPairingClient.pair(
                        context = context,
                        bluetoothAddress = address,
                        candidatePhone = phone,
                        candidateName = name
                    )
                    if (result is NearbyPairingClient.Result.Paired) { paired = result; break }
                }
                if (!consent.isCurrent(false, consentToken)) return@launch
                val found = paired
                if (found != null) {
                    navController.navigate(
                        "chat/${Uri.encode(found.sessionCode)}?displayName=${Uri.encode(found.displayName)}"
                    )
                } else {
                    Toast.makeText(context, R.string.nearby_pair_not_found, Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, R.string.nearby_pair_not_found, Toast.LENGTH_SHORT).show()
            } finally {
                pairing = false
            }
        }
    }

    // Request only one phone number, with no broad READ_CONTACTS permission.
    var selectionConsentToken by remember { mutableStateOf<String?>(null) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = selectionConsentToken
        selectionConsentToken = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && token != null && consent.isCurrent(false, token)) {
            scope.launch {
                readingSelection = true
                try {
                    val selected = withContext(Dispatchers.IO) {
                        if (!consent.isCurrent(false, token)) return@withContext null
                        try {
                            DeviceContactsReader.readSelected(context, uri)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                    }
                    if (!consent.isCurrent(false, token)) return@launch
                    if (selected != null) search(selected.e164, selected.displayName)
                    else Toast.makeText(context, R.string.nearby_contact_selection_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    readingSelection = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NearbyOptInCard()
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !enabled -> EmptyState(
                    icon = Icons.Filled.PersonSearch,
                    title = stringResource(R.string.nearby_enable_hint)
                )

                !hasScanPermission -> EmptyState(
                    icon = Icons.Filled.PersonSearch,
                    title = stringResource(R.string.nearby_scan_permission_needed),
                    actionLabel = stringResource(R.string.grant_permission),
                    onAction = { scanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN) }
                )

                pairing -> LoadingState(stringResource(R.string.nearby_pairing_in_progress))

                readingSelection -> LoadingState(stringResource(R.string.nearby_choose_contact))

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    SectionHeader(text = stringResource(R.string.nearby_devices_count, devices.size))
                    EmptyState(
                        icon = Icons.Filled.PersonSearch,
                        title = stringResource(R.string.nearby_pick_contact),
                        actionLabel = stringResource(R.string.nearby_choose_contact),
                        onAction = {
                            selectionConsentToken = consent.token(false)
                            try {
                                contactPicker.launch(DeviceContactsReader.phonePickerIntent())
                            } catch (_: ActivityNotFoundException) {
                                selectionConsentToken = null
                                Toast.makeText(context, R.string.nearby_contact_selection_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

/** Dismissal, Back and the negative action all leave without granting consent. */
@Composable
internal fun ContactsDisclosure(online: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = {
            Text(stringResource(if (online) R.string.contacts_disclosure_title else R.string.contacts_local_disclosure_title))
        },
        text = {
            Text(
                stringResource(if (online) R.string.contacts_disclosure_body else R.string.contacts_local_disclosure_body),
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.contacts_disclosure_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.contacts_disclosure_decline)) }
        }
    )
}

@Composable
internal fun ContactConsentSettings() {
    val context = LocalContext.current
    val consent = remember { ContactAccessConsent(context) }
    var online by remember { mutableStateOf(consent.isGranted(true)) }
    var nearby by remember { mutableStateOf(consent.isGranted(false)) }
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.contacts_consent_settings_hint), style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = online, onClick = { consent.revoke(true); online = false }) {
            Text(stringResource(R.string.contacts_consent_revoke_online))
        }
        TextButton(enabled = nearby, onClick = { consent.revoke(false); nearby = false }) {
            Text(stringResource(R.string.contacts_consent_revoke_nearby))
        }
    }
}

@Composable
private fun NearbyOptInCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by NearbyDiscoveryPreferences.isEnabled(context).collectAsStateWithLifecycle(initialValue = false)
    // Discovery is keyed on the phone number (the SPAKE2 password IS the number), so without one the
    // switch used to be a lie: it flipped, and NearbyContactAdvertiser silently no-oped. Lock it and
    // say why instead — an enabled-looking toggle that does nothing reads as "the feature is broken".
    val hasPhone = NearbyPairingSupport.ownPhoneE164() != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.nearby_discovery_toggle_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        if (hasPhone) R.string.nearby_discovery_toggle_subtitle
                        else R.string.nearby_discovery_phone_required
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                // Without a phone the toggle renders OFF regardless of the stored preference: the
                // preference may have been left on by a since-signed-out account, and showing an
                // "on" switch for a feature that cannot run would be the same lie in reverse.
                checked = enabled && hasPhone,
                enabled = hasPhone,
                onCheckedChange = { checked ->
                    scope.launch {
                        // RfcommForegroundService hosts discovery and reacts to this preference.
                        NearbyDiscoveryPreferences.setEnabled(context, checked)
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun ContactMatchRow(
    displayName: String,
    phone: String,
    stableKey: String,
    photoUrl: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            displayName = displayName,
            stableKey = stableKey,
            photoUrl = photoUrl.ifBlank { null },
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = phone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.add_from_contacts_add_button),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun LoadingState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
