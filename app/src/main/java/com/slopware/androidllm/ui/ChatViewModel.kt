package com.slopware.androidllm.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.slopware.androidllm.domain.BackendType
import com.slopware.androidllm.domain.ChatMessage
import com.slopware.androidllm.domain.ChatRepository
import com.slopware.androidllm.domain.ChatSession
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.HardwareController
import com.slopware.androidllm.domain.HfModelEntry
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.LlmEngine
import com.slopware.androidllm.domain.ModelDownloadState
import com.slopware.androidllm.domain.Role
import com.slopware.androidllm.domain.SiliconTelemetry
import com.slopware.androidllm.domain.ThemeMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null,
    val activeMessages: List<ChatMessage> = emptyList(),
    val config: LlmConfig = LlmConfig(),
    val discoveredModels: List<String> = emptyList(),
    val catalogModels: List<HfModelEntry> = emptyList(),
    val hfSearchResults: List<HfModelEntry> = emptyList(),
    val isSearchingHf: Boolean = false,
    val downloadState: ModelDownloadState = ModelDownloadState(),
    val telemetry: SiliconTelemetry? = null,
    val isBenchmarking: Boolean = false,
    val inputText: String = "",
    val attachedImagePath: String? = null,
    val isInitializing: Boolean = false,
    val isGenerating: Boolean = false,
    val partialAnswer: String = "",
    val partialThought: String = "",
    val errorMessage: String? = null,
)

private data class EngineKey(
    val modelPath: String,
    val backend: BackendType,
    val visionBackend: BackendType,
    val audioBackend: BackendType,
    val cpuThreadCount: Int,
    val enableMtp: Boolean,
) {
    companion object {
        fun from(c: LlmConfig) = EngineKey(
            c.modelPath,
            c.backend,
            c.visionBackend,
            c.audioBackend,
            c.cpuThreadCount,
            c.enableMtp,
        )
    }
}

/**
 * Coordinates chat state, Model Hub downloads, ARM64 hardware telemetry, and streaming inference
 * without Android Context or LiteRT-LM JNI dependencies.
 */
