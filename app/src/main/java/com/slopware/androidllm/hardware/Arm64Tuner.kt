package com.slopware.androidllm.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.slopware.androidllm.domain.ArmCoreInfo
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.HardwareController
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.SiliconTelemetry
import java.io.File

/**
 * Low-level ARM64 hardware controller backed by `libslop_hw.so` (`FPCR.FZ`, `CTR_EL0`,
 * `CNTFRQ_EL0`, `__NR_sched_setaffinity`, NEON `fmla`, `fadvise64`, and `prctl`/`setpriority`),
 * with safe fallbacks for JVM unit tests and non-ARM64 emulators.
 */
class Arm64Tuner(
    private val context: Context? = null,
) : HardwareController {
    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("slop_hw")
        true
    }.getOrDefault(false)

    @Volatile
    private var lastNeonGflops: Double = 0.0

    @Volatile
    private var lastFpcrValue: Long = 0L

    @Volatile
    private var lastVmAction: String = "Idle"

    @Volatile
    private var activeTimerSlackNs: Long = -1L

    private data class CoreDescriptor(
        val index: Int,
        val maxFreqKhz: Int,
        val curFreqFile: File,
    )

    // Cache static CPU topology paths once to avoid per-sample sysfs directory scans.
    private val cachedCoreDescriptors: List<CoreDescriptor> by lazy { discoverSysfsCores() }

    override fun applyTuning(config: LlmConfig): SiliconTelemetry =
        sampleInternal(config, applyNative = true, runBench = false)

    override fun sampleTelemetry(config: LlmConfig, runBench: Boolean): SiliconTelemetry =
        sampleInternal(config, applyNative = false, runBench = runBench)

    override fun warmupModelPages(modelPath: String, prefetchBytes: Long): String =
        withModelFd(modelPath, "Warmup") { fd, file ->
            val bytes = prefetchBytes.coerceAtMost(file.length()).coerceAtLeast(0L)
            nativeFadviseModelFd(fd, 0L, 0L, POSIX_FADV_SEQUENTIAL)
            val rc = nativeFadviseModelFd(fd, 0L, bytes, POSIX_FADV_WILLNEED)
            "Warmup ${bytes / (1024L * 1024L)} MB (rc=$rc)"
        }

    override fun evictColdModelPages(modelPath: String): String =
        withModelFd(modelPath, "Evict") { fd, _ ->
            val rc = nativeFadviseModelFd(fd, 0L, 0L, POSIX_FADV_DONTNEED)
            "Evicted cold pages (rc=$rc)"
        }

    override fun configureLowLatencyThread(enable: Boolean, nice: Int): Long {
        if (!nativeLoaded) {
            activeTimerSlackNs = -1L
            return -1L
        }
        val slack = if (enable) 1L else 0L
        val targetNice = if (enable) nice else 0
        val packed = runCatching { nativeConfigureThreadLatency(slack, targetNice) }.getOrDefault(-1L)
        activeTimerSlackNs = if (enable && packed.toInt() == 0) slack else -1L
        return packed
    }

    override fun effectiveAffinity(
        requested: CoreAffinityMode,
        telemetry: SiliconTelemetry,
        config: LlmConfig,
    ): CoreAffinityMode = resolveEffectiveAffinity(requested, telemetry.thermalCelsius, config.enableThermalGuard)

    private inline fun withModelFd(
        modelPath: String,
        opName: String,
        block: (fd: Int, file: File) -> String,
    ): String {
        if (modelPath.isBlank()) {
            return "$opName skipped: empty path".also { lastVmAction = it }
        }
        val file = File(modelPath)
        if (!file.isFile) {
            return "$opName skipped: missing file".also { lastVmAction = it }
        }
        if (!nativeLoaded) {
            return "$opName simulated (${file.length() / (1024L * 1024L)} MB)".also { lastVmAction = it }
        }
        // Open O_RDONLY only inside nativeLoaded so JVM unit tests never touch Android ParcelFileDescriptor stubs.
        val msg = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                block(pfd.fd, file)
            }
        }.getOrElse { "$opName error: ${it.message ?: "io"}" }
        lastVmAction = msg
        return msg
    }

    private fun sampleInternal(
        config: LlmConfig,
        applyNative: Boolean,
        runBench: Boolean,
    ): SiliconTelemetry {
        val rawCores = readSysfsCores()
        val thermalC = readThermalCelsius()
        val effectiveMode = resolveEffectiveAffinity(config.coreAffinity, thermalC, config.enableThermalGuard)
        val throttled = effectiveMode != config.coreAffinity
        val (cores, mask) = classifyCoresAndMask(rawCores, effectiveMode)

        var fpcrValue = 0L
        var l1DCacheBytes = 0
        var l1ICacheBytes = 0
        var timerFreqHz = 0L

        if (applyNative) {
            configureLowLatencyThread(config.enableLowLatencyThread)
        }
        if (nativeLoaded) {
            if (applyNative || lastFpcrValue == 0L) {
                lastFpcrValue = runCatching { nativeSetFpcrFlushToZero(config.enableFpcrFlushToZero) }.getOrDefault(0L)
            }
            fpcrValue = lastFpcrValue
            if (applyNative) {
                runCatching { nativePinThreadAffinity(mask) }
            }
            val regs = runCatching { nativeReadSiliconRegs() }.getOrDefault(0L)
            val ctr = regs and 0xFFFFFFFFL
            timerFreqHz = (regs ushr 32) and 0xFFFFFFFFL
            if (ctr != 0L) {
                // CTR_EL0[3:0] = log2(words) for I-cache min line; [19:16] = log2(words) for D-cache min line.
                l1ICacheBytes = 4 shl (ctr.toInt() and 0xF)
                l1DCacheBytes = 4 shl ((ctr ushr 16).toInt() and 0xF)
            }
            if (runBench && timerFreqHz > 0L) {
                val ticks = runCatching { nativeNeonDotBench(BENCH_LOOPS) }.getOrDefault(0L)
                if (ticks > 0L) {
                    val seconds = ticks.toDouble() / timerFreqHz.toDouble()
                    lastNeonGflops = (BENCH_LOOPS.toDouble() * FLOPS_PER_LOOP) / seconds / 1e9
                }
            }
        }

        val (availRamMb, totalRamMb) = readMemInfoMb()
        val flushToZeroActive = if (nativeLoaded && fpcrValue != 0L) {
            (fpcrValue and FPCR_FZ_BIT) != 0L
        } else {
            config.enableFpcrFlushToZero
        }

        return SiliconTelemetry(
            nativeLoaded = nativeLoaded,
            arch = System.getProperty("os.arch") ?: "unknown",
            fpcrHex = "0x%08X".format(fpcrValue and 0xFFFFFFFFL),
            flushToZeroActive = flushToZeroActive,
            l1DCacheLineBytes = l1DCacheBytes,
            l1ICacheLineBytes = l1ICacheBytes,
            timerFreqMhz = (timerFreqHz / 1_000_000L).toInt(),
            activeAffinityMaskHex = "0x%02X".format(mask),
            neonGflops = lastNeonGflops,
            thermalCelsius = thermalC,
            availRamMb = availRamMb,
            totalRamMb = totalRamMb,
            cores = cores,
            machineCodeDisassembly = MACHINE_CODE_DISASSEMBLY,
            lastVmAction = lastVmAction,
            timerSlackNs = activeTimerSlackNs,
            thermalThrottled = throttled,
        )
    }

    private fun discoverSysfsCores(): List<CoreDescriptor> = runCatching {
        val cpuRoot = File("/sys/devices/system/cpu")
        val cpuDirs = cpuRoot.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) }
            ?: return emptyList()
        cpuDirs.mapNotNull { dir ->
            val idx = dir.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
            val cpufreqDir = File(dir, "cpufreq")
            // Pixel SELinux can restrict cpufreq; fall back to world-readable EAS cpu_capacity.
            val maxFreq = File(cpufreqDir, "cpuinfo_max_freq").readIntOrNull()
                ?: File(dir, "cpu_capacity").readIntOrNull()
                ?: 0
            CoreDescriptor(
                index = idx,
                maxFreqKhz = maxFreq,
                curFreqFile = File(cpufreqDir, "scaling_cur_freq"),
            )
        }.sortedBy { it.index }
    }.getOrDefault(emptyList())

    private fun readSysfsCores(): List<ArmCoreInfo> = runCatching {
        cachedCoreDescriptors.map { core ->
            ArmCoreInfo(
                coreIndex = core.index,
                maxFreqKhz = core.maxFreqKhz,
                curFreqKhz = core.curFreqFile.readIntOrNull() ?: 0,
                tier = "",
            )
        }
    }.getOrDefault(emptyList())

    private fun readMemInfoMb(): Pair<Long, Long> = runCatching {
        val memFile = File("/proc/meminfo")
        if (!memFile.isFile) return 0L to 0L
        var totalKb = 0L
        var availKb = 0L
        var freeKb = 0L
        memFile.forEachLine { line ->
            when {
                line.startsWith("MemTotal:") -> totalKb = line.parseMemKb()
                line.startsWith("MemAvailable:") -> availKb = line.parseMemKb()
                line.startsWith("MemFree:") -> freeKb = line.parseMemKb()
            }
        }
        val effectiveAvail = if (availKb > 0L) availKb else freeKb
        (effectiveAvail / 1024L) to (totalKb / 1024L)
    }.getOrDefault(0L to 0L)

    /**
     * Reads device temperature via permissionless Android framework APIs (`ACTION_BATTERY_CHANGED`
     * sticky broadcast + `PowerManager.getCurrentThermalStatus`) instead of listing
     * `/sys/class/thermal`, which triggers SELinux `sysfs_thermal` directory read denials on
     * Android 15+/17+ `untrusted_app` domains.
     */
    private fun readThermalCelsius(): Float? {
        val ctx = context ?: return null
        return runCatching {
            val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val rawTenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
                    ?: PowerManager.THERMAL_STATUS_NONE
            } else {
                PowerManager.THERMAL_STATUS_NONE
            }
            resolveFrameworkThermalCelsius(rawTenthsC, thermalStatus)
        }.getOrNull()
    }

    private fun File.readIntOrNull(): Int? =
        if (isFile) runCatching { readText().trim().toIntOrNull() }.getOrNull() else null

    private fun String.parseMemKb(): Long =
        split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L

    private external fun nativeSetFpcrFlushToZero(enable: Boolean): Long
    private external fun nativeReadSiliconRegs(): Long
    private external fun nativePinThreadAffinity(mask: Long): Int
    private external fun nativeNeonDotBench(loops: Int): Long
    private external fun nativeFadviseModelFd(fd: Int, offset: Long, len: Long, advice: Int): Int
    private external fun nativeConfigureThreadLatency(timerSlackNs: Long, niceValue: Int): Long

    companion object {
        private const val FPCR_FZ_BIT = 1L shl 24
        private const val BENCH_LOOPS = 500_000
        private const val FLOPS_PER_LOOP = 64.0 // 8x fmla v0.4s, v1.4s, v2.4s (4 lanes * 2 ops each)
        private const val POSIX_FADV_SEQUENTIAL = 2
        private const val POSIX_FADV_WILLNEED = 3
        private const val POSIX_FADV_DONTNEED = 4
        const val THERMAL_THROTTLE_C = 78f
        const val LOW_RAM_EVICT_MB = 450L

        /** Returns `true` only when `/proc/meminfo` reported a positive low-RAM figure below 450 MB. */
        fun shouldEvictOnLowRam(availRamMb: Long): Boolean = availRamMb in 1L until LOW_RAM_EVICT_MB

        /**
         * Combines sticky battery temperature (`EXTRA_TEMPERATURE` tenths of °C, validated in `5f..130f`)
         * and `PowerManager` thermal status so OS-reported severe/moderate thermal load triggers
         * [THERMAL_THROTTLE_C] even when battery thermistor readings lag behind SoC die heat.
         */
        fun resolveFrameworkThermalCelsius(
            batteryTenthsCelsius: Int,
            thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
        ): Float? {
            val batteryC = (batteryTenthsCelsius / 10f).takeIf { batteryTenthsCelsius > 0 && it in 5f..130f }
            return when {
                thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                    maxOf(batteryC ?: THERMAL_THROTTLE_C, THERMAL_THROTTLE_C)
                thermalStatus == PowerManager.THERMAL_STATUS_MODERATE ->
                    batteryC ?: THERMAL_THROTTLE_C
                else -> batteryC
            }
        }

        /** Downshifts `PRIME_ONLY` -> `BIG_AND_PRIME` at or above 78C when thermal guard is active. */
        fun resolveEffectiveAffinity(
            requested: CoreAffinityMode,
            thermalCelsius: Float?,
            enableThermalGuard: Boolean,
        ): CoreAffinityMode =
            if (
                enableThermalGuard &&
                requested == CoreAffinityMode.PRIME_ONLY &&
                (thermalCelsius ?: 0f) >= THERMAL_THROTTLE_C
            ) {
                CoreAffinityMode.BIG_AND_PRIME
            } else {
                requested
            }

        /**
         * Classifies heterogeneous ARM cores into "Prime", "Big", and "Little" tiers by max
         * frequency and builds a 64-bit CPU affinity mask for [mode]. Exposed for JVM testing.
         */
        fun classifyCoresAndMask(
            rawCores: List<ArmCoreInfo>,
            mode: CoreAffinityMode,
        ): Pair<List<ArmCoreInfo>, Long> {
            if (rawCores.isEmpty()) {
                val count = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
                val allMask = (1L shl count) - 1L
                val fallbackMask = when (mode) {
                    CoreAffinityMode.ALL_CORES -> allMask
                    CoreAffinityMode.BIG_AND_PRIME -> (allMask ushr (count / 2)) shl (count / 2)
                    CoreAffinityMode.PRIME_ONLY -> 1L shl (count - 1)
                    CoreAffinityMode.EFFICIENCY_ONLY -> (1L shl (count / 2).coerceAtLeast(1)) - 1L
                }.takeIf { it != 0L } ?: allMask
                return emptyList<ArmCoreInfo>() to fallbackMask
            }

            val distinctFreqs = rawCores.map { it.maxFreqKhz }.filter { it > 0 }.distinct().sortedDescending()
            val classified = rawCores.map { core ->
                val tier = when {
                    distinctFreqs.size >= 3 -> when (core.maxFreqKhz) {
                        distinctFreqs.first() -> "Prime"
                        distinctFreqs.last() -> "Little"
                        else -> "Big"
                    }
                    distinctFreqs.size == 2 -> if (core.maxFreqKhz == distinctFreqs.first()) "Big" else "Little"
                    else -> "Big"
                }
                core.copy(tier = tier)
            }

            val selected = when (mode) {
                CoreAffinityMode.ALL_CORES -> classified
                CoreAffinityMode.BIG_AND_PRIME ->
                    classified.filter { it.tier == "Prime" || it.tier == "Big" }
                CoreAffinityMode.PRIME_ONLY ->
                    classified.filter { it.tier == "Prime" }
                        .ifEmpty { classified.filter { it.tier == "Big" } }
                CoreAffinityMode.EFFICIENCY_ONLY ->
                    classified.filter { it.tier == "Little" }
            }.ifEmpty { classified }

            val mask = selected.fold(0L) { acc, core ->
                if (core.coreIndex in 0..62) acc or (1L shl core.coreIndex) else acc
            }.takeIf { it != 0L } ?: 1L
            return classified to mask
        }

        val MACHINE_CODE_DISASSEMBLY: List<String> = listOf(
            "; nativeSetFpcrFlushToZero(enable)",
            "d53b4400  mrs  x0, fpcr",
            "52a06009  movz w9, #0x0300, lsl #16",
            "34000062  cbz  w2, +12",
            "aa090000  orr  x0, x0, x9",
            "14000002  b    +8",
            "8a290000  bic  x0, x0, x9",
            "d51b4400  msr  fpcr, x0",
            "d53b4400  mrs  x0, fpcr",
            "d65f03c0  ret",
            "; nativeReadSiliconRegs()",
            "d53b0020  mrs  x0, ctr_el0",
            "2a0003e0  mov  w0, w0",
            "d53be001  mrs  x1, cntfrq_el0",
            "aa018000  orr  x0, x0, x1, lsl #32",
            "d65f03c0  ret",
            "; nativePinThreadAffinity(mask)",
            "d10043ff  sub  sp, sp, #16",
            "a9007fe2  stp  x2, xzr, [sp]",
            "d2800000  mov  x0, #0",
            "d2800101  mov  x1, #8",
            "910003e2  mov  x2, sp",
            "d2800f48  mov  x8, #122 ; __NR_sched_setaffinity",
            "d4000001  svc  #0",
            "910043ff  add  sp, sp, #16",
            "d65f03c0  ret",
            "; nativeNeonDotBench(loops)",
            "52800029  mov  w9, #1",
            "7100005f  cmp  w2, #0",
            "1a89c042  csel w2, w2, w9, gt",
            "1e2e1000  fmov s0, #1.0",
            "4e040400  dup  v0.4s, v0.s[0]",
            "4ea01c01  orr  v1.16b, v0.16b, v0.16b",
            "4ea01c02  orr  v2.16b, v0.16b, v0.16b",
            "d53be043  mrs  x3, cntvct_el0",
            "f98003e0  prfm pldl1keep, [sp]",
            "4e22cc20  fmla v0.4s, v1.4s, v2.4s ; x8 unrolled",
            "71000442  subs w2, w2, #1",
            "54fffeec  b.gt -36",
            "d53be044  mrs  x4, cntvct_el0",
            "cb030080  sub  x0, x4, x3",
            "d65f03c0  ret",
            "; nativeFadviseModelFd(fd, offset, len, advice)",
            "d2801be8  mov  x8, #223 ; __NR_fadvise64",
            "d4000001  svc  #0",
            "d65f03c0  ret",
            "; nativeConfigureThreadLatency(slackNs, nice)",
            "528003a0  mov  w0, #29  ; PR_SET_TIMERSLACK",
            "d28014e8  mov  x8, #167 ; __NR_prctl",
            "d4000001  svc  #0",
            "d2801188  mov  x8, #140 ; __NR_setpriority",
            "d4000001  svc  #0",
            "d5033b9f  dsb  ish",
            "d5033fdf  isb",
            "d65f03c0  ret",
        )
    }
}
