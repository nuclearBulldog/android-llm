package com.slopware.androidllm.data

import android.content.Context
import android.net.Uri
import com.slopware.androidllm.domain.AccessibilityConfig
import com.slopware.androidllm.domain.BackendType
import com.slopware.androidllm.domain.ChatMessage
import com.slopware.androidllm.domain.ChatRepository
import com.slopware.androidllm.domain.ChatSession
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.HfModelEntry
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.ModelDownloadState
import com.slopware.androidllm.domain.Role
import com.slopware.androidllm.domain.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-backed [ChatRepository] managing JSON persistence, local/SAF models, and resumable
 * HuggingFace Hub downloads inside `filesDir/models/`.
 */
class LocalChatRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatRepository {
    private val appContext = context.applicationContext ?: context
    private val lock = Any()
    private val persistScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val sessionsWriteMutex = Mutex()
    private val configWriteMutex = Mutex()
    private val downloadMutex = Mutex()
    private val downloadCancelled = AtomicBoolean(false)
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private val sessionsFile = File(appContext.filesDir, "sessions.json")
    private val configFile = File(appContext.filesDir, "llm_config.json")
    private val internalModelsDir = File(appContext.filesDir, "models")

    private val _sessions = MutableStateFlow(loadSessions())
    override val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _config = MutableStateFlow(loadConfig())
    override val config: StateFlow<LlmConfig> = _config.asStateFlow()

    private val _downloadState = MutableStateFlow(ModelDownloadState())
    override val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    override fun upsertSession(session: ChatSession) {
        synchronized(lock) {
            _sessions.value = (_sessions.value.filterNot { it.id == session.id } + session)
                .sortedByDescending { it.updatedAtMs }
        }
        scheduleSessionsPersist()
    }

    override fun deleteSession(id: String) {
        synchronized(lock) {
            _sessions.value = _sessions.value.filterNot { it.id == id }
        }
        scheduleSessionsPersist()
    }

    override fun updateConfig(config: LlmConfig) {
        synchronized(lock) {
            _config.value = config
        }
        persistScope.launch {
            configWriteMutex.withLock {
                writeConfig(synchronized(lock) { _config.value })
            }
        }
    }

    private fun scheduleSessionsPersist() {
        persistScope.launch {
            sessionsWriteMutex.withLock {
                writeSessions(synchronized(lock) { _sessions.value })
            }
        }
    }

