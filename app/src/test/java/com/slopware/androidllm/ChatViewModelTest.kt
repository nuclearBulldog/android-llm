package com.slopware.androidllm

import androidx.lifecycle.ViewModelStore
import com.slopware.androidllm.domain.AccessibilityConfig
import com.slopware.androidllm.domain.BackendType
import com.slopware.androidllm.domain.CoreAffinityMode
import com.slopware.androidllm.domain.HfModelEntry
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.Role
import com.slopware.androidllm.domain.StreamDelta
import com.slopware.androidllm.domain.ThemeMode
import com.slopware.androidllm.ui.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        engine: FakeLlmEngine = FakeLlmEngine(),
        repo: FakeChatRepository = FakeChatRepository(
            initialConfig = LlmConfig(modelPath = "/models/gemma.litertlm"),
        ),
        hardwareController: FakeHardwareController = FakeHardwareController(),
    ): ChatViewModel {
        val vm = ChatViewModel(engine, repo, hardwareController, testDispatcher)
        viewModelStore.put("chat_${System.nanoTime()}", vm)
        return vm
    }

    private fun TestScope.sendPrompt(vm: ChatViewModel, prompt: String) {
        vm.updateInput(prompt)
        vm.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun streamingAccumulatesTextAndThoughtSeparately() = runTest(testDispatcher) {
        val midStreamGate = CompletableDeferred<Unit>()
        val engine = FakeLlmEngine().apply {
            streamHandler = { _, _, _ ->
                flow {
                    emit(StreamDelta(thoughtDelta = "First "))
                    emit(StreamDelta(textDelta = "Hel"))
                    midStreamGate.await()
                    emit(StreamDelta(thoughtDelta = "thought."))
                    emit(StreamDelta(textDelta = "lo!"))
                }
            }
        }
        val repo = FakeChatRepository(initialConfig = LlmConfig(modelPath = "/models/gemma.litertlm"))
        val vm = createViewModel(engine, repo)
        runCurrent()

        vm.updateInput("Hi")
        vm.sendMessage()
        runCurrent()

        assertTrue(vm.uiState.value.isGenerating)
        assertEquals("First ", vm.uiState.value.partialThought)
        assertEquals("Hel", vm.uiState.value.partialAnswer)

        midStreamGate.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isGenerating)
        assertEquals("", state.partialAnswer)
        assertEquals("", state.partialThought)
        assertEquals(2, state.activeMessages.size)
        assertEquals(Role.USER, state.activeMessages[0].role)
        assertEquals("Hi", state.activeMessages[0].text)
        assertEquals(Role.MODEL, state.activeMessages[1].role)
        assertEquals("Hello!", state.activeMessages[1].text)
        assertEquals("First thought.", state.activeMessages[1].thought)
        assertEquals(state.activeMessages, repo.sessions.value.single().messages)
    }

    @Test
    fun stopGenerationCancelsActiveStreamAndPreservesPartialOutput() = runTest(testDispatcher) {
        val holdStream = CompletableDeferred<Unit>()
        val engine = FakeLlmEngine().apply {
            streamHandler = { _, _, _ ->
                flow {
                    emit(StreamDelta(thoughtDelta = "Thinking step"))
                    emit(StreamDelta(textDelta = "Partial reply"))
                    holdStream.await()
                    emit(StreamDelta(textDelta = " discarded"))
                }
            }
        }
        val vm = createViewModel(engine)
        runCurrent()

        vm.updateInput("Explain")
        vm.sendMessage()
        runCurrent()

        assertTrue(vm.uiState.value.isGenerating)
        assertEquals("Thinking step", vm.uiState.value.partialThought)
        assertEquals("Partial reply", vm.uiState.value.partialAnswer)

        vm.stopGeneration()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isGenerating)
        assertEquals("", state.partialAnswer)
        assertEquals("", state.partialThought)
        val assistant = state.activeMessages.last()
        assertEquals(Role.MODEL, assistant.role)
        assertEquals("Partial reply", assistant.text)
        assertEquals("Thinking step", assistant.thought)
    }

    @Test
    fun engineErrorsPropagateAndDismissClearsThem() = runTest(testDispatcher) {
        val initFailEngine = FakeLlmEngine().apply {
            initializeError = IllegalStateException("GPU OOM")
        }
        val vm1 = createViewModel(initFailEngine)
        sendPrompt(vm1, "Hi")

        assertEquals("GPU OOM", vm1.uiState.value.errorMessage)
        assertFalse(vm1.uiState.value.isInitializing)
        assertFalse(vm1.uiState.value.isGenerating)
        vm1.dismissError()
        assertNull(vm1.uiState.value.errorMessage)

        val failGate = CompletableDeferred<Unit>()
        val streamFailEngine = FakeLlmEngine().apply {
            streamHandler = { _, _, _ ->
                flow {
                    emit(StreamDelta(textDelta = "Partial "))
                    failGate.await()
                    throw IllegalStateException("JNI decode error")
                }
            }
        }
        val vm2 = createViewModel(streamFailEngine)
        runCurrent()
        vm2.updateInput("Hi again")
        vm2.sendMessage()
        runCurrent()
        assertEquals("Partial ", vm2.uiState.value.partialAnswer)

        failGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("JNI decode error", vm2.uiState.value.errorMessage)
        assertFalse(vm2.uiState.value.isGenerating)
        assertEquals("Partial ", vm2.uiState.value.activeMessages.last().text)
        vm2.dismissError()
        assertNull(vm2.uiState.value.errorMessage)
    }

    @Test
    fun sessionCreateSwitchAndDeleteUpdatesStateAndRepository() = runTest(testDispatcher) {
        val engine = FakeLlmEngine().apply {
            cannedDeltas = listOf(StreamDelta(textDelta = "Reply"))
        }
        val repo = FakeChatRepository(initialConfig = LlmConfig(modelPath = "/models/gemma.litertlm"))
        val vm = createViewModel(engine, repo)
        runCurrent()

        sendPrompt(vm, "First chat")
        val firstId = vm.uiState.value.activeSessionId
        assertNotNull(firstId)

        vm.newSession()
        assertNull(vm.uiState.value.activeSessionId)
        assertTrue(vm.uiState.value.activeMessages.isEmpty())

        sendPrompt(vm, "Second chat")
        val secondId = vm.uiState.value.activeSessionId
        assertNotNull(secondId)
        assertNotEquals(firstId, secondId)
        assertEquals(2, vm.uiState.value.sessions.size)

        vm.selectSession(firstId!!)
        assertEquals(firstId, vm.uiState.value.activeSessionId)
        assertEquals("First chat", vm.uiState.value.activeMessages.first().text)

        vm.deleteSession(secondId!!)
        assertEquals(listOf(secondId), repo.deletedIds)
        assertEquals(firstId, vm.uiState.value.activeSessionId)
        assertEquals(1, vm.uiState.value.sessions.size)

        vm.deleteSession(firstId)
        assertNull(vm.uiState.value.activeSessionId)
        assertTrue(vm.uiState.value.activeMessages.isEmpty())
        assertTrue(repo.sessions.value.isEmpty())
    }

    @Test
    fun configUpdateReinitializesOnlyWhenEngineKeyChanges() = runTest(testDispatcher) {
        val engine = FakeLlmEngine()
        val repo = FakeChatRepository(initialConfig = LlmConfig(modelPath = ""))
        val vm = createViewModel(engine, repo)
        runCurrent()

        val withModel = vm.uiState.value.config.copy(modelPath = "/models/gemma.litertlm")
        vm.updateConfig(withModel)
        advanceUntilIdle()
        assertEquals(1, engine.initializedConfigs.size)
        assertEquals("/models/gemma.litertlm", repo.config.value.modelPath)

        // Sampling, thinking, theme, or accessibility changes reuse the loaded Engine.
        vm.updateConfig(
            withModel.copy(
                temperature = 0.2,
                enableThinking = false,
                accessibility = AccessibilityConfig(highContrast = true, fontScale = 1.2f),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, engine.initializedConfigs.size)
        assertEquals(0.2, repo.config.value.temperature, 0.0001)
        assertTrue(repo.config.value.accessibility.highContrast)

        // Changing backend, CPU threads, or MTP triggers Engine re-initialization.
        vm.updateConfig(withModel.copy(backend = BackendType.CPU, cpuThreadCount = 6))
        advanceUntilIdle()
        assertEquals(2, engine.initializedConfigs.size)
        assertEquals(BackendType.CPU, engine.initializedConfigs.last().backend)
        assertEquals(6, engine.initializedConfigs.last().cpuThreadCount)
    }

    @Test
    fun toggleQuickThemeRevertsToSystemWhenTargetMatchesSystem() = runTest(testDispatcher) {
        val vm = createViewModel(
            repo = FakeChatRepository(initialConfig = LlmConfig(themeMode = ThemeMode.SYSTEM)),
        )
        runCurrent()

        // On dark OS: SYSTEM -> LIGHT, then toggling again reverts to SYSTEM (not pinned DARK).
        vm.toggleQuickTheme(systemIsDark = true)
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, vm.uiState.value.config.themeMode)

        vm.toggleQuickTheme(systemIsDark = true)
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.config.themeMode)

        // On light OS: SYSTEM -> DARK, then toggling again reverts to SYSTEM.
        vm.toggleQuickTheme(systemIsDark = false)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, vm.uiState.value.config.themeMode)

        vm.toggleQuickTheme(systemIsDark = false)
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.config.themeMode)
    }

    @Test
    fun hardwareTuningAndBenchmarkRefreshSiliconTelemetry() = runTest(testDispatcher) {
        val hw = FakeHardwareController()
        val vm = createViewModel(hardwareController = hw)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.telemetry)
        assertEquals(0, hw.appliedConfigs.size)

        vm.updateConfig(
            vm.uiState.value.config.copy(
                enableFpcrFlushToZero = false,
                coreAffinity = CoreAffinityMode.PRIME_ONLY,
            ),
        )
        advanceUntilIdle()
        assertEquals(1, hw.appliedConfigs.size)
        assertFalse(vm.uiState.value.telemetry!!.flushToZeroActive)

        vm.sampleTelemetry(runBench = true)
        advanceUntilIdle()
        assertEquals(42.5, vm.uiState.value.telemetry!!.neonGflops, 0.001)
        assertFalse(vm.uiState.value.isBenchmarking)
    }

    @Test
    fun huggingFaceHubSearchDownloadCancelAndDeleteLifecycle() = runTest(testDispatcher) {
        val entry = HfModelEntry(
            id = "gemma4-e2b",
            title = "Gemma 4 E2B IT",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeLabel = "2.58 GB",
            sizeBytes = 2_580_000_000L,
            tags = listOf("Gemma 4"),
            description = "Test model",
        )
        val engine = FakeLlmEngine()
        val repo = FakeChatRepository(initialConfig = LlmConfig()).apply {
            curatedCatalog = listOf(entry)
            repoSearchResults = listOf(entry)
            downloadResultPath = "/app/files/models/gemma-4-E2B-it.litertlm"
            downloadGate = CompletableDeferred()
        }
        val vm = createViewModel(engine, repo)
        advanceUntilIdle()
        assertEquals(listOf(entry), vm.uiState.value.catalogModels)

        vm.searchHfRepo("litert-community/gemma-4-E2B-it-litert-lm")
        advanceUntilIdle()
        assertEquals(listOf(entry), vm.uiState.value.hfSearchResults)
        assertFalse(vm.uiState.value.isSearchingHf)

        vm.downloadHfModel(entry)
        runCurrent()
        assertTrue(vm.uiState.value.downloadState.isDownloading)

        repo.downloadGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.downloadState.isDownloading)
        assertEquals("/app/files/models/gemma-4-E2B-it.litertlm", vm.uiState.value.config.modelPath)
        assertTrue("/app/files/models/gemma-4-E2B-it.litertlm" in vm.uiState.value.discoveredModels)
        assertEquals(1, engine.initializedConfigs.size)

        vm.deleteModel("/app/files/models/gemma-4-E2B-it.litertlm")
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.config.modelPath)
        assertTrue(vm.uiState.value.discoveredModels.isEmpty())
        assertTrue(engine.closeCount >= 1)

        vm.cancelDownload()
        assertEquals(1, repo.cancelDownloadCount)
    }

    @Test
    fun regenerateLastReplacesPreviousAnswerAndKeepsPriorHistory() = runTest(testDispatcher) {
        val engine = FakeLlmEngine().apply {
            cannedDeltas = listOf(StreamDelta(textDelta = "A1"))
        }
        val vm = createViewModel(engine)
        sendPrompt(vm, "Q1")

        engine.cannedDeltas = listOf(StreamDelta(textDelta = "A2"))
        sendPrompt(vm, "Q2")

        engine.cannedDeltas = listOf(StreamDelta(textDelta = "A2-v2"))
        vm.regenerateLast()
        advanceUntilIdle()

        assertEquals(listOf("Q1", "A1"), engine.lastHistory?.map { it.text })
        assertEquals("Q2", engine.lastUserMessage?.text)
        assertEquals(
            listOf("Q1", "A1", "Q2", "A2-v2"),
            vm.uiState.value.activeMessages.map { it.text },
        )
    }

    @Test
    fun evictModelPageCacheAndOnTrimMemoryEvictColdModelPages() = runTest(testDispatcher) {
        val hw = FakeHardwareController()
        val repo = FakeChatRepository(initialConfig = LlmConfig(modelPath = "/models/gemma4.litertlm"))
        val vm = createViewModel(repo = repo, hardwareController = hw)
        advanceUntilIdle()

        vm.onTrimMemory(5)
        advanceUntilIdle()
        assertTrue(hw.evictedModels.isEmpty())

        vm.onTrimMemory(10)
        advanceUntilIdle()
        assertEquals(listOf("/models/gemma4.litertlm"), hw.evictedModels)
        assertEquals("Evicted cold pages", vm.uiState.value.telemetry?.lastVmAction)

        vm.evictModelPageCache()
        advanceUntilIdle()
        assertEquals(listOf("/models/gemma4.litertlm", "/models/gemma4.litertlm"), hw.evictedModels)
    }

    @Test
    fun updateConfigAppliesLowLatencyThreadAndThermalGuardTelemetry() = runTest(testDispatcher) {
        val hw = FakeHardwareController().apply { fakeThermalCelsius = 82.0f }
        val repo = FakeChatRepository(
            initialConfig = LlmConfig(
                modelPath = "/models/gemma4.litertlm",
                coreAffinity = CoreAffinityMode.BIG_AND_PRIME,
                enableLowLatencyThread = true,
                enableThermalGuard = true,
            ),
        )
        val vm = createViewModel(repo = repo, hardwareController = hw)
        advanceUntilIdle()

        val primeCfg = vm.uiState.value.config.copy(coreAffinity = CoreAffinityMode.PRIME_ONLY)
        vm.updateConfig(primeCfg)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.telemetry?.thermalThrottled == true)
        assertEquals(50_000L, vm.uiState.value.telemetry?.timerSlackNs)

        vm.updateConfig(primeCfg.copy(enableLowLatencyThread = false))
        advanceUntilIdle()
        assertFalse(hw.latencyConfigs.last().first)
        assertEquals(0L, vm.uiState.value.telemetry?.timerSlackNs)
    }

    @Test
    fun streamingAccumulatesBurstDeltasIntoFinalMessage() = runTest(testDispatcher) {
        val burst = (1..24).map { i -> StreamDelta(textDelta = "w$i ", thoughtDelta = "t$i ") }
        val engine = FakeLlmEngine().apply { cannedDeltas = burst }
        val vm = createViewModel(engine)

        sendPrompt(vm, "Burst")

        val assistant = vm.uiState.value.activeMessages.last()
        assertEquals(Role.MODEL, assistant.role)
        assertEquals((1..24).joinToString("") { "w$it " }, assistant.text)
        assertEquals((1..24).joinToString("") { "t$it " }, assistant.thought)
        assertFalse(vm.uiState.value.isGenerating)
        assertEquals("", vm.uiState.value.partialAnswer)
        assertEquals("", vm.uiState.value.partialThought)
    }
}
