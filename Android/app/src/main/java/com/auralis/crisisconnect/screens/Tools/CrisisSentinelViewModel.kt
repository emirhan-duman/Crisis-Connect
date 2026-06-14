package com.auralis.crisisconnect.screens.Tools

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ai.CrisisSentinelChatRole
import com.auralis.crisisconnect.ai.CrisisSentinelConversation
import com.auralis.crisisconnect.ai.CrisisSentinelConversationStore
import com.auralis.crisisconnect.ai.CrisisSentinelConversationSummary
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
import com.auralis.crisisconnect.ai.CrisisSentinelRequest
import com.auralis.crisisconnect.ai.CrisisSentinelResponse
import com.auralis.crisisconnect.ai.CrisisSentinelText
import com.auralis.crisisconnect.ai.CrisisSentinelUserMode
import com.auralis.crisisconnect.security.FirebaseAppCheckFailures
import com.auralis.crisisconnect.security.SecurityRepository
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
    val transientMessageRes: Int? = null
)

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
            conversations = conversationStore.summaries(),
            modelStatus = currentModelStatus(),
            showPerformanceWarning = shouldShowModelPerformanceWarning(application)
        )
    )
    val uiState: StateFlow<CrisisSentinelUiState> = _uiState.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, _ ->
        _uiState.update {
            it.copy(
                isGenerating = false,
                isResolvingModelRelease = false,
                isProcessingInput = false,
                transientMessageRes = R.string.crisis_sentinel_error_response,
                modelStatus = currentModelStatus()
            )
        }
    }

    init {
        observeModelDownloadWork()
        refreshRoleAccess()
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
            _uiState.update { state ->
                // Mode is now derived purely from the role (no manual default-mode picker):
                // field team → field, authorized → coordinator, everyone else → public.
                val preferredMode = preferredModeForRole(role)
                val nextMode = if (preferredMode in modes) preferredMode else CrisisSentinelUserMode.Public
                conversationStore.saveDefaultMode(nextMode)
                state.copy(
                    mode = nextMode,
                    availableModes = modes,
                    certificateRole = role
                )
            }
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessageRes = null) }
    }

    fun cancelGeneration() {
        runtime.cancelGeneration()
        generationTimeoutJob?.cancel()
        generationTimeoutJob = null
        generationJob?.cancel()
        generationJob = null
        _uiState.update {
            it.copy(
                isGenerating = false,
                transientMessageRes = R.string.crisis_sentinel_generation_cancelled,
                modelStatus = currentModelStatus()
            )
        }
    }

    fun refreshModelStatus() {
        _uiState.update { it.copy(modelStatus = currentModelStatus()) }
    }

    fun importImageForOcr(uri: Uri) {
        processConnectorInput(
            blockTitle = "Fotoğraf OCR",
            loader = { CrisisSentinelInputProcessor.extractTextFromImage(getApplication(), uri) }
        )
    }

    fun importTextFile(uri: Uri) {
        processConnectorInput(
            blockTitle = "Dosya metni",
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
            blockTitle = "Sesli dikte",
            text = clean,
            messageRes = R.string.crisis_sentinel_input_added
        )
    }

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
        _uiState.update { it.copy(conversations = conversationStore.summaries()) }
    }

    fun deleteConversation(id: String) {
        if (id.isBlank() || id == CRISIS_SENTINEL_NEW_CONVERSATION_ID) return

        val state = _uiState.value
        val isActiveConversation = state.activeConversation?.id == id
        if (isActiveConversation && state.isGenerating) {
            runtime.cancelGeneration()
            generationTimeoutJob?.cancel()
            generationTimeoutJob = null
            generationJob?.cancel()
            generationJob = null
        }

        val deleted = conversationStore.delete(id)
        _uiState.update {
            val activeDeleted = it.activeConversation?.id == id
            it.copy(
                conversations = conversationStore.summaries(),
                activeConversation = if (activeDeleted) null else it.activeConversation,
                prompt = if (activeDeleted) "" else it.prompt,
                response = if (activeDeleted) null else it.response,
                isGenerating = if (activeDeleted) false else it.isGenerating,
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
        }
    }

    fun createConversation(): String {
        val conversation = conversationStore.create(mode = _uiState.value.mode)
        _uiState.update {
            it.copy(
                conversations = conversationStore.summaries(),
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
                    it.copy(conversations = conversationStore.summaries())
                } else {
                    it.copy(
                        activeConversation = null,
                        prompt = "",
                        response = null,
                        conversations = conversationStore.summaries()
                    )
                }
            }
            return
        }

        val conversation = conversationStore.load(id)
        _uiState.update {
            it.copy(
                activeConversation = conversation,
                prompt = conversation?.draftText.orEmpty(),
                response = null,
                conversations = conversationStore.summaries()
            )
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
                conversations = conversationStore.summaries()
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
    }

    fun submit() {
        val state = _uiState.value
        val prompt = state.prompt.trim()
        if (prompt.isBlank()) {
            _uiState.update { it.copy(transientMessageRes = R.string.crisis_sentinel_prompt_empty) }
            return
        }
        if (state.isGenerating) return

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

        val activeId = state.activeConversation?.id?.takeIf { it.isNotBlank() }
        val targetConversationId = when {
            // The "new conversation" route keeps its literal id for the whole screen lifetime, so
            // after the first message we must keep appending to the conversation it created —
            // otherwise every send spawns a fresh one-message conversation.
            conversationId == CRISIS_SENTINEL_NEW_CONVERSATION_ID -> activeId
            conversationId.isNotBlank() -> conversationId
            else -> activeId
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
                conversations = conversationStore.summaries(),
                prompt = "",
                isGenerating = true,
                transientMessageRes = null,
                modelStatus = currentModelStatus()
            )
        }

        startAssistantGeneration(conversation = conversationAfterUserMessage, prompt = prompt)
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
        val trimmedConversation = conversationStore.removeTrailingAssistantMessages(conversation.id)
            ?: conversation
        _uiState.update {
            it.copy(
                activeConversation = trimmedConversation,
                conversations = conversationStore.summaries(),
                isGenerating = true,
                transientMessageRes = null,
                modelStatus = currentModelStatus()
            )
        }
        startAssistantGeneration(conversation = trimmedConversation, prompt = lastPrompt)
    }

    private fun startAssistantGeneration(
        conversation: CrisisSentinelConversation,
        prompt: String
    ) {
        launchGeneration {
            val startedAtMillis = System.currentTimeMillis()
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
            val response = withContext(Dispatchers.Default) {
                engine.respondWithModel(request)
            }
            if (!currentCoroutineContext().isActive) return@launchGeneration
            val generationDurationMillis = System.currentTimeMillis() - startedAtMillis
            val conversationAfterAssistantMessage = conversationStore.appendAssistantResponse(
                id = conversation.id,
                response = response,
                generationDurationMillis = generationDurationMillis
            )
            _uiState.update {
                it.copy(
                    response = response,
                    activeConversation = conversationAfterAssistantMessage
                        ?: conversationStore.load(conversation.id),
                    conversations = conversationStore.summaries(),
                    isGenerating = false,
                    modelStatus = currentModelStatus()
                )
            }
        }
    }

    private fun launchGeneration(block: suspend () -> Unit) {
        generationJob?.cancel()
        generationTimeoutJob?.cancel()

        val job = viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                // User cancel and timeout paths update the UI before cancelling the job.
            } catch (_: Throwable) {
                runtime.cancelGeneration()
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        transientMessageRes = R.string.crisis_sentinel_error_response,
                        modelStatus = currentModelStatus()
                    )
                }
            }
        }

        generationJob = job
        val timeoutJob = viewModelScope.launch {
            delay(GENERATION_TIMEOUT_MS)
            if (generationJob === job && job.isActive) {
                runtime.cancelGeneration()
                job.cancel()
                _uiState.update {
                    it.copy(
                        isGenerating = false,
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
