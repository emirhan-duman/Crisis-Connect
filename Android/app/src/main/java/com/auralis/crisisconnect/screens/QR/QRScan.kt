package com.auralis.crisisconnect.screens.QR

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanning
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Chat.ChatScreenViewModel
import com.auralis.crisisconnect.screens.Chat.EncryptionSetupResult
import java.net.URLDecoder
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: QrScannerViewModel = viewModel()
    val hasCameraPermission by viewModel.hasCameraPermission.collectAsStateWithLifecycle()
    val showNameDialog by viewModel.showNameDialog.collectAsStateWithLifecycle()
    val manualName by viewModel.manualName.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }
    var analyzerSet by remember { mutableStateOf(false) }
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var lastRetryAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var shouldRetryAfterPermission by remember { mutableStateOf(false) }
    var frozenPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var detectedQrBounds by remember { mutableStateOf<Rect?>(null) }
    var lastHandledQr by remember { mutableStateOf<String?>(null) }
    var lastHandledQrAtMs by remember { mutableLongStateOf(0L) }

    val prominentStatus = statusMessage?.takeIf {
        !showNameDialog && it.stage in PROMINENT_SCAN_STAGES
    }
    val bottomStatus = statusMessage?.takeIf {
        !showNameDialog && it.stage !in PROMINENT_SCAN_STAGES
    }
    val overlayState = when {
        prominentStatus != null || showNameDialog || frozenPreview != null ->
            QrScannerOverlayState.Locked

        bottomStatus?.stage == ScanStatusStage.ERROR -> QrScannerOverlayState.Error
        else -> QrScannerOverlayState.Scanning
    }

    val requiredBtPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // IMPORTANT: Keep location permissions here.
            // Some devices still require location permission for classic Bluetooth discovery.
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun openAppSettings() {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val scanGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val connectGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Keep this location check aligned with requiredBtPermissions above.
        scanGranted && connectGranted && locationGranted
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        val granted = hasBluetoothPermissions()
        if (granted) {
            Log.d("QRScanner", "Bluetooth permissions granted")
            shouldRetryAfterPermission = false
            action?.invoke()
        } else {
            Log.w("QRScanner", "Bluetooth permissions denied")
            shouldRetryAfterPermission = false
            lastRetryAction = null
            viewModel.reportMissingPermissions()
        }
    }

    val scanner = remember { BarcodeScanning.getClient() }

    fun clearCapturedQrVisual() {
        frozenPreview = null
        detectedQrBounds = null
    }

    fun captureDetectedQr(qrBounds: AndroidRect?) {
        frozenPreview = previewView.bitmap?.asImageBitmap()
        detectedQrBounds = qrBounds?.toComposeRect()
    }

    fun startAnalyzer() {
        clearCapturedQrVisual()
        Log.d("QRScanner", "Starting QR analyzer")
        controller.clearImageAnalysisAnalyzer()
        controller.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(context),
            MlKitAnalyzer(
                listOf(scanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(context)
            ) { result ->
                val barcode = result.getValue(scanner)?.firstOrNull()
                val raw = barcode?.rawValue

                if (raw.isNullOrEmpty() || pendingPermissionAction != null) {
                    return@MlKitAnalyzer
                }

                val now = SystemClock.elapsedRealtime()
                if (raw == lastHandledQr && now - lastHandledQrAtMs < QR_SCAN_RESULT_COOLDOWN_MS) {
                    return@MlKitAnalyzer
                }
                lastHandledQr = raw
                lastHandledQrAtMs = now

                Log.d("QRScanner", "QR code detected, length: ${raw.length}")
                captureDetectedQr(barcode?.boundingBox)

                val processAction = {
                    viewModel.onQrResult(context, raw) {
                        navController.navigate("main") {
                            popUpTo("main") { inclusive = true }
                        }
                    }
                    controller.clearImageAnalysisAnalyzer()
                    analyzerSet = false
                }
                lastRetryAction = processAction

                if (hasBluetoothPermissions()) {
                    processAction()
                } else {
                    Log.d("QRScanner", "Requesting Bluetooth permissions")
                    pendingPermissionAction = processAction
                    shouldRetryAfterPermission = true
                    btPermissionLauncher.launch(requiredBtPermissions)
                }
            }
        )
        analyzerSet = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d("QRScanner", "Camera permission granted")
        } else {
            Log.w("QRScanner", "Camera permission denied")
        }
        viewModel.setPermission(granted)
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("QRScanner", "Camera permission granted")
            viewModel.setPermission(true)
        } else {
            Log.d("QRScanner", "Requesting camera permission")
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            try {
                controller.bindToLifecycle(lifecycleOwner)
                previewView.controller = controller
                if (!analyzerSet) {
                    startAnalyzer()
                }
            } catch (_: Exception) { }
        } else {
            Log.w("QRScanner", "Camera permission denied")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewView.controller = null
            try { controller.unbind() } catch (_: Exception) { }
            analyzerSet = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        previewView.controller = null
                        try { controller.unbind() } catch (_: Exception) { }
                        navController.popBackStack()
                    }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                ) {
                    Text(
                        text = stringResource(R.string.qr_scan_instruction),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }

                if (hasCameraPermission) {
                    val previewShape = RoundedCornerShape(28.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .widthIn(max = 420.dp)
                                .aspectRatio(1f)
                                .clip(previewShape)
                        ) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                            frozenPreview?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            QrScannerOverlay(
                                cornerRadius = 28.dp,
                                borderWidth = 4.dp,
                                overlayState = overlayState,
                                highlightedBounds = detectedQrBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = prominentStatus != null,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    prominentStatus?.let { status ->
                                        QrScannerProgressCard(
                                            status = status,
                                            modifier = Modifier
                                                .fillMaxWidth(0.94f)
                                                .widthIn(max = 408.dp)
                                        )
                                    }
                                }
                            }
                            if (showNameDialog) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                    shape = RoundedCornerShape(18.dp),
                                    tonalElevation = 8.dp,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 28.dp)
                                ) {
                                    Text(
                                        text = statusMessage?.text.orEmpty(),
                                        modifier = Modifier.padding(
                                            horizontal = 20.dp,
                                            vertical = 16.dp
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        tonalElevation = 3.dp,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.qr_scan_help_title),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = stringResource(R.string.qr_scan_help_step_align),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(R.string.qr_scan_help_step_still),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(R.string.qr_scan_help_step_light),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.size(8.dp))

                    Surface(
                        tonalElevation = 4.dp,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.qr_scan_camera_permission_required),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.qr_scan_camera_permission_help),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.qr_scan_grant_camera_permission))
                            }
                            TextButton(onClick = ::openAppSettings) {
                                Text(stringResource(R.string.qr_scan_open_settings))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.size(8.dp))
                }
            }

            if (showNameDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissNameDialog() },
                    title = { Text(stringResource(R.string.qr_scan_enter_name_title)) },
                    text = {
                        OutlinedTextField(
                            value = manualName,
                            onValueChange = { viewModel.updateManualName(it) },
                            label = { Text(stringResource(R.string.qr_scan_name_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val action = {
                                viewModel.saveManualContact(context) {
                                    navController.navigate("main") {
                                        popUpTo("main") { inclusive = true }
                                    }
                                }
                            }
                            lastRetryAction = action
                            action()
                        }) {
                            Text(stringResource(R.string.save))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissNameDialog() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
            AnimatedVisibility(
                visible = bottomStatus != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                bottomStatus?.let { status ->
                    QrScannerBottomStatus(
                        status = status,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .widthIn(max = 560.dp)
                    )
                }
            }
            LaunchedEffect(error) {
                error?.let {
                    controller.clearImageAnalysisAnalyzer()
                    analyzerSet = false

                    if (it == ScanError.MISSING_PERMISSIONS) {
                        if (hasBluetoothPermissions()) {
                            pendingPermissionAction = null
                            shouldRetryAfterPermission = false
                            lastRetryAction?.invoke()
                        } else if (lastRetryAction != null && !shouldRetryAfterPermission) {
                            pendingPermissionAction = lastRetryAction
                            shouldRetryAfterPermission = true
                            btPermissionLauncher.launch(requiredBtPermissions)
                        } else if (lastRetryAction == null) {
                            startAnalyzer()
                        }
                    } else {
                        if (it == ScanError.ALREADY_REGISTERED) {
                            delay(QR_ALREADY_REGISTERED_RETRY_DELAY_MS)
                        }
                        startAnalyzer()
                    }
                    viewModel.dismissError()
                }
            }
        }
    }
}

private val PROMINENT_SCAN_STAGES = setOf(
    ScanStatusStage.SEARCHING,
    ScanStatusStage.PAIRING,
    ScanStatusStage.SAVING,
    ScanStatusStage.SAVED,
    ScanStatusStage.ALREADY_REGISTERED
)

private const val QR_SCAN_RESULT_COOLDOWN_MS = 2_500L
private const val QR_ALREADY_REGISTERED_RETRY_DELAY_MS = 1_600L

private enum class QrScannerOverlayState {
    Scanning,
    Locked,
    Error
}

private fun AndroidRect.toComposeRect(): Rect = Rect(
    left = left.toFloat(),
    top = top.toFloat(),
    right = right.toFloat(),
    bottom = bottom.toFloat()
)

private data class QrStatusVisuals(
    val badge: String,
    val title: String,
    val supportingText: String,
    val icon: ImageVector,
    val accentColor: Color,
    val showProgress: Boolean,
    val footerHint: String? = null
)

@Composable
private fun qrStatusVisuals(status: ScanStatusMessage): QrStatusVisuals {
    val colorScheme = MaterialTheme.colorScheme
    return when (status.stage) {
        ScanStatusStage.SEARCHING -> QrStatusVisuals(
            badge = stringResource(R.string.qr_scan_status_badge_scanned),
            title = stringResource(R.string.qr_scan_status_title_searching),
            supportingText = stringResource(R.string.qr_scan_status_detail_searching),
            icon = Icons.Filled.Search,
            accentColor = colorScheme.primary,
            showProgress = true,
            footerHint = stringResource(R.string.qr_scan_status_hint_hold_steady)
        )

        ScanStatusStage.PAIRING -> QrStatusVisuals(
            badge = stringResource(R.string.qr_scan_status_badge_pairing),
            title = stringResource(R.string.qr_scan_status_title_pairing),
            supportingText = stringResource(R.string.qr_scan_status_detail_pairing),
            icon = Icons.Filled.Bluetooth,
            accentColor = Color(0xFF2563EB),
            showProgress = true,
            footerHint = stringResource(R.string.qr_scan_status_hint_hold_steady)
        )

        ScanStatusStage.SAVING -> QrStatusVisuals(
            badge = stringResource(R.string.qr_scan_status_badge_saving),
            title = stringResource(R.string.qr_scan_status_title_saving),
            supportingText = stringResource(R.string.qr_scan_status_detail_saving),
            icon = Icons.Filled.PersonAddAlt1,
            accentColor = Color(0xFF7C3AED),
            showProgress = true,
            footerHint = stringResource(R.string.qr_scan_status_hint_hold_steady)
        )

        ScanStatusStage.SAVED -> QrStatusVisuals(
            badge = stringResource(R.string.qr_scan_status_badge_saved),
            title = stringResource(R.string.qr_scan_status_title_saved),
            supportingText = stringResource(R.string.qr_scan_status_detail_saved),
            icon = Icons.Filled.CheckCircle,
            accentColor = Color(0xFF15803D),
            showProgress = false
        )

        ScanStatusStage.ALREADY_REGISTERED -> QrStatusVisuals(
            badge = stringResource(R.string.qr_scan_status_badge_already_registered),
            title = stringResource(R.string.qr_scan_status_title_already_registered),
            supportingText = stringResource(R.string.qr_scan_status_detail_already_registered),
            icon = Icons.Filled.CheckCircle,
            accentColor = Color(0xFF0F766E),
            showProgress = false
        )

        ScanStatusStage.SCANNED -> QrStatusVisuals(
            badge = stringResource(R.string.qr_scan_status_badge_scanned),
            title = status.text,
            supportingText = stringResource(R.string.qr_scan_status_detail_scanned),
            icon = Icons.Filled.QrCodeScanner,
            accentColor = colorScheme.primary,
            showProgress = false
        )

        ScanStatusStage.ERROR -> QrStatusVisuals(
            badge = stringResource(R.string.qr_scan_status_badge_error),
            title = status.text,
            supportingText = stringResource(R.string.qr_scan_status_detail_error),
            icon = Icons.Filled.QrCodeScanner,
            accentColor = colorScheme.error,
            showProgress = false
        )
    }
}

@Composable
private fun QrScannerProgressCard(
    status: ScanStatusMessage,
    modifier: Modifier = Modifier
) {
    val visuals = qrStatusVisuals(status)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(
            width = 1.dp,
            color = visuals.accentColor.copy(alpha = 0.18f)
        )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactLayout = maxWidth < 340.dp || maxHeight < 290.dp
            val contentPadding = if (compactLayout) 14.dp else 18.dp
            val sectionSpacing = if (compactLayout) 10.dp else 14.dp
            val headerShape = if (compactLayout) 18.dp else 20.dp
            val iconContainerSize = if (compactLayout) 34.dp else 40.dp
            val iconSize = if (compactLayout) 18.dp else 20.dp
            val badgeShape = RoundedCornerShape(999.dp)
            val showExtendedCopy = !compactLayout

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = contentPadding, vertical = contentPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = visuals.accentColor.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(headerShape)
                        )
                        .padding(
                            horizontal = if (compactLayout) 10.dp else 12.dp,
                            vertical = if (compactLayout) 8.dp else 10.dp
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(if (compactLayout) 10.dp else 12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = visuals.accentColor.copy(alpha = 0.14f),
                            modifier = Modifier.size(iconContainerSize)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = visuals.icon,
                                    contentDescription = null,
                                    tint = visuals.accentColor,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (compactLayout) 3.dp else 4.dp)
                        ) {
                            Surface(
                                modifier = Modifier.align(Alignment.Start),
                                shape = badgeShape,
                                color = visuals.accentColor.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = visuals.badge,
                                    modifier = Modifier.padding(
                                        horizontal = if (compactLayout) 8.dp else 10.dp,
                                        vertical = if (compactLayout) 4.dp else 5.dp
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = visuals.accentColor,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = if (compactLayout) 1 else 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = visuals.title,
                                modifier = Modifier.fillMaxWidth(),
                                style = if (compactLayout) {
                                    MaterialTheme.typography.titleSmall
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (showExtendedCopy) {
                    Text(
                        text = visuals.supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }

                if (visuals.showProgress) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (showExtendedCopy) 2.dp else 0.dp),
                        color = visuals.accentColor,
                        trackColor = visuals.accentColor.copy(alpha = 0.16f)
                    )
                }

                if (showExtendedCopy) {
                    visuals.footerHint?.let { footerHint ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = visuals.accentColor.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = footerHint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = visuals.accentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrScannerBottomStatus(
    status: ScanStatusMessage,
    modifier: Modifier = Modifier
) {
    val isError = status.stage == ScanStatusStage.ERROR
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (status.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = status.text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatEncryptionSetupScreen(navController: NavController, sessionCode: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val chatViewModel: ChatScreenViewModel = viewModel()

    LaunchedEffect(sessionCode) {
        chatViewModel.initialize(sessionCode)
    }

    var hasCameraPermission by remember { mutableStateOf(false) }
    var analyzerSet by remember { mutableStateOf(false) }
    var bottomMessage by remember { mutableStateOf<String?>(null) }
    var bottomLoading by remember { mutableStateOf(false) }
    var handshakeResult by remember { mutableStateOf<EncryptionSetupResult?>(null) }
    var handshakeInProgress by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }
    val scanner = remember { BarcodeScanning.getClient() }
    val expectedSessionCode = remember(sessionCode) {
        val trimmed = sessionCode.trim()
        runCatching { Uri.decode(trimmed) }.getOrDefault(trimmed)
    }

    fun restartAnalyzer() {
        if (!hasCameraPermission) {
            return
        }
        Log.d("QRScanner", "Starting QR analyzer")
        controller.clearImageAnalysisAnalyzer()
        controller.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(context),
            MlKitAnalyzer(
                listOf(scanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(context)
            ) { result ->
                if (handshakeInProgress) {
                    return@MlKitAnalyzer
                }
                val barcode = result.getValue(scanner)?.firstOrNull()
                val raw = barcode?.rawValue
                if (raw.isNullOrBlank()) {
                    return@MlKitAnalyzer
                }
                Log.d("QRScanner", "QR code detected, length: ${raw.length}")
                handshakeInProgress = true
                controller.clearImageAnalysisAnalyzer()
                val handshake = parseHandshake(raw)
                val normalizedSession = handshake?.first?.trim().orEmpty()
                val normalizedKey = handshake?.second?.trim().orEmpty()
                if (normalizedSession.isEmpty() || normalizedKey.isEmpty()) {
                    val message = context.getString(R.string.chat_encryption_setup_invalid_qr)
                    bottomMessage = message
                    bottomLoading = false
                    handshakeInProgress = false
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    restartAnalyzer()
                    return@MlKitAnalyzer
                }
                val decodedSession = runCatching { Uri.decode(normalizedSession) }
                    .getOrDefault(normalizedSession)
                if (!decodedSession.equals(expectedSessionCode, ignoreCase = true)) {
                    val message = context.getString(R.string.chat_encryption_setup_session_mismatch)
                    bottomMessage = message
                    bottomLoading = false
                    handshakeInProgress = false
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    restartAnalyzer()
                    return@MlKitAnalyzer
                }
                bottomMessage = context.getString(R.string.chat_encryption_setup_handshake_in_progress)
                bottomLoading = true
                handshakeResult = null
                chatViewModel.performEncryptionSetup(normalizedKey) { result ->
                    handshakeResult = result
                    when (result) {
                        EncryptionSetupResult.Success -> {
                            bottomMessage = context.getString(R.string.chat_encryption_setup_handshake_success)
                            bottomLoading = false
                            handshakeInProgress = false
                        }

                        EncryptionSetupResult.InvalidKey -> {
                            val message = context.getString(R.string.chat_encryption_setup_invalid_qr)
                            bottomMessage = message
                            bottomLoading = false
                            handshakeInProgress = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            restartAnalyzer()
                        }

                        EncryptionSetupResult.MissingSession, EncryptionSetupResult.HandshakeFailed -> {
                            val message = context.getString(R.string.chat_encryption_setup_handshake_failed)
                            bottomMessage = message
                            bottomLoading = false
                            handshakeInProgress = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            restartAnalyzer()
                        }
                    }
                }
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            try {
                controller.bindToLifecycle(lifecycleOwner)
                previewView.controller = controller
                restartAnalyzer()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewView.controller = null
            try {
                controller.unbind()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(handshakeResult) {
        if (handshakeResult == EncryptionSetupResult.Success) {
            delay(1200)
            previewView.controller = null
            try {
                controller.unbind()
            } catch (_: Exception) {
            }
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_encryption_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        previewView.controller = null
                        try {
                            controller.unbind()
                        } catch (_: Exception) {
                        }
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.chat_encryption_setup_title),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                    QrScannerOverlay(
                        cornerRadius = 24.dp,
                        borderWidth = 4.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.chat_encryption_setup_camera_permission),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_encryption_setup_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                AnimatedVisibility(
                    visible = bottomMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    bottomMessage?.let { message ->
                        Surface(
                            tonalElevation = 6.dp,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (bottomLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseHandshake(raw: String): Pair<String, String>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return null
    }

    if (trimmed.startsWith("dcs://", ignoreCase = true)) {
        val content = trimmed.substringAfter("://", trimmed)
        val decoded = decodeUriContent(content)
        return parseJsonHandshake(decoded)
    }

    if (trimmed.startsWith("{")) {
        return parseJsonHandshake(trimmed)
    }

    val parts = trimmed.split('|')
    return when (parts.size) {
        3 -> {
            val session = parts[1].trim()
            val key = parts[2].trim()
            if (session.isEmpty() || key.isEmpty()) {
                null
            } else {
                session to key
            }
        }

        2 -> {
            val session = parts[0].trim()
            val key = parts[1].trim()
            if (session.isEmpty() || key.isEmpty()) {
                null
            } else {
                session to key
            }
        }

        else -> null
    }
}

private fun decodeUriContent(raw: String): String {
    val fromAndroidUri = runCatching { Uri.decode(raw) }.getOrNull()
    if (!fromAndroidUri.isNullOrBlank()) {
        return fromAndroidUri
    }
    return runCatching {
        URLDecoder.decode(raw, Charsets.UTF_8.name())
    }.getOrElse { raw }
}

private fun parseJsonHandshake(raw: String): Pair<String, String>? {
    val code = extractJsonStringField(raw, "code") ?: return null
    val key = extractJsonStringField(raw, "key") ?: return null
    return code to key
}

private fun extractJsonStringField(rawJson: String, fieldName: String): String? {
    val regex = Regex("""["']$fieldName["']\s*:\s*["']([^"']+)["']""")
    return regex.find(rawJson)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

@Composable
private fun QrScannerOverlay(
    cornerRadius: Dp,
    borderWidth: Dp,
    modifier: Modifier = Modifier,
    overlayState: QrScannerOverlayState = QrScannerOverlayState.Scanning,
    highlightedBounds: Rect? = null
) {
    val frameColor = when (overlayState) {
        QrScannerOverlayState.Scanning -> Color.White
        QrScannerOverlayState.Locked -> Color(0xFF7DD3A7)
        QrScannerOverlayState.Error -> MaterialTheme.colorScheme.error
    }
    val highlightColor = when (overlayState) {
        QrScannerOverlayState.Error -> MaterialTheme.colorScheme.error
        else -> Color(0xFF34D399)
    }

    Canvas(modifier = modifier) {
        val radius = cornerRadius.toPx()
        val cutSize = size.minDimension * 0.7f
        val topLeft = Offset(
            (size.width - cutSize) / 2f,
            (size.height - cutSize) / 2f
        )
        val frameSize = Size(cutSize, cutSize)

        val path = Path().apply {
            addRect(Rect(Offset.Zero, size))
            addRoundRect(
                RoundRect(
                    rect = Rect(topLeft, frameSize),
                    cornerRadius = CornerRadius(radius)
                )
            )
            fillType = PathFillType.EvenOdd
        }

        drawPath(
            path = path,
            color = Color.Black.copy(alpha = if (overlayState == QrScannerOverlayState.Locked) 0.62f else 0.5f)
        )

        drawRoundRect(
            color = frameColor.copy(alpha = 0.2f),
            topLeft = topLeft,
            size = frameSize,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = borderWidth.toPx() * 2f)
        )

        drawRoundRect(
            color = frameColor,
            topLeft = topLeft,
            size = frameSize,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = borderWidth.toPx())
        )

        highlightedBounds?.let { rawBounds ->
            val clampedBounds = Rect(
                left = rawBounds.left.coerceIn(0f, size.width),
                top = rawBounds.top.coerceIn(0f, size.height),
                right = rawBounds.right.coerceIn(0f, size.width),
                bottom = rawBounds.bottom.coerceIn(0f, size.height)
            )

            if (clampedBounds.width > 0f && clampedBounds.height > 0f) {
                val highlightTopLeft = Offset(clampedBounds.left, clampedBounds.top)
                val highlightSize = Size(clampedBounds.width, clampedBounds.height)
                val highlightRadius = CornerRadius((borderWidth.toPx() * 2f).coerceAtLeast(12f))

                drawRoundRect(
                    color = highlightColor.copy(alpha = 0.18f),
                    topLeft = highlightTopLeft,
                    size = highlightSize,
                    cornerRadius = highlightRadius
                )
                drawRoundRect(
                    color = highlightColor,
                    topLeft = highlightTopLeft,
                    size = highlightSize,
                    cornerRadius = highlightRadius,
                    style = Stroke(width = borderWidth.toPx())
                )
            }
        }
    }
}
