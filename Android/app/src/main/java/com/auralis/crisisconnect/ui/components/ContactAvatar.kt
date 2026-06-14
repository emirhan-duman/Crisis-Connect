package com.auralis.crisisconnect.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import java.security.MessageDigest
import kotlin.random.Random

private const val AVATAR_COLOR_PREFS = "avatar_color_prefs"
private const val AVATAR_COLOR_KEY_PREFIX = "avatar_color_"

private val avatarPalette = listOf(
    Color(0xFF2E4F6F), // steel blue
    Color(0xFF3A6173), // slate teal
    Color(0xFF4A5D89), // muted indigo
    Color(0xFF486043), // olive green
    Color(0xFF6C5437), // bronze
    Color(0xFF714747), // brick
    Color(0xFF5A4D74), // muted plum
    Color(0xFF2F6A62), // deep teal
    Color(0xFF4E6674), // blue gray
    Color(0xFF5E6B44)  // moss
)
private val bracketedSegmentPattern = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")
private val whitespacePattern = Regex("\\s+")

@Composable
fun ContactAvatar(
    displayName: String,
    stableKey: String,
    bitmap: Bitmap? = null,
    photoUrl: String? = null,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    val context = LocalContext.current
    val placeholderInitial = stringResource(R.string.contact_initial_placeholder)
    val initials = remember(displayName, placeholderInitial) {
        val normalizedName = displayName
            .replace(bracketedSegmentPattern, " ")
            .trim()

        normalizedName
            .split(whitespacePattern)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { token ->
                token.firstOrNull { char -> char.isLetterOrDigit() }
                    ?.uppercaseChar()
                    ?.toString()
            }
            .take(2)
            .joinToString(separator = "")
            .ifEmpty { placeholderInitial }
    }
    val backgroundColor = remember(context, stableKey, displayName) {
        val index = resolveAvatarColorIndex(
            context = context,
            stableKey = stableKey,
            displayName = displayName
        )
        avatarPalette[index]
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = initials,
                style = textStyle,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GroupChatAvatar(
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Groups,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

private fun resolveAvatarColorIndex(
    context: Context,
    stableKey: String,
    displayName: String
): Int {
    val prefs = context.applicationContext
        .getSharedPreferences(AVATAR_COLOR_PREFS, Context.MODE_PRIVATE)
    val storageKey = buildAvatarStorageKey(stableKey, displayName)
    val savedIndex = prefs.getInt(storageKey, -1)
    if (savedIndex in avatarPalette.indices) {
        return savedIndex
    }
    val randomIndex = Random.nextInt(avatarPalette.size)
    prefs.edit().putInt(storageKey, randomIndex).apply()
    return randomIndex
}

private fun buildAvatarStorageKey(stableKey: String, displayName: String): String {
    val normalizedKey = stableKey.trim().ifEmpty { displayName.trim() }.ifBlank { "avatar" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizedKey.toByteArray(Charsets.UTF_8))
    val hex = buildString(digest.size * 2) {
        digest.forEach { byte ->
            append(((byte.toInt() shr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }
    return AVATAR_COLOR_KEY_PREFIX + hex
}
