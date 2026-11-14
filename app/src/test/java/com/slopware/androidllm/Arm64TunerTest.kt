package com.slopware.androidllm

import com.slopware.androidllm.domain.ArmCoreInfo
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.hardware.Arm64Tuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class Arm64TunerTest {

    @Test
    fun classifyCoresAndMaskSplitsPrimeBigLittleTiers() {
        val raw = listOf(
            ArmCoreInfo(0, 1_800_000, 1_200_000, ""),
            ArmCoreInfo(1, 1_800_000, 1_200_000, ""),
            ArmCoreInfo(2, 1_800_000, 1_200_000, ""),
            ArmCoreInfo(3, 1_800_000, 1_200_000, ""),
            ArmCoreInfo(4, 2_600_000, 2_200_000, ""),
            ArmCoreInfo(5, 2_600_000, 2_200_000, ""),
            ArmCoreInfo(6, 2_600_000, 2_200_000, ""),
            ArmCoreInfo(7, 3_200_000, 3_000_000, ""),
        )

        val (coresAll, maskAll) = Arm64Tuner.classifyCoresAndMask(raw, CoreAffinityMode.ALL_CORES)
        assertEquals(0xFFL, maskAll)
        assertEquals(listOf("Little", "Little", "Little", "Little", "Big", "Big", "Big", "Prime"), coresAll.map { it.tier })

        val (_, maskBigPrime) = Arm64Tuner.classifyCoresAndMask(raw, CoreAffinityMode.BIG_AND_PRIME)
        assertEquals(0xF0L, maskBigPrime)

        val (_, maskPrime) = Arm64Tuner.classifyCoresAndMask(raw, CoreAffinityMode.PRIME_ONLY)
        assertEquals(0x80L, maskPrime)

        val (_, maskEfficiency) = Arm64Tuner.classifyCoresAndMask(raw, CoreAffinityMode.EFFICIENCY_ONLY)
        assertEquals(0x0FL, maskEfficiency)
    }

    @Test
    fun classifyCoresAndMaskHandlesTwoTierAndEmptySysfsFallback() {
        val twoTier = listOf(
            ArmCoreInfo(0, 1_800_000, 1_000_000, ""),
            ArmCoreInfo(1, 2_400_000, 2_000_000, ""),
        )
        val (classified, maskBig) = Arm64Tuner.classifyCoresAndMask(twoTier, CoreAffinityMode.PRIME_ONLY)
        assertEquals(listOf("Little", "Big"), classified.map { it.tier })
        assertEquals(0b10L, maskBig)

        val (emptyCores, fallbackMask) = Arm64Tuner.classifyCoresAndMask(emptyList(), CoreAffinityMode.ALL_CORES)
        assertTrue(emptyCores.isEmpty())
        assertTrue(fallbackMask != 0L)
    }

    @Test
    fun machineCodeDisassemblyAndJvmFallbackAreValid() {
        val asm = Arm64Tuner.MACHINE_CODE_DISASSEMBLY.joinToString("\n")
        assertTrue(asm.contains("d53b4400"))
        assertTrue(asm.contains("52a06009"))
        assertTrue(asm.contains("d53b0020"))
        assertTrue(asm.contains("d2800f48"))
        assertTrue(asm.contains("d4000001"))
        assertTrue(asm.contains("4e22cc20"))
        assertTrue(asm.contains("54fffeec"))
        assertTrue(asm.contains("d2801be8"))
        assertTrue(asm.contains("d28014e8"))
        assertTrue(asm.contains("d5033b9f"))
        assertTrue(asm.contains("d5033fdf"))

        val telemetry = Arm64Tuner(null).sampleTelemetry(LlmConfig(), runBench = false)
        assertFalse(telemetry.nativeLoaded)
        assertTrue(telemetry.machineCodeDisassembly.isNotEmpty())
        assertTrue(telemetry.activeAffinityMaskHex.startsWith("0x"))
    }

    @Test
    fun effectiveAffinityDownshiftsPrimeOnlyAtThermalThresholdWhenEnabled() {
        val tuner = Arm64Tuner(null)
        val baseTel = tuner.sampleTelemetry(LlmConfig(), runBench = false)

        val hotGuardOn = LlmConfig(coreAffinity = CoreAffinityMode.PRIME_ONLY, enableThermalGuard = true)
        assertEquals(
            CoreAffinityMode.BIG_AND_PRIME,
            tuner.effectiveAffinity(CoreAffinityMode.PRIME_ONLY, baseTel.copy(thermalCelsius = 78.0f), hotGuardOn),
        )
        assertEquals(
            CoreAffinityMode.BIG_AND_PRIME,
            tuner.effectiveAffinity(CoreAffinityMode.PRIME_ONLY, baseTel.copy(thermalCelsius = 84.5f), hotGuardOn),
        )
        assertEquals(
            CoreAffinityMode.PRIME_ONLY,
            tuner.effectiveAffinity(CoreAffinityMode.PRIME_ONLY, baseTel.copy(thermalCelsius = 77.9f), hotGuardOn),
        )
        assertEquals(
            CoreAffinityMode.PRIME_ONLY,
            tuner.effectiveAffinity(CoreAffinityMode.PRIME_ONLY, baseTel.copy(thermalCelsius = null), hotGuardOn),
        )

        val guardOff = hotGuardOn.copy(enableThermalGuard = false)
        assertEquals(
            CoreAffinityMode.PRIME_ONLY,
            tuner.effectiveAffinity(CoreAffinityMode.PRIME_ONLY, baseTel.copy(thermalCelsius = 90.0f), guardOff),
        )
        assertEquals(
            CoreAffinityMode.ALL_CORES,
            tuner.effectiveAffinity(CoreAffinityMode.ALL_CORES, baseTel.copy(thermalCelsius = 90.0f), hotGuardOn),
        )
    }

    @Test
    fun vmPageGuardAndLatencyThreadFallbackSafelyOnJvm() {
        val tuner = Arm64Tuner(null)
        assertTrue(tuner.warmupModelPages("").contains("empty path"))
        assertTrue(tuner.evictColdModelPages("/does/not/exist/model.litertlm").contains("missing file"))

        val tmp = Files.createTempFile("slop-vm-test-", ".litertlm").toFile()
        try {
            tmp.writeBytes(ByteArray(1024 * 1024))
            val warmMsg = tuner.warmupModelPages(tmp.absolutePath)
            assertTrue(warmMsg.startsWith("Warmup simulated"))
            assertEquals(warmMsg, tuner.sampleTelemetry(LlmConfig(), runBench = false).lastVmAction)

            val evictMsg = tuner.evictColdModelPages(tmp.absolutePath)
            assertTrue(evictMsg.startsWith("Evict simulated"))
            assertEquals(evictMsg, tuner.sampleTelemetry(LlmConfig(), runBench = false).lastVmAction)
        } finally {
            tmp.delete()
        }

        assertEquals(-1L, tuner.configureLowLatencyThread(enable = true, nice = -4))
        assertEquals(-1L, tuner.sampleTelemetry(LlmConfig(), runBench = false).timerSlackNs)

        assertFalse(Arm64Tuner.shouldEvictOnLowRam(0L))
        assertTrue(Arm64Tuner.shouldEvictOnLowRam(1L))
        assertTrue(Arm64Tuner.shouldEvictOnLowRam(449L))
        assertFalse(Arm64Tuner.shouldEvictOnLowRam(450L))
    }

    @Test
    fun asmFilesMatchGenerated16KbAlignedArm64Elf64Library() {
        fun locate(rel: String): File = listOf(File(rel), File("app/$rel")).first { it.isFile }

        val asmFiles = listOf(
            locate("src/main/asm/slop_silicon.asm"),
            locate("src/main/asm/slop_vm_guard.asm"),
        )
        val parsedFunctions = mutableListOf<Pair<String, MutableList<UInt>>>()
        for (asmFile in asmFiles) {
            for (raw in asmFile.readLines()) {
                val line = raw.substringBefore("//").substringBefore(";").trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith(".global") -> {
                        parsedFunctions += line.removePrefix(".global").trim() to mutableListOf()
                    }
                    line.startsWith(".inst") -> {
                        val hex = line.removePrefix(".inst").trim().removePrefix("0x").removePrefix("0X")
                        parsedFunctions.last().second += hex.toLong(16).toUInt()
                    }
                }
            }
        }

        val expectedSymbols = listOf(
            "Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeSetFpcrFlushToZero",
            "Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeReadSiliconRegs",
            "Java_com_slopware_androidllm_hardware_Arm64Tuner_nativePinThreadAffinity",
            "Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeNeonDotBench",
            "Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeFadviseModelFd",
            "Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeConfigureThreadLatency",
        )
        assertEquals(expectedSymbols, parsedFunctions.map { it.first })

        val soFile = locate("src/main/jniLibs/arm64-v8a/libslop_hw.so")
        val bytes = soFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // ELF64 magic, little-endian, ET_DYN (3), EM_AARCH64 (183).
        assertEquals(0x7F.toByte(), bytes[0])
        assertEquals('E'.code.toByte(), bytes[1])
        assertEquals('L'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])
        assertEquals(2.toByte(), bytes[4]) // ELFCLASS64
        assertEquals(1.toByte(), bytes[5]) // ELFDATA2LSB
        assertEquals(3.toShort(), buf.getShort(16)) // ET_DYN
        assertEquals(183.toShort(), buf.getShort(18)) // EM_AARCH64

        // Program headers: verify PT_LOAD segments have 16KB (0x4000) alignment and PT_DYNAMIC exists.
        val ePhoff = buf.getLong(32).toInt()
        val ePhentsize = buf.getShort(54).toInt()
        val ePhnum = buf.getShort(56).toInt()
        assertEquals(56, ePhentsize)

        var loadCount = 0
        var hasDynamic = false
        for (i in 0 until ePhnum) {
            val base = ePhoff + i * ePhentsize
            val pType = buf.getInt(base)
            val pAlign = buf.getLong(base + 48)
            if (pType == 1) { // PT_LOAD
                loadCount++
                assertEquals("PT_LOAD must use 16KB page alignment", 0x4000L, pAlign)
            } else if (pType == 2) { // PT_DYNAMIC
                hasDynamic = true
            }
        }
        assertEquals(2, loadCount)
        assertTrue(hasDynamic)

        // DT_HASH immediately follows 4 program headers at 0x120: nbucket=7, nchain=7 (UNDEF + 6 JNI symbols).
        val hashOff = 64 + ePhnum * ePhentsize
        val nbucket = buf.getInt(hashOff)
        val nchain = buf.getInt(hashOff + 4)
        assertEquals(7, nbucket)
        assertEquals(1 + expectedSymbols.size, nchain)

        // Verify every .dynsym global function entry points to .text (shndx=4) with exact byte length and code bytes.
        val dynsymOff = (hashOff + (2 + nbucket + nchain) * 4 + 7) and 7.inv()
        val dynstrOff = dynsymOff + nchain * 24
        parsedFunctions.forEachIndexed { idx, (symbol, fnWords) ->
            val symOff = dynsymOff + (idx + 1) * 24
            val stName = buf.getInt(symOff)
            assertEquals(0x12.toByte(), bytes[symOff + 4]) // STB_GLOBAL | STT_FUNC
            assertEquals(4.toShort(), buf.getShort(symOff + 6)) // .text section index
            val stValue = buf.getLong(symOff + 8).toInt()
            val stSize = buf.getLong(symOff + 16).toInt()
            assertEquals(fnWords.size * 4, stSize)
            val dynName = bytes.copyOfRange(dynstrOff + stName, bytes.indexOf(0.toByte(), dynstrOff + stName)).decodeToString()
            assertEquals(symbol, dynName)
            fnWords.forEachIndexed { wIdx, word ->
                assertEquals(word, buf.getInt(stValue + wIdx * 4).toUInt())
            }
        }
    }

    private fun ByteArray.indexOf(target: Byte, fromIndex: Int): Int {
        for (i in fromIndex until size) if (this[i] == target) return i
        return size
    }
}
