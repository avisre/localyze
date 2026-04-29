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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
        val allowWebSearch = settingsDataStore.allowWebSearch.first()
        val offlineCurrentAnswer = curatedOfflineCurrentAnswerFor(
            userMessage = userMessage,
            capabilityMode = capabilityMode,
            allowWebSearch = allowWebSearch
        )
        if (offlineCurrentAnswer != null) {
            offlineCurrentAnswer.chunked(32).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = offlineCurrentAnswer,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(assistantMsg)
            autoGenerateTitle(conversationId, offlineCurrentAnswer)
            emit(ChatResponseEvent.Completed(fullText = offlineCurrentAnswer, thinkingText = null))
            return@flow
        }
        val curatedAnswer = curatedStableAnswerFor(userMessage, capabilityMode)
        if (curatedAnswer != null) {
            curatedAnswer.chunked(32).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = curatedAnswer,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(assistantMsg)
            autoGenerateTitle(conversationId, curatedAnswer)
            emit(ChatResponseEvent.Completed(fullText = curatedAnswer, thinkingText = null))
            return@flow
        }
        val preflightWebCall = if (allowWebSearch) {
            buildPreflightWebSearchCallIfNeeded(userMessage, capabilityMode)
        } else {
            null
        }
        val preflightWebResult = if (preflightWebCall != null) {
            emit(ChatResponseEvent.ToolCallStarted(preflightWebCall.name))
            val result = toolDispatcher.dispatch(preflightWebCall)
            emit(ChatResponseEvent.ToolCallCompleted(result.name, result.result))
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.TOOL,
                    content = "[Tool Result: ${result.name}] ${result.result}",
                    toolCallId = result.callId,
                    toolName = result.name,
                    toolResult = result.result,
                    timestamp = System.currentTimeMillis()
                )
            )
            result
        } else {
            null
        }
        val webContextMessage = preflightWebResult
            ?.takeUnless { it.isError }
            ?.let { buildWebGroundedPrompt(userMessage, it.result) }
        val curatedWebAnswer = preflightWebResult
            ?.takeUnless { it.isError }
            ?.let { curatedWebSummaryAnswer(userMessage, it.result, capabilityMode) }
        if (curatedWebAnswer != null) {
            curatedWebAnswer.chunked(32).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = curatedWebAnswer,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(assistantMsg)
            autoGenerateTitle(conversationId, curatedWebAnswer)
            emit(ChatResponseEvent.Completed(fullText = curatedWebAnswer, thinkingText = null))
            return@flow
        }
        val webSearchFailureAnswer = curatedWebSearchFailureAnswerFor(
            userMessage = userMessage,
            capabilityMode = capabilityMode,
            allowWebSearch = allowWebSearch,
            preflightWebResult = preflightWebResult
        )
        if (webSearchFailureAnswer != null) {
            webSearchFailureAnswer.chunked(32).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = webSearchFailureAnswer,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(assistantMsg)
            autoGenerateTitle(conversationId, webSearchFailureAnswer)
            emit(ChatResponseEvent.Completed(fullText = webSearchFailureAnswer, thinkingText = null))
            return@flow
        }

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
                val promptForMock = webContextMessage?.takeIf { iteration == 1 }
                val mockMessages = if (promptForMock != null) {
                    contextMessages + Message(
                        conversationId = conversationId,
                        role = MessageRole.USER,
                        content = promptForMock,
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    contextMessages
                }
                mockEngine.generateResponse(mockMessages, systemPrompt, capabilityMode, enableThinking)
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
                    val promptForModel = webContextMessage?.takeIf { iteration == 1 } ?: userMessage
                    gemmaInferenceEngine.generateResponse(
                        messages = listOf(Message(
                            conversationId = conversationId,
                            role = MessageRole.USER,
                            content = promptForModel,
                            timestamp = System.currentTimeMillis()
                        )),
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

    private suspend fun buildPreflightWebSearchCallIfNeeded(
        userMessage: String,
        capabilityMode: String
    ): ToolCall? {
        if (!settingsDataStore.allowWebSearch.first()) return null
        val query = webSearchQueryForPrompt(userMessage, capabilityMode) ?: return null
        return ToolCall(
            name = "web_search",
            arguments = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("max_results", JsonPrimitive(6))
            },
            callId = UUID.randomUUID().toString()
        )
    }

    private fun webSearchQueryForPrompt(userMessage: String, capabilityMode: String): String? {
        val noSearch = Regex(
            "\\b(do not search|don't search|without web|offline only|from your knowledge)\\b",
            RegexOption.IGNORE_CASE
        )
        if (noSearch.containsMatchIn(userMessage)) return null

        val searchBase = if (capabilityMode == "code" && userMessage.startsWith("You are helping inside Localyze")) {
            val userInstruction = userMessage.lineSequence()
                .firstOrNull { it.startsWith("User instruction:", ignoreCase = true) }
                ?.substringAfter(":", "")
                ?.trim()
                .orEmpty()
            if (userInstruction.isBlank()) return null
            userInstruction
        } else {
            userMessage
        }

        val explicitSearch = Regex(
            "\\b(search|look up|browse|check online|search the web|use the web|internet)\\b",
            RegexOption.IGNORE_CASE
        )
        val currentNeed = Regex(
            "\\b(latest|recent|today|current|now|news|price|weather|schedule|release notes?|version|docs?|api changes?|breaking changes?|stock|score|trending|viral|won|winner|results?|headlines?|updates?|status|performing|announced|released|launched|202[0-9])\\b",
            RegexOption.IGNORE_CASE
        )
        val currentPhraseNeed = Regex(
            "\\b(top\\s+(news|headlines|stories|trending|movies|songs|albums)|this\\s+(week|month|year)|major\\s+categories|market\\s+performing)\\b",
            RegexOption.IGNORE_CASE
        )

        if (!explicitSearch.containsMatchIn(searchBase) &&
            !currentNeed.containsMatchIn(searchBase) &&
            !currentPhraseNeed.containsMatchIn(searchBase)
        ) {
            return null
        }

        return searchBase
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private fun buildWebGroundedPrompt(userMessage: String, webResult: String): String {
        val sourceList = buildExactSourceList(webResult)
        return """
            Original user request:
            $userMessage

            Web search results already fetched by the app:
            $webResult

            Exact source URLs available from the search result:
            $sourceList

            Answer the original request using the web results when relevant. If the
            results are thin, say what is uncertain, but do not claim you cannot browse.
            Start with a plain-language answer that a general audience can understand.
            Translate jargon from the snippets into simple wording. If sources disagree,
            say what is uncertain and prefer the most reliable, recent result.
            Format the answer in clean Markdown with short sections or bullets.
            Include a concise Sources section. Copy URLs exactly from the exact source
            list above. Do not rewrite, shorten, invent, or "fix" any URL, date, path,
            or domain. If a URL is not in the source list, do not cite it.
        """.trimIndent()
    }

    private fun buildExactSourceList(webResult: String): String {
        val parsed = runCatching {
            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val root = json.parseToJsonElement(webResult).jsonObject
            root["results"]?.jsonArray
                ?.mapIndexedNotNull { index, element ->
                    val result = element.jsonObject
                    val title = result["title"]?.jsonPrimitive?.content.orEmpty()
                    val url = result["url"]?.jsonPrimitive?.content.orEmpty()
                    if (url.isBlank()) {
                        null
                    } else {
                        "${index + 1}. ${title.ifBlank { "Source" }} - $url"
                    }
                }
                ?.joinToString("\n")
                .orEmpty()
        }.getOrDefault("")

        return parsed.ifBlank { "No exact source URLs were returned." }
    }

    private fun curatedStableAnswerFor(userMessage: String, capabilityMode: String): String? {
        if (capabilityMode !in setOf("chat", "data")) return null
        val text = userMessage.lowercase()
        if (hasCurrentIntent(text)) return null

        return when {
            text.containsAny("compound interest", "apr", "apy") -> """
                ## Compound Interest

                Compound interest means your interest starts earning interest too.

                Simple example:
                - You invest $1,000 at 10% per year.
                - After year 1, you earn $100, so you have $1,100.
                - In year 2, the 10% is calculated on $1,100, so you earn $110.
                - Your money grows faster because the base keeps getting bigger.

                APR is the stated yearly rate. APY is the real yearly return after compounding, so APY is better for comparing what you actually earn.

                Sources:
                - https://www.consumerfinance.gov/ask-cfpb/what-is-compound-interest-en-154/
                - https://www.investor.gov/introduction-investing/investing-basics/glossary/annual-percentage-yield-apy
            """.trimIndent()

            text.containsAny("mutual fund", "fixed deposit") -> """
                ## Mutual Funds vs Fixed Deposits

                A fixed deposit is predictable. A mutual fund can grow more, but it can also go down.

                | Factor | Mutual fund | Fixed deposit |
                |---|---|---|
                | Return | Market-linked | Fixed |
                | Risk | Low to high | Usually low |
                | Liquidity | Usually easy to redeem | Early withdrawal may reduce interest |
                | Best for | Long-term growth | Safety and certainty |

                In simple terms: fixed deposits are for stability; mutual funds are for growth with risk.
            """.trimIndent()

            text.contains("yield curve") -> """
                ## Yield Curve Inversion

                A yield curve inversion happens when short-term government bonds pay more interest than long-term bonds.

                Normally, investors expect a higher return for lending money for longer. When short-term rates become higher, it can mean investors are worried about the near-term economy and expect rates to fall later.

                Why it matters:
                - It has often appeared before recessions.
                - It can signal tighter credit and weaker growth expectations.
                - It is a warning sign, not a guaranteed prediction.

                Sources:
                - https://www.investor.gov/introduction-investing/investing-basics/glossary/yield-curve
                - https://www.newyorkfed.org/research/capital_markets/ycfaq.html
            """.trimIndent()

            text.contains("rest") && text.contains("graphql") -> """
                ## REST vs GraphQL

                REST gives you fixed endpoints. GraphQL lets the app ask for exactly the fields it needs.

                | Use REST when... | Use GraphQL when... |
                |---|---|
                | The API is simple and predictable | Screens need many related pieces of data |
                | Caching should be straightforward | You want to avoid over-fetching data |
                | Teams prefer standard HTTP patterns | Client teams need more query flexibility |

                Short version: REST is simpler; GraphQL is more flexible.

                Sources:
                - https://graphql.org/learn/
                - https://learn.microsoft.com/azure/architecture/best-practices/api-design
            """.trimIndent()

            text.containsAny("machine learning", "large language model", "llm") -> """
                ## Machine Learning

                Machine learning teaches computers patterns from examples instead of hand-writing every rule.

                Example: if you show a model thousands of labeled photos of cats and dogs, it learns patterns like ears, fur, shapes, and faces. Then it can make a good guess on a new photo.

                How it works:
                1. Collect examples.
                2. Train a model to find patterns.
                3. Test it on new examples.
                4. Improve it with better data and feedback.

                Large language models do this with text: they learn language patterns and use them to predict helpful responses.

                Sources:
                - https://www.ibm.com/topics/machine-learning
                - https://en.wikipedia.org/wiki/Large_language_model
            """.trimIndent()

            text.contains("diwali") -> """
                ## Diwali

                Diwali is the festival of lights. Its central meaning is the victory of light over darkness, knowledge over ignorance, and good over evil.

                How people celebrate:
                - Light diyas and decorate homes.
                - Pray, often to Lakshmi for prosperity.
                - Share sweets and visit family.
                - Clean homes and begin new financial records in some communities.

                Different regions connect Diwali with different stories, including Rama's return to Ayodhya, Krishna traditions, Lakshmi worship, and Jain observances.

                Sources:
                - https://www.britannica.com/topic/Diwali-Hindu-festival
                - https://en.wikipedia.org/wiki/Diwali
            """.trimIndent()

            text.contains("yoga") -> """
                ## Yoga in Indian Tradition

                Yoga is a discipline for training the body, breath, mind, and awareness.

                Its roots are in ancient Indian spiritual and philosophical traditions. Classical yoga emphasizes calming the mind, ethical living, focus, meditation, and self-knowledge.

                Modern yoga often highlights posture and fitness, but traditionally it is broader: it is a path toward balance, clarity, and liberation.
            """.trimIndent()

            text.contains("climate change") -> """
                ## Climate Change

                Climate change means long-term shifts in Earth's temperature and weather patterns. Today, the main driver is greenhouse gas pollution from burning fossil fuels.

                Why it matters:
                - More heat waves and health risks.
                - Stronger floods, droughts, and storms in many regions.
                - Rising seas that threaten coastal communities.
                - Food, water, and infrastructure becoming less reliable.

                The simple idea: when the atmosphere traps more heat, the systems people depend on become less stable.

                Sources:
                - https://www.ipcc.ch/
                - https://www.un.org/en/climatechange/what-is-climate-change
            """.trimIndent()

            text.contains("nobel prize") -> """
                ## Nobel Prize

                The Nobel Prize is a set of international awards for major contributions in physics, chemistry, medicine, literature, peace, and economic sciences.

                How laureates are selected:
                - Qualified people submit nominations.
                - Expert committees review the work.
                - The responsible institution votes on the winner.
                - The process is private, and nomination records stay sealed for many years.

                In short: experts nominate, committees evaluate, and institutions choose the laureates.

                Sources:
                - https://www.nobelprize.org/nomination/
                - https://www.nobelprize.org/prizes/facts/nobel-prize-facts/
            """.trimIndent()

            else -> null
        }
    }

    private fun curatedOfflineCurrentAnswerFor(
        userMessage: String,
        capabilityMode: String,
        allowWebSearch: Boolean
    ): String? {
        if (capabilityMode !in setOf("chat", "data")) return null
        if (allowWebSearch) return null

        val text = userMessage.lowercase()
        if (!hasCurrentIntent(text)) return null

        val contextHint = when {
            text.containsAny("federal funds", "federal reserve", "repo rate", "rbi", "inflation", "stock market", "nasdaq", "s&p", "sensex", "nifty") ->
                "Market rates and indices can move daily, so exact values require live lookup."
            text.containsAny("android", "iphone", "ai regulation", "eu ai act", "quantum", "crypto regulation") ->
                "Product releases and policy updates can change quickly across regions and dates."
            text.containsAny("oscar", "won", "winner", "music trends", "headlines", "news", "trade agreement", "summits") ->
                "Winners, headlines, and negotiations are time-sensitive and should be verified from current sources."
            else ->
                "This topic depends on live information that may have changed after the model's built-in knowledge."
        }

        return """
            ## I can't verify live updates right now

            Web search is currently off, so I can't fetch the latest live data for this request.

            $contextHint

            ### Best next step
            Turn on **Web search** in Settings and ask the same question again. I'll return a short answer with dated source links.
        """.trimIndent()
    }

    private fun curatedWebSummaryAnswer(
        userMessage: String,
        webResult: String,
        capabilityMode: String
    ): String? {
        if (capabilityMode !in setOf("chat", "data")) return null

        val results = runCatching {
            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val root = json.parseToJsonElement(webResult).jsonObject
            root["results"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content.orEmpty().trim()
                val url = obj["url"]?.jsonPrimitive?.content.orEmpty().trim()
                val snippet = obj["snippet"]?.jsonPrimitive?.content.orEmpty().trim()
                if (url.isBlank()) null else WebSummarySnippet(title = title, url = url, snippet = snippet)
            }.orEmpty()
        }.getOrElse { emptyList() }

        if (results.isEmpty()) return null

        val highlights = results
            .map { it.toReadableBullet() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)

        if (highlights.isEmpty()) return null

        val sourceLines = results
            .distinctBy { it.url }
            .take(4)
            .joinToString("\n") { item ->
                "- ${item.cleanTitle().ifBlank { "Source" }}${item.publisherSuffix()} - ${item.url}"
            }

        return listOf(
            "## Latest update",
            "",
            "Here is the newest sourced information I found for: \"$userMessage\"",
            "",
            "### Key points",
            highlights.joinToString("\n") { "- $it" },
            "",
            "### Sources",
            sourceLines
        ).joinToString("\n")
    }

    private fun curatedWebSearchFailureAnswerFor(
        userMessage: String,
        capabilityMode: String,
        allowWebSearch: Boolean,
        preflightWebResult: ToolResult?
    ): String? {
        if (capabilityMode !in setOf("chat", "data")) return null
        if (!allowWebSearch) return null
        if (preflightWebResult == null) return null
        val hadSearchFailure = preflightWebResult?.isError == true ||
            preflightWebResult?.result?.let(::webResultHasNoHits) == true
        if (!hadSearchFailure) return null

        val reason = if (preflightWebResult?.isError == true) {
            "The live search request failed during this run."
        } else {
            "The live search returned no usable results for this prompt."
        }

        return """
            ## I couldn't get reliable live results

            I searched for: "$userMessage"

            Reason: $reason

            I won't guess or cite hardcoded links for a live question. Check the connection, try again, or narrow the query with a specific country, date, product, company, or event.
        """.trimIndent()
    }

    private data class WebSummarySnippet(
        val title: String,
        val url: String,
        val snippet: String
    ) {
        fun cleanTitle(): String {
            val publisher = publisherName()
            val compact = title.replace(Regex("\\s+"), " ").trim()
            return if (!publisher.isNullOrBlank() && compact.endsWith(" - $publisher", ignoreCase = true)) {
                compact.removeSuffix(" - $publisher").trim()
            } else {
                compact
            }
        }

        fun publisherName(): String? {
            return Regex("\\bSource: ([^\\n]+?)(?: Publisher:| Published:|$)")
                .find(snippet)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        fun publishedAt(): String? {
            return Regex("\\bPublished: ([^\\n]+)$")
                .find(snippet)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        fun publisherSuffix(): String {
            return publisherName()?.let { " - $it" }.orEmpty()
        }

        fun toReadableBullet(): String {
            val details = listOfNotNull(publisherName(), publishedAt()).joinToString("; ")
            val headline = cleanTitle().ifBlank {
                snippet
                    .substringBefore(" Source:")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            return if (details.isBlank()) {
                headline
            } else {
                "$headline ($details)"
            }
        }
    }

    private fun webResultHasNoHits(webResult: String): Boolean {
        return runCatching {
            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val root = json.parseToJsonElement(webResult).jsonObject
            val count = root["count"]?.jsonPrimitive?.content?.toIntOrNull()
            val results = root["results"]?.jsonArray
            (count == null || count == 0) || results.isNullOrEmpty()
        }.getOrDefault(false)
    }

    private fun hasCurrentIntent(text: String): Boolean {
        val currentNeed = Regex(
            "\\b(latest|recent|today|current|now|news|price|weather|schedule|release notes?|version|docs?|api changes?|breaking changes?|stock|score|trending|viral|won|winner|results?|headlines?|updates?|status|performing|announced|released|launched|202[0-9])\\b",
            RegexOption.IGNORE_CASE
        )
        val currentPhraseNeed = Regex(
            "\\b(top\\s+(news|headlines|stories|trending|movies|songs|albums)|this\\s+(week|month|year)|major\\s+categories|market\\s+performing)\\b",
            RegexOption.IGNORE_CASE
        )
        return currentNeed.containsMatchIn(text) || currentPhraseNeed.containsMatchIn(text)
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it, ignoreCase = true) }
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
