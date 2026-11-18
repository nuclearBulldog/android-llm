package com.slopware.androidllm

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.slopware.androidllm.data.LiteRtLmEngine
import com.slopware.androidllm.data.LocalChatRepository
import com.slopware.androidllm.hardware.Arm64Tuner
import com.slopware.androidllm.ui.AppTheme
import com.slopware.androidllm.ui.ChatScreen
import com.slopware.androidllm.ui.ChatViewModel
import com.slopware.androidllm.ui.copyImageToAppStorage
import com.slopware.androidllm.ui.resolveDarkTheme
import kotlinx.coroutines.launch

/**
 * Single-activity host wiring on-device LiteRT-LM, model hub storage, and ARM64 tuning into [ChatViewModel].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        val tuner = Arm64Tuner(applicationContext)
        ChatViewModel.factory(
            engine = LiteRtLmEngine(applicationContext, hardwareController = tuner),
            repo = LocalChatRepository(applicationContext),
            hardwareController = tuner,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            consumeAssistantIntent(intent)
        }
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val isDark = resolveDarkTheme(uiState.config.themeMode)
            // Keep system bar icon contrast synced with in-app Light/Dark overrides.
            LaunchedEffect(isDark) {
                val style = if (isDark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppTheme(
                themeMode = uiState.config.themeMode,
                accessibility = uiState.config.accessibility,
            ) {
                ChatScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAssistantIntent(intent)
    }

    // Forward Android memory trim signals so VM page-cache eviction runs under system pressure.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        viewModel.onTrimMemory(level)
    }

    private fun consumeAssistantIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val prompt = sequenceOf(
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(SearchManager.QUERY),
            intent.getStringExtra("query"),
            intent.getBundleExtra(Intent.EXTRA_ASSIST_CONTEXT)?.getString(Intent.EXTRA_TEXT),
        ).mapNotNull { it?.toString()?.trim() }.firstOrNull { it.isNotEmpty() }

        @Suppress("DEPRECATION")
        val streamUri: Uri? = (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            ?: if (action == Intent.ACTION_SEND) {
                intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            } else {
                null
            }

        val isAssistantEntry = action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND
        if (prompt == null && streamUri == null && !isAssistantEntry) return

        val autoSend = if (intent.hasExtra("auto_send")) {
            intent.getBooleanExtra("auto_send", false)
        } else {
            isAssistantEntry && !prompt.isNullOrBlank()
        }

        if (streamUri != null) {
            lifecycleScope.launch {
                val savedPath = copyImageToAppStorage(applicationContext, streamUri)
                viewModel.handleAssistantIntent(prompt = prompt, imagePath = savedPath, autoSend = autoSend)
            }
        } else {
            viewModel.handleAssistantIntent(prompt = prompt, imagePath = null, autoSend = autoSend)
        }
    }
}
