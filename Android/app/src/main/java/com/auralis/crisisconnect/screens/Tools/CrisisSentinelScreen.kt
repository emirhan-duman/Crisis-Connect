package com.auralis.crisisconnect.screens.Tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ai.CrisisSentinelChatMessage
import com.auralis.crisisconnect.ai.CrisisSentinelChatRole
import com.auralis.crisisconnect.ai.CrisisSentinelConversationSummary
import com.auralis.crisisconnect.ai.CrisisSentinelModelAvailability
import com.auralis.crisisconnect.ai.CrisisSentinelModelStatus
import com.auralis.crisisconnect.ai.CrisisSentinelResponseSource
import com.auralis.crisisconnect.ai.CrisisSentinelUserMode
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Gemma attribution links, shown compactly in the Crisis Sentinel info sheet (Gemma Terms require
// surfacing these); moved here from the settings screen.
private const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"
private const val GEMMA_PROHIBITED_USE_URL = "https://ai.google.dev/gemma/prohibited_use_policy"

private fun openCrisisSentinelUrl(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrisisSentinelScreen(navController: NavController) {
    val viewModel: CrisisSentinelViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val transientMessage = uiState.transientMessageRes
    val context = LocalContext.current
    var showMobileDataWarning by remember { mutableStateOf(false) }
    var showDeleteModelConfirm by remember { mutableStateOf(false) }
    val requestModelDownload = {
        if (context.shouldConfirmCrisisSentinelDownload()) {
            showMobileDataWarning = true
        } else {
            viewModel.requestModelDownload()
        }
    }

    if (showMobileDataWarning) {
        MobileDataDownloadDialog(
            onDismiss = { showMobileDataWarning = false },
            onConfirm = {
                showMobileDataWarning = false
                viewModel.requestModelDownload()
            }
        )
    }

    if (showDeleteModelConfirm) {
        DeleteModelConfirmationDialog(
            onDismiss = { showDeleteModelConfirm = false },
            onConfirm = {
                showDeleteModelConfirm = false
                viewModel.deleteDownloadedModel()
            }
        )
    }

    if (transientMessage != null) {
        val message = stringResource(transientMessage)
        LaunchedEffect(transientMessage) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientMessage()
        }
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_crisis_sentinel_title,
                onNavigateBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = viewModel::refreshModelStatus) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.crisis_sentinel_refresh_status)
                        )
                    }
                }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                IntroCard()
            }
            item {
                ModelStatusCard(
                    status = uiState.modelStatus,
                    downloadState = uiState.modelDownloadState,
                    isResolvingModelRelease = uiState.isResolvingModelRelease,
                    onDownloadClicked = requestModelDownload,
                    onDeleteClicked = { showDeleteModelConfirm = true }
                )
            }
            item {
                DownloadOnlyCard(
                    isModelReady = uiState.modelStatus.isReady,
                    onOpenMainMenu = {
                        navController.navigate("main") {
                            launchSingleTop = true
                            popUpTo("main") { inclusive = false }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Lets the nav transitions know a swipe-back gesture already animated the chat off-screen (and
 * showed the list underneath), so the pop should not replay slide animations on top of it.
 */
internal object CrisisSentinelSwipeBackCoordinator {
    @Volatile
    private var armedAtMillis = 0L

    fun arm() {
        armedAtMillis = System.currentTimeMillis()
    }

    fun isArmed(): Boolean = System.currentTimeMillis() - armedAtMillis < 600L
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrisisSentinelHomeScreen(navController: NavController) {
    val viewModel: CrisisSentinelViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showInfo by remember { mutableStateOf(false) }
    var showDeleteModelConfirm by remember { mutableStateOf(false) }
    var conversationPendingDelete by remember { mutableStateOf<CrisisSentinelConversationSummary?>(null) }
    val openNewConversation = {
        navController.navigate("crisis_sentinel_chat/$CRISIS_SENTINEL_NEW_CONVERSATION_ID")
    }

    LaunchedEffect(Unit) {
        viewModel.refreshRoleAccess()
        viewModel.refreshModelStatus()
        viewModel.refreshConversations()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshRoleAccess()
                viewModel.refreshModelStatus()
                viewModel.refreshConversations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (showInfo) {
        val infoContext = LocalContext.current
        CrisisSentinelInfoSheet(
            modelStatus = uiState.modelStatus,
            onDismiss = { showInfo = false },
            onDeleteModel = {
                showInfo = false
                showDeleteModelConfirm = true
            },
            onOpenTerms = { openCrisisSentinelUrl(infoContext, GEMMA_TERMS_URL) },
            onOpenPolicy = { openCrisisSentinelUrl(infoContext, GEMMA_PROHIBITED_USE_URL) }
        )
    }

    if (showDeleteModelConfirm) {
        DeleteModelConfirmationDialog(
            onDismiss = { showDeleteModelConfirm = false },
            onConfirm = {
                showDeleteModelConfirm = false
                viewModel.deleteDownloadedModel()
            }
        )
    }

    conversationPendingDelete?.let { conversation ->
        DeleteConversationConfirmationDialog(
            onDismiss = { conversationPendingDelete = null },
            onConfirm = {
                conversationPendingDelete = null
                viewModel.deleteConversation(conversation.id)
            }
        )
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_crisis_sentinel_title,
                onNavigateBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.info)
                        )
                    }
                    IconButton(onClick = { navController.navigate("crisis_sentinel_settings") }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.crisis_sentinel_settings)
                        )
                    }
                }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        },
        floatingActionButton = {
            if (uiState.modelStatus.isReady) {
                ExtendedFloatingActionButton(
                    onClick = openNewConversation,
                    icon = { Icon(imageVector = Icons.Filled.Add, contentDescription = null) },
                    text = { Text(text = stringResource(R.string.crisis_sentinel_new_chat)) }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        if (uiState.modelStatus.isReady) {
            if (uiState.conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyConversationState()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 18.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.crisis_sentinel_recent_chats),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(
                        items = uiState.conversations,
                        key = { it.id }
                    ) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onClick = { navController.navigate("crisis_sentinel_chat/${conversation.id}") },
                            onLongPress = { conversationPendingDelete = conversation }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 18.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    ModelNotReadyCard(onOpenDownload = { navController.navigate("crisis_sentinel") })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrisisSentinelSettingsScreen(navController: NavController) {
    val viewModel: CrisisSentinelViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showMobileDataWarning by remember { mutableStateOf(false) }
    var showDeleteModelConfirm by remember { mutableStateOf(false) }
    val requestModelDownload = {
        if (context.shouldConfirmCrisisSentinelDownload()) {
            showMobileDataWarning = true
        } else {
            viewModel.requestModelDownload()
        }
    }

    if (showMobileDataWarning) {
        MobileDataDownloadDialog(
            onDismiss = { showMobileDataWarning = false },
            onConfirm = {
                showMobileDataWarning = false
                viewModel.requestModelDownload()
            }
        )
    }

    if (showDeleteModelConfirm) {
        DeleteModelConfirmationDialog(
            onDismiss = { showDeleteModelConfirm = false },
            onConfirm = {
                showDeleteModelConfirm = false
                viewModel.deleteDownloadedModel()
            }
        )
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.crisis_sentinel_settings,
                onNavigateBack = { navController.popBackStack() }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        LaunchedEffect(Unit) {
            viewModel.refreshRoleAccess()
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ModelStatusCard(
                    status = uiState.modelStatus,
                    downloadState = uiState.modelDownloadState,
                    isResolvingModelRelease = uiState.isResolvingModelRelease,
                    onDownloadClicked = requestModelDownload,
                    onDeleteClicked = { showDeleteModelConfirm = true }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrisisSentinelChatScreen(
    navController: NavController,
    conversationId: String
) {
    val viewModel: CrisisSentinelViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val transientMessage = uiState.transientMessageRes
    val swipeBackThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val swipeBackOffset = remember { Animatable(0f) }
    val swipeBackScope = rememberCoroutineScope()
    val swipeHaptic = LocalHapticFeedback.current
    var swipeContainerWidthPx by remember { mutableStateOf(0f) }
    var isSwipeBackExiting by remember { mutableStateOf(false) }
    var swipeThresholdReached by remember { mutableStateOf(false) }
    val chatListState = rememberLazyListState()
    var showNewResponseChip by remember { mutableStateOf(false) }
    val isChatListAtBottom by remember {
        derivedStateOf {
            val info = chatListState.layoutInfo
            info.totalItemsCount == 0 ||
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 1
        }
    }
    val chatMessages = uiState.activeConversation?.messages.orEmpty()
    val photoOcrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let(viewModel::importImageForOcr)
    }
    val textFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::importTextFile)
    }
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            viewModel.appendVoiceDictation(spoken)
        }
    }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Crisis Sentinel")
        }
    }

    LaunchedEffect(conversationId) {
        viewModel.openConversation(conversationId)
    }

    // Smart scroll: follow the conversation only when the user is already at the bottom (or just
    // sent a message); otherwise offer a "new reply" chip instead of yanking the list.
    LaunchedEffect(chatMessages.size, uiState.isGenerating) {
        if (chatMessages.isEmpty()) return@LaunchedEffect
        val lastIsUser = chatMessages.last().role == CrisisSentinelChatRole.User
        if (lastIsUser || isChatListAtBottom) {
            val targetIndex = chatMessages.lastIndex + if (uiState.isGenerating) 1 else 0
            chatListState.animateScrollToItem(targetIndex.coerceAtLeast(0))
            showNewResponseChip = false
        } else if (!uiState.isGenerating) {
            showNewResponseChip = true
        }
    }

    LaunchedEffect(isChatListAtBottom) {
        if (isChatListAtBottom) {
            showNewResponseChip = false
        }
    }

    DisposableEffect(conversationId) {
        onDispose {
            viewModel.persistDraft(conversationId)
        }
    }

    if (transientMessage != null) {
        val message = stringResource(transientMessage)
        LaunchedEffect(transientMessage) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { swipeContainerWidthPx = it.width.toFloat() }
            .pointerInput(swipeBackThresholdPx) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        if (!isSwipeBackExiting && (dragAmount > 0f || swipeBackOffset.value > 0f)) {
                            change.consume()
                            val next = (swipeBackOffset.value + dragAmount).coerceAtLeast(0f)
                            // Tick once when the drag crosses the back threshold (iOS-like feedback).
                            val width = swipeContainerWidthPx
                            val crossed = next > swipeBackThresholdPx ||
                                (width > 0f && next > width * 0.35f)
                            if (crossed && !swipeThresholdReached) {
                                swipeThresholdReached = true
                                swipeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else if (!crossed && swipeThresholdReached) {
                                swipeThresholdReached = false
                            }
                            swipeBackScope.launch { swipeBackOffset.snapTo(next) }
                        }
                    },
                    onDragEnd = {
                        swipeThresholdReached = false
                        val width = swipeContainerWidthPx
                        val shouldPop = !isSwipeBackExiting && width > 0f &&
                            (
                                swipeBackOffset.value > swipeBackThresholdPx ||
                                    swipeBackOffset.value > width * 0.35f
                                )
                        if (shouldPop) {
                            // Finish the slide under the finger's momentum, then pop; the nav
                            // pop-exit runs on an already off-screen surface, so only the home
                            // screen's pop-enter slide remains visible — one continuous motion.
                            isSwipeBackExiting = true
                            swipeBackScope.launch {
                                swipeBackOffset.animateTo(
                                    targetValue = width,
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        easing = FastOutLinearInEasing
                                    )
                                )
                                CrisisSentinelSwipeBackCoordinator.arm()
                                navController.popBackStack()
                            }
                        } else if (!isSwipeBackExiting) {
                            swipeBackScope.launch {
                                swipeBackOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        swipeThresholdReached = false
                        if (!isSwipeBackExiting) {
                            swipeBackScope.launch {
                                swipeBackOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    }
                )
            }
    ) {
        // iOS-style swipe-back: while dragging, render the conversation list live underneath with
        // a parallax slide and a dim that lifts as the chat moves away.
        if (swipeBackOffset.value > 0.5f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val width = swipeContainerWidthPx.takeIf { it > 0f } ?: size.width
                        val progress = (swipeBackOffset.value / width).coerceIn(0f, 1f)
                        translationX = -(1f - progress) * width * 0.3f
                    }
            ) {
                CrisisSentinelHomeScreen(navController)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val width = swipeContainerWidthPx.takeIf { it > 0f } ?: size.width
                            alpha = (1f - (swipeBackOffset.value / width)).coerceIn(0f, 1f)
                        }
                        .background(Color.Black.copy(alpha = 0.3f))
                )
            }
        }
        Scaffold(
            modifier = Modifier.graphicsLayer { translationX = swipeBackOffset.value },
            topBar = {
                AppBackTopBar(
                    title = uiState.activeConversation?.title
                        ?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.crisis_sentinel_new_chat),
                    onNavigateBack = { navController.popBackStack() }
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            },
            // Don't let the Scaffold consume the bottom (nav-bar) inset — the floating composer
            // handles it via navigationBarsPadding(); otherwise the input slides under the system
            // navigation buttons.
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            // ChatGPT-style: messages scroll edge-to-edge UNDER a floating composer that fades them
            // out at its top edge — no flat box below the input bar.
            var composerHeightPx by remember { mutableStateOf(0) }
            val composerHeightDp = with(LocalDensity.current) { composerHeightPx.toDp() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                val messages = uiState.activeConversation?.messages.orEmpty()
                if (messages.isEmpty() && !uiState.isGenerating) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = composerHeightDp),
                        contentAlignment = Alignment.Center
                    ) {
                        ChatEmptyStateCard(
                            mode = uiState.mode,
                            onSuggestionClick = { suggestion ->
                                viewModel.onPromptChanged(suggestion)
                                viewModel.submitChatMessage(conversationId)
                            }
                        )
                    }
                } else {
                    val lastMessageId = messages.lastOrNull()?.id
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = chatListState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 18.dp,
                            // Reserve room so the last message clears the floating composer.
                            bottom = (composerHeightDp + 8.dp).coerceAtLeast(24.dp)
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = messages,
                            key = { it.id }
                        ) { message ->
                            ChatMessageBubble(
                                message = message,
                                canRegenerate = message.role == CrisisSentinelChatRole.Assistant &&
                                    message.id == lastMessageId &&
                                    !uiState.isGenerating,
                                onRegenerate = viewModel::regenerateLastResponse
                            )
                        }
                        if (uiState.isGenerating) {
                            item {
                                AssistantThinkingBubble()
                            }
                        }
                    }
                }
                if (showNewResponseChip) {
                    Surface(
                        onClick = {
                            showNewResponseChip = false
                            swipeBackScope.launch {
                                val target = chatListState.layoutInfo.totalItemsCount - 1
                                chatListState.animateScrollToItem(target.coerceAtLeast(0))
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = (composerHeightDp + 8.dp).coerceAtLeast(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "↓  " + stringResource(R.string.crisis_sentinel_new_response),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                ChatComposer(
                    prompt = uiState.prompt,
                    isGenerating = uiState.isGenerating,
                    isProcessingInput = uiState.isProcessingInput,
                    showPerformanceWarning = uiState.showPerformanceWarning,
                    onPromptChanged = viewModel::onPromptChanged,
                    onSubmit = { viewModel.submitChatMessage(conversationId) },
                    onCancelGeneration = viewModel::cancelGeneration,
                    onPhotoOcrClicked = { photoOcrLauncher.launch("image/*") },
                    onTextFileClicked = {
                        textFileLauncher.launch(
                            arrayOf(
                                "text/*",
                                "application/json"
                            )
                        )
                    },
                    onVoiceClicked = {
                        runCatching { speechLauncher.launch(speechIntent) }
                            .onFailure { viewModel.markInputUnavailable() }
                    },
                    modelReady = uiState.modelStatus.isReady,
                    isDownloading = uiState.modelDownloadState.isActive,
                    onDownloadModel = { viewModel.requestModelDownload() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { composerHeightPx = it.height }
                )
            }
        }
    }
}

@Composable
private fun DownloadOnlyCard(
    isModelReady: Boolean,
    onOpenMainMenu: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isModelReady) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isModelReady) Icons.Filled.CheckCircle else Icons.Filled.CloudDownload,
                    contentDescription = null,
                    tint = if (isModelReady) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Text(
                    text = stringResource(
                        if (isModelReady) {
                            R.string.crisis_sentinel_download_ready_title
                        } else {
                            R.string.crisis_sentinel_download_only_title
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(
                    if (isModelReady) {
                        R.string.crisis_sentinel_download_ready_body
                    } else {
                        R.string.crisis_sentinel_download_only_body
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onOpenMainMenu,
                enabled = isModelReady,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.crisis_sentinel_open_main_menu))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrisisSentinelInfoSheet(
    modelStatus: CrisisSentinelModelStatus,
    onDismiss: () -> Unit,
    onDeleteModel: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPolicy: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.crisis_sentinel_info_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            CrisisSentinelInfoSection(
                title = stringResource(R.string.crisis_sentinel_info_heading_usage),
                body = stringResource(R.string.crisis_sentinel_info_body_usage)
            )
            CrisisSentinelInfoSection(
                title = stringResource(R.string.crisis_sentinel_info_heading_offline_model),
                body = stringResource(R.string.crisis_sentinel_info_body_offline_model)
            )
            CrisisSentinelInfoSection(
                title = stringResource(R.string.crisis_sentinel_info_heading_safety),
                body = stringResource(R.string.crisis_sentinel_info_body_safety)
            )

            // Compact AI/Gemma attribution (moved here from Settings to keep settings clean while
            // still satisfying the Gemma Terms attribution requirement).
            HorizontalDivider()
            CrisisSentinelInfoSection(
                title = stringResource(R.string.settings_ai_notices_title),
                body = stringResource(R.string.settings_ai_notices_based_on_gemma) +
                    "\n\n" + stringResource(R.string.settings_ai_notices_not_affiliated)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onOpenTerms, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_ai_notices_terms_button))
                }
                TextButton(onClick = onOpenPolicy, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_ai_notices_policy_button))
                }
            }

            if (modelStatus.isReady) {
                HorizontalDivider()
                CrisisSentinelInfoSection(
                    title = stringResource(R.string.crisis_sentinel_info_heading_storage),
                    body = stringResource(
                        R.string.crisis_sentinel_info_body_storage,
                        formatBytes(modelStatus.bytes)
                    )
                )
                OutlinedButton(
                    onClick = onDeleteModel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                    Text(
                        text = stringResource(R.string.crisis_sentinel_model_delete),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CrisisSentinelInfoSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeleteModelConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.crisis_sentinel_model_delete_title)) },
        text = { Text(text = stringResource(R.string.crisis_sentinel_model_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.crisis_sentinel_model_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DeleteConversationConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.crisis_sentinel_chat_delete_title)) },
        text = { Text(text = stringResource(R.string.crisis_sentinel_chat_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.crisis_sentinel_chat_delete_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun MobileDataDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.crisis_sentinel_mobile_data_title)) },
        text = { Text(text = stringResource(R.string.crisis_sentinel_mobile_data_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.crisis_sentinel_mobile_data_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun CrisisSentinelHomeHero(
    isModelReady: Boolean,
    onNewChat: () -> Unit,
    onOpenDownload: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_tool_crisis_sentinel_shine),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tool_crisis_sentinel_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.crisis_sentinel_home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            if (isModelReady) {
                Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.crisis_sentinel_new_chat),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else {
                OutlinedButton(onClick = onOpenDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.CloudDownload, contentDescription = null)
                    Text(
                        text = stringResource(R.string.crisis_sentinel_model_download),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversationState() {
    Column(
        modifier = Modifier.widthIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.crisis_sentinel_no_chats_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.crisis_sentinel_no_chats_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ModelNotReadyCard(onOpenDownload: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.crisis_sentinel_model_answer_not_ready),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.crisis_sentinel_model_fallback),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onOpenDownload, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.crisis_sentinel_model_download))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ConversationRow(
    conversation: CrisisSentinelConversationSummary,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val locale = Locale.getDefault()
    val updatedLabel = remember(conversation.updatedAtMillis, locale) {
        if (conversation.updatedAtMillis > 0L) {
            SimpleDateFormat("d MMM", locale).format(Date(conversation.updatedAtMillis))
        } else {
            ""
        }
    }
    val preview = conversation.lastMessagePreview
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.crisis_sentinel_empty_chat_preview)
    val previewText = if (conversation.isDraft) {
        stringResource(R.string.crisis_sentinel_draft_preview, preview)
    } else {
        preview
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress
                )
                .padding(horizontal = 2.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.crisis_sentinel_new_chat) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (updatedLabel.isNotBlank()) {
                        Text(
                            text = updatedLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (conversation.isDraft) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatEmptyStateCard(
    mode: CrisisSentinelUserMode = CrisisSentinelUserMode.Public,
    onSuggestionClick: (String) -> Unit = {}
) {
    val suggestions = if (mode == CrisisSentinelUserMode.Public) {
        listOf(
            stringResource(R.string.crisis_sentinel_suggest_public_1),
            stringResource(R.string.crisis_sentinel_suggest_public_2),
            stringResource(R.string.crisis_sentinel_suggest_public_3),
            stringResource(R.string.crisis_sentinel_suggest_public_4)
        )
    } else {
        listOf(
            stringResource(R.string.crisis_sentinel_suggest_field_1),
            stringResource(R.string.crisis_sentinel_suggest_field_2),
            stringResource(R.string.crisis_sentinel_suggest_field_3),
            stringResource(R.string.crisis_sentinel_suggest_field_4)
        )
    }
    Column(
        modifier = Modifier.widthIn(max = 380.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.crisis_sentinel_chat_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.crisis_sentinel_chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestions.forEach { suggestion ->
                SuggestionCard(
                    text = suggestion,
                    onClick = { onSuggestionClick(suggestion) }
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatMessageBubble(
    message: CrisisSentinelChatMessage,
    canRegenerate: Boolean = false,
    onRegenerate: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.chat_message_copied)
    var showContextMenu by remember { mutableStateOf(false) }
    val isUser = message.role == CrisisSentinelChatRole.User
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box {
            val bubbleInteraction = remember { MutableInteractionSource() }
            val contentModifier = if (isUser) {
                Modifier
                    .widthIn(max = 330.dp)
                    .background(bubbleColor, RoundedCornerShape(18.dp))
                    .combinedClickable(
                        onClick = {},
                        interactionSource = bubbleInteraction,
                        indication = null,
                        onLongClick = { showContextMenu = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            } else {
                // Assistant replies render as full-width plain text (no bubble), ChatGPT/Claude style.
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        interactionSource = bubbleInteraction,
                        indication = null,
                        onLongClick = { showContextMenu = true }
                    )
                    .padding(vertical = 2.dp)
            }
            Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                } else {
                    MarkdownLiteText(
                        text = message.text,
                        color = textColor
                    )
                }
                if (!isUser && message.source != null) {
                    val sourceLabel = stringResource(responseSourceLabel(message.source))
                    val durationLabel = message.generationDurationMillis
                        ?.takeIf { it > 0L }
                        ?.let { millis ->
                            stringResource(
                                R.string.crisis_sentinel_duration_seconds,
                                String.format(Locale.getDefault(), "%.1f", millis / 1000f)
                            )
                        }
                    Text(
                        text = listOfNotNull(sourceLabel, durationLabel).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.chat_action_copy)) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.text))
                        Toast
                            .makeText(context, copiedMessage, Toast.LENGTH_SHORT)
                            .show()
                        showContextMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.crisis_sentinel_action_share)) },
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message.text)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                        showContextMenu = false
                    }
                )
                if (canRegenerate) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.crisis_sentinel_action_regenerate)) },
                        onClick = {
                            showContextMenu = false
                            onRegenerate()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownLiteText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val formatted = remember(text) { buildMarkdownLiteAnnotatedString(text) }
    Text(
        text = formatted,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier
    )
}

/** Minimal markdown for model output: `**bold**`, `# headers`, and `-`/`*`/`•` bullets. */
private fun buildMarkdownLiteAnnotatedString(raw: String): AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        raw.split('\n').forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") -> {
                    withStyle(boldStyle) {
                        appendWithInlineBold(trimmed.trimStart('#').trim(), boldStyle)
                    }
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    append("•  ")
                    appendWithInlineBold(trimmed.drop(2).trimStart(), boldStyle)
                }

                else -> appendWithInlineBold(line, boldStyle)
            }
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendWithInlineBold(
    text: String,
    boldStyle: SpanStyle
) {
    var remaining = text
    while (true) {
        val start = remaining.indexOf("**")
        if (start < 0) {
            append(remaining)
            return
        }
        val end = remaining.indexOf("**", start + 2)
        if (end < 0) {
            append(remaining)
            return
        }
        append(remaining.substring(0, start))
        withStyle(boldStyle) {
            append(remaining.substring(start + 2, end))
        }
        remaining = remaining.substring(end + 2)
    }
}

