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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MissedVideoCall
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ai.CrisisSentinelModelFileStore
import com.auralis.crisisconnect.ai.CrisisSentinelModelManifestCache
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.core.chat.stripReplyMetadata
import com.auralis.crisisconnect.core.search.normalizeForSearch
import com.auralis.crisisconnect.core.search.searchableMessageBody
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.AuthorityMeshChatStore
import com.auralis.crisisconnect.data.GattMeshChatStore
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.MeshMessageStatus
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.feature.RescueFeatureManager
import com.auralis.crisisconnect.isScreenshotDemoModeEnabledSync
import com.auralis.crisisconnect.screens.Chat.ChatScreenshotDemoScenario
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
import com.auralis.crisisconnect.ui.components.rememberConnectedSessions
import com.auralis.crisisconnect.ui.theme.StatusConnectedContainer
import com.auralis.crisisconnect.ui.theme.StatusConnectedOnContainer
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.toAuthorityConversationEntity
import com.auralis.crisisconnect.data.toChannelConversation
import com.auralis.crisisconnect.messaging.AuthorityBridgeContacts
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import com.auralis.crisisconnect.messaging.InternetConversation
import com.auralis.crisisconnect.screens.authority.ChannelConversation
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val RESCUE_ROLE_LOG_TAG = "RescueRoleCheck"
private const val SOS_BROADCAST_LOG_TAG = "SosBroadcastStart"
private val ALLOWED_RESCUE_ROLES = setOf("admin", "fieldteam")
private const val SEARCH_PULL_MIN_PREVIEW_DURATION_MS = 70L
private const val SEARCH_PULL_MIN_OPEN_DURATION_MS = 130L

/** Room the open search bar occupies above the conversation list. */
private val SEARCH_OPEN_LIST_TOP_PADDING = 84.dp

/**
 * Per-frame bookkeeping for the pull-to-search gesture. Plain fields, not snapshot state:
 * composition never reads these — the visuals follow two Animatables in the draw/placement
 * phase instead, which keeps the drag from recomposing the screen every frame.
 */
private class SearchPullGestureState {
    var distancePx = 0f
    var maxDistancePx = 0f
    var startedAtMs = 0L
    var durationMs = 0L

    val isActive: Boolean
        get() = distancePx > 0f || maxDistancePx > 0f || startedAtMs != 0L

    fun reset() {
        distancePx = 0f
        maxDistancePx = 0f
        startedAtMs = 0L
        durationMs = 0L
    }
}
private const val MAIN_CONNECTED_LABEL_DURATION_MS = 4_000L
private const val CHAT_LOCATION_PREFIX = "CC_LOC:"
private const val MESH_GENERAL_SESSION_CODE = "gattmesh:general"
private const val MESH_GENERAL_LIST_ITEM_KEY = "gattmesh:general:list_item"
private const val AUTHORITY_MESH_LIST_ITEM_KEY = "authority:general:list_item"
// The authority/rescue mesh keeps a contact row only so its messages satisfy the message↔contact
// foreign key. It must never surface in the main home conversation list (it lives in the Rescue
// screen instead), so it is always filtered out here.
private const val AUTHORITY_MESH_SESSION_CODE = "authority:general"
private const val PERMISSION_REQUEST_PREFS = "settings_permission_requests"
private const val PERMISSION_REQUESTED_KEY_PREFIX = "requested_"
private val GOOGLE_MAPS_LOCATION_REGEX =
    Regex("""https?://maps\.google\.com/\?q=([-0-9.]+),([-0-9.]+)""")

private enum class WelcomeContinueAction {
    RequestPermissions,
    OpenSettings,
    EnableBluetooth
}

private data class WelcomePermissionRequirement(
    val labelRes: Int,
    val descriptionRes: Int,
    val permissions: List<String>
)

private data class WelcomeContinueBlocker(
    val buttonText: String,
    val detailText: String,
    val action: WelcomeContinueAction? = null,
    val actionText: String? = null
)

