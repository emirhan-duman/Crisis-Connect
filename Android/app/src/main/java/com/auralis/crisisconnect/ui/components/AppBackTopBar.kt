package com.auralis.crisisconnect.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.crisisconnect.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackTopBar(
    @StringRes titleRes: Int,
    onNavigateBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    AppBackTopBar(
        title = stringResource(titleRes),
        onNavigateBack = onNavigateBack,
        actions = actions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    val scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.sp
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    style = titleStyle,
                    color = if (isDarkTheme) Color.White else Color(0xFF042C43),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
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
