@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.auralis.crisisconnect.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.core.chat.stripReplyMetadata
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.GattMeshChatStore
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.MeshMessageStatus
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.feature.RescueFeatureManager
import com.auralis.crisisconnect.navigation.buildConversationRoute
import com.auralis.crisisconnect.navigation.ChatSharedElements
import com.auralis.crisisconnect.screens.Chat.formatCallDuration
import com.auralis.crisisconnect.screens.Chat.parseSharedFilePayload
import com.auralis.crisisconnect.security.FirebaseRoleHelper
import com.auralis.crisisconnect.security.FirebaseRoleHelper.RescueRoleResult
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.GattSOSServerService
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.RfcommForegroundService.CallDirection
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.auralis.crisisconnect.service.RfcommForegroundService.CallResult
import com.auralis.crisisconnect.service.p2p.P2pGattChatManager
import com.auralis.crisisconnect.ui.components.AppBottomBar
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.ui.components.GroupChatAvatar
import com.auralis.crisisconnect.ui.theme.StatusConnectedContainer
import com.auralis.crisisconnect.ui.theme.StatusConnectedOnContainer
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RESCUE_ROLE_LOG_TAG = "RescueRoleCheck"
private const val SOS_BROADCAST_LOG_TAG = "SosBroadcastStart"
private val ALLOWED_RESCUE_ROLES = setOf("admin", "fieldteam")
private const val SEARCH_PULL_MIN_PREVIEW_DURATION_MS = 70L
private const val SEARCH_PULL_MIN_OPEN_DURATION_MS = 130L
private const val MAIN_CONNECTED_LABEL_DURATION_MS = 4_000L
private const val CHAT_LOCATION_PREFIX = "CC_LOC:"
private const val MESH_GENERAL_SESSION_CODE = "gattmesh:general"
private const val MESH_GENERAL_LIST_ITEM_KEY = "gattmesh:general:list_item"
private const val PERMISSION_REQUEST_PREFS = "settings_permission_requests"
private const val PERMISSION_REQUESTED_KEY_PREFIX = "requested_"
private val WELCOME_BLOCKED_BUTTON_COLOR = Color(0xFFC62828)
private val WELCOME_BLOCKED_TEXT_COLOR = Color(0xFFB71C1C)
private val GOOGLE_MAPS_LOCATION_REGEX =
    Regex("""https?://maps\.google\.com/\?q=([-0-9.]+),([-0-9.]+)""")

private enum class WelcomeContinueAction {
    RequestPermissions,
    OpenSettings,
    EnableBluetooth
}

private data class WelcomePermissionRequirement(
    val labelRes: Int,
    val permissions: List<String>
)

private data class WelcomeContinueBlocker(
    val buttonText: String,
    val detailText: String,
    val action: WelcomeContinueAction? = null,
    val actionText: String? = null
)

private fun openLegalLink(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.welcome_link_open_error),
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun Context.hasUsableInternetConnection(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return manager.hasUsableInternetConnection()
}

private fun ConnectivityManager.hasUsableInternetConnection(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun welcomePermissionRequirements(): List<WelcomePermissionRequirement> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        buildList {
            add(
                WelcomePermissionRequirement(
                    labelRes = R.string.permission_group_bluetooth,
                    permissions = listOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    )
                )
            )
            add(
                WelcomePermissionRequirement(
                    labelRes = R.string.permission_group_location,
                    permissions = listOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            )
        }
    } else {
        listOf(
            WelcomePermissionRequirement(
                labelRes = R.string.permission_group_location,
                permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        )
    }
}

private fun Context.isPermissionGranted(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun missingWelcomePermissions(context: Context): List<String> {
    return welcomePermissionRequirements()
        .flatMap { requirement -> requirement.permissions }
        .distinct()
        .filterNot { permission -> context.isPermissionGranted(permission) }
}

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun Context.findHostActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }
}

