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
                text = "Settings & Hardware Tuner",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = onOpenModelHub,
                modifier = Modifier.heightIn(min = minTouchSize()),
            ) {
                Text("Models")
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
            title = "High Contrast",
            subtitle = "WCAG AAA monochrome contrast surfaces",
            checked = a11y.highContrast,
            onCheckedChange = { onUpdateConfig(config.copy(accessibility = a11y.copy(highContrast = it))) },
        )
        ToggleSettingRow(
            title = "Reduce Motion",
            subtitle = "Minimize animations and instant scroll",
            checked = a11y.reduceMotion,
            onCheckedChange = { onUpdateConfig(config.copy(accessibility = a11y.copy(reduceMotion = it))) },
        )
        ToggleSettingRow(
            title = "Large Touch Targets",
            subtitle = "Expand interactive controls to 52dp+",
            checked = a11y.largeTouchTargets,
            onCheckedChange = { onUpdateConfig(config.copy(accessibility = a11y.copy(largeTouchTargets = it))) },
        )
        SliderSettingRow(
            label = "Font Scale",
            value = a11y.fontScale,
            range = 0.85f..1.40f,
            formatValue = { String.format(Locale.US, "%.2fx", it) },
            onCommit = {
                val rounded = (it * 20f).roundToInt() / 20f
                onUpdateConfig(config.copy(accessibility = a11y.copy(fontScale = rounded)))
            },
        )

        HorizontalDivider()

        // Low-Level ARM64 Silicon & Backend Tuner
        SectionTitle("ARM64 Silicon & Backend Tuner")
        BackendSelectorRow("Primary Backend", config.backend) {
            onUpdateConfig(config.copy(backend = it))
        }
        BackendSelectorRow("Vision Backend", config.visionBackend) {
            onUpdateConfig(config.copy(visionBackend = it))
        }
        BackendSelectorRow("Audio Backend", config.audioBackend) {
            onUpdateConfig(config.copy(audioBackend = it))
        }
        ToggleSettingRow(
            title = "Multi-Token Prediction (MTP)",
            subtitle = "Speculative decoding speedup on GPU/CPU",
            checked = config.enableMtp,
            onCheckedChange = { onUpdateConfig(config.copy(enableMtp = it)) },
        )
        ToggleSettingRow(
            title = "ARM64 FPCR Flush-to-Zero (FZ bit 24)",
            subtitle = "Eliminate subnormal FP32 penalties in NEON/SIMD",
            checked = config.enableFpcrFlushToZero,
            onCheckedChange = { onUpdateConfig(config.copy(enableFpcrFlushToZero = it)) },
        )
        ToggleSettingRow(
            title = "VM Page Cache Warmup (fadvise64)",
            subtitle = "Prefetch model weights sequentially before engine init",
            checked = config.enableVmPageWarmup,
            onCheckedChange = { onUpdateConfig(config.copy(enableVmPageWarmup = it)) },
        )
        ToggleSettingRow(
            title = "Low-Latency Timer Slack & Barriers",
            subtitle = "Tighten prctl timer slack, nice -4, and DSB/ISB barriers",
            checked = config.enableLowLatencyThread,
            onCheckedChange = { onUpdateConfig(config.copy(enableLowLatencyThread = it)) },
        )
        ToggleSettingRow(
            title = "SoC Thermal Guard (>= 78°C Downshift)",
            subtitle = "Auto-downshift Prime-Only to Big+Prime under thermal load",
            checked = config.enableThermalGuard,
            onCheckedChange = { onUpdateConfig(config.copy(enableThermalGuard = it)) },
        )
        AffinitySelector(
            selected = config.coreAffinity,
            onSelect = { onUpdateConfig(config.copy(coreAffinity = it)) },
        )
        SliderSettingRow(
            label = "CPU Threads",
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

        // Reasoning & Sampler
        SectionTitle("Reasoning & Sampler")
        ToggleSettingRow(
            title = "Enable Thinking",
            subtitle = "Stream model reasoning tokens in thought channel",
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
            label = "Temperature",
            value = config.temperature.toFloat(),
            range = 0f..1.5f,
            formatValue = { String.format(Locale.US, "%.2f", it) },
            onCommit = { onUpdateConfig(config.copy(temperature = (it * 100f).roundToInt() / 100.0)) },
        )
        SliderSettingRow(
            label = "Top-K",
            value = config.topK.toFloat(),
            range = 1f..100f,
            formatValue = { "${it.roundToInt()}" },
            onCommit = { onUpdateConfig(config.copy(topK = it.roundToInt())) },
        )
        SliderSettingRow(
            label = "Top-P",
            value = config.topP.toFloat(),
            range = 0.1f..1.0f,
            formatValue = { String.format(Locale.US, "%.2f", it) },
            onCommit = { onUpdateConfig(config.copy(topP = (it * 100f).roundToInt() / 100.0)) },
        )
        SliderSettingRow(
            label = "Max Output Tokens",
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
    selected: BackendType,
    onSelect: (BackendType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
        CoreAffinityMode.ALL_CORES to "All Cores",
        CoreAffinityMode.BIG_AND_PRIME to "Big + Prime",
        CoreAffinityMode.PRIME_ONLY to "Prime Only",
        CoreAffinityMode.EFFICIENCY_ONLY to "Efficiency",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ARM big.LITTLE Core Affinity", style = MaterialTheme.typography.bodyMedium)
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

@OptIn(ExperimentalLayoutApi::class)
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
                Text("Live Silicon Telemetry", style = MaterialTheme.typography.titleSmall)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (telemetry?.nativeLoaded == true) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    Text(
                        text = if (telemetry?.nativeLoaded == true) "libslop_hw.so active" else "JVM / Fallback",
                        style = MaterialTheme.typography.labelSmall,
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
                            text = "Thermal Guard active: Prime-Only downshifted to Big+Prime",
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
                Text(
                    text = buildString {
                        appendLine("Arch: ${telemetry.arch} · Mask: ${telemetry.activeAffinityMaskHex}")
                        appendLine("FPCR: ${telemetry.fpcrHex} (FZ=${telemetry.flushToZeroActive}) · Slack: $slackLabel")
                        appendLine("CTR_EL0 L1 I/D: ${telemetry.l1ICacheLineBytes}B / ${telemetry.l1DCacheLineBytes}B · Timer: ${telemetry.timerFreqMhz} MHz")
                        appendLine("RAM: ${telemetry.availRamMb}/${telemetry.totalRamMb} MB · Temp: $thermal")
                        append("VM: ${telemetry.lastVmAction}")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )

                if (telemetry.cores.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        telemetry.cores.forEach { core ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    text = "C${core.coreIndex} ${core.tier} ${core.curFreqKhz / 1000}/${core.maxFreqKhz / 1000}MHz",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Button(
                        onClick = onRunBenchmark,
                        enabled = !isBenchmarking,
                        modifier = Modifier.heightIn(min = minTouchSize()),
                    ) {
                        if (isBenchmarking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Run NEON SIMD Benchmark")
                        }
                    }
                    if (telemetry.neonGflops > 0.0) {
                        Text(
                            text = String.format(Locale.US, "%.2f GFLOPS", telemetry.neonGflops),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onEvictPageCache,
                    enabled = !isBusy && !isBenchmarking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = minTouchSize()),
                ) {
                    Text("Purge Cold Model Pages")
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
                        Text("Raw AArch64 Machine Code", style = MaterialTheme.typography.labelLarge)
                        Icon(
                            if (showDisassembly) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle machine code disassembly",
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
            Text("Thinking Budget", style = MaterialTheme.typography.bodyMedium)
            FilterChip(
                selected = isUnlimited,
                onClick = { onBudgetChange(if (isUnlimited) 1024 else -1) },
                label = { Text("Unlimited (-1)") },
            )
        }
        if (!isUnlimited) {
            SliderSettingRow(
                label = "Max Reasoning Tokens",
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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatValue(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
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
        Text("System Prompt", style = MaterialTheme.typography.titleSmall)
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
                Text("Apply Prompt")
            }
        }
    }
}
