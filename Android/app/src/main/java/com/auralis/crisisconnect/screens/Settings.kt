package com.auralis.crisisconnect.screens

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.app.ActivityCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ThemeOption
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import com.auralis.crisisconnect.ui.components.ContactAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val viewModel: SettingsViewModel = viewModel()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appVersionName by remember(context) {
        mutableStateOf(loadAppVersionName(context))
    }
    val appVersionCode by remember(context) {
        mutableStateOf(loadAppVersionCode(context))
    }
    val openSettingsHint = stringResource(R.string.settings_missing_permissions_open_settings_hint)
    var profileBitmap by remember(context) {
        mutableStateOf(ProfileImageStorage.loadProfileImage(context))
    }
    val showFullScreenIntentCard = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    var hasFullScreenIntentAccess by remember(context, showFullScreenIntentCard) {
        mutableStateOf(canUseFullScreenIntentAccess(context))
    }
    val permissionRequirements = remember { settingsPermissionRequirements() }
    var missingPermissionRequirements by remember(context, permissionRequirements) {
        mutableStateOf(
            findMissingPermissionRequirements(
                context = context,
                requirements = permissionRequirements
            )
        )
    }
    val permissionRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingPermissionRequirements = findMissingPermissionRequirements(
            context = context,
            requirements = permissionRequirements
        )
    }

    DisposableEffect(lifecycleOwner, context, permissionRequirements) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                profileBitmap = ProfileImageStorage.loadProfileImage(context)
                missingPermissionRequirements = findMissingPermissionRequirements(
                    context = context,
                    requirements = permissionRequirements
                )
                hasFullScreenIntentAccess = canUseFullScreenIntentAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppBackTopBar(
                titleRes = R.string.Settings,
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
                    .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp)
            ) {
                if (missingPermissionRequirements.isNotEmpty()) {
                    MissingPermissionsCard(
                        missingPermissionRequirements = missingPermissionRequirements,
                        onRequestPermissions = {
                            val deniedPermissions = missingPermissionRequirements
                                .flatMap { it.permissions }
                                .filterNot { permission -> isPermissionGranted(context, permission) }
                                .distinct()
                            if (deniedPermissions.isEmpty()) return@MissingPermissionsCard

                            val hostActivity = context.findHostActivity()
                            val requestablePermissions = if (hostActivity != null) {
                                deniedPermissions.filter { permission ->
                                    val requestedBefore = wasPermissionRequestedBefore(context, permission)
                                    !requestedBefore || ActivityCompat.shouldShowRequestPermissionRationale(
                                        hostActivity,
                                        permission
                                    )
                                }
                            } else {
                                emptyList()
                            }

                            if (requestablePermissions.isNotEmpty()) {
                                markPermissionsAsRequested(context, requestablePermissions)
                                permissionRequestLauncher.launch(requestablePermissions.toTypedArray())
                            } else {
                                Toast.makeText(context, openSettingsHint, Toast.LENGTH_LONG).show()
                                openAppPermissionSettings(context)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (showFullScreenIntentCard && !hasFullScreenIntentAccess) {
                    CallAlertAccessCard(
                        hasAccess = hasFullScreenIntentAccess,
                        onOpenSettings = {
                            val opened = openFullScreenIntentSettings(context)
                            if (!opened) {
                                Toast.makeText(context, openSettingsHint, Toast.LENGTH_LONG).show()
                                openAppPermissionSettings(context)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { navController.navigate("profile") }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(40.dp)) {
                            ContactAvatar(
                                displayName = viewModel.userName,
                                stableKey = "local_profile_avatar",
                                bitmap = profileBitmap,
                                modifier = Modifier.size(40.dp),
                                textStyle = MaterialTheme.typography.labelLarge
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_profile_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.settings_profile_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                val english = stringResource(R.string.english)
                val turkish = stringResource(R.string.turkish)
                val languages = listOf(
                    "en" to english,
                    "tr" to turkish,
                )
                val selectedCode = viewModel.selectedCode
                val selectedLabel = languages.firstOrNull { it.first == selectedCode }?.second ?: english
                var showLanguageSheet by remember { mutableStateOf(false) }

                Text(
                    text = stringResource(R.string.select_language),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.language_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showLanguageSheet = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = selectedLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = selectedCode.uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (showLanguageSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showLanguageSheet = false }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.select_language),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.language_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            languages.forEach { (code, label) ->
                                val isSelected = selectedCode == code
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        if (!isSelected) {
                                            viewModel.updateLanguage(context, code)
                                        }
                                        showLanguageSheet = false
                                    },
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    ),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                            )
                                            Text(
                                                text = code.uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.select_theme),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.theme_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val themeOptions = listOf(
                    ThemeOption.SYSTEM to Triple(
                        stringResource(R.string.theme_system),
                        Icons.Outlined.SettingsSuggest,
                        stringResource(R.string.theme_system_hint)
                    ),
                    ThemeOption.LIGHT to Triple(
                        stringResource(R.string.theme_light),
                        Icons.Outlined.LightMode,
                        stringResource(R.string.theme_light_hint)
                    ),
                    ThemeOption.DARK to Triple(
                        stringResource(R.string.theme_dark),
                        Icons.Outlined.DarkMode,
                        stringResource(R.string.theme_dark_hint)
                    )
                )

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
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            themeOptions.forEachIndexed { index, (option, info) ->
                                SegmentedButton(
                                    modifier = Modifier.weight(1f),
                                    shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                                    onClick = { viewModel.updateTheme(option) },
                                    selected = viewModel.themeOption == option,
                                    icon = {
                                        Icon(
                                            imageVector = info.second,
                                            contentDescription = null
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = info.first,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }

                        val selectedTheme = themeOptions.firstOrNull { it.first == viewModel.themeOption }
                        selectedTheme?.let { (_, info) ->
                            Text(
                                text = info.third,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { navController.navigate("advanced_settings") }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SettingsSuggest,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_advanced_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.settings_advanced_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(
                        R.string.settings_app_version_format,
                        appVersionName,
                        appVersionCode
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CallAlertAccessCard(
    hasAccess: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = if (hasAccess) {
        stringResource(R.string.settings_call_alerts_status_enabled)
    } else {
        stringResource(R.string.settings_call_alerts_status_disabled)
    }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (hasAccess) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    }
                ) {
                    Icon(
                        modifier = Modifier.padding(8.dp),
                        imageVector = Icons.Outlined.SettingsSuggest,
                        contentDescription = null,
                        tint = if (hasAccess) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_call_alerts_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.settings_call_alerts_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (hasAccess) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold
            )

            FilledTonalButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.settings_call_alerts_manage_button))
            }
        }
    }
}

@Composable
private fun MissingPermissionsCard(
    missingPermissionRequirements: List<SettingsPermissionRequirement>,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val missingList = remember(missingPermissionRequirements, context) {
        missingPermissionRequirements.joinToString(separator = " • ") {
            context.getString(it.labelRes)
        }
    }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                ) {
                    Icon(
                        modifier = Modifier.padding(8.dp),
                        imageVector = Icons.Outlined.SettingsSuggest,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_missing_permissions_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_missing_permissions_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        text = stringResource(
                            R.string.settings_missing_permissions_badge_count,
                            missingPermissionRequirements.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_missing_permissions_impact),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(
                    R.string.settings_missing_permissions_missing_list,
                    missingList
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.settings_missing_permissions_request_button))
            }
        }
    }
}

private fun loadAppVersionName(context: Context): String {
    val packageInfo = getPackageInfoOrNull(context) ?: return "?"
    return packageInfo.versionName?.takeIf { it.isNotBlank() } ?: "?"
}

private fun loadAppVersionCode(context: Context): Long {
    val packageInfo = getPackageInfoOrNull(context) ?: return 0L
    return PackageInfoCompat.getLongVersionCode(packageInfo)
}

private fun getPackageInfoOrNull(context: Context): PackageInfo? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

private data class SettingsPermissionRequirement(
    val labelRes: Int,
    val permissions: List<String>
)

private fun settingsPermissionRequirements(): List<SettingsPermissionRequirement> {
    val requirements = mutableListOf<SettingsPermissionRequirement>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        requirements += SettingsPermissionRequirement(
            labelRes = R.string.permission_group_bluetooth,
            permissions = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        )
        requirements += SettingsPermissionRequirement(
            labelRes = R.string.permission_group_location,
            permissions = listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    } else {
        requirements += SettingsPermissionRequirement(
            labelRes = R.string.permission_group_location,
            permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        requirements += SettingsPermissionRequirement(
            labelRes = R.string.permission_group_notifications,
            permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
        )
    }
    return requirements
}

private fun findMissingPermissionRequirements(
    context: Context,
    requirements: List<SettingsPermissionRequirement>
): List<SettingsPermissionRequirement> {
    return requirements.filter { requirement ->
        requirement.permissions.any { permission -> !isPermissionGranted(context, permission) }
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        permission
    ) == PackageManager.PERMISSION_GRANTED
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

private fun canUseFullScreenIntentAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return true
    }
    val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
    return runCatching { notificationManager.canUseFullScreenIntent() }
        .getOrDefault(false)
}

private fun openFullScreenIntentSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return false
    }
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        false
    }
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

private const val PERMISSION_REQUEST_PREFS = "settings_permission_requests"
private const val PERMISSION_REQUESTED_KEY_PREFIX = "requested_"
