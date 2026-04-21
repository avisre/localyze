package com.localassistant.ui.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localassistant.ai.AudioRecordingState
import com.localassistant.data.local.SettingsDataStore
import com.localassistant.data.repository.ChatRepository
import com.localassistant.data.repository.PerformanceMonitor
import com.localassistant.domain.usecases.ChatResponseEvent
import com.localassistant.domain.usecases.ManageMemoryUseCase
import com.localassistant.domain.usecases.RecordAudioUseCase
import com.localassistant.domain.usecases.SendMessageUseCase
import com.localassistant.tools.DispatchResult
import com.localassistant.tools.ToolDispatcher
import com.localassistant.utils.InputValidator
import com.localassistant.utils.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val CONVERSATION_ID_KEY = "conversationId"
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
        _uiState.update { it.copy(isMockMode = sendMessageUseCase.isUsingMockEngine()) }
        observeSettings()
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

    private fun observeSettings() {
        viewModelScope.launch {
            settingsDataStore.thinkingMode.collect { enabled ->
                val previous = _uiState.value.enableThinking
                _uiState.update { it.copy(enableThinking = enabled) }
                if (previous != enabled && !_uiState.value.isStreaming) {
                    resetEngineForCurrentConversation(_uiState.value.capabilityMode, enabled)
                }
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
                _uiState.update { it.copy(allowWebSearch = enabled) }
            }
        }
    }

    fun loadConversation(id: Long) {
        loadConversationJob?.cancel()
        messagesJob?.cancel()
        loadConversationJob = viewModelScope.launch {
            val conversation = chatRepository.getConversation(id)
            if (conversation == null) {
                _uiState.update { it.copy(error = "Conversation not found") }
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
                    activeToolCalls = emptyList(),
                    isStreaming = false,
                    isThinking = false,
                    error = null
                )
            }

            messagesJob = viewModelScope.launch {
                chatRepository.getMessagesForConversation(id)
                    .catch { e -> _uiState.update { it.copy(error = "Failed to load messages: " + e.message) } }
                    .collect { msgs ->
                        _uiState.update { s ->
                            s.copy(
                                messages = msgs,
                                showMascot = msgs.isEmpty() && !s.isStreaming
                            )
                        }
                    }
            }
        }
    }

    fun createNewConversation() {
        viewModelScope.launch {
            try {
                val conv = chatRepository.createConversation(capabilityMode = _uiState.value.capabilityMode)
                sendMessageUseCase.resetEngineConversation(conv.capabilityMode, _uiState.value.enableThinking)
                _uiState.update { s -> s.copy(currentConversationId = conv.id, currentConversationTitle = conv.title, messages = emptyList(), streamingText = "", thinkingText = "", activeToolCalls = emptyList(), isStreaming = false, isThinking = false, showMascot = true, error = null) }
                loadConversation(conv.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to create conversation: " + e.message) }
            }
        }
    }

    fun sendMessage(text: String) {
        android.util.Log.d("ChatViewModel", "sendMessage called with: '$text'")

        // Validate input before processing
        val validationResult = InputValidator.validateMessage(text)
        if (validationResult is ValidationResult.Error) {
            _uiState.update { it.copy(error = validationResult.message) }
            return
        }

        val state = _uiState.value
        android.util.Log.d("ChatViewModel", "isStreaming=${state.isStreaming}, currentConversationId=${state.currentConversationId}")
        if (state.isStreaming || text.isBlank()) return
        val conversationId = state.currentConversationId
        if (conversationId <= 0L) {
            viewModelScope.launch {
                try {
                    val conv = chatRepository.createConversation(capabilityMode = state.capabilityMode)
                    _uiState.update { it.copy(currentConversationId = conv.id) }
                    loadConversation(conv.id)
                    doSendMessage(conv.id, text)
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "Failed to create conversation: " + e.message) }
                }
            }
            return
        }
        doSendMessage(conversationId, text)
    }

    private fun doSendMessage(conversationId: Long, text: String) {
        android.util.Log.d("ChatViewModel", "doSendMessage: convId=$conversationId, text='$text'")
        performanceMonitor.startResponse()
        _uiState.update { s -> s.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", activeToolCalls = emptyList(), showMascot = false, error = null) }
        generationJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(60000) { // 60 second overall timeout
                    sendMessageUseCase.sendMessage(conversationId = conversationId, userMessage = text, capabilityMode = _uiState.value.capabilityMode, enableThinking = _uiState.value.enableThinking)
                        .catch { e ->
                            android.util.Log.e("ChatViewModel", "Error in sendMessage flow", e)
                            _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, error = "Generation error: " + e.message) }
                        }
                        .collect { event ->
                            android.util.Log.d("ChatViewModel", "Received event: ${event.javaClass.simpleName}")
                            handleResponseEvent(event)
                        }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("ChatViewModel", "Message generation timed out", e)
                performanceMonitor.recordError("Generation timed out", timeout = true)
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, error = "The model took too long to respond. Please try again.") }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Unexpected error during message generation", e)
                performanceMonitor.recordError(e.message ?: "Generation error")
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, error = "Error: ${e.message}") }
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
        _uiState.update { s -> s.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", activeToolCalls = emptyList(), showMascot = false, error = null) }
        generationJob = viewModelScope.launch {
            sendMessageUseCase.sendMessageWithImage(conversationId = id, userMessage = text, imageBitmap = bitmap, capabilityMode = _uiState.value.capabilityMode, enableThinking = _uiState.value.enableThinking)
                .catch { e -> _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, error = "Image generation error: " + e.message) } }
                .collect { event -> handleResponseEvent(event) }
        }
    }

    fun sendAudioMessage(audioBytes: ByteArray) {
        android.util.Log.d("ChatViewModel", "sendAudioMessage called with ${audioBytes.size} bytes")
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
        _uiState.update { s -> s.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", activeToolCalls = emptyList(), showMascot = false, error = null) }
        generationJob = viewModelScope.launch {
            sendMessageUseCase.sendMessageWithAudio(conversationId = id, audioBytes = audioBytes, capabilityMode = _uiState.value.capabilityMode, enableThinking = _uiState.value.enableThinking)
                .catch { e -> _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, error = "Audio generation error: " + e.message) } }
                .collect { event -> handleResponseEvent(event) }
        }
    }

    private fun handleResponseEvent(event: ChatResponseEvent) {
        android.util.Log.d("ChatViewModel", "handleResponseEvent: ${event.javaClass.simpleName}")
        when (event) {
            is ChatResponseEvent.StreamingToken -> {
                android.util.Log.v("ChatViewModel", "StreamingToken: '${event.text.take(20)}...'")
                performanceMonitor.addToken(event.text)
                _uiState.update { s ->
                    if (s.streamTokens) {
                        s.copy(streamingText = s.streamingText + event.text, isStreaming = true, isThinking = false)
                    } else {
                        s.copy(isStreaming = true, isThinking = false)
                    }
                }
            }
            is ChatResponseEvent.ThinkingToken -> {
                android.util.Log.v("ChatViewModel", "ThinkingToken: '${event.text.take(20)}...'")
                _uiState.update { s ->
                    if (s.streamTokens) {
                        s.copy(thinkingText = s.thinkingText + event.text, isThinking = true, isStreaming = true)
                    } else {
                        s.copy(isThinking = true, isStreaming = true)
                    }
                }
            }
            is ChatResponseEvent.ToolCallStarted -> {
                android.util.Log.d("ChatViewModel", "ToolCallStarted: ${event.toolName}")
                _uiState.update { s -> s.copy(activeToolCalls = s.activeToolCalls + ActiveToolCall(toolName = event.toolName, isExecuting = true)) }
            }
            is ChatResponseEvent.ToolCallCompleted -> {
                android.util.Log.d("ChatViewModel", "ToolCallCompleted: ${event.toolName}")
                val updated = _uiState.value.activeToolCalls.map { if (it.toolName == event.toolName && it.isExecuting) it.copy(isExecuting = false, result = event.result) else it }
                _uiState.update { s -> s.copy(activeToolCalls = updated) }
            }
            is ChatResponseEvent.Completed -> {
                android.util.Log.d("ChatViewModel", "Completed")
                performanceMonitor.completeResponse()
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, streamingText = "", thinkingText = "", activeToolCalls = emptyList(), showMascot = false) }
            }
            is ChatResponseEvent.Error -> {
                android.util.Log.e("ChatViewModel", "Error event: ${event.message}")
                performanceMonitor.recordError(event.message)
                _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, error = event.message) }
            }
        }
    }

    fun startAudioRecording() {
        viewModelScope.launch {
            val result = recordAudioUseCase.startRecording()
            if (result.isFailure) _uiState.update { s -> s.copy(error = "Recording failed: " + result.exceptionOrNull()?.message) }
        }
    }

    fun stopAudioRecording() {
        android.util.Log.d("ChatViewModel", "stopAudioRecording called")
        viewModelScope.launch {
            val result = recordAudioUseCase.stopRecording()
            android.util.Log.d("ChatViewModel", "voice transcription result: isSuccess=${result.isSuccess}, transcript='${result.getOrNull().orEmpty().take(80)}'")
            if (result.isSuccess) {
                val transcript = result.getOrNull().orEmpty().trim()
                if (transcript.isBlank()) {
                    _uiState.update { it.copy(error = "No speech was recognized") }
                    return@launch
                }
                android.util.Log.d("ChatViewModel", "Sending transcribed voice prompt")
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
        _uiState.update { s -> s.copy(isStreaming = false, isThinking = false) }
    }

    fun regenerateResponse() {
        val state = _uiState.value
        if (state.isStreaming) return
        val convId = state.currentConversationId
        if (convId <= 0L) return
        _uiState.update { it.copy(isStreaming = true, isThinking = false, streamingText = "", thinkingText = "", activeToolCalls = emptyList(), error = null) }
        generationJob = viewModelScope.launch {
            sendMessageUseCase.regenerateLastResponse(conversationId = convId, capabilityMode = state.capabilityMode, enableThinking = state.enableThinking)
                .catch { e -> _uiState.update { s -> s.copy(isStreaming = false, isThinking = false, error = "Regeneration error: " + e.message) } }
                .collect { event -> handleResponseEvent(event) }
        }
    }

    fun toggleThinkingExpanded(index: Int) {
        _expandedThinking.update { c -> if (c.contains(index)) c - index else c + index }
    }

    fun isUsingMockEngine(): Boolean = sendMessageUseCase.isUsingMockEngine()

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

    private fun loadMostRecentConversation(existingConversations: List<com.localassistant.domain.models.Conversation>? = null) {
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
                        activeToolCalls = emptyList(),
                        isStreaming = false,
                        isThinking = false,
                        showMascot = true
                    )
                }
            }
        }
    }

    private fun generationJobIsActive(): Boolean = generationJob?.isActive == true

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
                // Tool result will be handled through the message flow
                android.util.Log.d("ChatViewModel", "Tool executed: ${result.name}, success: ${!result.isError}")
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