    override fun discoverModels(): List<String> =
        listOf(internalModelsDir, File(ADB_MODEL_DIR))
            .flatMap { dir ->
                dir.listFiles()
                    ?.filter { file ->
                        file.isFile && MODEL_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }
                    }
                    .orEmpty()
            }
            .map { it.absolutePath }
            .distinct()
            .sorted()

    override fun importModelUri(uriString: String, fileName: String): String {
        internalModelsDir.mkdirs()
        val finalName = sanitizeModelFileName(fileName)
        val destFile = File(internalModelsDir, finalName)
        // Stage into a .part file so an interrupted multi-GB copy never shows up in discoverModels().
        val partFile = File(internalModelsDir, "$finalName.part")

        val input = appContext.contentResolver.openInputStream(Uri.parse(uriString))
            ?: throw IllegalStateException("Unable to open model URI: $uriString")
        try {
            input.use { src ->
                partFile.outputStream().use { dst -> src.copyTo(dst) }
            }
            finalizePartFile(partFile, destFile)
            return destFile.absolutePath
        } finally {
            if (partFile.exists()) partFile.delete()
        }
    }

    override fun deleteModelFile(path: String): Boolean {
        val root = runCatching { internalModelsDir.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        // Restrict deletion strictly to app-managed filesDir/models/ to avoid touching /data/local/tmp or arbitrary files.
        val insideModelsDir = target.path.startsWith(root.path + File.separator)
        if (!insideModelsDir || !target.isFile) return false
        return target.delete()
    }

    override fun catalogModels(): List<HfModelEntry> = HuggingFaceCatalog.curatedModels

    override suspend fun fetchRepoLitertFiles(repoId: String, hfToken: String): List<HfModelEntry> =
        HuggingFaceCatalog.fetchRepoLitertFiles(repoId, hfToken, ioDispatcher)

    override suspend fun downloadHfModel(entry: HfModelEntry, hfToken: String): String =
        withContext(ioDispatcher) {
            downloadMutex.withLock {
                downloadCancelled.set(false)
                internalModelsDir.mkdirs()
                val safeName = sanitizeModelFileName(entry.fileName)
                val destFile = File(internalModelsDir, safeName)
                val partFile = File(internalModelsDir, "$safeName.part")
                val repoFileKey = "${entry.repoId}/${entry.fileName}"

                try {
                    if (destFile.isFile && (entry.sizeBytes <= 0L || destFile.length() == entry.sizeBytes)) {
                        _downloadState.value = ModelDownloadState(
                            repoFileKey = repoFileKey,
                            fileName = safeName,
                            bytesDownloaded = destFile.length(),
                            totalBytes = destFile.length(),
                            completedPath = destFile.absolutePath,
                        )
                        return@withLock destFile.absolutePath
                    }

                    val resultPath = executeResumableDownload(entry, hfToken, repoFileKey, safeName, partFile, destFile)
                    _downloadState.value = ModelDownloadState(
                        repoFileKey = repoFileKey,
                        fileName = safeName,
                        bytesDownloaded = destFile.length(),
                        totalBytes = destFile.length(),
                        isDownloading = false,
                        completedPath = resultPath,
                    )
                    resultPath
                } catch (e: CancellationException) {
                    _downloadState.update { it.copy(isDownloading = false) }
                    throw e
                } catch (e: Exception) {
                    _downloadState.update {
                        it.copy(isDownloading = false, error = e.message ?: "Download failed")
                    }
                    throw e
                } finally {
                    activeConnection = null
                }
            }
        }

    override fun cancelDownload() {
        downloadCancelled.set(true)
        activeConnection?.disconnect()
        _downloadState.update { it.copy(isDownloading = false) }
    }

    private suspend fun executeResumableDownload(
        entry: HfModelEntry,
        hfToken: String,
        repoFileKey: String,
        safeName: String,
        partFile: File,
        destFile: File,
    ): String {
        var existingBytes = if (partFile.isFile) partFile.length() else 0L
        var conn = openWithRedirects(entry.downloadUrl, hfToken, existingBytes)
        activeConnection = conn

        if (conn.responseCode == 416) {
            conn.disconnect()
            partFile.delete()
            existingBytes = 0L
            conn = openWithRedirects(entry.downloadUrl, hfToken, 0L)
            activeConnection = conn
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val hint = if (code == 401 || code == 403) " (gated model — add an HF token)" else ""
            conn.disconnect()
            throw IllegalStateException("Download HTTP $code for ${entry.fileName}$hint")
        }

        val isPartial = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
        val resumeOffset = if (isPartial) existingBytes else 0L
        val contentLen = conn.contentLengthLong.takeIf { it > 0L } ?: 0L
        val totalBytes = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
            ?: if (contentLen > 0L) resumeOffset + contentLen else entry.sizeBytes

        var downloaded = resumeOffset
        _downloadState.value = ModelDownloadState(
            repoFileKey = repoFileKey,
            fileName = safeName,
            bytesDownloaded = downloaded,
            totalBytes = totalBytes,
            isDownloading = true,
        )

        var lastReportMs = System.currentTimeMillis()
        var lastReportBytes = downloaded
        try {
            conn.inputStream.use { input ->
                FileOutputStream(partFile, isPartial).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (downloadCancelled.get()) throw CancellationException("Download cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val elapsedMs = now - lastReportMs
                        if (elapsedMs >= 150L) {
                            val speed = ((downloaded - lastReportBytes) * 1000L) / elapsedMs.coerceAtLeast(1L)
                            _downloadState.value = ModelDownloadState(
                                repoFileKey = repoFileKey,
                                fileName = safeName,
                                bytesDownloaded = downloaded,
                                totalBytes = totalBytes.coerceAtLeast(downloaded),
                                speedBytesPerSec = speed,
                                isDownloading = true,
                            )
                            lastReportMs = now
                            lastReportBytes = downloaded
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        finalizePartFile(partFile, destFile)
        return destFile.absolutePath
    }

    private fun openWithRedirects(
        startUrl: String,
        hfToken: String,
        rangeStart: Long,
    ): HttpURLConnection {
        var currentUrl = URL(startUrl)
        repeat(5) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                // Never forward HuggingFace Bearer tokens to external S3/CloudFront presigned redirect targets.
                if (hfToken.isNotBlank() && currentUrl.host.endsWith("huggingface.co", ignoreCase = true)) {
                    setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
                }
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("Empty redirect from $currentUrl")
                currentUrl = URL(currentUrl, location)
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while downloading $startUrl")
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        val total = header?.substringAfter('/', "")?.trim()?.toLongOrNull()
        return total?.takeIf { it > 0L }
    }

    private fun sanitizeModelFileName(rawName: String): String {
        val cleanBase = File(rawName).name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "model_${System.currentTimeMillis()}" }
        return if (MODEL_EXTENSIONS.any { cleanBase.endsWith(it, ignoreCase = true) }) {
            cleanBase
        } else {
            "$cleanBase.litertlm"
        }
    }

    private fun finalizePartFile(partFile: File, destFile: File) {
        if (!partFile.renameTo(destFile)) {
            destFile.delete()
            check(partFile.renameTo(destFile)) { "Failed to finalize model at ${destFile.path}" }
        }
    }

    private fun loadSessions(): List<ChatSession> = runCatching {
        if (!sessionsFile.isFile) return emptyList()
        val array = JSONArray(sessionsFile.readText())
        (0 until array.length())
            .map { array.getJSONObject(it).toChatSession() }
            .sortedByDescending { it.updatedAtMs }
    }.getOrDefault(emptyList())

    private fun writeSessions(list: List<ChatSession>) {
        val array = JSONArray().apply { list.forEach { put(it.toJson()) } }
        writeAtomically(sessionsFile, array.toString())
    }

    private fun loadConfig(): LlmConfig = runCatching {
        if (!configFile.isFile) return LlmConfig()
        JSONObject(configFile.readText()).toLlmConfig()
    }.getOrDefault(LlmConfig())

    private fun writeConfig(cfg: LlmConfig) {
        writeAtomically(configFile, cfg.toJson().toString())
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val ADB_MODEL_DIR = "/data/local/tmp/llm"
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        val MODEL_EXTENSIONS = listOf(".litertlm", ".tflite", ".task")
    }
}

private fun JSONObject.optNonNullString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private inline fun <reified E : Enum<E>> JSONObject.optEnum(key: String, fallback: E): E {
    val raw = optNonNullString(key) ?: return fallback
    return enumValues<E>().find { it.name == raw } ?: fallback
}

private fun LlmConfig.toJson(): JSONObject = JSONObject().apply {
    put("modelPath", modelPath)
    put("backend", backend.name)
    put("visionBackend", visionBackend.name)
    put("audioBackend", audioBackend.name)
    put("enableMtp", enableMtp)
    put("enableFpcrFlushToZero", enableFpcrFlushToZero)
    put("enableVmPageWarmup", enableVmPageWarmup)
    put("enableLowLatencyThread", enableLowLatencyThread)
    put("enableThermalGuard", enableThermalGuard)
    put("coreAffinity", coreAffinity.name)
    put("cpuThreadCount", cpuThreadCount)
    put("enableThinking", enableThinking)
    put("thinkingBudget", thinkingBudget)
    put("temperature", temperature)
    put("topK", topK)
    put("topP", topP)
    put("maxOutputTokens", maxOutputTokens)
    put("systemPrompt", systemPrompt)
    put("themeMode", themeMode.name)
    put("accessibility", accessibility.toJson())
}

private fun JSONObject.toLlmConfig(): LlmConfig {
    val d = LlmConfig()
    val accObj = optJSONObject("accessibility")
    return LlmConfig(
        modelPath = optNonNullString("modelPath") ?: d.modelPath,
        backend = optEnum("backend", d.backend),
        visionBackend = optEnum("visionBackend", d.visionBackend),
        audioBackend = optEnum("audioBackend", d.audioBackend),
        enableMtp = optBoolean("enableMtp", d.enableMtp),
        enableFpcrFlushToZero = optBoolean("enableFpcrFlushToZero", d.enableFpcrFlushToZero),
        enableVmPageWarmup = optBoolean("enableVmPageWarmup", d.enableVmPageWarmup),
        enableLowLatencyThread = optBoolean("enableLowLatencyThread", d.enableLowLatencyThread),
        enableThermalGuard = optBoolean("enableThermalGuard", d.enableThermalGuard),
        coreAffinity = optEnum("coreAffinity", d.coreAffinity),
        cpuThreadCount = optInt("cpuThreadCount", d.cpuThreadCount),
        enableThinking = optBoolean("enableThinking", d.enableThinking),
        thinkingBudget = optInt("thinkingBudget", d.thinkingBudget),
        temperature = optDouble("temperature", d.temperature),
        topK = optInt("topK", d.topK),
        topP = optDouble("topP", d.topP),
        maxOutputTokens = optInt("maxOutputTokens", d.maxOutputTokens),
        systemPrompt = optNonNullString("systemPrompt") ?: d.systemPrompt,
        themeMode = optEnum("themeMode", d.themeMode),
        accessibility = accObj?.toAccessibilityConfig() ?: d.accessibility,
    )
}

private fun AccessibilityConfig.toJson(): JSONObject = JSONObject().apply {
    put("highContrast", highContrast)
    put("fontScale", fontScale.toDouble())
    put("reduceMotion", reduceMotion)
    put("largeTouchTargets", largeTouchTargets)
}

private fun JSONObject.toAccessibilityConfig(): AccessibilityConfig {
    val d = AccessibilityConfig()
    return AccessibilityConfig(
        highContrast = optBoolean("highContrast", d.highContrast),
        fontScale = optDouble("fontScale", d.fontScale.toDouble()).toFloat().coerceIn(0.85f, 1.40f),
        reduceMotion = optBoolean("reduceMotion", d.reduceMotion),
        largeTouchTargets = optBoolean("largeTouchTargets", d.largeTouchTargets),
    )
}

private fun ChatSession.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("updatedAtMs", updatedAtMs)
    put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
}

private fun JSONObject.toChatSession(): ChatSession {
    val msgArray = optJSONArray("messages") ?: JSONArray()
    val messages = (0 until msgArray.length()).map { msgArray.getJSONObject(it).toChatMessage() }
    return ChatSession(
        id = getString("id"),
        title = optNonNullString("title") ?: "New Chat",
        messages = messages,
        updatedAtMs = optLong("updatedAtMs", 0L),
    )
}

private fun ChatMessage.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("role", role.name)
    put("text", text)
    put("thought", thought)
    if (imagePath != null) put("imagePath", imagePath)
    put("timestampMs", timestampMs)
}

private fun JSONObject.toChatMessage(): ChatMessage = ChatMessage(
    id = getString("id"),
    role = optEnum("role", Role.USER),
    text = optNonNullString("text") ?: "",
    thought = optNonNullString("thought") ?: "",
    imagePath = optNonNullString("imagePath"),
    timestampMs = optLong("timestampMs", 0L),
)
