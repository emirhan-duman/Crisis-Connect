package com.auralis.crisisconnect.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                title.contains(normalizedQuery, ignoreCase = true) ||
                    description.contains(normalizedQuery, ignoreCase = true)
            }
        }
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
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                .heightIn(min = searchFieldHeight, max = searchFieldHeight)
                                .padding(horizontal = 2.dp),
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

                items(
                    items = filteredTools,
                    key = { tool -> tool.title },
                    contentType = { "tool_card" }
                ) { tool ->
                    ToolCard(
                        tool = tool,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            focusManager.clearFocus(force = true)
                            navController.navigate(tool.route)
                        }
                    )
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
private fun ToolCard(
    tool: ToolsMainScreenViewModel.ToolItem,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                ToolIcon(tool = tool, contentDescription = null)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(tool.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(tool.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
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
            tint = iconTint
        )

        tool.icon != null -> Icon(
            imageVector = tool.icon,
            contentDescription = contentDescription,
            tint = iconTint
        )
    }
}
