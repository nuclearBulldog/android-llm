package com.slopware.androidllm.data

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.slopware.androidllm.domain.BackendType
import com.slopware.androidllm.domain.ChatMessage
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.HardwareController
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.LlmEngine
import com.slopware.androidllm.domain.Role
import com.slopware.androidllm.domain.SiliconTelemetry
import com.slopware.androidllm.domain.StreamDelta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * LiteRT-LM backed [LlmEngine] managing single-engine lifecycle and ARM64 hardware tuning on [ioDispatcher].
 */
class LiteRtLmEngine(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hardwareController: HardwareController? = null,
) : LlmEngine {
    private val appContext = context.applicationContext ?: context
    private val mutex = Mutex()
    private val generation = AtomicLong(0L)

    private var engine: Engine? = null
    @Volatile
    private var activeConversation: Conversation? = null
    private var activeKey: EngineKey? = null

    override suspend fun initialize(config: LlmConfig) {
        val startGen = generation.get()
        withContext(ioDispatcher) {
            mutex.withLock {
                initializeEngineLocked(config, withVision = false, expectedGeneration = startGen)
            }
        }
    }

    override fun streamMessage(
        history: List<ChatMessage>,
        userMessage: ChatMessage,
        config: LlmConfig,
    ): Flow<StreamDelta> = flow {
        val startGen = generation.get()
        val needsVision = userMessage.imagePath != null ||
            history.any { !it.imagePath.isNullOrBlank() && File(it.imagePath).isFile }
        mutex.withLock {
            val targetKey = EngineKey(
                modelPath = config.modelPath,
                backend = config.backend,
                visionBackend = if (needsVision) config.visionBackend else null,
                cpuThreadCount = config.cpuThreadCount,
                enableMtp = config.enableMtp,
            )
            val keyMatches = activeKey == targetKey || (
                !needsVision &&
                    activeKey?.visionBackend != null &&
                    activeKey == targetKey.copy(visionBackend = activeKey?.visionBackend)
                )
            if (engine?.isInitialized() != true || !keyMatches) {
                initializeEngineLocked(config, withVision = needsVision, expectedGeneration = startGen)
            } else {
                // Re-apply thermal-guarded affinity, low-latency slack/nice, and FPCR.FZ before decode.
                applyHardwareGuard(config)
            }

            closeConversationLocked()
            val conversation = try {
                checkNotNull(engine) { "Engine is not initialized." }
                    .createConversation(buildConversationConfig(config, history))
            } catch (e: LiteRtLmJniException) {
                throw IllegalStateException("Failed to create conversation: ${e.message ?: "native error"}", e)
            } catch (e: IllegalStateException) {
                throw IllegalStateException("Conversation error: ${e.message ?: "invalid engine state"}", e)
            }

            activeConversation = conversation
            var completed = false
            try {
                conversation.sendMessageAsync(checkNotNull(userMessage.toLitertContentsOrNull())).collect { chunk ->
                    val thought = chunk.channels[THOUGHT_CHANNEL].orEmpty()
                    val text = chunk.toString()
                    if (thought.isNotEmpty() || text.isNotEmpty()) {
                        emit(StreamDelta(textDelta = text, thoughtDelta = thought))
                    }
                }
                completed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiteRtLmJniException) {
                throw IllegalStateException("Inference failed: ${e.message ?: "native error"}", e)
            } catch (e: IllegalStateException) {
                throw IllegalStateException("Inference state error: ${e.message ?: "invalid state"}", e)
            } finally {
                // LiteRT-LM's Flow wrapper uses a no-op awaitClose, so cancel native decode explicitly on stop/error.
                if (!completed && conversation.isAlive) {
                    runCatching { conversation.cancelProcess() }
                }
                closeConversationLocked()
                if (generation.get() != startGen) {
                    closeEngineLocked()
                }
            }
        }
    }.flowOn(ioDispatcher)

    override fun close() {
        generation.incrementAndGet()
        activeConversation?.let { conv ->
            if (conv.isAlive) runCatching { conv.cancelProcess() }
        }
        if (mutex.tryLock()) {
            try {
                closeConversationLocked()
                closeEngineLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    @Suppress("DEPRECATION") // Matches api.md MTP flag while remaining compatible with 0.19.1.
    private fun initializeEngineLocked(
        config: LlmConfig,
        withVision: Boolean,
        withAudio: Boolean = false,
        expectedGeneration: Long,
    ) {
        // Always release the previous Conversation/Engine first to reclaim GBs of native RAM before loading.
        closeConversationLocked()
        closeEngineLocked(nextModelPath = config.modelPath)

        if (config.modelPath.isBlank()) {
            throw IllegalStateException("No model selected. Select or download a .litertlm model in Model Hub.")
        }
        if (!File(config.modelPath).isFile) {
            throw IllegalStateException("Model file not found: ${config.modelPath}")
        }

        val telemetry = applyHardwareGuard(config, forEngineInit = true)
        val lowRam = telemetry != null && telemetry.availRamMb in 1L until LOW_RAM_EVICT_MB
        if (config.enableVmPageWarmup && !lowRam) {
            hardwareController?.warmupModelPages(config.modelPath)
        }
        ExperimentalFlags.enableSpeculativeDecoding = config.enableMtp

        val engineConfig = EngineConfig(
            modelPath = config.modelPath,
            backend = toLitertBackend(config.backend, config.cpuThreadCount),
            visionBackend = if (withVision) toLitertBackend(config.visionBackend, config.cpuThreadCount) else null,
            audioBackend = if (withAudio) toLitertBackend(config.audioBackend, config.cpuThreadCount) else null,
            cacheDir = appContext.cacheDir.path,
        )

        val newEngine = Engine(engineConfig)
        try {
            newEngine.initialize()
            if (generation.get() != expectedGeneration) {
                if (newEngine.isInitialized()) runCatching { newEngine.close() }
                throw CancellationException("Engine closed during initialization.")
            }
            engine = newEngine
            activeKey = EngineKey(
                modelPath = config.modelPath,
                backend = config.backend,
                visionBackend = if (withVision) config.visionBackend else null,
                cpuThreadCount = config.cpuThreadCount,
                enableMtp = config.enableMtp,
            )
        } catch (e: CancellationException) {
            if (newEngine.isInitialized()) runCatching { newEngine.close() }
            throw e
        } catch (e: LiteRtLmJniException) {
            if (newEngine.isInitialized()) runCatching { newEngine.close() }
            throw IllegalStateException("Failed to initialize ${config.backend} engine: ${e.message ?: "native error"}", e)
        } catch (e: IllegalStateException) {
            if (newEngine.isInitialized()) runCatching { newEngine.close() }
            throw IllegalStateException("Engine initialization error: ${e.message ?: "invalid state"}", e)
        }
    }

    private fun buildConversationConfig(config: LlmConfig, history: List<ChatMessage>): ConversationConfig {
        val initialMessages = history.mapNotNull { msg ->
            when (msg.role) {
                Role.USER -> msg.toLitertContentsOrNull(ignoreMissingImage = true)?.let { Message.user(it) }
                Role.MODEL -> msg.text.takeIf { it.isNotBlank() }?.let { Message.model(it) }
            }
        }
        return ConversationConfig(
            systemInstruction = config.systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
            initialMessages = initialMessages,
            samplerConfig = SamplerConfig(
                topK = config.topK,
                topP = config.topP,
                temperature = config.temperature,
            ),
            thinkingConfig = ThinkingConfig(
                enableThinking = config.enableThinking,
                thinkingTokenBudget = if (config.enableThinking) config.thinkingBudget else 0,
            ),
            maxOutputToken = config.maxOutputTokens,
        )
    }

    private fun toLitertBackend(type: BackendType, cpuThreadCount: Int): Backend = when (type) {
        BackendType.CPU -> Backend.CPU(threadCount = cpuThreadCount.coerceAtLeast(1))
        BackendType.GPU -> Backend.GPU()
        BackendType.NPU -> Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
    }

    private fun ChatMessage.toLitertContentsOrNull(ignoreMissingImage: Boolean = false): Contents? {
        val parts = buildList {
            imagePath
                ?.takeIf { it.isNotBlank() && (!ignoreMissingImage || File(it).isFile) }
                ?.let { add(Content.ImageFile(it)) }
            if (text.isNotEmpty() || (!ignoreMissingImage && isEmpty())) {
                add(Content.Text(text))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.let { Contents.of(it) }
    }

    private fun applyHardwareGuard(
        config: LlmConfig,
        forEngineInit: Boolean = false,
    ): SiliconTelemetry? {
        val controller = hardwareController ?: return null
        val baseline = controller.sampleTelemetry(config, runBench = false)
        if (baseline.availRamMb in 1L until LOW_RAM_EVICT_MB && config.modelPath.isNotBlank()) {
            controller.evictColdModelPages(config.modelPath)
        }
        val effectiveMode = controller.effectiveAffinity(config.coreAffinity, baseline, config)
        // Worker pthreads inherit the caller's cpus_allowed mask during Engine.initialize(); avoid pinning multi-thread init to 1 tier.
        val appliedMode = when {
            forEngineInit && config.cpuThreadCount > 1 && effectiveMode == CoreAffinityMode.PRIME_ONLY ->
                CoreAffinityMode.BIG_AND_PRIME
            forEngineInit && config.cpuThreadCount > 1 && effectiveMode == CoreAffinityMode.EFFICIENCY_ONLY ->
                CoreAffinityMode.ALL_CORES
            else -> effectiveMode
        }
        controller.configureLowLatencyThread(config.enableLowLatencyThread)
        return controller.applyTuning(config.copy(coreAffinity = appliedMode))
    }

    private fun closeConversationLocked() {
        val conv = activeConversation ?: return
        activeConversation = null
        if (conv.isAlive) runCatching { conv.close() }
    }

    private fun closeEngineLocked(nextModelPath: String? = null) {
        val eng = engine
        val prevPath = activeKey?.modelPath
        engine = null
        activeKey = null
        if (eng != null && eng.isInitialized()) runCatching { eng.close() }
        if (!prevPath.isNullOrBlank() && prevPath != nextModelPath) {
            // Reclaim mmap'd page cache after closing JNI handle on model switch or unload.
            hardwareController?.evictColdModelPages(prevPath)
        }
    }

    private data class EngineKey(
        val modelPath: String,
        val backend: BackendType,
        val visionBackend: BackendType?,
        val cpuThreadCount: Int,
        val enableMtp: Boolean,
    )

    private companion object {
        const val THOUGHT_CHANNEL = "thought"
        const val LOW_RAM_EVICT_MB = 450L
    }
}
