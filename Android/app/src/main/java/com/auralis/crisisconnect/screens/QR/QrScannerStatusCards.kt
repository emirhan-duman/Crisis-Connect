package com.auralis.crisisconnect.screens.QR

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme

private data class QrStatusVisuals(
    val title: String,
    val supportingText: String,
    val icon: ImageVector,
    val accent: Color,
    val step: Int? = null
)

@Composable
private fun qrStatusVisuals(status: ScanStatusMessage): QrStatusVisuals {
    val colors = MaterialTheme.colorScheme
    return when (status.stage) {
        ScanStatusStage.SEARCHING -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_searching),
            stringResource(R.string.qr_scan_status_detail_searching), Icons.Default.Search, colors.onSurface, 0)
        ScanStatusStage.PAIRING -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_pairing),
            stringResource(R.string.qr_scan_status_detail_pairing), Icons.Default.Bluetooth, colors.onSurface, 1)
        ScanStatusStage.SAVING -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_saving),
            stringResource(R.string.qr_scan_status_detail_saving), Icons.Default.PersonAddAlt1, colors.onSurface, 2)
        ScanStatusStage.SAVED -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_saved),
            stringResource(R.string.qr_scan_status_detail_saved), Icons.Default.CheckCircle,
            if (colors.surface.luminance() < 0.5f) Color(0xFF7DD3A7) else Color(0xFF15803D))
        ScanStatusStage.ALREADY_REGISTERED -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_title_already_registered),
            stringResource(R.string.qr_scan_status_detail_already_registered), Icons.Default.CheckCircle, colors.onSurface)
        ScanStatusStage.SCANNED -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_badge_scanned), status.text, Icons.Default.QrCodeScanner, colors.onSurface)
        ScanStatusStage.ERROR -> QrStatusVisuals(
            stringResource(R.string.qr_scan_status_badge_error), status.text, Icons.Default.ErrorOutline, colors.error)
    }
}

/** Presentation only; scan results and connection progress are owned by the view model. */
@Composable
internal fun QrScannerProgressCard(status: ScanStatusMessage, modifier: Modifier = Modifier) {
    val visuals = qrStatusVisuals(status)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (status.stage == ScanStatusStage.SAVED) visuals.accent.copy(alpha = 0.12f)
                            else colors.surfaceContainerHighest
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(visuals.icon, null, Modifier.size(24.dp), tint = visuals.accent)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(visuals.title, Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(visuals.supportingText, style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                }
            }
            visuals.step?.let { current ->
                val labels = listOf(
                    stringResource(R.string.qr_scan_step_search),
                    stringResource(R.string.qr_scan_step_pair),
                    stringResource(R.string.qr_scan_step_save))
                if (LocalDensity.current.fontScale >= 1.5f) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        labels.forEachIndexed { index, label -> QrConnectionStep(index, current, label) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        labels.forEachIndexed { index, label ->
                            QrConnectionStep(index, current, label, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrConnectionStep(index: Int, current: Int, label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(when {
        index < current -> R.string.qr_scan_step_done
        index == current -> R.string.qr_scan_step_active
        else -> R.string.qr_scan_step_waiting
    })
    Column(modifier.clearAndSetSemantics { contentDescription = "$label, $description" },
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(
            if (index <= current) colors.onSurface else colors.outlineVariant, RoundedCornerShape(2.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index == current) {
                CircularProgressIndicator(Modifier.size(12.dp), color = colors.onSurface, strokeWidth = 1.5.dp)
            } else if (index < current) {
                Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = colors.onSurface)
            }
            Text(label, style = MaterialTheme.typography.labelMedium,
                fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (index <= current) colors.onSurface else colors.onSurfaceVariant)
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
        QrScannerProgressCard(ScanStatusMessage("", true, ScanStatusStage.PAIRING))
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