@Composable
private fun AssistantThinkingBubble() {
    // Bubble-less to match the assistant message style; animated typing dots instead of a spinner.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypingDots()
        Text(
            text = stringResource(R.string.crisis_sentinel_generating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "crisis_sentinel_typing")
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val dotAlpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600,
                        delayMillis = index * 160,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "crisis_sentinel_dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .background(dotColor, CircleShape)
            )
        }
    }
}

@Composable
private fun ChatComposer(
    prompt: String,
    isGenerating: Boolean,
    isProcessingInput: Boolean,
    showPerformanceWarning: Boolean,
    onPromptChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancelGeneration: () -> Unit,
    onPhotoOcrClicked: () -> Unit,
    onTextFileClicked: () -> Unit,
    onVoiceClicked: () -> Unit,
    modelReady: Boolean = true,
    isDownloading: Boolean = false,
    onDownloadModel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val inputActionsEnabled = !isProcessingInput && !isGenerating
    val sendEnabled = isGenerating || prompt.isNotBlank()
    val helperMessage = when {
        isProcessingInput -> stringResource(R.string.crisis_sentinel_input_processing)
        isGenerating -> stringResource(R.string.crisis_sentinel_generation_working)
        showPerformanceWarning -> stringResource(R.string.crisis_sentinel_generation_performance_warning)
        else -> null
    }
    val barColor = MaterialTheme.colorScheme.background

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Lift above the keyboard when open, otherwise above the system nav bar.
            .imePadding()
    ) {
        // Soft fade so messages scrolling under the bar dissolve into the background (ChatGPT-style)
        // instead of ending at a hard edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, barColor))
                )
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Solid bar background runs down through the navigation-bar area so there is no
                // separate white box below the input field.
                .background(barColor)
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
        if (!modelReady) {
            // Persistent, in-place CTA instead of a transient snackbar when the offline model
            // hasn't been downloaded yet.
            FilledTonalButton(
                onClick = onDownloadModel,
                enabled = !isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Filled.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isDownloading) {
                        stringResource(R.string.crisis_sentinel_model_downloading_button)
                    } else {
                        stringResource(R.string.crisis_sentinel_model_download)
                    }
                )
            }
        } else {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        enabled = inputActionsEnabled,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.crisis_sentinel_inputs_title),
                            tint = if (inputActionsEnabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.crisis_sentinel_input_photo_ocr)) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Filled.Image, contentDescription = null)
                            },
                            enabled = inputActionsEnabled,
                            onClick = {
                                showAttachmentMenu = false
                                onPhotoOcrClicked()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.crisis_sentinel_input_file)) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Filled.Description, contentDescription = null)
                            },
                            enabled = inputActionsEnabled,
                            onClick = {
                                showAttachmentMenu = false
                                onTextFileClicked()
                            }
                        )
                    }
                }
                BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChanged,
                    enabled = !isGenerating,
                    minLines = 1,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (prompt.isNotBlank()) {
                                onSubmit()
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 132.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (prompt.isBlank()) {
                                Text(
                                    text = stringResource(R.string.crisis_sentinel_chat_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                IconButton(
                    onClick = onVoiceClicked,
                    enabled = inputActionsEnabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.crisis_sentinel_input_voice),
                        tint = if (inputActionsEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                        }
                    )
                }
                val sendButtonColor by animateColorAsState(
                    targetValue = when {
                        isGenerating -> MaterialTheme.colorScheme.errorContainer
                        prompt.isNotBlank() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    label = "crisis_sentinel_send_button_color"
                )
                IconButton(
                    onClick = {
                        if (isGenerating) {
                            onCancelGeneration()
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSubmit()
                        }
                    },
                    enabled = sendEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .background(sendButtonColor, CircleShape)
                ) {
                    if (isGenerating) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_cancel),
                            contentDescription = stringResource(R.string.crisis_sentinel_generation_cancel),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.crisis_sentinel_ask),
                            tint = if (prompt.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        }
        if (helperMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isProcessingInput) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = helperMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (isGenerating) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }
        }
    }
}

