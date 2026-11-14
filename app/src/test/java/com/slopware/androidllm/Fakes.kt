package com.slopware.androidllm

import com.slopware.androidllm.domain.ArmCoreInfo
import com.slopware.androidllm.domain.ChatMessage
import com.slopware.androidllm.domain.ChatRepository
import com.slopware.androidllm.domain.ChatSession
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.HardwareController
import com.slopware.androidllm.domain.HfModelEntry
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.LlmEngine
import com.slopware.androidllm.domain.ModelDownloadState
import com.slopware.androidllm.domain.SiliconTelemetry
import com.slopware.androidllm.domain.StreamDelta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

class FakeLlmEngine : LlmEngine {
    val initializedConfigs = mutableListOf<LlmConfig>()
    var initializeGate: CompletableDeferred<Unit>? = null
    var initializeError: Throwable? = null
    var closeCount = 0

    var lastHistory: List<ChatMessage>? = null
    var lastUserMessage: ChatMessage? = null
    var lastStreamConfig: LlmConfig? = null
    var cannedDeltas: List<StreamDelta> = emptyList()
    var streamError: Throwable? = null
    var streamHandler: ((List<ChatMessage>, ChatMessage, LlmConfig) -> Flow<StreamDelta>)? = null

    override suspend fun initialize(config: LlmConfig) {
        initializeGate?.await()
        initializeError?.let { throw it }
        initializedConfigs += config
    }

    override fun streamMessage(
        history: List<ChatMessage>,
        userMessage: ChatMessage,
        config: LlmConfig,
    ): Flow<StreamDelta> {
        lastHistory = history
        lastUserMessage = userMessage
        lastStreamConfig = config
        return streamHandler?.invoke(history, userMessage, config) ?: flow {
            for (delta in cannedDeltas) emit(delta)
            streamError?.let { throw it }
        }
    }

    override fun close() {
        closeCount++
    }
}

class FakeChatRepository(
    initialSessions: List<ChatSession> = emptyList(),
    initialConfig: LlmConfig = LlmConfig(),
) : ChatRepository {
    private val _sessions = MutableStateFlow(initialSessions.sortedByDescending { it.updatedAtMs })
    override val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _config = MutableStateFlow(initialConfig)
    override val config: StateFlow<LlmConfig> = _config.asStateFlow()

    private val _downloadState = MutableStateFlow(ModelDownloadState())
    override val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    var discoveredModelsList: List<String> = emptyList()
    var importedModelPath: String = "/app/files/models/imported.litertlm"
    var curatedCatalog: List<HfModelEntry> = emptyList()
    var repoSearchResults: List<HfModelEntry> = emptyList()
    var searchError: Throwable? = null
    var downloadGate: CompletableDeferred<Unit>? = null
    var downloadResultPath: String = "/app/files/models/downloaded.litertlm"
    var downloadError: Throwable? = null
    var allowDeleteModel: Boolean = true

    val deletedIds = mutableListOf<String>()
    val deletedModelPaths = mutableListOf<String>()
    var cancelDownloadCount = 0

    override fun upsertSession(session: ChatSession) {
        _sessions.value = (_sessions.value.filterNot { it.id == session.id } + session)
            .sortedByDescending { it.updatedAtMs }
    }

    override fun deleteSession(id: String) {
        deletedIds += id
        _sessions.value = _sessions.value.filterNot { it.id == id }
    }

    override fun updateConfig(config: LlmConfig) {
        _config.value = config
    }

    override fun discoverModels(): List<String> = discoveredModelsList

    override fun importModelUri(uriString: String, fileName: String): String = importedModelPath

    override fun deleteModelFile(path: String): Boolean {
        if (!allowDeleteModel) return false
        deletedModelPaths += path
        discoveredModelsList = discoveredModelsList.filterNot { it == path }
        return true
    }

    override fun catalogModels(): List<HfModelEntry> = curatedCatalog

    override suspend fun fetchRepoLitertFiles(repoId: String, hfToken: String): List<HfModelEntry> {
        searchError?.let { throw it }
        return repoSearchResults
    }

    override suspend fun downloadHfModel(entry: HfModelEntry, hfToken: String): String {
        val key = "${entry.repoId}/${entry.fileName}"
        _downloadState.value = ModelDownloadState(
            repoFileKey = key,
            fileName = entry.fileName,
            bytesDownloaded = entry.sizeBytes / 2,
            totalBytes = entry.sizeBytes,
            speedBytesPerSec = 10_000_000L,
            isDownloading = true,
        )
        downloadGate?.await()
        downloadError?.let { err ->
            _downloadState.value = _downloadState.value.copy(isDownloading = false, error = err.message)
            throw err
        }
        _downloadState.value = ModelDownloadState(
            repoFileKey = key,
            fileName = entry.fileName,
            bytesDownloaded = entry.sizeBytes,
            totalBytes = entry.sizeBytes,
            isDownloading = false,
            completedPath = downloadResultPath,
        )
        if (downloadResultPath !in discoveredModelsList) {
            discoveredModelsList = discoveredModelsList + downloadResultPath
        }
        return downloadResultPath
    }

    override fun cancelDownload() {
        cancelDownloadCount++
        _downloadState.value = _downloadState.value.copy(isDownloading = false)
    }
}

