package com.auralis.crisisconnect.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(navController: NavController) {
    val context = LocalContext.current
    val viewModel: NewChatViewModel = viewModel()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBluetoothPermission()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.restoreBluetoothSettings()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshBluetoothPermission()
    }

    val bluetoothPermissions = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            else -> emptyArray()
        }
    }

    val permissionGranted by viewModel.permissionGranted.collectAsState()
    val sessionCode by viewModel.sessionCode.collectAsState()
    val aesKey by viewModel.aesKey.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val isDiscoverable by viewModel.isDiscoverable.collectAsState()
    val qrShareRequested by viewModel.qrShareRequested.collectAsState()
    val qrShareId by viewModel.qrShareId.collectAsState()
    val shouldObscureQr = qrShareRequested && (!isDiscoverable || !permissionGranted)
    val qrBlockReason = when {
        !permissionGranted && bluetoothPermissions.isNotEmpty() ->
            stringResource(R.string.qr_overlay_error_missing_permissions)

        !isDiscoverable ->
            stringResource(R.string.qr_overlay_error_not_discoverable)

        else -> stringResource(R.string.qr_overlay_error_generic)
    }
    val qrActionLabel = when {
        !permissionGranted && bluetoothPermissions.isNotEmpty() ->
            stringResource(R.string.request_bluetooth_permissions_button)

        !isDiscoverable ->
            stringResource(R.string.qr_permission_overlay_action)

        else ->
            stringResource(R.string.qr_permission_overlay_retry)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val grantedDuration = result.resultCode
        viewModel.setDiscoverable(grantedDuration > 0)
    }

    var showPermissionRequiredDialog by rememberSaveable { mutableStateOf(false) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        viewModel.refreshBluetoothPermission()
        showPermissionRequiredDialog = grantResults.values.any { granted -> !granted } &&
            !viewModel.permissionGranted.value
    }

    val launchDiscoverable: () -> Unit = {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        launcher.launch(intent)
    }

    val manualMac by viewModel.manualMacAddress.collectAsState()
    val manualName by viewModel.manualRemoteName.collectAsState()
    val manualSessionCode by viewModel.manualSessionCode.collectAsState()
    val manualConnectState by viewModel.manualConnectState.collectAsState()
    val navigateToMain by viewModel.navigateToMain.collectAsState()
    var showManualDialog by rememberSaveable { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    var hasAttemptedEnable by rememberSaveable { mutableStateOf(false) }

    val onAllowConnectionsClick = {
        hasAttemptedEnable = true
        viewModel.refreshBluetoothPermission()
        val currentPermissionGranted = viewModel.permissionGranted.value

        when {
            !currentPermissionGranted && bluetoothPermissions.isNotEmpty() -> {
                showPermissionRequiredDialog = false
                bluetoothPermissionLauncher.launch(bluetoothPermissions)
            }

            else -> {
                launchDiscoverable()
            }
        }
    }

    val onCreateQrClick = {
        viewModel.requestQrShare()
        if (permissionGranted && isDiscoverable) {
            viewModel.activateQrShareIfReady()
        } else {
            onAllowConnectionsClick()
        }
    }

    LaunchedEffect(qrShareRequested, permissionGranted, isDiscoverable) {
        if (qrShareRequested && permissionGranted && isDiscoverable) {
            viewModel.activateQrShareIfReady()
        }
        if (!shouldObscureQr) {
            hasAttemptedEnable = false
        }
    }

    LaunchedEffect(manualConnectState) {
        if (manualConnectState is ManualConnectState.Success) {
            showManualDialog = false
            navController.navigate("main") {
                popUpTo("main") { inclusive = true }
            }
            viewModel.resetManualConnectState()
        }
    }

    LaunchedEffect(navigateToMain) {
        if (navigateToMain) {
            viewModel.consumeNavigateToMain()
            navController.navigate("main") {
                popUpTo("main") { inclusive = true }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_chat_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.new_chat_info_button)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { innerPadding ->
        val primaryColor = MaterialTheme.colorScheme.primary
        val qrBitmap = remember(sessionCode, aesKey, userName, primaryColor, context, qrShareId) {
            val bluetoothName = currentBluetoothName(context)
            val json = JSONObject().apply {
                put("v", 1)
                put("code", sessionCode)
                put("key", aesKey)
                put("platform", "android")
                put("bleFallbackCapable", true)
                if (userName.isNotBlank()) {
                    put("name", userName)
                }
                if (bluetoothName.isNotBlank()) {
                    put("bluetoothName", bluetoothName)
                }
                if (qrShareId.isNotBlank()) {
                    put("shareId", qrShareId)
                }
            }.toString()
            val qrText = "dcs://${Uri.encode(json)}"
            generateQrBitmap(
                text = qrText,
                moduleColor = primaryColor.toArgb(),
                context = context,
                logoScale = 0.2f
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.your_qr_code),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!qrShareRequested) {
                            Text(
                                text = stringResource(R.string.new_chat_prepare_qr_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Button(
                                onClick = onCreateQrClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.new_chat_prepare_qr_action),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(230.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = stringResource(R.string.bluetooth_qr_description),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (shouldObscureQr) Modifier.blur(32.dp) else Modifier)
                                )

                                if (shouldObscureQr) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = qrBlockReason,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center
                                            )
                                            Button(
                                                onClick = onAllowConnectionsClick,
                                                shape = RoundedCornerShape(999.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Bluetooth,
                                                    contentDescription = null
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = qrActionLabel,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                            }
                                            if (hasAttemptedEnable) {
                                                OutlinedButton(
                                                    onClick = onAllowConnectionsClick,
                                                    shape = RoundedCornerShape(999.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = stringResource(R.string.qr_permission_overlay_retry),
                                                        style = MaterialTheme.typography.labelLarge
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                text = stringResource(R.string.qr_scan_info),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.session_code_label, sessionCode),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                            if (!permissionGranted && bluetoothPermissions.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        bluetoothPermissionLauncher.launch(bluetoothPermissions)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.request_bluetooth_permissions_button),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { navController.navigate("qr_scan") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.scan_qr),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                OutlinedButton(
                    onClick = {
                        viewModel.resetManualConnectState()
                        showManualDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.manual_connect_open_button),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }

    if (showManualDialog) {
        ManualConnectDialog(
            manualMac = manualMac,
            manualSessionCode = manualSessionCode,
            manualName = manualName,
            loading = manualConnectState is ManualConnectState.Loading,
            errorMessage = (manualConnectState as? ManualConnectState.Error)?.message,
            onMacChange = viewModel::updateManualMac,
            onSessionCodeChange = viewModel::updateManualSessionCode,
            onNameChange = viewModel::updateManualName,
            onDismiss = {
                showManualDialog = false
                viewModel.resetManualConnectState()
            },
            onConfirm = viewModel::connectToManualContact
        )
    }

    if (showInfo) {
        NewChatInfoSheet(onDismiss = { showInfo = false })
    }

    if (showPermissionRequiredDialog && !permissionGranted && bluetoothPermissions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showPermissionRequiredDialog = false },
            title = { Text(stringResource(R.string.bluetooth_permissions_required_title)) },
            text = { Text(stringResource(R.string.bluetooth_permissions_required_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionRequiredDialog = false
                        bluetoothPermissionLauncher.launch(bluetoothPermissions)
                    }
                ) {
                    Text(stringResource(R.string.qr_permission_overlay_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRequiredDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Suppress("UNUSED_PARAMETER")
fun generateQrBitmap(
    text: String,
    size: Int = 512,
    moduleColor: Int = android.graphics.Color.BLACK,
    logoScale: Float = 0.2f,
    context: android.content.Context,
    logoResId: Int = R.drawable.dcslogo
): ImageBitmap {
    val writer = QRCodeWriter()
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 1
    )
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val qrBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(qrBitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val moduleSize = size.toFloat() / width

    val gradientPaint = Paint().apply {
        shader = LinearGradient(
            0f,
            0f,
            size.toFloat(),
            size.toFloat(),
            intArrayOf(
                android.graphics.Color.parseColor("#2196F3"),
                android.graphics.Color.parseColor("#0D47A1")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    val finderPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#001E3C")
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    val whitePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    fun findFinderStart(searchX: IntProgression, searchY: IntProgression): Pair<Int, Int>? {
        for (y in searchY) {
            for (x in searchX) {
                if (bitMatrix[x, y]) {
                    return x to y
                }
            }
        }
        return null
    }

    val finderSizeModules = 7
    val topLeftFinder = findFinderStart(0 until width, 0 until height)
    val topRightFinder = findFinderStart((width - 1 downTo 0), 0 until height)
    val bottomLeftFinder = findFinderStart(0 until width, (height - 1 downTo 0))
    val finderPositions = listOfNotNull(topLeftFinder, topRightFinder, bottomLeftFinder)

    fun isInFinderArea(x: Int, y: Int, start: Pair<Int, Int>): Boolean {
        val (startX, startY) = start
        return x in startX until (startX + finderSizeModules) &&
                y in startY until (startY + finderSizeModules)
    }

    for (x in 0 until width) {
        for (y in 0 until height) {
            if (!bitMatrix[x, y]) continue
            val isFinderModule = finderPositions.any { isInFinderArea(x, y, it) }
            if (isFinderModule) continue
            val left = x * moduleSize
            val top = y * moduleSize
            val right = left + moduleSize
            val bottom = top + moduleSize
            canvas.drawRect(left, top, right, bottom, gradientPaint)
        }
    }

    fun drawFinder(start: Pair<Int, Int>) {
        val (startX, startY) = start
        val centerX = (startX + finderSizeModules / 2f) * moduleSize
        val centerY = (startY + finderSizeModules / 2f) * moduleSize

        val outerRadius = finderSizeModules * moduleSize / 2f
        canvas.drawCircle(centerX, centerY, outerRadius, finderPaint)

        val whiteRadius = (finderSizeModules - 2f) * moduleSize / 2f
        canvas.drawCircle(centerX, centerY, whiteRadius, whitePaint)

        val innerRadius = (finderSizeModules - 4f) * moduleSize / 2f
        canvas.drawCircle(centerX, centerY, innerRadius, finderPaint)
    }

    finderPositions.forEach { drawFinder(it) }

    val logoBitmap = BitmapFactory.decodeResource(context.resources, logoResId)
    if (logoBitmap != null && logoScale > 0f) {
        val maxLogoSide = (size * logoScale).roundToInt().coerceIn(1, size)
        val (scaledWidth, scaledHeight) = if (logoBitmap.width >= logoBitmap.height && logoBitmap.width != 0) {
            val height = (maxLogoSide * (logoBitmap.height.toFloat() / logoBitmap.width.toFloat())).roundToInt().coerceAtLeast(1)
            maxLogoSide to height
        } else if (logoBitmap.height != 0) {
            val width = (maxLogoSide * (logoBitmap.width.toFloat() / logoBitmap.height.toFloat())).roundToInt().coerceAtLeast(1)
            width to maxLogoSide
        } else {
            null
        } ?: Pair(maxLogoSide, maxLogoSide)
        val scaledLogo = Bitmap.createScaledBitmap(logoBitmap, scaledWidth, scaledHeight, true)
        val canvas = Canvas(qrBitmap)
        val centerX = size / 2f
        val centerY = size / 2f
        val halfLogoWidth = scaledLogo.width / 2f
        val halfLogoHeight = scaledLogo.height / 2f
        val left = centerX - halfLogoWidth
        val top = centerY - halfLogoHeight
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
        val padding = 8f
        canvas.drawRoundRect(
            left - padding,
            top - padding,
            left + scaledLogo.width + padding,
            top + scaledLogo.height + padding,
            20f,
            20f,
            paint
        )
        canvas.drawBitmap(scaledLogo, left, top, null)
    }

    return qrBitmap.asImageBitmap()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatInfoSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.new_chat_info_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.new_chat_info_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NewChatInfoSection(
                title = stringResource(R.string.new_chat_info_section_prepare_title),
                description = stringResource(R.string.new_chat_info_section_prepare_body)
            )
            NewChatInfoSection(
                title = stringResource(R.string.new_chat_info_section_share_title),
                description = stringResource(R.string.new_chat_info_section_share_body)
            )
            NewChatInfoSection(
                title = stringResource(R.string.new_chat_info_section_manual_title),
                description = stringResource(R.string.new_chat_info_section_manual_body)
            )
            Text(
                text = stringResource(R.string.new_chat_info_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NewChatInfoSection(
    title: String,
    description: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ManualConnectDialog(
    manualMac: String,
    manualSessionCode: String,
    manualName: String,
    loading: Boolean,
    errorMessage: String?,
    onMacChange: (String) -> Unit,
    onSessionCodeChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_connect_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = manualMac,
                    onValueChange = onMacChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manual_connect_mac_hint)) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = manualSessionCode,
                    onValueChange = onSessionCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manual_connect_session_hint)) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = manualName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manual_connect_name_hint)) },
                    singleLine = true
                )

                if (!errorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = manualMac.isNotBlank() && manualSessionCode.isNotBlank() && !loading
            ) {
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.manual_connect_searching),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    Text(stringResource(R.string.manual_connect_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun currentBluetoothName(context: Context): String {
    val hasBluetoothConnectPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    if (!hasBluetoothConnectPermission) {
        return ""
    }
    return runCatching {
        BluetoothAdapter.getDefaultAdapter()?.name?.trim().orEmpty()
    }.getOrDefault("")
}
