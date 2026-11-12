package com.auralis.crisisconnect.screens.QR

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme

private data class QrStatusVisuals(
    val title: String,
    val supportingText: String,
    val icon: ImageVector? = null
)

@Composable
private fun qrStatusVisuals(status: ScanStatusMessage): QrStatusVisuals {
    return when (status.stage) {
        ScanStatusStage.SEARCHING -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_searching),
            stringResource(R.string.qr_scan_status_detail_searching))
        ScanStatusStage.PAIRING -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_pairing),
            stringResource(R.string.qr_scan_status_detail_pairing))
        ScanStatusStage.SAVING -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_saving),
            stringResource(R.string.qr_scan_status_detail_saving))
        ScanStatusStage.SAVED -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_saved),
            stringResource(R.string.qr_scan_status_detail_saved), Icons.Default.Check)
        ScanStatusStage.ALREADY_REGISTERED -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_already_registered),
            stringResource(R.string.qr_scan_status_detail_already_registered), Icons.Default.Check)
        ScanStatusStage.SCANNED -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_badge_scanned), status.text, Icons.Default.QrCodeScanner)
        ScanStatusStage.ERROR -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_badge_error), status.text, Icons.Default.ErrorOutline)
    }
}

/** Presentation only; scan results and connection progress are owned by the view model. */
@Composable
internal fun QrScannerProgressCard(status: ScanStatusMessage, modifier: Modifier = Modifier) {
    val visuals = qrStatusVisuals(status)
    val colors = MaterialTheme.colorScheme
    val accent = when (status.stage) {
        ScanStatusStage.SAVED -> if (colors.surface.luminance() < 0.5f) Color(0xFF7DD3A7) else Color(0xFF15803D)
        ScanStatusStage.ERROR -> colors.error
        else -> colors.onSurface
    }
    val indicatorBackground = when (status.stage) {
        ScanStatusStage.SAVED -> accent.copy(alpha = 0.12f)
        ScanStatusStage.ERROR -> colors.errorContainer
        else -> colors.surfaceContainerHigh
    }
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 4.dp,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier.size(40.dp).background(indicatorBackground, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center
            ) {
                if (status.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = accent,
                        strokeWidth = 2.dp,
                        trackColor = colors.outlineVariant
                    )
                } else {
                    visuals.icon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (status.stage == ScanStatusStage.ERROR) colors.onErrorContainer else accent
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    visuals.title,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    visuals.supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun QrScannerBottomStatus(status: ScanStatusMessage, modifier: Modifier = Modifier) {
    // Keep the complete error/recovery message from the verifier visible.
    QrScannerProgressCard(status, modifier)
}

@Composable
private fun QrStatusCardGallery() {
    Column(Modifier.background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        QrScannerProgressCard(ScanStatusMessage("", true, ScanStatusStage.SEARCHING))
        QrScannerProgressCard(ScanStatusMessage("", true, ScanStatusStage.SAVING))
        QrScannerProgressCard(ScanStatusMessage("", false, ScanStatusStage.SAVED))
        QrScannerProgressCard(ScanStatusMessage(
            stringResource(R.string.qr_scan_error_bluetooth_off), false, ScanStatusStage.ERROR))
    }
}

@Preview(name = "QR cards · Light", widthDp = 390, heightDp = 800)
@Composable
private fun QrStatusCardsLightPreview() {
    DisasterCommunicationSystemTheme(darkTheme = false, dynamicColor = false) { QrStatusCardGallery() }
}

@Preview(name = "QR cards · Dark", widthDp = 390, heightDp = 800)
@Composable
private fun QrStatusCardsDarkPreview() {
    DisasterCommunicationSystemTheme(darkTheme = true, dynamicColor = false) { QrStatusCardGallery() }
}

@Preview(name = "QR cards · Large text", widthDp = 320, heightDp = 900, fontScale = 2f)
@Composable
private fun QrStatusCardsLargePreview() {
    DisasterCommunicationSystemTheme(darkTheme = true, dynamicColor = false) { QrStatusCardGallery() }
}
