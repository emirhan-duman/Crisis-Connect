package com.auralis.crisisconnect.ui.cert

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.security.CertificateProvisioningNotifier
import com.auralis.crisisconnect.security.CertificateProvisioningNotifier.Event
import kotlinx.coroutines.delay

private const val IN_PROGRESS_MAX_DISPLAY_MS = 12_000L
private const val SUCCESS_DISPLAY_MS = 4_500L
private const val FAILURE_DISPLAY_MS = 7_000L

private data class BannerStyle(
    val icon: ImageVector,
    val container: Color,
    val accent: Color,
    val onContainer: Color,
    val showProgress: Boolean,
    val title: String,
    val body: String,
)

@Composable
private fun rememberBannerStyle(event: Event): BannerStyle {
    val colors = MaterialTheme.colorScheme
    return when (event) {
        Event.InProgress -> BannerStyle(
            icon = Icons.Outlined.VerifiedUser,
            container = colors.surfaceVariant,
            accent = colors.tertiary,
            onContainer = colors.onSurfaceVariant,
            showProgress = true,
            title = stringResource(R.string.cert_banner_in_progress_title),
            body = stringResource(R.string.cert_banner_in_progress_body),
        )
        Event.Success -> BannerStyle(
            icon = Icons.Outlined.CheckCircle,
            container = colors.primaryContainer,
            accent = colors.primary,
            onContainer = colors.onPrimaryContainer,
            showProgress = false,
            title = stringResource(R.string.cert_banner_success_title),
            body = stringResource(R.string.cert_banner_success_body),
        )
        is Event.Failure -> BannerStyle(
            icon = Icons.Outlined.ErrorOutline,
            container = colors.errorContainer,
            accent = colors.error,
            onContainer = colors.onErrorContainer,
            showProgress = false,
            title = stringResource(R.string.cert_banner_failure_title),
            body = event.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                stringResource(R.string.cert_banner_failure_body_with_detail, detail)
            } ?: stringResource(R.string.cert_banner_failure_body_generic),
        )
    }
}

private fun autoDismissMs(event: Event): Long = when (event) {
    Event.InProgress -> IN_PROGRESS_MAX_DISPLAY_MS
    Event.Success -> SUCCESS_DISPLAY_MS
    is Event.Failure -> FAILURE_DISPLAY_MS
}

/**
 * Modern floating banner mounted near the top of the screen that surfaces
 * automatic certificate provisioning outcomes (in-progress, success, failure).
 *
 * Behaviour:
 *  - Subscribes to [CertificateProvisioningNotifier] events.
 *  - Auto-dismisses after a state-specific timeout.
 *  - Replacing an in-progress event with a terminal one resets the timer.
 *  - User can manually dismiss with the close button.
 */
@Composable
fun CertificateProvisioningBanner(modifier: Modifier = Modifier) {
    var currentEvent by remember { mutableStateOf<Event?>(null) }
    var dismissalToken by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        CertificateProvisioningNotifier.events.collect { event ->
            currentEvent = event
            dismissalToken++
        }
    }

    val event = currentEvent
    LaunchedEffect(dismissalToken, event) {
        val activeEvent = event ?: return@LaunchedEffect
        delay(autoDismissMs(activeEvent))
        if (event === currentEvent) {
            currentEvent = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = event != null,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 320, easing = LinearOutSlowInEasing),
                initialOffsetY = { -it - 40 },
            ) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 260, easing = LinearEasing),
                targetOffsetY = { -it - 40 },
            ) + fadeOut(animationSpec = tween(200)),
        ) {
            if (event != null) {
                BannerCard(
                    event = event,
                    onDismiss = { currentEvent = null },
                )
            }
        }
    }
}

@Composable
private fun BannerCard(event: Event, onDismiss: () -> Unit) {
    val style = rememberBannerStyle(event)
    val elevationDp by animateFloatAsState(
        targetValue = if (event === Event.InProgress) 6f else 10f,
        animationSpec = tween(400),
        label = "cert-banner-elevation",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .shadow(
                elevation = elevationDp.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = style.accent.copy(alpha = 0.25f),
                spotColor = style.accent.copy(alpha = 0.4f),
            ),
        shape = RoundedCornerShape(20.dp),
        color = style.container,
        contentColor = style.onContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(style = style, showSpinner = style.showProgress)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = style.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = style.onContainer,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = style.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = style.onContainer.copy(alpha = 0.85f),
                        maxLines = 3,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cert_banner_dismiss),
                        modifier = Modifier.size(18.dp),
                        tint = style.onContainer.copy(alpha = 0.75f),
                    )
                }
            }
            if (style.showProgress) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = style.accent,
                    trackColor = style.accent.copy(alpha = 0.18f),
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(style.accent.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun IconBadge(style: BannerStyle, showSpinner: Boolean) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = style.accent.copy(alpha = 0.18f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = style.accent,
                strokeWidth = 2.2.dp,
            )
        } else {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Wraps arbitrary screen content and overlays the certificate provisioning
 * banner at the top. Use this once at the Compose root (just inside the theme
 * surface) so the banner floats above any screen.
 */
@Composable
fun WithCertificateProvisioningBanner(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        content()
        CertificateProvisioningBanner(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().run { 0.dp })
        )
    }
}
