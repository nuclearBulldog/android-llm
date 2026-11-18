package com.slopware.androidllm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slopware.androidllm.domain.BackendType
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.SiliconTelemetry
import com.slopware.androidllm.domain.ThemeMode
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    config: LlmConfig,
    telemetry: SiliconTelemetry?,
    isBusy: Boolean,
    isBenchmarking: Boolean,
    onUpdateConfig: (LlmConfig) -> Unit,
    onRunBenchmark: () -> Unit,
    onEvictPageCache: () -> Unit = {},
    onOpenModelHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SettingsSheetBody(
            config = config,
            telemetry = telemetry,
            isBusy = isBusy,
            isBenchmarking = isBenchmarking,
            onUpdateConfig = onUpdateConfig,
            onRunBenchmark = onRunBenchmark,
            onEvictPageCache = onEvictPageCache,
            onOpenModelHub = onOpenModelHub,
        )
    }
}

@Composable
fun SettingsSheetBody(
    config: LlmConfig,
    telemetry: SiliconTelemetry?,
    isBusy: Boolean,
    isBenchmarking: Boolean,
    onUpdateConfig: (LlmConfig) -> Unit,
    onRunBenchmark: () -> Unit,
    onEvictPageCache: () -> Unit = {},
    onOpenModelHub: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val a11y = config.accessibility

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Settings & Performance Tuning",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            OutlinedButton(
                onClick = onOpenModelHub,
                modifier = Modifier.heightIn(min = minTouchSize()),
            ) {
                Text("Models", maxLines = 1, softWrap = false)
            }
        }

        if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        // Appearance & Accessibility
        SectionTitle("Appearance & Accessibility")
        val themeOptions = listOf(
            ThemeMode.SYSTEM to "System (${if (systemDark) "Dark" else "Light"})",
            ThemeMode.LIGHT to "Light",
            ThemeMode.DARK to "Dark",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            themeOptions.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = config.themeMode == mode,
                    onClick = { onUpdateConfig(config.copy(themeMode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        ToggleSettingRow(
            title = "High Contrast Mode",
            subtitle = "Use maximum-contrast monochrome surfaces and borders (WCAG AAA)",
            checked = a11y.highContrast,
            onCheckedChange = { onUpdateConfig(config.copy(accessibility = a11y.copy(highContrast = it))) },
        )
        ToggleSettingRow(
            title = "Reduce Motion",
            subtitle = "Minimize animations and use instant list scrolling",
            checked = a11y.reduceMotion,
            onCheckedChange = { onUpdateConfig(config.copy(accessibility = a11y.copy(reduceMotion = it))) },
        )
        ToggleSettingRow(
            title = "Large Touch Targets",
            subtitle = "Expand interactive buttons and controls to at least 52dp",
            checked = a11y.largeTouchTargets,
            onCheckedChange = { onUpdateConfig(config.copy(accessibility = a11y.copy(largeTouchTargets = it))) },
        )
        SliderSettingRow(
            label = "Text Size Scale",
            subtitle = "Adjusts in-app chat and control typography size",
            value = a11y.fontScale,
            range = 0.85f..1.40f,
            formatValue = { String.format(Locale.US, "%.2fx", it) },
            onCommit = {
                val rounded = (it * 20f).roundToInt() / 20f
                onUpdateConfig(config.copy(accessibility = a11y.copy(fontScale = rounded)))
            },
        )

        HorizontalDivider()

        // On-Device Hardware & Performance Tuning
        SectionTitle("On-Device Hardware & Performance Tuning")
        BackendSelectorRow(
            label = "Primary Inference Processor",
            subtitle = "Selects where language model weights run: GPU (OpenCL acceleration, fastest for most models), CPU (multi-core ARM64 NEON), or NPU (dedicated neural accelerator)",
            selected = config.backend,
        ) {
            onUpdateConfig(config.copy(backend = it))
        }
        BackendSelectorRow(
            label = "Image & Vision Processor",
            subtitle = "Hardware processor used to encode attached photos and images during multimodal chat",
            selected = config.visionBackend,
        ) {
            onUpdateConfig(config.copy(visionBackend = it))
        }
        BackendSelectorRow(
            label = "Audio & Speech Processor",
            subtitle = "Hardware processor used for audio feature extraction and speech encoding",
            selected = config.audioBackend,
        ) {
            onUpdateConfig(config.copy(audioBackend = it))
        }
        ToggleSettingRow(
            title = "Speculative Decoding (Multi-Token Prediction)",
            subtitle = "Drafts multiple tokens ahead per step for up to 2.2x faster GPU responses; best for coding and structured tasks (disable on CPU if freeform chat feels slower)",
            checked = config.enableMtp,
            onCheckedChange = { onUpdateConfig(config.copy(enableMtp = it)) },
        )
        ToggleSettingRow(
            title = "Agentic Tool Calling & Assistant Actions",
            subtitle = "Allow the model to invoke on-device assistant tools (clock, calculator, hardware status, persistent notes, and system actions)",
            checked = config.enableAgentTools,
            onCheckedChange = { onUpdateConfig(config.copy(enableAgentTools = it)) },
        )
        ToggleSettingRow(
            title = "Subnormal Float Flush-to-Zero (ARM64 FPCR.FZ)",
            subtitle = "Flushes tiny denormalized floating-point numbers directly to zero in hardware to prevent microcode stalls during neural matrix math",
            checked = config.enableFpcrFlushToZero,
            onCheckedChange = { onUpdateConfig(config.copy(enableFpcrFlushToZero = it)) },
        )
        ToggleSettingRow(
            title = "Sequential Model Weight Prefetch (VM Read-Ahead)",
            subtitle = "Pre-warms the Linux page cache with model weights via fadvise64 before initialization to reduce first-token latency",
            checked = config.enableVmPageWarmup,
            onCheckedChange = { onUpdateConfig(config.copy(enableVmPageWarmup = it)) },
        )
        ToggleSettingRow(
            title = "Low-Latency Thread Priority & Timer Slack",
            subtitle = "Boosts inference thread scheduling priority (nice -4) and tightens kernel timer slack for steadier token streaming",
            checked = config.enableLowLatencyThread,
            onCheckedChange = { onUpdateConfig(config.copy(enableLowLatencyThread = it)) },
        )
        ToggleSettingRow(
            title = "Automatic Overheat Protection (Thermal Guard ≥ 78°C)",
            subtitle = "Automatically shifts Prime-Core-Only workloads onto Big + Prime cores when device temperature reaches 78°C to prevent OS thermal throttling",
            checked = config.enableThermalGuard,
            onCheckedChange = { onUpdateConfig(config.copy(enableThermalGuard = it)) },
        )
        AffinitySelector(
            selected = config.coreAffinity,
            onSelect = { onUpdateConfig(config.copy(coreAffinity = it)) },
        )
        SliderSettingRow(
            label = "CPU Inference Thread Count",
            subtitle = "Worker threads used for CPU inference and tokenization (4 threads is optimal for most 8-core phones)",
            value = config.cpuThreadCount.toFloat(),
            range = 1f..8f,
            formatValue = { "${it.roundToInt()}" },
            onCommit = { onUpdateConfig(config.copy(cpuThreadCount = it.roundToInt())) },
        )
        SiliconTelemetryCard(
            telemetry = telemetry,
            isBusy = isBusy,
            isBenchmarking = isBenchmarking,
            onRunBenchmark = onRunBenchmark,
            onEvictPageCache = onEvictPageCache,
        )

        HorizontalDivider()

        // Reasoning, Sampling & Assistant Persona
        SectionTitle("Reasoning, Sampling & Assistant Persona")
        ToggleSettingRow(
            title = "Extended Reasoning (Thinking Mode)",
            subtitle = "Allow the model to work through complex problems step-by-step in a collapsible thought card before answering",
            checked = config.enableThinking,
            onCheckedChange = { onUpdateConfig(config.copy(enableThinking = it)) },
        )
        if (config.enableThinking) {
            ThinkingBudgetControl(
                budget = config.thinkingBudget,
                onBudgetChange = { onUpdateConfig(config.copy(thinkingBudget = it)) },
            )
        }
        SliderSettingRow(
            label = "Response Creativity (Temperature)",
            subtitle = "Lower values produce focused, deterministic answers; higher values increase creativity and variety",
            value = config.temperature.toFloat(),
            range = 0f..1.5f,
            formatValue = { String.format(Locale.US, "%.2f", it) },
            onCommit = { onUpdateConfig(config.copy(temperature = (it * 100f).roundToInt() / 100.0)) },
        )
        SliderSettingRow(
            label = "Vocabulary Cutoff (Top-K)",
            subtitle = "Limits each token step to the K most likely candidate words",
            value = config.topK.toFloat(),
            range = 1f..100f,
            formatValue = { "${it.roundToInt()}" },
            onCommit = { onUpdateConfig(config.copy(topK = it.roundToInt())) },
        )
        SliderSettingRow(
            label = "Nucleus Probability (Top-P)",
            subtitle = "Samples only from the smallest set of words whose cumulative probability reaches P",
            value = config.topP.toFloat(),
            range = 0.1f..1.0f,
            formatValue = { String.format(Locale.US, "%.2f", it) },
            onCommit = { onUpdateConfig(config.copy(topP = (it * 100f).roundToInt() / 100.0)) },
        )
        SliderSettingRow(
            label = "Maximum Response Length (Tokens)",
            subtitle = "Upper limit on generated tokens per reply (including reasoning tokens)",
            value = config.maxOutputTokens.toFloat(),
            range = 256f..4096f,
            formatValue = { "${it.roundToInt()}" },
            onCommit = { onUpdateConfig(config.copy(maxOutputTokens = it.roundToInt())) },
        )

        SystemPromptEditor(
            currentPrompt = config.systemPrompt,
            onApply = { onUpdateConfig(config.copy(systemPrompt = it)) },
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun BackendSelectorRow(
    label: String,
    subtitle: String? = null,
    selected: BackendType,
    onSelect: (BackendType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            BackendType.entries.forEachIndexed { index, backend ->
                SegmentedButton(
                    selected = selected == backend,
                    onClick = { onSelect(backend) },
                    shape = SegmentedButtonDefaults.itemShape(index, BackendType.entries.size),
                ) { Text(backend.name) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AffinitySelector(
    selected: CoreAffinityMode,
    onSelect: (CoreAffinityMode) -> Unit,
) {
    val labels = mapOf(
        CoreAffinityMode.ALL_CORES to "All CPU Cores",
        CoreAffinityMode.BIG_AND_PRIME to "Performance Cores (Big + Prime)",
        CoreAffinityMode.PRIME_ONLY to "Peak Single-Core (Prime Only)",
        CoreAffinityMode.EFFICIENCY_ONLY to "Battery Saver (Efficiency Cores)",
    )
    val descriptions = mapOf(
        CoreAffinityMode.ALL_CORES to "Schedules threads across every available CPU core (Little, Big, and Prime).",
        CoreAffinityMode.BIG_AND_PRIME to "Pins inference threads to high-performance Big and Prime cores for the best sustained speed.",
        CoreAffinityMode.PRIME_ONLY to "Pins threads exclusively to the highest-clocked Prime core(s) for minimum single-thread latency.",
        CoreAffinityMode.EFFICIENCY_ONLY to "Restricts threads to low-power Efficiency (Little) cores to minimize heat and battery drain.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Processor Core Scheduling Policy", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = descriptions[selected].orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoreAffinityMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(labels[mode] ?: mode.name) },
                )
            }
        }
    }
}

@Composable
private fun SiliconTelemetryCard(
    telemetry: SiliconTelemetry?,
    isBusy: Boolean,
    isBenchmarking: Boolean,
    onRunBenchmark: () -> Unit,
    onEvictPageCache: () -> Unit,
) {
    var showDisassembly by rememberSaveable { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Live Processor & Memory Telemetry",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (telemetry?.nativeLoaded == true) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    Text(
                        text = if (telemetry?.nativeLoaded == true) "ARM64 Active" else "JVM Fallback",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (telemetry != null) {
                if (telemetry.thermalThrottled) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Overheat Protection Active: Shifted from Prime-Only to Performance Cores (Big + Prime)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                val thermal = telemetry.thermalCelsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "N/A"
                val slackLabel = when {
                    telemetry.timerSlackNs > 0L -> "${telemetry.timerSlackNs} ns"
                    else -> "Default"
                }
                val fzStatus = if (telemetry.flushToZeroActive) "Enabled" else "Disabled"
                Text(
                    text = buildString {
                        appendLine("Architecture: ${telemetry.arch} (Mask ${telemetry.activeAffinityMaskHex})")
                        appendLine("Flush-to-Zero: $fzStatus (FPCR ${telemetry.fpcrHex})")
                        appendLine("Timer Slack: $slackLabel · Counter: ${telemetry.timerFreqMhz} MHz")
                        appendLine("L1 Cache Line: ${telemetry.l1ICacheLineBytes}B Inst / ${telemetry.l1DCacheLineBytes}B Data")
                        appendLine("Memory: ${telemetry.availRamMb} / ${telemetry.totalRamMb} MB free")
                        appendLine("Device Temp: $thermal")
                        append("Page Cache: ${telemetry.lastVmAction}")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )

                if (telemetry.cores.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        telemetry.cores.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                pair.forEach { core ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = "C${core.coreIndex} ${core.tier} · ${core.curFreqKhz / 1000}/${core.maxFreqKhz / 1000} MHz",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Button(
                    onClick = onRunBenchmark,
                    enabled = !isBenchmarking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = minTouchSize()),
                ) {
                    if (isBenchmarking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        val benchLabel = if (telemetry.neonGflops > 0.0) {
                            "Run SIMD Benchmark (${String.format(Locale.US, "%.2f GFLOPS", telemetry.neonGflops)})"
                        } else {
                            "Run SIMD Vector Benchmark"
                        }
                        Text(benchLabel, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }

                OutlinedButton(
                    onClick = onEvictPageCache,
                    enabled = !isBusy && !isBenchmarking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = minTouchSize()),
                ) {
                    Text("Release Cached Model Weights from RAM", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (telemetry.machineCodeDisassembly.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDisassembly = !showDisassembly }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "View Native ARM64 Assembly Instructions",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Icon(
                            if (showDisassembly) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle native ARM64 assembly instructions",
                        )
                    }
                    AnimatedVisibility(visible = showDisassembly) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            telemetry.machineCodeDisassembly.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBudgetControl(
    budget: Int,
    onBudgetChange: (Int) -> Unit,
) {
    val isUnlimited = budget < 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Reasoning Token Budget",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            FilterChip(
                selected = isUnlimited,
                onClick = { onBudgetChange(if (isUnlimited) 1024 else -1) },
                label = { Text("Unlimited (-1)", maxLines = 1, softWrap = false) },
            )
        }
        if (!isUnlimited) {
            SliderSettingRow(
                label = "Maximum Reasoning Tokens",
                value = budget.coerceIn(128, 4096).toFloat(),
                range = 128f..4096f,
                formatValue = { "${it.roundToInt()}" },
                onCommit = { onBudgetChange(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minTouchSize())
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SliderSettingRow(
    label: String,
    subtitle: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    formatValue: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    // Track drag locally so disk-backed LlmConfig persistence only runs when the slider settles.
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
                text = formatValue(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = draft.coerceIn(range.start, range.endInclusive),
            onValueChange = { draft = it },
            valueRange = range,
            onValueChangeFinished = { onCommit(draft) },
        )
    }
}

@Composable
private fun SystemPromptEditor(
    currentPrompt: String,
    onApply: (String) -> Unit,
) {
    var draft by remember(currentPrompt) { mutableStateOf(currentPrompt) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Assistant Persona & System Instructions", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Defines the assistant's tone, role, and custom instructions across conversations",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        if (draft != currentPrompt) {
            Button(
                onClick = { onApply(draft) },
                modifier = Modifier.align(Alignment.End).heightIn(min = minTouchSize()),
            ) {
                Text("Apply Instructions")
            }
        }
    }
}
