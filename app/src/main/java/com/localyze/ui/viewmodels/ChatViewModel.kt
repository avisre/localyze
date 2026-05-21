package com.localyze.ui.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localyze.ai.AudioRecordingState
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.ChatRepository
import com.localyze.data.repository.PerformanceMonitor
import com.localyze.domain.usecases.ChatResponseEvent
import com.localyze.domain.usecases.ManageMemoryUseCase
import com.localyze.domain.usecases.RecordAudioUseCase
import com.localyze.domain.usecases.SendMessageUseCase
import com.localyze.tools.DispatchResult
import com.localyze.tools.ToolDispatcher
import com.localyze.util.CrashRecoveryStore
import com.localyze.utils.InputValidator
import com.localyze.utils.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val recordAudioUseCase: RecordAudioUseCase,
    private val manageMemoryUseCase: ManageMemoryUseCase,
    private val chatRepository: ChatRepository,
    private val settingsDataStore: SettingsDataStore,
    private val toolDispatcher: ToolDispatcher,
    private val performanceMonitor: PerformanceMonitor,
    private val crashRecoveryStore: CrashRecoveryStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val CONVERSATION_ID_KEY = "conversationId"
        // Hoisted out of the per-token hot path. Previously each
        // StreamingToken rebuilt these Regex instances and applied them
        // to the entire accumulated text — O(n²) main-thread cost.
        private val DIGIT_LETTER = Regex("(\\d)([a-zA-Z])")
        private val LETTER_DIGIT = Regex("([a-zA-Z])(\\d)")
        // 180s on real devices is plenty, but emulator CPU prefill on a
        // 5000+ token system prompt + web context easily exceeds that.
        // Bump to 1200s on emulator so the ViewModel doesn't kill long
        // inferences before they finish.
        val GENERATION_TIMEOUT_MS: Long = try {
            val fields = listOf(
                android.os.Build.PRODUCT.orEmpty(),
                android.os.Build.HARDWARE.orEmpty(),
                android.os.Build.MODEL.orEmpty()
            )
            val isEmulator = fields.any { v ->
                v.contains("sdk_gphone", ignoreCase = true) ||
                v.contains("ranchu", ignoreCase = true) ||
                v.contains("goldfish", ignoreCase = true) ||
                v.contains("generic", ignoreCase = true)
            }
            if (isEmulator) 1_200_000L else 180_000L
        } catch (t: Throwable) {
            180_000L
        }
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var generationJob: Job? = null
    private var loadConversationJob: Job? = null
    private var messagesJob: Job? = null
    private var didHandleInitialConversation = false
    val recordingState: StateFlow<AudioRecordingState> = recordAudioUseCase.getRecordingState()
    val amplitudeFlow = recordAudioUseCase.getAmplitudeFlow()
    private val _expandedThinking = MutableStateFlow(setOf<Int>())
    val expandedThinking: StateFlow<Set<Int>> = _expandedThinking.asStateFlow()

    init {
        observeSettings()
        observeCrashRecovery()
        val cid = readInitialConversationId(savedStateHandle)
        when {
            cid == 0L -> {
                didHandleInitialConversation = true
                createNewConversation()
            }
            cid != null && cid > 0L -> {
                didHandleInitialConversation = true
                loadConversation(cid)
            }
        }
        observeConversations()
    }

    private fun observeCrashRecovery() {
        viewModelScope.launch {
            crashRecoveryStore.lastCrashedPrompt.collect { prompt ->
                _uiState.update { it.copy(lastCrashedPrompt = prompt) }
            }
        }
    }

    fun acknowledgeCrashRecovery() {
        crashRecoveryStore.acknowledgeCrash()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsDataStore.thinkingMode.collect { enabled ->
                // Thinking channels are always-on in the engine after the
                // refactor — the UI just hides thought tokens when this
                // toggle is off. So we update local state without
                // resetting the conversation (used to invalidate KV cache
                // on every toggle, also raced with releaseForBackground
                // and crashed the app).
                _uiState.update { it.copy(enableThinking = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.streamTokens.collect { enabled ->
                _uiState.update { it.copy(streamTokens = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.voiceAutoPlay.collect { enabled ->
                _uiState.update { it.copy(voiceAutoPlay = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.allowWebSearch.collect { enabled ->
                val previous = _uiState.value.allowWebSearch
                _uiState.update { it.copy(allowWebSearch = enabled) }
                if (previous != enabled && !_uiState.value.isStreaming) {
                    resetEngineForCurrentConversation(
                        _uiState.value.capabilityMode,
                        _uiState.value.enableThinking
                    )
                }
            }
        }
    }

    fun loadConversation(id: Long) {
        loadConversationJob?.cancel()
        messagesJob?.cancel()
        loadConversationJob = viewModelScope.launch {
            val conversation = chatRepository.getConversation(id)
            if (conversation == null) {
                // Silently fall back to the most recent conversation instead
                // of showing a "Conversation not found" snackbar — the user
                // just tapped a stale link, no error worth surfacing.
                loadMostRecentConversation()
                return@launch
            }

            sendMessageUseCase.resetEngineConversationWithSavedContext(
                conversation.id,
                conversation.capabilityMode,
                _uiState.value.enableThinking
            )
            _uiState.update {
                it.copy(
                    currentConversationId = conversation.id,
                    currentConversationTitle = conversation.title,
                    capabilityMode = conversation.capabilityMode,
                    streamingText = "",
                    thinkingText = "",
                    generationStatus = "",
                    activeToolCalls = emptyList(),
                    isStreaming = false,
                    isThinking = false,
                    error = null
                )
            }

            observeMessagesForConversation(id)
        }
    }

    fun createNewConversation() {
        viewModelScope.launch {
            try {
                val conv = chatRepository.createConversation(capabilityMode = _uiState.value.capabilityMode)
                sendMessageUseCase.resetEngineConversation(conv.capabilityMode, _uiState.value.enableThinking)
                _uiState.update { s -> s.copy(currentConversationId = conv.id, currentConversationTitle = conv.title, messages = emptyList(), streamingText = "", thinkingText = "", generationStatus = "", activeToolCalls = emptyList(), isStreaming = false, isThinking = false, showMascot = true, isUsingThreadContext = false, error = null) }
                loadConversation(conv.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to create conversation: " + e.message) }
            }
        }
    }

    fun sendMessage(text: String) {
        com.localyze.utils.AppLog.d("ChatViewModel", "sendMessage called with: '$text'")

        // Validate input before processing
        val trimmedText = text.trim()
        val validationResult = InputValidator.validateMessage(trimmedText)
        if (validationResult is ValidationResult.Error) {
            _uiState.update { it.copy(error = validationResult.message) }
            return
        }

        val state = _uiState.value
        com.localyze.utils.AppLog.d("ChatViewModel", "isStreaming=${state.isStreaming}, currentConversationId=${state.currentConversationId}")
        if (state.isStreaming || trimmedText.isBlank()) return
        val conversationId = state.currentConversationId
        if (conversationId <= 0L) {
            viewModelScope.launch {
                try {
                    val conv = chatRepository.createConversation(capabilityMode = state.capabilityMode)
                    _uiState.update { it.copy(currentConversationId = conv.id) }
                    loadConversation(conv.id)
                    doSendMessage(conv.id, trimmedText)
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "Failed to create conversation: " + e.message) }
                }
            }
            return
        }
        doSendMessage(conversationId, trimmedText)
    }

    fun sendMessageInNewConversation(text: String, capabilityModeOverride: String? = null) {
        val trimmedText = text.trim()
        com.localyze.utils.AppLog.d("ChatViewModel", "sendMessageInNewConversation called with: '$trimmedText'")

        val validationResult = InputValidator.validateMessage(trimmedText)
        if (validationResult is ValidationResult.Error) {
            _uiState.update { it.copy(error = validationResult.message) }
            return
        }

        if (_uiState.value.isStreaming || trimmedText.isBlank()) return

        viewModelScope.launch {
            try {
                val capabilityMode = capabilityModeOverride ?: _uiState.value.capabilityMode
                val enableThinking = _uiState.value.enableThinking
                val conv = chatRepository.createConversation(capabilityMode = capabilityMode)
                sendMessageUseCase.resetEngineConversation(conv.capabilityMode, enableThinking)

                loadConversationJob?.cancel()
                observeMessagesForConversation(conv.id)
                _uiState.update { s ->
                    s.copy(
                        currentConversationId = conv.id,
                        currentConversationTitle = conv.title,
                        capabilityMode = conv.capabilityMode,
                        messages = emptyList(),
                        streamingText = "",
                        thinkingText = "",
                        generationStatus = "",
                        activeToolCalls = emptyList(),
                        isStreaming = false,
                        isThinking = false,
                        showMascot = true,
                        error = null
                    )
                }

                doSendMessage(conv.id, trimmedText)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to create conversation: " + e.message) }
            }
        }
    }

    private fun doSendMessage(conversationId: Long, text: String) {
        com.localyze.utils.AppLog.d("ChatViewModel", "doSendMessage: convId=$conversationId, text='$text'")
        performanceMonitor.startResponse()
        _uiState.update { s -> s.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", generationStatus = "Reading your message", activeToolCalls = emptyList(), showMascot = false, error = null) }
        generationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(GENERATION_TIMEOUT_MS) {
                    sendMessageUseCase.sendMessage(conversationId = conversationId, userMessage = text, capabilityMode = _uiState.value.capabilityMode, enableThinking = _uiState.value.enableThinking)
                        .catch { e ->
                            if (e is CancellationException) throw e
                            android.util.Log.e("ChatViewModel", "Error in sendMessage flow", e)
                            _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "Generation error: " + e.message) }
                        }
                        .collect { event ->
                            com.localyze.utils.AppLog.d("ChatViewModel", "Received event: ${event.javaClass.simpleName}")
                            handleResponseEvent(event)
                        }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("ChatViewModel", "Message generation timed out", e)
                performanceMonitor.recordError("Generation timed out", timeout = true)
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "The model took too long to respond. Please try again.") }
            } catch (e: CancellationException) {
                com.localyze.utils.AppLog.d("ChatViewModel", "Message generation cancelled")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", activeToolCalls = emptyList()) }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Unexpected error during message generation", e)
                performanceMonitor.recordError(e.message ?: "Generation error")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "Error: ${e.message}") }
            }
        }
    }

    private fun doContinueWithToolResult(conversationId: Long, toolResult: com.localyze.domain.models.ToolResult) {
        com.localyze.utils.AppLog.d("ChatViewModel", "doContinueWithToolResult: convId=$conversationId, tool=${toolResult.name}")
        performanceMonitor.startResponse()
        _uiState.update { s -> s.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", generationStatus = "Using tool result", activeToolCalls = emptyList(), error = null) }
        generationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(GENERATION_TIMEOUT_MS) {
                    sendMessageUseCase.continueWithToolResult(
                        conversationId = conversationId,
                        toolResult = toolResult,
                        capabilityMode = _uiState.value.capabilityMode,
                        enableThinking = _uiState.value.enableThinking
                    )
                        .catch { e ->
                            if (e is CancellationException) throw e
                            android.util.Log.e("ChatViewModel", "Error in continueWithToolResult flow", e)
                            _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "Generation error: " + e.message) }
                        }
                        .collect { event ->
                            com.localyze.utils.AppLog.d("ChatViewModel", "Received event: ${event.javaClass.simpleName}")
                            handleResponseEvent(event)
                        }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("ChatViewModel", "Tool result generation timed out", e)
                performanceMonitor.recordError("Tool result generation timed out", timeout = true)
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "The model took too long to respond. Please try again.") }
            } catch (e: CancellationException) {
                com.localyze.utils.AppLog.d("ChatViewModel", "Tool result generation cancelled")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", activeToolCalls = emptyList()) }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Unexpected error during tool result generation", e)
                performanceMonitor.recordError(e.message ?: "Generation error")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "Error: ${e.message}") }
            }
        }
    }

    fun sendImageMessage(text: String, imageBitmap: Bitmap) {
        val state = _uiState.value
        if (state.isStreaming) return

        // Validate input text (image messages can have optional text)
        if (text.isNotBlank()) {
            val validationResult = InputValidator.validateMessage(text)
            if (validationResult is ValidationResult.Error) {
                _uiState.update { it.copy(error = validationResult.message) }
                return
            }
        }

        val conversationId = state.currentConversationId
        if (conversationId <= 0L) {
            viewModelScope.launch {
                try {
                    val conv = chatRepository.createConversation(capabilityMode = state.capabilityMode)
                    _uiState.update { it.copy(currentConversationId = conv.id) }
                    loadConversation(conv.id)
                    doSendImageMessage(conv.id, text, imageBitmap)
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "Failed to create conversation: " + e.message) }
                }
            }
            return
        }
        doSendImageMessage(conversationId, text, imageBitmap)
    }

    private fun doSendImageMessage(id: Long, text: String, bitmap: Bitmap) {
        performanceMonitor.startResponse()
        _uiState.update { s -> s.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", generationStatus = "Reading the image", activeToolCalls = emptyList(), showMascot = false, error = null) }
        generationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(GENERATION_TIMEOUT_MS) {
                    sendMessageUseCase.sendMessageWithImage(
                        conversationId = id,
                        userMessage = text,
                        imageBitmap = bitmap,
                        capabilityMode = _uiState.value.capabilityMode,
                        enableThinking = _uiState.value.enableThinking
                    )
                        .catch { e ->
                            if (e is CancellationException) throw e
                            android.util.Log.e("ChatViewModel", "Error in image message flow", e)
                            performanceMonitor.recordError(e.message ?: "Image generation error")
                            _uiState.update { s ->
                                s.copy(
                                    isStreaming = false,
                                    isThinking = false,
                                    generationStatus = "",
                                    error = "Image generation error: " + e.message
                                )
                            }
                        }
                        .collect { event ->
                            com.localyze.utils.AppLog.d("ChatViewModel", "Received image event: ${event.javaClass.simpleName}")
                            handleResponseEvent(event)
                        }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("ChatViewModel", "Image generation timed out", e)
                performanceMonitor.recordError("Image generation timed out", timeout = true)
                _uiState.update { s ->
                    s.copy(
                        isStreaming = false,
                        isThinking = false,
                        generationStatus = "",
                        error = "The image analysis took too long. Please try again."
                    )
                }
            } catch (e: CancellationException) {
                com.localyze.utils.AppLog.d("ChatViewModel", "Image generation cancelled")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", activeToolCalls = emptyList()) }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Unexpected error during image generation", e)
                performanceMonitor.recordError(e.message ?: "Image generation error")
                _uiState.update { s ->
                    s.copy(
                        isStreaming = false,
                        isThinking = false,
                        generationStatus = "",
                        error = "Image generation error: ${e.message}"
                    )
                }
            }
        }
    }

    fun sendAudioMessage(audioBytes: ByteArray) {
        com.localyze.utils.AppLog.d("ChatViewModel", "sendAudioMessage called with ${audioBytes.size} bytes")
        val state = _uiState.value
        if (state.isStreaming) {
            android.util.Log.w("ChatViewModel", "Cannot send audio: already streaming")
            return
        }
        val conversationId = state.currentConversationId
        if (conversationId <= 0L) {
            viewModelScope.launch {
                try {
                    val conv = chatRepository.createConversation(capabilityMode = state.capabilityMode)
                    _uiState.update { it.copy(currentConversationId = conv.id) }
                    loadConversation(conv.id)
                    doSendAudioMessage(conv.id, audioBytes)
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "Failed to create conversation: " + e.message) }
                }
            }
            return
        }
        doSendAudioMessage(conversationId, audioBytes)
    }

    private fun doSendAudioMessage(id: Long, audioBytes: ByteArray) {
        performanceMonitor.startResponse()
        _uiState.update { s -> s.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", generationStatus = "Listening to the audio", activeToolCalls = emptyList(), showMascot = false, error = null) }
        generationJob = viewModelScope.launch {
            try {
                sendMessageUseCase.sendMessageWithAudio(conversationId = id, audioBytes = audioBytes, capabilityMode = _uiState.value.capabilityMode, enableThinking = _uiState.value.enableThinking)
                    .catch { e ->
                        if (e is CancellationException) throw e
                        _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "Audio generation error: " + e.message) }
                    }
                    .collect { event -> handleResponseEvent(event) }
            } catch (e: CancellationException) {
                com.localyze.utils.AppLog.d("ChatViewModel", "Audio generation cancelled")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", activeToolCalls = emptyList()) }
            }
        }
    }

    private fun handleResponseEvent(event: ChatResponseEvent) {
        com.localyze.utils.AppLog.d("ChatViewModel", "handleResponseEvent: ${event.javaClass.simpleName}")
        when (event) {
            is ChatResponseEvent.StreamingToken -> {
                com.localyze.utils.AppLog.v("ChatViewModel", "StreamingToken: '${event.text.take(200)}'")
                performanceMonitor.addToken(event.text)
                _uiState.update { s ->
                    if (s.streamTokens) {
                        // Apply digit/letter spacing only to the new delta,
                        // not to the entire accumulated text. The model
                        // emits these one token at a time so re-running the
                        // fixup on the full string every token is O(n²).
                        val fixedDelta = event.text
                            .replace(DIGIT_LETTER, "$1 $2")
                            .replace(LETTER_DIGIT, "$1 $2")
                        s.copy(streamingText = s.streamingText + fixedDelta, isStreaming = true, isThinking = false, generationStatus = "Writing answer")
                    } else {
                        s.copy(isStreaming = true, isThinking = false, generationStatus = "Writing answer")
                    }
                }
            }
            is ChatResponseEvent.ThinkingToken -> {
                com.localyze.utils.AppLog.v("ChatViewModel", "ThinkingToken: '${event.text.take(200)}'")
                _uiState.update { s ->
                    if (s.streamTokens) {
                        s.copy(thinkingText = s.thinkingText + event.text, isThinking = true, isStreaming = true, generationStatus = "Thinking through the request")
                    } else {
                        s.copy(isThinking = true, isStreaming = true, generationStatus = "Thinking through the request")
                    }
                }
            }
            is ChatResponseEvent.ToolCallStarted -> {
                com.localyze.utils.AppLog.d("ChatViewModel", "ToolCallStarted: ${event.toolName}")
                _uiState.update { s -> s.copy(generationStatus = toolStatus(event.toolName, executing = true), activeToolCalls = s.activeToolCalls + ActiveToolCall(toolName = event.toolName, isExecuting = true)) }
            }
            is ChatResponseEvent.ToolCallCompleted -> {
                com.localyze.utils.AppLog.d("ChatViewModel", "ToolCallCompleted: ${event.toolName}")
                val updated = _uiState.value.activeToolCalls.map { if (it.toolName == event.toolName && it.isExecuting) it.copy(isExecuting = false, result = event.result) else it }
                _uiState.update { s -> s.copy(generationStatus = toolStatus(event.toolName, executing = false), activeToolCalls = updated) }
            }
            is ChatResponseEvent.Completed -> {
                com.localyze.utils.AppLog.d("ChatViewModel", "Completed")
                performanceMonitor.completeResponse()
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, streamingText = "", thinkingText = "", generationStatus = "", activeToolCalls = emptyList(), showMascot = false) }
            }
            is ChatResponseEvent.ContextReset -> {
                com.localyze.utils.AppLog.d("ChatViewModel", "ContextReset: ${event.message}")
                _uiState.update { s -> s.copy(contextResetNotice = event.message) }
            }
            is ChatResponseEvent.ToolConfirmationNeeded -> {
                com.localyze.utils.AppLog.d("ChatViewModel", "ToolConfirmationNeeded: ${event.toolName}")
                _uiState.update { s ->
                    s.copy(
                        pendingToolConfirmation = DispatchResult.PendingConfirmation(
                            tool = toolDispatcher.getTool(event.toolCall.name) ?: return@update s,
                            toolCall = event.toolCall,
                            message = event.message
                        )
                    )
                }
            }
            is ChatResponseEvent.Error -> {
                android.util.Log.e("ChatViewModel", "Error event: ${event.message}")
                performanceMonitor.recordError(event.message)
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = event.message) }
            }
        }
    }

    fun clearContextResetNotice() {
        _uiState.update { it.copy(contextResetNotice = null) }
    }

    fun startAudioRecording() {
        viewModelScope.launch {
            val result = recordAudioUseCase.startRecording()
            if (result.isFailure) _uiState.update { s -> s.copy(error = "Recording failed: " + result.exceptionOrNull()?.message) }
        }
    }

    fun stopAudioRecording() {
        com.localyze.utils.AppLog.d("ChatViewModel", "stopAudioRecording called")
        viewModelScope.launch {
            val result = recordAudioUseCase.stopRecording()
            com.localyze.utils.AppLog.d("ChatViewModel", "voice transcription result: isSuccess=${result.isSuccess}, transcript='${result.getOrNull().orEmpty().take(80)}'")
            if (result.isSuccess) {
                val transcript = result.getOrNull().orEmpty().trim()
                if (transcript.isBlank()) {
                    _uiState.update { it.copy(error = "No speech was recognized") }
                    return@launch
                }
                com.localyze.utils.AppLog.d("ChatViewModel", "Sending transcribed voice prompt")
                sendMessage(transcript)
            } else {
                _uiState.update { s -> s.copy(error = "Voice input error: " + result.exceptionOrNull()?.message) }
            }
        }
    }

    fun cancelAudioRecording() { recordAudioUseCase.cancelRecording() }
    fun toggleThinkingMode() {
        val prevThinking = _uiState.value.enableThinking
        _uiState.update { it.copy(enableThinking = !it.enableThinking) }
        // When thinking mode changes, reset the LiteRT-LM Conversation
        // so it picks up the new channel configuration (Gallery pattern)
        if (prevThinking != _uiState.value.enableThinking) {
            viewModelScope.launch {
                resetEngineForCurrentConversation(_uiState.value.capabilityMode, _uiState.value.enableThinking)
            }
        }
    }
    fun setCapabilityMode(mode: String) {
        val prevState = _uiState.value.capabilityMode
        _uiState.update { it.copy(capabilityMode = mode) }
        // When capability mode changes, reset the LiteRT-LM Conversation
        // so it picks up the new system prompt (Gallery pattern)
        if (prevState != mode) {
            viewModelScope.launch {
                resetEngineForCurrentConversation(mode, _uiState.value.enableThinking)
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        sendMessageUseCase.stopGeneration()
        _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "") }
    }

    fun regenerateResponse() {
        val state = _uiState.value
        if (state.isStreaming) return
        val convId = state.currentConversationId
        if (convId <= 0L) return
        _uiState.update { it.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", generationStatus = "Regenerating response", activeToolCalls = emptyList(), error = null) }
        generationJob = viewModelScope.launch {
            try {
                sendMessageUseCase.regenerateLastResponse(conversationId = convId, capabilityMode = state.capabilityMode, enableThinking = state.enableThinking)
                    .catch { e ->
                        if (e is CancellationException) throw e
                        _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", error = "Regeneration error: " + e.message) }
                    }
                    .collect { event -> handleResponseEvent(event) }
            } catch (e: CancellationException) {
                com.localyze.utils.AppLog.d("ChatViewModel", "Regeneration cancelled")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, generationStatus = "", activeToolCalls = emptyList()) }
            }
        }
    }

    fun toggleThinkingExpanded(index: Int) {
        _expandedThinking.update { c -> if (c.contains(index)) c - index else c + index }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    fun enableWebSearch() {
        viewModelScope.launch { settingsDataStore.setAllowWebSearch(true) }
    }

    fun updateMessageContent(messageId: Long, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            try {
                val message = chatRepository.getMessage(messageId) ?: return@launch
                chatRepository.updateMessage(message.copy(content = content.trim()))
                if (message.conversationId == _uiState.value.currentConversationId) {
                    resetEngineForCurrentConversation(
                        _uiState.value.capabilityMode,
                        _uiState.value.enableThinking
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to update message: " + e.message) }
            }
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            try {
                val message = chatRepository.getMessage(messageId) ?: return@launch
                chatRepository.deleteMessage(messageId)
                if (message.conversationId == _uiState.value.currentConversationId) {
                    resetEngineForCurrentConversation(
                        _uiState.value.capabilityMode,
                        _uiState.value.enableThinking
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete message: " + e.message) }
            }
        }
    }

    fun renameConversation(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            try {
                val conversation = chatRepository.getConversation(id) ?: return@launch
                chatRepository.updateConversation(conversation.copy(title = title.trim()))
                if (_uiState.value.currentConversationId == id) {
                    _uiState.update { it.copy(currentConversationTitle = title.trim()) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to rename conversation: " + e.message) }
            }
        }
    }

    fun pinConversation(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            try {
                val conversation = chatRepository.getConversation(id) ?: return@launch
                chatRepository.updateConversation(conversation.copy(isPinned = pinned))
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to update conversation: " + e.message) }
            }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            try {
                chatRepository.deleteConversation(id)
                if (_uiState.value.currentConversationId == id) {
                    messagesJob?.cancel()
                    loadMostRecentConversation()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete conversation: " + e.message) }
            }
        }
    }

    private fun observeConversations() {
        viewModelScope.launch {
            chatRepository.getAllConversations()
                .catch { e -> _uiState.update { it.copy(error = "Failed to load conversations: " + e.message) } }
                .collect { conversations ->
                    val currentId = _uiState.value.currentConversationId
                    val currentConversation = conversations.firstOrNull { it.id == currentId }

                    if (currentConversation != null) {
                        _uiState.update {
                            it.copy(
                                currentConversationTitle = currentConversation.title,
                                capabilityMode = currentConversation.capabilityMode
                            )
                        }
                    }

                    if (!didHandleInitialConversation) {
                        didHandleInitialConversation = true
                        conversations.firstOrNull()?.let { loadConversation(it.id) }
                    } else if (currentId > 0L && currentConversation == null && !generationJobIsActive()) {
                        loadMostRecentConversation(conversations)
                    }
                }
        }
    }

    private fun observeMessagesForConversation(id: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.getMessagesForConversation(id)
                .catch { e -> _uiState.update { it.copy(error = "Failed to load messages: " + e.message) } }
                .collect { msgs ->
                    _uiState.update { s ->
                        s.copy(
                            messages = msgs,
                            showMascot = msgs.isEmpty() && !s.isStreaming,
                            isUsingThreadContext = msgs.size >= 2
                        )
                    }
                }
        }
    }

    private fun loadMostRecentConversation(existingConversations: List<com.localyze.domain.models.Conversation>? = null) {
        viewModelScope.launch {
            val conversations = existingConversations ?: chatRepository.getAllConversations().first()
            val nextConversation = conversations.firstOrNull()
            if (nextConversation != null) {
                loadConversation(nextConversation.id)
            } else {
                messagesJob?.cancel()
                sendMessageUseCase.resetEngineConversation(_uiState.value.capabilityMode, _uiState.value.enableThinking)
                _uiState.update {
                    it.copy(
                        currentConversationId = -1L,
                        currentConversationTitle = "New Chat",
                        messages = emptyList(),
                        streamingText = "",
                        thinkingText = "",
                        generationStatus = "",
                        activeToolCalls = emptyList(),
                        isStreaming = false,
                        isThinking = false,
                        showMascot = true,
                        isUsingThreadContext = false
                    )
                }
            }
        }
    }

    private fun generationJobIsActive(): Boolean = generationJob?.isActive == true

    private fun toolStatus(toolName: String, executing: Boolean): String {
        return when (toolName) {
            "web_search" -> if (executing) "Searching the web" else "Synthesizing from sources"
            "calculator" -> if (executing) "Calculating" else "Using exact result"
            "memory" -> if (executing) "Checking memory" else "Using saved context"
            "file_reader" -> if (executing) "Reading file" else "Using file context"
            "calendar" -> if (executing) "Reading calendar" else "Using calendar data"
            "contacts" -> if (executing) "Looking up contact" else "Using contact data"
            else -> if (executing) "Using $toolName" else "Processing tool result"
        }
    }

    /**
     * Shows a tool confirmation dialog for tools that require user approval.
     */
    fun requestToolConfirmation(pending: DispatchResult.PendingConfirmation) {
        _uiState.update { it.copy(pendingToolConfirmation = pending) }
    }

    /**
     * Executes a confirmed tool call.
     */
    fun confirmToolExecution(pending: DispatchResult.PendingConfirmation) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(pendingToolConfirmation = null) }
                val result = toolDispatcher.confirmAndExecute(pending)
                com.localyze.utils.AppLog.d("ChatViewModel", "Tool executed: ${result.name}, success: ${!result.isError}")

                // Resume generation with the tool result
                val state = _uiState.value
                if (state.currentConversationId > 0L && !result.isError) {
                    doContinueWithToolResult(state.currentConversationId, result)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Tool execution failed: ${e.message}") }
            }
        }
    }

    /**
     * Dismisses the pending tool confirmation without executing.
     */
    fun dismissToolConfirmation() {
        _uiState.update { it.copy(pendingToolConfirmation = null) }
    }

    private suspend fun resetEngineForCurrentConversation(
        capabilityMode: String,
        enableThinking: Boolean
    ) {
        val currentId = _uiState.value.currentConversationId
        if (currentId > 0L) {
            sendMessageUseCase.resetEngineConversationWithSavedContext(
                currentId,
                capabilityMode,
                enableThinking
            )
        } else {
            sendMessageUseCase.resetEngineConversation(capabilityMode, enableThinking)
        }
    }

    private fun readInitialConversationId(savedStateHandle: SavedStateHandle): Long? {
        return runCatching { savedStateHandle.get<Long>(CONVERSATION_ID_KEY) }.getOrNull()
            ?: runCatching { savedStateHandle.get<String>(CONVERSATION_ID_KEY)?.toLongOrNull() }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        loadConversationJob?.cancel()
        messagesJob?.cancel()
    }
}
