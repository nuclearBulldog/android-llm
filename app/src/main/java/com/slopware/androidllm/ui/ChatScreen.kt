package com.slopware.androidllm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slopware.androidllm.domain.AccessibilityConfig
import com.slopware.androidllm.domain.ArmCoreInfo
import com.slopware.androidllm.domain.BackendType
import com.slopware.androidllm.domain.ChatMessage
import com.slopware.androidllm.domain.ChatSession
import com.slopware.androidllm.domain.HfModelEntry
import com.slopware.androidllm.domain.LlmConfig
import com.slopware.androidllm.domain.ModelDownloadState
import com.slopware.androidllm.domain.Role
import com.slopware.androidllm.domain.SiliconTelemetry
import com.slopware.androidllm.domain.ThemeMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()
    var showModelHub by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val savedPath = copyImageToAppStorage(context, uri)
                if (savedPath != null) {
                    viewModel.attachImage(savedPath)
                } else {
                    Toast.makeText(context, "Could not read selected image.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    ChatScreenContent(
        uiState = uiState,
        modifier = modifier,
        onInputChange = viewModel::updateInput,
        onAttachImageClick = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onClearAttachment = { viewModel.attachImage(null) },
        onSendMessage = viewModel::sendMessage,
        onStopGeneration = viewModel::stopGeneration,
        onRegenerateLast = viewModel::regenerateLast,
        onNewSession = viewModel::newSession,
        onSelectSession = viewModel::selectSession,
        onDeleteSession = viewModel::deleteSession,
        onOpenModelHub = {
            viewModel.refreshModels()
            showModelHub = true
        },
        onToggleQuickTheme = { viewModel.toggleQuickTheme(systemDark) },
        onOpenSettings = {
            viewModel.sampleTelemetry(runBench = false)
            showSettings = true
        },
        onDismissError = viewModel::dismissError,
        onCopyText = { text -> copyToClipboard(context, text) },
    )

    if (showModelHub) {
        ModelHubSheet(
            config = uiState.config,
            discoveredModels = uiState.discoveredModels,
            catalogModels = uiState.catalogModels,
            hfSearchResults = uiState.hfSearchResults,
            isSearchingHf = uiState.isSearchingHf,
            downloadState = uiState.downloadState,
            isBusy = uiState.isInitializing || uiState.isGenerating,
            onSelectModel = { path ->
                viewModel.updateConfig(uiState.config.copy(modelPath = path))
                showModelHub = false
            },
            onRefreshModels = viewModel::refreshModels,
            onImportModel = viewModel::importModel,
            onDeleteModel = viewModel::deleteModel,
            onSearchHfRepo = viewModel::searchHfRepo,
            onDownloadHfModel = viewModel::downloadHfModel,
            onCancelDownload = viewModel::cancelDownload,
            onCopySnippet = { copyToClipboard(context, it) },
            onDismiss = { showModelHub = false },
        )
    }

    if (showSettings) {
        SettingsSheet(
            config = uiState.config,
            telemetry = uiState.telemetry,
            isBusy = uiState.isInitializing || uiState.isGenerating,
            isBenchmarking = uiState.isBenchmarking,
            onUpdateConfig = viewModel::updateConfig,
            onRunBenchmark = { viewModel.sampleTelemetry(runBench = true) },
            onEvictPageCache = viewModel::evictModelPageCache,
            onOpenModelHub = {
                showSettings = false
                viewModel.refreshModels()
                showModelHub = true
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    modifier: Modifier = Modifier,
    onInputChange: (String) -> Unit,
    onAttachImageClick: () -> Unit,
    onClearAttachment: () -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerateLast: () -> Unit,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenModelHub: () -> Unit,
    onToggleQuickTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onCopyText: (String) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isDarkRendered = resolveDarkTheme(uiState.config.themeMode)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawerSheet(
                sessions = uiState.sessions,
                activeSessionId = uiState.activeSessionId,
                onNewChat = {
                    onNewSession()
                    scope.launch { drawerState.close() }
                },
                onSelectSession = { id ->
                    onSelectSession(id)
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = onDeleteSession,
            )
        },
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0),
            topBar = {
                ChatTopBar(
                    config = uiState.config,
                    isInitializing = uiState.isInitializing,
                    isDownloading = uiState.downloadState.isDownloading,
                    isDarkRendered = isDarkRendered,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onModelHubClick = onOpenModelHub,
                    onToggleTheme = onToggleQuickTheme,
                    onSettingsClick = onOpenSettings,
                )
            },
        ) { innerPadding ->
            val reduceMotion = LocalAccessibility.current.reduceMotion
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Union navBars + ime so opening the keyboard never double-pads bottom insets.
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                MessageList(
                    messages = uiState.activeMessages,
                    isGenerating = uiState.isGenerating,
                    partialAnswer = uiState.partialAnswer,
                    partialThought = uiState.partialThought,
                    config = uiState.config,
                    telemetry = uiState.telemetry,
                    onOpenModelHub = onOpenModelHub,
                    onStarterPrompt = onInputChange,
                    onRegenerateLast = onRegenerateLast,
                    onCopyText = onCopyText,
                    modifier = Modifier.weight(1f),
                )

                var latestError by remember { mutableStateOf("") }
                if (uiState.errorMessage != null) latestError = uiState.errorMessage
                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
                    exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
                ) {
                    ErrorBanner(message = latestError, onDismiss = onDismissError)
                }

                FloatingComposerBar(
                    inputText = uiState.inputText,
                    attachedImagePath = uiState.attachedImagePath,
                    isGenerating = uiState.isGenerating,
                    isInitializing = uiState.isInitializing,
                    onInputChange = onInputChange,
                    onAttachImageClick = onAttachImageClick,
                    onClearAttachment = onClearAttachment,
                    onSendMessage = onSendMessage,
                    onStopGeneration = onStopGeneration,
                )
            }
        }
    }
}

