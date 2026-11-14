package com.slopware.androidllm.domain

enum class Role {
    USER,
    MODEL,
}

enum class BackendType {
    CPU,
    GPU,
    NPU,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class CoreAffinityMode {
    ALL_CORES,
    BIG_AND_PRIME,
    PRIME_ONLY,
    EFFICIENCY_ONLY,
}

data class AccessibilityConfig(
    val highContrast: Boolean = false,
    val fontScale: Float = 1.0f,
    val reduceMotion: Boolean = false,
    val largeTouchTargets: Boolean = false,
)

data class LlmConfig(
    val modelPath: String = "",
    val backend: BackendType = BackendType.GPU,
    val visionBackend: BackendType = BackendType.GPU,
    val audioBackend: BackendType = BackendType.CPU,
    val enableMtp: Boolean = true,
    val enableFpcrFlushToZero: Boolean = true,
    val enableVmPageWarmup: Boolean = true,
    val enableLowLatencyThread: Boolean = true,
    val enableThermalGuard: Boolean = true,
    val coreAffinity: CoreAffinityMode = CoreAffinityMode.BIG_AND_PRIME,
    val cpuThreadCount: Int = 4,
    val enableThinking: Boolean = true,
    val thinkingBudget: Int = 1024,
    val temperature: Double = 0.8,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val maxOutputTokens: Int = 2048,
    val systemPrompt: String = "You are a helpful assistant.",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accessibility: AccessibilityConfig = AccessibilityConfig(),
)

data class HfModelEntry(
    val id: String,
    val title: String,
    val repoId: String,
    val fileName: String,
    val sizeLabel: String,
    val sizeBytes: Long,
    val tags: List<String>,
    val description: String,
    val recommendedBackend: BackendType = BackendType.GPU,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repoId/resolve/main/$fileName?download=true"
}

data class ModelDownloadState(
    val repoFileKey: String = "",
    val fileName: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val completedPath: String? = null,
)

data class ArmCoreInfo(
    val coreIndex: Int,
    val maxFreqKhz: Int,
    val curFreqKhz: Int,
    val tier: String,
)

data class SiliconTelemetry(
    val nativeLoaded: Boolean,
    val arch: String,
    val fpcrHex: String,
    val flushToZeroActive: Boolean,
    val l1DCacheLineBytes: Int,
    val l1ICacheLineBytes: Int,
    val timerFreqMhz: Int,
    val activeAffinityMaskHex: String,
    val neonGflops: Double,
    val thermalCelsius: Float?,
    val availRamMb: Long,
    val totalRamMb: Long,
    val cores: List<ArmCoreInfo>,
    val machineCodeDisassembly: List<String>,
    val lastVmAction: String = "Idle",
    val timerSlackNs: Long = -1L,
    val thermalThrottled: Boolean = false,
)

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val thought: String = "",
    val imagePath: String? = null,
    val timestampMs: Long,
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAtMs: Long,
)

data class StreamDelta(
    val textDelta: String = "",
    val thoughtDelta: String = "",
)
