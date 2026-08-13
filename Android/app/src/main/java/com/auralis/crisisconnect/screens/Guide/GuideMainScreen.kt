package com.auralis.crisisconnect.screens.Guide

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Guide.GuideMainScreenViewModel.GuideArticle
import com.auralis.crisisconnect.screens.Guide.GuideMainScreenViewModel.GuideCategory
import com.auralis.crisisconnect.service.sos.EmergencyNumberResolver
import com.auralis.crisisconnect.ui.components.AppBottomBar
import java.text.Normalizer
import java.util.Locale

private const val GUIDE_PREFERENCES = "survival_guide_preferences"
private const val CHECKED_ITEMS_KEY = "checked_checklist_items"

private val CheckedItemsSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() }
)

private data class GuideItemEntry(val category: GuideCategory, val article: GuideArticle)
private data class GuideEmergencyContact(val number: String, val service: String)

private enum class GuideMode {
    NOW, PREPARE, AFTER;

    @Composable
    fun title() = when (this) {
        NOW -> stringResource(R.string.guide_redesign_mode_now)
        PREPARE -> stringResource(R.string.guide_redesign_mode_prepare)
        AFTER -> stringResource(R.string.guide_redesign_mode_after)
    }

    @Composable
    fun heading() = when (this) {
        NOW -> stringResource(R.string.guide_redesign_heading_now)
        PREPARE -> stringResource(R.string.guide_redesign_heading_prepare)
        AFTER -> stringResource(R.string.guide_redesign_heading_after)
    }

    @Composable
    fun subtitle() = when (this) {
        NOW -> stringResource(R.string.guide_redesign_subtitle_now)
        PREPARE -> stringResource(R.string.guide_redesign_subtitle_prepare)
        AFTER -> stringResource(R.string.guide_redesign_subtitle_after)
    }

    fun contains(articleId: String) = when (this) {
        PREPARE -> articleId in setOf("G-002", "G-003", "G-004", "E-001")
        AFTER -> articleId in setOf("E-003", "E-004", "MH-001")
        NOW -> articleId in setOf("G-001", "E-002", "F-001", "F-002", "F-003", "W-001", "P-001", "FA-001", "FA-002", "FA-003", "FA-004", "FA-005")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideTopBar(title: String, onBack: (() -> Unit)?, onSettings: (() -> Unit)?) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Column {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp),
                    color = if (dark) Color.White else Color(0xFF042C43),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                onBack?.let { back ->
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                onSettings?.let { open ->
                    IconButton(onClick = open) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.Settings))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f))
    }
}

@Composable
fun GuideMainScreen(navController: NavController) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val categories = remember { GuideMainScreenViewModel.CATEGORIES }
    val entries = remember(categories) {
        categories.flatMap { category -> category.guides.map { GuideItemEntry(category, it) } }
    }
    val regionalEmergency = remember(context) { EmergencyNumberResolver.resolveWithRegion(context) }
    val emergencyContacts = remember(context, locale, regionalEmergency) { emergencyContacts(context, regionalEmergency) }
    val preferences = remember(context) { context.getSharedPreferences(GUIDE_PREFERENCES, Context.MODE_PRIVATE) }

    var selectedModeName by rememberSaveable { mutableStateOf(GuideMode.NOW.name) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var checkedItems by rememberSaveable(stateSaver = CheckedItemsSaver) {
        mutableStateOf(preferences.getStringSet(CHECKED_ITEMS_KEY, emptySet()).orEmpty().toSet())
    }

    val selectedMode = GuideMode.valueOf(selectedModeName)
    val selectedEntry = entries.firstOrNull { it.article.id == selectedArticleId }
    val visibleEntries = remember(entries, selectedMode, searchQuery, locale) {
        val tokens = searchQuery.toSearchTokens(locale)
        if (tokens.isEmpty()) entries.filter { selectedMode.contains(it.article.id) }
        else entries.filter { it.matchesQuery(tokens, locale) }
    }

    LaunchedEffect(checkedItems) {
        preferences.edit().putStringSet(CHECKED_ITEMS_KEY, checkedItems.toSet()).apply()
    }
    BackHandler(enabled = selectedEntry != null) { selectedArticleId = null }

    Scaffold(
        topBar = {
            GuideTopBar(
                title = selectedEntry?.category?.title?.resolve(locale) ?: stringResource(R.string.Guide),
                onBack = selectedEntry?.let { { selectedArticleId = null } },
                onSettings = if (selectedEntry == null) ({ navController.navigate("settings") }) else null
            )
        },
        bottomBar = { if (selectedEntry == null) AppBottomBar(navController) }
    ) { padding ->
        if (selectedEntry == null) {
            GuideHome(
                modifier = Modifier.fillMaxSize().padding(padding),
                locale = locale,
                entries = entries,
                visibleEntries = visibleEntries,
                selectedMode = selectedMode,
                onModeChange = { selectedModeName = it.name; searchQuery = "" },
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                regionalEmergency = regionalEmergency,
                emergencyContacts = emergencyContacts,
                checkedItems = checkedItems,
                onOpen = { selectedArticleId = it.article.id },
                onCall = { dialEmergency(context, it) },
                onOfficialAssembly = { openOfficialAssemblyLookup(context) }
            )
        } else {
            GuideFocus(
                modifier = Modifier.fillMaxSize().padding(padding),
                locale = locale,
                entry = selectedEntry,
                emergencyContacts = emergencyContacts,
                checkedItems = checkedItems,
                onToggle = { index -> checkedItems = checkedItems.toggle("${selectedEntry.article.id}#$index") },
                onCall = { dialEmergency(context, it) }
            )
        }
    }
}

