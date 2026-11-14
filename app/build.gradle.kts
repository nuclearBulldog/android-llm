import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.slopware.androidllm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.slopware.androidllm"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Our synthesized .so has no DWARF sections; skip strip so NDK is never required.
            keepDebugSymbols += "**/libslop_hw.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

/**
 * Parses `.global <symbol>` and `.inst 0x<hex>` directives from AArch64 `.asm` sources so
 * the build-time ELF64 synthesizer stays locked to the checked-in assembly files.
 */
fun parseAsmFunctions(asmFiles: List<File>): List<Pair<String, List<Long>>> {
    val functions = mutableListOf<Pair<String, MutableList<Long>>>()
    for (file in asmFiles) {
        for (raw in file.readLines()) {
            val line = raw.substringBefore("//").substringBefore(";").trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith(".global") -> {
                    val symbol = line.removePrefix(".global").trim()
                    require(symbol.isNotEmpty()) { "Empty .global directive in ${file.name}" }
                    functions += symbol to mutableListOf()
                }
                line.startsWith(".inst") -> {
                    val hex = line.removePrefix(".inst").trim().removePrefix("0x").removePrefix("0X")
                    val current = functions.lastOrNull()
                        ?: error(".inst before .global in ${file.name}: $line")
                    current.second += hex.toLong(16)
                }
            }
        }
    }
    require(functions.isNotEmpty() && functions.all { it.second.isNotEmpty() }) {
        "Expected non-empty .global/.inst JNI functions in ${asmFiles.map { it.name }}"
    }
    return functions
}

/**
 * Synthesizes a minimal 16 KB page-aligned ELF64 (`ET_DYN`, `EM_AARCH64`) shared library containing
 * raw AArch64 machine code parsed from `.asm` sources so the app builds with zero NDK required.
 */
