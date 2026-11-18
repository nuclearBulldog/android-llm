package com.slopware.androidllm.data

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.slopware.androidllm.domain.HardwareController
import com.slopware.androidllm.domain.LlmConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * On-device LiteRT-LM [ToolSet] exposing device clock, ARM64 silicon telemetry, deterministic
 * scientific math evaluation, and persistent assistant memory (`assistant_notes.json`).
 */
class AgentToolSet(
    private val context: Context? = null,
    private val hardwareController: HardwareController? = null,
    private val configProvider: () -> LlmConfig = { LlmConfig() },
    private val notesFile: File = File(
        context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "androidllm"),
        "assistant_notes.json",
    ),
) : ToolSet {
    private val notesLock = Any()

    @Tool(description = "Get the current local date, time, timezone, and day of week on the device.")
    fun get_device_time(): Map<String, Any> {
        val now = ZonedDateTime.now()
        return mapOf(
            "date" to now.toLocalDate().toString(),
            "time" to now.toLocalTime().withNano(0).toString(),
            "dayOfWeek" to now.dayOfWeek.name,
            "timezone" to now.zone.id,
            "isoTimestamp" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    @Tool(description = "Read live on-device ARM64 silicon telemetry, RAM usage, thermal sensor status, CPU core affinity mask, FPCR flush-to-zero state, and core frequencies.")
    fun get_hardware_status(): String {
        val cfg = configProvider()
        val tel = hardwareController?.sampleTelemetry(cfg, runBench = false)
        return JSONObject().apply {
            put("arch", tel?.arch ?: (System.getProperty("os.arch") ?: "arm64-v8a"))
            put("availRamMb", tel?.availRamMb ?: 0L)
            put("totalRamMb", tel?.totalRamMb ?: 0L)
            put("thermalCelsius", tel?.thermalCelsius?.toDouble() ?: JSONObject.NULL)
            put("thermalThrottled", tel?.thermalThrottled ?: false)
            put("activeAffinityMaskHex", tel?.activeAffinityMaskHex ?: "0x0")
            put("flushToZeroActive", tel?.flushToZeroActive ?: cfg.enableFpcrFlushToZero)
            put("fpcrHex", tel?.fpcrHex ?: "0x0")
            put("backend", cfg.backend.name)
            put("coreAffinity", cfg.coreAffinity.name)
            put(
                "cores",
                JSONArray().apply {
                    tel?.cores.orEmpty().forEach { core ->
                        put(
                            JSONObject()
                                .put("core", core.coreIndex)
                                .put("tier", core.tier)
                                .put("curFreqMhz", core.curFreqKhz / 1000)
                                .put("maxFreqMhz", core.maxFreqKhz / 1000),
                        )
                    }
                },
            )
        }.toString()
    }

    @Tool(description = "Evaluate a mathematical expression (+, -, *, /, %, ^, parentheses, sqrt, sin, cos, log, abs) deterministically and return the numeric result.")
    fun evaluate_math(
        @ToolParam(description = "Mathematical expression to evaluate, e.g. '(18 * 4) + sqrt(144)' or '2 ^ 10'.") expression: String,
    ): Map<String, Any> = runCatching {
        val result = evaluateExpression(expression)
        val formatted = if (result.isFinite() && result == result.toLong().toDouble()) {
            result.toLong().toString()
        } else {
            result.toString()
        }
        mapOf("expression" to expression, "result" to result, "formatted" to formatted)
    }.getOrElse { e ->
        mapOf("expression" to expression, "error" to (e.message ?: "Invalid math expression"))
    }

    @Tool(description = "Save or update a persistent user note or memory in on-device storage so the assistant remembers it across sessions.")
    fun save_assistant_note(
        @ToolParam(description = "Short topic key or label for the note, e.g. 'todo' or 'user_preference'.") key: String,
        @ToolParam(description = "The note content to remember.") content: String,
    ): Map<String, Any> {
        val cleanKey = key.trim().ifEmpty { "note" }
        val cleanContent = content.trim()
        synchronized(notesLock) {
            val existing = loadNotesLocked().filterNot { it.optString("key").equals(cleanKey, ignoreCase = true) }
            val entry = JSONObject()
                .put("key", cleanKey)
                .put("content", cleanContent)
                .put("updatedAtMs", System.currentTimeMillis())
            val updated = JSONArray().apply {
                put(entry)
                existing.forEach { put(it) }
            }
            notesFile.parentFile?.mkdirs()
            notesFile.writeText(updated.toString())
            return mapOf("status" to "saved", "key" to cleanKey, "totalNotes" to updated.length())
        }
    }

    @Tool(description = "Read saved assistant notes and memories from on-device storage. Optionally filter by keyword.")
    fun read_assistant_notes(
        @ToolParam(description = "Optional keyword or key filter. Leave empty to list all saved notes. Default: empty") query: String = "",
    ): Map<String, Any> {
        val q = query.trim()
        val notes = synchronized(notesLock) { loadNotesLocked() }
            .filter { obj ->
                q.isEmpty() ||
                    obj.optString("key").contains(q, ignoreCase = true) ||
                    obj.optString("content").contains(q, ignoreCase = true)
            }
            .map { obj ->
                mapOf(
                    "key" to obj.optString("key"),
                    "content" to obj.optString("content"),
                )
            }
        return mapOf("count" to notes.size, "notes" to notes)
    }

    private fun loadNotesLocked(): List<JSONObject> = runCatching {
        if (!notesFile.isFile) return emptyList()
        val arr = JSONArray(notesFile.readText())
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }.getOrDefault(emptyList())

    companion object {
        /**
         * Recursive-descent evaluator for arithmetic (`+`, `-`, `*`, `/`, `%`, `^`, parentheses)
         * and elementary scientific functions (`sqrt`, `sin`, `cos`, `tan`, `log`, `ln`, `log10`, `abs`).
         */
        internal fun evaluateExpression(raw: String): Double {
            val s = raw.trim()
            require(s.isNotEmpty()) { "Empty expression" }
            return MathParser(s).parse()
        }

        private class MathParser(private val s: String) {
            private var pos = 0

            fun parse(): Double {
                val result = parseExpr()
                skipWs()
                require(pos == s.length) { "Unexpected trailing characters at index $pos" }
                return result
            }

            private fun skipWs() {
                while (pos < s.length && s[pos].isWhitespace()) pos++
            }

            private fun match(ch: Char): Boolean {
                skipWs()
                if (pos < s.length && s[pos] == ch) {
                    pos++
                    return true
                }
                return false
            }

            private fun parseExpr(): Double {
                var v = parseTerm()
                while (true) {
                    when {
                        match('+') -> v += parseTerm()
                        match('-') -> v -= parseTerm()
                        else -> return v
                    }
                }
            }

            private fun parseTerm(): Double {
                var v = parsePower()
                while (true) {
                    when {
                        match('*') -> v *= parsePower()
                        match('/') -> {
                            val d = parsePower()
                            require(d != 0.0) { "Division by zero" }
                            v /= d
                        }
                        match('%') -> {
                            val d = parsePower()
                            require(d != 0.0) { "Modulo by zero" }
                            v %= d
                        }
                        else -> return v
                    }
                }
            }

            private fun parsePower(): Double {
                val base = parseUnary()
                return if (match('^')) base.pow(parsePower()) else base
            }

            private fun parseUnary(): Double = when {
                match('+') -> parseUnary()
                match('-') -> -parseUnary()
                else -> parsePrimary()
            }

            private fun parsePrimary(): Double {
                skipWs()
                require(pos < s.length) { "Unexpected end of expression" }
                if (match('(')) {
                    val v = parseExpr()
                    require(match(')')) { "Missing closing parenthesis" }
                    return v
                }
                val start = pos
                if (s[pos].isLetter()) {
                    while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
                    val name = s.substring(start, pos).lowercase()
                    if (match('(')) {
                        val arg = parseExpr()
                        require(match(')')) { "Missing closing parenthesis after $name" }
                        return when (name) {
                            "sqrt" -> sqrt(arg)
                            "sin" -> sin(arg)
                            "cos" -> cos(arg)
                            "tan" -> tan(arg)
                            "log", "ln" -> ln(arg)
                            "log10" -> log10(arg)
                            "abs" -> abs(arg)
                            "round" -> round(arg)
                            "floor" -> floor(arg)
                            "ceil" -> ceil(arg)
                            else -> throw IllegalArgumentException("Unknown function: $name")
                        }
                    }
                    return when (name) {
                        "pi" -> Math.PI
                        "e" -> Math.E
                        else -> throw IllegalArgumentException("Unknown identifier: $name")
                    }
                }
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                    pos++
                    if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                val token = s.substring(start, pos)
                return token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Unexpected token at index $start: '$token'")
            }
        }
    }
}

/**
 * [OpenApiTool] executing structured JSON device actions (`copy_clipboard`, `open_url`, `web_search`)
 * with safe Android `Intent` flags when [context] is available.
 */
class DeviceActionOpenApiTool(
    private val context: Context? = null,
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "launch_device_action",
          "description": "Execute a safe Android device action: copy text to the system clipboard, open an http/https URL, or launch a web search.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "description": "Action type: 'copy_clipboard', 'open_url', or 'web_search'."
              },
              "value": {
                "type": "string",
                "description": "Text to copy to clipboard, URL to open, or search query string."
              }
            },
            "required": [
              "action",
              "value"
            ]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String = runCatching {
        val params = JSONObject(paramsJsonString)
        val action = params.optString("action").trim().lowercase()
        val value = params.optString("value").trim()
        require(value.isNotEmpty()) { "Missing 'value' parameter for $action" }
        val ctx = context

        when (action) {
            "copy_clipboard", "clipboard", "copy" -> {
                if (ctx != null) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Assistant", value))
                }
                JSONObject()
                    .put("status", if (ctx != null) "ok" else "simulated")
                    .put("action", "copy_clipboard")
                    .put("copiedLength", value.length)
                    .toString()
            }
            "open_url", "url", "view" -> {
                val normalized = if (value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true)
                ) {
                    value
                } else {
                    "https://$value"
                }
                if (ctx != null) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                JSONObject()
                    .put("status", if (ctx != null) "ok" else "simulated")
                    .put("action", "open_url")
                    .put("url", normalized)
                    .toString()
            }
            "web_search", "search" -> {
                if (ctx != null) {
                    val searchIntent = Intent(Intent.ACTION_WEB_SEARCH)
                        .putExtra(SearchManager.QUERY, value)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(searchIntent) }.onFailure {
                        val fallbackUrl = "https://www.google.com/search?q=${Uri.encode(value)}"
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                JSONObject()
                    .put("status", if (ctx != null) "ok" else "simulated")
                    .put("action", "web_search")
                    .put("query", value)
                    .toString()
            }
            else -> JSONObject()
                .put("status", "error")
                .put("error", "Unsupported action: $action")
                .toString()
        }
    }.getOrElse { e ->
        JSONObject()
            .put("status", "error")
            .put("error", e.message ?: "Device action failed")
            .toString()
    }
}