@Composable
private fun GuideHome(
    modifier: Modifier,
    locale: Locale,
    entries: List<GuideItemEntry>,
    visibleEntries: List<GuideItemEntry>,
    selectedMode: GuideMode,
    onModeChange: (GuideMode) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    regionalEmergency: EmergencyNumberResolver.RegionalEmergencyNumber,
    emergencyContacts: List<GuideEmergencyContact>,
    checkedItems: Set<String>,
    onOpen: (GuideItemEntry) -> Unit,
    onCall: (String) -> Unit,
    onOfficialAssembly: () -> Unit
) {
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { OfflineStatus(locale, regionalEmergency.countryIso) }
        item { ModeSelector(selectedMode, onModeChange) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(selectedMode.heading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(selectedMode.subtitle(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selectedMode == GuideMode.NOW) {
            item { EmergencyPanel(emergencyContacts, hasVerifiedGuideEmergencyRegion(regionalEmergency.countryIso), onCall) }
            item { HazardGrid(entries, onOpen) }
        }
        if (selectedMode == GuideMode.PREPARE) {
            item { PreparationProgress(entries, checkedItems) }
            if (regionalEmergency.countryIso.equals("TR", true)) {
                item { OfficialAssemblyCard(onOfficialAssembly) }
            }
        }
        item { SearchField(searchQuery, onSearchChange) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (searchQuery.isBlank()) selectedMode.title() else stringResource(R.string.guide_redesign_search_results),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                Text(visibleEntries.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (visibleEntries.isEmpty()) item { EmptyState() }
        else items(visibleEntries, key = { it.article.id }) { entry ->
            CompactGuideRow(locale, entry, checkedItems) { onOpen(entry) }
        }
    }
}

@Composable
private fun OfflineStatus(locale: Locale, countryIso: String?) {
    val country = countryIso?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(locale).ifBlank { it } }
        ?: stringResource(R.string.guide_redesign_unknown_region)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.guide_redesign_offline_available), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(country, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeSelector(selected: GuideMode, onSelect: (GuideMode) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GuideMode.entries.forEach { mode ->
                val active = selected == mode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(mode) }),
                    shape = RoundedCornerShape(11.dp),
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    tonalElevation = if (active) 2.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            mode.title(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmergencyPanel(
    contacts: List<GuideEmergencyContact>,
    regionVerified: Boolean,
    onCall: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.error)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.guide_redesign_immediate_danger), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.guide_redesign_emergency_instruction), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            contacts.forEach { contact ->
                Button(
                    onClick = { onCall(contact.number) },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(R.string.guide_screen_action_call_local, contact.number), fontWeight = FontWeight.Bold)
                        Text(contact.service, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (!regionVerified) {
                Text(
                    stringResource(R.string.guide_redesign_region_unverified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun HazardGrid(entries: List<GuideItemEntry>, onOpen: (GuideItemEntry) -> Unit) {
    val hazards = listOf(
        Triple("E-002", stringResource(R.string.guide_redesign_hazard_earthquake), Icons.Default.Place),
        Triple("F-001", stringResource(R.string.guide_redesign_hazard_fire_smoke), Icons.Default.Warning),
        Triple("FA-002", stringResource(R.string.guide_redesign_hazard_severe_bleeding), Icons.Default.Warning),
        Triple("FA-005", stringResource(R.string.guide_redesign_hazard_cannot_breathe), Icons.Default.Call),
        Triple("W-001", stringResource(R.string.guide_redesign_hazard_flood), Icons.Default.Place),
        Triple("P-001", stringResource(R.string.guide_redesign_hazard_poisoning_co), Icons.Default.Warning)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.guide_redesign_choose_situation), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        hazards.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { index, hazard ->
                    val entry = entries.firstOrNull { it.article.id == hazard.first }
                    val tint = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Surface(
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 76.dp).clickable(enabled = entry != null) { entry?.let(onOpen) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(hazard.third, contentDescription = null, tint = tint)
                            Text(hazard.second, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreparationProgress(entries: List<GuideItemEntry>, checkedItems: Set<String>) {
    val preparation = entries.filter { GuideMode.PREPARE.contains(it.article.id) }
    val total = preparation.sumOf { it.article.checklist.size }
    val completed = preparation.sumOf { entry ->
        entry.article.checklist.indices.count { index -> checkedItems.contains("${entry.article.id}#$index") }
    }
    val progress = if (total == 0) 0f else completed.toFloat() / total.toFloat()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.guide_redesign_readiness), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$completed/$total", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OfficialAssemblyCard(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.guide_screen_assembly_query), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.guide_redesign_official_assembly_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.guide_screen_action_open_assembly))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(start = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                if (query.isBlank()) Text(stringResource(R.string.guide_screen_search_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.guide_screen_clear_search))
                }
            }
        }
    }
}

@Composable
private fun CompactGuideRow(locale: Locale, entry: GuideItemEntry, checkedItems: Set<String>, onClick: () -> Unit) {
    val completed = entry.article.checklist.indices.count { checkedItems.contains("${entry.article.id}#$it") }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.article.title.resolve(locale), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    entry.category.title.resolve(locale) + if (completed > 0) " • $completed/${entry.article.checklist.size}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Search, contentDescription = null)
        Text(stringResource(R.string.guide_screen_empty_title), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.guide_screen_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuideFocus(
    modifier: Modifier,
    locale: Locale,
    entry: GuideItemEntry,
    emergencyContacts: List<GuideEmergencyContact>,
    checkedItems: Set<String>,
    onToggle: (Int) -> Unit,
    onCall: (String) -> Unit
) {
    val article = entry.article
    val completed = article.checklist.indices.count { checkedItems.contains("${article.id}#$it") }
    val progress = if (article.checklist.isEmpty()) 0f else completed.toFloat() / article.checklist.size.toFloat()
    val emergencyText: (String) -> String = { it.withEmergencyContacts(emergencyContacts) }

    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.category.title.resolve(locale), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(emergencyText(article.title.resolve(locale)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(
                    "${stringResource(R.string.guide_redesign_offline_available)} • ${stringResource(R.string.guide_screen_read_minutes_format, article.readMinutes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.guide_screen_section_30_seconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    article.in30Seconds.take(3).forEachIndexed { index, item ->
                        NumberedAction(index + 1, emergencyText(item.resolve(locale)))
                    }
                }
            }
        }
        if (GuideMode.NOW.contains(article.id)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.guide_redesign_emergency_help), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    emergencyContacts.forEach { contact ->
                        Button(onClick = { onCall(contact.number) }, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Call, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("${contact.number} • ${contact.service}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (article.stepByStep.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.guide_screen_section_step_by_step), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    article.stepByStep.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(emergencyText(item.resolve(locale)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (article.dontDo.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.22f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.guide_screen_section_dont), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { heading() })
                        }
                        article.dontDo.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                Text(emergencyText(item.resolve(locale)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        if (article.checklist.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.guide_screen_section_checklist), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                                Text("$completed/${article.checklist.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        article.checklist.forEachIndexed { index, item ->
                            val checked = checkedItems.contains("${article.id}#$index")
                            Row(
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(index) }),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    emergencyText(item.resolve(locale)),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.guide_redesign_source_verification), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(
                        if (article.id == "G-001") emergencyContacts.joinToString(" • ") { "${it.service}: ${it.number}" }
                        else article.sourceNote?.resolve(locale)?.let(emergencyText).orEmpty(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.guide_redesign_reviewer_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberedAction(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).defaultMinSize(minHeight = 32.dp))
    }
}

private fun emergencyContacts(context: Context, regional: EmergencyNumberResolver.RegionalEmergencyNumber): List<GuideEmergencyContact> {
    val all = context.getString(R.string.guide_redesign_service_all)
    val medicalFire = context.getString(R.string.guide_redesign_service_fire_medical)
    val medical = context.getString(R.string.guide_redesign_service_medical)
    val fire = context.getString(R.string.guide_redesign_service_fire)
    val police = context.getString(R.string.guide_redesign_service_police)
    return when (regional.countryIso?.uppercase(Locale.US)) {
        "TR" -> listOf(GuideEmergencyContact("112", all))
        "US", "CA", "MX" -> listOf(GuideEmergencyContact("911", all))
        "GB" -> listOf(GuideEmergencyContact("999", all))
        "IE" -> listOf(GuideEmergencyContact("112", all))
        "AU" -> listOf(GuideEmergencyContact("000", all))
        "NZ" -> listOf(GuideEmergencyContact("111", all))
        "JP" -> listOf(GuideEmergencyContact("119", medicalFire), GuideEmergencyContact("110", police))
        "KR" -> listOf(GuideEmergencyContact("119", medicalFire), GuideEmergencyContact("112", police))
        "CN" -> listOf(GuideEmergencyContact("120", medical), GuideEmergencyContact("119", fire), GuideEmergencyContact("110", police))
        "BR" -> listOf(GuideEmergencyContact("192", medical), GuideEmergencyContact("193", fire), GuideEmergencyContact("190", police))
        else -> listOf(GuideEmergencyContact(regional.number, context.getString(R.string.guide_redesign_service_regional)))
    }
}

private fun hasVerifiedGuideEmergencyRegion(countryIso: String?): Boolean =
    countryIso?.uppercase(Locale.US) in setOf("TR", "US", "CA", "MX", "GB", "IE", "AU", "NZ", "JP", "KR", "CN", "BR")

private fun Set<String>.toggle(key: String): Set<String> = if (contains(key)) this - key else this + key

private fun String.withEmergencyContacts(contacts: List<GuideEmergencyContact>): String {
    val primary = contacts.firstOrNull()?.number ?: return this
    val contactNumbers = contacts.map { it.number }.toSet()
    var output = this
    listOf("112", "911", "999", "000", "111").forEach { generic ->
        if (generic !in contactNumbers) output = output.replace(Regex("\\b$generic\\b"), primary)
    }
    return output
}

private fun String.toSearchTokens(locale: Locale): List<String> = trim()
    .split(Regex("\\s+"))
    .map { it.toSearchKey(locale) }
    .filter { it.isNotBlank() }
    .distinct()

private fun String.toSearchKey(locale: Locale): String {
    val normalized = Normalizer.normalize(lowercase(locale), Normalizer.Form.NFD)
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

private fun GuideItemEntry.matchesQuery(tokens: List<String>, locale: Locale): Boolean {
    val haystack = buildString {
        append(article.title.resolve(locale)).append(' ')
        append(article.priority.resolve(locale)).append(' ')
        append(category.title.resolve(locale)).append(' ')
        append(category.description.resolve(locale)).append(' ')
        article.in30Seconds.forEach { append(it.resolve(locale)).append(' ') }
        article.stepByStep.forEach { append(it.resolve(locale)).append(' ') }
        article.dontDo.forEach { append(it.resolve(locale)).append(' ') }
        article.checklist.forEach { append(it.resolve(locale)).append(' ') }
    }.toSearchKey(locale)
    return tokens.all { haystack.contains(it) }
}

private fun dialEmergency(context: Context, number: String) {
    if (!context.tryStartActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))) {
        Toast.makeText(context, context.getString(R.string.guide_screen_no_dial_app_with_number, number), Toast.LENGTH_SHORT).show()
    }
}

private fun openOfficialAssemblyLookup(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.turkiye.gov.tr/afet-ve-acil-durum-toplanma-alani-sorgulama"))
    if (!context.tryStartActivity(intent)) Toast.makeText(context, context.getString(R.string.guide_screen_no_map_app), Toast.LENGTH_SHORT).show()
}

private fun Context.tryStartActivity(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
