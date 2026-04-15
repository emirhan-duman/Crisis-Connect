package com.auralis.crisisconnect.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.auralis.crisisconnect.R
import kotlinx.coroutines.delay

/**
 * Icons used by this menu come from Material Icons Extended:
 * `implementation("androidx.compose.material:material-icons-extended:<version>")`
 */

enum class AttachmentAction(@StringRes val labelRes: Int) {
    Document(R.string.chat_attachment_document),
    Camera(R.string.chat_attachment_camera),
    Gallery(R.string.chat_attachment_gallery),
    Location(R.string.chat_attachment_location)
}

@Composable
fun WhatsAppAttachmentMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAction: (AttachmentAction) -> Unit,
    actions: List<AttachmentAction> = AttachmentAction.entries,
    bottomPadding: Dp = 24.dp
) {
    val overlayState = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) {
        overlayState.targetState = visible
    }

    val visibleActions = remember(actions) { actions.distinct() }
    val itemVisibilities = remember(visibleActions) {
        mutableStateListOf<Boolean>().apply { repeat(visibleActions.size) { add(false) } }
    }

    LaunchedEffect(visible) {
        if (visible) {
            visibleActions.indices.forEach { index ->
                itemVisibilities[index] = false
            }
            visibleActions.indices.forEach { index ->
                delay(index * 60L)
                itemVisibilities[index] = true
            }
        } else {
            visibleActions.indices.forEach { index ->
                itemVisibilities[index] = false
            }
        }
    }

    BackHandler(enabled = overlayState.currentState || overlayState.targetState) {
        onDismiss()
    }

    if (overlayState.currentState || overlayState.targetState) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = overlayState,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val scrimInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = scrimInteraction,
                            indication = null
                        ) { onDismiss() }
                )
            }

            AnimatedVisibility(
                modifier = Modifier.align(Alignment.BottomCenter),
                visibleState = overlayState,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                AttachmentMenuContent(
                    actions = visibleActions,
                    itemVisibilities = itemVisibilities,
                    onAction = onAction,
                    onDismiss = onDismiss,
                    modifier = Modifier.padding(bottom = bottomPadding)
                )
            }
        }
    }
}

@Composable
private fun AttachmentMenuContent(
    actions: List<AttachmentAction>,
    itemVisibilities: List<Boolean>,
    onAction: (AttachmentAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 56.dp
) {
    val columns = 3
    val rows = 2
    val cells = rows * columns
    val itemsWithPlaceholders: List<AttachmentAction?> = remember(actions) {
        buildList(cells) {
            addAll(actions)
            while (size < cells) {
                add(null)
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            for (row in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    for (column in 0 until columns) {
                        val cellIndex = row * columns + column
                        val action = itemsWithPlaceholders[cellIndex]
                        Box(
                            modifier = Modifier
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (action != null) {
                                val actionIndex = actions.indexOf(action)
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = itemVisibilities.getOrNull(actionIndex) == true,
                                    enter = scaleIn(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        ),
                                        initialScale = 0.6f
                                    ) + fadeIn(),
                                    exit = scaleOut() + fadeOut()
                                ) {
                                    AttachmentMenuItem(
                                        action = action,
                                        size = buttonSize,
                                        onClick = {
                                            onAction(action)
                                            onDismiss()
                                        }
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.size(buttonSize))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentMenuItem(
    action: AttachmentAction,
    size: Dp,
    onClick: () -> Unit
) {
    val label = stringResource(id = action.labelRes)
    val (icon, containerColor) = when (action) {
        AttachmentAction.Document -> Icons.Filled.Description to MaterialTheme.colorScheme.primary
        AttachmentAction.Camera -> Icons.Filled.CameraAlt to MaterialTheme.colorScheme.tertiary
        AttachmentAction.Gallery -> Icons.Filled.Photo to MaterialTheme.colorScheme.secondary
        AttachmentAction.Location -> Icons.Filled.LocationOn to MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun AttachmentMenuExample() {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Filled.Photo,
                contentDescription = stringResource(id = R.string.chat_add_attachment)
            )
        }
        WhatsAppAttachmentMenu(
            visible = showMenu,
            onDismiss = { showMenu = false },
            onAction = { showMenu = false }
        )
    }
}
