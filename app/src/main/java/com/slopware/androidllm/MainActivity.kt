package com.slopware.androidllm

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slopware.androidllm.data.LiteRtLmEngine
import com.slopware.androidllm.data.LocalChatRepository
import com.slopware.androidllm.hardware.Arm64Tuner
import com.slopware.androidllm.ui.AppTheme
import com.slopware.androidllm.ui.ChatScreen
import com.slopware.androidllm.ui.ChatViewModel
import com.slopware.androidllm.ui.resolveDarkTheme

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

    // Forward Android memory trim signals so VM page-cache eviction runs under system pressure.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        viewModel.onTrimMemory(level)
    }
}
