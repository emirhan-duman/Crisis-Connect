package com.auralis.crisisconnect.screens.Tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ai.CrisisSentinelEngine
import com.auralis.crisisconnect.ai.CrisisSentinelOnlineModelChoice
import com.auralis.crisisconnect.ai.CrisisSentinelOnlineProvider

/**
 * Compact pill for the chat top bar showing the active engine; tapping opens
 * [CrisisSentinelEnginePickerSheet]. Only rendered for field-team users — public users have a
 * single (Edge) engine and no choice to make.
 */
@Composable
fun CrisisSentinelEngineChip(
    selectedEngine: CrisisSentinelEngine,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedModelLabel: String? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = engineIcon(selectedEngine),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (selectedEngine == CrisisSentinelEngine.Online &&
                    !selectedModelLabel.isNullOrBlank()
                ) {
                    selectedModelLabel
                } else {
                    stringResource(
                        when (selectedEngine) {
                            CrisisSentinelEngine.Online ->
                                R.string.crisis_sentinel_engine_online_chip
                            CrisisSentinelEngine.Edge ->
                                R.string.crisis_sentinel_engine_edge_chip
                        }
                    )
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp)
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.crisis_sentinel_engine_picker_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrisisSentinelEnginePickerSheet(
    selectedEngine: CrisisSentinelEngine,
    onlineAvailable: Boolean,
    edgeAvailable: Boolean,
    onSelect: (CrisisSentinelEngine) -> Unit,
    onDismiss: () -> Unit,
    providers: List<CrisisSentinelOnlineProvider> = emptyList(),
    isLoadingProviders: Boolean = false,
    providersLoadFailed: Boolean = false,
    selectedModel: CrisisSentinelOnlineModelChoice? = null,
    onSelectModel: (CrisisSentinelOnlineModelChoice?) -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.crisis_sentinel_engine_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            EngineOptionRow(
                engine = CrisisSentinelEngine.Online,
                titleRes = R.string.crisis_sentinel_engine_online,
                subtitleRes = R.string.crisis_sentinel_engine_online_subtitle,
                unavailableReasonRes = R.string.crisis_sentinel_engine_online_unavailable_offline,
                isSelected = selectedEngine == CrisisSentinelEngine.Online,
                isAvailable = onlineAvailable,
                // Selecting an engine keeps the sheet open when models can be picked beneath.
                onSelect = { onSelect(CrisisSentinelEngine.Online) }
            )
            if (onlineAvailable && selectedEngine == CrisisSentinelEngine.Online) {
                OnlineModelSection(
                    providers = providers,
                    isLoading = isLoadingProviders,
                    loadFailed = providersLoadFailed,
                    selectedModel = selectedModel,
                    onSelectModel = onSelectModel
                )
            }
            EngineOptionRow(
                engine = CrisisSentinelEngine.Edge,
                titleRes = R.string.crisis_sentinel_engine_edge,
                subtitleRes = R.string.crisis_sentinel_engine_edge_subtitle,
                unavailableReasonRes = R.string.crisis_sentinel_engine_edge_unavailable_missing,
                isSelected = selectedEngine == CrisisSentinelEngine.Edge,
                isAvailable = edgeAvailable,
                onSelect = {
                    onSelect(CrisisSentinelEngine.Edge)
                    onDismiss()
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Web ModelPicker analog: provider-grouped model list beneath the Online engine row. */
@Composable
private fun OnlineModelSection(
    providers: List<CrisisSentinelOnlineProvider>,
    isLoading: Boolean,
    loadFailed: Boolean,
    selectedModel: CrisisSentinelOnlineModelChoice?,
    onSelectModel: (CrisisSentinelOnlineModelChoice?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.crisis_sentinel_model_picker_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        when {
            isLoading && providers.isEmpty() -> {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = stringResource(R.string.crisis_sentinel_model_picker_section),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            loadFailed && providers.isEmpty() -> {
                Text(
                    text = stringResource(R.string.crisis_sentinel_model_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            else -> {
                ModelOptionRow(
                    label = stringResource(R.string.crisis_sentinel_model_default),
                    contextWindow = null,
                    isSelected = selectedModel == null,
                    onClick = { onSelectModel(null) }
                )
                providers.forEach { provider ->
                    Text(
                        text = provider.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    provider.models.forEach { model ->
                        ModelOptionRow(
                            label = model.label,
                            contextWindow = model.contextWindow,
                            isSelected = selectedModel?.providerId == provider.id &&
                                selectedModel.modelId == model.id,
                            onClick = {
                                onSelectModel(
                                    CrisisSentinelOnlineModelChoice(
                                        providerId = provider.id,
                                        modelId = model.id,
                                        modelLabel = model.label
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelOptionRow(
    label: String,
    contextWindow: Long?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            formatContextWindow(contextWindow)?.let { window ->
                Text(
                    text = window,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Web parity: ≥1M → "1M"/"1.5M", ≥1000 → "200K", else raw. */
private fun formatContextWindow(contextWindow: Long?): String? {
    contextWindow ?: return null
    return when {
        contextWindow >= 1_000_000L -> {
            val millions = contextWindow / 1_000_000.0
            if (millions % 1.0 == 0.0) "${millions.toInt()}M" else "${millions}M"
        }

        contextWindow >= 1_000L -> "${contextWindow / 1_000L}K"
        else -> contextWindow.toString()
    }
}

@Composable
private fun EngineOptionRow(
    engine: CrisisSentinelEngine,
    titleRes: Int,
    subtitleRes: Int,
    unavailableReasonRes: Int,
    isSelected: Boolean,
    isAvailable: Boolean,
    onSelect: () -> Unit
) {
    val contentAlpha = if (isAvailable) 1f else 0.45f
    Surface(
        onClick = onSelect,
        enabled = isAvailable,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected && isAvailable) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = engineIcon(engine),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                modifier = Modifier.size(26.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
                if (!isAvailable) {
                    Text(
                        text = stringResource(unavailableReasonRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (isSelected && isAvailable) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun engineIcon(engine: CrisisSentinelEngine): ImageVector = when (engine) {
    CrisisSentinelEngine.Online -> Icons.Filled.Cloud
    CrisisSentinelEngine.Edge -> Icons.Filled.Smartphone
}
