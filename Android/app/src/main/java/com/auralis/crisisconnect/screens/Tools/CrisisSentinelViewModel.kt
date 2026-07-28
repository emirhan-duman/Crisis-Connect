package com.auralis.crisisconnect.screens.Tools

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ai.CrisisSentinelChatMessage
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.ai.CrisisSentinelChatRole
import com.auralis.crisisconnect.ai.CrisisSentinelCloudChatStore
import com.auralis.crisisconnect.ai.CrisisSentinelConversation
import com.auralis.crisisconnect.ai.CrisisSentinelConversationIds
import com.auralis.crisisconnect.ai.CrisisSentinelConversationStore
import com.auralis.crisisconnect.ai.CrisisSentinelConversationSummary
import com.auralis.crisisconnect.ai.CrisisSentinelEngine
import com.auralis.crisisconnect.ai.CrisisSentinelMapPoint
import com.auralis.crisisconnect.ai.CrisisSentinelOnlineModelChoice
import com.auralis.crisisconnect.ai.CrisisSentinelOnlineProvider
import com.auralis.crisisconnect.ai.CrisisSentinelInputProcessor
import com.auralis.crisisconnect.ai.CrisisSentinelLiteRtModelRuntime
import com.auralis.crisisconnect.ai.CrisisSentinelModelDownloadWorker
import com.auralis.crisisconnect.ai.CrisisSentinelModelAvailability
import com.auralis.crisisconnect.ai.CrisisSentinelModelFileStore
import com.auralis.crisisconnect.ai.CrisisSentinelModelManifestCache
import com.auralis.crisisconnect.ai.CrisisSentinelModelManifestClient
import com.auralis.crisisconnect.ai.CrisisSentinelModelRelease
import com.auralis.crisisconnect.ai.CrisisSentinelModelStatus
import com.auralis.crisisconnect.ai.CrisisSentinelOfflineEngine
import com.auralis.crisisconnect.ai.CrisisSentinelOnlineClient
import com.auralis.crisisconnect.ai.CrisisSentinelOnlineException
import com.auralis.crisisconnect.ai.CrisisSentinelOnlineMessage
import com.auralis.crisisconnect.ai.CrisisSentinelRequest
import com.auralis.crisisconnect.ai.CrisisSentinelResponse
import com.auralis.crisisconnect.ai.CrisisSentinelResponseSource
import com.auralis.crisisconnect.ai.CrisisSentinelText
import com.auralis.crisisconnect.ai.CrisisSentinelUserMode
import com.auralis.crisisconnect.security.FirebaseAppCheckFailures
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.NotificationLocalization
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val CRISIS_SENTINEL_NEW_CONVERSATION_ID = "new"

/** Whether online conversations can sync to the web dashboard's Firestore chats. */
enum class CrisisSentinelCloudSyncState {
    Unknown,
    Enabled,

    /** No panel key derivable from users/{uid} — online chats work but stay on-device. */
    NoPanel,

    /** Firestore rejected a read/write; treated like NoPanel until the next role refresh. */
    PermissionDenied
}

data class CrisisSentinelModelDownloadState(
    val isActive: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long? = null
)

data class CrisisSentinelUiState(
    val prompt: String = "",
    val mode: CrisisSentinelUserMode = CrisisSentinelUserMode.Public,
    val response: CrisisSentinelResponse? = null,
    val conversations: List<CrisisSentinelConversationSummary> = emptyList(),
    val activeConversation: CrisisSentinelConversation? = null,
    val modelStatus: CrisisSentinelModelStatus,
    val modelDownloadState: CrisisSentinelModelDownloadState = CrisisSentinelModelDownloadState(),
    val isGenerating: Boolean = false,
    val isResolvingModelRelease: Boolean = false,
    val isProcessingInput: Boolean = false,
    val showPerformanceWarning: Boolean = false,
    val availableModes: List<CrisisSentinelUserMode> = listOf(CrisisSentinelUserMode.Public),
    val certificateRole: String? = null,
    val transientMessageRes: Int? = null,
    val hasInternet: Boolean = false,
    val isOnlineEngineAllowed: Boolean = false,
    val selectedEngine: CrisisSentinelEngine = CrisisSentinelEngine.Edge,
    /** Partial online reply while it streams; null outside an online generation. */
    val streamingReply: String? = null,
    val cloudSyncState: CrisisSentinelCloudSyncState = CrisisSentinelCloudSyncState.Unknown,
    val onlineProviders: List<CrisisSentinelOnlineProvider> = emptyList(),
    val isLoadingProviders: Boolean = false,
    val providersLoadFailed: Boolean = false,
    /** The user's pinned online provider/model; null = server default. */
    val selectedOnlineModel: CrisisSentinelOnlineModelChoice? = null
) {
    val onlineEngineAvailable: Boolean get() = isOnlineEngineAllowed && hasInternet
    val edgeEngineAvailable: Boolean get() = modelStatus.isReady
    val canChat: Boolean get() = onlineEngineAvailable || edgeEngineAvailable

    /**
     * The engine a send would actually use: the selected one when it is available, otherwise
     * whichever engine still is. Null means no engine can answer ("AI unavailable").
     */
    val activeEngine: CrisisSentinelEngine?
        get() = when {
            selectedEngine == CrisisSentinelEngine.Online && onlineEngineAvailable ->
                CrisisSentinelEngine.Online
            selectedEngine == CrisisSentinelEngine.Edge && edgeEngineAvailable ->
                CrisisSentinelEngine.Edge
            onlineEngineAvailable -> CrisisSentinelEngine.Online
            edgeEngineAvailable -> CrisisSentinelEngine.Edge
            else -> null
        }
}

class CrisisSentinelViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CrisisSentinelModelFileStore(application)
    private val conversationStore = CrisisSentinelConversationStore(application)
    private val securityRepository = SecurityRepository(application.applicationContext)
    private val workManager = WorkManager.getInstance(application.applicationContext)
    private val downloadWorkLiveData by lazy {
        workManager.getWorkInfosForUniqueWorkLiveData(CrisisSentinelModelDownloadWorker.UNIQUE_WORK_NAME)
    }
    private val downloadWorkObserver = Observer<List<WorkInfo>> { workInfos ->
        handleModelDownloadWork(workInfos)
    }
    private val manifestClient = CrisisSentinelModelManifestClient()
    private val manifestCache = CrisisSentinelModelManifestCache(application)
    private var currentRelease: CrisisSentinelModelRelease =
        manifestCache.load() ?: CrisisSentinelModelFileStore.defaultRelease
    private var observedActiveDownload = false
    private var lastFinishedDownloadWorkId: UUID? = null
    private var generationJob: Job? = null
    private var generationTimeoutJob: Job? = null
    private val onlineClient = CrisisSentinelOnlineClient()
    private val cloudChatStore = CrisisSentinelCloudChatStore.getInstance(application)
    private var cloudSummaries: List<CrisisSentinelConversationSummary> = emptyList()
    private var cloudSummariesJob: Job? = null
    private var cloudMessagesJob: Job? = null
    private var activeGenerationEngine: CrisisSentinelEngine? = null
    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val runtime = CrisisSentinelLiteRtModelRuntime(
        context = application,
        store = store,
        releaseProvider = { currentRelease }
    )
    private val engine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CrisisSentinelOfflineEngine(
            modelRuntime = runtime,
            onModelFailure = { throwable ->
                Log.w(TAG, "Crisis Sentinel local model generation failed", throwable)
            },
            onModelRejected = { preview ->
                Log.w(TAG, "Crisis Sentinel local model output rejected. preview=${preview.take(160)}")
            }
        )
    }

    private val _uiState = MutableStateFlow(
        CrisisSentinelUiState(
            mode = conversationStore.defaultMode(),
            conversations = mergedSummaries(),
            modelStatus = currentModelStatus(),
            showPerformanceWarning = shouldShowModelPerformanceWarning(application),
            selectedOnlineModel = conversationStore.preferredOnlineModel()
        )
    )
    val uiState: StateFlow<CrisisSentinelUiState> = _uiState.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, _ ->
        _uiState.update {
            it.copy(
                isGenerating = false,
                isResolvingModelRelease = false,
                isProcessingInput = false,
                streamingReply = null,
                transientMessageRes = R.string.crisis_sentinel_error_response,
                modelStatus = currentModelStatus()
            )
        }
    }

    init {
        // Seed connectivity synchronously so the engine picker doesn't flash "online
        // unavailable" while waiting for the first network callback.
        _uiState.update { it.copy(hasInternet = hasUsableInternet()) }
        registerConnectivityWatcher()
        observeModelDownloadWork()
        refreshRoleAccess()
    }

    private fun hasUsableInternet(): Boolean {
        val manager = connectivityManager ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerConnectivityWatcher() {
        val manager = connectivityManager ?: return
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            // onAvailable fires before VALIDATED, so track capability changes instead.
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                updateInternetAvailability(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }

            override fun onLost(network: Network) {
                updateInternetAvailability(hasUsableInternet())
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "Could not register connectivity watcher", it) }
    }

    private fun updateInternetAvailability(hasInternet: Boolean) {
        if (_uiState.value.hasInternet == hasInternet) return
        _uiState.update { it.copy(hasInternet = hasInternet) }
        recomputeEngineSelection()
    }

    /**
     * Re-resolves [CrisisSentinelUiState.selectedEngine]: the persisted user choice wins while
     * that engine is available; otherwise fall back to whichever is (Online preferred). The
     * persisted preference itself is never overwritten here, so a temporary outage doesn't
     * erase the user's choice.
     */
    private fun recomputeEngineSelection() {
        _uiState.update { state ->
            val preferred = conversationStore.preferredEngine()
            val selected = when {
                preferred == CrisisSentinelEngine.Online && state.onlineEngineAvailable ->
                    CrisisSentinelEngine.Online
                preferred == CrisisSentinelEngine.Edge && state.edgeEngineAvailable ->
                    CrisisSentinelEngine.Edge
                state.onlineEngineAvailable -> CrisisSentinelEngine.Online
                else -> CrisisSentinelEngine.Edge
            }
            if (state.selectedEngine == selected) state else state.copy(selectedEngine = selected)
        }
    }

    fun selectEngine(engine: CrisisSentinelEngine) {
        val state = _uiState.value
        val available = when (engine) {
            CrisisSentinelEngine.Online -> state.onlineEngineAvailable
            CrisisSentinelEngine.Edge -> state.edgeEngineAvailable
        }
        if (!available) return
        conversationStore.savePreferredEngine(engine)
        _uiState.update { it.copy(selectedEngine = engine) }
    }

    fun selectOnlineModel(choice: CrisisSentinelOnlineModelChoice?) {
        conversationStore.savePreferredOnlineModel(choice)
        _uiState.update { it.copy(selectedOnlineModel = choice) }
    }

    /** Loads the provider/model list for the picker (memory-cached across VM instances). */
    fun refreshOnlineProviders() {
        val state = _uiState.value
        if (!state.isOnlineEngineAllowed || state.isLoadingProviders) return
        val cached = providersCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < PROVIDERS_CACHE_TTL_MS) {
            _uiState.update { it.copy(onlineProviders = cached.second, providersLoadFailed = false) }
            reconcileModelSelection(cached.second)
            return
        }
        _uiState.update { it.copy(isLoadingProviders = true, providersLoadFailed = false) }
        viewModelScope.launch(exceptionHandler) {
            val providers = runCatching { onlineClient.fetchProviders() }
                .onFailure { Log.w(TAG, "Provider list fetch failed", it) }
                .getOrNull()
            if (providers == null) {
                _uiState.update {
                    it.copy(
                        isLoadingProviders = false,
                        onlineProviders = cached?.second.orEmpty(),
                        providersLoadFailed = cached == null
                    )
                }
            } else {
                providersCache = System.currentTimeMillis() to providers
                _uiState.update {
                    it.copy(isLoadingProviders = false, onlineProviders = providers)
                }
                reconcileModelSelection(providers)
            }
        }
    }

    /**
     * Web-parity reconcile: if the pinned provider/model was removed server-side (panel
     * reconfigured, model retired), fall back to the server default instead of sending a
     * stale providerId that the server would reject.
     */
    private fun reconcileModelSelection(providers: List<CrisisSentinelOnlineProvider>) {
        if (providers.isEmpty()) return
        val selected = _uiState.value.selectedOnlineModel ?: return
        val stillValid = providers.any { provider ->
            provider.id == selected.providerId &&
                provider.models.any { it.id == selected.modelId }
        }
        if (!stillValid) {
            selectOnlineModel(null)
        }
    }

    // ------------------------------------------------------------------ cloud sync

    private fun mergedSummaries(): List<CrisisSentinelConversationSummary> {
        return (conversationStore.summaries() + cloudSummaries)
            .sortedByDescending { it.updatedAtMillis }
    }

    private suspend fun refreshCloudSync() {
        if (!_uiState.value.isOnlineEngineAllowed) {
            cloudSummariesJob?.cancel()
            cloudSummariesJob = null
            return
        }
        val panelKey = withContext(Dispatchers.IO) {
            runCatching { cloudChatStore.resolvePanelKey() }.getOrNull()
        }
        if (panelKey == null) {
            _uiState.update { it.copy(cloudSyncState = CrisisSentinelCloudSyncState.NoPanel) }
            return
        }
        _uiState.update { it.copy(cloudSyncState = CrisisSentinelCloudSyncState.Enabled) }
        startCloudSummaries()
    }

    private fun startCloudSummaries() {
        if (cloudSummariesJob?.isActive == true) return
        cloudSummariesJob = viewModelScope.launch {
            cloudChatStore.chatSummaries.collect { cloudState ->
                if (cloudState.permissionDenied) {
                    cloudChatStore.invalidatePanelKey()
                    _uiState.update {
                        it.copy(cloudSyncState = CrisisSentinelCloudSyncState.PermissionDenied)
                    }
                }
                cloudSummaries = cloudState.summaries
                _uiState.update { it.copy(conversations = mergedSummaries()) }
            }
        }
    }

    private fun handleCloudWriteFailure(error: Throwable) {
        Log.w(TAG, "Cloud chat write failed", error)
        val permissionDenied =
            (error as? FirebaseFirestoreException)?.code ==
                FirebaseFirestoreException.Code.PERMISSION_DENIED
        if (permissionDenied) {
            cloudChatStore.invalidatePanelKey()
            _uiState.update {
                it.copy(cloudSyncState = CrisisSentinelCloudSyncState.PermissionDenied)
            }
        }
    }

    fun onPromptChanged(prompt: String) {
        _uiState.update { it.copy(prompt = prompt, transientMessageRes = null) }
    }


    fun refreshRoleAccess() {
        viewModelScope.launch(exceptionHandler) {
            val role = withContext(Dispatchers.IO) {
                securityRepository.getUsableStoredCertificateRole(allowExpired = true)
            }?.lowercase(Locale.US)
            val modes = availableModesForRole(role)
            // The FirebaseAuth check is deliberately independent of the certificate: a stored
            // cert can outlive the sign-in session, but the online engine needs an ID token
            // from a real (non-anonymous) user.
            val onlineAllowed = role in ONLINE_ENGINE_ROLES &&
                FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous } != null
            _uiState.update { state ->
                // Mode is now derived purely from the role (no manual default-mode picker):
                // field team → field, authorized → coordinator, everyone else → public.
                val preferredMode = preferredModeForRole(role)
                val nextMode = if (preferredMode in modes) preferredMode else CrisisSentinelUserMode.Public
                conversationStore.saveDefaultMode(nextMode)
                state.copy(
                    mode = nextMode,
                    availableModes = modes,
                    certificateRole = role,
                    isOnlineEngineAllowed = onlineAllowed
                )
            }
            recomputeEngineSelection()
            refreshCloudSync()
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessageRes = null) }
    }

    fun cancelGeneration() {
        // Online generations are aborted purely by cancelling the job (which cancels the HTTP
        // call); poking the LiteRT runtime for them could disturb an unrelated local session.
        if (activeGenerationEngine != CrisisSentinelEngine.Online) {
            runtime.cancelGeneration()
        }
        generationTimeoutJob?.cancel()
        generationTimeoutJob = null
        generationJob?.cancel()
        generationJob = null
        _uiState.update {
            it.copy(
                isGenerating = false,
                streamingReply = null,
                transientMessageRes = R.string.crisis_sentinel_generation_cancelled,
                modelStatus = currentModelStatus()
            )
        }
    }

    fun refreshModelStatus() {
        _uiState.update { it.copy(modelStatus = currentModelStatus()) }
        recomputeEngineSelection()
    }

    fun importImageForOcr(uri: Uri) {
        processConnectorInput(
            blockTitle = localizedString(R.string.crisis_sentinel_block_photo_ocr),
            loader = { CrisisSentinelInputProcessor.extractTextFromImage(getApplication(), uri) }
        )
    }

    fun importTextFile(uri: Uri) {
        processConnectorInput(
            blockTitle = localizedString(R.string.crisis_sentinel_block_file_text),
            loader = { CrisisSentinelInputProcessor.extractTextFromFile(getApplication(), uri) }
        )
    }

    fun appendVoiceDictation(text: String?) {
        val clean = text?.trim().orEmpty()
        if (clean.isBlank()) {
            _uiState.update { it.copy(transientMessageRes = R.string.crisis_sentinel_input_empty) }
            return
        }
        appendInputBlock(
            blockTitle = localizedString(R.string.crisis_sentinel_block_voice_dictation),
            text = clean,
            messageRes = R.string.crisis_sentinel_input_added
        )
    }

    /** Resolves a string in the app's per-app locale (Application context alone misses it pre-33). */
    private fun localizedString(resId: Int): String =
        NotificationLocalization.localizedContext(getApplication()).getString(resId)

    fun markInputUnavailable() {
        _uiState.update { it.copy(transientMessageRes = R.string.crisis_sentinel_input_unavailable) }
    }

    private fun processConnectorInput(
        blockTitle: String,
        loader: suspend () -> String
    ) {
        if (_uiState.value.isProcessingInput) return
        viewModelScope.launch(exceptionHandler) {
            _uiState.update {
                it.copy(
                    isProcessingInput = true,
                    transientMessageRes = null
                )
            }
            val result = runCatching { loader() }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isProcessingInput = false,
                        transientMessageRes = R.string.crisis_sentinel_input_failed
                    )
                }
                return@launch
            }
            val extracted = result.getOrNull()?.trim().orEmpty()
            if (extracted.isBlank()) {
                _uiState.update {
                    it.copy(
                        isProcessingInput = false,
                        transientMessageRes = R.string.crisis_sentinel_input_empty
                    )
                }
                return@launch
            }
            appendInputBlock(
                blockTitle = blockTitle,
                text = extracted,
                messageRes = R.string.crisis_sentinel_input_added
            )
            _uiState.update { it.copy(isProcessingInput = false) }
        }
    }

    private fun appendInputBlock(
        blockTitle: String,
        text: String,
        messageRes: Int
    ) {
        _uiState.update { state ->
            val existing = state.prompt.trim()
            val block = "[$blockTitle]\n${text.trim()}"
            state.copy(
                prompt = if (existing.isBlank()) block else "$existing\n\n$block",
                transientMessageRes = messageRes
            )
        }
    }

    fun requestModelDownload() {
        resolveLatestModelRelease(
            shouldDownloadIfNeeded = true,
            forceDownload = true,
            showUserMessage = true
        )
    }

    fun refreshConversations() {
        _uiState.update { it.copy(conversations = mergedSummaries()) }
    }

    fun renameConversation(id: String, title: String) {
        val clean = title.trim()
        if (clean.isBlank() || id.isBlank() || id == CRISIS_SENTINEL_NEW_CONVERSATION_ID) return
        if (CrisisSentinelConversationIds.isCloud(id)) {
            // Optimistic title update; web parity — rename sets title + updatedAt.
            _uiState.update { state ->
                state.copy(
                    activeConversation = state.activeConversation
                        ?.takeIf { it.id == id }
                        ?.copy(title = clean)
                        ?: state.activeConversation,
                    transientMessageRes = R.string.crisis_sentinel_chat_renamed
                )
            }
            viewModelScope.launch(exceptionHandler) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        cloudChatStore.renameChat(
                            CrisisSentinelConversationIds.cloudDocId(id),
                            clean
                        )
                    }
                }.onFailure(::handleCloudWriteFailure)
            }
            return
        }
        val renamed = conversationStore.rename(id, clean)
        _uiState.update { state ->
            state.copy(
                conversations = mergedSummaries(),
                activeConversation = if (state.activeConversation?.id == id) {
                    renamed ?: state.activeConversation
                } else {
                    state.activeConversation
                },
                transientMessageRes = R.string.crisis_sentinel_chat_renamed
            )
        }
    }

    fun deleteConversation(id: String) {
        if (id.isBlank() || id == CRISIS_SENTINEL_NEW_CONVERSATION_ID) return

        val state = _uiState.value
        val isActiveConversation = state.activeConversation?.id == id
        if (isActiveConversation && state.isGenerating) {
            if (activeGenerationEngine != CrisisSentinelEngine.Online) {
                runtime.cancelGeneration()
            }
            generationTimeoutJob?.cancel()
            generationTimeoutJob = null
            generationJob?.cancel()
            generationJob = null
        }

        if (CrisisSentinelConversationIds.isCloud(id)) {
            if (isActiveConversation) {
                cloudMessagesJob?.cancel()
                cloudMessagesJob = null
            }
            // Optimistic removal; the snapshot listener confirms once the deletes land.
            cloudSummaries = cloudSummaries.filterNot { it.id == id }
            _uiState.update {
                val activeDeleted = it.activeConversation?.id == id
                it.copy(
                    conversations = mergedSummaries(),
                    activeConversation = if (activeDeleted) null else it.activeConversation,
                    prompt = if (activeDeleted) "" else it.prompt,
                    response = if (activeDeleted) null else it.response,
                    isGenerating = if (activeDeleted) false else it.isGenerating,
                    streamingReply = if (activeDeleted) null else it.streamingReply,
                    transientMessageRes = R.string.crisis_sentinel_chat_deleted
                )
            }
            viewModelScope.launch(exceptionHandler) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        cloudChatStore.deleteChat(CrisisSentinelConversationIds.cloudDocId(id))
                    }
                }.onFailure(::handleCloudWriteFailure)
            }
            return
        }

        val deleted = conversationStore.delete(id)
        _uiState.update {
            val activeDeleted = it.activeConversation?.id == id
            it.copy(
                conversations = mergedSummaries(),
                activeConversation = if (activeDeleted) null else it.activeConversation,
                prompt = if (activeDeleted) "" else it.prompt,
                response = if (activeDeleted) null else it.response,
                isGenerating = if (activeDeleted) false else it.isGenerating,
                streamingReply = if (activeDeleted) null else it.streamingReply,
                transientMessageRes = if (deleted) {
                    R.string.crisis_sentinel_chat_deleted
                } else {
                    R.string.crisis_sentinel_chat_delete_failed
                }
            )
        }
    }

    fun deleteDownloadedModel() {
        viewModelScope.launch(Dispatchers.IO) {
            runtime.close()
            val deleted = store.deleteModel(currentRelease)
            _uiState.update {
                it.copy(
                    modelStatus = currentModelStatus(),
                    transientMessageRes = if (deleted) {
                        R.string.crisis_sentinel_model_deleted
                    } else {
                        R.string.crisis_sentinel_model_delete_failed
                    }
                )
            }
            recomputeEngineSelection()
        }
    }

    fun createConversation(): String {
        val conversation = conversationStore.create(mode = _uiState.value.mode)
        _uiState.update {
            it.copy(
                conversations = mergedSummaries(),
                activeConversation = conversation,
                prompt = "",
                response = null
            )
        }
        return conversation.id
    }

    fun openConversation(id: String) {
        if (id == CRISIS_SENTINEL_NEW_CONVERSATION_ID) {
            _uiState.update {
                // This re-fires when the screen re-enters composition (e.g. rotation). Only reset
                // when nothing is active yet; otherwise the conversation started from this "new"
                // screen would vanish from the UI even though it is persisted in the store.
                if (it.activeConversation != null) {
                    it.copy(conversations = mergedSummaries())
                } else {
                    it.copy(
                        activeConversation = null,
                        prompt = "",
                        response = null,
                        conversations = mergedSummaries()
                    )
                }
            }
            return
        }

        if (CrisisSentinelConversationIds.isCloud(id)) {
            openCloudConversation(id)
            return
        }

        cloudMessagesJob?.cancel()
        cloudMessagesJob = null
        val conversation = conversationStore.load(id)
        _uiState.update {
            it.copy(
                activeConversation = conversation,
                prompt = conversation?.draftText.orEmpty(),
                response = null,
                conversations = mergedSummaries()
            )
        }
    }

    /**
     * Opens a Firestore-homed chat: seeds a skeleton immediately (so the screen never blanks)
     * and live-collects the message listener — replies typed on the web dashboard appear on
     * the phone in real time.
     */
    private fun openCloudConversation(id: String) {
        val docId = CrisisSentinelConversationIds.cloudDocId(id)
        val knownTitle = _uiState.value.conversations.firstOrNull { it.id == id }?.title
        _uiState.update { state ->
            val existing = state.activeConversation?.takeIf { it.id == id }
            state.copy(
                activeConversation = existing ?: CrisisSentinelConversation(
                    id = id,
                    title = knownTitle.orEmpty(),
                    mode = CrisisSentinelUserMode.FieldTeam,
                    createdAtMillis = 0L,
                    updatedAtMillis = System.currentTimeMillis(),
                    messages = emptyList()
                ),
                prompt = if (existing == null) "" else state.prompt,
                response = null,
                conversations = mergedSummaries()
            )
        }
        cloudMessagesJob?.cancel()
        cloudMessagesJob = viewModelScope.launch {
            cloudChatStore.messages(docId).collect { cloud ->
                _uiState.update { state ->
                    val active = state.activeConversation
                    if (active?.id != id) return@update state
                    if (!cloud.chatExists) {
                        // Deleted from the web dashboard while open here.
                        state.copy(
                            activeConversation = null,
                            isGenerating = false,
                            streamingReply = null,
                            transientMessageRes = R.string.crisis_sentinel_chat_deleted
                        )
                    } else {
                        state.copy(activeConversation = active.copy(messages = cloud.messages))
                    }
                }
            }
        }
    }

    fun persistDraft(conversationId: String) {
        val state = _uiState.value
        val activeId = state.activeConversation?.id?.takeIf { it.isNotBlank() }
        val targetId = when {
            conversationId == CRISIS_SENTINEL_NEW_CONVERSATION_ID -> activeId
            conversationId.isNotBlank() -> conversationId
            else -> activeId
        }
        // Cloud chats have no draft support (web has none either) — never write a cloud id
        // into the local store.
        if (targetId != null && CrisisSentinelConversationIds.isCloud(targetId)) {
            return
        }
        if (targetId == null && state.prompt.isBlank()) {
            return
        }
        val savedDraft = conversationStore.saveDraft(
            id = targetId,
            mode = state.mode,
            text = state.prompt
        )
        _uiState.update {
            it.copy(
                activeConversation = savedDraft ?: it.activeConversation,
                conversations = mergedSummaries()
            )
        }
    }

    private fun resolveLatestModelRelease(
        shouldDownloadIfNeeded: Boolean,
        forceDownload: Boolean,
        showUserMessage: Boolean
    ) {
        if (_uiState.value.isResolvingModelRelease) return

        viewModelScope.launch(exceptionHandler) {
            _uiState.update {
                it.copy(
                    isResolvingModelRelease = true,
                    transientMessageRes = if (showUserMessage) null else it.transientMessageRes
                )
            }

            val releaseResult = runCatching {
                manifestClient.fetchLatest()
            }
            val release = releaseResult.getOrNull()

            if (release == null) {
                val appCheckRejected = releaseResult.exceptionOrNull()?.let { throwable ->
                    FirebaseAppCheckFailures.isLikelyAppCheckFailure(
                        throwable = throwable,
                        allowGenericUnauthenticated = true
                    )
                } == true
                _uiState.update {
                    it.copy(
                        isResolvingModelRelease = false,
                        modelStatus = currentModelStatus(),
                        transientMessageRes = when {
                            appCheckRejected -> R.string.app_check_install_play_store_message
                            showUserMessage -> R.string.crisis_sentinel_download_manifest_missing
                            else -> it.transientMessageRes
                        }
                    )
                }
                return@launch
            }

            currentRelease = release
            manifestCache.save(release)
            val status = currentModelStatus(release)
            if (status.availability == CrisisSentinelModelAvailability.InsufficientStorage) {
                _uiState.update {
                    it.copy(
                        isResolvingModelRelease = false,
                        modelStatus = status,
                        transientMessageRes = if (showUserMessage) {
                            R.string.crisis_sentinel_model_storage_low
                        } else {
                            it.transientMessageRes
                        }
                    )
                }
                return@launch
            }
            val shouldStartDownload = forceDownload || (shouldDownloadIfNeeded && !status.isReady)
            val started = shouldStartDownload && CrisisSentinelModelDownloadWorker.enqueue(
                context = getApplication<Application>(),
                release = release
            )
            _uiState.update {
                it.copy(
                    isResolvingModelRelease = false,
                    modelStatus = status,
                    transientMessageRes = downloadStartMessage(
                        showUserMessage = showUserMessage,
                        shouldStartDownload = shouldStartDownload,
                        started = started,
                        previousMessage = it.transientMessageRes
                    )
                )
            }
        }
    }

    private fun downloadStartMessage(
        showUserMessage: Boolean,
        shouldStartDownload: Boolean,
        started: Boolean,
        previousMessage: Int?
    ): Int? {
        if (!showUserMessage) return previousMessage
        if (!shouldStartDownload) return previousMessage
        return if (started) {
            R.string.crisis_sentinel_download_queued
        } else {
            R.string.crisis_sentinel_download_manifest_missing
        }
    }

    private fun observeModelDownloadWork() {
        downloadWorkLiveData.observeForever(downloadWorkObserver)
    }

    private fun handleModelDownloadWork(workInfos: List<WorkInfo>) {
        val info = workInfos.firstOrNull()
        if (info == null) {
            _uiState.update {
                it.copy(modelDownloadState = CrisisSentinelModelDownloadState())
            }
            return
        }

        val isActive = info.state == WorkInfo.State.ENQUEUED ||
            info.state == WorkInfo.State.RUNNING ||
            info.state == WorkInfo.State.BLOCKED
        if (isActive) {
            observedActiveDownload = true
        }

        val progress = info.progress
        val bytesDownloaded = progress.getLong(
            CrisisSentinelModelDownloadWorker.KEY_BYTES_DOWNLOADED,
            0L
        )
        val bytesTotal = progress.getLong(
            CrisisSentinelModelDownloadWorker.KEY_BYTES_TOTAL,
            -1L
        ).takeIf { it > 0L }

        val shouldNotifyFinished = info.state.isFinished &&
            observedActiveDownload &&
            lastFinishedDownloadWorkId != info.id
        val finishedMessage = if (shouldNotifyFinished) {
            lastFinishedDownloadWorkId = info.id
            observedActiveDownload = false
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    runtime.close()
                    R.string.crisis_sentinel_download_complete
                }
                WorkInfo.State.FAILED -> R.string.crisis_sentinel_download_failed
                else -> null
            }
        } else {
            null
        }

        _uiState.update {
            it.copy(
                modelDownloadState = CrisisSentinelModelDownloadState(
                    isActive = isActive,
                    bytesDownloaded = if (isActive) bytesDownloaded else 0L,
                    bytesTotal = if (isActive) bytesTotal else null
                ),
                modelStatus = currentModelStatus(),
                transientMessageRes = finishedMessage ?: it.transientMessageRes
            )
        }
        recomputeEngineSelection()
    }

    fun submit() {
        val state = _uiState.value
        val prompt = state.prompt.trim()
        if (prompt.isBlank()) {
            _uiState.update { it.copy(transientMessageRes = R.string.crisis_sentinel_prompt_empty) }
            return
        }
        if (state.isGenerating) return

        // This legacy non-chat surface is Edge-only by design.
        activeGenerationEngine = CrisisSentinelEngine.Edge
        Analytics.sentinelPrompt("edge")
        launchGeneration {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    transientMessageRes = null,
                    modelStatus = currentModelStatus()
                )
            }
            val request = CrisisSentinelRequest(
                prompt = prompt,
                mode = _uiState.value.mode,
                locale = CrisisSentinelText.responseLocaleFor(prompt)
            )
            val response = withContext(Dispatchers.Default) {
                engine.respondWithModel(request)
            }
            if (!currentCoroutineContext().isActive) return@launchGeneration
            _uiState.update {
                it.copy(
                    response = response,
                    isGenerating = false,
                    modelStatus = currentModelStatus()
                )
            }
        }
    }

    fun submitChatMessage(conversationId: String) {
        val state = _uiState.value
        val prompt = state.prompt.trim()
        if (prompt.isBlank()) {
            _uiState.update { it.copy(transientMessageRes = R.string.crisis_sentinel_prompt_empty) }
            return
        }
        if (state.isGenerating) return

        Analytics.sentinelPrompt(state.activeEngine?.name?.lowercase() ?: "unknown")
        val activeId = state.activeConversation?.id?.takeIf { it.isNotBlank() }
        val targetConversationId = when {
            // The "new conversation" route keeps its literal id for the whole screen lifetime, so
            // after the first message we must keep appending to the conversation it created —
            // otherwise every send spawns a fresh one-message conversation.
            conversationId == CRISIS_SENTINEL_NEW_CONVERSATION_ID -> activeId
            conversationId.isNotBlank() -> conversationId
            else -> activeId
        }

        if (targetConversationId != null &&
            CrisisSentinelConversationIds.isCloud(targetConversationId)
        ) {
            submitToExistingCloudChat(targetConversationId, prompt)
            return
        }
        // Storage-home rule: a fresh chat started on the Online engine lives in Firestore
        // (synced with the web dashboard); everything else lives in the local store.
        if (targetConversationId == null &&
            state.activeEngine == CrisisSentinelEngine.Online &&
            state.cloudSyncState == CrisisSentinelCloudSyncState.Enabled
        ) {
            submitNewCloudChat(prompt)
            return
        }

        // Online chat that can't sync (no panel / permission): works locally; say so once.
        val syncNotice = if (
            targetConversationId == null &&
            state.activeEngine == CrisisSentinelEngine.Online &&
            state.cloudSyncState != CrisisSentinelCloudSyncState.Enabled &&
            !cloudUnavailableNoticeShown
        ) {
            cloudUnavailableNoticeShown = true
            R.string.crisis_sentinel_cloud_sync_unavailable
        } else {
            null
        }

        val conversationAfterUserMessage = targetConversationId?.let { targetId ->
            conversationStore.appendUserMessage(
                id = targetId,
                text = prompt
            )
        } ?: conversationStore.create(mode = state.mode).let { newConversation ->
            conversationStore.appendUserMessage(
                id = newConversation.id,
                text = prompt
            ) ?: newConversation
        }

        _uiState.update {
            it.copy(
                activeConversation = conversationAfterUserMessage,
                conversations = mergedSummaries(),
                prompt = "",
                isGenerating = true,
                transientMessageRes = syncNotice,
                modelStatus = currentModelStatus()
            )
        }

        startAssistantGeneration(conversation = conversationAfterUserMessage, prompt = prompt)
    }

    private fun submitToExistingCloudChat(cloudId: String, prompt: String) {
        _uiState.update {
            it.copy(
                prompt = "",
                isGenerating = true,
                transientMessageRes = null,
                modelStatus = currentModelStatus()
            )
        }
        val seqs = cloudChatStore.reserveTurnSeqs()
        viewModelScope.launch(exceptionHandler) {
            try {
                withContext(Dispatchers.IO) {
                    cloudChatStore.appendUserMessage(
                        chatDocId = CrisisSentinelConversationIds.cloudDocId(cloudId),
                        text = prompt,
                        seq = seqs.userSeq
                    )
                }
            } catch (error: Exception) {
                handleCloudWriteFailure(error)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        transientMessageRes = R.string.crisis_sentinel_error_response
                    )
                }
                return@launch
            }
            startAssistantGeneration(
                conversation = cloudConversationForGeneration(cloudId, prompt),
                prompt = prompt,
                cloudAssistantSeq = seqs.assistantSeq
            )
        }
    }

    private fun submitNewCloudChat(prompt: String) {
        _uiState.update {
            it.copy(
                prompt = "",
                isGenerating = true,
                transientMessageRes = null,
                response = null,
                modelStatus = currentModelStatus()
            )
        }
        viewModelScope.launch(exceptionHandler) {
            val docId = try {
                withContext(Dispatchers.IO) { cloudChatStore.createChat(prompt) }
            } catch (error: Exception) {
                handleCloudWriteFailure(error)
                null
            }
            if (docId == null) {
                // Transparent local fallback: the turn continues on-device.
                val conversation = conversationStore.create(mode = _uiState.value.mode)
                val withUser = conversationStore.appendUserMessage(conversation.id, prompt)
                    ?: conversation
                _uiState.update {
                    it.copy(
                        activeConversation = withUser,
                        conversations = mergedSummaries(),
                        transientMessageRes =
                            R.string.crisis_sentinel_cloud_sync_error_local_fallback
                    )
                }
                startAssistantGeneration(conversation = withUser, prompt = prompt)
                return@launch
            }
            val cloudId = CrisisSentinelConversationIds.fromCloudDocId(docId)
            openCloudConversation(cloudId)
            val seqs = cloudChatStore.reserveTurnSeqs()
            try {
                withContext(Dispatchers.IO) {
                    cloudChatStore.appendUserMessage(docId, prompt, seqs.userSeq)
                }
            } catch (error: Exception) {
                handleCloudWriteFailure(error)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        transientMessageRes = R.string.crisis_sentinel_error_response
                    )
                }
                return@launch
            }
            startAssistantGeneration(
                conversation = cloudConversationForGeneration(cloudId, prompt),
                prompt = prompt,
                cloudAssistantSeq = seqs.assistantSeq
            )
        }
    }

    /**
     * History for a cloud-homed generation, built from the live listener state. The just-sent
     * prompt is appended synthetically when the listener echo hasn't landed yet.
     */
    private fun cloudConversationForGeneration(
        cloudId: String,
        prompt: String
    ): CrisisSentinelConversation {
        val live = _uiState.value.activeConversation?.takeIf { it.id == cloudId }
        val messages = live?.messages.orEmpty()
        val last = messages.lastOrNull()
        val needsPrompt = last == null ||
            last.role != CrisisSentinelChatRole.User ||
            last.text != prompt
        val finalMessages = if (needsPrompt) {
            messages + CrisisSentinelChatMessage(
                id = "pending-user",
                role = CrisisSentinelChatRole.User,
                text = prompt,
                timestampMillis = System.currentTimeMillis()
            )
        } else {
            messages
        }
        return CrisisSentinelConversation(
            id = cloudId,
            title = live?.title.orEmpty(),
            mode = live?.mode ?: CrisisSentinelUserMode.FieldTeam,
            createdAtMillis = 0L,
            updatedAtMillis = System.currentTimeMillis(),
            messages = finalMessages
        )
    }

    /**
     * Re-runs the last user prompt in the active conversation, replacing the trailing assistant
     * reply in place.
     */
    fun regenerateLastResponse() {
        val state = _uiState.value
        if (state.isGenerating) return
        val conversation = state.activeConversation ?: return
        val lastPrompt = conversation.messages
            .lastOrNull { it.role == CrisisSentinelChatRole.User }
            ?.text
            ?.takeIf { it.isNotBlank() }
            ?: return

        if (CrisisSentinelConversationIds.isCloud(conversation.id)) {
            val trailingBotIds = conversation.messages
                .takeLastWhile { it.role == CrisisSentinelChatRole.Assistant }
                .map { it.id }
            val trimmed = conversation.copy(
                messages = conversation.messages
                    .dropLastWhile { it.role == CrisisSentinelChatRole.Assistant }
            )
            _uiState.update {
                it.copy(
                    activeConversation = trimmed,
                    isGenerating = true,
                    transientMessageRes = null,
                    modelStatus = currentModelStatus()
                )
            }
            viewModelScope.launch(exceptionHandler) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        cloudChatStore.deleteMessages(
                            CrisisSentinelConversationIds.cloudDocId(conversation.id),
                            trailingBotIds
                        )
                    }
                }.onFailure(::handleCloudWriteFailure)
                startAssistantGeneration(
                    conversation = trimmed,
                    prompt = lastPrompt,
                    cloudAssistantSeq = cloudChatStore.reserveTurnSeqs().assistantSeq
                )
            }
            return
        }

        val trimmedConversation = conversationStore.removeTrailingAssistantMessages(conversation.id)
            ?: conversation
        _uiState.update {
            it.copy(
                activeConversation = trimmedConversation,
                conversations = mergedSummaries(),
                isGenerating = true,
                transientMessageRes = null,
                modelStatus = currentModelStatus()
            )
        }
        startAssistantGeneration(conversation = trimmedConversation, prompt = lastPrompt)
    }

    private fun startAssistantGeneration(
        conversation: CrisisSentinelConversation,
        prompt: String,
        cloudAssistantSeq: Long? = null
    ) {
        val engineForGeneration = _uiState.value.activeEngine
        if (engineForGeneration == null) {
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    transientMessageRes = R.string.crisis_sentinel_engine_unavailable_hint
                )
            }
            return
        }
        activeGenerationEngine = engineForGeneration
        val timeoutMillis = when (engineForGeneration) {
            CrisisSentinelEngine.Online -> ONLINE_GENERATION_TIMEOUT_MS
            CrisisSentinelEngine.Edge -> GENERATION_TIMEOUT_MS
        }
        launchGeneration(timeoutMillis) {
            val startedAtMillis = System.currentTimeMillis()
            val outcome = try {
                when (engineForGeneration) {
                    CrisisSentinelEngine.Online -> generateOnlineOutcome(conversation)
                    CrisisSentinelEngine.Edge ->
                        GenerationOutcome(response = generateEdgeResponse(conversation, prompt))
                }
            } catch (online: CrisisSentinelOnlineException) {
                Log.w(TAG, "Crisis Sentinel online generation failed (${online.kind})", online)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingReply = null,
                        transientMessageRes = onlineErrorMessage(online),
                        modelStatus = currentModelStatus()
                    )
                }
                if (online.kind == CrisisSentinelOnlineException.Kind.Unauthorized ||
                    online.kind == CrisisSentinelOnlineException.Kind.Forbidden
                ) {
                    // The certificate/session may have been revoked; re-evaluate the gate.
                    refreshRoleAccess()
                }
                return@launchGeneration
            }
            if (!currentCoroutineContext().isActive) return@launchGeneration
            val generationDurationMillis = System.currentTimeMillis() - startedAtMillis

            if (CrisisSentinelConversationIds.isCloud(conversation.id)) {
                persistCloudAssistantMessage(
                    cloudId = conversation.id,
                    engineUsed = engineForGeneration,
                    outcome = outcome,
                    generationDurationMillis = generationDurationMillis,
                    assistantSeq = cloudAssistantSeq
                )
                return@launchGeneration
            }

            val conversationAfterAssistantMessage = conversationStore.appendAssistantResponse(
                id = conversation.id,
                response = outcome.response,
                generationDurationMillis = generationDurationMillis,
                modelName = outcome.modelName,
                cardJson = outcome.cardJson,
                mapPoints = outcome.mapPoints,
                unsupportedToolName = outcome.unsupportedToolName
            )
            _uiState.update {
                it.copy(
                    response = outcome.response,
                    activeConversation = conversationAfterAssistantMessage
                        ?: conversationStore.load(conversation.id),
                    conversations = mergedSummaries(),
                    isGenerating = false,
                    streamingReply = null,
                    modelStatus = currentModelStatus()
                )
            }
        }
    }

    private data class GenerationOutcome(
        val response: CrisisSentinelResponse,
        val modelName: String? = null,
        val cardJson: String? = null,
        val mapPoints: List<CrisisSentinelMapPoint> = emptyList(),
        val unsupportedToolName: String? = null,
        val groundingMetadataJson: String? = null
    )

    private suspend fun persistCloudAssistantMessage(
        cloudId: String,
        engineUsed: CrisisSentinelEngine,
        outcome: GenerationOutcome,
        generationDurationMillis: Long,
        assistantSeq: Long?
    ) {
        val botMessage = CrisisSentinelChatMessage(
            id = UUID.randomUUID().toString(),
            role = CrisisSentinelChatRole.Assistant,
            text = outcome.response.answer,
            timestampMillis = System.currentTimeMillis(),
            source = outcome.response.source,
            confidence = outcome.response.confidence.toDouble(),
            generationDurationMillis = generationDurationMillis,
            // Edge replies written into synced chats still need a label for the web side.
            modelName = outcome.modelName ?: CrisisSentinelCloudChatStore.EDGE_MODEL_NAME
                .takeIf { engineUsed == CrisisSentinelEngine.Edge },
            cardJson = outcome.cardJson,
            mapPoints = outcome.mapPoints,
            unsupportedToolName = outcome.unsupportedToolName
        )
        val writeFailed = runCatching {
            withContext(Dispatchers.IO) {
                cloudChatStore.appendBotMessage(
                    chatDocId = CrisisSentinelConversationIds.cloudDocId(cloudId),
                    message = botMessage,
                    seq = assistantSeq ?: cloudChatStore.reserveTurnSeqs().assistantSeq,
                    groundingMetadataJson = outcome.groundingMetadataJson
                )
            }
        }.onFailure(::handleCloudWriteFailure).isFailure
        _uiState.update { state ->
            val active = state.activeConversation
            // On a failed write keep the reply visible in-memory so the answer isn't lost
            // (it just won't sync); on success the snapshot listener delivers it.
            val patchedActive = if (writeFailed && active?.id == cloudId) {
                active.copy(messages = active.messages + botMessage)
            } else {
                active
            }
            state.copy(
                response = outcome.response,
                activeConversation = patchedActive,
                isGenerating = false,
                streamingReply = null,
                transientMessageRes = if (writeFailed) {
                    R.string.crisis_sentinel_cloud_sync_error_local_fallback
                } else {
                    state.transientMessageRes
                },
                modelStatus = currentModelStatus()
            )
        }
    }

    private suspend fun generateEdgeResponse(
        conversation: CrisisSentinelConversation,
        prompt: String
    ): CrisisSentinelResponse {
        val priorMessages = conversation.messages.dropLast(1)
        val recentUserMessages = priorMessages
            .filter { it.role == CrisisSentinelChatRole.User }
            .takeLast(3)
            .map { it.text }
        val responseLocale = CrisisSentinelText.responseLocaleFor(
            prompt = prompt,
            recentMessages = recentUserMessages
        )
        val request = CrisisSentinelRequest(
            prompt = prompt,
            mode = conversation.mode,
            locale = responseLocale,
            // Real conversational context: include the assistant's own replies (not just the
            // user prompts) so follow-ups like "devam et" have something to follow.
            recentMessages = priorMessages
                .takeLast(8)
                .map { message ->
                    val compactText = message.text.replace(Regex("\\s+"), " ").trim().take(200)
                    val speaker = if (message.role == CrisisSentinelChatRole.User) {
                        "User"
                    } else {
                        "Assistant"
                    }
                    "$speaker: $compactText"
                }
        )
        return withContext(Dispatchers.Default) {
            engine.respondWithModel(request)
        }
    }

    /**
     * Streams a reply from the cloud engine. No locale hint and no output validation on
     * purpose: the server injects its own Crisis Sentinel prompt, answers in the user's
     * language, and its output contract differs from the Edge model's.
     */
    private suspend fun generateOnlineOutcome(
        conversation: CrisisSentinelConversation
    ): GenerationOutcome {
        val history = conversation.messages
            // Card/map-only replies can have empty text; the wire format wants text turns.
            .filter { it.text.isNotBlank() }
            .map { message ->
                CrisisSentinelOnlineMessage(
                    sender = if (message.role == CrisisSentinelChatRole.User) {
                        CrisisSentinelOnlineMessage.SENDER_USER
                    } else {
                        CrisisSentinelOnlineMessage.SENDER_BOT
                    },
                    text = message.text
                )
            }
        val modelChoice = _uiState.value.selectedOnlineModel
        val result = onlineClient.streamChat(history, modelChoice) { accumulated ->
            _uiState.update { it.copy(streamingReply = accumulated) }
        }
        val response = CrisisSentinelResponse(
            answer = result.text,
            mode = conversation.mode,
            source = CrisisSentinelResponseSource.OnlineModel,
            citations = emptyList(),
            incidentDraft = null,
            followUpQuestions = emptyList(),
            safetyNotices = emptyList(),
            confidence = 1f
        )
        return GenerationOutcome(
            response = response,
            // Web parity: the picked model's label wins over what the server reports.
            modelName = modelChoice?.modelLabel ?: result.modelName,
            cardJson = result.cardJson,
            mapPoints = result.mapPoints,
            // The notice is only worth showing when the reply text carries no substance.
            unsupportedToolName = result.unsupportedTools.firstOrNull()
                ?.takeIf { result.text.trim().length < UNSUPPORTED_TOOL_TEXT_THRESHOLD },
            groundingMetadataJson = result.groundingMetadataJson
        )
    }

    private fun onlineErrorMessage(error: CrisisSentinelOnlineException): Int = when (error.kind) {
        CrisisSentinelOnlineException.Kind.Unauthorized,
        CrisisSentinelOnlineException.Kind.Forbidden ->
            R.string.crisis_sentinel_online_error_unauthorized

        CrisisSentinelOnlineException.Kind.Quota ->
            R.string.crisis_sentinel_online_error_quota

        CrisisSentinelOnlineException.Kind.NotConfigured ->
            R.string.crisis_sentinel_online_error_not_configured

        CrisisSentinelOnlineException.Kind.Network,
        CrisisSentinelOnlineException.Kind.Server ->
            if (_uiState.value.edgeEngineAvailable) {
                R.string.crisis_sentinel_online_error_network_edge_hint
            } else {
                R.string.crisis_sentinel_online_error_network
            }
    }

    private fun launchGeneration(
        timeoutMillis: Long = GENERATION_TIMEOUT_MS,
        block: suspend () -> Unit
    ) {
        generationJob?.cancel()
        generationTimeoutJob?.cancel()

        val job = viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                // User cancel and timeout paths update the UI before cancelling the job.
            } catch (_: Throwable) {
                if (activeGenerationEngine != CrisisSentinelEngine.Online) {
                    runtime.cancelGeneration()
                }
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingReply = null,
                        transientMessageRes = R.string.crisis_sentinel_error_response,
                        modelStatus = currentModelStatus()
                    )
                }
            }
        }

        generationJob = job
        val timeoutJob = viewModelScope.launch {
            delay(timeoutMillis)
            if (generationJob === job && job.isActive) {
                if (activeGenerationEngine != CrisisSentinelEngine.Online) {
                    runtime.cancelGeneration()
                }
                job.cancel()
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingReply = null,
                        transientMessageRes = R.string.crisis_sentinel_generation_timeout,
                        modelStatus = currentModelStatus()
                    )
                }
            }
        }
        generationTimeoutJob = timeoutJob
        job.invokeOnCompletion {
            if (generationJob === job) {
                generationJob = null
            }
            if (generationTimeoutJob === timeoutJob) {
                generationTimeoutJob = null
            }
            timeoutJob.cancel()
        }
    }

    private fun currentModelStatus(
        release: CrisisSentinelModelRelease = currentRelease
    ): CrisisSentinelModelStatus {
        return store.status(release = release, verifyChecksum = false)
    }

    override fun onCleared() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        downloadWorkLiveData.removeObserver(downloadWorkObserver)
        generationTimeoutJob?.cancel()
        generationJob?.cancel()
        runtime.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "CrisisSentinelAI"
        // Kept above the engine's MODEL_RUNTIME_TIMEOUT_MS (180s) so the engine's graceful
        // rule-based fallback fires first; this hard cap is only a last-resort stop. Raised from
        // 55s because Gemma 3n E2B's first-load + generation can run well past a minute.
        const val GENERATION_TIMEOUT_MS = 200_000L

        // The cloud engine has no local model-load cost; the server enforces its own provider
        // timeout, so this only guards against a wedged stream.
        const val ONLINE_GENERATION_TIMEOUT_MS = 120_000L

        val ONLINE_ENGINE_ROLES = setOf("admin", "fieldteam")

        // A functionCall reply whose text is shorter than this carries no substance, so the
        // "available on the web dashboard" notice is shown instead.
        const val UNSUPPORTED_TOOL_TEXT_THRESHOLD = 40

        const val PROVIDERS_CACHE_TTL_MS = 15 * 60_000L

        // Shared across the multiple VM instances (one per NavBackStackEntry).
        @Volatile
        private var providersCache: Pair<Long, List<CrisisSentinelOnlineProvider>>? = null

        // "This chat is saved on this device only" is surfaced once per app start.
        @Volatile
        private var cloudUnavailableNoticeShown = false

        fun shouldShowModelPerformanceWarning(application: Application): Boolean {
            val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return activityManager.isLowRamDevice || memoryInfo.totalMem in 1 until MIN_RECOMMENDED_RAM_BYTES
        }

        const val MIN_RECOMMENDED_RAM_BYTES = 4_000_000_000L

        fun availableModesForRole(role: String?): List<CrisisSentinelUserMode> = when (role) {
            "admin" -> listOf(
                CrisisSentinelUserMode.Public,
                CrisisSentinelUserMode.FieldTeam,
                CrisisSentinelUserMode.Coordinator
            )
            "fieldteam" -> listOf(
                CrisisSentinelUserMode.Public,
                CrisisSentinelUserMode.FieldTeam
            )
            else -> listOf(CrisisSentinelUserMode.Public)
        }

        fun preferredModeForRole(role: String?): CrisisSentinelUserMode = when (role) {
            "admin" -> CrisisSentinelUserMode.Coordinator
            "fieldteam" -> CrisisSentinelUserMode.FieldTeam
            else -> CrisisSentinelUserMode.Public
        }
    }
}
