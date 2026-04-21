package com.localassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localassistant.data.repository.ModelRepository
import com.localassistant.ui.navigation.MainNavigation
import com.localassistant.ui.theme.LocalAssistantTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var modelRepository: ModelRepository

    @Inject
    lateinit var gemmaInferenceEngine: com.localassistant.ai.GemmaInferenceEngine

    private var sharedText by mutableStateOf<String?>(null)
    private var sharedImageUris by mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        
        // Debug: handle ADB-send intent (adb shell am start -n com.localassistant/.MainActivity -e chat_msg "hello")
        val debugMsg = intent?.getStringExtra("chat_msg")
        if (debugMsg != null) {
            android.util.Log.d("MainActivity", "Debug chat_msg received: $debugMsg")
            sharedText = debugMsg
        }
        
        setContent {
            LocalAssistantTheme {
                MainNavigation(
                    sharedText = sharedText,
                    sharedImageUris = sharedImageUris,
                    onSharedContentConsumed = {
                        sharedText = null
                        sharedImageUris = emptyList()
                    },
                    modelRepository = modelRepository,
                    gemmaInferenceEngine = gemmaInferenceEngine
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
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