class FakeHardwareController : HardwareController {
    val appliedConfigs = mutableListOf<LlmConfig>()
    val sampledConfigs = mutableListOf<Pair<LlmConfig, Boolean>>()
    val warmedUpModels = mutableListOf<Pair<String, Long>>()
    val evictedModels = mutableListOf<String>()
    val latencyConfigs = mutableListOf<Pair<Boolean, Int>>()
    var lastBenchGflops: Double = 42.5
    var fakeThermalCelsius: Float = 36.5f
    var lastVmAction: String = "Idle"
    var fakeTimerSlackNs: Long = -1L

    override fun applyTuning(config: LlmConfig): SiliconTelemetry {
        appliedConfigs += config
        if (config.enableLowLatencyThread) {
            configureLowLatencyThread(enable = true, nice = -4)
        }
        return buildTelemetry(config, neonGflops = 0.0)
    }

    override fun sampleTelemetry(config: LlmConfig, runBench: Boolean): SiliconTelemetry {
        sampledConfigs += config to runBench
        return buildTelemetry(config, neonGflops = if (runBench) lastBenchGflops else 0.0)
    }

    override fun warmupModelPages(modelPath: String, prefetchBytes: Long): String {
        warmedUpModels += modelPath to prefetchBytes
        lastVmAction = "Warmup ${prefetchBytes / (1024L * 1024L)}MB"
        return lastVmAction
    }

    override fun evictColdModelPages(modelPath: String): String {
        evictedModels += modelPath
        lastVmAction = if (modelPath.isBlank()) "Evict skipped (no model)" else "Evicted cold pages"
        return lastVmAction
    }

    override fun configureLowLatencyThread(enable: Boolean, nice: Int): Long {
        latencyConfigs += enable to nice
        fakeTimerSlackNs = if (enable) 50_000L else 0L
        return fakeTimerSlackNs
    }

    override fun effectiveAffinity(
        requested: CoreAffinityMode,
        telemetry: SiliconTelemetry,
        config: LlmConfig,
    ): CoreAffinityMode =
        if (config.enableThermalGuard &&
            requested == CoreAffinityMode.PRIME_ONLY &&
            (telemetry.thermalCelsius ?: 0f) >= 78f
        ) {
            CoreAffinityMode.BIG_AND_PRIME
        } else {
            requested
        }

    private fun buildTelemetry(config: LlmConfig, neonGflops: Double) = SiliconTelemetry(
        nativeLoaded = true,
        arch = "aarch64",
        fpcrHex = if (config.enableFpcrFlushToZero) "0x03000000" else "0x00000000",
        flushToZeroActive = config.enableFpcrFlushToZero,
        l1DCacheLineBytes = 64,
        l1ICacheLineBytes = 64,
        timerFreqMhz = 19,
        activeAffinityMaskHex = "0xF0",
        neonGflops = neonGflops,
        thermalCelsius = fakeThermalCelsius,
        availRamMb = 5000L,
        totalRamMb = 12288L,
        cores = listOf(ArmCoreInfo(0, 1800000, 1200000, "Little")),
        machineCodeDisassembly = listOf("d53b4400  mrs x0, fpcr"),
        lastVmAction = lastVmAction,
        timerSlackNs = fakeTimerSlackNs,
        thermalThrottled = config.enableThermalGuard &&
            config.coreAffinity == CoreAffinityMode.PRIME_ONLY &&
            fakeThermalCelsius >= 78f,
    )
}
