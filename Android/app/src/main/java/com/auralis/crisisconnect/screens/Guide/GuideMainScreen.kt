package com.auralis.crisisconnect.screens.Guide

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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

private const val ALL_CATEGORIES_ID = "all"
private val CheckedChecklistItemsSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() }
)

private data class GuideItemEntry(
    val category: GuideCategory,
    val article: GuideArticle
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideTopBar(
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
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.Guide),
                    style = titleStyle,
                    color = if (isDarkTheme) Color.White else Color(0xFF042C43),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideMainScreen(navController: NavController) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val categories = remember { GuideMainScreenViewModel.CATEGORIES }
    val emergencyInfo = remember(context) { EmergencyNumberResolver.resolveWithRegion(context) }
    val listState = rememberLazyListState()

    var selectedCategoryId by rememberSaveable { mutableStateOf(ALL_CATEGORIES_ID) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedGuideId by rememberSaveable { mutableStateOf<String?>(null) }
    var checkedChecklistItems by rememberSaveable(stateSaver = CheckedChecklistItemsSaver) {
        mutableStateOf(emptySet<String>())
    }
    val hasActiveFilters by remember(selectedCategoryId, searchQuery) {
        derivedStateOf {
            selectedCategoryId != ALL_CATEGORIES_ID || searchQuery.isNotBlank()
        }
    }
    val stickyHeaderElevated by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    val visibleGuides = remember(categories, selectedCategoryId, searchQuery, locale) {
        val selectedCategories = if (selectedCategoryId == ALL_CATEGORIES_ID) {
            categories
        } else {
            categories.filter { category -> category.id == selectedCategoryId }
        }

        val allEntries = selectedCategories.flatMap { category ->
            category.guides.map { article ->
                GuideItemEntry(category = category, article = article)
            }
        }

        val queryTokens = searchQuery.toSearchTokens(locale)
        if (queryTokens.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry -> entry.matchesQuery(queryTokens, locale) }
        }
    }

    LaunchedEffect(visibleGuides, expandedGuideId) {
        if (expandedGuideId != null && visibleGuides.none { it.article.id == expandedGuideId }) {
            expandedGuideId = null
        }
    }

    Scaffold(
        topBar = {
            GuideTopBar(
                onOpenSettings = { navController.navigate("settings") }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            GuideStickyFiltersPanel(
                locale = locale,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                onSelectCategory = { selectedCategoryId = it },
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                visibleGuideCount = visibleGuides.size,
                hasActiveFilters = hasActiveFilters,
                onClearFilters = {
                    searchQuery = ""
                    selectedCategoryId = ALL_CATEGORIES_ID
                    expandedGuideId = null
                },
                elevated = stickyHeaderElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    GuideIntroPanel(
                        locale = locale,
                        emergencyInfo = emergencyInfo,
                        onCallEmergency = {
                            dialEmergency(
                                context = context,
                                number = emergencyInfo.number
                            )
                        },
                        onOpenAssemblyArea = { openAssemblyAreaMap(context) }
                    )
                }

                if (visibleGuides.isEmpty()) {
                    item {
                        EmptyGuidesState()
                    }
                } else {
                    items(
                        items = visibleGuides,
                        key = { entry -> entry.article.id },
                        contentType = { "guide_card" }
                    ) { entry ->
                        val isExpanded = expandedGuideId == entry.article.id
                        GuideArticleCard(
                            locale = locale,
                            entry = entry,
                            isExpanded = isExpanded,
                            checkedChecklistItems = checkedChecklistItems,
                            onToggleExpanded = {
                                expandedGuideId = if (isExpanded) null else entry.article.id
                            },
                            onStartChecklist = {
                                expandedGuideId = entry.article.id
                                checkedChecklistItems = checkedChecklistItems.filterNot { key ->
                                    key.startsWith("${entry.article.id}#")
                                }.toSet()
                            },
                            onToggleChecklist = { index ->
                                val key = "${entry.article.id}#$index"
                                checkedChecklistItems = if (checkedChecklistItems.contains(key)) {
                                    checkedChecklistItems - key
                                } else {
                                    checkedChecklistItems + key
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideIntroPanel(
    locale: Locale,
    emergencyInfo: EmergencyNumberResolver.RegionalEmergencyNumber,
    onCallEmergency: () -> Unit,
    onOpenAssemblyArea: () -> Unit
) {
    val unknownCountryLabel = stringResource(R.string.guide_country_unknown)
    val countryLabel = remember(locale, emergencyInfo.countryIso) {
        emergencyInfo.countryIso
            ?.let { iso -> Locale("", iso).getDisplayCountry(locale).ifBlank { iso } }
            ?: unknownCountryLabel
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.guide_screen_intro_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.guide_screen_intro_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(
                    R.string.guide_screen_region_value,
                    countryLabel,
                    emergencyInfo.number
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCallEmergency,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.guide_screen_action_call_local, emergencyInfo.number),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onOpenAssemblyArea,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.guide_screen_action_open_assembly),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStickyFiltersPanel(
    locale: Locale,
    categories: List<GuideCategory>,
    selectedCategoryId: String,
    onSelectCategory: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    visibleGuideCount: Int,
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit,
    elevated: Boolean,
    modifier: Modifier = Modifier
) {
    val panelElevation by animateDpAsState(
        targetValue = if (elevated) 6.dp else 0.dp,
        label = "guide_sticky_panel_elevation"
    )
    val panelColor by animateColorAsState(
        targetValue = if (elevated) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        },
        label = "guide_sticky_panel_color"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            shape = RoundedCornerShape(18.dp),
            color = panelColor,
            tonalElevation = panelElevation,
            shadowElevation = panelElevation,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompactSearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth()
                )

                CategoryFilters(
                    locale = locale,
                    categories = categories,
                    selectedCategoryId = selectedCategoryId,
                    onSelectCategory = onSelectCategory
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.guide_screen_results_count, visibleGuideCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AnimatedVisibility(
                        visible = hasActiveFilters,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        TextButton(onClick = onClearFilters) {
                            Text(
                                text = stringResource(R.string.guide_screen_clear_filters),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilters(
    locale: Locale,
    categories: List<GuideCategory>,
    selectedCategoryId: String,
    onSelectCategory: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CategoryFilterTab(
            label = stringResource(R.string.guide_screen_filter_all),
            selected = selectedCategoryId == ALL_CATEGORIES_ID,
            onClick = { onSelectCategory(ALL_CATEGORIES_ID) }
        )

        categories.forEach { category ->
            CategoryFilterTab(
                label = category.title.resolve(locale),
                selected = selectedCategoryId == category.id,
                onClick = { onSelectCategory(category.id) }
            )
        }
    }
}

@Composable
private fun CategoryFilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
        },
        label = "guide_category_tab_container"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        },
        label = "guide_category_tab_border"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "guide_category_tab_label"
    )

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(modifier = Modifier.weight(1f)) {
                if (query.isBlank()) {
                    Text(
                        text = stringResource(R.string.guide_screen_search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }

            if (query.isNotBlank()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.guide_screen_clear_search),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyGuidesState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.guide_screen_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.guide_screen_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GuideArticleCard(
    locale: Locale,
    entry: GuideItemEntry,
    isExpanded: Boolean,
    checkedChecklistItems: Set<String>,
    onToggleExpanded: () -> Unit,
    onStartChecklist: () -> Unit,
    onToggleChecklist: (Int) -> Unit
) {
    val article = entry.article
    val category = entry.category
    val context = LocalContext.current
    val view = LocalView.current
    val quickActions = if (isExpanded) article.in30Seconds else article.in30Seconds.take(2)
    val hiddenQuickActionCount = article.in30Seconds.size - quickActions.size
    val metadata = remember(article, category, context, locale) {
        "${article.id} • ${category.title.resolve(locale)} • ${readDurationLabel(context, article.readMinutes)} • ${article.priority.resolve(locale)}"
    }
    val expandIconAnimationSpec = remember {
        spring<Float>(
            dampingRatio = 0.92f,
            stiffness = Spring.StiffnessMedium
        )
    }
    val toggleExpandedWithFeedback = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onToggleExpanded()
    }
    val expandIconRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = expandIconAnimationSpec,
        label = "guide_expand_icon_rotation"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMedium
                )
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = toggleExpandedWithFeedback),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = article.title.resolve(locale),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) {
                        stringResource(R.string.guide_screen_collapse_details)
                    } else {
                        stringResource(R.string.guide_screen_expand_details)
                    },
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(20.dp)
                        .rotate(expandIconRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            GuideSection(
                title = stringResource(R.string.guide_screen_section_30_seconds),
                items = quickActions.map { it.resolve(locale) },
                warning = false
            )

            if (!isExpanded && hiddenQuickActionCount > 0) {
                Text(
                    text = stringResource(R.string.guide_screen_more_quick_steps, hiddenQuickActionCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GuideSection(
                        title = stringResource(R.string.guide_screen_section_step_by_step),
                        items = article.stepByStep.map { it.resolve(locale) },
                        warning = false
                    )

                    GuideSection(
                        title = stringResource(R.string.guide_screen_section_dont),
                        items = article.dontDo.map { it.resolve(locale) },
                        warning = true
                    )

                    GuideChecklistSection(
                        title = stringResource(R.string.guide_screen_section_checklist),
                        article = article,
                        locale = locale,
                        checkedChecklistItems = checkedChecklistItems,
                        onToggleChecklist = onToggleChecklist
                    )

                    article.sourceNote?.let { sourceNote ->
                        Text(
                            text = "${stringResource(R.string.guide_screen_source_note)} ${sourceNote.resolve(locale)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (article.checklist.isNotEmpty()) {
                OutlinedButton(
                    onClick = onStartChecklist,
                    modifier = Modifier.defaultMinSize(minHeight = 38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.guide_screen_action_start_checklist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideSection(
    title: String,
    items: List<String>,
    warning: Boolean
) {
    if (items.isEmpty()) return

    val titleColor = if (warning) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val textColor = if (warning) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bulletColor = if (warning) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val sectionBody: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )

            items.forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .background(color = bulletColor, shape = CircleShape)
                    )
                    Text(
                        text = line,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor
                    )
                }
            }
        }
    }

    if (warning) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
            )
        ) {
            sectionBody(Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    } else {
        sectionBody(Modifier)
    }
}

@Composable
private fun GuideChecklistSection(
    title: String,
    article: GuideArticle,
    locale: Locale,
    checkedChecklistItems: Set<String>,
    onToggleChecklist: (Int) -> Unit
) {
    val completedCount = remember(article.id, article.checklist.size, checkedChecklistItems) {
        article.checklist.indices.count { index ->
            checkedChecklistItems.contains("${article.id}#$index")
        }
    }
    val totalCount = article.checklist.size
    val progress = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount.toFloat()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.guide_screen_checklist_progress,
                        completedCount,
                        totalCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )

            article.checklist.forEachIndexed { index, item ->
                val key = "${article.id}#$index"
                val isChecked = checkedChecklistItems.contains(key)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleChecklist(index) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onToggleChecklist(index) }
                    )
                    Text(
                        text = item.resolve(locale),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isChecked) FontWeight.Medium else FontWeight.Normal,
                        color = if (isChecked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (isChecked) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    )
                }
            }
        }
    }
}

private fun readDurationLabel(context: Context, minutes: Int): String {
    return context.getString(R.string.guide_screen_read_minutes_format, minutes)
}

private fun String.toSearchTokens(locale: Locale): List<String> {
    return trim()
        .split(Regex("\\s+"))
        .map { token -> token.toSearchKey(locale) }
        .filter { token -> token.isNotBlank() }
        .distinct()
}

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

private fun GuideItemEntry.matchesQuery(queryTokens: List<String>, locale: Locale): Boolean {
    if (queryTokens.isEmpty()) return true

    val haystack = buildString {
        append(article.id)
        append(' ')
        append(article.id.filter { it.isLetterOrDigit() })
        append(' ')
        append(article.title.resolve(locale))
        append(' ')
        append(article.priority.resolve(locale))
        append(' ')
        append(category.title.resolve(locale))
        append(' ')
        append(category.description.resolve(locale))
        append(' ')
        article.in30Seconds.forEach { append(it.resolve(locale)).append(' ') }
        article.stepByStep.forEach { append(it.resolve(locale)).append(' ') }
        article.dontDo.forEach { append(it.resolve(locale)).append(' ') }
        article.checklist.forEach { append(it.resolve(locale)).append(' ') }
    }.toSearchKey(locale)

    return queryTokens.all { token -> haystack.contains(token) }
}

private fun dialEmergency(context: Context, number: String) {
    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
    if (!context.tryStartActivity(dialIntent)) {
        Toast.makeText(
            context,
            context.getString(R.string.guide_screen_no_dial_app_with_number, number),
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun openAssemblyAreaMap(context: Context) {
    val query = Uri.encode(context.getString(R.string.guide_screen_assembly_query))
    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query"))
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")
    )

    if (context.tryStartActivity(geoIntent) || context.tryStartActivity(webIntent)) {
        return
    }

    Toast.makeText(
        context,
        context.getString(R.string.guide_screen_no_map_app),
        Toast.LENGTH_SHORT
    ).show()
}

private fun Context.tryStartActivity(intent: Intent): Boolean {
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