private fun wasPermissionRequestedBefore(context: Context, permission: String): Boolean {
    val prefs = context.getSharedPreferences(PERMISSION_REQUEST_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean("$PERMISSION_REQUESTED_KEY_PREFIX$permission", false)
}

private fun markPermissionsAsRequested(context: Context, permissions: List<String>) {
    val prefs = context.getSharedPreferences(PERMISSION_REQUEST_PREFS, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    permissions.forEach { permission ->
        editor.putBoolean("$PERMISSION_REQUESTED_KEY_PREFIX$permission", true)
    }
    editor.apply()
}

private fun requestableWelcomePermissions(context: Context, permissions: List<String>): List<String> {
    val hostActivity = context.findHostActivity() ?: return emptyList()
    return permissions.filter { permission ->
        val requestedBefore = wasPermissionRequestedBefore(context, permission)
        !requestedBefore || ActivityCompat.shouldShowRequestPermissionRationale(
            hostActivity,
            permission
        )
    }
}

private fun resolveWelcomeContinueBlocker(context: Context): WelcomeContinueBlocker? {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothSupported =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            bluetoothManager?.adapter != null

    if (!bluetoothSupported) {
        return WelcomeContinueBlocker(
            buttonText = context.getString(R.string.welcome_continue_issue_bluetooth_unavailable_short),
            detailText = context.getString(R.string.rescue_error_bluetooth_unavailable)
        )
    }

    val missingPermissions = missingWelcomePermissions(context)
    val missingPermissionGroups = welcomePermissionRequirements()
        .filter { requirement ->
            requirement.permissions.any { permission -> !context.isPermissionGranted(permission) }
        }
        .map { requirement -> context.getString(requirement.labelRes) }

    if (missingPermissionGroups.isNotEmpty()) {
        val buttonText = if (missingPermissionGroups.size == 1) {
            context.getString(
                R.string.welcome_continue_issue_single_permission_group,
                missingPermissionGroups.first()
            )
        } else {
            context.getString(R.string.settings_missing_permissions_title)
        }
        val detailText = context.getString(
            R.string.settings_missing_permissions_missing_list,
            missingPermissionGroups.joinToString(", ")
        )
        val requestablePermissions = requestableWelcomePermissions(context, missingPermissions)
        val action = if (requestablePermissions.isNotEmpty()) {
            WelcomeContinueAction.RequestPermissions
        } else {
            WelcomeContinueAction.OpenSettings
        }
        val actionText = if (requestablePermissions.isNotEmpty()) {
            context.getString(R.string.settings_missing_permissions_request_button)
        } else {
            context.getString(R.string.qr_scan_open_settings)
        }
        return WelcomeContinueBlocker(
            buttonText = buttonText,
            detailText = detailText,
            action = action,
            actionText = actionText
        )
    }

    val bluetoothEnabled = runCatching { bluetoothManager?.adapter?.isEnabled == true }
        .getOrElse { false }
    if (!bluetoothEnabled) {
        return WelcomeContinueBlocker(
            buttonText = context.getString(R.string.welcome_continue_issue_bluetooth_disabled_short),
            detailText = context.getString(R.string.rescue_error_bluetooth_disabled),
            action = WelcomeContinueAction.EnableBluetooth,
            actionText = context.getString(R.string.welcome_continue_action_enable_bluetooth)
        )
    }

    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val viewModel: MainScreenViewModel = viewModel()
    val showDialog by viewModel.showDialog.collectAsStateWithLifecycle()
    val publicMeshEnabled by viewModel.publicMeshEnabled.collectAsStateWithLifecycle()
    val unreadCounts by viewModel.unreadCounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val meshUnreadCount by GattMeshChatStore.unreadCount.collectAsStateWithLifecycle()
    val persistedMeshUnreadCount = unreadCounts.entries.firstOrNull { (sessionCode, _) ->
        sessionCode.equals(MESH_GENERAL_SESSION_CODE, ignoreCase = true)
    }?.value ?: 0
    val effectiveMeshUnreadCount = maxOf(meshUnreadCount, persistedMeshUnreadCount)
    val directUnreadCount = unreadCounts.entries
        .filterNot { (sessionCode, _) ->
            sessionCode.equals(MESH_GENERAL_SESSION_CODE, ignoreCase = true)
        }
        .sumOf { (_, count) -> count }
    val totalUnreadCount = directUnreadCount + effectiveMeshUnreadCount
    val showGeneralMeshEntry = publicMeshEnabled
    // Keeps track of Rescue access state from local certificate cache and online verification.
    var rescueRoleResult by remember { mutableStateOf<RescueRoleResult?>(null) }
    var rescueLaunchInFlight by remember { mutableStateOf(false) }
    var rescueInstallProgress by remember { mutableStateOf<Int?>(null) }
    var rescuePrefetchRequested by rememberSaveable { mutableStateOf(false) }
    var welcomeContinueBlocker by remember(context) {
        mutableStateOf(resolveWelcomeContinueBlocker(context))
    }
    val coroutineScope = rememberCoroutineScope()
    val rescueFeatureManager = remember(context) {
        RescueFeatureManager(context.applicationContext)
    }
    val sosServiceStarter = remember(context) {
        {
            val sosIntent = Intent(context, GattSOSServerService::class.java)
            ContextCompat.startForegroundService(context, sosIntent)
        }
    }
    val requestEnableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sosServiceStarter()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.rescue_error_bluetooth_disabled),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    val welcomeEnableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        welcomeContinueBlocker = resolveWelcomeContinueBlocker(context)
    }
    val welcomePermissionRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        welcomeContinueBlocker = resolveWelcomeContinueBlocker(context)
    }
    val rescueInstallConfirmationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // The split install listener handles success, cancellation, and retry states.
    }

    LaunchedEffect(Unit) {
        val securityRepository = SecurityRepository(context)
        val authUser = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }
        if (authUser == null) {
            val cachedRescueRole = securityRepository
                .getUsableStoredCertificateRole(allowExpired = true)
                ?.takeIf { it in ALLOWED_RESCUE_ROLES }
            rescueRoleResult = if (cachedRescueRole != null) {
                LocalKeyStorage.saveRole(context, cachedRescueRole)
                RescueRoleResult.Authorized(cachedRescueRole)
            } else {
                LocalKeyStorage.clearRole(context)
                RescueRoleResult.Unauthorized
            }
            return@LaunchedEffect
        }

        LocalKeyStorage.saveUid(context, authUser.uid)
        val cachedCertificateRole = securityRepository
            .getUsableStoredCertificateRole(allowExpired = true)
        val cachedRescueRole = cachedCertificateRole?.let {
            it.takeIf { role -> role in ALLOWED_RESCUE_ROLES }
        }

        val hasInternet = context.hasUsableInternetConnection()
        rescueRoleResult = if (cachedRescueRole != null) {
            LocalKeyStorage.saveRole(context, cachedRescueRole)
            RescueRoleResult.Authorized(cachedRescueRole)
        } else if (hasInternet) {
            LocalKeyStorage.clearRole(context)
            null
        } else {
            LocalKeyStorage.clearRole(context)
            RescueRoleResult.Unauthorized
        }

        if (!hasInternet) {
            return@LaunchedEffect
        }

        val fetchedResult = FirebaseRoleHelper.fetchRescueRole()
        rescueRoleResult = when (fetchedResult) {
            is RescueRoleResult.Authorized -> {
                LocalKeyStorage.saveRole(context, fetchedResult.role)
                securityRepository.warmUpCertificate()
                fetchedResult
            }

            RescueRoleResult.Unauthorized -> {
                LocalKeyStorage.clearRole(context)
                securityRepository.clearStoredCertificate()
                RescueRoleResult.Unauthorized
            }

            RescueRoleResult.Unauthenticated -> {
                if (cachedRescueRole != null) {
                    LocalKeyStorage.saveRole(context, cachedRescueRole)
                    RescueRoleResult.Authorized(cachedRescueRole)
                } else {
                    RescueRoleResult.Unauthorized
                }
            }

            is RescueRoleResult.Failure -> {
                if (cachedRescueRole != null) {
                    LocalKeyStorage.saveRole(context, cachedRescueRole)
                    RescueRoleResult.Authorized(cachedRescueRole)
                } else {
                    fetchedResult
                }
            }
        }
    }

    LaunchedEffect(rescueRoleResult) {
        val failure = rescueRoleResult as? RescueRoleResult.Failure ?: return@LaunchedEffect
        Log.w(RESCUE_ROLE_LOG_TAG, "Unable to verify rescue role", failure.exception)
    }

    LaunchedEffect(rescueRoleResult, rescuePrefetchRequested) {
        if (rescueRoleResult !is RescueRoleResult.Authorized || rescuePrefetchRequested) {
            return@LaunchedEffect
        }
        rescuePrefetchRequested = true
        rescueFeatureManager.prefetchIfNeeded()
    }

    LaunchedEffect(Unit) {
        GattSOSServerService.startFailureMessage.collectLatest { message ->
            if (message.isNullOrBlank()) return@collectLatest
            Toast.makeText(
                context,
                message,
                Toast.LENGTH_LONG
            ).show()
            GattSOSServerService.clearStartupFailureMessage()
        }
    }

    LaunchedEffect(showDialog, context) {
        if (showDialog) {
            welcomeContinueBlocker = resolveWelcomeContinueBlocker(context)
        }
    }

    DisposableEffect(lifecycleOwner, context, showDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && showDialog) {
                welcomeContinueBlocker = resolveWelcomeContinueBlocker(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.welcome_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.welcome_description),
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val termsUrl = stringResource(R.string.welcome_terms_url)
                    val privacyUrl = stringResource(R.string.welcome_privacy_url)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.welcome_terms_link_label),
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                openLegalLink(context, termsUrl)
                            }
                        )
                        Text(
                            text = stringResource(R.string.welcome_privacy_link_label),
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                openLegalLink(context, privacyUrl)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    var fullName by rememberSaveable { mutableStateOf("") }
                    val isChecked = remember { mutableStateOf(false) }

                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text(stringResource(R.string.full_name_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isChecked.value,
                            onCheckedChange = { isChecked.value = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.welcome_checkbox))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val hasValidName = fullName.isNotBlank()
                    val isSystemBlocked = welcomeContinueBlocker != null
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.acceptDialog(fullName)
                        },
                        enabled = isChecked.value && hasValidName && !isSystemBlocked,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        colors = if (isSystemBlocked) {
                            ButtonDefaults.buttonColors(
                                containerColor = WELCOME_BLOCKED_BUTTON_COLOR,
                                contentColor = Color.White,
                                disabledContainerColor = WELCOME_BLOCKED_BUTTON_COLOR,
                                disabledContentColor = Color.White
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(
                            text = welcomeContinueBlocker?.buttonText
                                ?: stringResource(R.string.continue_button),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                    welcomeContinueBlocker?.detailText?.let { detailText ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = detailText,
                            modifier = Modifier.fillMaxWidth(),
                            color = WELCOME_BLOCKED_TEXT_COLOR,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    welcomeContinueBlocker?.let { blocker ->
                        val actionText = blocker.actionText
                        val action = blocker.action
                        if (actionText != null && action != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    when (action) {
                                        WelcomeContinueAction.RequestPermissions -> {
                                            val missingPermissions = missingWelcomePermissions(context)
                                            val requestablePermissions =
                                                requestableWelcomePermissions(context, missingPermissions)
                                            if (requestablePermissions.isNotEmpty()) {
                                                markPermissionsAsRequested(context, requestablePermissions)
                                                welcomePermissionRequestLauncher.launch(
                                                    requestablePermissions.toTypedArray()
                                                )
                                            } else {
                                                openAppPermissionSettings(context)
                                            }
                                        }

                                        WelcomeContinueAction.OpenSettings -> {
                                            openAppPermissionSettings(context)
                                        }

                                        WelcomeContinueAction.EnableBluetooth -> {
                                            runCatching {
                                                welcomeEnableBluetoothLauncher.launch(
                                                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                                )
                                            }.onFailure { throwable ->
                                                Log.w(
                                                    SOS_BROADCAST_LOG_TAG,
                                                    "Unable to show Bluetooth enable prompt from welcome dialog",
                                                    throwable
                                                )
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.rescue_error_bluetooth_disabled),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    text = actionText,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MainScreenTextureBackground(
            modifier = Modifier.matchParentSize()
        )

        Scaffold(
            containerColor = Color.Transparent,
            floatingActionButton = {
            val defaultFabElevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp,
                pressedElevation = 12.dp,
                focusedElevation = 10.dp,
                hoveredElevation = 10.dp
            )
            val isSosActive by GattSOSServerService.isRunning.collectAsStateWithLifecycle()
            val sosStartTimestamp by GattSOSServerService.startTimestampMillis.collectAsStateWithLifecycle()
            val sosElapsedText = rememberSosElapsedText(
                startTimestamp = sosStartTimestamp,
                isRunning = isSosActive
            )
            val sosLabel = if (isSosActive) {
                stringResource(
                    R.string.emergency_button_with_time,
                    sosElapsedText ?: "--:--"
                )
            } else {
                stringResource(R.string.emergency_button_label)
            }
            val sosContainerColor = if (isSosActive) {
                Color(0xFFB71C1C)
            } else {
                Color(0xFFC62828)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier
                        .height(52.dp)
                        .widthIn(min = 108.dp, max = 184.dp),
                    shape = RoundedCornerShape(18.dp),
                    onClick = {
                        navController.navigate("sos_status") {
                            launchSingleTop = true
                        }
                        if (isSosActive) return@ExtendedFloatingActionButton

                        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                            as? BluetoothManager)?.adapter
                        val bluetoothEnabled = runCatching { adapter?.isEnabled == true }
                            .getOrElse { false }
                        if (!bluetoothEnabled && adapter != null) {
                            val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                            } else {
                                true
                            }
                            if (hasConnectPermission) {
                                runCatching {
                                    requestEnableBluetoothLauncher.launch(
                                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                    )
                                }.onFailure { throwable ->
                                    Log.w(
                                        SOS_BROADCAST_LOG_TAG,
                                        "Unable to show Bluetooth enable prompt for SOS",
                                        throwable
                                    )
                                    sosServiceStarter()
                                }
                                return@ExtendedFloatingActionButton
                            }
                        }
                        sosServiceStarter()
                    },
                    containerColor = sosContainerColor,
                    contentColor = Color.White,
                    elevation = defaultFabElevation,
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_alarm_sos),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            text = sosLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                )
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Rescue access comes from local cache first and is revalidated online when possible.
                    when (val result = rescueRoleResult) {
                        null -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        is RescueRoleResult.Authorized -> {
                            FloatingActionButton(
                                modifier = Modifier.size(56.dp),
                                containerColor = Color(0xFF1D3E66),
                                contentColor = Color(0xFFF2F6FB),
                                elevation = defaultFabElevation,
                                onClick = {
                                    if (rescueLaunchInFlight) {
                                        return@FloatingActionButton
                                    }
                                    if (rescueFeatureManager.launchInstalled(context)) {
                                        return@FloatingActionButton
                                    }
                                    val hostActivity = context.findHostActivity()
                                    if (hostActivity == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.rescue_module_unavailable),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@FloatingActionButton
                                    }
                                    rescueFeatureManager.installAndLaunch(
                                        activity = hostActivity,
                                        confirmationLauncher = rescueInstallConfirmationLauncher,
                                        onStateChanged = { state ->
                                            when (state) {
                                                is RescueFeatureManager.InstallState.Installing -> {
                                                    rescueLaunchInFlight = true
                                                    rescueInstallProgress = state.progressPercent
                                                }

                                                RescueFeatureManager.InstallState.NotInstalling -> {
                                                    rescueLaunchInFlight = false
                                                    rescueInstallProgress = null
                                                }
                                            }
                                        },
                                        onError = { message ->
                                            rescueLaunchInFlight = false
                                            rescueInstallProgress = null
                                            Toast.makeText(
                                                context,
                                                message,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    )
                                }
                            ) {
                                if (rescueLaunchInFlight) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = Color(0xFFF2F6FB)
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.searchandrescue),
                                        contentDescription = stringResource(R.string.rescue_tools_button_description),
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                            }
                        }

                        is RescueRoleResult.Failure,
                        RescueRoleResult.Unauthenticated,
                        RescueRoleResult.Unauthorized -> {
                            // Hide the rescue button if the role is missing, invalid, or an error occurred.
                        }
                    }

                    FloatingActionButton(
                        modifier = Modifier.size(60.dp),
                        containerColor = Color(0xFF304A76),
                        contentColor = Color.White,
                        elevation = defaultFabElevation,
                        onClick = {
                            navController.navigate("new_chat")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = stringResource(R.string.new_chat_title),
                            modifier = Modifier.size(32.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        },
            topBar = {
                MainTopBar(
                    onOpenSettings = { navController.navigate("settings") }
                )
            },
            bottomBar = {
                AppBottomBar(
                    navController = navController,
                    messageBadgeCount = totalUnreadCount
                )
            }
        ) { innerPadding ->
            MainConversationContent(
                viewModel = viewModel,
                navController = navController,
                innerPadding = innerPadding,
                isCurrentUserRescue = rescueRoleResult is RescueRoleResult.Authorized,
                showGeneralMeshEntry = showGeneralMeshEntry,
                meshGeneralUnreadCount = effectiveMeshUnreadCount,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    onOpenSettings: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    val scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.sp
    )

    Column {
        TopAppBar(
            title = {
                Row(
                    modifier = Modifier.padding(start = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = titleStyle,
                        color = if (isDarkTheme) Color.White else Color(0xFF042C43),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                Icon(
                    painter = painterResource(R.drawable.dcslogo),
                    contentDescription = null, // decorative icon
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .padding(start = 12.dp, end = 6.dp)
                        .size(32.dp)
                )
            },
            actions = {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.Settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = scrolledContainerColor
            )
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
        )
    }
}

@Composable
private fun MainConversationContent(
    viewModel: MainScreenViewModel,
    navController: NavController,
    innerPadding: PaddingValues,
    isCurrentUserRescue: Boolean,
    showGeneralMeshEntry: Boolean,
    meshGeneralUnreadCount: Int,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val isContactsLoaded by viewModel.isContactsLoaded.collectAsStateWithLifecycle()
    val latestMessages by viewModel.latestMessages.collectAsStateWithLifecycle()
    val latestCallEvents by viewModel.latestCallEvents.collectAsStateWithLifecycle()
    val activeCalls = rememberActiveCallsBySession()
    val connectedSessions = rememberConnectedSessions()
    val unreadCounts by viewModel.unreadCounts.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessagesNewestFirst.collectAsStateWithLifecycle()
    val meshGeneralMessages by GattMeshChatStore.messages.collectAsStateWithLifecycle()
    // Use screen width from configuration instead of BoxWithConstraints scope
    val configuration = LocalConfiguration.current
    val isExpandedScreen = configuration.screenWidthDp >= 600
    val listState = rememberLazyListState()
    val locale = Locale.getDefault()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var maxPullDistancePx by remember { mutableFloatStateOf(0f) }
    var pullStartedAtMs by remember { mutableLongStateOf(0L) }
    var pullDurationMs by remember { mutableLongStateOf(0L) }
    val pullThresholdPx = with(LocalDensity.current) { 92.dp.toPx() }
    val pullRevealStartPx = with(LocalDensity.current) { 16.dp.toPx() }
    val pullProgress = (
        (pullDistancePx - pullRevealStartPx) / (pullThresholdPx - pullRevealStartPx)
        ).coerceIn(0f, 1f)
    val showSearchBar = isSearchVisible || searchQuery.isNotBlank()
    val isPreviewVisible = !showSearchBar &&
        pullDistancePx > pullRevealStartPx &&
        pullDurationMs >= SEARCH_PULL_MIN_PREVIEW_DURATION_MS
    val previewRevealFraction = if (isPreviewVisible) pullProgress else 0f
    val openedRevealFraction by animateFloatAsState(
        targetValue = if (showSearchBar) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 420f),
        label = "main_search_open_reveal"
    )
    val searchRevealFraction = if (showSearchBar) openedRevealFraction else previewRevealFraction
    val isPullReady = pullProgress >= 1f
    val filteredContacts = remember(contacts, allMessages, searchQuery, locale) {
        filterContactsForGlobalSearch(
            contacts = contacts,
            allMessages = allMessages,
            query = searchQuery,
            locale = locale
        )
    }
    val showSearchNoResults = isContactsLoaded &&
        searchQuery.isNotBlank() &&
        filteredContacts.isEmpty() &&
        !showGeneralMeshEntry
    val onContactSelected: (String, String?, String?) -> Unit = remember(navController) {
        { sessionCode, preferredDisplayName, preferredTransport ->
            navController.navigate(
                buildConversationRoute(
                    sessionCode = sessionCode,
                    preferredDisplayName = preferredDisplayName,
                    preferredTransport = preferredTransport
                )
            )
        }
    }
    fun resetPullState() {
        pullDistancePx = 0f
        maxPullDistancePx = 0f
        pullStartedAtMs = 0L
        pullDurationMs = 0L
    }
    fun shouldOpenSearchFromPull(): Boolean {
        if (isSearchVisible) {
            return false
        }
        return maxPullDistancePx >= pullThresholdPx &&
            pullDurationMs >= SEARCH_PULL_MIN_OPEN_DURATION_MS
    }
    val pullToSearchConnection = remember(listState, isSearchVisible, pullThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput || isSearchVisible) {
                    return Offset.Zero
                }
                val isAtTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                when {
                    isAtTop && available.y > 0f -> {
                        val now = SystemClock.uptimeMillis()
                        if (pullStartedAtMs == 0L) {
                            pullStartedAtMs = now
                        }
                        val dampedDelta = available.y * 0.55f
                        val nextDistance = (pullDistancePx + dampedDelta).coerceAtMost(
                            pullThresholdPx * 1.35f
                        )
                        if (nextDistance != pullDistancePx) {
                            pullDistancePx = nextDistance
                        }
                        if (nextDistance > maxPullDistancePx) {
                            maxPullDistancePx = nextDistance
                        }
                        pullDurationMs = (now - pullStartedAtMs).coerceAtLeast(0L)
                    }

                    (available.y < 0f || !isAtTop) &&
                        (
                            pullDistancePx > 0f ||
                                maxPullDistancePx > 0f ||
                                pullStartedAtMs != 0L
                            ) -> {
                        val nextDistance = (pullDistancePx + available.y).coerceAtLeast(0f)
                        if (nextDistance != pullDistancePx) {
                            pullDistancePx = nextDistance
                        }
                        if (!isAtTop) {
                            pullStartedAtMs = 0L
                        }
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (shouldOpenSearchFromPull()) {
                    isSearchVisible = true
                }
                resetPullState()
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(listState, isSearchVisible, pullThresholdPx) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { isScrolling ->
                if (
                    !isScrolling &&
                    !isSearchVisible &&
                    (pullDistancePx > 0f || pullStartedAtMs != 0L)
                ) {
                    if (shouldOpenSearchFromPull()) {
                        isSearchVisible = true
                    }
                    resetPullState()
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        val (horizontalPadding, verticalPadding) = responsivePadding(isExpandedScreen)

        val contentModifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)

        if (!isContactsLoaded) {
            Box(
                modifier = contentModifier,
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.main_contacts_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (contacts.isEmpty() && !showGeneralMeshEntry) {
            Box(
                modifier = contentModifier,
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_contacts_message),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val listTopPadding by animateDpAsState(
                targetValue = if (showSearchBar) 84.dp else 0.dp,
                animationSpec = tween(
                    durationMillis = if (showSearchBar) 210 else 170,
                    easing = FastOutSlowInEasing
                ),
                label = "main_search_list_padding"
            )
            val listModifier = if (isExpandedScreen) {
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
            } else {
                Modifier
                    .fillMaxSize()
            }

            Box(
                modifier = contentModifier
                    .nestedScroll(pullToSearchConnection)
            ) {
                ContactList(
                    contacts = filteredContacts,
                    latestMessages = latestMessages,
                    latestCallEvents = latestCallEvents,
                    activeCalls = activeCalls,
                    connectedSessions = connectedSessions,
                    unreadCounts = unreadCounts,
                    isCurrentUserRescue = isCurrentUserRescue,
                    showGeneralMeshEntry = showGeneralMeshEntry,
                    meshGeneralUnreadCount = meshGeneralUnreadCount,
                    meshGeneralMessages = meshGeneralMessages,
                    onContactSelected = onContactSelected,
                    listState = listState,
                    contentPadding = PaddingValues(top = listTopPadding),
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = listModifier
                )

                if (showSearchBar || isPreviewVisible) {
                    MainScreenSearchBar(
                        query = searchQuery,
                        onQueryChange = {
                            if (showSearchBar) {
                                searchQuery = it
                            }
                        },
                        onClose = {
                            searchQuery = ""
                            isSearchVisible = false
                            resetPullState()
                        },
                        interactive = showSearchBar,
                        pullReady = isPullReady,
                        revealFraction = searchRevealFraction,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isExpandedScreen) 24.dp else 8.dp,
                                vertical = if (isExpandedScreen) 8.dp else 6.dp
                            )
                    )
                }

                if (showSearchNoResults) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.main_search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreenTextureBackground(
    modifier: Modifier = Modifier
) {
    val baseColor = MaterialTheme.colorScheme.background
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
    val vignetteColor = if (baseColor.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.09f)
    } else {
        Color.Black.copy(alpha = 0.14f)
    }
    Box(
        modifier = modifier
            .background(baseColor)
            .drawWithCache {
                val gridSpacing = 28.dp.toPx()
                val vignette = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        vignetteColor
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.maxDimension * 0.92f
                )
                onDrawBehind {
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                        x += gridSpacing
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += gridSpacing
                    }
                    drawRect(brush = vignette)
                }
            }
    )
}

@Composable
private fun MainScreenSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    interactive: Boolean,
    pullReady: Boolean,
    revealFraction: Float,
    modifier: Modifier = Modifier
) {
    val reveal = revealFraction.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(22.dp)
    val placeholder = stringResource(
        if (interactive) {
            R.string.main_search_placeholder
        } else if (pullReady) {
            R.string.main_search_pull_ready
        } else {
            R.string.main_search_pull_hint
        }
    )
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(
        alpha = if (interactive) 0.55f else 0.28f
    )
    val iconTint = if (interactive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .alpha(reveal)
            .offset(y = (-8).dp * (1f - reveal))
            .border(width = 1.dp, color = outlineColor, shape = shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (interactive) 2.dp else 0.dp,
        shadowElevation = if (interactive) 8.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = iconTint
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = { value ->
                    if (interactive) {
                        onQueryChange(value)
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                readOnly = !interactive,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (interactive && query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.main_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (interactive) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactList(
    contacts: List<Contact>,
    latestMessages: Map<String, ChatMessage>,
    latestCallEvents: Map<String, CallEvent>,
    activeCalls: Map<String, CallUiState>,
    connectedSessions: Set<String>,
    unreadCounts: Map<String, Int>,
    isCurrentUserRescue: Boolean,
    showGeneralMeshEntry: Boolean,
    meshGeneralUnreadCount: Int,
    meshGeneralMessages: List<MeshChatMessage>,
    onContactSelected: (String, String?, String?) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val timeFormatter = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    val recentConnectedLabelSessions = rememberRecentConnectedLabelSessions(connectedSessions)
    val sortedContacts = remember(
        contacts,
        latestMessages,
        latestCallEvents,
        activeCalls,
        unreadCounts,
        locale
    ) {
        sortContactsForMainList(
            contacts = contacts,
            latestMessages = latestMessages,
            latestCallEvents = latestCallEvents,
            activeCalls = activeCalls,
            unreadCounts = unreadCounts,
            locale = locale
        )
    }
    val visibleContacts = remember(sortedContacts, showGeneralMeshEntry) {
        if (!showGeneralMeshEntry) {
            sortedContacts
        } else {
            sortedContacts.filterNot { contact ->
                contact.sessionCode.equals(MESH_GENERAL_SESSION_CODE, ignoreCase = true)
            }
        }
    }
    val rows = remember(
        visibleContacts,
        latestMessages,
        latestCallEvents,
        activeCalls,
        connectedSessions,
        context,
        isCurrentUserRescue,
        timeFormatter
    ) {
        visibleContacts.map { contact ->
            val sessionCode = contact.sessionCode
            val lastMessage = latestMessages[sessionCode]
            val lastCallEvent = latestCallEvents[sessionCode]
            val activeCall = activeCalls[sessionCode]
            val displayName = mainListContactDisplayName(
                contact = contact,
                isCurrentUserRescue = isCurrentUserRescue,
                context = context
            )
            val supportingText = contactSupportingText(contact)
            ContactListRowUi(
                name = displayName,
                sessionCode = sessionCode,
                stableKey = contactStableKey(contact),
                preferredTransport = contact.preferredTransport,
                supportingText = supportingText,
                timestampText = latestContactActivityTimestampMillis(
                    lastMessage = lastMessage,
                    lastCallEvent = lastCallEvent,
                    activeCall = activeCall
                )?.let { timestamp ->
                    timeFormatter.format(Date(timestamp))
                },
                preview = buildContactPreviewUi(
                    context = context,
                    contactName = displayName,
                    sessionCode = sessionCode,
                    lastMessage = lastMessage,
                    lastCallEvent = lastCallEvent,
                    activeCall = activeCall
                ),
                isConnected = sessionCode in connectedSessions,
                isVerified = shouldShowVerifiedBadgeForContact(
                    contact = contact,
                    isCurrentUserRescue = isCurrentUserRescue
                )
            )
        }
    }
    val meshGeneralLastMessage = remember(meshGeneralMessages) {
        meshGeneralMessages.maxByOrNull { it.timestampMillis }
    }
    val meshGeneralTimestampText = remember(meshGeneralLastMessage, timeFormatter) {
        meshGeneralLastMessage?.timestampMillis?.let { timestamp ->
            timeFormatter.format(Date(timestamp))
        }
    }
    val meshGeneralPreview = remember(meshGeneralLastMessage, context) {
        buildMeshGeneralPreviewUi(
            context = context,
            lastMessage = meshGeneralLastMessage
        )
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding
    ) {
        if (showGeneralMeshEntry) {
            item(
                key = MESH_GENERAL_LIST_ITEM_KEY,
                contentType = "mesh_general_item"
            ) {
                MeshGeneralListItem(
                    unreadCount = meshGeneralUnreadCount,
                    timestampText = meshGeneralTimestampText,
                    preview = meshGeneralPreview,
                    onClick = { onContactSelected(MESH_GENERAL_SESSION_CODE, null, null) }
                )
            }
        }
        items(
            items = rows,
            key = { row -> row.stableKey },
            contentType = { "contact_item" }
        ) { row ->
            val unreadCount = unreadCounts[row.sessionCode] ?: 0
            ContactListItem(
                contactName = row.name,
                sessionCode = row.sessionCode,
                contactStableKey = row.stableKey,
                supportingText = row.supportingText,
                timestampText = row.timestampText,
                preview = row.preview,
                isConnected = row.isConnected,
                isVerified = row.isVerified,
                showConnectedLabel = row.sessionCode in recentConnectedLabelSessions,
                unreadCount = unreadCount,
                onClick = { onContactSelected(row.sessionCode, row.name, row.preferredTransport) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

private fun sortContactsForMainList(
    contacts: List<Contact>,
    latestMessages: Map<String, ChatMessage>,
    latestCallEvents: Map<String, CallEvent>,
    activeCalls: Map<String, CallUiState>,
    unreadCounts: Map<String, Int>,
    locale: Locale
): List<Contact> {
    return contacts.sortedWith(
        compareByDescending<Contact> { contact ->
            isRescuerContact(contact)
        }
            .thenByDescending { contact ->
                unreadCounts[contact.sessionCode] ?: 0
            }
            .thenByDescending { contact ->
                latestContactActivityTimestampMillis(
                    lastMessage = latestMessages[contact.sessionCode],
                    lastCallEvent = latestCallEvents[contact.sessionCode],
                    activeCall = activeCalls[contact.sessionCode]
                ) ?: Long.MIN_VALUE
            }
            .thenBy { contact ->
                contact.name.lowercase(locale)
            }
    )
}

private fun isRescuerContact(contact: Contact): Boolean {
    return BlePeerIdentityUtils.isRescuerDisplayName(contact.name)
}

private fun shouldShowVerifiedBadgeForContact(
    contact: Contact,
    isCurrentUserRescue: Boolean
): Boolean {
    return contact.verified &&
        !isCurrentUserRescue &&
        isRescuerContact(contact)
}

private fun mainListContactDisplayName(
    contact: Contact,
    isCurrentUserRescue: Boolean,
    context: Context
): String {
    if (!isBleChatSession(contact)) {
        return contact.name
    }
    return BlePeerIdentityUtils.buildBleCounterpartyDisplayName(
        preferredName = contact.name,
        addressForFallback = contact.address.ifBlank { contact.sessionCode },
        isCurrentUserRescue = isCurrentUserRescue,
        context = context
    )
}

private fun isBleChatSession(contact: Contact): Boolean {
    return contact.sessionCode.startsWith("ble:", ignoreCase = true) &&
        normalizePreferredTransport(contact.preferredTransport) != PREFERRED_TRANSPORT_BLE_GATT
}

private fun filterContactsForGlobalSearch(
    contacts: List<Contact>,
    allMessages: List<ChatMessage>,
    query: String,
    locale: Locale
): List<Contact> {
    val normalizedQuery = query.trim().lowercase(locale)
    if (normalizedQuery.isEmpty()) {
        return contacts
    }

    val matchedSessions = allMessages.asSequence()
        .filter { message ->
            doesMessageMatchSearchQuery(
                message = message,
                normalizedQuery = normalizedQuery,
                locale = locale
            )
        }
        .map { it.sessionCode }
        .toHashSet()

    return contacts.filter { contact ->
        val contactText = buildString {
            append(contact.name)
            if (contact.sessionCode.isNotBlank()) {
                append(' ')
                append(contact.sessionCode)
            }
            if (contact.address.isNotBlank()) {
                append(' ')
                append(contact.address)
            }
        }.lowercase(locale)

        contactText.contains(normalizedQuery) ||
            matchedSessions.contains(contact.sessionCode)
    }
}

private fun doesMessageMatchSearchQuery(
    message: ChatMessage,
    normalizedQuery: String,
    locale: Locale
): Boolean {
    if (normalizedQuery.isBlank()) {
        return false
    }
    val normalizedBody = message.text.trim()
    val body = stripReplyMetadata(normalizedBody)
        ?.takeIf { it.isNotBlank() }
        ?: normalizedBody
    val replyBody = parseReplyMetadata(normalizedBody)?.body.orEmpty()
    val searchableText = buildString {
        append(body)
        if (replyBody.isNotBlank()) {
            append(' ')
            append(replyBody)
        }
    }.lowercase(locale)
    return searchableText.contains(normalizedQuery)
}

@Immutable
private data class ContactPreviewUi(
    val previewText: String,
    val previewWithSender: String,
    val showReadIndicator: Boolean,
    val isRead: Boolean,
    val indicator: ContactPreviewIndicatorUi? = null
)

@Immutable
private data class ContactPreviewIndicatorUi(
    val icon: ImageVector,
    val contentDescription: String,
    val tone: ContactPreviewIndicatorTone
)

private enum class ContactPreviewIndicatorTone {
    Default,
    Accent,
    Error
}

@Immutable
private data class ContactListRowUi(
    val name: String,
    val sessionCode: String,
    val stableKey: String,
    val preferredTransport: String,
    val supportingText: String,
    val timestampText: String?,
    val preview: ContactPreviewUi,
    val isConnected: Boolean,
    val isVerified: Boolean
)

private fun contactSupportingText(contact: Contact): String {
    return when {
        contact.address.isNotBlank() -> contact.address
        contact.sessionCode.isNotBlank() -> contact.sessionCode
        else -> ""
    }
}

private fun contactStableKey(contact: Contact): String {
    val primary = contact.sessionCode
        .ifBlank { contact.address }
    if (primary.isNotBlank()) {
        return primary
    }
    return "${contact.name}:${contact.aesKey}:${contact.hashCode()}"
}

@Composable
private fun rememberActiveCallsBySession(): Map<String, CallUiState> {
    val context = LocalContext.current
    var activeCalls by remember { mutableStateOf<Map<String, CallUiState>>(emptyMap()) }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        val collectScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        var callsJob: Job? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val boundService = (binder as? RfcommForegroundService.LocalBinder)?.getService() ?: return
                callsJob?.cancel()
                callsJob = collectScope.launch {
                    boundService.calls.collectLatest { calls ->
                        activeCalls = calls.filterValues { call ->
                            call.state == CallState.Connecting ||
                                call.state == CallState.Ringing ||
                                call.state == CallState.InCall
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                callsJob?.cancel()
                callsJob = null
                activeCalls = emptyMap()
            }
        }
        val bound = runCatching {
            appContext.bindService(
                Intent(appContext, RfcommForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)

        onDispose {
            callsJob?.cancel()
            collectScope.cancel()
            activeCalls = emptyMap()
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    return activeCalls
}

@Composable
private fun rememberConnectedSessions(): Set<String> {
    val context = LocalContext.current
    var connectedSessions by remember { mutableStateOf<Set<String>>(emptySet()) }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        val p2pGattChatManager = P2pGattChatManager.shared(appContext)
        val collectScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        var sessionsJob: Job? = null
        var bleClientSessionsJob: Job? = null
        var bleServerSessionsJob: Job? = null
        var rfcommSessions = emptySet<String>()
        var bleClientSessions = emptySet<String>()
        var bleServerSessions = emptySet<String>()
        fun updateConnectedSessions() {
            connectedSessions = rfcommSessions + bleClientSessions + bleServerSessions
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val boundService = (binder as? RfcommForegroundService.LocalBinder)?.getService() ?: return
                sessionsJob?.cancel()
                sessionsJob = collectScope.launch {
                    boundService.activeSessions.collectLatest { sessions ->
                        rfcommSessions = sessions
                        updateConnectedSessions()
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                sessionsJob?.cancel()
                sessionsJob = null
                rfcommSessions = emptySet()
                updateConnectedSessions()
            }
        }
        bleClientSessionsJob = collectScope.launch {
            p2pGattChatManager.connectedSessions.collectLatest { sessions ->
                bleClientSessions = sessions
                updateConnectedSessions()
            }
        }
        bleServerSessionsJob = collectScope.launch {
            BlePeerStore.peers.collectLatest { peers ->
                bleServerSessions = peers.values
                    .mapNotNull { peer ->
                        peer.sessionCode.trim().takeIf { it.isNotEmpty() }
                    }
                    .toSet()
                updateConnectedSessions()
            }
        }
        val bound = runCatching {
            appContext.bindService(
                Intent(appContext, RfcommForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)

        onDispose {
            sessionsJob?.cancel()
            bleClientSessionsJob?.cancel()
            bleServerSessionsJob?.cancel()
            collectScope.cancel()
            connectedSessions = emptySet()
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    return connectedSessions
}

@Composable
private fun rememberRecentConnectedLabelSessions(
    connectedSessions: Set<String>
): Set<String> {
    val coroutineScope = rememberCoroutineScope()
    val labelVisibility = remember { mutableStateMapOf<String, Boolean>() }
    val hideJobs = remember { mutableStateMapOf<String, Job>() }

    LaunchedEffect(connectedSessions) {
        val disconnectedSessions = labelVisibility.keys.filterNot { it in connectedSessions }
        disconnectedSessions.forEach { sessionCode ->
            labelVisibility.remove(sessionCode)
            hideJobs.remove(sessionCode)?.cancel()
        }

        connectedSessions.forEach { sessionCode ->
            if (sessionCode in labelVisibility) {
                return@forEach
            }
            labelVisibility[sessionCode] = true
            hideJobs.remove(sessionCode)?.cancel()
            hideJobs[sessionCode] = coroutineScope.launch {
                delay(MAIN_CONNECTED_LABEL_DURATION_MS)
                labelVisibility[sessionCode] = false
                hideJobs.remove(sessionCode)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            hideJobs.values.forEach { it.cancel() }
            hideJobs.clear()
            labelVisibility.clear()
        }
    }

    return labelVisibility
        .filterValues { visible -> visible }
        .keys
}

private fun activeCallTimestampMillis(activeCall: CallUiState?): Long? {
    val call = activeCall ?: return null
    return call.connectedAt ?: call.startedAt
}

private fun latestContactActivityTimestampMillis(
    lastMessage: ChatMessage?,
    lastCallEvent: CallEvent?,
    activeCall: CallUiState?
): Long? {
    val latestTimestamp = maxOf(
        lastMessage?.timestampMillis ?: Long.MIN_VALUE,
        lastCallEvent?.timestampMillis ?: Long.MIN_VALUE,
        activeCallTimestampMillis(activeCall) ?: Long.MIN_VALUE
    )
    return latestTimestamp.takeIf { it != Long.MIN_VALUE }
}

private fun buildContactPreviewUi(
    context: Context,
    contactName: String,
    sessionCode: String,
    lastMessage: ChatMessage?,
    lastCallEvent: CallEvent?,
    activeCall: CallUiState?
): ContactPreviewUi {
    activeCall?.let { call ->
        return buildActiveCallPreviewUi(
            context = context,
            call = call
        )
    }

    val showCallPreview = when {
        lastCallEvent == null -> false
        lastMessage == null -> true
        else -> lastCallEvent.timestampMillis > lastMessage.timestampMillis
    }
    if (showCallPreview) {
        return buildCallEventPreviewUi(
            context = context,
            event = requireNotNull(lastCallEvent)
        )
    }

    if (lastMessage == null) {
        val fallbackPreviewText = if (
            sessionCode.equals(MESH_GENERAL_SESSION_CODE, ignoreCase = true)
        ) {
            context.getString(R.string.mesh_chat_general_subtitle)
        } else {
            context.getString(R.string.main_contact_preview_saved)
        }
        return ContactPreviewUi(
            previewText = fallbackPreviewText,
            previewWithSender = fallbackPreviewText,
            showReadIndicator = false,
            isRead = false,
            indicator = ContactPreviewIndicatorUi(
                icon = Icons.Filled.People,
                contentDescription = fallbackPreviewText,
                tone = ContactPreviewIndicatorTone.Default
            )
        )
    }

    val previewText = when (lastMessage.messageType) {
        MessageType.TEXT -> {
            val normalizedBody = lastMessage.text.trim()
            val replyMetadata = parseReplyMetadata(normalizedBody)
            val replyBody = replyMetadata?.body

            val text = when {
                replyBody != null -> {
                    val replyPreview = when {
                        isChatLocationPayload(replyBody) ->
                            context.getString(R.string.chat_location_preview_label)
                        parseSharedFilePayload(replyBody) != null ->
                            context.getString(R.string.chat_file_preview_label)
                        else -> replyBody
                    }
                    if (lastMessage.isLocal) {
                        context.getString(
                            R.string.conversation_preview_reply_you_prefix,
                            replyPreview
                        )
                    } else {
                        context.getString(
                            R.string.conversation_preview_reply_prefix,
                            replyPreview
                        )
                    }
                }

                else -> {
                    val basePreview = stripReplyMetadata(normalizedBody)
                        ?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.conversation_preview_text_placeholder)
                    when {
                        isChatLocationPayload(basePreview) ->
                            context.getString(R.string.chat_location_preview_label)
                        parseSharedFilePayload(basePreview) != null ->
                            context.getString(R.string.chat_file_preview_label)
                        else -> basePreview
                    }
                }
            }

            text.trim()
        }

        MessageType.AUDIO -> context.getString(R.string.conversation_preview_voice_message)
        MessageType.IMAGE -> context.getString(R.string.conversation_preview_photo_message)
    }

    val senderPrefix = when {
        lastMessage.isLocal -> null
        else -> context.getString(
            R.string.conversation_preview_sender_contact_prefix,
            contactName
        )
    }

    val previewWithSender = if (senderPrefix.isNullOrBlank()) {
        previewText
    } else {
        buildString {
            append(senderPrefix)
            if (previewText.isNotEmpty()) {
                append(' ')
            }
            append(previewText)
        }.trim()
    }

    return ContactPreviewUi(
        previewText = previewText,
        previewWithSender = previewWithSender,
        showReadIndicator = lastMessage.isLocal,
        isRead = lastMessage.isRead,
        indicator = null
    )
}

private fun buildActiveCallPreviewUi(
    context: Context,
    call: CallUiState
): ContactPreviewUi {
    val statusText = when (call.state) {
        CallState.Connecting -> context.getString(R.string.chat_call_connecting)
        CallState.Ringing -> if (call.isOutgoing) {
            context.getString(R.string.chat_call_outgoing_ringing)
        } else {
            context.getString(R.string.chat_call_incoming)
        }
        CallState.InCall -> context.getString(R.string.chat_call_ongoing_banner)
        CallState.Idle, CallState.Ended -> context.getString(R.string.chat_call_idle)
    }
    val indicator = when (call.state) {
        CallState.Connecting -> ContactPreviewIndicatorUi(
            icon = if (call.isOutgoing) Icons.Filled.CallMade else Icons.Filled.CallReceived,
            contentDescription = statusText,
            tone = ContactPreviewIndicatorTone.Accent
        )
        CallState.Ringing -> ContactPreviewIndicatorUi(
            icon = if (call.isOutgoing) Icons.Filled.CallMade else Icons.Filled.CallReceived,
            contentDescription = statusText,
            tone = ContactPreviewIndicatorTone.Accent
        )
        CallState.InCall -> ContactPreviewIndicatorUi(
            icon = Icons.Filled.Call,
            contentDescription = statusText,
            tone = ContactPreviewIndicatorTone.Accent
        )
        CallState.Idle, CallState.Ended -> null
    }
    return ContactPreviewUi(
        previewText = statusText,
        previewWithSender = statusText,
        showReadIndicator = false,
        isRead = false,
        indicator = indicator
    )
}

private fun buildCallEventPreviewUi(
    context: Context,
    event: CallEvent
): ContactPreviewUi {
    val statusText = when (event.direction) {
        CallDirection.OUTGOING -> when (event.result) {
            CallResult.ANSWERED -> context.getString(R.string.chat_call_event_outgoing_answered)
            CallResult.CANCELED -> context.getString(R.string.chat_call_event_canceled)
            CallResult.REJECTED -> context.getString(R.string.chat_call_event_rejected)
            CallResult.MISSED -> context.getString(R.string.chat_call_event_missed)
        }

        CallDirection.INCOMING -> when (event.result) {
            CallResult.ANSWERED -> context.getString(R.string.chat_call_event_incoming_answered)
            CallResult.MISSED -> context.getString(R.string.chat_call_event_missed)
            CallResult.REJECTED -> context.getString(R.string.chat_call_event_rejected)
            CallResult.CANCELED -> context.getString(R.string.chat_call_event_canceled)
        }
    }
    val previewText = buildString {
        append(statusText)
        if (event.result == CallResult.ANSWERED) {
            event.durationMillis
                ?.takeIf { it > 0L }
                ?.let { durationMillis ->
                    append(" \u00B7 ")
                    append(formatCallDuration(context, durationMillis))
                }
        }
    }
    return ContactPreviewUi(
        previewText = previewText,
        previewWithSender = previewText,
        showReadIndicator = false,
        isRead = false,
        indicator = when (event.result) {
            CallResult.ANSWERED -> ContactPreviewIndicatorUi(
                icon = if (event.direction == CallDirection.OUTGOING) {
                    Icons.Filled.CallMade
                } else {
                    Icons.Filled.CallReceived
                },
                contentDescription = statusText,
                tone = ContactPreviewIndicatorTone.Accent
            )
            CallResult.MISSED -> ContactPreviewIndicatorUi(
                icon = Icons.Filled.CallMissed,
                contentDescription = statusText,
                tone = ContactPreviewIndicatorTone.Error
            )
            CallResult.REJECTED -> ContactPreviewIndicatorUi(
                icon = Icons.Filled.CallEnd,
                contentDescription = statusText,
                tone = ContactPreviewIndicatorTone.Error
            )
            CallResult.CANCELED -> ContactPreviewIndicatorUi(
                icon = if (event.direction == CallDirection.OUTGOING) {
                    Icons.Filled.CallMade
                } else {
                    Icons.Filled.CallReceived
                },
                contentDescription = statusText,
                tone = ContactPreviewIndicatorTone.Default
            )
        }
    )
}

private fun buildMeshGeneralPreviewUi(
    context: Context,
    lastMessage: MeshChatMessage?
): ContactPreviewUi {
    if (lastMessage == null) {
        val defaultSubtitle = context.getString(R.string.mesh_chat_general_subtitle)
        return ContactPreviewUi(
            previewText = defaultSubtitle,
            previewWithSender = defaultSubtitle,
            showReadIndicator = false,
            isRead = false
        )
    }

    val normalizedBody = lastMessage.text.trim()
    val replyMetadata = parseReplyMetadata(normalizedBody)
    val replyBody = replyMetadata?.body
    val previewText = when {
        replyBody != null -> {
            val replyPreview = when {
                isChatLocationPayload(replyBody) ->
                    context.getString(R.string.chat_location_preview_label)
                parseSharedFilePayload(replyBody) != null ->
                    context.getString(R.string.chat_file_preview_label)
                else -> replyBody
            }
            if (lastMessage.isLocal) {
                context.getString(
                    R.string.conversation_preview_reply_you_prefix,
                    replyPreview
                )
            } else {
                context.getString(
                    R.string.conversation_preview_reply_prefix,
                    replyPreview
                )
            }
        }

        else -> {
            val basePreview = stripReplyMetadata(normalizedBody)
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.conversation_preview_text_placeholder)
            when {
                isChatLocationPayload(basePreview) ->
                    context.getString(R.string.chat_location_preview_label)
                parseSharedFilePayload(basePreview) != null ->
                    context.getString(R.string.chat_file_preview_label)
                else -> basePreview
            }
        }
    }.trim()

    val senderPrefix = if (lastMessage.isLocal) {
        null
    } else {
        val sender = lastMessage.senderLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.rescue_unknown_user)
        context.getString(R.string.conversation_preview_sender_contact_prefix, sender)
    }

    val previewWithSender = if (senderPrefix.isNullOrBlank()) {
        previewText
    } else {
        buildString {
            append(senderPrefix)
            if (previewText.isNotEmpty()) {
                append(' ')
            }
            append(previewText)
        }.trim()
    }

    return ContactPreviewUi(
        previewText = previewText,
        previewWithSender = previewWithSender,
        showReadIndicator = lastMessage.isLocal,
        isRead = lastMessage.status == MeshMessageStatus.READ
    )
}

private fun isChatLocationPayload(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.contains(CHAT_LOCATION_PREFIX, ignoreCase = true)) {
        return true
    }
    return GOOGLE_MAPS_LOCATION_REGEX.containsMatchIn(trimmed)
}

@Composable
private fun ContactListItem(
    contactName: String,
    sessionCode: String,
    contactStableKey: String,
    supportingText: String,
    timestampText: String?,
    preview: ContactPreviewUi,
    isConnected: Boolean,
    isVerified: Boolean,
    showConnectedLabel: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val contactAvatarVersion by ContactAvatarStorage.observeAvatarVersion(sessionCode)
        .collectAsStateWithLifecycle(initialValue = 0L)
    val contactAvatarBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = sessionCode,
        key2 = contactAvatarVersion
    ) {
        value = withContext(Dispatchers.IO) {
            ContactAvatarStorage.loadContactAvatar(context, sessionCode)
        }
    }
    val titleSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(
                    key = ChatSharedElements.title(sessionCode)
                ),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isGattMeshSession = sessionCode.startsWith("gattmesh:", ignoreCase = true)
            if (isGattMeshSession) {
                GroupChatAvatar(
                    modifier = Modifier.size(52.dp),
                    iconSize = 26.dp
                )
            } else {
                ContactAvatar(
                    displayName = contactName,
                    stableKey = contactStableKey,
                    bitmap = contactAvatarBitmap,
                    modifier = Modifier.size(52.dp),
                    textStyle = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isVerified) {
                            Icon(
                                imageVector = Icons.Filled.Verified,
                                contentDescription = stringResource(R.string.ble_chat_identity_verified),
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF1D9BF0)
                            )
                        }
                        Text(
                            text = contactName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = titleSharedModifier.weight(1f, fill = false)
                        )
                        if (isConnected) {
                            ConnectedInlineIndicator(showLabel = showConnectedLabel)
                        }
                    }
                    if (timestampText != null) {
                        Text(
                            text = timestampText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (preview.previewText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (preview.showReadIndicator) {
                            val readIndicatorColor = Color(0xFF34B7F1)
                            Icon(
                                imageVector = Icons.Filled.DoneAll,
                                contentDescription = stringResource(
                                    if (preview.isRead) {
                                        R.string.chat_message_status_read
                                    } else {
                                        R.string.chat_message_status_delivered
                                    }
                                ),
                                modifier = Modifier.size(18.dp),
                                tint = if (preview.isRead) {
                                    readIndicatorColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                }
                            )
                        } else if (preview.indicator != null) {
                            val tint = when (preview.indicator.tone) {
                                ContactPreviewIndicatorTone.Default ->
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                ContactPreviewIndicatorTone.Accent ->
                                    MaterialTheme.colorScheme.primary
                                ContactPreviewIndicatorTone.Error ->
                                    MaterialTheme.colorScheme.error
                            }
                            Icon(
                                imageVector = preview.indicator.icon,
                                contentDescription = preview.indicator.contentDescription,
                                modifier = Modifier.size(18.dp),
                                tint = tint
                            )
                        }
                        Text(
                            text = preview.previewWithSender,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (unreadCount > 0) {
                val unreadLabel = if (unreadCount > 99) "99+" else unreadCount.toString()
                Badge(
                    modifier = Modifier.padding(start = 12.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = unreadLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedInlineIndicator(
    showLabel: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 220)),
        color = StatusConnectedContainer,
        contentColor = StatusConnectedOnContainer,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Link,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
            AnimatedVisibility(
                visible = showLabel,
                enter = fadeIn(animationSpec = tween(durationMillis = 180)),
                exit = fadeOut(animationSpec = tween(durationMillis = 180))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_status_connected),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshGeneralListItem(
    unreadCount: Int,
    timestampText: String?,
    preview: ContactPreviewUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GroupChatAvatar(
                modifier = Modifier.size(52.dp),
                iconSize = 26.dp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mesh_chat_general_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (timestampText != null) {
                        Text(
                            text = timestampText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (preview.previewText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (preview.showReadIndicator) {
                            val readIndicatorColor = Color(0xFF34B7F1)
                            Icon(
                                imageVector = Icons.Filled.DoneAll,
                                contentDescription = stringResource(
                                    if (preview.isRead) {
                                        R.string.chat_message_status_read
                                    } else {
                                        R.string.chat_message_status_delivered
                                    }
                                ),
                                modifier = Modifier.size(18.dp),
                                tint = if (preview.isRead) {
                                    readIndicatorColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                }
                            )
                        }
                        Text(
                            text = preview.previewWithSender,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.mesh_chat_general_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (unreadCount > 0) {
                val unreadLabel = if (unreadCount > 99) "99+" else unreadCount.toString()
                Badge(
                    modifier = Modifier.padding(start = 12.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = unreadLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun responsivePadding(isExpanded: Boolean): Pair<Dp, Dp> = if (isExpanded) {
    48.dp to 32.dp
} else {
    0.dp to 0.dp
}

@Composable
private fun rememberSosElapsedText(
    startTimestamp: Long?,
    isRunning: Boolean
): String? {
    val elapsedMillis = remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val shouldTick = isRunning && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    LaunchedEffect(startTimestamp, shouldTick) {
        if (startTimestamp == null) {
            elapsedMillis.longValue = 0L
            return@LaunchedEffect
        }

        val start = startTimestamp
        if (!shouldTick) {
            elapsedMillis.longValue = System.currentTimeMillis() - start
            return@LaunchedEffect
        }

        while (isActive) {
            elapsedMillis.longValue = System.currentTimeMillis() - start
            delay(1_000)
        }
    }

    if (startTimestamp == null) {
        return null
    }
    return formatElapsedForButton(elapsedMillis.longValue)
}

private fun formatElapsedForButton(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