@Composable
private fun IntroCard() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_tool_crisis_sentinel_shine),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.tool_crisis_sentinel_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.crisis_sentinel_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    status: CrisisSentinelModelStatus,
    downloadState: CrisisSentinelModelDownloadState,
    isResolvingModelRelease: Boolean,
    onDownloadClicked: () -> Unit,
    onDeleteClicked: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.crisis_sentinel_model_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(text = stringResource(modelStatusLabel(status.availability))) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (status.isReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null
                        )
                    }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = stringResource(
                                R.string.crisis_sentinel_model_release,
                                status.release.displayName
                            )
                        )
                    }
                )
            }
            ModelStorageDetails(status = status)
            if (downloadState.isActive) {
                val total = downloadState.bytesTotal
                if (total != null && total > 0L) {
                    val progress = (downloadState.bytesDownloaded.toFloat() / total.toFloat())
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.crisis_sentinel_model_downloading_known,
                            formatBytes(downloadState.bytesDownloaded),
                            formatBytes(total)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(
                            R.string.crisis_sentinel_model_downloading_unknown,
                            formatBytes(downloadState.bytesDownloaded)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = stringResource(
                    if (status.isReady) {
                        R.string.crisis_sentinel_model_answer_ready
                    } else {
                        R.string.crisis_sentinel_model_fallback
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.isReady && onDeleteClicked != null) {
                OutlinedButton(
                    onClick = onDeleteClicked,
                    enabled = !downloadState.isActive && !isResolvingModelRelease,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                    Text(
                        text = stringResource(R.string.crisis_sentinel_model_delete),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onDownloadClicked,
                    enabled = status.availability != CrisisSentinelModelAvailability.InsufficientStorage &&
                        !isResolvingModelRelease &&
                        !downloadState.isActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.CloudDownload, contentDescription = null)
                    Text(
                        text = when {
                            downloadState.isActive -> stringResource(R.string.crisis_sentinel_model_downloading_button)
                            isResolvingModelRelease -> stringResource(R.string.crisis_sentinel_model_manifest_loading)
                            status.availability == CrisisSentinelModelAvailability.InsufficientStorage ->
                                stringResource(R.string.crisis_sentinel_model_storage_low)
                            else -> stringResource(R.string.crisis_sentinel_model_download)
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelStorageDetails(status: CrisisSentinelModelStatus) {
    val expectedBytes = status.release.expectedBytes
    val freeBytes = status.freeBytes
    val requiredBytes = status.requiredFreeBytes
    val missingBytes = freeBytes?.let { (requiredBytes - it).coerceAtLeast(0L) } ?: 0L
    val statusColor = if (missingBytes > 0L) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val installedBytes = status.bytes.takeIf { it > 0L }
        Text(
            text = when {
                installedBytes != null -> stringResource(
                    R.string.crisis_sentinel_model_size,
                    formatBytes(installedBytes)
                )
                expectedBytes != null -> stringResource(
                    R.string.crisis_sentinel_model_download_size,
                    formatBytes(expectedBytes)
                )
                else -> stringResource(R.string.crisis_sentinel_model_download_size_unknown)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(
                R.string.crisis_sentinel_model_required_space,
                formatBytes(requiredBytes)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (freeBytes != null) {
            Text(
                text = stringResource(
                    R.string.crisis_sentinel_model_available_space,
                    formatBytes(freeBytes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
            if (!status.isReady) {
                Text(
                    text = if (missingBytes > 0L) {
                        stringResource(
                            R.string.crisis_sentinel_model_space_short,
                            formatBytes(missingBytes)
                        )
                    } else {
                        stringResource(R.string.crisis_sentinel_model_space_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
        }
    }
}

private fun modelStatusLabel(availability: CrisisSentinelModelAvailability): Int {
    return when (availability) {
        CrisisSentinelModelAvailability.Ready -> R.string.crisis_sentinel_model_active
        CrisisSentinelModelAvailability.Missing -> R.string.crisis_sentinel_model_missing
        CrisisSentinelModelAvailability.Corrupt -> R.string.crisis_sentinel_model_corrupt
        CrisisSentinelModelAvailability.InsufficientStorage -> R.string.crisis_sentinel_model_storage_low
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.getDefault(), "%.1f GB", megabytes / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", megabytes)
    }
}

private fun Context.shouldConfirmCrisisSentinelDownload(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        connectivityManager.isActiveNetworkMetered
}

private fun responseSourceLabel(source: CrisisSentinelResponseSource): Int {
    return when (source) {
        CrisisSentinelResponseSource.LocalModel -> R.string.crisis_sentinel_source_local_model
        CrisisSentinelResponseSource.OfflineRules -> R.string.crisis_sentinel_source_offline_rules
    }
}