class ChatViewModel(
    private val engine: LlmEngine,
    private val repo: ChatRepository,
    private val hardwareController: HardwareController? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessions = repo.sessions.value,
            config = repo.config.value,
            catalogModels = repo.catalogModels(),
            downloadState = repo.downloadState.value,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var initializedEngineKey: EngineKey? = null
    private var initJob: Job? = null
    private var generationJob: Job? = null
    private var downloadJob: Job? = null
    private var finalizeActiveRun: ((preservePartial: Boolean) -> Unit)? = null

    init {
        viewModelScope.launch {
            repo.sessions.collect { sessions ->
                _uiState.update { state ->
                    val active = sessions.firstOrNull { it.id == state.activeSessionId }
                    state.copy(sessions = sessions, activeMessages = active?.messages.orEmpty())
                }
            }
        }
        viewModelScope.launch {
            repo.config.collect { cfg ->
                _uiState.update { it.copy(config = cfg) }
            }
        }
        viewModelScope.launch {
            repo.downloadState.collect { dl ->
                _uiState.update { it.copy(downloadState = dl) }
            }
        }
        refreshModels()
        sampleTelemetry(runBench = false)
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun attachImage(path: String?) {
        _uiState.update { it.copy(attachedImagePath = path) }
    }

    /**
     * Handles incoming Android Assistant, Voice Command, Share, or Process-Text intents by starting
     * a fresh session with the extracted [prompt] and/or [imagePath] and optionally sending immediately.
     */
    fun handleAssistantIntent(
        prompt: String?,
        imagePath: String? = null,
        autoSend: Boolean = false,
    ) {
        val cleanPrompt = prompt?.trim().orEmpty()
        val cleanImage = imagePath?.trim()?.takeIf { it.isNotEmpty() }
        cancelGeneration(preservePartial = true)
        _uiState.update {
            it.copy(
                activeSessionId = null,
                activeMessages = emptyList(),
                inputText = cleanPrompt,
                attachedImagePath = cleanImage,
                partialAnswer = "",
                partialThought = "",
                errorMessage = null,
            )
        }
        if (autoSend && _uiState.value.config.modelPath.isNotBlank() &&
            (cleanPrompt.isNotEmpty() || cleanImage != null)
        ) {
            sendMessage()
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        if (state.isGenerating || state.isInitializing) return
        val prompt = state.inputText.trim()
        val imagePath = state.attachedImagePath
        if (prompt.isEmpty() && imagePath == null) return

        val now = System.currentTimeMillis()
        val existing = state.sessions.firstOrNull { it.id == state.activeSessionId }
        val sessionId = existing?.id ?: UUID.randomUUID().toString()
        val historyBeforeUser = existing?.messages.orEmpty()
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = Role.USER,
            text = prompt,
            imagePath = imagePath,
            timestampMs = now,
        )
        val title = existing?.title?.takeIf { it.isNotBlank() }
            ?: prompt.lineSequence().firstOrNull().orEmpty().take(40).ifBlank { "Image chat" }
        val updatedSession = ChatSession(
            id = sessionId,
            title = title,
            messages = historyBeforeUser + userMessage,
            updatedAtMs = now,
        )

        persistActiveSession(updatedSession)
        _uiState.update { it.copy(inputText = "", attachedImagePath = null, errorMessage = null) }
        launchGeneration(sessionId, historyBeforeUser, userMessage, state.config)
    }

    fun stopGeneration() {
        cancelGeneration(preservePartial = true)
    }

    fun regenerateLast() {
        val state = _uiState.value
        if (state.isGenerating || state.isInitializing) return
        val session = state.sessions.firstOrNull { it.id == state.activeSessionId } ?: return
        val trimmed = if (session.messages.lastOrNull()?.role == Role.MODEL) {
            session.messages.dropLast(1)
        } else {
            session.messages
        }
        val userMessage = trimmed.lastOrNull()?.takeIf { it.role == Role.USER } ?: return
        persistActiveSession(session.copy(messages = trimmed, updatedAtMs = System.currentTimeMillis()))
        launchGeneration(session.id, trimmed.dropLast(1), userMessage, state.config)
    }

    fun newSession() {
        cancelGeneration(preservePartial = true)
        _uiState.update {
            it.copy(
                activeSessionId = null,
                activeMessages = emptyList(),
                inputText = "",
                attachedImagePath = null,
                partialAnswer = "",
                partialThought = "",
                errorMessage = null,
            )
        }
    }

    fun selectSession(id: String) {
        if (_uiState.value.activeSessionId == id) return
        cancelGeneration(preservePartial = true)
        val target = repo.sessions.value.firstOrNull { it.id == id }
        _uiState.update {
            it.copy(
                activeSessionId = id,
                activeMessages = target?.messages.orEmpty(),
                attachedImagePath = null,
                partialAnswer = "",
                partialThought = "",
                errorMessage = null,
            )
        }
    }

    fun deleteSession(id: String) {
        if (_uiState.value.activeSessionId == id) cancelGeneration(preservePartial = false)
        repo.deleteSession(id)
        _uiState.update { state ->
            val remaining = repo.sessions.value
            val nextId = if (state.activeSessionId == id) null else state.activeSessionId
            state.copy(
                sessions = remaining,
                activeSessionId = nextId,
                activeMessages = remaining.firstOrNull { it.id == nextId }?.messages.orEmpty(),
            )
        }
    }

    /**
     * Toggles between Light and Dark comfort mode relative to [systemIsDark], reverting to
     * [ThemeMode.SYSTEM] when the target matches the OS theme so future OS shifts aren't pinned.
     */
    fun toggleQuickTheme(systemIsDark: Boolean) {
        val currentDark = when (_uiState.value.config.themeMode) {
            ThemeMode.SYSTEM -> systemIsDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val targetDark = !currentDark
        val nextMode = when {
            targetDark == systemIsDark -> ThemeMode.SYSTEM
            targetDark -> ThemeMode.DARK
            else -> ThemeMode.LIGHT
        }
        updateConfig(_uiState.value.config.copy(themeMode = nextMode))
    }

    fun updateConfig(config: LlmConfig) {
        val prev = _uiState.value.config
        val engineChanged = EngineKey.from(config) != (initializedEngineKey ?: EngineKey.from(prev))
        val tuningChanged = config.enableFpcrFlushToZero != prev.enableFpcrFlushToZero ||
            config.coreAffinity != prev.coreAffinity ||
            config.enableLowLatencyThread != prev.enableLowLatencyThread ||
            config.enableThermalGuard != prev.enableThermalGuard
        repo.updateConfig(config)
        _uiState.update { it.copy(config = config) }
        if (engineChanged) {
            initializedEngineKey = null
            if (config.modelPath.isNotBlank() && !_uiState.value.isGenerating) {
                preloadEngine(config)
            }
        }
        if (hardwareController != null) {
            viewModelScope.launch {
                val tel = withContext(ioDispatcher) {
                    if (tuningChanged) {
                        hardwareController.applyTuning(config)
                        hardwareController.configureLowLatencyThread(config.enableLowLatencyThread)
                    }
                    enrichTelemetry(hardwareController, config, hardwareController.sampleTelemetry(config))
                }
                _uiState.update { it.copy(telemetry = tel) }
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            try {
                val models = withContext(ioDispatcher) { repo.discoverModels() }
                _uiState.update { it.copy(discoveredModels = models) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update { it.copy(errorMessage = t.message ?: "Failed to scan models.") }
            }
        }
    }

    fun importModel(uriString: String, fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isInitializing = true, errorMessage = null) }
            try {
                val importedPath = withContext(ioDispatcher) { repo.importModelUri(uriString, fileName) }
                val models = withContext(ioDispatcher) { repo.discoverModels() }
                _uiState.update { it.copy(discoveredModels = models, isInitializing = false) }
                updateConfig(_uiState.value.config.copy(modelPath = importedPath))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(isInitializing = false, errorMessage = t.message ?: "Failed to import model.")
                }
            }
        }
    }

    fun deleteModel(path: String) {
        viewModelScope.launch {
            try {
                val deleted = withContext(ioDispatcher) { repo.deleteModelFile(path) }
                if (!deleted) {
                    _uiState.update {
                        it.copy(errorMessage = "Only app-imported models can be deleted from the app.")
                    }
                    return@launch
                }
                val models = withContext(ioDispatcher) { repo.discoverModels() }
                _uiState.update { it.copy(discoveredModels = models) }
                if (_uiState.value.config.modelPath == path) {
                    initializedEngineKey = null
                    engine.close()
                    updateConfig(_uiState.value.config.copy(modelPath = ""))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update { it.copy(errorMessage = t.message ?: "Failed to delete model.") }
            }
        }
    }

    fun searchHfRepo(repoId: String, token: String = "") {
        val trimmed = repoId.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a HuggingFace repository (owner/repo).") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingHf = true, errorMessage = null) }
            try {
                val results = withContext(ioDispatcher) { repo.fetchRepoLitertFiles(trimmed, token.trim()) }
                _uiState.update { it.copy(hfSearchResults = results, isSearchingHf = false) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(isSearchingHf = false, errorMessage = t.message ?: "HuggingFace lookup failed.")
                }
            }
        }
    }

    fun downloadHfModel(entry: HfModelEntry, token: String = "") {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                val localPath = withContext(ioDispatcher) { repo.downloadHfModel(entry, token.trim()) }
                val models = withContext(ioDispatcher) { repo.discoverModels() }
                _uiState.update { it.copy(discoveredModels = models) }
                updateConfig(_uiState.value.config.copy(modelPath = localPath))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update { it.copy(errorMessage = t.message ?: "Download failed.") }
            }
        }
    }

    fun cancelDownload() {
        repo.cancelDownload()
        downloadJob?.cancel()
        downloadJob = null
    }

    fun sampleTelemetry(runBench: Boolean = false) {
        val hc = hardwareController ?: return
        viewModelScope.launch {
            if (runBench) _uiState.update { it.copy(isBenchmarking = true) }
            try {
                val cfg = _uiState.value.config
                val tel = withContext(ioDispatcher) {
                    enrichTelemetry(hc, cfg, hc.sampleTelemetry(cfg, runBench))
                }
                _uiState.update { it.copy(telemetry = tel, isBenchmarking = false) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(isBenchmarking = false, errorMessage = t.message ?: "Telemetry read failed.")
                }
            }
        }
    }

    fun evictModelPageCache() {
        val hc = hardwareController ?: return
        viewModelScope.launch {
            try {
                val cfg = _uiState.value.config
                val (action, tel) = withContext(ioDispatcher) {
                    val vmMsg = hc.evictColdModelPages(cfg.modelPath)
                    val sampled = enrichTelemetry(hc, cfg, hc.sampleTelemetry(cfg))
                    vmMsg to sampled
                }
                val updated = if (action.isNotBlank()) tel.copy(lastVmAction = action) else tel
                _uiState.update { it.copy(telemetry = updated) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update { it.copy(errorMessage = t.message ?: "Page cache purge failed.") }
            }
        }
    }

    fun onTrimMemory(level: Int) {
        // Matches ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW (10) without coupling ViewModel to Android.
        if (level >= TRIM_MEMORY_RUNNING_LOW_LEVEL) {
            evictModelPageCache()
        }
    }

    private fun enrichTelemetry(
        hc: HardwareController,
        config: LlmConfig,
        raw: SiliconTelemetry,
    ): SiliconTelemetry {
        val effective = hc.effectiveAffinity(config.coreAffinity, raw, config)
        val downshifted = config.coreAffinity == CoreAffinityMode.PRIME_ONLY &&
            effective != CoreAffinityMode.PRIME_ONLY
        return raw.copy(thermalThrottled = raw.thermalThrottled || downshifted)
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun preloadEngine(config: LlmConfig) {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            try {
                ensureInitialized(config)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(isInitializing = false, errorMessage = t.message ?: "Failed to initialize model.")
                }
            }
        }
    }

    private suspend fun ensureInitialized(config: LlmConfig) {
        val targetKey = EngineKey.from(config)
        if (initializedEngineKey == targetKey) return
        _uiState.update { it.copy(isInitializing = true, errorMessage = null) }
        try {
            withContext(ioDispatcher) { engine.initialize(config) }
            initializedEngineKey = targetKey
        } finally {
            _uiState.update { it.copy(isInitializing = false) }
        }
    }

    private fun launchGeneration(
        sessionId: String,
        historyBeforeUser: List<ChatMessage>,
        userMessage: ChatMessage,
        config: LlmConfig,
    ) {
        val answerBuilder = StringBuilder()
        val thoughtBuilder = StringBuilder()
        val committed = AtomicBoolean(false)

        fun finalize(preservePartial: Boolean) {
            if (!committed.compareAndSet(false, true)) return
            val answer = answerBuilder.toString()
            val thought = thoughtBuilder.toString()
            if (preservePartial && (answer.isNotEmpty() || thought.isNotEmpty())) {
                val session = repo.sessions.value.firstOrNull { it.id == sessionId }
                if (session != null) {
                    val assistantMsg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = Role.MODEL,
                        text = answer,
                        thought = thought,
                        timestampMs = System.currentTimeMillis(),
                    )
                    val updated = session.copy(
                        messages = session.messages + assistantMsg,
                        updatedAtMs = assistantMsg.timestampMs,
                    )
                    repo.upsertSession(updated)
                    _uiState.update { state ->
                        state.copy(
                            sessions = repo.sessions.value,
                            activeMessages = if (state.activeSessionId == sessionId) updated.messages else state.activeMessages,
                        )
                    }
                }
            }
            _uiState.update { it.copy(isGenerating = false, partialAnswer = "", partialThought = "") }
        }

        val finalizeRef: (Boolean) -> Unit = ::finalize
        finalizeActiveRun = finalizeRef
        generationJob = viewModelScope.launch {
            try {
                val needsVision = !userMessage.imagePath.isNullOrBlank() ||
                    historyBeforeUser.any { !it.imagePath.isNullOrBlank() }
                if (needsVision) {
                    // Skip text-only ensureInitialized so streamMessage initializes once with visionBackend enabled.
                    initJob?.cancel()
                } else {
                    // Join any matching preload instead of racing two Engine.initialize() calls.
                    initJob?.join()
                    ensureInitialized(config)
                }
                _uiState.update {
                    it.copy(isGenerating = true, partialAnswer = "", partialThought = "", errorMessage = null)
                }
                val coalesceTo60Fps = ioDispatcher === Dispatchers.IO
                var lastEmitNs = 0L
                engine.streamMessage(historyBeforeUser, userMessage, config)
                    .flowOn(ioDispatcher)
                    .collect { delta ->
                        if (delta.thoughtDelta.isNotEmpty()) thoughtBuilder.append(delta.thoughtDelta)
                        if (delta.textDelta.isNotEmpty()) answerBuilder.append(delta.textDelta)
                        val nowNs = System.nanoTime()
                        // Coalesce rapid token updates to a 32ms (~30Hz) frame budget so text layout doesn't saturate SoC.
                        if (!coalesceTo60Fps || lastEmitNs == 0L || nowNs - lastEmitNs >= STREAM_FRAME_BUDGET_NS) {
                            lastEmitNs = nowNs
                            _uiState.update {
                                it.copy(
                                    partialThought = thoughtBuilder.toString(),
                                    partialAnswer = answerBuilder.toString(),
                                )
                            }
                        }
                    }
                initializedEngineKey = EngineKey.from(config)
                finalize(true)
            } catch (e: CancellationException) {
                finalize(true)
                throw e
            } catch (t: Throwable) {
                finalize(true)
                _uiState.update {
                    it.copy(isInitializing = false, errorMessage = t.message ?: "Generation failed.")
                }
            } finally {
                if (finalizeActiveRun === finalizeRef) finalizeActiveRun = null
            }
        }
    }

    private fun cancelGeneration(preservePartial: Boolean) {
        val job = generationJob ?: return
        generationJob = null
        val finalize = finalizeActiveRun
        finalizeActiveRun = null
        job.cancel()
        finalize?.invoke(preservePartial)
    }

    private fun persistActiveSession(session: ChatSession) {
        repo.upsertSession(session)
        _uiState.update {
            it.copy(sessions = repo.sessions.value, activeSessionId = session.id, activeMessages = session.messages)
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        initJob?.cancel()
        downloadJob?.cancel()
        engine.close()
    }

    companion object {
        private const val STREAM_FRAME_BUDGET_NS = 32_000_000L
        private const val TRIM_MEMORY_RUNNING_LOW_LEVEL = 10

        fun factory(
            engine: LlmEngine,
            repo: ChatRepository,
            hardwareController: HardwareController? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(engine, repo, hardwareController) as T
            }
    }
}