fun synthesizeArm64HwLibrary(functions: List<Pair<String, List<Long>>>): ByteArray {
    val pageSize = 0x4000 // Android 15+ requires 16 KB PT_LOAD alignment.

    fun elfHash(name: String): Long {
        var h = 0L
        for (b in name.encodeToByteArray()) {
            h = (h shl 4) + (b.toInt() and 0xFF)
            val g = h and 0xF0000000L
            if (g != 0L) h = h xor (g ushr 24)
            h = h and g.inv()
        }
        return h and 0xFFFFFFFFL
    }

    fun alignUp(value: Int, align: Int): Int = (value + align - 1) and (align - 1).inv()

    val dynstrBuf = ByteArrayOutputStream().apply { write(0) }
    fun addDynStr(s: String): Int {
        val off = dynstrBuf.size()
        dynstrBuf.write(s.encodeToByteArray())
        dynstrBuf.write(0)
        return off
    }
    val sonameOff = addDynStr("libslop_hw.so")
    val symNameOffs = functions.map { addDynStr(it.first) }
    val dynstrBytes = dynstrBuf.toByteArray()

    val nbucket = 7
    val nchain = 1 + functions.size
    val buckets = IntArray(nbucket)
    val chains = IntArray(nchain)
    functions.forEachIndexed { idx, (name, _) ->
        val symIdx = idx + 1
        val b = (elfHash(name) % nbucket).toInt()
        chains[symIdx] = buckets[b]
        buckets[b] = symIdx
    }

    val shstrBuf = ByteArrayOutputStream().apply { write(0) }
    fun addShStr(s: String): Int {
        val off = shstrBuf.size()
        shstrBuf.write(s.encodeToByteArray())
        shstrBuf.write(0)
        return off
    }
    val shNames = listOf("", ".hash", ".dynsym", ".dynstr", ".text", ".dynamic", ".shstrtab").map {
        if (it.isEmpty()) 0 else addShStr(it)
    }
    val shstrBytes = shstrBuf.toByteArray()

    val ehdrSize = 64
    val phdrCount = 4
    val phdrSize = phdrCount * 56
    val hashOff = ehdrSize + phdrSize // 0x120
    val hashSize = (2 + nbucket + nchain) * 4
    val dynsymOff = alignUp(hashOff + hashSize, 8)
    val dynsymSize = nchain * 24
    val dynstrOff = dynsymOff + dynsymSize
    val textOff = alignUp(dynstrOff + dynstrBytes.size, 16)
    val fnOffsets = IntArray(functions.size)
    var cursor = textOff
    functions.forEachIndexed { i, (_, code) ->
        fnOffsets[i] = cursor
        cursor += code.size * 4
    }
    val textSize = cursor - textOff
    val rxEnd = cursor

    val rwOff = pageSize // 0x4000
    val dynamicSize = 8 * 16
    val shstrOff = rwOff + dynamicSize
    val shoff = alignUp(shstrOff + shstrBytes.size, 8)
    val shnum = 7
    val totalSize = shoff + shnum * 64

    val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

    // ELF64 header
    buf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))
    buf.position(16)
    buf.putShort(3) // ET_DYN
    buf.putShort(183) // EM_AARCH64
    buf.putInt(1) // EV_CURRENT
    buf.putLong(0L) // e_entry
    buf.putLong(ehdrSize.toLong()) // e_phoff
    buf.putLong(shoff.toLong()) // e_shoff
    buf.putInt(0) // e_flags
    buf.putShort(64) // e_ehsize
    buf.putShort(56) // e_phentsize
    buf.putShort(phdrCount.toShort())
    buf.putShort(64) // e_shentsize
    buf.putShort(shnum.toShort())
    buf.putShort(6) // e_shstrndx = .shstrtab

    fun putPhdr(type: Int, flags: Int, off: Long, vaddr: Long, filesz: Long, memsz: Long, align: Long) {
        buf.putInt(type); buf.putInt(flags)
        buf.putLong(off); buf.putLong(vaddr); buf.putLong(vaddr)
        buf.putLong(filesz); buf.putLong(memsz); buf.putLong(align)
    }
    putPhdr(1, 5, 0L, 0L, rxEnd.toLong(), rxEnd.toLong(), pageSize.toLong()) // PT_LOAD RX
    putPhdr(1, 6, rwOff.toLong(), rwOff.toLong(), dynamicSize.toLong(), dynamicSize.toLong(), pageSize.toLong()) // PT_LOAD RW
    putPhdr(2, 6, rwOff.toLong(), rwOff.toLong(), dynamicSize.toLong(), dynamicSize.toLong(), 8L) // PT_DYNAMIC
    putPhdr(0x6474e551, 6, 0L, 0L, 0L, 0L, 16L) // PT_GNU_STACK

    // .hash
    buf.position(hashOff)
    buf.putInt(nbucket); buf.putInt(nchain)
    buckets.forEach { buf.putInt(it) }
    chains.forEach { buf.putInt(it) }

    // .dynsym (STN_UNDEF + exported JNI functions in section 4 = .text)
    buf.position(dynsymOff)
    buf.position(dynsymOff + 24)
    functions.forEachIndexed { idx, (_, code) ->
        buf.putInt(symNameOffs[idx])
        buf.put(0x12.toByte()) // STB_GLOBAL | STT_FUNC
        buf.put(0.toByte())
        buf.putShort(4) // .text section index
        buf.putLong(fnOffsets[idx].toLong())
        buf.putLong((code.size * 4).toLong())
    }

    // .dynstr + .text
    buf.position(dynstrOff)
    buf.put(dynstrBytes)
    functions.forEachIndexed { idx, (_, code) ->
        buf.position(fnOffsets[idx])
        code.forEach { buf.putInt(it.toInt()) }
    }

    // .dynamic inside RW segment
    buf.position(rwOff)
    fun putDyn(tag: Long, value: Long) { buf.putLong(tag); buf.putLong(value) }
    putDyn(14L, sonameOff.toLong()) // DT_SONAME
    putDyn(4L, hashOff.toLong()) // DT_HASH
    putDyn(5L, dynstrOff.toLong()) // DT_STRTAB
    putDyn(6L, dynsymOff.toLong()) // DT_SYMTAB
    putDyn(10L, dynstrBytes.size.toLong()) // DT_STRSZ
    putDyn(11L, 24L) // DT_SYMENT
    putDyn(30L, 8L) // DT_FLAGS = DF_BIND_NOW
    putDyn(0L, 0L) // DT_NULL

    // .shstrtab + section headers
    buf.position(shstrOff)
    buf.put(shstrBytes)
    buf.position(shoff)
    fun putShdr(name: Int, type: Int, flags: Long, addr: Long, off: Long, size: Long, link: Int, info: Int, align: Long, ent: Long) {
        buf.putInt(name); buf.putInt(type); buf.putLong(flags)
        buf.putLong(addr); buf.putLong(off); buf.putLong(size)
        buf.putInt(link); buf.putInt(info); buf.putLong(align); buf.putLong(ent)
    }
    putShdr(shNames[0], 0, 0L, 0L, 0L, 0L, 0, 0, 0L, 0L)
    putShdr(shNames[1], 5, 2L, hashOff.toLong(), hashOff.toLong(), hashSize.toLong(), 2, 0, 4L, 4L)
    putShdr(shNames[2], 11, 2L, dynsymOff.toLong(), dynsymOff.toLong(), dynsymSize.toLong(), 3, 1, 8L, 24L)
    putShdr(shNames[3], 3, 2L, dynstrOff.toLong(), dynstrOff.toLong(), dynstrBytes.size.toLong(), 0, 0, 1L, 0L)
    putShdr(shNames[4], 1, 6L, textOff.toLong(), textOff.toLong(), textSize.toLong(), 0, 0, 16L, 0L)
    putShdr(shNames[5], 6, 3L, rwOff.toLong(), rwOff.toLong(), dynamicSize.toLong(), 3, 0, 8L, 16L)
    putShdr(shNames[6], 3, 0L, 0L, shstrOff.toLong(), shstrBytes.size.toLong(), 0, 0, 1L, 0L)

    return buf.array()
}

val generateArm64NativeLib = tasks.register("generateArm64NativeLib") {
    val asmFiles = listOf(
        layout.projectDirectory.file("src/main/asm/slop_silicon.asm").asFile,
        layout.projectDirectory.file("src/main/asm/slop_vm_guard.asm").asFile,
    )
    val soFile = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libslop_hw.so").asFile
    inputs.files(asmFiles)
    outputs.file(soFile)
    doLast {
        val bytes = synthesizeArm64HwLibrary(parseAsmFunctions(asmFiles))
        soFile.parentFile.mkdirs()
        if (!soFile.exists() || !soFile.readBytes().contentEquals(bytes)) {
            soFile.writeBytes(bytes)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(generateArm64NativeLib)
}

tasks.withType<Test>().configureEach {
    dependsOn(generateArm64NativeLib)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
