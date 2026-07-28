package com.auralis.crisisconnect.screens.Chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.auralis.crisisconnect.ui.theme.IncomingBubbleBlueLight
import com.auralis.crisisconnect.ui.theme.MidnightBlue
import com.auralis.crisisconnect.ui.theme.OutgoingBubbleGrayLight

@Composable
fun incomingChatBubbleColors(): Pair<Color, Color> {
    val colorScheme = MaterialTheme.colorScheme
    return if (colorScheme.surface.luminance() > 0.5f) {
        IncomingBubbleBlueLight to MidnightBlue
    } else {
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer
    }
}

@Composable
fun outgoingChatBubbleColors(): Pair<Color, Color> {
    val colorScheme = MaterialTheme.colorScheme
    return if (colorScheme.surface.luminance() > 0.5f) {
        OutgoingBubbleGrayLight to MidnightBlue
    } else {
        colorScheme.surfaceVariant to colorScheme.onSurface
    }
}
