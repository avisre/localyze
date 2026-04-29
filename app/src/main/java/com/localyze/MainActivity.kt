package com.localyze

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localyze.data.repository.ModelRepository
import com.localyze.ui.navigation.MainNavigation
import com.localyze.ui.theme.LocalyzeTheme
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var modelRepository: ModelRepository

    @Inject
    lateinit var gemmaInferenceEngine: com.localyze.ai.GemmaInferenceEngine

    private var sharedText by mutableStateOf<String?>(null)
    private var sharedImageUris by mutableStateOf<List<String>>(emptyList())
    private var codeTestPrompt by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        handleDebugChatIntent(intent)
        handleCodeTestIntent(intent)

        setContent {
            LocalyzeTheme {
                MainNavigation(
                    sharedText = sharedText,
                    sharedImageUris = sharedImageUris,
                    codeTestPrompt = codeTestPrompt,
                    onSharedContentConsumed = {
                        sharedText = null
                        sharedImageUris = emptyList()
                    },
                    onCodeTestPromptConsumed = {
                        codeTestPrompt = null
                    },
                    modelRepository = modelRepository,
                    gemmaInferenceEngine = gemmaInferenceEngine
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        handleDebugChatIntent(intent)
        handleCodeTestIntent(intent)
    }

    private fun handleDebugChatIntent(intent: Intent?) {
        val debugMsg = intent?.getStringExtra("chat_msg")
            ?.let(::decodeDebugMessage)
            ?.trim()
        if (!debugMsg.isNullOrEmpty()) {
            android.util.Log.d("MainActivity", "Debug chat_msg received: $debugMsg")
            sharedText = debugMsg
            sharedImageUris = emptyList()
        }
    }

    private fun decodeDebugMessage(raw: String): String {
        val normalized = raw.replace("%s", " ")
        return runCatching {
            URLDecoder.decode(normalized, Charsets.UTF_8.name())
        }.getOrDefault(normalized)
    }

    private fun handleCodeTestIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("triggerTest", false)) {
            val prompt = intent.getStringExtra("testPrompt") ?: "make a portfolio website with animations"
            android.util.Log.d("MainActivity", "Code test prompt: $prompt")
            codeTestPrompt = prompt
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    sharedImageUris = emptyList()
                } else if (intent.type?.startsWith("image/") == true) {
                    val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        sharedImageUris = listOf(uri.toString())
                    }
                    sharedText = null
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    sharedImageUris = uris?.map { it.toString() } ?: emptyList()
                    sharedText = null
                }
            }
        }
    }
}
