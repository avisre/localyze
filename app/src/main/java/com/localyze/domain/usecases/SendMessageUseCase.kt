package com.localyze.domain.usecases

import android.content.Context
import android.graphics.Bitmap
import com.localyze.ai.ContextWindowManager
import com.localyze.ai.GemmaInferenceEngine
import com.localyze.ai.InferenceToken
import com.localyze.ai.MockGemmaEngine
import com.localyze.ai.SystemPromptBuilder
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.ChatRepository
import com.localyze.data.repository.MemoryRepository
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import com.localyze.domain.models.ToolCall
import com.localyze.domain.models.ToolResult
import com.localyze.tools.ToolDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Named

/**
 * Orchestrates the full message-sending flow including the agentic
 * tool-call loop (up to 3 iterations).
 *
 * KEY ARCHITECTURE DECISIONS (matching AI Edge Gallery):
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * 1. For the REAL GemmaInferenceEngine, we do NOT manually build context windows
 *    or inject message history. LiteRT-LM's Conversation object tracks history
 *    automatically. We only send the latest user message.
 *
 * 2. For the MOCK engine (development/CI), we still use the context window
 *    approach since MockGemmaEngine doesn't have internal history tracking.
 *
 * 3. When a new conversation starts or mode changes, we call
 *    engine.resetConversation() to clear the KV cache.
 */
class SendMessageUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gemmaInferenceEngine: GemmaInferenceEngine,
    private val mockEngine: MockGemmaEngine,
    @Named("useMockEngine") private val useMockEngine: Boolean,
    private val contextWindowManager: ContextWindowManager,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val toolDispatcher: ToolDispatcher,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val settingsDataStore: SettingsDataStore
) {

    companion object {
        private const val MAX_TOOL_ITERATIONS = 3
        private const val TITLE_MAX_LENGTH = 40
    }

    private var generationJob: Job? = null
    private var isMockActive: Boolean = false

    /**
     * Called when starting a new conversation or switching modes.
     * Resets the LiteRT-LM Conversation so it starts fresh.
     */
    fun resetEngineConversation(
        capabilityMode: String,
        enableThinking: Boolean
    ) {
        if (!useMockEngine) {
            gemmaInferenceEngine.setRestoredConversationContext(null)
            gemmaInferenceEngine.resetConversation(capabilityMode, enableThinking)
        }
    }

    suspend fun resetEngineConversationWithSavedContext(
        conversationId: Long,
        capabilityMode: String,
        enableThinking: Boolean
    ) {
        if (!useMockEngine) {
            gemmaInferenceEngine.setRestoredConversationContext(
                buildRestoredConversationContext(conversationId)
            )
            gemmaInferenceEngine.resetConversation(capabilityMode, enableThinking)
        }
    }

    fun sendMessage(
        conversationId: Long,
        userMessage: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        // Save user message to DB
        val userMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userMessage,
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(userMsg)

        emitAll(
            generateWithToolLoop(conversationId, userMessage, capabilityMode, enableThinking)
        )
    }

    fun sendMessageWithImage(
        conversationId: Long,
        userMessage: String,
        imageBitmap: Bitmap,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        // Save bitmap to cache directory and store path in imageUris
        val imagePath = saveBitmapToCache(imageBitmap)
        val userMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userMessage.ifBlank { "Describe this image" },
            imageUris = imagePath?.let { listOf(it) } ?: emptyList(),
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(userMsg)

        val fullTextBuilder = StringBuilder()
        val thinkingTextBuilder = StringBuilder()

        // Image analysis uses a short-lived multimodal Session in the real engine.
        // Conversation remains text-only in LiteRT-LM 0.10, so after the image
        // turn is saved we rebuild text conversation context from the database.
        val tokenFlow = if (useMockEngine) {
            isMockActive = true
            val messages = getConversationMessages(conversationId)
            val systemPrompt = systemPromptBuilder.buildSystemPrompt(capabilityMode, enableThinking)
            val memories = getPromptMemories()
            val contextMessages = contextWindowManager.buildContextWindow(
                messages = messages, systemPrompt = systemPrompt, memories = memories
            )
            mockEngine.generateResponseWithImage(contextMessages, imageBitmap, userMessage, capabilityMode, enableThinking)
        } else {
            gemmaInferenceEngine.generateResponseWithImage(
                getConversationMessages(conversationId), imageBitmap, userMessage, capabilityMode, enableThinking
            )
        }

        var generationError: String? = null
        try {
            tokenFlow.collect { token ->
                when (token) {
                    is InferenceToken.TextToken -> {
                        fullTextBuilder.append(token.text)
                        emit(ChatResponseEvent.StreamingToken(token.text))
                    }
                    is InferenceToken.ThinkingToken -> {
                        thinkingTextBuilder.append(token.text)
                        emit(ChatResponseEvent.ThinkingToken(token.text))
                    }
                    is InferenceToken.ToolCallToken -> {
                        val toolCall = token.toolCall
                        emit(ChatResponseEvent.ToolCallStarted(toolCall.name))
                        val result = toolDispatcher.dispatch(toolCall)
                        emit(ChatResponseEvent.ToolCallCompleted(toolCall.name, result.result))
                    }
                    is InferenceToken.EndOfStream -> { }
                    is InferenceToken.Error -> {
                        generationError = token.message
                        emit(ChatResponseEvent.Error(token.message))
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            emit(ChatResponseEvent.Error(e.message ?: "Generation failed"))
            return@flow
        }

        if (generationError != null) {
            return@flow
        }

        if (fullTextBuilder.isBlank()) {
            emit(ChatResponseEvent.Error("Image analysis did not return a response. Please try again."))
            return@flow
        }

        val assistantMsg = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = fullTextBuilder.toString(),
            thinkingContent = thinkingTextBuilder.toString().ifBlank { null },
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(assistantMsg)
        autoGenerateTitle(conversationId, fullTextBuilder.toString())

        if (!useMockEngine) {
            gemmaInferenceEngine.setRestoredConversationContext(
                buildRestoredConversationContext(conversationId)
            )
            gemmaInferenceEngine.resetConversation(capabilityMode, enableThinking)
        }

        emit(ChatResponseEvent.Completed(
            fullText = fullTextBuilder.toString(),
            thinkingText = thinkingTextBuilder.toString().ifBlank { null }
        ))
    }

    fun sendMessageWithAudio(
        conversationId: Long,
        audioBytes: ByteArray,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        val userMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = "\uD83C\uDFA4 Voice message",
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(userMsg)

        val fullTextBuilder = StringBuilder()
        val thinkingTextBuilder = StringBuilder()

        // Gallery pattern: We do NOT need to explicitly reset the conversation here.
        // The ensureConversation() inside generateResponseWithAudio() will detect
        // supportAudio=true and auto-reset if the current conversation doesn't
        // support audio. This preserves multi-turn context when sending
        // consecutive audio messages.

        val tokenFlow = if (useMockEngine) {
            isMockActive = true
            val messages = getConversationMessages(conversationId)
            val systemPrompt = systemPromptBuilder.buildSystemPrompt(capabilityMode, enableThinking)
            val memories = getPromptMemories()
            val contextMessages = contextWindowManager.buildContextWindow(
                messages = messages, systemPrompt = systemPrompt, memories = memories
            )
            mockEngine.generateResponseWithAudio(contextMessages, audioBytes, "Transcribe and respond to this audio", capabilityMode, enableThinking)
        } else {
            gemmaInferenceEngine.generateResponseWithAudio(
                getConversationMessages(conversationId), audioBytes, "Transcribe and respond to this audio", capabilityMode, enableThinking
            )
        }

        try {
            tokenFlow.collect { token ->
                when (token) {
                    is InferenceToken.TextToken -> {
                        fullTextBuilder.append(token.text)
                        emit(ChatResponseEvent.StreamingToken(token.text))
                    }
                    is InferenceToken.ThinkingToken -> {
                        thinkingTextBuilder.append(token.text)
                        emit(ChatResponseEvent.ThinkingToken(token.text))
                    }
                    is InferenceToken.ToolCallToken -> {
                        val toolCall = token.toolCall
                        emit(ChatResponseEvent.ToolCallStarted(toolCall.name))
                        val result = toolDispatcher.dispatch(toolCall)
                        emit(ChatResponseEvent.ToolCallCompleted(toolCall.name, result.result))
                    }
                    is InferenceToken.EndOfStream -> { }
                    is InferenceToken.Error -> emit(ChatResponseEvent.Error(token.message))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            emit(ChatResponseEvent.Error(e.message ?: "Generation failed"))
            return@flow
        }

        val assistantMsg = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = fullTextBuilder.toString(),
            thinkingContent = thinkingTextBuilder.toString().ifBlank { null },
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(assistantMsg)
        autoGenerateTitle(conversationId, fullTextBuilder.toString())

        emit(ChatResponseEvent.Completed(
            fullText = fullTextBuilder.toString(),
            thinkingText = thinkingTextBuilder.toString().ifBlank { null }
        ))
    }

    fun stopGeneration() {
        if (useMockEngine) {
            generationJob?.cancel()
        } else {
            gemmaInferenceEngine.stopGeneration()
        }
        generationJob = null
    }

    fun regenerateLastResponse(
        conversationId: Long,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        val recentMessages = chatRepository.getRecentMessages(conversationId, 1)
        val lastMessage = recentMessages.firstOrNull()
        if (lastMessage != null && lastMessage.role == MessageRole.ASSISTANT) {
            chatRepository.deleteMessage(lastMessage.id)
        }

        val remainingMessages = chatRepository.getRecentMessages(conversationId, 2)
        val lastUserMessage = remainingMessages.firstOrNull { it.role == MessageRole.USER }
        if (lastUserMessage == null) {
            emit(ChatResponseEvent.Error("No user message found to regenerate from"))
            return@flow
        }

        resetEngineConversationWithSavedContext(conversationId, capabilityMode, enableThinking)

        emitAll(
            generateWithToolLoop(
                conversationId,
                lastUserMessage.content,
                capabilityMode,
                enableThinking
            )
        )
    }

    fun isUsingMockEngine(): Boolean = useMockEngine

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun generateWithToolLoop(
        conversationId: Long,
        userMessage: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        var iteration = 0
        var pendingToolResults: List<ToolResult>? = null

        val fullTextBuilder = StringBuilder()
        val thinkingTextBuilder = StringBuilder()

        while (iteration < MAX_TOOL_ITERATIONS && currentCoroutineContext().isActive) {
            iteration++

            // â”€â”€ Build the token flow â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            //
            // REAL ENGINE (Gallery pattern):
            //   - Conversation tracks history internally, NO manual injection
            //   - Only send the latest user message
            //   - Context window building is NOT needed for the real engine
            //
            // MOCK ENGINE:
            //   - Still uses context window (mock doesn't track history)
            //
            val tokenFlow = if (useMockEngine) {
                isMockActive = true
                // Mock: build full context window from DB
                val messages = getConversationMessages(conversationId)
                val systemPrompt = systemPromptBuilder.buildSystemPrompt(capabilityMode, enableThinking)
                val memories = getPromptMemories()

                val contextMessages = if (pendingToolResults != null) {
                    val toolMessages = pendingToolResults!!.map { result ->
                        Message(
                            conversationId = conversationId,
                            role = MessageRole.TOOL,
                            content = "[Tool Result: ${result.name}] ${result.result}",
                            toolCallId = result.callId,
                            toolName = result.name,
                            toolResult = result.result,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                    for (toolMsg in toolMessages) {
                        chatRepository.saveMessage(toolMsg)
                    }
                    val allMessages = getConversationMessages(conversationId)
                    contextWindowManager.buildContextWindow(
                        messages = allMessages, systemPrompt = systemPrompt, memories = memories
                    )
                } else {
                    contextWindowManager.buildContextWindow(
                        messages = messages, systemPrompt = systemPrompt, memories = memories
                    )
                }
                mockEngine.generateResponse(contextMessages, systemPrompt, capabilityMode, enableThinking)
            } else {
                // REAL ENGINE: Gallery pattern â€” just send the latest message
                // Conversation handles history internally
                if (pendingToolResults != null) {
                    // After tool execution, we need to feed tool results back.
                    // For LiteRT-LM with native tool calling, the engine handles this.
                    // For our manual tool loop, we reset conversation with tool results
                    // injected as the latest context and re-generate.
                    val toolResultsText = pendingToolResults!!.joinToString("\n") { result ->
                        "[Tool Result: ${result.name}] ${result.result}"
                    }
                    // Save tool results as messages so conversation context is maintained
                    for (result in pendingToolResults!!) {
                        chatRepository.saveMessage(Message(
                            conversationId = conversationId,
                            role = MessageRole.TOOL,
                            content = "[Tool Result: ${result.name}] ${result.result}",
                            toolCallId = result.callId,
                            toolName = result.name,
                            toolResult = result.result,
                            timestamp = System.currentTimeMillis()
                        ))
                    }

                    // Send tool results as a follow-up message
                    gemmaInferenceEngine.generateResponse(
                        messages = listOf(Message(
                            conversationId = conversationId,
                            role = MessageRole.USER,
                            content = "Here are the tool results:\n$toolResultsText\nPlease use these results to answer my original question."
                        )),
                        systemPrompt = systemPromptBuilder.buildSystemPrompt(capabilityMode, enableThinking),
                        capabilityMode = capabilityMode,
                        enableThinking = enableThinking
                    )
                } else {
                    gemmaInferenceEngine.generateResponse(
                        messages = getConversationMessages(conversationId),
                        systemPrompt = systemPromptBuilder.buildSystemPrompt(capabilityMode, enableThinking),
                        capabilityMode = capabilityMode,
                        enableThinking = enableThinking
                    )
                }
            }

            pendingToolResults = null
            val toolCallsInIteration = mutableListOf<ToolCall>()
            val iterationTextBuilder = StringBuilder()
            val iterationThinkingBuilder = StringBuilder()

            try {
                tokenFlow.collect { token ->
                    when (token) {
                        is InferenceToken.TextToken -> {
                            iterationTextBuilder.append(token.text)
                            fullTextBuilder.append(token.text)
                            emit(ChatResponseEvent.StreamingToken(token.text))
                        }
                        is InferenceToken.ThinkingToken -> {
                            iterationThinkingBuilder.append(token.text)
                            thinkingTextBuilder.append(token.text)
                            emit(ChatResponseEvent.ThinkingToken(token.text))
                        }
                        is InferenceToken.ToolCallToken -> {
                            toolCallsInIteration.add(token.toolCall)
                        }
                        is InferenceToken.EndOfStream -> { }
                        is InferenceToken.Error -> {
                            emit(ChatResponseEvent.Error(token.message))
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                emit(ChatResponseEvent.Error(e.message ?: "Generation failed"))
                return@flow
            }

            if (toolCallsInIteration.isNotEmpty()) {
                val toolResults = mutableListOf<ToolResult>()
                for (toolCall in toolCallsInIteration) {
                    emit(ChatResponseEvent.ToolCallStarted(toolCall.name))
                    val result = toolDispatcher.dispatch(toolCall)
                    toolResults.add(result)
                    emit(ChatResponseEvent.ToolCallCompleted(toolCall.name, result.result))
                }

                if (iterationTextBuilder.isNotEmpty()) {
                    val partialAssistantMsg = Message(
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = iterationTextBuilder.toString(),
                        thinkingContent = iterationThinkingBuilder.toString().ifBlank { null },
                        timestamp = System.currentTimeMillis()
                    )
                    chatRepository.saveMessage(partialAssistantMsg)
                }

                pendingToolResults = toolResults
            } else {
                break
            }
        }

        if (fullTextBuilder.isNotEmpty()) {
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = fullTextBuilder.toString(),
                thinkingContent = thinkingTextBuilder.toString().ifBlank { null },
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(assistantMsg)
            autoGenerateTitle(conversationId, fullTextBuilder.toString())
        }

        emit(ChatResponseEvent.Completed(
            fullText = fullTextBuilder.toString(),
            thinkingText = thinkingTextBuilder.toString().ifBlank { null }
        ))
    }

    private suspend fun getConversationMessages(conversationId: Long): List<Message> {
        return chatRepository.getMessagesForConversation(conversationId).first()
    }

    private suspend fun getPromptMemories() =
        if (settingsDataStore.memoryEnabled.first()) {
            memoryRepository.getAllMemories()
        } else {
            emptyList()
        }

    private suspend fun buildRestoredConversationContext(conversationId: Long): String? {
        val recent = chatRepository.getRecentMessages(conversationId, 12)
            .asReversed()
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }

        if (recent.isEmpty()) return null

        return recent.joinToString("\n") { message ->
            val role = when (message.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                else -> "Message"
            }
            "$role: ${message.content.take(800)}"
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap): String? {
        return try {
            val cacheDir = File(context.cacheDir, "shared_images").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "img_${System.currentTimeMillis()}.png"
            val file = File(cacheDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun autoGenerateTitle(conversationId: Long, responseText: String) {
        if (responseText.isBlank()) return

        val conversation = chatRepository.getConversation(conversationId) ?: return
        if (conversation.title != "New Chat") return

        val title = if (responseText.trimStart().startsWith("```")) {
            val codeContent = responseText.lines()
                .dropWhile { it.startsWith("```") }
                .firstOrNull()
                ?.take(30)
                ?.trim()
                ?: "Code Help"
            "Code: $codeContent"
        } else {
            responseText
                .replace(Regex("```[^`]*```"), "")
                .replace(Regex("[#*`\\[\\]]"), "")
                .lineSequence()
                .firstOrNull()
                ?.take(TITLE_MAX_LENGTH)
                ?.trim()
                ?: "New Chat"
        }

        chatRepository.updateConversation(conversation.copy(title = title))
    }
}
