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
import androidx.camera.core.TorchState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Chat.ChatScreenViewModel
import com.auralis.crisisconnect.screens.Chat.EncryptionSetupResult
import java.net.URLDecoder
import kotlinx.coroutines.flow.collect
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

    LaunchedEffect(lifecycleOwner, navController, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.connectionState.collect { state ->
                if (state.result is QrConnectResult.Saved &&
                    navController.currentBackStackEntry?.destination?.route == "qr_scan" &&
                    viewModel.consumeSuccess(state.id)) {
                    navController.navigate("main") { popUpTo("main") { inclusive = true } }
                }
            }
        }
    }

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
    var showTips by remember { mutableStateOf(false) }
    var cameraStreaming by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var torchPending by remember { mutableStateOf(false) }

    DisposableEffect(previewView, controller, lifecycleOwner) {
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            cameraStreaming = state == PreviewView.StreamState.STREAMING
            hasFlash = controller.cameraInfo?.hasFlashUnit() == true
        }
        val torchObserver = Observer<Int> { state -> torchOn = state == TorchState.ON }
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        controller.torchState.observe(lifecycleOwner, torchObserver)
        onDispose {
            previewView.previewStreamState.removeObserver(streamObserver)
            controller.torchState.removeObserver(torchObserver)
        }
    }

    fun toggleTorch() {
        if (!hasFlash || !cameraStreaming || torchPending) return
        torchPending = true
        val request = runCatching { controller.enableTorch(!torchOn) }.getOrNull()
        if (request == null) {
            torchPending = false
            Toast.makeText(context, R.string.qr_scanner_torch_error, Toast.LENGTH_SHORT).show()
            return
        }
        request.addListener({
            torchPending = false
            if (runCatching { request.get() }.isFailure) {
                Toast.makeText(context, R.string.qr_scanner_torch_error, Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

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

                if (raw.isNullOrEmpty() || pendingPermissionAction != null || showTips) {
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
                    viewModel.onQrResult(context, raw)
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
                },
                actions = {
                    IconButton(
                        onClick = { showTips = true },
                        enabled = overlayState != QrScannerOverlayState.Locked || !hasCameraPermission
                    ) {
                        Icon(Icons.Default.HelpOutline, contentDescription = stringResource(R.string.qr_scanner_help_action))
                    }
                }
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val compactLayout = maxHeight < 560.dp || LocalDensity.current.fontScale > 1.3f
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (compactLayout || !hasCameraPermission) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (hasCameraPermission) Arrangement.spacedBy(16.dp) else Arrangement.Center
            ) {
                if (hasCameraPermission) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .then(if (compactLayout) Modifier.height(320.dp) else Modifier.weight(1f))
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black)
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
                            cornerRadius = 20.dp,
                            borderWidth = 2.5.dp,
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
                        if (!compactLayout && overlayState == QrScannerOverlayState.Scanning) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                                shape = RoundedCornerShape(999.dp),
                                color = Color.Black.copy(alpha = 0.7f),
                                contentColor = Color.White
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(
                                        stringResource(if (cameraStreaming) R.string.qr_scanner_camera_ready else R.string.qr_scanner_camera_starting),
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                    )
                                }
                            }
                        }
                        if (hasFlash && overlayState == QrScannerOverlayState.Scanning) {
                            OutlinedButton(
                                onClick = ::toggleTorch,
                                enabled = cameraStreaming && !torchPending,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color.Black.copy(alpha = 0.7f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(if (torchOn) R.string.qr_scanner_torch_off else R.string.qr_scanner_torch_on))
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.qr_scanner_heading),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = stringResource(R.string.qr_scanner_auto_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (compactLayout && overlayState == QrScannerOverlayState.Scanning) {
                            Text(
                                text = stringResource(if (cameraStreaming) R.string.qr_scanner_camera_ready else R.string.qr_scanner_camera_starting),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                            }
                        }
                        Text(
                            text = stringResource(R.string.qr_scanner_permission_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = stringResource(R.string.qr_scanner_permission_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.qr_scan_grant_camera_permission))
                        }
                        TextButton(onClick = ::openAppSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.qr_scan_open_settings))
                        }
                    }
                }
            }

            if (showNameDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissNameDialog() },
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    icon = { Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.onSurface) },
                    title = { Text(stringResource(R.string.qr_scan_enter_name_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.qr_scan_status_detail_scanned),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = { viewModel.updateManualName(it) },
                                label = { Text(stringResource(R.string.qr_scan_name_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                                    focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                                    cursorColor = MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface),
                            onClick = {
                                val action = {
                                    viewModel.saveManualContact(context)
                                }
                                lastRetryAction = action
                                action()
                            }
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissNameDialog() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
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
    if (showTips) {
        ModalBottomSheet(onDismissRequest = { showTips = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.qr_scan_help_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.qr_scan_help_step_align), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.qr_scan_help_step_still), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.qr_scan_help_step_light), style = MaterialTheme.typography.bodyLarge)
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
        val cutSize = size.minDimension * 0.78f
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
            color = Color.Black.copy(alpha = if (overlayState == QrScannerOverlayState.Locked) 0.62f else 0.42f)
        )

        drawRoundRect(
            color = frameColor.copy(alpha = 0.24f),
            topLeft = topLeft,
            size = frameSize,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 1.dp.toPx())
        )

        val left = topLeft.x
        val top = topLeft.y
        val right = left + cutSize
        val bottom = top + cutSize
        val cornerLength = 32.dp.toPx().coerceAtMost(cutSize / 3f)
        val corner = radius.coerceAtMost(cornerLength * 0.75f)
        val guides = Path().apply {
            moveTo(left, top + cornerLength)
            lineTo(left, top + corner)
            quadraticTo(left, top, left + corner, top)
            lineTo(left + cornerLength, top)
            moveTo(right - cornerLength, top)
            lineTo(right - corner, top)
            quadraticTo(right, top, right, top + corner)
            lineTo(right, top + cornerLength)
            moveTo(right, bottom - cornerLength)
            lineTo(right, bottom - corner)
            quadraticTo(right, bottom, right - corner, bottom)
            lineTo(right - cornerLength, bottom)
            moveTo(left + cornerLength, bottom)
            lineTo(left + corner, bottom)
            quadraticTo(left, bottom, left, bottom - corner)
            lineTo(left, bottom - cornerLength)
        }
        drawPath(guides, Color.Black.copy(alpha = 0.4f), style = Stroke(width = borderWidth.toPx() + 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(guides, frameColor, style = Stroke(width = borderWidth.toPx(), cap = StrokeCap.Round))

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
