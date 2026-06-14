package com.auralis.crisisconnect.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.crisisconnect.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the device-bound rescue certificate. State and actions live on
 * [ProfileViewModel] so the profile screen header (role chip, verified
 * badge) stays in sync with revoke / provision actions taken here.
 */
@Composable
fun ProfileCertificateCard(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.certificateState.collectAsStateWithLifecycle()
    var confirmRevoke by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.refreshCertificate() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            CardHeader(state = state)

            Spacer(modifier = Modifier.height(14.dp))

            when (val status = state.status) {
                is CertificateStatus.Loading -> LoadingBlock()
                is CertificateStatus.Missing -> MissingBlock(
                    provisioning = state.provisioning,
                    onProvision = { viewModel.provisionCertificate() },
                )
                is CertificateStatus.Loaded -> LoadedBlock(
                    status = status,
                    revoking = state.revoking,
                    provisioning = state.provisioning,
                    onRefresh = { viewModel.refreshCertificate() },
                    onRevokeClicked = { confirmRevoke = true },
                    onRenew = { viewModel.provisionCertificate() },
                )
                is CertificateStatus.Failure -> FailureBlock(
                    message = status.message,
                    onRetry = { viewModel.refreshCertificate() },
                )
            }

            state.statusMessage?.let { message ->
                Spacer(modifier = Modifier.height(10.dp))
                InfoBanner(
                    text = message,
                    isError = false,
                    onDismiss = { viewModel.consumeCertificateMessages() },
                )
            }
            state.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(10.dp))
                InfoBanner(
                    text = message,
                    isError = true,
                    onDismiss = { viewModel.consumeCertificateMessages() },
                )
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { if (!state.revoking) confirmRevoke = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(text = stringResourceSafe(R.string.profile_cert_revoke_title)) },
            text = { Text(text = stringResourceSafe(R.string.profile_cert_revoke_body)) },
            confirmButton = {
                TextButton(
                    enabled = !state.revoking,
                    onClick = {
                        confirmRevoke = false
                        viewModel.revokeCertificate(reason = "user_request")
                    },
                ) {
                    Text(
                        text = stringResourceSafe(R.string.profile_cert_revoke_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.revoking,
                    onClick = { confirmRevoke = false },
                ) {
                    Text(text = stringResourceSafe(R.string.profile_cert_cancel))
                }
            },
        )
    }
}

@Composable
private fun CardHeader(state: CertificateUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                imageVector = Icons.Outlined.VerifiedUser,
                contentDescription = null,
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            Text(
                text = stringResourceSafe(R.string.profile_cert_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResourceSafe(R.string.profile_cert_card_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        val status = state.status
        if (status is CertificateStatus.Loaded) {
            StatusChip(status = status)
        }
    }
}

@Composable
private fun StatusChip(status: CertificateStatus.Loaded) {
    val (label, container, content) = when {
        status.isRevoked -> Triple(
            stringResourceSafe(R.string.profile_cert_status_revoked),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        status.isExpired -> Triple(
            stringResourceSafe(R.string.profile_cert_status_expired),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> Triple(
            stringResourceSafe(R.string.profile_cert_status_active),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            val icon = when {
                status.isRevoked -> Icons.Filled.Close
                status.isExpired -> Icons.Filled.AccessTime
                else -> Icons.Filled.CheckCircle
            }
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResourceSafe(R.string.profile_cert_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MissingBlock(provisioning: Boolean, onProvision: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResourceSafe(R.string.profile_cert_missing_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onProvision,
            enabled = !provisioning,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            if (provisioning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = stringResourceSafe(R.string.profile_cert_request_in_progress))
            } else {
                Icon(imageVector = Icons.Filled.Lock, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResourceSafe(R.string.profile_cert_request_button))
            }
        }
    }
}

@Composable
private fun LoadedBlock(
    status: CertificateStatus.Loaded,
    revoking: Boolean,
    provisioning: Boolean,
    onRefresh: () -> Unit,
    onRevokeClicked: () -> Unit,
    onRenew: () -> Unit,
) {
    val df = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailRow(
            label = stringResourceSafe(R.string.profile_cert_role_label),
            value = status.certificate.role,
        )
        DetailRow(
            label = stringResourceSafe(R.string.profile_cert_device_label),
            value = status.certificate.deviceId,
            mono = true,
        )
        DetailRow(
            label = stringResourceSafe(R.string.profile_cert_issued_label),
            value = df.format(Date(status.certificate.issuedAtMillis)),
        )
        DetailRow(
            label = stringResourceSafe(R.string.profile_cert_expires_label),
            value = df.format(Date(status.certificate.expiresAtMillis)),
            highlight = when {
                status.isExpired -> MaterialTheme.colorScheme.error
                status.certificate.expiresAtMillis - System.currentTimeMillis() <
                    24L * 60 * 60 * 1000 -> MaterialTheme.colorScheme.tertiary
                else -> null
            },
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
        )

        val canRenew = status.isExpired
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (canRenew) {
                Button(
                    onClick = onRenew,
                    modifier = Modifier.weight(1f),
                    enabled = !provisioning,
                    shape = MaterialTheme.shapes.large,
                ) {
                    if (provisioning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResourceSafe(R.string.profile_cert_renew_button))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResourceSafe(R.string.profile_cert_refresh_button))
                }
            }
            OutlinedButton(
                onClick = onRevokeClicked,
                modifier = Modifier.weight(1f),
                enabled = !revoking && !status.isRevoked,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                if (revoking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResourceSafe(R.string.profile_cert_revoke_button))
                }
            }
        }
    }
}

@Composable
private fun FailureBlock(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResourceSafe(R.string.profile_cert_failure_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
        OutlinedButton(onClick = onRetry, shape = MaterialTheme.shapes.large) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResourceSafe(R.string.profile_cert_retry_button))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    highlight: Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.widthIn(min = 110.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight != null) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = highlight ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                Text(text = stringResourceSafe(R.string.profile_cert_dismiss))
            }
        }
    }
}

@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)
