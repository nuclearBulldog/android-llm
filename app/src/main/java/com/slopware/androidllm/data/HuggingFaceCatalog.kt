package com.slopware.androidllm.data

import com.slopware.androidllm.domain.BackendType
import com.slopware.androidllm.domain.HfModelEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Curated LiteRT-LM models from `api.md` plus HuggingFace Hub repository file discovery.
 */
object HuggingFaceCatalog {
    private val supportedExtensions = setOf(".litertlm", ".tflite", ".task")

    val curatedModels: List<HfModelEntry> = listOf(
        HfModelEntry(
            id = "gemma-4-e2b-it",
            title = "Gemma 4 E2B IT",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeLabel = "2.58 GB",
            sizeBytes = 2_580_000_000L,
            tags = listOf("Gemma 4", "Thinking", "Vision", "MTP"),
            description = "Frontier 2B-effective reasoning & multimodal model tuned for mobile GPU + MTP.",
            recommendedBackend = BackendType.GPU,
        ),
        HfModelEntry(
            id = "gemma-4-e4b-it",
            title = "Gemma 4 E4B IT",
            repoId = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeLabel = "3.65 GB",
            sizeBytes = 3_650_000_000L,
            tags = listOf("Gemma 4", "Thinking", "Vision", "MTP"),
            description = "Higher-capacity 4B-effective reasoning & coding model for flagship 8GB+ devices.",
            recommendedBackend = BackendType.GPU,
        ),
        HfModelEntry(
            id = "gemma-3n-e2b-it",
            title = "Gemma 3n E2B IT",
            repoId = "google/gemma-3n-E2B-it-litert-lm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            sizeLabel = "2.97 GB",
            sizeBytes = 2_970_000_000L,
            tags = listOf("Multimodal", "Vision", "Audio"),
            description = "On-device multimodal Gemma 3n supporting image + text prompts.",
            recommendedBackend = BackendType.GPU,
        ),
        HfModelEntry(
            id = "gemma-3-1b-it",
            title = "Gemma 3 1B IT",
            repoId = "litert-community/Gemma3-1B-IT",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeLabel = "554 MB",
            sizeBytes = 554_000_000L,
            tags = listOf("Fast", "Lightweight", "Text"),
            description = "Compact 1B instruction-tuned model for quick responses and tight RAM budgets.",
            recommendedBackend = BackendType.GPU,
        ),
        HfModelEntry(
            id = "functiongemma-270m-it",
            title = "FunctionGemma 270M IT",
            repoId = "google/functiongemma-270m-it",
            fileName = "functiongemma-270m-it.litertlm",
            sizeLabel = "280 MB",
            sizeBytes = 280_000_000L,
            tags = listOf("270M", "Tools", "CPU/GPU"),
            description = "Ultra-lightweight model ideal for CPU smoke tests and tool-calling workflows.",
            recommendedBackend = BackendType.CPU,
        ),
    )

    suspend fun fetchRepoLitertFiles(
        repoId: String,
        hfToken: String = "",
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<HfModelEntry> = withContext(ioDispatcher) {
        val cleanRepo = normalizeRepoId(repoId)
        require(cleanRepo.contains('/')) { "Enter a HuggingFace repository as owner/model-name." }

        val url = URL("https://huggingface.co/api/models/$cleanRepo?blobs=true")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (hfToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val hint = if (code == 401 || code == 403) " (gated/private repo — add an HF token)" else ""
                throw IllegalStateException("HuggingFace API HTTP $code for $cleanRepo$hint")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseRepoSiblingsJson(cleanRepo, body)
        } finally {
            conn.disconnect()
        }
    }

    fun parseRepoSiblingsJson(repoId: String, jsonText: String): List<HfModelEntry> {
        val root = JSONObject(jsonText)
        val siblings = root.optJSONArray("siblings") ?: JSONArray()
        return (0 until siblings.length()).mapNotNull { idx ->
            val item = siblings.optJSONObject(idx) ?: return@mapNotNull null
            val fileName = item.optString("rfilename")
            val lower = fileName.lowercase(Locale.US)
            if (supportedExtensions.none { lower.endsWith(it) }) return@mapNotNull null

            val sizeBytes = item.optLong("size", 0L).takeIf { it > 0L }
                ?: item.optJSONObject("lfs")?.optLong("size", 0L)
                ?: 0L
            val backend = if (lower.contains("cpu") || lower.contains("270m")) BackendType.CPU else BackendType.GPU
            val extTag = lower.substringAfterLast('.', "litertlm").uppercase(Locale.US)
            HfModelEntry(
                id = "$repoId/$fileName",
                title = fileName.substringAfterLast('/'),
                repoId = repoId,
                fileName = fileName,
                sizeLabel = formatBytes(sizeBytes),
                sizeBytes = sizeBytes,
                tags = listOf(extTag, repoId.substringBefore('/')),
                description = "From HuggingFace repository $repoId",
                recommendedBackend = backend,
            )
        }
    }

    fun normalizeRepoId(raw: String): String =
        raw.trim()
            .removePrefix("https://huggingface.co/")
            .removePrefix("http://huggingface.co/")
            .substringBefore('?')
            .trim('/')

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "Size unknown"
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> "${bytes / 1024L} KB"
    }
}
