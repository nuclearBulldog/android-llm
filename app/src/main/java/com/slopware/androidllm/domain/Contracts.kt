package com.slopware.androidllm.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over on-device LLM inference so UI and JVM tests do not depend on LiteRT-LM JNI.
 */
interface LlmEngine {
    suspend fun initialize(config: LlmConfig)

    fun streamMessage(
        history: List<ChatMessage>,
        userMessage: ChatMessage,
        config: LlmConfig,
    ): Flow<StreamDelta>

    fun close()
}

/**
 * Persists chat sessions/settings and manages local, SAF-imported, and HuggingFace Hub models.
 */
interface ChatRepository {
    val sessions: StateFlow<List<ChatSession>>
    val config: StateFlow<LlmConfig>
    val downloadState: StateFlow<ModelDownloadState>

    fun upsertSession(session: ChatSession)

    fun deleteSession(id: String)

    fun updateConfig(config: LlmConfig)

    fun discoverModels(): List<String>

    fun importModelUri(uriString: String, fileName: String): String

    fun deleteModelFile(path: String): Boolean

    fun catalogModels(): List<HfModelEntry>

    suspend fun fetchRepoLitertFiles(repoId: String, hfToken: String = ""): List<HfModelEntry>

    suspend fun downloadHfModel(entry: HfModelEntry, hfToken: String = ""): String

    fun cancelDownload()
}

/**
 * Low-level hardware tuner applying CPU affinity/FPCR tuning and sampling SoC telemetry.
 */
interface HardwareController {
    fun applyTuning(config: LlmConfig): SiliconTelemetry

    fun sampleTelemetry(config: LlmConfig, runBench: Boolean = false): SiliconTelemetry

    fun warmupModelPages(modelPath: String, prefetchBytes: Long = 32L * 1024L * 1024L): String

    fun evictColdModelPages(modelPath: String): String

    fun configureLowLatencyThread(enable: Boolean, nice: Int = -4): Long

    fun effectiveAffinity(
        requested: CoreAffinityMode,
        telemetry: SiliconTelemetry,
        config: LlmConfig,
    ): CoreAffinityMode
}
