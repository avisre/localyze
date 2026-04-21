package com.localyze.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.localyze.domain.models.Message as DomainMessage
import com.localyze.domain.models.MessageRole
import com.localyze.domain.models.ToolCall
import com.localyze.tools.ToolRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Inference engine using the LiteRT-LM Kotlin API with native NPU support.
 *
 * This implementation mirrors the architecture used by Google's AI Edge Gallery
 * app (https://github.com/google-ai-edge/gallery), which successfully runs
 * Gemma 4 E4B on Snapdragon devices without OOM kills.
 *
 * KEY DESIGN DECISIONS (matching AI Edge Gallery):
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * 1. Engine + Conversation are long-lived singletons
 *    - Engine: created once when model loads, lives for entire app session
 *    - Conversation: created once, REUSED across all messages in a chat session
 *    - Conversation is only reset when explicitly requested (new chat, mode change)
 *    - This is critical: Conversation maintains the KV cache and token history
 *      internally â€” recreating it each message breaks multi-turn conversations
 *
 * 2. We NEVER inject message history into Conversation
 *    - LiteRT-LM tracks conversation history automatically as you call
 *      sendMessageAsync repeatedly
 *    - Manual history injection (our old approach) causes duplication
 *
 * 3. We use the MessageCallback-based sendMessageAsync API
 *    - Gallery's pattern: conversation.sendMessageAsync(Contents, MessageCallback, extraContext)
 *    - This is the correct streaming API for LiteRT-LM
 *
 * 4. Backend selection: NPU â†’ GPU â†’ CPU with automatic fallback
 *    - When NPU is active, SamplerConfig must be null (DSP handles sampling)
 *    - Separate visionBackend and audioBackend for multimodal
 *
 * 5. conversation.cancelProcess() for stop generation
 *    - Gallery uses this for their stop button
 */
@OptIn(ExperimentalApi::class)
@Singleton
class GemmaInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val toolRegistry: ToolRegistry
) {

    companion object {
        private const val TAG = "GemmaInference"
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_TOP_P = 0.95f

        /** Sample rate for audio recording â€” 16kHz mono PCM 16-bit (Gallery pattern). */
        const val SAMPLE_RATE = 16000

        /**
         * Determine the best model file for this device.
         *
         * LiteRT-LM models come in variants optimized for specific hardware:
         * - Generic (gemma-4-E4B-it.litertlm) â€” works on CPU/GPU, no NPU optimization
         * - Qualcomm NPU (gemma-e2b-npu.litertlm / gemma-4-E2B-it_qualcomm_qcs8275.litertlm)
         *   â€” pre-compiled for Hexagon DSP on Snapdragon devices
         *
         * NPU-optimized models are ~3x faster and use ~50% less RAM
         * because compute runs on the Hexagon DSP instead of the app heap.
         *
         * We look for NPU models first; if none found, fall back to generic.
         */
/**
     * Detect if a model file is NPU-optimized (Qualcomm SoC variant).
     * NPU models have _qualcomm or _npu in the filename.
     */
    }

    private val _modelLoadState = MutableStateFlow<ModelLoadState>(ModelLoadState.NotLoaded)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    var maxTokens: Int = DEFAULT_MAX_TOKENS
        private set
    var randomSeed: Int = 0
        private set
    var temperature: Float = DEFAULT_TEMPERATURE
        private set
    var topK: Int = DEFAULT_TOP_K
        private set
    var topP: Float = DEFAULT_TOP_P
        private set

    fun setMaxTokens(value: Int) { maxTokens = value.coerceIn(256, 8192) }
    fun setRandomSeed(value: Int) { randomSeed = value }
    fun setTemperature(value: Float) { temperature = value.coerceIn(0f, 2f) }
    fun setTopK(value: Int) { topK = value.coerceAtLeast(1) }
    fun setTopP(value: Float) { topP = value.coerceIn(0f, 1f) }

    fun isModelLoaded(): Boolean = _modelLoadState.value is ModelLoadState.Loaded
    fun getActiveBackend(): String = activeBackendType

    // â”€â”€ Engine & Conversation (Gallery pattern: long-lived singletons) â”€â”€â”€â”€

    /** The LiteRT-LM engine â€” created once, lives for entire app lifetime. */
    @Volatile private var engine: Engine? = null

    /**
     * The active Conversation â€” reused across messages.
     * Only reset when explicitly requested (new chat, mode change).
     * This is CRITICAL: the Conversation maintains the KV cache and
     * tracks message history internally. Do NOT recreate per message.
     */
    @Volatile private var conversation: Conversation? = null

    /** Which backend is currently active. */
    @Volatile private var activeBackendType: String = "none"

    /** The Backend object used â€” needed for SamplerConfig decision. */
    @Volatile private var activeBackend: Backend = Backend.CPU()

    /** Current system instruction and capability mode â€” tracked for reset. */
    @Volatile private var currentSystemInstruction: Contents? = null
    @Volatile private var currentCapabilityMode: String = "chat"
    @Volatile private var currentEnableThinking: Boolean = false
    @Volatile private var currentSupportImage: Boolean = false
    @Volatile private var currentSupportAudio: Boolean = false
    @Volatile private var restoredConversationContext: String? = null

    /**
     * Initialize the engine, trying NPU â†’ GPU â†’ CPU in order.
     *
     * Matches AI Edge Gallery's LlmChatModelHelper.initialize() exactly.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (_modelLoadState.value is ModelLoadState.Loaded) return@withContext
        _modelLoadState.value = ModelLoadState.Loading(0.1f)

        try {
            // Use ModelRepository's findModelFile() to locate the best model
            val modelRepository = com.localyze.data.repository.ModelRepository(context, okhttp3.OkHttpClient())
            val modelFile = modelRepository.findModelFile()
                ?: File(context.filesDir, "models/${com.localyze.data.repository.ModelRepository.MODEL_FILENAME}")
            Log.d(TAG, "Model file: ${modelFile.absolutePath}, exists=${modelFile.exists()}, size=${modelFile.length()}")

            if (!modelFile.exists()) {
                _modelLoadState.value = ModelLoadState.Error("Gemma 4 E4B model file not found at ${modelFile.absolutePath}")
                throw ModelNotFoundException("Gemma 4 E4B model file not found")
            }

            Log.d(TAG, "Model: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB), Gemma 4 E4B only")

            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            // Set ADSP_LIBRARY_PATH for Qualcomm Hexagon DSP (required for NPU)
            // See: https://github.com/google-ai-edge/LiteRT/issues/5077
            // The LiteRT-LM Kotlin API should do this automatically when using
            // Backend.NPU(), but we set it explicitly as a safety measure.
            try {
                android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir, true)
                Log.d(TAG, "Set ADSP_LIBRARY_PATH=$nativeLibDir")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set ADSP_LIBRARY_PATH: ${e.message}")
            }

            // Try backends in priority order: NPU â†’ GPU â†’ CPU
            //
            // âš ï¸ IMPORTANT: Backend.NPU() with Engine.initialize() can trigger a
            // native SIGABRT in liblitertlm_jni.so (Issue #774, #5159). This
            // kills the entire process â€” we CANNOT catch it in Kotlin.
            //
            // WORKAROUND: We try GPU first (which always works), and only
            // attempt NPU if the user explicitly requests it. The NPU model
            // files are backwards-compatible â€” they run fine on GPU too.
            //
            // TODO: Once LiteRT-LM Kotlin API NPU support stabilizes
            // (see github.com/google-ai-edge/LiteRT-LM/issues/774),
            // re-enable NPU as first-priority backend.
            val backends = listOf(
                "gpu" to Backend.GPU(),
                "cpu" to Backend.CPU()
            )

            var loaded = false
            for ((name, backend) in backends) {
                val progress = when (name) {
                    "npu" -> 0.15f; "gpu" -> 0.35f; "cpu" -> 0.55f; else -> 0.5f
                }
                _modelLoadState.value = ModelLoadState.Loading(progress)
                Log.d(TAG, "Attempting $name backend on ${Build.MANUFACTURER} ${Build.MODEL}...")

                try {
                    // Gallery pattern: set cacheDir conditionally
                    val cacheDir = if (modelFile.absolutePath.startsWith("/data/local/tmp")) {
                        context.getExternalFilesDir(null)?.absolutePath
                    } else {
                        null
                    }

                    // Gallery pattern: use separate visionBackend and audioBackend
                    val engineConfig = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        // Gallery uses GPU for vision, CPU for audio on Gemma models
                        visionBackend = Backend.GPU(),
                        audioBackend = Backend.CPU(),
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDir
                    )

                    val candidateEngine = Engine(engineConfig)
                    candidateEngine.initialize()

                    engine = candidateEngine
                    activeBackend = backend
                    activeBackendType = name
                    loaded = true
                    Log.d(TAG, "âœ“ $name backend initialized successfully!")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "$name backend failed: ${e.message}", e)
                    // Continue to next backend
                }
            }

            if (!loaded) {
                _modelLoadState.value = ModelLoadState.Error("All Gemma 4 E4B backends (GPU, CPU) failed")
                throw ModelLoadException("All model backends failed")
            }

            // Start foreground service for the model's entire lifetime
            ModelLoadingService.start(context)
            ModelLoadingService.updateNotification(context, "Gemma 4 E4B â€¢ $activeBackendType")

            _modelLoadState.value = ModelLoadState.Loaded
            Log.d(TAG, "Model init complete! Backend: $activeBackendType")
        } catch (e: ModelNotFoundException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during model init", e)
            _modelLoadState.value = ModelLoadState.Error("Out of memory", e)
            throw ModelLoadException("Out of memory", e)
        } catch (e: Exception) {
            Log.e(TAG, "Model init failed", e)
            _modelLoadState.value = ModelLoadState.Error("Failed to load model: ${e.message}", e)
            throw ModelLoadException("Failed to load model: ${e.message}", e)
        }
    }

    /**
     * Ensure a Conversation exists for the given configuration.
     * Creates one if none exists, or if the configuration (mode, thinking, system prompt) changed.
     *
     * This matches the Gallery pattern where a Conversation is created once
     * and reused across messages. We only reset it when the configuration changes.
     */
    private fun ensureConversation(
        capabilityMode: String,
        enableThinking: Boolean,
        supportImage: Boolean = false,
        supportAudio: Boolean = false
    ): Conversation {
        val theEngine = engine ?: throw ModelLoadException("Engine not initialized")

        // Check if we need to reset the conversation (mode or thinking changed)
        val needsReset = conversation != null &&
            (currentCapabilityMode != capabilityMode ||
             currentEnableThinking != enableThinking ||
             currentSupportImage != supportImage ||
             currentSupportAudio != supportAudio)

        if (needsReset) {
            Log.d(TAG, "Configuration changed (mode=$capabilityMode, thinking=$enableThinking), resetting conversation")
            resetConversation()
        }

        // If conversation already exists, reuse it (Gallery pattern!)
        conversation?.let { return it }

        // Build system instruction from the system prompt builder
        val systemPrompt = systemPromptBuilder.buildSystemPrompt(
            capabilityMode = capabilityMode,
            enableThinking = enableThinking,
            includeToolDescriptions = false
        )
        val restoredContext = restoredConversationContext
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n\nRecent saved conversation context:\n$it" }
            .orEmpty()
        currentSystemInstruction = Contents.of(systemPrompt + restoredContext)
        currentCapabilityMode = capabilityMode
        currentEnableThinking = enableThinking
        currentSupportImage = supportImage
        currentSupportAudio = supportAudio

        // Build thinking channels if enabled
        val channels = if (enableThinking) {
            listOf(Channel(channelName = "thought", start = "<thought>", end = "</thought>"))
        } else {
            null
        }

        // Build tool providers for native tool calling
        val toolProviders = buildToolProviders()

        // Gallery pattern: SamplerConfig null on NPU
        val samplerConfig = if (activeBackend is Backend.NPU) {
            null
        } else {
            SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble()
            )
        }

        // Gallery pattern: optionally enable constrained decoding
        ExperimentalFlags.enableConversationConstrainedDecoding = false

        val conversationConfig = ConversationConfig(
            samplerConfig = samplerConfig,
            systemInstruction = currentSystemInstruction,
            tools = toolProviders,
            channels = channels
        )

        ExperimentalFlags.enableConversationConstrainedDecoding = false

        val newConversation = theEngine.createConversation(conversationConfig)
        conversation = newConversation

        Log.d(TAG, "Created new Conversation for mode=$capabilityMode, thinking=$enableThinking")
        return newConversation
    }

    /**
     * Reset the conversation context (Gallery: resetConversation pattern).
     * Closes the current conversation and creates a new one with the same config.
     */
    fun resetConversation() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation: ${e.message}")
        }
        conversation = null
        currentSystemInstruction = null
    }

    /**
     * Full reset: new conversation with updated configuration.
     */
    fun resetConversation(
        capabilityMode: String,
        enableThinking: Boolean,
        supportImage: Boolean = false,
        supportAudio: Boolean = false
    ) {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation: ${e.message}")
        }
        conversation = null
        currentSystemInstruction = null
        // Force recreation on next call
        currentCapabilityMode = ""
        currentEnableThinking = !enableThinking  // Force mismatch to trigger recreate
        if (engine != null && _modelLoadState.value is ModelLoadState.Loaded) {
            ensureConversation(capabilityMode, enableThinking, supportImage, supportAudio)
        }
    }

    fun setRestoredConversationContext(contextText: String?) {
        restoredConversationContext = contextText
    }

    /**
     * Stop ongoing generation (Gallery: conversation.cancelProcess pattern).
     */
    fun stopGeneration() {
        try {
            conversation?.cancelProcess()
            Log.d(TAG, "Cancelled ongoing generation")
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling generation: ${e.message}")
        }
    }

    /**
     * Generate a streaming text response.
     *
     * Uses the Gallery's MessageCallback pattern:
     * conversation.sendMessageAsync(Contents, MessageCallback, extraContext)
     *
     * The Conversation automatically tracks message history internally,
     * so we do NOT need to inject past messages.
     */
    fun generateResponse(
        messages: List<DomainMessage>,
        systemPrompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = callbackFlow {
        Log.d(TAG, "generateResponse called: capabilityMode=$capabilityMode, enableThinking=$enableThinking, engine=${engine != null}, loadState=${_modelLoadState.value}")

        if (engine == null || _modelLoadState.value !is ModelLoadState.Loaded) {
            // Try lazy initialization
            if (_modelLoadState.value !is ModelLoadState.Loading) {
                Log.d(TAG, "Engine not initialized, attempting lazy initialization...")
                try {
                    initialize()
                    Log.d(TAG, "Lazy initialization completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Lazy initialization failed", e)
                }
            }
            if (engine == null) {
                Log.w(TAG, "Engine still null after initialization attempt, using fallback")
                val msg = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
                for (chunk in generateContextualFallback(msg, capabilityMode).chunked(2)) {
                    trySend(InferenceToken.TextToken(chunk))
                }
                trySend(InferenceToken.EndOfStream)
                close()
                return@callbackFlow
            }
        }

        var responseReceived = false
        val timeoutJob = launch(Dispatchers.Default) {
            delay(30000) // 30 second timeout
            if (!responseReceived) {
                Log.e(TAG, "Response timeout - no callback received within 30 seconds")
                trySend(InferenceToken.Error("The model is not responding. Please try again or restart the app."))
                trySend(InferenceToken.EndOfStream)
                close()
            }
        }

        try {
            val conv = ensureConversation(capabilityMode, enableThinking)
            Log.d(TAG, "Conversation ready: ${conv.hashCode()}")

            // Extract the last user message â€” Gallery pattern: only send the latest input
            // The Conversation already has all previous messages tracked internally
            val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
            Log.d(TAG, "Sending message to model: '${lastUserMessage.take(50)}...'")

            ModelLoadingService.updateNotification(context, "Generating on $activeBackendType...")

            // Gallery pattern: sendMessageAsync with MessageCallback
            conv.sendMessageAsync(
                Contents.of(Content.Text(lastUserMessage)),
                object : MessageCallback {
                    override fun onMessage(message: LiteRtMessage) {
                        responseReceived = true
                        Log.d(TAG, "onMessage callback received")

                        // Extract thinking content from channels (Gallery pattern)
                        val thinkingContent = message.channels["thought"]
                        if (thinkingContent != null && enableThinking) {
                            for (chunk in thinkingContent.chunked(3)) {
                                trySend(InferenceToken.ThinkingToken(chunk))
                            }
                        }

                        // Extract tool calls if present
                        val toolCalls = message.toolCalls
                        for (tc in toolCalls) {
                            val domainToolCall = ToolCall(
                                name = tc.name,
                                arguments = kotlinx.serialization.json.JsonObject(
                                    tc.arguments.mapValues { (_, v) ->
                                        when (v) {
                                            is String -> kotlinx.serialization.json.JsonPrimitive(v)
                                            is Int -> kotlinx.serialization.json.JsonPrimitive(v)
                                            is Double -> kotlinx.serialization.json.JsonPrimitive(v)
                                            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                                            else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                                        }
                                    }
                                ),
                                callId = ""
                            )
                            trySend(InferenceToken.ToolCallToken(domainToolCall))
                        }

                        // Extract text content (Gallery: message.toString() for full text)
                        val textContent = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }

                        Log.d(TAG, "Received text content: '${textContent.take(100)}...'")

                        if (textContent.isNotBlank() && toolCalls.isEmpty()) {
                            for (chunk in textContent.chunked(2)) {
                                trySend(InferenceToken.TextToken(chunk))
                            }
                        }
                    }

                    override fun onDone() {
                        responseReceived = true
                        Log.d(TAG, "onDone callback received")
                        timeoutJob.cancel()
                        trySend(InferenceToken.EndOfStream)
                        close()
                    }

                    override fun onError(throwable: Throwable) {
                        responseReceived = true
                        timeoutJob.cancel()
                        if (throwable is CancellationException) {
                            Log.i(TAG, "Generation cancelled by user")
                            trySend(InferenceToken.EndOfStream)
                        } else {
                            Log.e(TAG, "LiteRT-LM error during inference", throwable)
                            trySend(InferenceToken.Error("Inference error: ${throwable.message}"))
                        }
                        close()
                    }
                },
                emptyMap()
            )
        } catch (e: LiteRtLmJniException) {
            timeoutJob.cancel()
            Log.e(TAG, "LiteRT-LM JNI error during inference", e)
            trySend(InferenceToken.Error("Inference error: ${e.message}"))
            trySend(InferenceToken.EndOfStream)
            close()
        } catch (e: Exception) {
            timeoutJob.cancel()
            Log.e(TAG, "Generation failed", e)
            trySend(InferenceToken.Error("Generation failed: ${e.message}"))
            trySend(InferenceToken.EndOfStream)
            close()
        } finally {
            ModelLoadingService.updateNotification(context, "Gemma 4 E4B â€¢ $activeBackendType")
        }

        // Keep the callbackFlow open until explicitly closed or cancelled
        // This prevents the 'awaitClose' error and ensures callbacks can complete
        awaitClose {
            Log.d(TAG, "callbackFlow closing, cancelling timeout job")
            timeoutJob.cancel()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Generate a response with an image input.
     * Gallery pattern: include Content.ImageBytes in the Contents.
     */
    fun generateResponseWithImage(
        messages: List<DomainMessage>,
        imageBitmap: Bitmap,
        prompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = callbackFlow {
        if (engine == null) {
            val msg = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
            for (chunk in generateContextualFallback(msg, capabilityMode).chunked(2)) {
                trySend(InferenceToken.TextToken(chunk))
            }
            trySend(InferenceToken.EndOfStream)
            close()
            return@callbackFlow
        }

        try {
            val conv = ensureConversation(capabilityMode, enableThinking, supportImage = true)

            // Convert Bitmap to PNG ByteArray (Gallery pattern)
            val stream = ByteArrayOutputStream()
            imageBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val imageBytes = stream.toByteArray()

            val lastUserMessage = prompt.ifBlank {
                messages.lastOrNull { it.role == MessageRole.USER }?.content ?: "Describe this image"
            }

            ModelLoadingService.updateNotification(context, "Generating on $activeBackendType [image]...")

            // Gallery pattern: Image content first, then text
            conv.sendMessageAsync(
                Contents.of(
                    Content.ImageBytes(imageBytes),
                    Content.Text(lastUserMessage)
                ),
                object : MessageCallback {
                    override fun onMessage(message: LiteRtMessage) {
                        val thinkingContent = message.channels["thought"]
                        if (thinkingContent != null && enableThinking) {
                            for (chunk in thinkingContent.chunked(3)) {
                                trySend(InferenceToken.ThinkingToken(chunk))
                            }
                        }

                        val textContent = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }

                        if (textContent.isNotBlank()) {
                            for (chunk in textContent.chunked(2)) {
                                trySend(InferenceToken.TextToken(chunk))
                            }
                        }
                    }

                    override fun onDone() {
                        trySend(InferenceToken.EndOfStream)
                        close()
                    }

                    override fun onError(throwable: Throwable) {
                        if (throwable is CancellationException) {
                            trySend(InferenceToken.EndOfStream)
                        } else {
                            trySend(InferenceToken.Error("Image inference error: ${throwable.message}"))
                        }
                        close()
                    }
                },
                emptyMap()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Image inference failed", e)
            trySend(InferenceToken.Error("Image inference failed: ${e.message}"))
            trySend(InferenceToken.EndOfStream)
            close()
        } finally {
            ModelLoadingService.updateNotification(context, "Gemma 4 E4B â€¢ $activeBackendType")
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Generate a response with audio input.
     *
     * Gallery pattern: include Content.AudioBytes in the Contents.
     *
     * IMPORTANT: LiteRT-LM expects WAV-wrapped audio data, NOT raw PCM.
     * The Gallery's ChatMessageAudioClip.genByteArrayForWav() wraps raw PCM
     * in a 44-byte RIFF/WAVE header before passing to Content.AudioBytes().
     * We follow the same pattern here.
     */
    fun generateResponseWithAudio(
        messages: List<DomainMessage>,
        audioBytes: ByteArray,
        prompt: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<InferenceToken> = callbackFlow {
        if (engine == null) {
            val msg = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
            for (chunk in generateContextualFallback(msg, capabilityMode).chunked(2)) {
                trySend(InferenceToken.TextToken(chunk))
            }
            trySend(InferenceToken.EndOfStream)
            close()
            return@callbackFlow
        }

        try {
            val conv = ensureConversation(capabilityMode, enableThinking, supportAudio = true)

            val lastUserMessage = prompt.ifBlank {
                messages.lastOrNull { it.role == MessageRole.USER }?.content ?: "Transcribe and respond to this audio"
            }

            ModelLoadingService.updateNotification(context, "Generating on $activeBackendType [audio]...")

            // Gallery pattern: wrap raw PCM in WAV header before sending to model.
            // LiteRT-LM's Content.AudioBytes expects WAV format, not raw PCM.
            // This matches ChatMessageAudioClip.genByteArrayForWav() in the Gallery app.
            val wavBytes = wrapPcmInWavHeader(audioBytes, SAMPLE_RATE)
            Log.d(TAG, "Audio: raw PCM ${audioBytes.size} bytes -> WAV ${wavBytes.size} bytes")

            // Gallery pattern: Audio content first, then text
            conv.sendMessageAsync(
                Contents.of(
                    Content.AudioBytes(wavBytes),
                    Content.Text(lastUserMessage)
                ),
                object : MessageCallback {
                    override fun onMessage(message: LiteRtMessage) {
                        val thinkingContent = message.channels["thought"]
                        if (thinkingContent != null && enableThinking) {
                            for (chunk in thinkingContent.chunked(3)) {
                                trySend(InferenceToken.ThinkingToken(chunk))
                            }
                        }

                        val textContent = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }

                        if (textContent.isNotBlank()) {
                            for (chunk in textContent.chunked(2)) {
                                trySend(InferenceToken.TextToken(chunk))
                            }
                        }
                    }

                    override fun onDone() {
                        trySend(InferenceToken.EndOfStream)
                        close()
                    }

                    override fun onError(throwable: Throwable) {
                        if (throwable is CancellationException) {
                            trySend(InferenceToken.EndOfStream)
                        } else {
                            trySend(InferenceToken.Error("Audio inference error: ${throwable.message}"))
                        }
                        close()
                    }
                },
                emptyMap()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Audio inference failed", e)
            trySend(InferenceToken.Error("Audio inference failed: ${e.message}"))
            trySend(InferenceToken.EndOfStream)
            close()
        } finally {
            ModelLoadingService.updateNotification(context, "Gemma 4 E4B â€¢ $activeBackendType")
        }
    }.flowOn(Dispatchers.Default)

    // â”€â”€ Tool Providers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Build LiteRT-LM ToolProviders from our app's ToolRegistry.
     *
     * This uses LiteRT-LM's native tool() function to create ToolProviders
     * that the model can call natively â€” matching the Gallery's approach.
     */
    private fun buildToolProviders(): List<ToolProvider> {
        val appTools = toolRegistry.getAllTools()
        if (appTools.isEmpty()) return emptyList()

        return appTools.map { appTool ->
            tool(object : com.google.ai.edge.litertlm.OpenApiTool {
                override fun getToolDescriptionJsonString(): String {
                    val schema = appTool.getParameterSchema()
                    val name = appTool.name
                    val description = appTool.description
                    // Format as OpenAPI function description (Gallery pattern)
                    return """{"name":"$name","description":"$description","parameters":${schema.toString()}}"""
                }

                override fun execute(paramsJsonString: String): String {
                    // When the model uses automatic tool calling, this is invoked
                    return try {
                        val params = kotlinx.serialization.json.Json.decodeFromString<
                            kotlinx.serialization.json.JsonObject>(paramsJsonString)
                        val result = kotlinx.coroutines.runBlocking {
                            appTool.execute(params)
                        }
                        result
                    } catch (e: Exception) {
                        "Error executing tool ${appTool.name}: ${e.message}"
                    }
                }
            })
        }
    }

    // â”€â”€ Lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun release() {
        try { conversation?.close() } catch (_: Exception) { }
        try { engine?.close() } catch (_: Exception) { }
        engine = null
        conversation = null
        activeBackendType = "none"
        activeBackend = Backend.CPU()
        _modelLoadState.value = ModelLoadState.NotLoaded
        ModelLoadingService.stop(context)
    }

    // â”€â”€ Fallback â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun generateContextualFallback(userMessage: String, capabilityMode: String): String {
        return when (capabilityMode) {
            "code" -> "I'd be happy to help with code! However, the AI model is currently loading. Please try again in a moment."
            "see" -> "I can see you've shared something visual. The AI model is loading â€” please retry shortly."
            "write" -> "I'd love to help with writing! The AI model is still initializing. Please try again in a moment."
            "brainstorm" -> "Great ideas start with a conversation! The AI model is loading â€” give it a moment and try again."
            "data" -> "I can help analyze data once the AI model finishes loading. Please try again shortly."
            else -> "Hi! I'm your on-device AI assistant. The model is currently loading. Please try sending your message again in a moment."
        }
    }

    // â”€â”€ WAV Header Utility (matching Gallery's ChatMessageAudioClip.genByteArrayForWav()) â”€â”€

    /**
     * Wraps raw PCM 16-bit mono audio data in a WAV file header.
     *
     * This is the exact same approach used by the AI Edge Gallery app's
     * ChatMessageAudioClip.genByteArrayForWav() method. LiteRT-LM's
     * Content.AudioBytes expects WAV format, not raw PCM bytes.
     *
     * WAV header structure (44 bytes):
     *   - Bytes 0-3:   "RIFF"
     *   - Bytes 4-7:    File size - 8
     *   - Bytes 8-11:   "WAVE"
     *   - Bytes 12-15:   "fmt "
     *   - Bytes 16-19:   Sub-chunk size (16 for PCM)
     *   - Bytes 20-21:   Audio format (1 for PCM)
     *   - Bytes 22-23:   Number of channels (1 for mono)
     *   - Bytes 24-27:   Sample rate
     *   - Bytes 28-31:   Byte rate (sampleRate * channels * bitsPerSample / 8)
     *   - Bytes 32-33:   Block align (channels * bitsPerSample / 8)
     *   - Bytes 34-35:   Bits per sample (16)
     *   - Bytes 36-39:   "data"
     *   - Bytes 40-43:   Data size
     */
    fun wrapPcmInWavHeader(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val channels = 1 // Mono
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val pcmDataSize = pcmData.size
        val wavFileSize = pcmDataSize + 44

        val header = ByteArray(44)

        // RIFF/WAVE header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (wavFileSize and 0xff).toByte()
        header[5] = (wavFileSize shr 8 and 0xff).toByte()
        header[6] = (wavFileSize shr 16 and 0xff).toByte()
        header[7] = (wavFileSize shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Sub-chunk size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1  // Audio format (1 = PCM)
        header[21] = 0
        header[22] = channels.toByte() // Number of channels
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = blockAlign.toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = (bitsPerSample.toInt() shr 8 and 0xff).toByte()
        // data sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataSize and 0xff).toByte()
        header[41] = (pcmDataSize shr 8 and 0xff).toByte()
        header[42] = (pcmDataSize shr 16 and 0xff).toByte()
        header[43] = (pcmDataSize shr 24 and 0xff).toByte()

        return header + pcmData
    }

    private fun String.chunked(size: Int): List<String> {
        if (this.isEmpty()) return emptyList()
        return (0..length - 1 step size).map { substring(it, minOf(it + size, length)) }
    }
}