internal data class WelcomeReadinessItem(
    val title: String,
    val description: String,
    val isReady: Boolean,
    val icon: WelcomeReadinessIcon
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

private fun isCrisisSentinelModelReady(context: Context): Boolean {
    val appContext = context.applicationContext
    val release = CrisisSentinelModelManifestCache(appContext).load()
        ?: CrisisSentinelModelFileStore.defaultRelease
    return CrisisSentinelModelFileStore(appContext)
        .status(release = release, verifyChecksum = false)
        .isReady
}

private fun welcomePermissionRequirements(): List<WelcomePermissionRequirement> {
    return buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                WelcomePermissionRequirement(
                    labelRes = R.string.permission_group_bluetooth,
                    descriptionRes = R.string.welcome_permission_bluetooth_description,
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
                    descriptionRes = R.string.welcome_permission_location_description,
                    permissions = listOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            )
        } else {
            add(
                WelcomePermissionRequirement(
                    labelRes = R.string.permission_group_location,
                    descriptionRes = R.string.welcome_permission_location_description,
                    permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                WelcomePermissionRequirement(
                    labelRes = R.string.permission_group_nearby_wifi,
                    descriptionRes = R.string.welcome_permission_nearby_wifi_description,
                    permissions = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                )
            )
            add(
                WelcomePermissionRequirement(
                    labelRes = R.string.permission_group_notifications,
                    descriptionRes = R.string.welcome_permission_notifications_description,
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }
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
        .map { requirement ->
            val labelRes = if (requirement.labelRes == R.string.permission_group_nearby_wifi) {
                R.string.welcome_setup_bluetooth_title
            } else {
                requirement.labelRes
            }
            context.getString(labelRes)
        }
        .distinct()

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
            context.getString(R.string.welcome_continue_action_request_permissions)
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

private fun buildWelcomeReadinessItems(context: Context): List<WelcomeReadinessItem> {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothSupported =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            bluetoothManager?.adapter != null
    val bluetoothEnabled = bluetoothSupported &&
        runCatching { bluetoothManager?.adapter?.isEnabled == true }.getOrElse { false }
    val permissionRequirements = welcomePermissionRequirements()
    val nearbyLinkPermissions = permissionRequirements
        .filter { requirement ->
            requirement.labelRes == R.string.permission_group_bluetooth ||
                requirement.labelRes == R.string.permission_group_nearby_wifi
        }
        .flatMap { requirement -> requirement.permissions }
    val locationPermissions = permissionRequirements
        .filter { requirement -> requirement.labelRes == R.string.permission_group_location }
        .flatMap { requirement -> requirement.permissions }
    val notificationPermissions = permissionRequirements
        .filter { requirement -> requirement.labelRes == R.string.permission_group_notifications }
        .flatMap { requirement -> requirement.permissions }

    return buildList {
        add(
            WelcomeReadinessItem(
                title = context.getString(R.string.welcome_setup_bluetooth_title),
                description = context.getString(R.string.welcome_setup_bluetooth_description),
                isReady = bluetoothSupported &&
                    bluetoothEnabled &&
                    nearbyLinkPermissions.all { permission -> context.isPermissionGranted(permission) },
                icon = WelcomeReadinessIcon.NearbyLinks
            )
        )
        if (locationPermissions.isNotEmpty()) {
            add(
                WelcomeReadinessItem(
                    title = context.getString(R.string.permission_group_location),
                    description = context.getString(R.string.welcome_permission_location_description),
                    isReady = locationPermissions.all { permission ->
                        context.isPermissionGranted(permission)
                    },
                    icon = WelcomeReadinessIcon.Location
                )
            )
        }
        if (notificationPermissions.isNotEmpty()) {
            add(
                WelcomeReadinessItem(
                    title = context.getString(R.string.permission_group_notifications),
                    description = context.getString(R.string.welcome_permission_notifications_description),
                    isReady = notificationPermissions.all { permission ->
                        context.isPermissionGranted(permission)
                    },
                    icon = WelcomeReadinessIcon.Notifications
                )
            )
        }
    }
}

private fun welcomePermissionGroupsForIcon(icon: WelcomeReadinessIcon): Set<Int> {
    return when (icon) {
        WelcomeReadinessIcon.NearbyLinks -> setOf(
            R.string.permission_group_bluetooth,
            R.string.permission_group_nearby_wifi
        )
        WelcomeReadinessIcon.Location -> setOf(R.string.permission_group_location)
        WelcomeReadinessIcon.Notifications -> setOf(R.string.permission_group_notifications)
    }
}

private fun missingPermissionsForReadinessIcon(
    context: Context,
    icon: WelcomeReadinessIcon
): List<String> {
    val groups = welcomePermissionGroupsForIcon(icon)
    return welcomePermissionRequirements()
        .filter { requirement -> requirement.labelRes in groups }
        .flatMap { requirement -> requirement.permissions }
        .distinct()
        .filterNot { permission -> context.isPermissionGranted(permission) }
}

private fun isBluetoothCurrentlyEnabled(context: Context): Boolean {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
        bluetoothManager?.adapter != null
    return supported && runCatching { bluetoothManager?.adapter?.isEnabled == true }.getOrElse { false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onOnboardingCompleted: () -> Unit = {}
) {
    val viewModel: MainScreenViewModel = viewModel()
    val showDialog by viewModel.showDialog.collectAsStateWithLifecycle()
    val publicMeshEnabled by viewModel.publicMeshEnabled.collectAsStateWithLifecycle()
    val authorityMeshEnabled by viewModel.authorityMeshEnabled.collectAsStateWithLifecycle()
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
    // Pinned "Kurtarma ağı" entry on the messages home (mirrors iOS' authority entry card), shown to
    // verified rescuers while the authority mesh is on. Opens the authority chat in the rescue module.
    val showAuthorityMeshEntry = authorityMeshEnabled &&
        rescueRoleResult is RescueRoleResult.Authorized
    val onAuthorityMeshSelected: () -> Unit = {
        rescueFeatureManager.launchInstalled(
            context,
            startDestination = RescueFeatureManager.START_DESTINATION_MESH_CHAT
        )
    }
    val sosCountdownNavigator = remember(navController) {
        {
            navController.navigate("sos_countdown") {
                launchSingleTop = true
            }
        }
    }
    val requestEnableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sosCountdownNavigator()
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
                securityRepository.warmUpCertificateInBackground()
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
        var fullName by rememberSaveable { mutableStateOf("") }
        var isTermsAccepted by rememberSaveable { mutableStateOf(false) }
        val blocker = welcomeContinueBlocker
        val termsUrl = stringResource(R.string.welcome_terms_url)
        val privacyUrl = stringResource(R.string.welcome_privacy_url)
        val readinessItems = remember(context, blocker) {
            buildWelcomeReadinessItems(context)
        }
        WelcomeScreen(
            fullName = fullName,
            onFullNameChange = { fullName = it },
            isTermsAccepted = isTermsAccepted,
            onTermsAcceptedChange = { isTermsAccepted = it },
            readinessItems = readinessItems,
            arePermissionsSatisfied = blocker == null,
            permissionActionLabel = blocker?.actionText ?: blocker?.buttonText,
            completeActionLabel = stringResource(R.string.welcome_finish_button),
            blockerDetailText = blocker?.detailText,
            onOpenTerms = { openLegalLink(context, termsUrl) },
            onOpenPrivacy = { openLegalLink(context, privacyUrl) },
            onResolveBlocker = {
                when (blocker?.action) {
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

                    null -> Unit
                }
            },
            onRequestPermission = { item ->
                // Tapping a not-ready card surfaces the system prompt for just that
                // permission group (or the Bluetooth enable sheet for nearby links).
                if (item.icon == WelcomeReadinessIcon.NearbyLinks &&
                    !isBluetoothCurrentlyEnabled(context)
                ) {
                    runCatching {
                        welcomeEnableBluetoothLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        )
                    }.onFailure { throwable ->
                        Log.w(
                            SOS_BROADCAST_LOG_TAG,
                            "Unable to show Bluetooth enable prompt from welcome card",
                            throwable
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.rescue_error_bluetooth_disabled),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    val missing = missingPermissionsForReadinessIcon(context, item.icon)
                    val requestable = requestableWelcomePermissions(context, missing)
                    if (requestable.isNotEmpty()) {
                        markPermissionsAsRequested(context, requestable)
                        welcomePermissionRequestLauncher.launch(requestable.toTypedArray())
                    } else {
                        openAppPermissionSettings(context)
                    }
                }
            },
            onComplete = {
                viewModel.acceptDialog(fullName)
                onOnboardingCompleted()
            }
        )
        return
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
            val isSosActive by GattSOSServerService.isDeclared.collectAsStateWithLifecycle()
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
                        if (isSosActive) {
                            navController.navigate("sos_status") {
                                launchSingleTop = true
                            }
                            return@ExtendedFloatingActionButton
                        }

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
                                    sosCountdownNavigator()
                                }
                                return@ExtendedFloatingActionButton
                            }
                        }
                        sosCountdownNavigator()
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
                showAuthorityMeshEntry = showAuthorityMeshEntry,
                onAuthorityMeshSelected = onAuthorityMeshSelected,
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
    showAuthorityMeshEntry: Boolean,
    onAuthorityMeshSelected: () -> Unit,
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
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val messageSearchResults by viewModel.messageSearchResults.collectAsStateWithLifecycle()
    val meshGeneralMessages by GattMeshChatStore.messages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Cross-panel (hierarchy) channel peers, surfaced as normal-looking rows in the home list.
    // Loaded once when the messages screen composes — hoisted to the top of the body so it runs
    // regardless of which contacts/empty/loading branch renders below. Empty for non-managers, so
    // no gate is needed. Only peers you've ACTUALLY messaged appear here (like a normal chat list) —
    // the full reachable roster lives in the "Kurumdan ekle" picker, not on the home screen.
    // Offline-first: the local (SQLCipher) conversation cache drives the list, so it renders instantly
    // and survives with no connectivity — a disaster app should still show your chats offline.
    val authorityDao = remember(appContext) { AppDatabase.getInstance(appContext).authorityMessageDao() }
    val channelConversations by remember(authorityDao) {
        authorityDao.observeConversations().map { rows -> rows.map { it.toChannelConversation() } }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // (channel:peer) keys whose last incoming message is newer than the thread's read cursor → home badge.
    val channelUnreadKeys by remember(authorityDao) {
        authorityDao.observeUnreadKeys().map { keys -> keys.map { "${it.channelId}:${it.peerUid}" }.toSet() }
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    // (channel:peer) → the peer's hidden Bluetooth-bridge sessionCode, so channel rows can show the
    // same connected pill citizen rows get when the offline link is up.
    val myUid = remember { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }
    val channelBridgeSessions = remember(channelConversations, myUid) {
        if (myUid.isBlank()) {
            emptyMap()
        } else {
            channelConversations.associate { conversation ->
                "${conversation.channelId}:${conversation.peerUid}" to
                    InternetConversation.pairId(myUid, conversation.peerUid)
            }
        }
    }
    // When online, refresh the previews from the roster and write them through into Room.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val client = HierarchyMessagingClient()
                val channels = client.fetchChannels()
                // Hidden Bluetooth-bridge contacts for peers whose number the backend released,
                // so the kurum chat gains an offline transport (fire-and-forget; failures only
                // cost the offline capability, never the online refresh below).
                runCatching { AuthorityBridgeContacts.syncFromChannels(appContext, channels) }
                    .onFailure { android.util.Log.w("AuthorityBridge", "bridge sync failed", it) }
                val candidates = channels.flatMap { channel ->
                    channel.peers.map { peer -> channel to peer }
                }
                // Keep only peers you've actually messaged, and carry the last-message preview for the
                // row. Probe channels in parallel; newest conversation first, like a normal chat list.
                val fresh = coroutineScope {
                    candidates.map { (channel, peer) ->
                        async {
                            val preview = client.latestMessageWith(channel.channelId, peer.uid)
                                ?: return@async null
                            ChannelConversation(
                                channelId = channel.channelId,
                                peerUid = peer.uid,
                                // Show a real name, not the backend's login-email fallback: prefer a
                                // non-email roster name, else the name the peer stamped on their
                                // messages, else the email's local part (iOS parity).
                                peerName = com.auralis.crisisconnect.messaging.AuthorityNameResolver
                                    .resolve(peer.name, preview.peerName),
                                peerPanelName = channel.peerPanelName,
                                group = channel.group,
                                lastText = preview.text,
                                lastAtMillis = preview.atMillis,
                                lastSenderUid = preview.senderUid,
                                lastAttachmentKind = preview.attachmentKind,
                                peerRole = peer.role ?: "",
                            )
                        }
                    }.awaitAll().filterNotNull()
                }
                authorityDao.replaceConversations(fresh.map { it.toAuthorityConversationEntity() })
            } catch (e: Exception) {
                android.util.Log.w("AuthorityChannels", "channel roster refresh failed", e)
                // Offline / transient failure: keep whatever conversations are already cached.
            }
        }
    }
    // Use screen width from configuration instead of BoxWithConstraints scope
    val configuration = LocalConfiguration.current
    val isExpandedScreen = configuration.screenWidthDp >= 600
    val listState = rememberLazyListState()
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }
    // Raw gesture bookkeeping lives outside the snapshot system on purpose: nothing composes
    // off these per-frame values. The UI only observes the two Animatables (bar reveal, list
    // shift) in the draw/placement phase, plus the isPullReady flip for the hint text + haptic.
    val pullGesture = remember { SearchPullGestureState() }
    val searchBarReveal = remember { Animatable(0f) }
    val listPullOffset = remember { Animatable(0f) }
    var isPullReady by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val pullScope = rememberCoroutineScope()
    var isCrisisSentinelReady by remember(appContext) {
        mutableStateOf(isCrisisSentinelModelReady(appContext))
    }
    val pullThresholdPx = with(LocalDensity.current) { 92.dp.toPx() }
    val pullRevealStartPx = with(LocalDensity.current) { 16.dp.toPx() }
    val searchOpenShiftPx = with(LocalDensity.current) { SEARCH_OPEN_LIST_TOP_PADDING.toPx() }
    val showSearchBar = isSearchVisible || searchQuery.isNotBlank()
    val normalizedQuery = remember(searchQuery) { normalizeForSearch(searchQuery.trim()) }
    // Field-team users (valid rescue certificate) always get the entry — they can use the cloud
    // engine without the on-device model; everyone else needs the model installed.
    val showCrisisSentinelEntry = remember(
        isCrisisSentinelReady,
        isCurrentUserRescue,
        normalizedQuery,
        context
    ) {
        if (!isCrisisSentinelReady && !isCurrentUserRescue) {
            false
        } else {
            normalizedQuery.isEmpty() ||
                normalizeForSearch(context.getString(R.string.tool_crisis_sentinel_title))
                    .contains(normalizedQuery) ||
                normalizeForSearch(context.getString(R.string.tool_crisis_sentinel_description))
                    .contains(normalizedQuery)
        }
    }
    // While searching, the pinned mesh entries obey the query like everything else.
    val showGeneralMeshEntryForList = showGeneralMeshEntry && (
        normalizedQuery.isEmpty() ||
            normalizeForSearch(stringResource(R.string.mesh_chat_general_title))
                .contains(normalizedQuery)
        )
    val showAuthorityMeshEntryForList = showAuthorityMeshEntry && (
        normalizedQuery.isEmpty() ||
            normalizeForSearch(stringResource(R.string.authority_mesh_chat_title))
                .contains(normalizedQuery)
        )
    // The bar stays composed while it is even partially revealed (dragging, springing back,
    // or fading out on close); the boolean only flips at the edges.
    val isSearchBarRevealed by remember {
        derivedStateOf { searchBarReveal.value > 0.001f }
    }
    val searchRevealFraction: () -> Float = { searchBarReveal.value }
    val filteredContacts = remember(contacts, normalizedQuery) {
        filterContactsForGlobalSearch(
            contacts = contacts,
            normalizedQuery = normalizedQuery
        )
    }
    // Cross-panel (authority) rows obey the same search box as chats: match on the peer's name, their
    // agency/panel, or the last-message preview. Without this the search bar silently skipped them.
    val filteredChannelConversations = remember(channelConversations, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            channelConversations
        } else {
            channelConversations.filter { conversation ->
                normalizeForSearch(conversation.peerName).contains(normalizedQuery) ||
                    normalizeForSearch(conversation.peerPanelName).contains(normalizedQuery) ||
                    normalizeForSearch(conversation.peerUid).contains(normalizedQuery) ||
                    normalizeForSearch(conversation.lastText).contains(normalizedQuery)
            }
        }
    }
    // Matched messages become their own result rows (with the contact's name resolved for the
    // header), instead of silently keeping the contact in the list like the old search did.
    val searchMessageRows = remember(messageSearchResults, contacts, isCurrentUserRescue, context) {
        if (messageSearchResults.messages.isEmpty()) {
            emptyList()
        } else {
            val contactsBySession = contacts.associateBy { it.sessionCode }
            messageSearchResults.messages.mapNotNull { message ->
                if (message.sessionCode.equals(AUTHORITY_MESH_SESSION_CODE, ignoreCase = true)) {
                    return@mapNotNull null
                }
                val contact = contactsBySession[message.sessionCode]
                SearchMessageRowUi(
                    messageId = message.id,
                    sessionCode = message.sessionCode,
                    timestampMillis = message.timestampMillis,
                    displayName = contact?.let {
                        mainListContactDisplayName(
                            contact = it,
                            isCurrentUserRescue = isCurrentUserRescue,
                            context = context
                        )
                    } ?: message.sessionCode,
                    stableKey = contact?.let { contactStableKey(it) } ?: message.sessionCode,
                    preferredTransport = contact?.preferredTransport,
                    body = searchableMessageBody(message) ?: message.text
                )
            }
        }
    }
    // Only claim "no results" once the debounced message search has caught up with what's
    // typed — otherwise the empty state flashes while results are still being computed.
    val isSearchSettled = messageSearchResults.query == normalizedQuery
    val showSearchNoResults = isContactsLoaded &&
        normalizedQuery.isNotEmpty() &&
        isSearchSettled &&
        filteredContacts.isEmpty() &&
        filteredChannelConversations.isEmpty() &&
        searchMessageRows.isEmpty() &&
        !showGeneralMeshEntryForList &&
        !showAuthorityMeshEntryForList &&
        !showCrisisSentinelEntry
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
    // Maps the raw pull distance to the 0..1 reveal the bar and the list shift both follow.
    fun pullRevealTarget(): Float {
        if (pullGesture.durationMs < SEARCH_PULL_MIN_PREVIEW_DURATION_MS) {
            return 0f
        }
        return (
            (pullGesture.distancePx - pullRevealStartPx) / (pullThresholdPx - pullRevealStartPx)
            ).coerceIn(0f, 1f)
    }
    // Called on every drag frame: snap the visuals to the finger and fire one haptic tick
    // exactly when the pull crosses the open threshold.
    fun updatePullVisuals() {
        val fraction = pullRevealTarget()
        pullScope.launch {
            searchBarReveal.snapTo(fraction)
            listPullOffset.snapTo(fraction * searchOpenShiftPx)
        }
        val ready = pullGesture.distancePx >= pullThresholdPx
        if (ready != isPullReady) {
            isPullReady = ready
            if (ready) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    fun shouldOpenSearchFromPull(): Boolean {
        if (isSearchVisible) {
            return false
        }
        return pullGesture.maxDistancePx >= pullThresholdPx &&
            pullGesture.durationMs >= SEARCH_PULL_MIN_OPEN_DURATION_MS
    }
    // Finger lifted: either commit to opening search or spring everything back home.
    fun finishPull() {
        val open = shouldOpenSearchFromPull()
        pullGesture.reset()
        isPullReady = false
        if (open) {
            isSearchVisible = true
            // The list swaps from a transient draw offset to real top padding in the same
            // frame, so subtract the padding from the offset to keep the rows visually still.
            pullScope.launch {
                listPullOffset.snapTo(listPullOffset.value - searchOpenShiftPx)
                listPullOffset.animateTo(0f, tween(durationMillis = 210, easing = FastOutSlowInEasing))
            }
        } else {
            pullScope.launch {
                searchBarReveal.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
            pullScope.launch {
                listPullOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    fun closeSearch() {
        viewModel.onSearchQueryChange("")
        isSearchVisible = false
        pullGesture.reset()
        isPullReady = false
        // Mirror image of opening: padding drops instantly, the offset takes its place and
        // then animates out so the rows slide up in step with the bar fading away.
        pullScope.launch {
            listPullOffset.snapTo(listPullOffset.value + searchOpenShiftPx)
            listPullOffset.animateTo(0f, tween(durationMillis = 170, easing = FastOutSlowInEasing))
        }
    }
    BackHandler(enabled = showSearchBar) {
        closeSearch()
    }
    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            // Springs from wherever the pull left it — no restart-from-zero dip.
            searchBarReveal.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 420f))
        } else if (searchBarReveal.value > 0f) {
            searchBarReveal.animateTo(0f, tween(durationMillis = 170, easing = FastOutSlowInEasing))
        }
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
                        if (pullGesture.startedAtMs == 0L) {
                            pullGesture.startedAtMs = now
                        }
                        val dampedDelta = available.y * 0.55f
                        val nextDistance = (pullGesture.distancePx + dampedDelta).coerceAtMost(
                            pullThresholdPx * 1.35f
                        )
                        pullGesture.distancePx = nextDistance
                        if (nextDistance > pullGesture.maxDistancePx) {
                            pullGesture.maxDistancePx = nextDistance
                        }
                        pullGesture.durationMs =
                            (now - pullGesture.startedAtMs).coerceAtLeast(0L)
                        updatePullVisuals()
                    }

                    (available.y < 0f || !isAtTop) && pullGesture.isActive -> {
                        pullGesture.distancePx =
                            (pullGesture.distancePx + available.y).coerceAtLeast(0f)
                        if (!isAtTop) {
                            pullGesture.startedAtMs = 0L
                        }
                        updatePullVisuals()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullGesture.isActive) {
                    finishPull()
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(listState, isSearchVisible, pullThresholdPx) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { isScrolling ->
                if (!isScrolling && !isSearchVisible && pullGesture.isActive) {
                    finishPull()
                }
            }
    }

    DisposableEffect(lifecycleOwner, appContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isCrisisSentinelReady = isCrisisSentinelModelReady(appContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
        } else if (searchQuery.isBlank() && contacts.isEmpty() && channelConversations.isEmpty() &&
            !showGeneralMeshEntry && !(isCrisisSentinelReady || isCurrentUserRescue)) {
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
                    showCrisisSentinelEntry = showCrisisSentinelEntry,
                    showGeneralMeshEntry = showGeneralMeshEntryForList,
                    showAuthorityMeshEntry = showAuthorityMeshEntryForList,
                    onAuthorityMeshSelected = onAuthorityMeshSelected,
                    isSearchActive = normalizedQuery.isNotEmpty(),
                    normalizedQuery = normalizedQuery,
                    searchMessageRows = searchMessageRows,
                    channelConversations = filteredChannelConversations,
                    channelUnreadKeys = channelUnreadKeys,
                    channelBridgeSessions = channelBridgeSessions,
                    onChannelSelected = { conversation ->
                        navController.navigate(
                            "authority_channel/${Uri.encode(conversation.channelId)}/" +
                                "${Uri.encode(conversation.peerUid)}?title=${Uri.encode(conversation.peerName)}" +
                                "&agency=${Uri.encode(conversation.peerPanelName)}" +
                                "&role=${Uri.encode(conversation.peerRole)}"
                        )
                    },
                    meshGeneralUnreadCount = meshGeneralUnreadCount,
                    meshGeneralMessages = meshGeneralMessages,
                    onContactSelected = onContactSelected,
                    onCrisisSentinelSelected = { navController.navigate("crisis_sentinel_home") },
                    listState = listState,
                    // Real layout padding only while search is open; the drag preview moves the
                    // rows with a placement-phase offset instead so pulling never relayouts.
                    contentPadding = PaddingValues(
                        top = if (showSearchBar) SEARCH_OPEN_LIST_TOP_PADDING else 0.dp
                    ),
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = listModifier.offset {
                        IntOffset(x = 0, y = listPullOffset.value.roundToInt())
                    }
                )

                if (showSearchBar || isSearchBarRevealed) {
                    MainScreenSearchBar(
                        query = searchQuery,
                        onQueryChange = {
                            if (showSearchBar) {
                                viewModel.onSearchQueryChange(it)
                            }
                        },
                        onClose = ::closeSearch,
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(
                                R.string.main_search_no_results_for,
                                searchQuery.trim()
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
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
    revealFraction: () -> Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Opening search (via pull or otherwise) should land the user straight in the field,
    // keyboard up — without this the bar appears but a second tap is needed to type.
    LaunchedEffect(interactive) {
        if (interactive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
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
        alpha = if (interactive) 0.55f else if (pullReady) 0.45f else 0.28f
    )

    Surface(
        modifier = modifier
            .graphicsLayer {
                // Deferred read: the pull gesture animates the bar in the draw phase only,
                // so dragging never recomposes the screen.
                val reveal = revealFraction().coerceIn(0f, 1f)
                alpha = reveal
                translationY = (-12).dp.toPx() * (1f - reveal)
                val scale = 0.94f + 0.06f * reveal
                scaleX = scale
                scaleY = scale
            }
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
                .padding(
                    start = if (interactive) 4.dp else 16.dp,
                    end = if (interactive) 4.dp else 16.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (interactive) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    // Threshold feedback: the icon adopts the accent color the moment
                    // releasing would open search (paired with the haptic tick).
                    tint = if (pullReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.width(if (interactive) 4.dp else 12.dp))
            BasicTextField(
                value = query,
                onValueChange = { value ->
                    if (interactive) {
                        onQueryChange(value)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                readOnly = !interactive,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
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
    showCrisisSentinelEntry: Boolean,
    showGeneralMeshEntry: Boolean,
    showAuthorityMeshEntry: Boolean,
    onAuthorityMeshSelected: () -> Unit,
    isSearchActive: Boolean,
    normalizedQuery: String,
    searchMessageRows: List<SearchMessageRowUi>,
    channelConversations: List<ChannelConversation>,
    onChannelSelected: (ChannelConversation) -> Unit,
    channelUnreadKeys: Set<String>,
    channelBridgeSessions: Map<String, String>,
    meshGeneralUnreadCount: Int,
    meshGeneralMessages: List<MeshChatMessage>,
    onContactSelected: (String, String?, String?) -> Unit,
    onCrisisSentinelSelected: () -> Unit,
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
        sortedContacts.filterNot { contact ->
            // Authority/rescue mesh is never a home conversation; the public general mesh is rendered
            // as a dedicated pinned entry instead of a plain contact row.
            contact.sessionCode.equals(AUTHORITY_MESH_SESSION_CODE, ignoreCase = true) ||
                (showGeneralMeshEntry &&
                    contact.sessionCode.equals(MESH_GENERAL_SESSION_CODE, ignoreCase = true))
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
                peerPhotoUrl = contact.peerPhotoUrl,
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
    // Merge normal chats and authority conversations into one recency-sorted list so they interleave
    // (same comparator as sortContactsForMainList: rescuer-first, then unread, then most recent).
    val homeRows = remember(
        rows,
        visibleContacts,
        channelConversations,
        channelUnreadKeys,
        unreadCounts,
        latestMessages,
        latestCallEvents,
        activeCalls,
        timeFormatter,
        context
    ) {
        val contactItems = visibleContacts.zip(rows).map { (contact, rowUi) ->
            HomeRow.ContactRow(
                rowUi = rowUi,
                unread = unreadCounts[contact.sessionCode] ?: 0,
                sortIsRescuer = isRescuerContact(contact),
                sortMillis = latestContactActivityTimestampMillis(
                    lastMessage = latestMessages[contact.sessionCode],
                    lastCallEvent = latestCallEvents[contact.sessionCode],
                    activeCall = activeCalls[contact.sessionCode]
                ) ?: 0L
            )
        }
        val channelItems = channelConversations.map { conversation ->
            HomeRow.ChannelRow(
                conversation = conversation,
                preview = buildChannelPreviewUi(context, conversation),
                unread = if ("${conversation.channelId}:${conversation.peerUid}" in channelUnreadKeys) 1 else 0,
                timestampText = conversation.lastAtMillis
                    .takeIf { it > 0L }
                    ?.let { timeFormatter.format(Date(it)) }
            )
        }
        (contactItems + channelItems).sortedWith(
            compareByDescending<HomeRow> { it.sortIsRescuer }
                .thenByDescending { it.sortUnread }
                .thenByDescending { it.sortMillis }
        )
    }

    val hasChatResults = showCrisisSentinelEntry || showGeneralMeshEntry ||
        showAuthorityMeshEntry || homeRows.isNotEmpty()

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding
    ) {
        // While searching, split the results WhatsApp-style: chats that match by name first,
        // then the individual messages whose text matched.
        if (isSearchActive && hasChatResults) {
            item(
                key = "search_section_chats",
                contentType = "search_section_header"
            ) {
                SearchSectionHeader(text = stringResource(R.string.main_search_section_chats))
            }
        }
        if (showCrisisSentinelEntry) {
            item(
                key = "crisis_sentinel_main_entry",
                contentType = "crisis_sentinel_entry"
            ) {
                CrisisSentinelMainListItem(onClick = onCrisisSentinelSelected)
            }
        }
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
        if (showAuthorityMeshEntry) {
            item(
                key = AUTHORITY_MESH_LIST_ITEM_KEY,
                contentType = "authority_mesh_item"
            ) {
                val authorityUnread by AuthorityMeshChatStore.unreadCount
                    .collectAsStateWithLifecycle()
                MeshGeneralListItem(
                    unreadCount = authorityUnread,
                    timestampText = null,
                    preview = ContactPreviewUi(
                        previewText = "",
                        previewWithSender = "",
                        showReadIndicator = false,
                        isRead = false
                    ),
                    onClick = onAuthorityMeshSelected,
                    titleRes = R.string.authority_mesh_chat_title,
                    subtitleRes = R.string.authority_mesh_chat_subtitle
                )
            }
        }
        // One merged, recency-sorted list: cross-panel authority conversations interleave with chats as
        // normal-looking rows (same ContactListItem), each carrying its own unread badge.
        items(
            items = homeRows,
            key = { it.key },
            contentType = { it.contentType }
        ) { homeRow ->
            when (homeRow) {
                is HomeRow.ChannelRow -> {
                    val conversation = homeRow.conversation
                    // Live Bluetooth link to this peer's hidden bridge contact → same connected
                    // pill a citizen row gets.
                    val bridgeSession =
                        channelBridgeSessions["${conversation.channelId}:${conversation.peerUid}"]
                    val isBridgeConnected =
                        bridgeSession != null && bridgeSession in connectedSessions
                    ContactListItem(
                        contactName = conversation.peerName.ifBlank { conversation.peerUid },
                        sessionCode = "authority:${conversation.channelId}:${conversation.peerUid}",
                        contactStableKey = "authch:${conversation.channelId}:${conversation.peerUid}",
                        peerPhotoUrl = null,
                        // Which agency, not a generic "Authority" — shown as a chip right next to
                        // the name. The supporting text only renders when there is no message
                        // preview, and these rows always carry one, so the chip is the only spot
                        // where the agency is actually visible on the home list.
                        nameTag = conversation.peerPanelName.ifBlank {
                            stringResource(R.string.authority_channel_row_label)
                        },
                        supportingText = conversation.peerPanelName.ifBlank {
                            stringResource(R.string.authority_channel_row_label)
                        },
                        timestampText = homeRow.timestampText,
                        preview = homeRow.preview,
                        isConnected = isBridgeConnected,
                        isVerified = false,
                        showConnectedLabel = isBridgeConnected &&
                            bridgeSession in recentConnectedLabelSessions,
                        unreadCount = homeRow.unread,
                        onClick = { onChannelSelected(conversation) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
                is HomeRow.ContactRow -> {
                    val row = homeRow.rowUi
                    ContactListItem(
                        contactName = row.name,
                        sessionCode = row.sessionCode,
                        contactStableKey = row.stableKey,
                        peerPhotoUrl = row.peerPhotoUrl.ifBlank { null },
                        supportingText = row.supportingText,
                        timestampText = row.timestampText,
                        preview = row.preview,
                        isConnected = row.isConnected,
                        isVerified = row.isVerified,
                        showConnectedLabel = row.sessionCode in recentConnectedLabelSessions,
                        unreadCount = homeRow.unread,
                        onClick = { onContactSelected(row.sessionCode, row.name, row.preferredTransport) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            }
        }
        if (isSearchActive && searchMessageRows.isNotEmpty()) {
            item(
                key = "search_section_messages",
                contentType = "search_section_header"
            ) {
                SearchSectionHeader(text = stringResource(R.string.main_search_section_messages))
            }
            items(
                items = searchMessageRows,
                key = { "searchmsg:${it.messageId}" },
                contentType = { "search_message_row" }
            ) { row ->
                SearchMessageResultItem(
                    row = row,
                    normalizedQuery = normalizedQuery,
                    timestampText = formatSearchResultTimestamp(
                        timestampMillis = row.timestampMillis,
                        locale = locale,
                        timeFormatter = timeFormatter
                    ),
                    onClick = {
                        onContactSelected(row.sessionCode, row.displayName, row.preferredTransport)
                    }
                )
            }
        }
    }
}

/** A single matched message in the home-screen search results. */
@Immutable
private data class SearchMessageRowUi(
    val messageId: Long,
    val sessionCode: String,
    val timestampMillis: Long,
    val displayName: String,
    val stableKey: String,
    val preferredTransport: String?,
    val body: String
)

@Composable
private fun SearchSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SearchMessageResultItem(
    row: SearchMessageRowUi,
    normalizedQuery: String,
    timestampText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val contactAvatarVersion by ContactAvatarStorage.observeAvatarVersion(row.sessionCode)
        .collectAsStateWithLifecycle(initialValue = 0L)
    val contactAvatarBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = row.sessionCode,
        key2 = contactAvatarVersion
    ) {
        value = withContext(Dispatchers.IO) {
            ContactAvatarStorage.loadContactAvatar(context, row.sessionCode)
        }
    }
    val highlightColor = MaterialTheme.colorScheme.primary
    val snippet = remember(row.body, normalizedQuery, highlightColor) {
        buildSearchSnippet(
            body = row.body,
            normalizedQuery = normalizedQuery,
            highlightColor = highlightColor
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            displayName = row.displayName,
            stableKey = row.stableKey,
            bitmap = contactAvatarBitmap,
            photoUrl = null,
            modifier = Modifier.size(44.dp),
            textStyle = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
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
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val SEARCH_SNIPPET_LEAD_CHARS = 18

/**
 * Snippet with the matched substring highlighted. Relies on [normalizeForSearch] being
 * index-preserving: a match range found in the normalized text is applied directly to the
 * original text. Long messages are windowed so the first match stays visible.
 */
private fun buildSearchSnippet(
    body: String,
    normalizedQuery: String,
    highlightColor: Color
): AnnotatedString {
    val singleLine = body.replace('\n', ' ')
    val matchStart = if (normalizedQuery.isEmpty()) {
        -1
    } else {
        normalizeForSearch(singleLine).indexOf(normalizedQuery)
    }
    if (matchStart < 0) {
        return AnnotatedString(singleLine)
    }
    val matchEnd = matchStart + normalizedQuery.length
    val windowStart = if (matchStart <= SEARCH_SNIPPET_LEAD_CHARS) {
        0
    } else {
        val candidate = matchStart - SEARCH_SNIPPET_LEAD_CHARS
        // Snap forward to the next word boundary so the leading ellipsis cuts cleanly.
        val boundary = singleLine.indexOf(' ', candidate)
        if (boundary in candidate until matchStart) boundary + 1 else candidate
    }
    return buildAnnotatedString {
        if (windowStart > 0) {
            append("…")
        }
        append(singleLine, windowStart, matchStart)
        withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold)) {
            append(singleLine, matchStart, matchEnd)
        }
        append(singleLine, matchEnd, singleLine.length)
    }
}

/** Today → clock time; this year → "9 Tem"; otherwise with the year. */
private fun formatSearchResultTimestamp(
    timestampMillis: Long,
    locale: Locale,
    timeFormatter: SimpleDateFormat
): String? {
    if (timestampMillis <= 0L) {
        return null
    }
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestampMillis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return timeFormatter.format(Date(timestampMillis))
    }
    val pattern = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, locale).format(Date(timestampMillis))
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

/**
 * Name/code/address matching for the "Chats" section. Message-content matches are no longer
 * folded in here — they surface as their own rows in the "Messages" section instead, via the
 * ViewModel's debounced search flow. [normalizedQuery] must come from [normalizeForSearch].
 */
private fun filterContactsForGlobalSearch(
    contacts: List<Contact>,
    normalizedQuery: String
): List<Contact> {
    if (normalizedQuery.isEmpty()) {
        return contacts
    }
    return contacts.filter { contact ->
        normalizeForSearch(contact.name).contains(normalizedQuery) ||
            normalizeForSearch(contact.sessionCode).contains(normalizedQuery) ||
            normalizeForSearch(contact.address).contains(normalizedQuery)
    }
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
    val peerPhotoUrl: String,
    val supportingText: String,
    val timestampText: String?,
    val preview: ContactPreviewUi,
    val isConnected: Boolean,
    val isVerified: Boolean
)

/**
 * One home-list row — either a normal contact chat or a cross-panel authority conversation. Both are
 * merged into a single list sorted by the same keys ([sortIsRescuer] → [sortUnread] → [sortMillis]) so
 * authority chats interleave with chats by recency instead of being pinned in a separate block.
 */
private sealed interface HomeRow {
    val sortIsRescuer: Boolean
    val sortUnread: Int
    val sortMillis: Long
    val key: String
    val contentType: String

    data class ContactRow(
        val rowUi: ContactListRowUi,
        val unread: Int,
        override val sortIsRescuer: Boolean,
        override val sortMillis: Long,
    ) : HomeRow {
        override val sortUnread get() = unread
        override val key get() = rowUi.stableKey
        override val contentType get() = "contact_item"
    }

    data class ChannelRow(
        val conversation: ChannelConversation,
        val preview: ContactPreviewUi,
        val unread: Int,
        val timestampText: String?,
    ) : HomeRow {
        override val sortIsRescuer get() = false
        override val sortUnread get() = unread
        override val sortMillis get() = conversation.lastAtMillis
        override val key get() = "authch:${conversation.channelId}:${conversation.peerUid}"
        override val contentType get() = "authority_channel_row"
    }
}

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

    val visibleSessions = labelVisibility
        .filterValues { visible -> visible }
        .keys

    // Debug screenshot demo: keep the "Connected" text label permanently
    // pinned to the scripted contact instead of fading after ~4 seconds,
    // so screenshots taken any time after app launch show the pill in its
    // labelled state. BuildConfig.DEBUG gated via the sync helper.
    val context = LocalContext.current
    return if (isScreenshotDemoModeEnabledSync(context)) {
        visibleSessions + ChatScreenshotDemoScenario.DEMO_SESSION_CODE
    } else {
        visibleSessions
    }
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

/**
 * Preview for a cross-panel conversation row, styled exactly like a normal chat row (buildCallEventPreviewUi):
 * a call-log message (stored as a U+0001 sentinel + JSON) becomes a call indicator icon + clean label
 * (no "📞" emoji), while plain text is shown verbatim.
 */
private fun buildChannelPreviewUi(context: Context, conversation: ChannelConversation): ContactPreviewUi {
    val rawText = conversation.lastText
    if (rawText.isBlank()) {
        // Attachment-only last message → show its kind like ChatScreen ("Voice message"/"Photo"/"File").
        val attachmentLabel = when (conversation.lastAttachmentKind) {
            "audio" -> context.getString(R.string.conversation_preview_voice_message)
            "image" -> context.getString(R.string.conversation_preview_photo_message)
            "file" -> context.getString(R.string.chat_file_preview_label)
            else -> ""
        }
        return ContactPreviewUi(
            previewText = attachmentLabel,
            previewWithSender = attachmentLabel,
            showReadIndicator = false,
            isRead = false,
        )
    }
    if (rawText.startsWith('\u0001')) {
        val (labelRes, missed, video) = runCatching {
            val jsonPart = rawText.substringAfter('\u0001').substringAfter('\u0001')
            val obj = org.json.JSONObject(jsonPart)
            val isMissed = obj.optString("status") != "ended"
            val isVideo = obj.optString("kind") == "video"
            val res = when {
                isMissed -> R.string.authority_channel_call_missed
                isVideo -> R.string.authority_channel_call_video
                else -> R.string.authority_channel_call_audio
            }
            Triple(res, isMissed, isVideo)
        }.getOrElse { Triple(R.string.authority_channel_call_audio, false, false) }
        val label = context.getString(labelRes)
        return ContactPreviewUi(
            previewText = label,
            previewWithSender = label,
            showReadIndicator = false,
            isRead = false,
            indicator = ContactPreviewIndicatorUi(
                // Video calls get their own camera icon; audio calls keep the phone icon.
                icon = when {
                    video && missed -> Icons.Filled.MissedVideoCall
                    video -> Icons.Filled.Videocam
                    missed -> Icons.Filled.CallMissed
                    else -> Icons.Filled.Call
                },
                contentDescription = label,
                tone = if (missed) ContactPreviewIndicatorTone.Error else ContactPreviewIndicatorTone.Accent,
            ),
        )
    }
    // A shared location is a CC_LOC control payload — label it like the citizen rows do
    // instead of leaking the raw coordinates string.
    if (isChatLocationPayload(rawText)) {
        val label = context.getString(R.string.chat_location_preview_label)
        return ContactPreviewUi(
            previewText = label,
            previewWithSender = label,
            showReadIndicator = false,
            isRead = false,
        )
    }
    return ContactPreviewUi(
        previewText = rawText,
        previewWithSender = rawText,
        showReadIndicator = false,
        isRead = false,
    )
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
        MessageType.SOS_ALERT -> context.getString(R.string.conversation_preview_sos)
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
private fun CrisisSentinelMainListItem(
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
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_tool_crisis_sentinel_shine),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tool_crisis_sentinel_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.crisis_sentinel_main_entry_preview),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.CallMade,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun ContactListItem(
    contactName: String,
    sessionCode: String,
    contactStableKey: String,
    peerPhotoUrl: String? = null,
    nameTag: String? = null,
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
                    photoUrl = peerPhotoUrl,
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
                        if (!nameTag.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .widthIn(max = 132.dp)
                            ) {
                                Text(
                                    text = nameTag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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
    modifier: Modifier = Modifier,
    titleRes: Int = R.string.mesh_chat_general_title,
    subtitleRes: Int = R.string.mesh_chat_general_subtitle
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
                        text = stringResource(titleRes),
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
                        text = stringResource(subtitleRes),
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
