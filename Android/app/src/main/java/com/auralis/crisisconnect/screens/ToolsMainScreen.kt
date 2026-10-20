package com.auralis.crisisconnect.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBottomBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsMainScreen(navController: NavController) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val tools = remember(appContext) {
        ToolsMainScreenViewModel.getVisibleTools(appContext)
    }
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim()

    val filteredTools = remember(tools, normalizedQuery, context) {
        if (normalizedQuery.isBlank()) {
            tools
        } else {
            tools.filter { tool ->
                val title = context.getString(tool.title)
                val description = context.getString(tool.description)
                val summary = context.getString(toolSummaryRes(tool))
                title.contains(normalizedQuery, ignoreCase = true) ||
                    description.contains(normalizedQuery, ignoreCase = true) ||
                    summary.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    val openTool: (ToolsMainScreenViewModel.ToolItem) -> Unit = { tool ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        focusManager.clearFocus(force = true)
        navController.navigate(tool.route)
    }
    val groupedTools = remember(filteredTools) {
        filteredTools.groupBy { tool -> toolSection(tool.route) }
    }
    val hasNoResults = normalizedQuery.isNotBlank() && filteredTools.isEmpty()
    Scaffold(
        topBar = {
            ToolsTopBar(
                onOpenSettings = { navController.navigate("settings") }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val useQuickTiles = maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.3f
            val columns = when {
                maxWidth < 600.dp -> 1
                maxWidth < 900.dp -> 2
                else -> 3
            }
            val searchFieldHeight = when {
                maxWidth < 360.dp -> 48.dp
                maxWidth < 600.dp -> 50.dp
                else -> 52.dp
            }
            val searchFieldShape = if (maxWidth < 600.dp) 16.dp else 18.dp
            val searchIconSize = if (maxWidth < 360.dp) 18.dp else 20.dp
            val searchTextStyle = when {
                maxWidth < 360.dp -> MaterialTheme.typography.bodySmall
                maxWidth < 840.dp -> MaterialTheme.typography.bodyMedium
                else -> MaterialTheme.typography.bodyLarge
            }
            val searchFieldMaxWidth = when {
                maxWidth < 600.dp -> maxWidth
                maxWidth < 900.dp -> 620.dp
                else -> 700.dp
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "tools_search", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = searchFieldMaxWidth)
                                .heightIn(min = searchFieldHeight),
                            textStyle = searchTextStyle,
                            singleLine = true,
                            maxLines = 1,
                            shape = RoundedCornerShape(searchFieldShape),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.tools_search_placeholder),
                                    style = searchTextStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(searchIconSize)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            focusManager.clearFocus(force = true)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.tools_search_clear),
                                            modifier = Modifier.size(searchIconSize)
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { focusManager.clearFocus(force = true) }
                            )
                        )
                    }
                }

                if (hasNoResults) {
                    item(key = "tools_search_no_results", span = { GridItemSpan(maxLineSpan) }) {
                        NoResultsCard(query = normalizedQuery)
                    }
                }

                if (normalizedQuery.isNotBlank()) {
                    items(
                        items = filteredTools,
                        key = { tool -> tool.route },
                        contentType = { "tool_card" }
                    ) { tool ->
                        ToolRow(tool = tool, onClick = { openTool(tool) })
                    }
                } else {
                    ToolSection.entries.forEach { section ->
                        val sectionTools = groupedTools[section].orEmpty()
                        if (sectionTools.isNotEmpty()) {
                            item(key = "section_${section.name}", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = stringResource(section.titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(top = 16.dp, bottom = 4.dp)
                                        .semantics { heading() }
                                )
                            }
                            if (section == ToolSection.Quick && useQuickTiles) {
                                item(key = "quick_tools", span = { GridItemSpan(maxLineSpan) }) {
                                    Row(
                                        modifier = Modifier.height(IntrinsicSize.Min),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        sectionTools.forEach { tool ->
                                            QuickToolCard(
                                                tool = tool,
                                                onClick = { openTool(tool) },
                                                modifier = Modifier.weight(1f).fillMaxHeight()
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(
                                    items = sectionTools,
                                    key = { tool -> tool.route },
                                    contentType = { "tool_card" }
                                ) { tool ->
                                    ToolRow(tool = tool, onClick = { openTool(tool) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsTopBar(
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
                    text = stringResource(R.string.Tools),
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

@Composable
private fun NoResultsCard(query: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.tools_search_no_results, query),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolRow(
    tool: ToolsMainScreenViewModel.ToolItem,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = onClick
                )
                .heightIn(min = 76.dp)
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                ToolIcon(tool = tool, contentDescription = null)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(tool.title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(toolSummaryRes(tool)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 60.dp, end = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        )
    }
}

private fun toolSummaryRes(tool: ToolsMainScreenViewModel.ToolItem): Int = when (tool.route) {
    "crisis_sentinel" -> R.string.tools_summary_crisis_sentinel
    "breadcrumb_trail" -> R.string.tools_summary_breadcrumb
    "metal_detector" -> R.string.tools_summary_metal_detector
    else -> tool.description
}

@Composable
private fun ToolIcon(
    tool: ToolsMainScreenViewModel.ToolItem,
    contentDescription: String?
) {
    val iconTint = MaterialTheme.colorScheme.onPrimaryContainer
    when {
        tool.drawableIcon != null -> Icon(
            painter = painterResource(id = tool.drawableIcon),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )

        tool.icon != null -> Icon(
            imageVector = tool.icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
    }
}

// Keep every visible tool in exactly one group; unknown future routes remain discoverable.
private enum class ToolSection(val titleRes: Int) {
    Quick(R.string.tools_section_quick),
    Guidance(R.string.tools_section_guidance),
    Navigate(R.string.tools_section_navigate),
    Detect(R.string.tools_section_detect),
    Awareness(R.string.tools_section_awareness),
    Other(R.string.tools_section_other)
}

private fun toolSection(route: String): ToolSection = when (route) {
    "whistle", "flashlight", "cpr_assist" -> ToolSection.Quick
    "crisis_sentinel", "secure_dossiers" -> ToolSection.Guidance
    "offline_map", "breadcrumb_trail", "compass" -> ToolSection.Navigate
    "metal_detector", "signal_finder", "sensor_tool" -> ToolSection.Detect
    "recent_disasters" -> ToolSection.Awareness
    else -> ToolSection.Other
}

@Composable
private fun QuickToolCard(
    tool: ToolsMainScreenViewModel.ToolItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 96.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolIcon(tool = tool, contentDescription = null)
            Text(
                text = stringResource(tool.title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