@Composable
private fun HistoryDrawerSheet(
    sessions: List<ChatSession>,
    activeSessionId: String?,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val touchSize = minTouchSize()
    ModalDrawerSheet {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            FilledTonalButton(
                onClick = onNewChat,
                modifier = Modifier.heightIn(min = touchSize),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New")
            }
        }
        HorizontalDivider()
        if (sessions.isEmpty()) {
            Text(
                text = "No conversations yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = session.title.ifBlank { "Untitled chat" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        selected = session.id == activeSessionId,
                        onClick = { onSelectSession(session.id) },
                        badge = {
                            IconButton(
                                onClick = { onDeleteSession(session.id) },
                                modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${session.title}")
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    config: LlmConfig,
    isInitializing: Boolean,
    isDownloading: Boolean,
    isDarkRendered: Boolean,
    onMenuClick: () -> Unit,
    onModelHubClick: () -> Unit,
    onToggleTheme: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val touchSize = minTouchSize()
    val modelLabel = config.modelPath
        .substringAfterLast('/')
        .removeSuffix(".litertlm")
        .ifBlank { "Select model" }

    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Open chat history")
            }
        },
        title = {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.clickable(onClick = onModelHubClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isInitializing || isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = "$modelLabel · ${config.backend.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                }
            }
        },
        actions = {
            val haptics = LocalHapticFeedback.current
            val themeLabel = if (isDarkRendered) "Switch to light mode" else "Switch to dark mode"
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleTheme()
                },
                modifier = Modifier
                    .sizeIn(minWidth = touchSize, minHeight = touchSize)
                    .semantics { contentDescription = themeLabel },
            ) {
                ThemeComfortGlyph(
                    isDark = isDarkRendered,
                    contentColor = LocalContentColor.current,
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings and hardware tuner")
            }
        },
    )
}

