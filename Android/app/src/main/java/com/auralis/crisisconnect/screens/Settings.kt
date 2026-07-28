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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sos
import androidx.compose.material.icons.outlined.StarRate
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.text.Normalizer
import java.util.Locale

/**
 * A picker entry: [endonym] is the language's own name for itself ("Türkçe", "日本語") and never
 * changes with the UI language, so users can always find their language; [localizedName] is the
 * name in the current UI language, shown as a secondary hint.
 */
private data class LanguageOption(
    val code: String,
    val endonym: String,
    val localizedName: String
)

/**
 * Accent-insensitive search key so the picker finds languages however the user types them:
 * "turkce" matches "Türkçe", "viet" matches "Tiếng Việt", "espanol" matches "Español".
 * Dotless ı folds to i by hand because NFD leaves it untouched.
 */
private fun String.languageSearchKey(): String {
    val normalized = Normalizer.normalize(trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
    return buildString(normalized.length) {
        normalized.forEach { character ->
            when {
                Character.getType(character) == Character.NON_SPACING_MARK.toInt() -> Unit
                character == 'ı' -> append('i')
                else -> append(character)
            }
        }
    }
}

/** Pill search field for the language sheet, styled after the main screen search bar. */
@Composable
private fun LanguageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.language_search_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.main_search_clear),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

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

    val english = stringResource(R.string.english)
    val turkish = stringResource(R.string.turkish)
    val japanese = stringResource(R.string.japanese)
    val spanish = stringResource(R.string.spanish)
    val hindi = stringResource(R.string.hindi)
    val french = stringResource(R.string.french)
    val arabic = stringResource(R.string.arabic)
    val kurdish = stringResource(R.string.kurdish)
    val persian = stringResource(R.string.persian)
    val indonesian = stringResource(R.string.indonesian)
    val bengali = stringResource(R.string.bengali)
    val russian = stringResource(R.string.russian)
    val german = stringResource(R.string.german)
    val urdu = stringResource(R.string.urdu)
    val chinese = stringResource(R.string.chinese)
    val ukrainian = stringResource(R.string.ukrainian)
    val portuguese = stringResource(R.string.portuguese)
    val filipino = stringResource(R.string.filipino)
    val vietnamese = stringResource(R.string.vietnamese)
    val languages = listOf(
        LanguageOption("en", "English", english),
        LanguageOption("tr", "Türkçe", turkish),
        LanguageOption("ja", "日本語", japanese),
        LanguageOption("es", "Español", spanish),
        LanguageOption("hi", "हिन्दी", hindi),
        LanguageOption("fr", "Français", french),
        LanguageOption("ar", "العربية", arabic),
        LanguageOption("ku", "Kurdî", kurdish),
        LanguageOption("fa", "فارسی", persian),
        LanguageOption("id", "Bahasa Indonesia", indonesian),
        LanguageOption("bn", "বাংলা", bengali),
        LanguageOption("ru", "Русский", russian),
        LanguageOption("de", "Deutsch", german),
        LanguageOption("ur", "اردو", urdu),
        LanguageOption("zh", "中文", chinese),
        LanguageOption("uk", "Українська", ukrainian),
        LanguageOption("pt", "Português", portuguese),
        LanguageOption("fil", "Filipino", filipino),
        LanguageOption("vi", "Tiếng Việt", vietnamese),
    )
    val selectedCode = viewModel.selectedCode
    val selectedOption = languages.firstOrNull { it.code == selectedCode } ?: languages.first()
    var showLanguageSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
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
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Spacer for nice alignment under top bar
                Spacer(modifier = Modifier.height(4.dp))

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
                }

                // Section: Account
                Column {
                    SettingsSectionHeader(title = stringResource(R.string.settings_section_account))
                    GroupedSettingsCard {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_profile_title),
                            description = stringResource(R.string.settings_profile_description),
                            onClick = { navController.navigate("profile") },
                            leadingContent = {
                                ContactAvatar(
                                    displayName = viewModel.userName,
                                    stableKey = "local_profile_avatar",
                                    bitmap = profileBitmap,
                                    modifier = Modifier.size(40.dp),
                                    textStyle = MaterialTheme.typography.labelLarge
                                )
                            }
                        )

                        SettingsRowDivider()

                        SettingsNavRow(
                            icon = Icons.Outlined.ChildCare,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.child_profile_title),
                            description = stringResource(
                                if (viewModel.childProfileEnabled) {
                                    R.string.child_profile_status_on
                                } else {
                                    R.string.child_profile_status_off
                                }
                            ),
                            descriptionColor = if (viewModel.childProfileEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            onClick = { navController.navigate("child_profile_settings") }
                        )
                    }
                }

                // Section: Language
                Column {
                    SettingsSectionHeader(title = stringResource(R.string.select_language))
                    GroupedSettingsCard {
                        SettingsNavRow(
                            icon = Icons.Outlined.Language,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = selectedOption.endonym,
                            description = selectedOption.localizedName
                                .takeIf { it != selectedOption.endonym }
                                ?: selectedOption.code.uppercase(),
                            trailingIcon = Icons.Filled.ArrowDropDown,
                            onClick = { showLanguageSheet = true }
                        )
                    }
                }

                if (showLanguageSheet) {
                    // Full-height sheet: with 19 languages plus the keyboard, a half sheet
                    // leaves too little room for the list to be useful while searching.
                    val languageSheetState = rememberModalBottomSheetState(
                        skipPartiallyExpanded = true
                    )
                    // Declared inside the sheet block so the query resets on every reopen.
                    var languageQuery by remember { mutableStateOf("") }
                    val filteredLanguages = remember(languageQuery, languages) {
                        val key = languageQuery.languageSearchKey()
                        if (key.isBlank()) {
                            languages
                        } else {
                            languages.filter { option ->
                                option.endonym.languageSearchKey().contains(key) ||
                                    option.localizedName.languageSearchKey().contains(key) ||
                                    option.code.languageSearchKey().contains(key)
                            }
                        }
                    }
                    ModalBottomSheet(
                        onDismissRequest = { showLanguageSheet = false },
                        sheetState = languageSheetState
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .imePadding()
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
                            LanguageSearchField(
                                query = languageQuery,
                                onQueryChange = { languageQuery = it }
                            )
                            Spacer(modifier = Modifier.height(2.dp))

                            if (filteredLanguages.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SearchOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stringResource(R.string.language_search_no_results),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                // fill = false: short result lists keep the sheet compact
                                // instead of stretching it to full height.
                                LazyColumn(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(filteredLanguages, key = { it.code }) { option ->
                                        val isSelected = selectedCode == option.code
                                        OutlinedCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = {
                                                if (!isSelected) {
                                                    viewModel.updateLanguage(context, option.code)
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
                                                        text = option.endonym,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = option.localizedName
                                                            .takeIf { it != option.endonym }
                                                            ?: option.code.uppercase(),
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
                    }
                }

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

                // Section: Theme
                Column {
                    SettingsSectionHeader(title = stringResource(R.string.select_theme))
                    GroupedSettingsCard {
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
                                        onClick = {
                                            viewModel.updateTheme(option) {
                                                (context as? Activity)?.recreate()
                                            }
                                        },
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
                }

                // Section: General
                Column {
                    SettingsSectionHeader(title = stringResource(R.string.settings_section_general))
                    GroupedSettingsCard {
                        SettingsNavRow(
                            icon = Icons.Outlined.Sos,
                            iconTint = MaterialTheme.colorScheme.error,
                            title = stringResource(R.string.settings_sos_contacts_title),
                            description = stringResource(R.string.settings_sos_contacts_subtitle),
                            onClick = { navController.navigate("sos_emergency_contacts") }
                        )

                        SettingsRowDivider()

                        SettingsNavRow(
                            icon = Icons.Outlined.SettingsSuggest,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.settings_advanced_title),
                            description = stringResource(R.string.settings_advanced_description),
                            onClick = { navController.navigate("advanced_settings") }
                        )
                    }
                }

                if (viewModel.isDeveloperOptionsAvailable) {
                    // Debug-only section; hardcoded strings are intentional (developer-facing,
                    // never ships in release builds).
                    Column {
                        SettingsSectionHeader(title = "Developer / Debug")
                        GroupedSettingsCard {
                            SettingsRow(
                                icon = Icons.Outlined.BugReport,
                                iconTint = MaterialTheme.colorScheme.secondary,
                                title = stringResource(R.string.screenshot_demo_setting_title),
                                description = stringResource(R.string.screenshot_demo_setting_description),
                                checked = viewModel.screenshotDemoMode,
                                onCheckedChange = { enabled ->
                                    viewModel.updateScreenshotDemoMode(enabled)
                                }
                            )
                        }
                    }
                }

                // Section: Support & Feedback
                Column {
                    SettingsSectionHeader(title = stringResource(R.string.settings_section_support))
                    GroupedSettingsCard {
                        SettingsNavRow(
                            icon = Icons.Outlined.StarRate,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.settings_rate_app_title),
                            description = stringResource(R.string.settings_rate_app_description),
                            onClick = { openPlayStoreListing(context) }
                        )

                        SettingsRowDivider()

                        SettingsNavRow(
                            icon = Icons.Outlined.Share,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.settings_share_app_title),
                            description = stringResource(R.string.settings_share_app_description),
                            onClick = { shareApp(context) }
                        )

                        SettingsRowDivider()

                        SettingsNavRow(
                            icon = Icons.Outlined.MailOutline,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.settings_feedback_title),
                            description = stringResource(R.string.settings_feedback_description),
                            onClick = { sendFeedbackEmail(context, appVersionName, appVersionCode) }
                        )

                        SettingsRowDivider()

                        SettingsNavRow(
                            icon = Icons.Outlined.SupportAgent,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.settings_help_title),
                            description = stringResource(R.string.settings_help_description),
                            onClick = { openUrl(context, SUPPORT_CONTACT_URL) }
                        )
                    }
                }

                Text(
                    text = stringResource(
                        R.string.settings_app_version_format,
                        appVersionName,
                        appVersionCode
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Navigation counterpart of [SettingsRow]: identical layout and typography, but with a
 * trailing chevron instead of a switch. [leadingContent] replaces the icon badge for rows
 * that need a custom leading visual (e.g. the profile avatar).
 */
@Composable
private fun SettingsNavRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowForward,
    leadingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp) // Safe outer margin inside the card
            .clip(RoundedCornerShape(12.dp)) // Ripple will match this clean rounded shape
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp), // Inner padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (leadingContent != null) {
            Box(modifier = Modifier.size(40.dp)) {
                leadingContent()
            }
        } else if (icon != null) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = iconTint.copy(alpha = 0.12f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                ),
                color = descriptionColor
            )
        }

        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
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
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (hasAccess) {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
            }
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (hasAccess) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
            }
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
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
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
            labelRes = R.string.permission_group_nearby_wifi,
            permissions = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        )
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

