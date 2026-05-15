package com.localyze

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.ModelRepository
import com.localyze.ui.navigation.MainNavigation
import com.localyze.ui.theme.LocalyzeTheme
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var modelRepository: ModelRepository

    @Inject
    lateinit var gemmaInferenceEngine: com.localyze.ai.GemmaInferenceEngine

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private var sharedText by mutableStateOf<String?>(null)
    private var sharedImageUris by mutableStateOf<List<String>>(emptyList())
    private var codeTestPrompt by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        handleDebugSettingsIntent(intent)
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
        handleDebugSettingsIntent(intent)
        handleDebugChatIntent(intent)
        handleCodeTestIntent(intent)
    }

    private fun handleDebugSettingsIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return

        when {
            intent.getBooleanExtra("enable_web_search", false) -> {
                com.localyze.utils.AppLog.d("MainActivity", "Debug enable_web_search received")
                lifecycleScope.launch {
                    settingsDataStore.setAllowWebSearch(true)
                }
            }
            intent.getBooleanExtra("disable_web_search", false) -> {
                com.localyze.utils.AppLog.d("MainActivity", "Debug disable_web_search received")
                lifecycleScope.launch {
                    settingsDataStore.setAllowWebSearch(false)
                }
            }
        }

        // Allow per-test override of thinking mode (debug-only). The baseline
        // findings showed thinking-on garbles digits and inflates latency on
        // small models. Tests can pin it off.
        if (intent.hasExtra("set_thinking")) {
            val v = intent.getBooleanExtra("set_thinking", false)
            com.localyze.utils.AppLog.d("MainActivity", "Debug set_thinking=$v received")
            lifecycleScope.launch { settingsDataStore.setThinkingMode(v) }
        }
        // Per-test sampling overrides. Lower temp for digit/code-heavy prompts.
        if (intent.hasExtra("set_temperature")) {
            val t = intent.getFloatExtra("set_temperature", 0.7f)
            com.localyze.utils.AppLog.d("MainActivity", "Debug set_temperature=$t received")
            gemmaInferenceEngine.setTemperature(t)
        }
        if (intent.hasExtra("set_top_k")) {
            val k = intent.getIntExtra("set_top_k", 40)
            com.localyze.utils.AppLog.d("MainActivity", "Debug set_top_k=$k received")
            gemmaInferenceEngine.setTopK(k)
        }
        // Per-test backend override (debug). When the GPU backend produces
        // garbled numbers/identifiers on a particular device, force CPU as
        // the next-load backend. Persisted in SettingsDataStore so the
        // preference is read by the engine BEFORE it starts loading, which
        // avoids the race where GPU init wins before forceCpu kicks in.
        if (intent.hasExtra("force_cpu")) {
            val v = intent.getBooleanExtra("force_cpu", false)
            com.localyze.utils.AppLog.d("MainActivity", "Debug force_cpu=$v received (persisting)")
            lifecycleScope.launch {
                settingsDataStore.setForceCpuBackend(v)
                gemmaInferenceEngine.setForceCpu(v)
            }
        }
        // Toggle web search on/off via intent extra. Used by the
        // online-vs-offline quality eval harness so a single script can
        // flip modes between batches without driving Settings UI taps.
        if (intent.hasExtra("allow_web_search")) {
            val v = intent.getBooleanExtra("allow_web_search", true)
            com.localyze.utils.AppLog.d("MainActivity", "Debug allow_web_search=$v received (persisting)")
            lifecycleScope.launch {
                settingsDataStore.setAllowWebSearch(v)
            }
        }
    }

    private fun handleDebugChatIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return

        val debugMsg = intent?.getStringExtra("chat_msg")
            ?.let(::decodeDebugMessage)
            ?.trim()
        if (!debugMsg.isNullOrEmpty()) {
            com.localyze.utils.AppLog.d("MainActivity", "Debug chat_msg received: $debugMsg")
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
        if (!BuildConfig.DEBUG) return

        if (intent == null) return
        if (intent.getBooleanExtra("triggerTest", false)) {
            val rawPrompt = intent.getStringExtra("testPrompt") ?: "make a portfolio website with animations"
            val prompt = decodeDebugMessage(rawPrompt)
            com.localyze.utils.AppLog.d("MainActivity", "Code test prompt: $prompt")
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