@Composable
private fun ThemeComfortGlyph(isDark: Boolean, contentColor: Color) {
    Canvas(
        modifier = Modifier
            .size(22.dp)
            // Offscreen layer keeps BlendMode.Clear confined to the moon cutout instead of punching the window.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val r = size.minDimension * 0.26f
        if (isDark) {
            drawCircle(color = contentColor, radius = r, style = Stroke(width = 2.dp.toPx()))
            val inner = size.minDimension * 0.36f
            val outer = size.minDimension * 0.46f
            for (deg in 0 until 360 step 45) {
                val rad = Math.toRadians(deg.toDouble())
                drawLine(
                    color = contentColor,
                    start = Offset(center.x + (inner * cos(rad)).toFloat(), center.y + (inner * sin(rad)).toFloat()),
                    end = Offset(center.x + (outer * cos(rad)).toFloat(), center.y + (outer * sin(rad)).toFloat()),
                    strokeWidth = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        } else {
            drawCircle(color = contentColor, radius = size.minDimension * 0.36f)
            drawCircle(
                color = Color.Transparent,
                radius = size.minDimension * 0.28f,
                center = Offset(center.x + size.minDimension * 0.14f, center.y - size.minDimension * 0.1f),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    partialAnswer: String,
    partialThought: String,
    config: LlmConfig,
    telemetry: SiliconTelemetry?,
    onOpenModelHub: () -> Unit,
    onStarterPrompt: (String) -> Unit,
    onRegenerateLast: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalAccessibility.current.reduceMotion
    val touchSize = minTouchSize()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }

    LaunchedEffect(listState.isScrollInProgress, listState.canScrollForward, listState.lastScrolledBackward) {
        if (listState.isScrollInProgress && listState.lastScrolledBackward) {
            autoScrollEnabled = false
        } else if (!listState.canScrollForward) {
            autoScrollEnabled = true
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) autoScrollEnabled = true
    }

    val showStreamingBubble = isGenerating || partialAnswer.isNotEmpty() || partialThought.isNotEmpty()
    val totalItems = messages.size + if (showStreamingBubble) 1 else 0
    var prevTotalItems by remember { mutableIntStateOf(0) }

    LaunchedEffect(totalItems, partialAnswer.length, partialThought.length) {
        val itemAdded = totalItems > prevTotalItems
        prevTotalItems = totalItems
        if (autoScrollEnabled && totalItems > 0) {
            if (itemAdded && !reduceMotion) {
                listState.animateScrollToItem(totalItems - 1, scrollOffset = 100_000)
            } else if (itemAdded || listState.canScrollForward) {
                listState.scrollToItem(totalItems - 1, scrollOffset = 100_000)
            }
        }
    }

    if (totalItems == 0) {
        EmptyChatPlaceholder(
            config = config,
            telemetry = telemetry,
            onOpenModelHub = onOpenModelHub,
            onStarterPrompt = onStarterPrompt,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                when (msg.role) {
                    Role.USER -> UserBubble(message = msg)
                    Role.MODEL -> AssistantBubble(
                        messageKey = msg.id,
                        text = msg.text,
                        thought = msg.thought,
                        isStreaming = false,
                        canRegenerate = !isGenerating && index == messages.lastIndex,
                        onRegenerate = onRegenerateLast,
                        onCopy = { onCopyText(msg.text) },
                        onCopyCode = onCopyText,
                    )
                }
            }
            if (showStreamingBubble) {
                item(key = "streaming_assistant") {
                    AssistantBubble(
                        messageKey = "streaming_assistant",
                        text = partialAnswer,
                        thought = partialThought,
                        isStreaming = isGenerating,
                        canRegenerate = false,
                        onRegenerate = {},
                        onCopy = { onCopyText(partialAnswer) },
                        onCopyCode = onCopyText,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !autoScrollEnabled && canScrollDown,
            enter = if (reduceMotion) EnterTransition.None else fadeIn() + scaleIn(initialScale = 0.88f),
            exit = if (reduceMotion) ExitTransition.None else fadeOut() + scaleOut(targetScale = 0.88f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 4.dp,
                tonalElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                onClick = {
                    autoScrollEnabled = true
                    scope.launch {
                        if (totalItems > 0) {
                            if (reduceMotion) listState.scrollToItem(totalItems - 1, 100_000)
                            else listState.animateScrollToItem(totalItems - 1, 100_000)
                        }
                    }
                },
                modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Jump to bottom",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChatPlaceholder(
    config: LlmConfig,
    telemetry: SiliconTelemetry?,
    onOpenModelHub: () -> Unit,
    onStarterPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasModel = config.modelPath.isNotBlank()
    val starters = listOf(
        "Check device time, RAM, and processor status",
        "Explain Subnormal Float Flush-to-Zero on ARM64",
        "Write a Kotlin coroutine retry helper",
    )

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = "Slopware AI",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                val hwStatus = buildString {
                    append(if (hasModel) config.modelPath.substringAfterLast('/') else "No model active")
                    append(" · ${config.backend.name}")
                    if (config.enableAgentTools) append(" · Assistant Tools")
                    if (telemetry != null) {
                        append(" · ${telemetry.arch}")
                        if (telemetry.flushToZeroActive) append(" · Flush-to-Zero")
                    }
                }
                Text(
                    text = hwStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (!hasModel) {
                Button(
                    onClick = onOpenModelHub,
                    modifier = Modifier.heightIn(min = minTouchSize()),
                ) {
                    Text("Browse Model Hub / Import")
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                starters.forEach { prompt ->
                    AssistChip(
                        onClick = { onStarterPrompt(prompt) },
                        label = { Text(prompt) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message.imagePath?.let { LocalImagePreview(path = it) }
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    messageKey: String,
    text: String,
    thought: String,
    isStreaming: Boolean,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (thought.isNotBlank()) {
                ThinkingCard(
                    messageKey = messageKey,
                    thought = thought,
                    isStreamingThought = isStreaming && text.isEmpty(),
                )
            }
            if (text.isNotEmpty()) {
                StreamingMarkdownText(
                    markdown = text,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    isStreaming = isStreaming,
                    onCopyCode = onCopyCode,
                )
            } else if (isStreaming && thought.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Thinking...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!isStreaming && text.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    if (canRegenerate) {
                        TextButton(onClick = onRegenerate) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingCard(
    messageKey: String,
    thought: String,
    isStreamingThought: Boolean,
) {
    var userOverride by rememberSaveable(messageKey) { mutableStateOf<Boolean?>(null) }
    val expanded = userOverride ?: isStreamingThought
    val reduceMotion = LocalAccessibility.current.reduceMotion
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "thinkingChevron",
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { userOverride = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isStreamingThought) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = if (isStreamingThought) "Thinking..." else "Thought process",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse thoughts" else "Expand thoughts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            }
            if (reduceMotion) {
                if (expanded) {
                    Text(
                        text = thought,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(180)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(150)),
                ) {
                    Text(
                        text = thought,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun FloatingComposerBar(
    inputText: String,
    attachedImagePath: String?,
    isGenerating: Boolean,
    isInitializing: Boolean,
    onInputChange: (String) -> Unit,
    onAttachImageClick: () -> Unit,
    onClearAttachment: () -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
) {
    val touchSize = minTouchSize()
    val reduceMotion = LocalAccessibility.current.reduceMotion
    val haptics = LocalHapticFeedback.current
    var latestAttachment by remember { mutableStateOf("") }
    if (attachedImagePath != null) latestAttachment = attachedImagePath

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = attachedImagePath != null,
            enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            InputChip(
                selected = true,
                onClick = onClearAttachment,
                label = {
                    Text(
                        text = latestAttachment.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove image", modifier = Modifier.size(16.dp))
                },
            )
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onAttachImageClick,
                    enabled = !isGenerating,
                    modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Attach image")
                }
                TextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { Text("Ask Slopware AI...") },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Crossfade(
                    targetState = isGenerating,
                    animationSpec = if (reduceMotion) snap() else tween(durationMillis = 180),
                    label = "composerAction",
                ) { generating ->
                    if (generating) {
                        FilledIconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onStopGeneration()
                            },
                            modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onErrorContainer,
                                        RoundedCornerShape(3.dp),
                                    ),
                            )
                        }
                    } else {
                        val canSend = !isInitializing && (inputText.isNotBlank() || attachedImagePath != null)
                        FilledIconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSendMessage()
                            },
                            enabled = canSend,
                            modifier = Modifier.sizeIn(minWidth = touchSize, minHeight = touchSize),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalImagePreview(path: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(path, maxDim = 600) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Text(
            text = "Image: ${path.substringAfterLast('/')}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun decodeSampledBitmap(path: String, maxDim: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

internal suspend fun copyImageToAppStorage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val out = File(dir, "img_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext null
        out.absolutePath
    }.getOrNull()
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("chat", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun sampleTelemetryForPreview() = SiliconTelemetry(
    nativeLoaded = true,
    arch = "arm64-v8a",
    fpcrHex = "0x03000000",
    flushToZeroActive = true,
    l1DCacheLineBytes = 64,
    l1ICacheLineBytes = 64,
    timerFreqMhz = 19,
    activeAffinityMaskHex = "0xF0",
    neonGflops = 48.6,
    thermalCelsius = 37.2f,
    availRamMb = 5420L,
    totalRamMb = 12288L,
    cores = listOf(
        ArmCoreInfo(0, 1800000, 1200000, "Little"),
        ArmCoreInfo(4, 2600000, 2200000, "Big"),
        ArmCoreInfo(7, 3200000, 3000000, "Prime"),
    ),
    machineCodeDisassembly = listOf(
        "d53b4400  mrs x0, fpcr",
        "d51b4400  msr fpcr, x0",
        "4e22cc20  fmla v0.4s, v1.4s, v2.4s",
    ),
    lastVmAction = "Warmup 32 MB (fadvise64)",
    timerSlackNs = 1L,
    thermalThrottled = false,
)

@Preview(showBackground = true)
@Composable
fun ChatScreenLightPreview() {
    AppTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        PreviewChatBody(AccessibilityConfig())
    }
}

@Preview(showBackground = true)
@Composable
fun ChatScreenDarkHighContrastPreview() {
    val a11y = AccessibilityConfig(highContrast = true, largeTouchTargets = true, reduceMotion = true)
    AppTheme(themeMode = ThemeMode.DARK, accessibility = a11y, dynamicColor = false) {
        PreviewChatBody(a11y)
    }
}

@Preview(showBackground = true)
@Composable
fun ModelHubSheetPreview() {
    AppTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        Surface {
            ModelHubSheetBody(
                config = LlmConfig(modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"),
                discoveredModels = listOf("/data/local/tmp/llm/gemma-4-E2B-it.litertlm"),
                catalogModels = listOf(
                    HfModelEntry(
                        id = "gemma4-e2b",
                        title = "Gemma 4 E2B IT",
                        repoId = "litert-community/gemma-4-E2B-it-litert-lm",
                        fileName = "gemma-4-E2B-it.litertlm",
                        sizeLabel = "2.58 GB",
                        sizeBytes = 2_770_000_000L,
                        tags = listOf("Thinking", "Vision", "MTP"),
                        description = "Fast multimodal reasoning model tuned for mobile GPU.",
                        recommendedBackend = BackendType.GPU,
                    ),
                ),
                hfSearchResults = emptyList(),
                isSearchingHf = false,
                downloadState = ModelDownloadState(),
                isBusy = false,
                onSelectModel = {},
                onRefreshModels = {},
                onImportLocalClick = {},
                onDeleteModel = {},
                onSearchHfRepo = { _, _ -> },
                onDownloadHfModel = { _, _ -> },
                onCancelDownload = {},
                onCopySnippet = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsSheetSiliconPreview() {
    AppTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        Surface {
            SettingsSheetBody(
                config = LlmConfig(themeMode = ThemeMode.DARK),
                telemetry = sampleTelemetryForPreview(),
                isBusy = false,
                isBenchmarking = false,
                onUpdateConfig = {},
                onRunBenchmark = {},
                onEvictPageCache = {},
                onOpenModelHub = {},
            )
        }
    }
}

@Composable
private fun PreviewChatBody(accessibility: AccessibilityConfig) {
    ChatScreenContent(
        uiState = ChatUiState(
            config = LlmConfig(
                modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm",
                accessibility = accessibility,
            ),
            telemetry = sampleTelemetryForPreview(),
            activeMessages = listOf(
                ChatMessage("1", Role.USER, "Show a Kotlin Fibonacci function.", timestampMs = 1L),
                ChatMessage(
                    id = "2",
                    role = Role.MODEL,
                    thought = "Writing an iterative version avoids stack overflow.",
                    text = "Here is an **iterative** version:\n```kotlin\nfun fib(n: Int): Long = TODO()\n```",
                    timestampMs = 2L,
                ),
            ),
        ),
        onInputChange = {},
        onAttachImageClick = {},
        onClearAttachment = {},
        onSendMessage = {},
        onStopGeneration = {},
        onRegenerateLast = {},
        onNewSession = {},
        onSelectSession = {},
        onDeleteSession = {},
        onOpenModelHub = {},
        onToggleQuickTheme = {},
        onOpenSettings = {},
        onDismissError = {},
        onCopyText = {},
    )
}