private const val SUPPORT_EMAIL = "contact@crisisconnect.network"
private const val SUPPORT_CONTACT_URL = "https://crisisconnect.network/contact"

/**
 * Opens the app's Play Store listing. Prefers the Play Store app via the `market://` scheme and
 * falls back to the web listing when the store app is unavailable (e.g. emulators, sideloaded
 * builds). This is a plain store-listing deep link, not the In-App Review API, so it always works
 * and is safe to trigger from a button.
 */
private fun openPlayStoreListing(context: Context) {
    val packageName = context.packageName
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$packageName")
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    val opened = runCatching {
        context.startActivity(marketIntent)
        true
    }.getOrDefault(false)
    if (!opened) {
        openUrl(context, "https://play.google.com/store/apps/details?id=$packageName")
    }
}

private fun shareApp(context: Context) {
    val playUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.settings_share_app_text, playUrl))
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, null))
    }.onFailure {
        Toast.makeText(context, R.string.settings_no_app_to_handle, Toast.LENGTH_SHORT).show()
    }
}

private fun sendFeedbackEmail(context: Context, versionName: String, versionCode: Long) {
    val subject = context.getString(R.string.settings_feedback_email_subject, versionName)
    val body = context.getString(
        R.string.settings_feedback_email_body,
        "$versionName ($versionCode)",
        "${Build.MANUFACTURER} ${Build.MODEL}",
        Build.VERSION.RELEASE ?: "?"
    )
    // ACTION_SENDTO with a mailto: URI so only email apps match the chooser.
    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$SUPPORT_EMAIL")
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching {
        context.startActivity(emailIntent)
    }.onFailure {
        Toast.makeText(context, R.string.settings_no_app_to_handle, Toast.LENGTH_SHORT).show()
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, R.string.settings_no_app_to_handle, Toast.LENGTH_SHORT).show()
    }
}
