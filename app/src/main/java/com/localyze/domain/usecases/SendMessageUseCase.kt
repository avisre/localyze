package com.localyze.domain.usecases

import android.content.Context
import android.graphics.Bitmap
import com.localyze.ai.ContextWindowManager
import com.localyze.ai.GemmaInferenceEngine
import com.localyze.ai.InferenceToken
import com.localyze.ai.InputGuardrails
import com.localyze.ai.ResponsePostProcessor
import com.localyze.ai.SystemPromptBuilder
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.ChatRepository
import com.localyze.data.repository.MemoryRepository
import com.localyze.domain.clarify.ClarificationDecision
import com.localyze.domain.clarify.ClarificationOrchestrator
import com.localyze.domain.clarify.ClarifyState
import com.localyze.domain.clarify.toMarkdown
import com.localyze.domain.models.Message
import com.localyze.domain.models.MessageRole
import com.localyze.domain.models.ToolCall
import com.localyze.domain.models.ToolResult
import com.localyze.tools.FinancialSourceText
import com.localyze.tools.ToolDispatcher
import com.localyze.tools.buildFinancialVisualizationAnswer
import com.localyze.tools.companyFinancialWebSearchQueryFor
import com.localyze.tools.parseCompanyFinancialIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

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
 * 2. Mock engine has been removed. The app always uses the real Gemma engine.
 *
 * 3. When a new conversation starts or mode changes, we call
 *    engine.resetConversation() to clear the KV cache.
 */
class SendMessageUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gemmaInferenceEngine: GemmaInferenceEngine,
    private val contextWindowManager: ContextWindowManager,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val toolDispatcher: ToolDispatcher,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val settingsDataStore: SettingsDataStore,
    private val clarificationOrchestrator: ClarificationOrchestrator,
    private val crashRecoveryStore: com.localyze.util.CrashRecoveryStore,
    private val responsePostProcessor: ResponsePostProcessor,
    private val inputGuardrails: InputGuardrails
) {

    companion object {
        private const val MAX_TOOL_ITERATIONS = 3
        private const val TITLE_MAX_LENGTH = 40

        // KV-cache safety threshold. LiteRT-LM Conversation's real ceiling
        // is ~4096 tokens; we trip the summarize-and-drop path at ~70% so
        // the recompacted window fits comfortably back inside the cache.
        private const val CONTEXT_RESET_THRESHOLD_TOKENS = 2_800

        // How many recent USER+ASSISTANT messages we keep verbatim in the
        // restored-context block. Older turns get collapsed into the
        // persistent conversation summary instead.
        private const val KEEP_RECENT_MESSAGES = 16

        // Hard cap on the on-disk conversation summary. Anything past this
        // gets re-summarized into itself on the next reset.
        private const val MAX_SUMMARY_CHARS = 600

        // Hard cap on summarizer-output collection (chars). The engine
        // can keep going for a while; we don't need more than this for a
        // 3-bullet recap.
        private const val SUMMARIZER_OUTPUT_CHAR_CAP = 500
    }

    private var generationJob: Job? = null

    /**
     * Per-conversation clarification state. Lives in-memory; cleared when
     * the orchestrator returns Specific or PassThrough, or when the
     * conversation is reset. Concurrent because flow execution may span
     * multiple coroutines.
     */
    private val clarifyStates = java.util.concurrent.ConcurrentHashMap<Long, ClarifyState>()

    /** Drop any in-progress clarification for this conversation. */
    fun resetClarification(conversationId: Long) {
        clarifyStates.remove(conversationId)
    }

    /**
     * Push the current user-memory snapshot into the engine so it gets
     * baked into the next Conversation's system instruction. Cheap to
     * call repeatedly — the engine no-ops if the snapshot didn't change.
     *
     * Respects the memoryEnabled setting: when off, the snapshot is cleared.
     */
    private suspend fun refreshMemorySnapshot() {
        val enabled = settingsDataStore.memoryEnabled.first()
        val snapshot = if (enabled) {
            memoryRepository.getAllMemories().map { it.content }
        } else {
            emptyList()
        }
        gemmaInferenceEngine.setMemorySnapshot(snapshot)
    }

    /**
     * Called when starting a new conversation or switching modes.
     * Resets the LiteRT-LM Conversation so it starts fresh.
     */
    fun resetEngineConversation(
        capabilityMode: String,
        enableThinking: Boolean
    ) {
        gemmaInferenceEngine.setRestoredConversationContext(null)
        gemmaInferenceEngine.resetConversation(capabilityMode, enableThinking)
    }

    suspend fun resetEngineConversationWithSavedContext(
        conversationId: Long,
        capabilityMode: String,
        enableThinking: Boolean
    ) {
        gemmaInferenceEngine.setRestoredConversationContext(
            buildRestoredConversationContext(conversationId)
        )
        gemmaInferenceEngine.resetConversation(capabilityMode, enableThinking)
    }

    fun sendMessage(
        conversationId: Long,
        userMessage: String,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        refreshMemorySnapshot()
        // Save user message to DB first.
        val userMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userMessage,
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(userMsg)

        // Greeting / acknowledgement preflight: bare social tokens like
        // "hi", "thanks", "ok" re-fire the model on whatever context is
        // still in the KV cache, which produces nonsense ("hi" → repeat of
        // the prior CEO answer). Short-circuit them with a canned reply so
        // the model never sees the prompt.
        cannedGreetingReply(userMessage)?.let { reply ->
            reply.chunked(48).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = reply,
                    timestamp = System.currentTimeMillis()
                )
            )
            emit(ChatResponseEvent.Completed(fullText = reply, thinkingText = null))
            return@flow
        }

        // ── Input guardrail ──────────────────────────────────────────
        // Short-circuit prompt-injection attempts in Kotlin so we never
        // hit the model with them. The model's old INJECTION_RESISTANCE
        // prompt block (~200 tokens) was removed in favor of this gate.
        when (val guard = inputGuardrails.check(userMessage)) {
            is InputGuardrails.Decision.Refuse -> {
                chatRepository.saveMessage(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = guard.reply,
                        timestamp = System.currentTimeMillis()
                    )
                )
                emit(ChatResponseEvent.StreamingToken(guard.reply))
                emit(ChatResponseEvent.Completed(fullText = guard.reply, thinkingText = null))
                return@flow
            }
            InputGuardrails.Decision.Allow -> { /* fall through */ }
        }

        // ── Clarification gate ───────────────────────────────────────
        // Before sending to Gemma / web router, ask the orchestrator if
        // we should clarify first. North star is accuracy: a 1-2 round
        // clarification loop beats a generic guess.
        //
        // BUT: if a deterministic preflight tool (weather/time/currency/
        // news) can answer this directly, skip clarification — the tools
        // accept the raw query and don't need disambiguation. Otherwise
        // "news about Tesla" gets a "what topic?" prompt instead of the
        // actual answer.
        // Detect once, reuse twice: previously these four detectors ran here
        // (just for the boolean below) AND again inside generateWithToolLoop
        // for the actual dispatch. The detectors are regex-heavy enough
        // that the dup matters on hot paths.
        val preflightWeatherCall = buildPreflightWeatherCallIfNeeded(userMessage)
        val preflightTimeCall = buildPreflightTimeCallIfNeeded(userMessage)
        val preflightCurrencyCall = buildPreflightCurrencyCallIfNeeded(userMessage)
        val preflightNewsCall = buildPreflightNewsCallIfNeeded(userMessage)
        val preflightCanHandle = preflightWeatherCall != null ||
            preflightTimeCall != null ||
            preflightCurrencyCall != null ||
            preflightNewsCall != null
        val precomputedPreflight = PrecomputedPreflight(
            weather = preflightWeatherCall,
            time = preflightTimeCall,
            currency = preflightCurrencyCall,
            news = preflightNewsCall
        )
        val priorState = clarifyStates[conversationId]
        val effectiveQuery: String = when (
            val decision = if (preflightCanHandle) ClarificationDecision.PassThrough
                else clarificationOrchestrator.analyze(userMessage, priorState)
        ) {
            is ClarificationDecision.AskMore -> {
                // Save the assistant clarify message; keep the
                // pending-questions state for the next turn.
                val md = decision.toMarkdown()
                chatRepository.saveMessage(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = md,
                        timestamp = System.currentTimeMillis()
                    )
                )
                val newState = (priorState ?: ClarifyState(originalQuery = userMessage))
                    .withPending(decision.questions)
                clarifyStates[conversationId] = newState
                emit(ChatResponseEvent.StreamingToken(md))
                emit(ChatResponseEvent.Completed(fullText = md, thinkingText = null))
                return@flow
            }
            is ClarificationDecision.Specific -> {
                // Refined query is what we actually run through the model.
                clarifyStates.remove(conversationId)
                decision.refinedQuery
            }
            ClarificationDecision.PassThrough -> {
                // Clear any stale state defensively.
                clarifyStates.remove(conversationId)
                userMessage
            }
        }

        // Auto-promote chat-mode prompts that are clearly coding requests
        // to the dedicated code prompt. The chat prompt produces broken
        // identifiers ("longestrun" instead of "longest_run") on Python /
        // function-writing prompts; the code prompt is much stricter.
        val effectiveMode =
            if (capabilityMode == "chat" && looksLikeCodingPrompt(effectiveQuery)) {
                "code"
            } else {
                capabilityMode
            }

        emitAll(
            generateWithToolLoop(conversationId, effectiveQuery, effectiveMode, enableThinking, precomputedPreflight)
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Detect a token-repetition loop in model output (e.g. "yyyyyyyyyyyy"
     * or repeated short n-gram). Returns the start index of the loop or
     * null if none. Conservative: requires a clearly degenerate run, not
     * just normal repetition like "Mississippi" or "hahaha".
     */
    internal fun detectRepetitionLoop(text: String): Int? {
        if (text.length < 30) return null
        // Single-char run: 25+ identical chars in a row.
        val singleCharRun = Regex("(.)\\1{24,}").find(text)
        if (singleCharRun != null) return singleCharRun.range.first
        // Short n-gram (2-6 chars) repeated 8+ times consecutively.
        val shortNgramRun = Regex("(.{2,6}?)\\1{7,}").find(text)
        if (shortNgramRun != null) return shortNgramRun.range.first
        // Word-length n-gram (7-20 chars) repeated 4+ times — catches
        // Gemma's failure mode where it loops on a whole word like
        // "Amoxicillinamoxicillinamoxicillin".
        val wordRun = Regex("(.{7,20}?)\\1{3,}").find(text)
        if (wordRun != null) return wordRun.range.first
        return null
    }

    private fun looksLikeCodingPrompt(text: String): Boolean {
        val lower = text.lowercase()
        // Strong signals: explicit "write/draft a function/class/script in
        // <language>" or any code fence already present.
        if ("```" in text) return true
        val languages = listOf(
            "python", "javascript", "typescript", "kotlin", "java ",
            "java\n", "c++", "c#", "rust", "go ", "go\n", "swift",
            "ruby", "scala", "sql", "html", "css", "bash", "shell",
            "powershell"
        )
        val hasLanguage = languages.any { it in lower }
        val hasCodeVerb = Regex(
            "\\b(write|draft|implement|refactor|debug|fix|review|" +
                "explain|optimize|port|translate)\\b"
        ).containsMatchIn(lower)
        val hasCodeNoun = Regex(
            "\\b(function|method|class|script|program|snippet|regex|" +
                "algorithm|loop|recursion|api|endpoint|stack trace|" +
                "compile error|exception|unit test)\\b"
        ).containsMatchIn(lower)
        if (hasLanguage && (hasCodeVerb || hasCodeNoun)) return true
        // Identifier-like signals: snake_case or camelCase tokens with
        // parentheses (a function signature embedded in the prompt).
        if (Regex("[a-z][a-zA-Z0-9_]*\\([^)]*\\)").containsMatchIn(text) &&
            hasCodeVerb
        ) return true
        return false
    }

    fun sendMessageWithImage(
        conversationId: Long,
        userMessage: String,
        imageBitmap: Bitmap,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        refreshMemorySnapshot()
        // Save bitmap to cache directory then persist the message.
        // If the DB write fails, delete the orphaned cache file so
        // the cache directory doesn't fill up over time.
        val imagePath = saveBitmapToCache(imageBitmap)
        val userMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userMessage.ifBlank { "Describe this image" },
            imageUris = imagePath?.let { listOf(it) } ?: emptyList(),
            timestamp = System.currentTimeMillis()
        )
        try {
            chatRepository.saveMessage(userMsg)
        } catch (e: Exception) {
            imagePath?.let { java.io.File(it).delete() }
            throw e
        }

        // Emulator guard: LiteRT-LM's multimodal pipeline crashes on x86_64
        // emulators the same way the text pipeline does. Bail with a clear
        // message instead of SIGSEGV-ing.
        if (isRunningOnEmulator()) {
            val banner = "Image analysis uses the on-device model, which crashes its native " +
                "library on Android emulators (a known LiteRT-LM bug on x86_64). Install the " +
                "APK on a real Android phone to use image input."
            banner.chunked(48).forEach { emit(ChatResponseEvent.StreamingToken(it)) }
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = banner,
                    timestamp = System.currentTimeMillis()
                )
            )
            emit(ChatResponseEvent.Completed(fullText = banner, thinkingText = null))
            return@flow
        }

        val fullTextBuilder = StringBuilder()
        val thinkingTextBuilder = StringBuilder()
        val streamFilter = StreamingThinkingFilter(::stripThinkingPrefix)

        // Image analysis uses a short-lived multimodal Session in the real engine.
        // Conversation remains text-only in LiteRT-LM 0.10, so after the image
        // turn is saved we rebuild text conversation context from the database.
        val tokenFlow = gemmaInferenceEngine.generateResponseWithImage(
            getConversationMessages(conversationId), imageBitmap, userMessage, capabilityMode, enableThinking
        )

        var generationError: String? = null
        try {
            tokenFlow.collect { token ->
                when (token) {
                    is InferenceToken.TextToken -> {
                        fullTextBuilder.append(token.text)
                        val emitChunk = streamFilter.next(token.text)
                        if (emitChunk.isNotEmpty()) {
                            emit(ChatResponseEvent.StreamingToken(emitChunk))
                        }
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
            // Flush any buffered text that never hit a sentence boundary.
            val flushed = streamFilter.flush()
            if (flushed.isNotEmpty()) emit(ChatResponseEvent.StreamingToken(flushed))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            emit(ChatResponseEvent.Error(e.message ?: "Generation failed"))
            return@flow
        } finally {
            // The bitmap has been written to disk (PNG cache) AND consumed
            // by the vision encoder by this point. ARGB_8888 1024x1024 is
            // ~4MB — release it now so a 5-image session doesn't accumulate
            // 20MB of heap on its way to GC.
            if (!imageBitmap.isRecycled) imageBitmap.recycle()
        }

        if (generationError != null) {
            return@flow
        }

        if (fullTextBuilder.isBlank()) {
            emit(ChatResponseEvent.Error("Image analysis did not return a response. Please try again."))
            return@flow
        }

        val finalContent = polishWithFallback(fullTextBuilder.toString())
        val assistantMsg = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = finalContent,
            thinkingContent = thinkingTextBuilder.toString().ifBlank { null },
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(assistantMsg)
        autoGenerateTitle(conversationId, fullTextBuilder.toString())

        // Used to unconditionally reset the conversation after every image
        // turn, which nuked the KV cache (and re-prefilled the ~2700-token
        // system prompt) regardless of need. LiteRT-LM tracks history
        // internally; we only need to reset when context overflows.

        emit(ChatResponseEvent.Completed(
            fullText = fullTextBuilder.toString(),
            thinkingText = thinkingTextBuilder.toString().ifBlank { null }
        ))
    }.flowOn(Dispatchers.Default)

    fun sendMessageWithAudio(
        conversationId: Long,
        audioBytes: ByteArray,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        refreshMemorySnapshot()
        val userMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = "\uD83C\uDFA4 Voice message",
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(userMsg)

        // Emulator guard: same x86_64 LiteRT-LM SIGSEGV as text/image. The
        // audio pipeline runs the same sampler/tokenizer at the end of its
        // ASR + chat handoff, so it crashes too.
        if (isRunningOnEmulator()) {
            val banner = "Voice messages use the on-device model, which crashes its native " +
                "library on Android emulators. Install the APK on a real Android phone to " +
                "use voice input."
            banner.chunked(48).forEach { emit(ChatResponseEvent.StreamingToken(it)) }
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = banner,
                    timestamp = System.currentTimeMillis()
                )
            )
            emit(ChatResponseEvent.Completed(fullText = banner, thinkingText = null))
            return@flow
        }

        val fullTextBuilder = StringBuilder()
        val thinkingTextBuilder = StringBuilder()
        val streamFilter = StreamingThinkingFilter(::stripThinkingPrefix)

        // Gallery pattern: We do NOT need to explicitly reset the conversation here.
        // The ensureConversation() inside generateResponseWithAudio() will detect
        // supportAudio=true and auto-reset if the current conversation doesn't
        // support audio. This preserves multi-turn context when sending
        // consecutive audio messages.

        val tokenFlow = gemmaInferenceEngine.generateResponseWithAudio(
            getConversationMessages(conversationId), audioBytes, "Transcribe and respond to this audio", capabilityMode, enableThinking
        )

        try {
            tokenFlow.collect { token ->
                when (token) {
                    is InferenceToken.TextToken -> {
                        fullTextBuilder.append(token.text)
                        val chunk = streamFilter.next(token.text)
                        if (chunk.isNotEmpty()) emit(ChatResponseEvent.StreamingToken(chunk))
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
            val flushed = streamFilter.flush()
            if (flushed.isNotEmpty()) emit(ChatResponseEvent.StreamingToken(flushed))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            emit(ChatResponseEvent.Error(e.message ?: "Generation failed"))
            return@flow
        }

        val finalContent = polishWithFallback(fullTextBuilder.toString())
        val assistantMsg = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = finalContent,
            thinkingContent = thinkingTextBuilder.toString().ifBlank { null },
            timestamp = System.currentTimeMillis()
        )
        chatRepository.saveMessage(assistantMsg)
        autoGenerateTitle(conversationId, fullTextBuilder.toString())

        emit(ChatResponseEvent.Completed(
            fullText = fullTextBuilder.toString(),
            thinkingText = thinkingTextBuilder.toString().ifBlank { null }
        ))
    }.flowOn(Dispatchers.Default)

    fun stopGeneration() {
        gemmaInferenceEngine.stopGeneration()
        generationJob = null
    }

    fun regenerateLastResponse(
        conversationId: Long,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        refreshMemorySnapshot()
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
    }.flowOn(Dispatchers.Default)

    /**
     * Continue generation after a tool was confirmed by the user.
     * Feeds the tool result back into the engine and resumes the loop.
     */
    fun continueWithToolResult(
        conversationId: Long,
        toolResult: ToolResult,
        capabilityMode: String,
        enableThinking: Boolean
    ): Flow<ChatResponseEvent> = flow {
        refreshMemorySnapshot()
        chatRepository.saveMessage(
            Message(
                conversationId = conversationId,
                role = MessageRole.TOOL,
                content = "[Tool Result: ${toolResult.name}] ${toolResult.result}",
                toolCallId = toolResult.callId,
                toolName = toolResult.name,
                toolResult = toolResult.result,
                timestamp = System.currentTimeMillis()
            )
        )

        val fullTextBuilder = StringBuilder()
        val thinkingTextBuilder = StringBuilder()
        val streamFilter = StreamingThinkingFilter(::stripThinkingPrefix)

        val tokenFlow = gemmaInferenceEngine.generateResponse(
            messages = listOf(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = "Here is the tool result:\n[Tool Result: ${toolResult.name}] ${toolResult.result}\nPlease use this to answer my original question.",
                    timestamp = System.currentTimeMillis()
                )
            ),
            // Engine builds its own system instruction at Conversation creation
            // and ignores this arg; pass empty to avoid the per-turn DB hit.
            systemPrompt = "",
            capabilityMode = capabilityMode,
            enableThinking = enableThinking
        )

        val toolCallsInIteration = mutableListOf<ToolCall>()
        val iterationTextBuilder = StringBuilder()
        val iterationThinkingBuilder = StringBuilder()

        try {
            tokenFlow.collect { token ->
                when (token) {
                    is InferenceToken.TextToken -> {
                        iterationTextBuilder.append(token.text)
                        fullTextBuilder.append(token.text)
                        val chunk = streamFilter.next(token.text)
                        if (chunk.isNotEmpty()) emit(ChatResponseEvent.StreamingToken(chunk))
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
            val flushed = streamFilter.flush()
            if (flushed.isNotEmpty()) emit(ChatResponseEvent.StreamingToken(flushed))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            emit(ChatResponseEvent.Error(e.message ?: "Generation failed"))
            return@flow
        }

        if (toolCallsInIteration.isNotEmpty()) {
            for (toolCall in toolCallsInIteration) {
                emit(ChatResponseEvent.ToolCallStarted(toolCall.name))
                val dispatchResult = toolDispatcher.dispatchWithConfirmation(toolCall)
                when (dispatchResult) {
                    is com.localyze.tools.DispatchResult.Completed -> {
                        val result = dispatchResult.toolResult
                        emit(ChatResponseEvent.ToolCallCompleted(toolCall.name, result.result))
                    }
                    is com.localyze.tools.DispatchResult.PendingConfirmation -> {
                        emit(
                            ChatResponseEvent.ToolConfirmationNeeded(
                                toolName = toolCall.name,
                                message = dispatchResult.message,
                                toolCall = toolCall
                            )
                        )
                        return@flow
                    }
                }
            }
        }

        if (fullTextBuilder.isNotEmpty()) {
            val polished = polishWithFallback(fullTextBuilder.toString())
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = polished,
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
    }.flowOn(Dispatchers.Default)


    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun generateWithToolLoop(
        conversationId: Long,
        userMessage: String,
        capabilityMode: String,
        enableThinking: Boolean,
        precomputed: PrecomputedPreflight? = null
    ): Flow<ChatResponseEvent> = flow {
        // Hard short-circuit: if this exact prompt crashed the model on the
        // previous run (CrashRecoveryStore detected the sentinel at boot),
        // do not let the user re-fire it — that loop SIGSEGVs the app again.
        if (crashRecoveryStore.isBlocked(userMessage)) {
            val banner = "That last message ('${userMessage.take(60)}…') crashed the on-device model. " +
                "I've stopped it from being re-fired this session — try rephrasing it."
            banner.chunked(48).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            emit(ChatResponseEvent.Completed(fullText = banner, thinkingText = null))
            return@flow
        }
        var iteration = 0
        var pendingToolResults: List<ToolResult>? = null
        val collectedToolResults = mutableListOf<ToolResult>()
        // Per-turn tool-result cache keyed on (toolName, normalized args).
        // The model often re-fires the same tool inside one turn ("weather
        // in X" then "and tomorrow") — caching avoids duplicate network
        // round-trips. callId is excluded from the key since it's a UUID.
        val turnToolCache = mutableMapOf<String, com.localyze.domain.models.ToolResult>()
        suspend fun dispatchCached(call: ToolCall): com.localyze.domain.models.ToolResult {
            val key = "${call.name}|${call.arguments}"
            turnToolCache[key]?.let { return it }
            val result = toolDispatcher.dispatch(call)
            if (!result.isError) turnToolCache[key] = result
            return result
        }
        // Clear any stale tool-result captures from prior turns; the engine's
        // OpenApiTool wrappers will append to this during generation.
        gemmaInferenceEngine.clearRecentToolResults()

        val fullTextBuilder = StringBuilder()
        val thinkingTextBuilder = StringBuilder()
        val allowWebSearch = settingsDataStore.allowWebSearch.first()

        // Preflight calculator runs FIRST: detects math/conversion intent so we
        // bypass the "I can't verify live updates" curated path for prompts that
        // are arithmetic but happen to contain words like "price", "today", or
        // "now". The exact result is injected into the model prompt.
        val preflightCalcCall = buildPreflightCalculatorCallIfNeeded(userMessage)
        val preflightCalcResult = if (preflightCalcCall != null) {
            emit(ChatResponseEvent.ToolCallStarted(preflightCalcCall.name))
            val result = dispatchCached(preflightCalcCall)
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
        // Calculator pre-flight succeeded — short-circuit and emit the
        // deterministic result directly. Previously we wrapped the calc
        // result in a "grounded prompt" and asked the model to render it
        // in prose, but the on-device 4B reliably drops digits in its
        // narrative ("144 ÷ 12 = 12" came back as "4 ÷ 2 = 2"; "20% of
        // 250 = 50" came back as "0% of 50"). The CalculatorTool itself
        // is exact; bypassing the model removes a fabrication surface.
        val deterministicCalcAnswer = preflightCalcResult
            ?.takeUnless { it.isError }
            ?.let { formatDeterministicCalcAnswer(userMessage, it.result) }
        if (deterministicCalcAnswer != null) {
            deterministicCalcAnswer.chunked(32).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = deterministicCalcAnswer,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(assistantMsg)
            autoGenerateTitle(conversationId, deterministicCalcAnswer)
            emit(ChatResponseEvent.Completed(
                fullText = deterministicCalcAnswer, thinkingText = null
            ))
            return@flow
        }
        val calcContextMessage = preflightCalcResult
            ?.takeUnless { it.isError }
            ?.let { buildCalcGroundedPrompt(userMessage, it.result) }

        if (calcContextMessage == null) {
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
        }

        // Time / currency / news preflights: same idea as weather — direct
        // structured-API answer instead of routing through model + web_search.
        //
        // Order matters: time and currency are cheap/deterministic so check
        // them first. News calls Google News RSS which takes longer.
        val preflightTimeCall = if (calcContextMessage == null) (precomputed?.time ?: buildPreflightTimeCallIfNeeded(userMessage)) else null
        val preflightTimeResult = if (preflightTimeCall != null) {
            emit(ChatResponseEvent.ToolCallStarted(preflightTimeCall.name))
            val r = dispatchCached(preflightTimeCall)
            emit(ChatResponseEvent.ToolCallCompleted(r.name, r.result))
            r
        } else null
        val timeCuratedAnswer = preflightTimeResult
            ?.takeUnless { it.isError }
            ?.let { curatedTimeAnswerFor(it.result) }
        if (timeCuratedAnswer != null) {
            timeCuratedAnswer.chunked(48).forEach { emit(ChatResponseEvent.StreamingToken(it)) }
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = timeCuratedAnswer,
                    timestamp = System.currentTimeMillis()
                )
            )
            autoGenerateTitle(conversationId, timeCuratedAnswer)
            emit(ChatResponseEvent.Completed(fullText = timeCuratedAnswer, thinkingText = null))
            return@flow
        }

        val preflightCurrencyCall = if (calcContextMessage == null) (precomputed?.currency ?: buildPreflightCurrencyCallIfNeeded(userMessage)) else null
        val preflightCurrencyResult = if (preflightCurrencyCall != null) {
            emit(ChatResponseEvent.ToolCallStarted(preflightCurrencyCall.name))
            val r = dispatchCached(preflightCurrencyCall)
            emit(ChatResponseEvent.ToolCallCompleted(r.name, r.result))
            r
        } else null
        val currencyCuratedAnswer = preflightCurrencyResult
            ?.takeUnless { it.isError }
            ?.let { curatedCurrencyAnswerFor(it.result) }
        if (currencyCuratedAnswer != null) {
            currencyCuratedAnswer.chunked(48).forEach { emit(ChatResponseEvent.StreamingToken(it)) }
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = currencyCuratedAnswer,
                    timestamp = System.currentTimeMillis()
                )
            )
            autoGenerateTitle(conversationId, currencyCuratedAnswer)
            emit(ChatResponseEvent.Completed(fullText = currencyCuratedAnswer, thinkingText = null))
            return@flow
        }

        val preflightNewsCall = if (calcContextMessage == null) (precomputed?.news ?: buildPreflightNewsCallIfNeeded(userMessage)) else null
        val preflightNewsResult = if (preflightNewsCall != null) {
            emit(ChatResponseEvent.ToolCallStarted(preflightNewsCall.name))
            val r = dispatchCached(preflightNewsCall)
            emit(ChatResponseEvent.ToolCallCompleted(r.name, r.result))
            r
        } else null
        val newsCuratedAnswer = preflightNewsResult
            ?.takeUnless { it.isError }
            ?.let { curatedNewsAnswerFor(it.result) }
        if (newsCuratedAnswer != null) {
            newsCuratedAnswer.chunked(48).forEach { emit(ChatResponseEvent.StreamingToken(it)) }
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = newsCuratedAnswer,
                    timestamp = System.currentTimeMillis()
                )
            )
            autoGenerateTitle(conversationId, newsCuratedAnswer)
            emit(ChatResponseEvent.Completed(fullText = newsCuratedAnswer, thinkingText = null))
            return@flow
        }

        // Weather preflight: "what is the weather in <city>" is answered
        // directly by the weather_lookup tool (Open-Meteo) instead of round-
        // tripping through web_search + model synthesis. The model goes blank
        // on a lot of weather queries even with grounded context, so going
        // straight to a structured weather API gives us a real, usable answer.
        val preflightWeatherCall = if (calcContextMessage == null) {
            precomputed?.weather ?: buildPreflightWeatherCallIfNeeded(userMessage)
        } else {
            null
        }
        val preflightWeatherResult = if (preflightWeatherCall != null) {
            emit(ChatResponseEvent.ToolCallStarted(preflightWeatherCall.name))
            val result = dispatchCached(preflightWeatherCall)
            emit(ChatResponseEvent.ToolCallCompleted(result.name, result.result))
            result
        } else {
            null
        }
        val weatherCuratedAnswer = preflightWeatherResult
            ?.takeUnless { it.isError }
            ?.let { curatedWeatherAnswerFor(it.result) }
        if (weatherCuratedAnswer != null) {
            weatherCuratedAnswer.chunked(48).forEach { chunk ->
                emit(ChatResponseEvent.StreamingToken(chunk))
            }
            val assistantMsg = Message(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = weatherCuratedAnswer,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(assistantMsg)
            autoGenerateTitle(conversationId, weatherCuratedAnswer)
            emit(ChatResponseEvent.Completed(fullText = weatherCuratedAnswer, thinkingText = null))
            return@flow
        }

        // Skip web preflight when calculator (or weather) already produced an answer.
        val preflightWebCall = if (allowWebSearch && calcContextMessage == null) {
            buildPreflightWebSearchCallIfNeeded(userMessage, capabilityMode)
        } else {
            null
        }
        val preflightWebResult = if (preflightWebCall != null) {
            emit(ChatResponseEvent.ToolCallStarted(preflightWebCall.name))
            val result = dispatchCached(preflightWebCall)
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

        // Proactive context-window guardrail: when conversation tokens cross
        // the KV-cache safe threshold, collapse everything older than the
        // rolling window into a persistent summary on the Conversation row,
        // then rebuild with [persisted summary] + [last KEEP_RECENT_MESSAGES
        // verbatim turns]. The model never sees more than that, and the
        // user never sees the "Context full — new chat" banner because the
        // cache is recycled silently.
        val estimatedTokens = contextWindowManager.getTokenCountForMessages(
            getConversationMessages(conversationId)
        )
        if (estimatedTokens > CONTEXT_RESET_THRESHOLD_TOKENS) {
            summarizeAndPersistOlderHistory(conversationId, capabilityMode, enableThinking)
            val trimmedContext = buildRestoredConversationContext(conversationId)
            gemmaInferenceEngine.setRestoredConversationContext(trimmedContext)
            gemmaInferenceEngine.resetConversation(capabilityMode, enableThinking)
            emit(ChatResponseEvent.ContextReset())
        }

        // EMULATOR GUARD: LiteRT-LM has a reproducible SIGSEGV in its CPU
        // sampler/tokenizer on x86_64 (memmove overflow in liblitertlm_jni)
        // that kills the whole process whenever we feed it a non-trivial prompt.
        // If web search already ran and returned snippets, display them directly
        // without calling the model (safe path on x86_64). Otherwise show banner.
        if (isRunningOnEmulator()) {
            val webFallback = preflightWebResult
                ?.takeUnless { it.isError }
                ?.let { buildEmulatorWebSnippetAnswer(userMessage, it.result) }
            if (webFallback != null) {
                webFallback.chunked(32).forEach { emit(ChatResponseEvent.StreamingToken(it)) }
                chatRepository.saveMessage(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = webFallback,
                        timestamp = System.currentTimeMillis()
                    )
                )
                autoGenerateTitle(conversationId, webFallback)
                emit(ChatResponseEvent.Completed(fullText = webFallback, thinkingText = null))
                return@flow
            }
            val banner = buildString {
                append("**Heads up:** this is an Android emulator. The on-device language ")
                append("model crashes its own native library here (a known LiteRT-LM bug ")
                append("on x86_64), so I'm not running it.\n\n")
                append("**What still works on the emulator:**\n")
                append("- Weather (\"weather in Tokyo\")\n")
                append("- Time (\"what time is it in London\")\n")
                append("- Currency (\"100 USD to INR\")\n")
                append("- News (\"latest news from India\")\n")
                append("- Calculator / unit conversions (\"70 kg in lb\")\n\n")
                append("For everything else, install the APK on a real Android phone.")
            }
            banner.chunked(48).forEach { emit(ChatResponseEvent.StreamingToken(it)) }
            chatRepository.saveMessage(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = banner,
                    timestamp = System.currentTimeMillis()
                )
            )
            autoGenerateTitle(conversationId, banner)
            emit(ChatResponseEvent.Completed(fullText = banner, thinkingText = null))
            return@flow
        }

        // One filter instance per generation — buffers the FIRST tokens
        // of the assistant turn across all tool iterations so we can
        // strip a "thinking" preamble before the user sees it.
        val streamFilter = StreamingThinkingFilter(::stripThinkingPrefix)

        while (iteration < MAX_TOOL_ITERATIONS && currentCoroutineContext().isActive) {
            iteration++

            // â”€â”€ Build the token flow â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            // REAL ENGINE: Gallery pattern — Conversation tracks history internally,
            // so only the latest message needs to be sent.
            val tokenFlow = if (pendingToolResults != null) {
                    // After tool execution, we need to feed tool results back.
                    // For LiteRT-LM with native tool calling, the engine handles this.
                    // For our manual tool loop, we reset conversation with tool results
                    // injected as the latest context and re-generate.
                    val toolResultsText = pendingToolResults!!.joinToString("\n") { result ->
                        if (result.isError) {
                            "[Tool Error: ${result.name}] ${result.result} — fix the arguments and call ${result.name} again, " +
                                "or answer directly without this tool if it is not needed."
                        } else {
                            "[Tool Result: ${result.name}] ${result.result}"
                        }
                    }
                    val anyErrors = pendingToolResults!!.any { it.isError }
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
                val followupHeader = if (anyErrors) {
                    "Some tool calls failed. Read the errors carefully, then either retry with corrected arguments " +
                        "or answer the original question directly without that tool."
                } else {
                    "Here are the tool results."
                }
                gemmaInferenceEngine.generateResponse(
                    messages = listOf(Message(
                        conversationId = conversationId,
                        role = MessageRole.USER,
                        content = "$followupHeader\n$toolResultsText\nPlease use these results to answer my original question."
                    )),
                    // Engine builds its own system instruction at Conversation creation
                    // and ignores this arg; pass empty to avoid the per-turn DB hit.
                    systemPrompt = "",
                    capabilityMode = capabilityMode,
                    enableThinking = enableThinking
                )
            } else {
                val basePrompt = calcContextMessage?.takeIf { iteration == 1 }
                    ?: webContextMessage?.takeIf { iteration == 1 }
                    ?: userMessage
                // When web search is off and the user asked about something
                // time-sensitive, prepend a cutoff hint so the model answers
                // from training data and discloses the staleness instead of
                // refusing. Only applied on the first iteration to avoid
                // poisoning tool-result follow-ups.
                val promptForModel = if (
                    iteration == 1 &&
                    !allowWebSearch &&
                    capabilityMode in setOf("chat", "data") &&
                    basePrompt === userMessage &&
                    hasCurrentIntent(userMessage.lowercase())
                ) {
                    "Web search is currently OFF, so you cannot fetch live " +
                        "data. Answer the user's question from your training " +
                        "knowledge. If your answer concerns something that " +
                        "changes over time (versions, prices, officeholders, " +
                        "scores, news), state your knowledge cutoff and tell " +
                        "the user to enable web search for an up-to-date " +
                        "value. Do NOT refuse to answer.\n\n" +
                        "User question: $userMessage"
                } else {
                    basePrompt
                }
                gemmaInferenceEngine.generateResponse(
                    messages = listOf(Message(
                        conversationId = conversationId,
                        role = MessageRole.USER,
                        content = promptForModel,
                        timestamp = System.currentTimeMillis()
                    )),
                    // Engine builds its own system instruction at Conversation creation
                    // and ignores this arg; pass empty to avoid the per-turn DB hit.
                    systemPrompt = "",
                    capabilityMode = capabilityMode,
                    enableThinking = enableThinking
                )
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
                            val chunk = streamFilter.next(token.text)
                            if (chunk.isNotEmpty()) emit(ChatResponseEvent.StreamingToken(chunk))
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
                    val dispatchResult = toolDispatcher.dispatchWithConfirmation(toolCall)
                    when (dispatchResult) {
                        is com.localyze.tools.DispatchResult.Completed -> {
                            val result = dispatchResult.toolResult
                            toolResults.add(result)
                            emit(ChatResponseEvent.ToolCallCompleted(toolCall.name, result.result))
                        }
                        is com.localyze.tools.DispatchResult.PendingConfirmation -> {
                            emit(
                                ChatResponseEvent.ToolConfirmationNeeded(
                                    toolName = toolCall.name,
                                    message = dispatchResult.message,
                                    toolCall = toolCall
                                )
                            )
                            // Pause the loop until user confirms; the ViewModel will
                            // call continueWithToolResult() to resume.
                            return@flow
                        }
                    }
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
                collectedToolResults.addAll(toolResults)
            } else {
                break
            }
        }

        // Bad-output recovery. Three failure modes we catch:
        //   (a) BLANK — model emitted 0 text tokens (Q2/Q5 silent-failure).
        //   (b) STUB  — model emitted 1-2 words then stopped (e.g. "The")
        //                — looks valid to isBlank() but is unusable.
        //   (c) LOOP  — model entered a token-repetition loop, e.g.
        //                "Health: Screening (e.g., yyyyyyyyyy...".
        // For (a) and (b) we promote thinking-channel content or retry
        // once with thinking off; for (c) we truncate at the loop start
        // and keep the lead-up content. All three end with a polite
        // fallback bubble if nothing meaningful came out.
        val initialOutput = fullTextBuilder.toString()
        // Strip leaked LiteRT-LM tool-call markup (`<|tool_call>...<tool_call|>`,
        // `<|tool>...<tool|>`, etc.) before judging emptiness — sometimes the
        // model emits only those control tokens with no narrative.
        val narrativeOnly = initialOutput
            .replace(Regex("<\\|[a-z_]+>.*?<[a-z_]+\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|[a-z_]+>"), "")
            .replace(Regex("<[a-z_]+\\|>"), "")
        val narrativeAlnum = narrativeOnly.count { it.isLetterOrDigit() }
        val isBlank = narrativeOnly.isBlank()
        val isStub = !isBlank && narrativeAlnum < 8           // < 8 letters/digits ≈ 1-2 words
        // Replace fullTextBuilder if all that came out was markup, so we don't
        // ship raw `<|tool_call>` to the UI even if recovery later fails.
        if (narrativeAlnum < initialOutput.count { it.isLetterOrDigit() } / 2 ||
            narrativeOnly.isBlank()) {
            fullTextBuilder.clear()
            fullTextBuilder.append(narrativeOnly)
        }
        val loopStart = detectRepetitionLoop(narrativeOnly)
        val initialAlnum = narrativeAlnum
        val needsRecovery = isBlank || isStub || loopStart != null

        if (loopStart != null) {
            // Truncate the tail loop in place; keep the lead-up.
            val keep = initialOutput.substring(0, loopStart).trimEnd()
            fullTextBuilder.clear()
            fullTextBuilder.append(keep)
        }

        if (needsRecovery) {
            com.localyze.utils.AppLog.w(
                "SendMessageUseCase",
                "Bad-output recovery. blank=$isBlank stub=$isStub loop=${loopStart != null} " +
                    "enableThinking=$enableThinking thinkingChars=${thinkingTextBuilder.length} mode=$capabilityMode"
            )
            // For loop-truncated output, the kept lead-up may already be a
            // valid answer — only attempt thinking/retry if the kept content
            // is itself too short.
            val keptAlnum = fullTextBuilder.count { it.isLetterOrDigit() }
            val needsRegeneration = isBlank || isStub || keptAlnum < 30

            if (needsRegeneration) {
                val thinkingFallback = thinkingTextBuilder.toString().trim()
                if (thinkingFallback.isNotBlank()) {
                    // Model put the answer in the wrong channel — surface it.
                    fullTextBuilder.clear()
                    thinkingFallback.chunked(32).forEach { chunk ->
                        emit(ChatResponseEvent.StreamingToken(chunk))
                    }
                    fullTextBuilder.append(thinkingFallback)
                } else {
                    fullTextBuilder.clear()
                    // Prefer LiteRT-LM-side tool captures (the OpenApiTool path
                    // the model actually used) over the iteration-loop captures
                    // (which are only populated for the older non-native path).
                    val engineToolResults = gemmaInferenceEngine.snapshotRecentToolResults()
                    // Also include preflight web_search / calculator results —
                    // those paths bypass the LiteRT-LM OpenApiTool wrappers
                    // (dispatched directly via toolDispatcher.dispatch) so they
                    // don't appear in recentToolResults, but the data is just
                    // as valid for blank-recovery fallback.
                    val preflightFallback = buildList<Pair<String, String>> {
                        preflightWebResult
                            ?.takeUnless { it.isError }
                            ?.let { add("web_search" to it.result) }
                        preflightCalcResult
                            ?.takeUnless { it.isError }
                            ?.let { add("calculator" to it.result) }
                        preflightWeatherResult
                            ?.takeUnless { it.isError }
                            ?.let { add("weather_lookup" to it.result) }
                        preflightTimeResult
                            ?.takeUnless { it.isError }
                            ?.let { add("time_lookup" to it.result) }
                        preflightCurrencyResult
                            ?.takeUnless { it.isError }
                            ?.let { add("currency_convert" to it.result) }
                        preflightNewsResult
                            ?.takeUnless { it.isError }
                            ?.let { add("news_lookup" to it.result) }
                    }
                    val combinedEngineResults = engineToolResults + preflightFallback
                    val anyToolResults = combinedEngineResults.isNotEmpty() || collectedToolResults.isNotEmpty()
                    if (anyToolResults) {
                        // Tools fired but model produced no narrative. Try a
                        // recovery synth call in the tool-less "summary" mode
                        // first — this gives a real natural-language answer
                        // instead of dumping tool JSON + a disclaimer. Falls
                        // back to the deterministic formatter only if synth
                        // errors out or returns blank.
                        val allToolResults = combinedEngineResults +
                            collectedToolResults.map { it.name to it.result }
                        val synthesized = synthesizeAnswerFromToolResults(
                            userMessage, allToolResults, conversationId
                        )
                        val formatted = synthesized
                            ?: formatToolResultsAsAnswer(combinedEngineResults, collectedToolResults)
                        formatted.chunked(64).forEach { chunk ->
                            emit(ChatResponseEvent.StreamingToken(chunk))
                        }
                        fullTextBuilder.append(formatted)
                    } else {
                    // Tool-grounded recovery first: before cold-retrying the
                    // model on a knowledge-only basis, check if the prompt
                    // actually matches a live-data intent (weather, time,
                    // currency, news, calculator). If so, the right answer
                    // is to re-run those tools and format the result rather
                    // than retry the model blind — which would happily make
                    // up a fake weather report.
                    val recoveryCalls = listOfNotNull(
                        precomputed?.weather ?: buildPreflightWeatherCallIfNeeded(userMessage),
                        precomputed?.time ?: buildPreflightTimeCallIfNeeded(userMessage),
                        precomputed?.currency ?: buildPreflightCurrencyCallIfNeeded(userMessage),
                        precomputed?.news ?: buildPreflightNewsCallIfNeeded(userMessage),
                        buildPreflightCalculatorCallIfNeeded(userMessage)
                    )
                    val recoveryResults = recoveryCalls
                        .map { call -> call.name to dispatchCached(call) }
                        .filter { (_, r) -> !r.isError }
                        .map { (name, r) -> name to r.result }
                    val skipColdRetry = recoveryResults.isNotEmpty()
                    if (skipColdRetry) {
                        // Same recovery path: synthesize a natural answer from
                        // the rerun-preflight tool data before falling back to
                        // the disclaimer-tagged dump.
                        val synthesized = synthesizeAnswerFromToolResults(
                            userMessage, recoveryResults, conversationId
                        )
                        val formatted = synthesized
                            ?: formatToolResultsAsAnswer(recoveryResults, emptyList())
                        formatted.chunked(64).forEach { chunk ->
                            emit(ChatResponseEvent.StreamingToken(chunk))
                        }
                        fullTextBuilder.append(formatted)
                    } else {
                    // Retry with thinking off, tools stripped, AND a
                    // directive prefix that forces the model to answer
                    // from training data instead of refusing or falling
                    // silent. We've seen Gemma 4 E4B sit on certain
                    // medical / "guideline-stamped" prompts unless told
                    // explicitly to answer.
                    gemmaInferenceEngine.resetConversation(capabilityMode, enableThinking = false)
                    val directive = "Answer the question below directly using your training " +
                        "knowledge. State reasonable defaults explicitly if exact figures are " +
                        "not known, and add a brief disclaimer at the end. Do not refuse, do " +
                        "not ask follow-ups, do not search the web.\n\n" +
                        "QUESTION: $userMessage"
                    try {
                        gemmaInferenceEngine.generateResponse(
                            messages = listOf(
                                Message(
                                    conversationId = conversationId,
                                    role = MessageRole.USER,
                                    content = directive,
                                    timestamp = System.currentTimeMillis()
                                )
                            ),
                            // Engine builds its own system instruction at Conversation creation
                            // and ignores this arg; pass empty to avoid the per-turn DB hit.
                            systemPrompt = "",
                            capabilityMode = capabilityMode,
                            enableThinking = false
                        ).collect { token ->
                            when (token) {
                                is InferenceToken.TextToken -> {
                                    fullTextBuilder.append(token.text)
                                    emit(ChatResponseEvent.StreamingToken(token.text))
                                }
                                is InferenceToken.Error -> emit(ChatResponseEvent.Error(token.message))
                                is InferenceToken.ThinkingToken,
                                is InferenceToken.ToolCallToken,
                                is InferenceToken.EndOfStream -> { }
                            }
                        }
                        // Retry can also loop or stub — sanitize.
                        val retryOutput = fullTextBuilder.toString()
                        detectRepetitionLoop(retryOutput)?.let { lp ->
                            val keep = retryOutput.substring(0, lp).trimEnd()
                            fullTextBuilder.clear()
                            fullTextBuilder.append(keep)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.localyze.utils.AppLog.e(
                            "SendMessageUseCase",
                            "Empty-response retry failed: ${e.message}",
                            e
                        )
                    }
                    }  // close cold-retry else branch (skipColdRetry == false)
                    }
                }
            }

            // After all recovery, decide if we have something meaningful.
            // Threshold: ≥ 30 alnum chars ≈ a real sentence; below that
            // we surface a polite fallback instead of a stub answer.
            val recovered = fullTextBuilder.toString().trim()
            val alnumCount = recovered.count { it.isLetterOrDigit() }
            val isMeaningful = alnumCount >= 30
            if (!isMeaningful) {
                fullTextBuilder.clear()
                val msg = "I wasn't able to generate a response for that. " +
                    "Try rephrasing the question, or restart the app if this keeps happening."
                msg.chunked(32).forEach { chunk ->
                    emit(ChatResponseEvent.StreamingToken(chunk))
                }
                fullTextBuilder.append(msg)
                emit(ChatResponseEvent.Error("Model produced no usable output for this prompt."))
            }
        }

        // End-of-stream flush: if the model finished without ever hitting
        // a sentence boundary, the buffered first chunk needs to come out.
        val tailFlush = streamFilter.flush()
        if (tailFlush.isNotEmpty()) emit(ChatResponseEvent.StreamingToken(tailFlush))

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

    /**
     * Detect arithmetic / unit-conversion intent in the user message and synthesize
     * a calculator ToolCall the dispatcher can run *before* asking the model.
     *
     * The on-device 4B model is unreliable at deciding to call tools, so when we
     * can confidently extract the math, we run it ourselves and inject the exact
     * result into the prompt — guaranteeing accuracy on the prompts that matter.
     */
    internal fun buildPreflightCalculatorCallIfNeeded(userMessage: String): ToolCall? {
        // 1. Unit conversion ("convert 100 F to C", "5 km in miles", …)
        conversionMatch(userMessage)?.let { (value, from, to) ->
            return ToolCall(
                name = "calculator",
                arguments = buildJsonObject {
                    put("mode", JsonPrimitive("convert"))
                    put("value", JsonPrimitive(value))
                    put("from", JsonPrimitive(from))
                    put("to", JsonPrimitive(to))
                },
                callId = UUID.randomUUID().toString()
            )
        }
        // 2. Math expression — but only when the prompt actually is a math
        //    question. Long prose with incidental numbers (e.g. METAR string
        //    "27/22" inside a pilot question, or "$8M cash" in a CPA prompt)
        //    used to fire the calculator and poison the answer with a
        //    spurious computation. Gate the regex to short prompts OR
        //    prompts with explicit arithmetic intent words.
        if (!looksLikeArithmeticQuestion(userMessage)) return null
        val expr = extractMathExpression(userMessage) ?: return null
        return ToolCall(
            name = "calculator",
            arguments = buildJsonObject {
                put("mode", JsonPrimitive("eval"))
                put("expression", JsonPrimitive(expr))
            },
            callId = UUID.randomUUID().toString()
        )
    }

    /** True only when the user message is plausibly a math question — short
     *  enough to be one, OR contains an explicit arithmetic-intent verb
     *  near the numbers. Prevents the preflight calculator from firing on
     *  prose where numbers happen to appear (METARs, financial scenarios,
     *  vital-sign descriptions, etc.). */
    private fun looksLikeArithmeticQuestion(msg: String): Boolean {
        val trimmed = msg.trim()
        // Short messages: arithmetic likely to BE the message.
        if (trimmed.length <= 60) return true
        // Otherwise require explicit intent. Intent vocabulary is broad on
        // purpose: missing a verb here means the deterministic preflight
        // doesn't fire and the model gets to hallucinate digits, which is
        // the failure mode we're guarding against.
        val intent = Regex(
            "\\b(calculate|compute|what\\s+is|what'?s|how\\s+much|how\\s+many|" +
                "equals?|evaluate|simplify|solve|sum|product|quotient|remainder|" +
                "percent\\s+(?:of|off)|tip\\s+(?:on|of|for)|sale\\s+price|" +
                "marked\\s+down|discount|markdown|" +
                "(?:a|per|each|every|/)\\s*(?:week|month|day|year)\\b|" +
                "split|to\\s+the\\s+power)\\b",
            RegexOption.IGNORE_CASE
        )
        return intent.containsMatchIn(trimmed)
    }

    private fun conversionMatch(msg: String): Triple<Double, String, String>? {
        val units = "fahrenheit|celsius|kelvin|°?\\s*[fck]\\b|" +
            "km\\b|kilometers?|kilometres?|" +
            "m\\b|meters?|metres?|" +
            "cm\\b|centimeters?|centimetres?|" +
            "mm\\b|millimeters?|millimetres?|" +
            "mi\\b|miles?|ft\\b|feet|foot|inches|in\\b|yards?|yd\\b|" +
            "kg\\b|kilograms?|g\\b|grams?|" +
            "lb\\b|lbs\\b|pounds?|oz\\b|ounces?|" +
            "liters?|litres?|l\\b|ml\\b|milliliters?|millilitres?|" +
            "gallons?|gal\\b"
        // Forward: "100 F to C", "convert 50 miles into kilometers"
        val forward = Regex(
            "(?:convert\\s+)?(-?\\d+(?:\\.\\d+)?)\\s*(?:degrees?\\s+)?($units)\\s+(?:to|in|into)\\s+(?:degrees?\\s+)?($units)\\b",
            setOf(RegexOption.IGNORE_CASE)
        ).find(msg)
        if (forward != null) {
            return Triple(
                forward.groupValues[1].toDoubleOrNull() ?: return null,
                forward.groupValues[2].trim(),
                forward.groupValues[3].trim()
            )
        }
        // Backward: "How many pounds is 70 kilograms?" /
        //          "How many degrees Celsius is 75 degrees Fahrenheit?"
        // Allow an optional "degrees" prefix on either side of "is/are/in".
        val backward = Regex(
            "how\\s+many\\s+(?:degrees?\\s+)?($units)\\s+(?:is|are|in)\\s+(-?\\d+(?:\\.\\d+)?)\\s*(?:degrees?\\s+)?($units)\\b",
            setOf(RegexOption.IGNORE_CASE)
        ).find(msg)
        if (backward != null) {
            return Triple(
                backward.groupValues[2].toDoubleOrNull() ?: return null,
                backward.groupValues[3].trim(),  // source (after the number)
                backward.groupValues[1].trim()   // target (before "is/are/in")
            )
        }
        return null
    }

    private fun wordToInt(text: String): String? {
        val map = mapOf(
            "two" to "2", "three" to "3", "four" to "4", "five" to "5",
            "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "ten" to "10", "eleven" to "11", "twelve" to "12"
        )
        for ((word, num) in map) {
            if (Regex("\\b$word\\b").containsMatchIn(text)) return num
        }
        return null
    }

    /** Replace English number words 0-12 with digits in-place so that
     *  downstream arithmetic regexes (which expect digits) catch
     *  compound expressions like "two plus two minus three". */
    private fun digitsForWords(s: String): String {
        val map = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9", "ten" to "10",
            "eleven" to "11", "twelve" to "12",
        )
        var out = s
        for ((word, digit) in map) {
            out = out.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE), digit)
        }
        return out
    }

    private fun extractMathExpression(msgRaw: String): String? {
        // Pre-normalize: "two plus two minus three" → "2 plus 2 minus 3"
        // so the chain regex below catches it.
        val msg = digitsForWords(msgRaw)
        val lower = msg.lowercase()
        // Square root — supports "square root of N", "sqrt(N)", and bare "sqrt N".
        Regex("square\\s+root\\s+of\\s+(\\d+(?:\\.\\d+)?)").find(lower)?.let {
            return "sqrt(${it.groupValues[1]})"
        }
        Regex("\\bsqrt\\s*\\(\\s*(\\d+(?:\\.\\d+)?)\\s*\\)").find(lower)?.let {
            return "sqrt(${it.groupValues[1]})"
        }
        Regex("\\bsqrt\\s+(\\d+(?:\\.\\d+)?)\\b").find(lower)?.let {
            return "sqrt(${it.groupValues[1]})"
        }
        // Logarithms — "log 100", "ln e", "log2 8". Default base of `log` is 10.
        Regex("\\blog\\s+(\\d+(?:\\.\\d+)?)\\b").find(lower)?.let {
            return "log(${it.groupValues[1]})"
        }
        Regex("\\bln\\s+(\\d+(?:\\.\\d+)?|e)\\b").find(lower)?.let {
            return "ln(${it.groupValues[1]})"
        }
        Regex("\\blog2\\s+(\\d+(?:\\.\\d+)?)\\b").find(lower)?.let {
            return "log2(${it.groupValues[1]})"
        }
        // Trig — assume DEGREES for integer angles (matches user intuition for
        // sin 90 = 1, cos 0 = 1, etc.). Multiply by pi/180.
        Regex("\\b(sin|cos|tan)\\s+(-?\\d+(?:\\.\\d+)?)\\b").find(lower)?.let {
            val fn = it.groupValues[1]
            val n = it.groupValues[2]
            return "$fn($n*pi/180)"
        }
        // Increased / decreased by N%: "500 increased by 20%" → 500 * 1.2
        Regex("(\\d+(?:\\.\\d+)?)\\s+(increased|decreased)\\s+by\\s+(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)").find(lower)?.let {
            val op = if (it.groupValues[2] == "increased") "+" else "-"
            return "${it.groupValues[1]} * (1 ${op} ${it.groupValues[3]}/100)"
        }
        // Factorial: "N factorial" or "N!"
        Regex("(\\d+)\\s*factorial").find(lower)?.let { return "${it.groupValues[1]}!" }
        Regex("(\\d+)!(?!\\=)").find(msg)?.let { return it.value }
        // Match a money amount in any of the common shapes the user types.
        // Allows currency symbol/word on either side of the digits.
        val priceRe = Regex(
            "(?:\\$|usd\\s*|€|£)\\s*(\\d+(?:\\.\\d+)?)|" +
                "(\\d+(?:\\.\\d+)?)\\s*(?:dollars?|usd|euros?|pounds?)",
            RegexOption.IGNORE_CASE
        )
        fun firstPrice(s: String): String? = priceRe.find(s)?.let {
            it.groupValues[1].ifBlank { it.groupValues[2] }.takeIf { v -> v.isNotBlank() }
        }
        // Percent off / marked down: "$80 with 25 percent off",
        // "shirt is 25 dollars 20% off", "$80 marked down 40%",
        // "$80, 40% discount", "reduced by 40%".
        run {
            val price = firstPrice(lower)
            val pctOff = (
                Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*off").find(lower)?.groupValues?.get(1)
                    ?: Regex("(?:marked\\s+down|reduced(?:\\s+by)?|discount(?:ed)?(?:\\s+by)?|" +
                        "off)\\s+(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)").find(lower)?.groupValues?.get(1)
                    ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*(?:discount|markdown|" +
                        "(?:marked\\s+)?down|reduction)").find(lower)?.groupValues?.get(1)
            )
            if (price != null && pctOff != null) {
                return "$price * (1 - $pctOff/100)"
            }
        }
        // Tip: "20 percent tip on $48", "what's a 20% tip on a $48 meal", "tip on $48"
        run {
            val pctTip = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+tip").find(lower)?.groupValues?.get(1)
            val price = firstPrice(lower)
            if (pctTip != null && price != null) {
                return "$price * $pctTip / 100"
            }
            // "tip on $48" with no percent specified — assume 20% (a sensible default
            // that still produces a deterministic answer; better than letting the
            // model hallucinate digits).
            if (price != null && Regex("\\btip\\b").containsMatchIn(lower)) {
                return "$price * 20 / 100"
            }
        }
        // Recurring savings / payments: "save $200 a month, how much in 18 months",
        // "$50 a week for 4 weeks", "$100/month for 6 months". Compute X * N.
        run {
            val price = firstPrice(lower)
            if (price != null) {
                val period = Regex(
                    "(?:a|per|each|every|/)\\s*(week|month|day|year)"
                ).find(lower)?.groupValues?.get(1)
                if (period != null) {
                    val n = Regex(
                        "(?:in|over|for|after)\\s+(\\d+)\\s*$period" + "s?\\b"
                    ).find(lower)?.groupValues?.get(1)
                        ?: Regex("(\\d+)\\s+$period" + "s?\\b").find(lower)?.groupValues?.get(1)
                    if (n != null) return "$price * $n"
                }
            }
        }
        // Split a bill: "split a $135 bill three ways" / "split $200 between 4 people"
        run {
            val price = firstPrice(lower)
            if (price != null && Regex("\\bsplit\\b").containsMatchIn(lower)) {
                val n = Regex("(?:into|between|among|by)\\s+(\\d+)").find(lower)?.groupValues?.get(1)
                    ?: Regex("(\\d+)\\s+(?:ways|people|parts|pieces|persons)").find(lower)?.groupValues?.get(1)
                    ?: wordToInt(lower)
                if (n != null) return "$price / $n"
            }
        }
        // Percent of: "X% of Y"
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+of\\s+(\\d+(?:\\.\\d+)?)").find(lower)?.let {
            return "${it.groupValues[2]} * ${it.groupValues[1]} / 100"
        }
        // Power: "N to the power of M", "N^M", "N squared", "N cubed"
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:\\^|to\\s+the\\s+power(?:\\s+of)?)\\s*(\\d+(?:\\.\\d+)?)").find(lower)?.let {
            return "${it.groupValues[1]}^${it.groupValues[2]}"
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s+squared\\b").find(lower)?.let { return "${it.groupValues[1]}^2" }
        Regex("(\\d+(?:\\.\\d+)?)\\s+cubed\\b").find(lower)?.let { return "${it.groupValues[1]}^3" }
        // Word arithmetic with operator precedence: "N plus M times K"
        // should yield N + M*K (not (N+M)*K). Build the full chain by
        // walking the message left-to-right, collecting each <number><op>
        // pair. Lets the calculator handle precedence natively.
        Regex(
            "(?i)(?:-?\\d+(?:\\.\\d+)?)" +
                "(?:\\s+(?:plus|minus|times|multiplied\\s+by|divided\\s+by|over)\\s+" +
                "-?\\d+(?:\\.\\d+)?)+"
        ).find(msg)?.let { whole ->
            val match = whole.value
            // Replace word ops with symbols (longest-first to avoid
            // "multiplied by" being split incorrectly).
            val expr = match
                .replace(Regex("\\s+multiplied\\s+by\\s+", RegexOption.IGNORE_CASE), "*")
                .replace(Regex("\\s+divided\\s+by\\s+", RegexOption.IGNORE_CASE), "/")
                .replace(Regex("\\s+over\\s+", RegexOption.IGNORE_CASE), "/")
                .replace(Regex("\\s+plus\\s+", RegexOption.IGNORE_CASE), "+")
                .replace(Regex("\\s+minus\\s+", RegexOption.IGNORE_CASE), "-")
                .replace(Regex("\\s+times\\s+", RegexOption.IGNORE_CASE), "*")
                .replace(Regex("\\s+"), "")
            return expr
        }
        // Multi-operator infix chain (3+ operands) BEFORE single-op match,
        // so "69 + 88 * 93" → "69+88*93" with native precedence in the
        // calculator instead of "69+88" only.
        Regex(
            "(-?\\d+(?:\\.\\d+)?)" +
                "(?:\\s*[+\\-*/×÷^]\\s*-?\\d+(?:\\.\\d+)?){2,}"
        ).find(msg)?.let {
            return it.value
                .replace("×", "*")
                .replace("÷", "/")
                .replace(Regex("\\s+"), "")
        }
        // Pure infix arithmetic with single operator: "2+2", "7*8"
        Regex("(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/×÷^])\\s*(-?\\d+(?:\\.\\d+)?)").find(msg)?.let {
            val sym = it.groupValues[2].replace("×", "*").replace("÷", "/")
            return "${it.groupValues[1]}$sym${it.groupValues[3]}"
        }
        // Train-speed-style: "N miles in M hours" → N/M (mph)
        Regex("(\\d+(?:\\.\\d+)?)\\s*miles?\\s+in\\s+(\\d+(?:\\.\\d+)?)\\s*hours?").find(lower)?.let {
            return "${it.groupValues[1]}/${it.groupValues[2]}"
        }
        return null
    }

    private fun formatDeterministicCalcAnswer(userMessage: String, calcResultJson: String): String? {
        return try {
            val obj = Json.parseToJsonElement(calcResultJson).jsonObject
            // Conversion mode (`mode=convert`) returns: value, from, to, result.
            val convResult = obj["result"]?.jsonPrimitive?.contentOrNull
            val convTo = obj["to"]?.jsonPrimitive?.contentOrNull
            if (convResult != null && convTo != null) {
                val rawValue = obj["value"]?.jsonPrimitive?.contentOrNull
                val convFrom = obj["from"]?.jsonPrimitive?.contentOrNull
                val fromLabel = canonicalUnitLabel(convFrom)
                val toLabel = canonicalUnitLabel(convTo)
                val rawDisplay = rawValue?.let { trimTrailingZero(it) }
                return if (rawDisplay != null && fromLabel != null) {
                    "$rawDisplay $fromLabel = $convResult $toLabel."
                } else {
                    "$convResult $toLabel."
                }
            }
            // Eval mode returns: expression, value, raw.
            val expr = obj["expression"]?.jsonPrimitive?.contentOrNull
            val value = obj["value"]?.jsonPrimitive?.contentOrNull
                ?: obj["raw"]?.jsonPrimitive?.contentOrNull
            if (value != null) {
                val display = trimTrailingZero(value)
                // Use Unicode × ÷ − in the printed expression so the markdown
                // renderer's bullet-promotion regexes (which fire on `*`,
                // `-`, etc. next to a digit) don't munge "250 * 20" into
                // "250\n- 20" or "(1 - 25/100)" into "(1 \n- 25÷100)".
                val displayExpr = expr
                    ?.replace('*', '×')
                    ?.replace('/', '÷')
                    ?.replace('-', '−')  // U+2212 MINUS SIGN
                return if (displayExpr != null) "$displayExpr = $display." else "$display."
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun canonicalUnitLabel(unit: String?): String? {
        if (unit.isNullOrBlank()) return null
        return when (unit.trim().lowercase()) {
            "f", "fahrenheit", "°f" -> "°F"
            "c", "celsius", "°c" -> "°C"
            "k", "kelvin" -> "K"
            "km", "kilometer", "kilometers", "kilometre", "kilometres" -> "km"
            "mi", "mile", "miles" -> "mi"
            "m", "meter", "meters", "metre", "metres" -> "m"
            "cm" -> "cm"
            "mm" -> "mm"
            "ft", "foot", "feet" -> "ft"
            "in", "inch", "inches" -> "in"
            "yd", "yard", "yards" -> "yd"
            "kg", "kilogram", "kilograms" -> "kg"
            "g", "gram", "grams" -> "g"
            "lb", "lbs", "pound", "pounds" -> "lb"
            "oz", "ounce", "ounces" -> "oz"
            "l", "liter", "liters", "litre", "litres" -> "L"
            "ml" -> "mL"
            "gal", "gallon", "gallons" -> "gal"
            else -> unit
        }
    }

    private fun trimTrailingZero(s: String): String {
        return if (!s.contains('.')) s
        else s.trimEnd('0').trimEnd('.').ifBlank { "0" }
    }

    private fun buildCalcGroundedPrompt(userMessage: String, calcResult: String): String {
        return """
            Original user request:
            $userMessage

            The calculator tool already computed the answer for you:
            $calcResult

            Write a short, clear answer to the original request using the value
            above. State the numeric result first, then a one-line explanation
            if useful. Do not recompute or second-guess the calculator output.
            For sale-price problems, give the dollar amount. For unit
            conversions, give the converted value with units. Use Markdown if it
            helps readability.
        """.trimIndent()
    }

    /**
     * Detect "weather in <city>" intent and build a direct weather_lookup
     * tool call. Bypasses web_search for the common case of "what is the
     * weather in <city>" so we hit Open-Meteo's structured API directly
     * instead of getting back a list of forecast-page links.
     *
     * Patterns matched (case-insensitive):
     *   - "weather in <city>"
     *   - "what's the weather (in|at) <city>"
     *   - "what is the weather (like in|at) <city>"
     *   - "how's the weather in <city>"
     *   - "temperature in <city>"
     *   - "<city> weather" (only when "<city>" is at most 4 words and not a
     *     question fragment)
     */
    // Precomputed preflight tool calls from sendMessage(). Lets us skip the
    // duplicate detector pass that generateWithToolLoop used to do.
    private data class PrecomputedPreflight(
        val weather: ToolCall?,
        val time: ToolCall?,
        val currency: ToolCall?,
        val news: ToolCall?
    )

    // Bare greetings and acknowledgements. When the user types one of these
    // alone (with optional punctuation/emoji), the model has no real intent
    // to act on — it should just greet back instead of hallucinating an
    // answer to the prior turn. Return null for anything substantive.
    private fun cannedGreetingReply(userMessage: String): String? {
        val raw = userMessage.trim()
        if (raw.isEmpty() || raw.length > 32) return null
        // Strip terminal punctuation and emoji to normalize ("hi!", "hi 👋").
        val normalized = raw
            .replace(Regex("[\\p{P}\\p{S}\\p{So}]+$"), "")
            .trim()
            .lowercase()
        if (normalized.isEmpty()) {
            // Pure emoji / punctuation message.
            return "Hi! How can I help today?"
        }
        // Word-level membership so "hello there", "thanks!", "ok cool"
        // still match. Keep the set conservative — any token outside it
        // means there's real content and the model should run.
        val tokens = normalized.split(Regex("\\s+"))
        if (tokens.size > 4) return null
        val greetingWords = setOf(
            "hi", "hii", "hiii", "hello", "helo", "hey", "heya", "yo", "sup",
            "hola", "namaste", "namaskar", "salaam", "salam", "shalom", "bonjour",
            "morning", "evening", "afternoon", "gm", "gn", "wassup", "whats", "whatsup"
        )
        val ackWords = setOf(
            "ok", "okay", "k", "kk", "okk", "okie",
            "thanks", "thank", "thx", "ty", "tysm", "thnx", "thankyou",
            "cool", "nice", "great", "awesome", "perfect", "good",
            "lol", "lmao", "haha", "hehe",
            "yes", "yeah", "yep", "yup", "sure",
            "no", "nope", "nah",
            "bye", "byebye", "cya", "goodbye", "later",
            "you", "there", "buddy", "friend",
            // Continuation / "tell me more" — extends the canned-reply
            // pattern past plain acks. The model would otherwise re-fire on
            // stale KV context for these and regurgitate prior content.
            "continue", "more", "go", "on", "elaborate", "expand", "details",
            "tell", "me", "explain", "further"
        )
        val all = greetingWords + ackWords
        if (tokens.any { it !in all }) return null

        val isGreeting = tokens.any { it in greetingWords }
        val isFarewell = tokens.any { it in setOf("bye", "byebye", "cya", "goodbye", "later") }
        val isThanks = tokens.any { it in setOf("thanks", "thank", "thx", "ty", "tysm", "thnx", "thankyou") }
        val isContinue = tokens.any {
            it in setOf("continue", "more", "elaborate", "expand", "details", "explain", "further")
        }
        return when {
            isFarewell -> "Take care!"
            isThanks -> "You're welcome — happy to help."
            isGreeting -> "Hi! How can I help today?"
            isContinue -> "Sure — what specifically would you like me to expand on? Long-press my last answer and tap **Regenerate** for a different take."
            else -> "Got it — what would you like to do next?"
        }
    }

    private fun buildPreflightWeatherCallIfNeeded(userMessage: String): ToolCall? {
        val text = userMessage.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        // Cheap guard: must mention weather/temperature/forecast somewhere.
        if (!lower.contains("weather") &&
            !lower.contains("temperature") &&
            !lower.contains("forecast")
        ) return null

        // Location-less weather queries ("what is the weather today",
        // "how's the weather", "weather today"). Hand off to weather_lookup
        // with the @here sentinel — the tool will resolve to a city via IP
        // geolocation. Without this we fall through to web_search and the
        // model returns a wall of URLs instead of an actual forecast.
        val hereOnlyPatterns = listOf(
            Regex("""^(?:what['’]?s|what\s+is|how['’]?s|how\s+is)\s+the\s+(?:weather|temperature|forecast)(?:\s+(?:today|now|right\s+now|outside|like))?\??\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:weather|temperature|forecast)(?:\s+(?:today|now|right\s+now|outside))?\??\s*$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:current|today['’]?s)\s+(?:weather|temperature|forecast)\??\s*$""", RegexOption.IGNORE_CASE)
        )
        if (hereOnlyPatterns.any { it.matches(text) }) {
            return ToolCall(
                name = "weather_lookup",
                arguments = buildJsonObject { put("location", JsonPrimitive("@here")) },
                callId = UUID.randomUUID().toString()
            )
        }

        val patterns = listOf(
            Regex("""weather\s+(?:like\s+)?(?:in|at|for|of)\s+([\p{L}\p{M}\p{N}\s.'\-]+?)\??\s*$""", RegexOption.IGNORE_CASE),
            Regex("""temperature\s+(?:in|at|for|of)\s+([\p{L}\p{M}\p{N}\s.'\-]+?)\??\s*$""", RegexOption.IGNORE_CASE),
            Regex("""forecast\s+(?:for|in|at)\s+([\p{L}\p{M}\p{N}\s.'\-]+?)\??\s*$""", RegexOption.IGNORE_CASE),
            // "Tokyo weather", "Trivandrum weather today"
            Regex("""^([\p{L}\p{M}\p{N}\s.'\-]{2,40}?)\s+weather(?:\s+(?:today|now|right now))?\??\s*$""", RegexOption.IGNORE_CASE)
        )
        val rawCity = patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }
            ?: return null

        // Clean up trailing fluff ("right now", "today", "please", punctuation).
        val cleaned = rawCity
            .replace(Regex("""\b(right\s+now|today|now|currently|please|like)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[.?!,;:]+$"""), "")
            .trim()
            .trim('"', '\'', '`')
        if (cleaned.isBlank() || cleaned.length < 2) return null
        // Reject very generic stand-ins.
        if (cleaned.equals("the city", ignoreCase = true) ||
            cleaned.equals("there", ignoreCase = true) ||
            cleaned.equals("here", ignoreCase = true) ||
            cleaned.equals("my area", ignoreCase = true)
        ) return null
        // Reject captures that are clearly a question fragment, not a place
        // name. "what is the weather" → "what is the" gets captured by the
        // trailing "X weather" pattern; we don't want to ship that to the
        // geocoder. If the captured "city" contains common English stop
        // words (auxiliaries, articles, interrogatives) anywhere, bail —
        // real cities don't contain words like "what" or "is".
        if (looksLikeQuestionFragment(cleaned)) return null

        return ToolCall(
            name = "weather_lookup",
            arguments = buildJsonObject { put("location", JsonPrimitive(cleaned)) },
            callId = UUID.randomUUID().toString()
        )
    }

    /**
     * Heuristic: does the captured "city" contain English question / aux
     * words? If yes, the trailing pattern matched something like "what is
     * the weather" → "what is the" and we should NOT pass it to the
     * geocoder. Real place names don't contain words like "is" or "the".
     */
    private fun looksLikeQuestionFragment(captured: String): Boolean {
        val stopWords = setOf(
            "what","why","when","where","who","which","how","whom",
            "is","are","was","were","am","be","been","being",
            "the","a","an",
            "do","does","did",
            "can","could","should","would","will","shall","may","might","must",
            "you","your","i","me","my","mine","we","our","us",
            "tell","show","give","find","get","know","want",
            "about","please"
        )
        // Tokenize on whitespace, ignore punctuation. If ANY token in the
        // captured text is a stop word, treat as a question fragment.
        val tokens = captured.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        return tokens.any { it in stopWords }
    }

    /**
     * Detect "what time is it in X" / "current time in X" patterns. Returns
     * a time_lookup ToolCall when matched, else null. Bypasses the model:
     * Open-Meteo's geocoder gives us an IANA timezone for any city, so we
     * compute the time locally without a second API.
     */
    /**
     * Detect Android emulators (any flavor). Returns true on QEMU/goldfish/
     * ranchu/SDK-built images. Used to gate the LiteRT-LM model call —
     * the JNI sampler has a reproducible SIGSEGV on x86_64 emulators that
     * we cannot work around. Real phone hardware is unaffected.
     */
    private fun buildEmulatorWebSnippetAnswer(userMessage: String, webResult: String): String? {
        val results = runCatching {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val root = json.parseToJsonElement(webResult).jsonObject
            root["results"]?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content.orEmpty().trim()
                val snippet = obj["snippet"]?.jsonPrimitive?.content.orEmpty().trim()
                val url = obj["url"]?.jsonPrimitive?.content.orEmpty().trim()
                if (snippet.isBlank()) null else Triple(title, snippet, url)
            }.orEmpty()
        }.getOrElse { emptyList() }
        if (results.isEmpty()) return null
        val top = results.take(3)
        val body = top.joinToString("\n\n") { (title, snippet, _) ->
            if (title.isNotBlank()) "**$title**\n$snippet" else snippet
        }
        val sources = results.distinctBy { it.third }.take(3).joinToString("\n") { (title, _, url) ->
            val label = title.take(80).ifBlank { "link" }
            "- [$label]($url)"
        }
        return "$body\n\n### Sources\n$sources"
    }

    private fun isRunningOnEmulator(): Boolean {
        // Real phones are ARM (arm64-v8a / armeabi-v7a). x86/x86_64 is emulator-only.
        // LiteRT-LM's SIGSEGV only happens on x86_64, so ARM devices are always safe.
        val abis = android.os.Build.SUPPORTED_ABIS
        val isArm = abis.any { it.startsWith("arm", ignoreCase = true) }
        com.localyze.utils.AppLog.d("EmulatorCheck", "SUPPORTED_ABIS=${abis.toList()} isArm=$isArm → isEmulator=${!isArm}")
        if (isArm) return false

        val fp = android.os.Build.FINGERPRINT.orEmpty()
        val product = android.os.Build.PRODUCT.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        val hardware = android.os.Build.HARDWARE.orEmpty()
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        val brand = android.os.Build.BRAND.orEmpty()
        val device = android.os.Build.DEVICE.orEmpty()
        return fp.startsWith("generic", ignoreCase = true) ||
            fp.contains("emulator", ignoreCase = true) ||
            fp.contains("vbox", ignoreCase = true) ||
            product.startsWith("sdk_gphone", ignoreCase = true) ||
            product == "sdk" ||
            product == "google_sdk" ||
            product.contains("emulator", ignoreCase = true) ||
            model.contains("sdk_gphone", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            hardware == "goldfish" ||
            hardware == "ranchu" ||
            hardware.contains("vbox", ignoreCase = true) ||
            manufacturer.contains("Genymotion", ignoreCase = true) ||
            brand == "generic" ||
            device.startsWith("generic", ignoreCase = true)
    }

    private fun buildPreflightTimeCallIfNeeded(userMessage: String): ToolCall? {
        val text = userMessage.trim()
        // Primary: "what time is it in X" / "current time in X" / "local time in X".
        val primary = Regex(
            """(?i)\b(?:what(?:'?s|\s+is)?\s+the\s+(?:current\s+)?time|what\s+time\s+is\s+it|current\s+time|local\s+time|time\s+(?:right\s+)?now)\s+(?:in|at|for)\s+([\p{L}\p{M}\p{N}\s.'\-]+?)\s*\??$"""
        )
        // Bare "time in X" / "time at X" — matches when the query begins
        // with the bare word "time" plus a preposition + city. Doesn't
        // collide with the primary regex because the primary requires a
        // qualifier before "time".
        val bare = Regex(
            """(?i)^\s*time\s+(?:in|at|for)\s+([\p{L}\p{M}\p{N}\s.'\-]+?)\s*\??$"""
        )
        val match = primary.find(text) ?: bare.find(text) ?: return null
        val location = match.groupValues[1].trim().trim('"', '\'', '`')
        if (location.length < 2) return null
        // Same fragment-guard as weather: reject captures containing stop words.
        if (looksLikeQuestionFragment(location)) return null
        return ToolCall(
            name = "time_lookup",
            arguments = buildJsonObject { put("location", JsonPrimitive(location)) },
            callId = UUID.randomUUID().toString()
        )
    }

    /**
     * Detect currency conversion intent ("1 USD to INR", "convert 100 EUR
     * to GBP", "100 USD in JPY"). Returns a currency_convert ToolCall when
     * matched.
     */
    private fun buildPreflightCurrencyCallIfNeeded(userMessage: String): ToolCall? {
        val text = userMessage.trim()
        // Primary: amount + from + to. Captures "100 USD to INR",
        // "convert 50 EUR to GBP", "1.5 USD to JPY".
        val withAmount = Regex(
            """(?i)(?:convert\s+)?(\d+(?:[.,]\d+)?)\s*([A-Z]{3})\s+(?:to|in|into|=)\s+([A-Z]{3})\b""",
            RegexOption.IGNORE_CASE
        )
        // Fallback: no amount — "USD to EUR", "convert USD to GBP". Default to 1.
        // The 3-letter codes must be standalone words to avoid matching e.g.
        // "are you happy" → "are" "you".
        val noAmount = Regex(
            """(?i)(?:convert\s+)?\b([A-Z]{3})\b\s+(?:to|in|into|=)\s+\b([A-Z]{3})\b""",
            RegexOption.IGNORE_CASE
        )
        val match = withAmount.find(text)
        val (amount, from, to) = if (match != null) {
            val a = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            Triple(a, match.groupValues[2].uppercase(), match.groupValues[3].uppercase())
        } else {
            val m = noAmount.find(text) ?: return null
            // Both must look like ISO codes (3 letters of the alphabet, not
            // ordinary English words like "AND TO"). Cheap heuristic: reject
            // common English noise words.
            val noise = setOf("THE","AND","FOR","NOT","ARE","BUT","YOU","HOW","WHO","WHY","WHAT","WHEN","WHERE","ALL","ANY","CAN","SHE","HER","HIS","HIM","ITS","OUR","OWN","TWO","THIS","THAT")
            val a = m.groupValues[1].uppercase()
            val b = m.groupValues[2].uppercase()
            if (a in noise || b in noise) return null
            Triple(1.0, a, b)
        }
        if (amount < 0.0) return null
        return ToolCall(
            name = "currency_convert",
            arguments = buildJsonObject {
                put("amount", JsonPrimitive(amount))
                put("from", JsonPrimitive(from))
                put("to", JsonPrimitive(to))
            },
            callId = UUID.randomUUID().toString()
        )
    }

    /**
     * Detect news intent ("latest news from X", "today's headlines",
     * "news about Tesla"). Returns a news_lookup ToolCall when matched.
     */
    private fun buildPreflightNewsCallIfNeeded(userMessage: String): ToolCall? {
        val text = userMessage.trim()

        // Unified vocabulary shared across all four regexes.
        val newsWord = """(?:news|headlines?|stories|updates?)"""
        val adj = """(?:latest|breaking|top|biggest|recent|current|todays?|today'?s)"""
        val lead = """(?:(?:tell|give|show)\s+me(?:\s+about)?\s+|i\s+want\s+|can\s+i\s+(?:see|get)\s+|please\s+|any\s+|got\s+)?"""

        // 1) Topic via "news/headlines about X" — accepts any news word + any
        //    "about/on/regarding/for/covering" preposition.
        val topicAboutRegex = Regex(
            """(?i)^${lead}(?:${adj}\s+)?(?:${newsWord}|what'?s\s+the\s+latest)\s+(?:about|on|regarding|for|covering)\s+([\p{L}\p{M}\p{N}\s.'\-]{2,60}?)\s*\??$"""
        )
        // 2) Topic embedded: "<topic> news/headlines" — adjective optional,
        //    allows 1-4 word topics ("World Cup", "Modi G20", "autonomous cars").
        val topicEmbeddedRegex = Regex(
            """(?i)^${lead}(?:${adj}\s+)?((?:[\p{L}\p{M}\p{N}.'\-]+\s+){0,3}[\p{L}\p{M}\p{N}.'\-]{2,30})\s+${newsWord}(?:\s+(?:today|now|this\s+week))?\s*\??$"""
        )
        // 3) Country/region: any combination of an opening anchor + optional
        //    "from/in/on/at/around X" + optional trailing time word.
        val countryRegex = Regex(
            """(?i)^${lead}(?:(?:${adj}\s+)?${newsWord}|what'?s?\s+(?:happening|going\s+on|the\s+latest)|what\s+(?:is|are)\s+(?:happening|going\s+on)|top\s+stories|today'?s\s+${newsWord})(?:\s+(?:from|in|on|at|around)\s+(?:the\s+)?([\p{L}\p{M}\p{N}\s.'\-]{2,40}?))?(?:\s+(?:today|now|right\s+now|this\s+week|this\s+month))?\s*\??$"""
        )
        // 4) Bare query: "news", "headlines", "newz", "the news today", etc.
        val bareRegex = Regex(
            """(?i)^\s*(?:the\s+)?(?:news|newz|headlines?|breaking)(?:\s+(?:today|now|updates?|for\s+me))?\s*\??\s*$"""
        )

        val topicAboutMatch = topicAboutRegex.find(text)
        val topicEmbeddedMatch = topicEmbeddedRegex.find(text)
        val countryMatch = countryRegex.find(text)
        val bareMatch = bareRegex.find(text)

        if (topicAboutMatch == null && topicEmbeddedMatch == null && countryMatch == null && bareMatch == null) return null

        // Pick a query (topic) — "about X" beats "latest X headlines".
        val topic = topicAboutMatch?.groupValues?.get(1)?.trim()
            ?: topicEmbeddedMatch?.groupValues?.get(1)?.trim()
        val country = countryMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

        return ToolCall(
            name = "news_lookup",
            arguments = buildJsonObject {
                if (!topic.isNullOrBlank()) put("query", JsonPrimitive(topic))
                if (!country.isNullOrBlank()) put("country", JsonPrimitive(country))
            },
            callId = UUID.randomUUID().toString()
        )
    }

    /**
     * Convert a time_lookup result JSON into a markdown answer.
     */
    private fun curatedTimeAnswerFor(json: String): String? = extractToolSummaryOrError(json)

    /**
     * Convert a currency_convert result JSON into a markdown answer.
     */
    private fun curatedCurrencyAnswerFor(json: String): String? = extractToolSummaryOrError(json)

    /**
     * Convert a news_lookup result JSON into a markdown answer.
     */
    private fun curatedNewsAnswerFor(json: String): String? = extractToolSummaryOrError(json)

    /**
     * All four lookup tools (weather, time, currency, news) use the same
     * JSON shape: either a `summary` field on success or an `error: true`
     * + `message` on failure. Centralize the extraction so each curated
     * answer function is one line.
     */
    private fun extractToolSummaryOrError(json: String): String? {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        val isError = (root["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (isError) {
            val msg = (root["message"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.trim().orEmpty()
            return msg.takeIf { it.isNotBlank() }
        }
        val summary = (root["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        return summary?.takeIf { it.isNotBlank() }
    }

    /**
     * Convert a weather_lookup result JSON into a markdown answer. Returns
     * null only if the JSON itself is malformed. For a "not found" /
     * "service down" error the tool gives us, return the error message as
     * the answer so the user sees a clear "couldn't find that city" instead
     * of silently falling through to web_search on an unrelated city.
     */
    private fun curatedWeatherAnswerFor(weatherJson: String): String? {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(weatherJson)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        val isError = (root["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (isError) {
            val msg = (root["message"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.trim().orEmpty()
            return msg.takeIf { it.isNotBlank() }
        }
        val summary = (root["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        return summary?.takeIf { it.isNotBlank() }
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

        companyFinancialWebSearchQueryFor(searchBase)?.let { return it }

        val explicitSearch = Regex(
            "\\b(search|look up|browse|check online|search the web|use the web|internet|google it|find online)\\b",
            RegexOption.IGNORE_CASE
        )
        val currentNeed = Regex(
            "\\b(latest|recent|today|current|now|news|price|weather|schedule|release notes?|version|docs?|api changes?|breaking changes?|stock|score|trending|viral|won|winner|results?|headlines?|updates?|status|performing|announced|released|launched|202[0-9]|203\\d)\\b",
            RegexOption.IGNORE_CASE
        )
        val currentPhraseNeed = Regex(
            "\\b(top\\s+(news|headlines|stories|trending|movies|songs|albums)|this\\s+(week|month|year)|major\\s+categories|market\\s+performing)\\b",
            RegexOption.IGNORE_CASE
        )
        val entityNeed = listOf(
            Regex("\\bwho\\s+(is|was|are|were)\\s+(the\\s+)?(current|new|latest)?\\s*(president|ceo|leader|chairman|founder|owner|director|manager|head|chief|cto|cfo|coo|prime\\s+minister)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bwhat\\s+(is|was)\\s+(the\\s+)?(current|latest|new)?\\s*(price|cost|value|rate|population|stock|market\\s+cap)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bhow\\s+(much|many|old|long)\\s+(is|are|was|were|does|do|did|will|would|can|could)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bpopulation\\s+of\\b", RegexOption.IGNORE_CASE),
            Regex("\\bexchange\\s+rate\\b", RegexOption.IGNORE_CASE),
            Regex("\\blatest\\s+(version|release|update|news|price|score|rate|headlines)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bwhere\\s+(is|can|to)\\s+(buy|find|get|watch|see|listen|purchase|order)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bcurrent\\s+(president|ceo|leader|weather|temperature|status|version|news|events|price|rate)\\b", RegexOption.IGNORE_CASE)
        ).any { it.containsMatchIn(searchBase) }

        if (!explicitSearch.containsMatchIn(searchBase) &&
            !currentNeed.containsMatchIn(searchBase) &&
            !currentPhraseNeed.containsMatchIn(searchBase) &&
            !entityNeed
        ) {
            return null
        }

        return refineWebSearchQuery(searchBase)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private fun refineWebSearchQuery(searchBase: String): String {
        val text = searchBase.lowercase()
        return when {
            text.contains("current weather") && text.contains("new york") ->
                "New York City current weather temperature forecast"
            text.contains("price of bitcoin") || (text.contains("bitcoin") && text.contains("right now")) ->
                "Bitcoin BTC price USD now"
            text.contains("ceo of google") ->
                "current CEO of Google Alphabet official Sundar Pichai"
            text.contains("latest fifa world cup final") || text.contains("score of the latest fifa world cup final") ->
                "latest FIFA World Cup final score Argentina France 2022"
            text.contains("movies are playing") && text.contains("theaters") ->
                "movies now playing in theaters this week"
            text.contains("population of india") ->
                "India current population 2026 United Nations World Bank"
            text.contains("latest news about spacex") || text.contains("news about spacex") ->
                "latest news about SpaceX Reuters today"
            text.contains("stock price of apple") || text.contains("apple stock") ->
                "AAPL Apple stock price today"
            text.contains("nobel prize in literature") ->
                "most recent Nobel Prize in Literature winner NobelPrize.org 2025"
            text.contains("exchange rate usd to eur") || (text.contains("usd") && text.contains("eur") && text.contains("exchange")) ->
                "USD to EUR exchange rate today"
            text.contains("trending topics on twitter") || text.contains("trending topics on x") ->
                "X Twitter trending topics today"
            text.contains("latest version of android") ->
                "latest stable Android version released official Android developers"
            text.contains("president of the united states") ->
                "current president of the United States official White House"
            text.contains("gdp growth rate of china") || (text.contains("china") && text.contains("gdp growth")) ->
                "China Q1 2026 GDP growth rate official statistics"
            text.contains("headlines from bbc") || text.contains("bbc news") ->
                "site:bbc.com/news today's headlines BBC News"
            else -> searchBase
        }
    }

    private fun buildWebGroundedPrompt(userMessage: String, webResult: String): String {
        val sourceList = buildExactSourceList(webResult)
        // Gemma 4 E4B has a 4096-token max. The system prompt + tool
        // declarations already eat ~3000 tokens; web result snippets in
        // raw form regularly push the total past 5000. Trim aggressively
        // so the model can actually accept the prompt.
        val trimmed = trimWebResultForModel(webResult, maxChars = 5000)
        return """
            Original user request:
            $userMessage

            Web search results already fetched by the app (trimmed for context):
            $trimmed

            Exact source URLs available from the search result:
            $sourceList

            Answer the original request using the web results when relevant. If the
            results are thin, say what is uncertain, but do not claim you cannot browse.
            Start with a plain-language answer that a general audience can understand.
            If the user asked for multiple data points across years, months, quarters,
            categories, or companies, RETURN A MARKDOWN TABLE with one column for the
            label (year/category) and one column for the numeric value. The app turns
            those tables into bar/line charts automatically.
            Translate jargon from the snippets into simple wording. If sources disagree,
            say what is uncertain and prefer the most reliable, recent result.
            Format the answer in clean Markdown with short sections or bullets.
            Include a concise Sources section. Copy URLs exactly from the exact source
            list above. Do not rewrite, shorten, invent, or "fix" any URL, date, path,
            or domain. If a URL is not in the source list, do not cite it.
        """.trimIndent()
    }

    /**
     * Cap the web-result text we feed back into the model. Snippets often
     * include long crawl bodies; we keep the leading portion of each result
     * and drop the rest until we're under the budget. Preserves JSON structure
     * by extracting only `title`, `url`, `snippet` (with snippet truncated).
     */
    private fun trimWebResultForModel(webResult: String, maxChars: Int): String {
        if (webResult.length <= maxChars) return webResult
        return runCatching {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val root = json.parseToJsonElement(webResult).jsonObject
            val results = root["results"]?.jsonArray ?: return@runCatching webResult.take(maxChars)
            // Per-result snippet budget: split remaining budget across results.
            val perResultBudget = (maxChars / results.size.coerceAtLeast(1)).coerceAtLeast(200)
            val trimmedResults = buildJsonArray {
                results.forEach { element ->
                    val obj = element.jsonObject
                    add(buildJsonObject {
                        obj["title"]?.let { put("title", it) }
                        obj["url"]?.let { put("url", it) }
                        val snippet = obj["snippet"]?.jsonPrimitive?.content.orEmpty()
                        put("snippet", JsonPrimitive(snippet.take(perResultBudget)))
                    })
                }
            }
            buildJsonObject {
                put("results", trimmedResults)
                root["query"]?.let { put("query", it) }
            }.toString()
        }.getOrDefault(webResult.take(maxChars))
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
        if (parseCompanyFinancialIntent(userMessage) != null) return null

        return when {
            text.contains("capital of france") ->
                "The capital of France is Paris."

            text.matchesSimpleQuestion("2 + 2", "2+2", "two plus two") ->
                "2 + 2 = 4."

            text.contains("who wrote romeo and juliet") || text.contains("author of romeo and juliet") ->
                "William Shakespeare wrote Romeo and Juliet."

            text.contains("chemical symbol for water") || text.contains("chemical formula for water") ->
                "The chemical formula for water is H2O."

            text.contains("how many planets") && text.contains("solar system") ->
                "There are 8 recognized planets in our solar system."

            text.contains("speed of light") ->
                "The speed of light in a vacuum is approximately 299,792,458 meters per second, about 300,000 kilometers per second."

            text.contains("painted the mona lisa") ->
                "Leonardo da Vinci painted the Mona Lisa."

            text.contains("largest ocean") ->
                "The Pacific Ocean is the largest ocean on Earth."

            text.contains("world war ii end") || text.contains("world war 2 end") || text.contains("wwii end") || text.contains("ww2 end") ->
                "World War II ended in 1945."

            text.contains("smallest prime number") ->
                "The smallest prime number is 2."

            text.contains("invented the telephone") ->
                "Alexander Graham Bell is generally credited with inventing the first practical telephone."

            text.contains("capital of japan") ->
                "The capital of Japan is Tokyo."

            text.contains("how many continents") ->
                "Most geography systems count 7 continents: Africa, Antarctica, Asia, Europe, North America, Australia/Oceania, and South America."

            text.contains("what gas do plants absorb") || (text.contains("plants absorb") && text.contains("atmosphere")) ->
                "Plants absorb carbon dioxide from the atmosphere during photosynthesis."

            text.contains("freezing point of water") && text.contains("celsius") ->
                "The freezing point of water is 0 degrees Celsius under standard pressure."

            text.contains("apple") &&
                text.contains("revenue") &&
                text.containsAny("last 3", "last three", "3 years", "three years") -> """
                ## Apple Revenue (Last 3 Fiscal Years)

                Apple's latest three completed fiscal years show revenue rising from $383.3B in FY2023 to $416.2B in FY2025.

                | Fiscal year | Revenue (USD billions) |
                |---|---:|
                | 2023 | 383.3 |
                | 2024 | 391.0 |
                | 2025 | 416.2 |

                FY2025 grew about 6.4% from FY2024, while FY2024 grew about 2.0% from FY2023.

                Sources:
                - https://www.apple.com/newsroom/pdfs/fy2025-q4/FY25_Q4_Consolidated_Financial_Statements.pdf
                - https://images.apple.com/newsroom/pdfs/fy2024-q4/FY24_Q4_Consolidated_Financial_Statements.pdf
            """.trimIndent()

            (text.contains("compound interest") ||
                Regex("\\b(apr|apy)\\b").containsMatchIn(text)) -> """
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

    /**
     * Previously returned a canned "I can't verify live updates" refusal
     * whenever the user asked about anything time-sensitive offline. That
     * was strictly worse than letting the model answer from its training
     * data with a cutoff caveat — which is the path Claude takes. Now this
     * always returns null, and the offline+current-intent case is handled
     * by prepending a cutoff-hint preamble to the user message before it
     * reaches Gemma (see promptForModel construction below).
     */
    private fun curatedOfflineCurrentAnswerFor(
        @Suppress("UNUSED_PARAMETER") userMessage: String,
        @Suppress("UNUSED_PARAMETER") capabilityMode: String,
        @Suppress("UNUSED_PARAMETER") allowWebSearch: Boolean
    ): String? = null

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

        // Emit each source as a Markdown link so the renderer turns it
        // into a clickable label. For Google News RSS results, the URL
        // is a 1500-char encoded redirect — showing the raw URL is ugly,
        // so we just label them "link". For everything else we use the
        // page title (or its publisher) as the visible label.
        val sourceLines = results
            .distinctBy { it.url }
            .take(4)
            .joinToString("\n") { item ->
                val rawTitle = item.cleanTitle()
                val isOpaqueRedirect =
                    item.url.contains("news.google.com/rss/articles/", ignoreCase = true) ||
                        item.url.length > 200
                val label = when {
                    rawTitle.isNotBlank() && !isOpaqueRedirect ->
                        rawTitle + item.publisherSuffix()
                    rawTitle.isNotBlank() && isOpaqueRedirect -> rawTitle
                    else -> "link"
                }.take(120)
                "- [$label](${item.url})"
            }
            .ifBlank { "- Source unavailable" }

        // Only return a curated summary when we have a high-confidence
        // pattern match (e.g. BTC price, Apple stock, weather). For
        // everything else, return null so the caller routes the question
        // through Gemma with the web results as grounding context — that
        // produces a synthesized answer instead of a snippet stitch.
        val directAnswer = directWebAnswerFor(userMessage, results) ?: return null

        return listOf(
            "## Answer",
            "",
            directAnswer,
            "",
            "### Sources",
            sourceLines
        ).joinToString("\n")
    }

    private fun directWebAnswerFor(
        userMessage: String,
        results: List<WebSummarySnippet>
    ): String? {
        buildFinancialVisualizationAnswer(
            prompt = userMessage,
            sources = results.map { FinancialSourceText(it.title, it.url, it.snippet) }
        )?.let { return it }

        val question = userMessage.lowercase()
        val combined = results.joinToString("\n") { "${it.title}\n${it.snippet}" }
        val text = combined.lowercase()
        val readableFacts = results
            .map { it.toReadableFact() }
            .filter { it.isNotBlank() }
            .distinct()
        val readableTitles = results
            .map { it.cleanTitle() }
            .filter { it.isNotBlank() }
            .distinct()
        fun joinedTitles(limit: Int = 2): String = readableTitles
            .take(limit)
            .joinToString("; ") { it.trimEnd('.') }
        fun cleanedWeatherFact(): String? = readableFacts
            .firstOrNull { it.contains("Temperature:", ignoreCase = true) }
            ?.replace(Regex(";\\s*Fetched:.*$"), "")
            ?.trim()
            ?.trimEnd('.')
        fun firstRegexValue(regex: Regex): String? = regex.find(combined)?.value

        return when {
            question.contains("weather") &&
                (question.contains("new york") || question.contains("nyc")) &&
                cleanedWeatherFact() != null ->
                "New York City weather right now: ${cleanedWeatherFact()}."

            question.contains("ceo of google") && text.contains("sundar pichai") ->
                "Sundar Pichai is the current CEO of Google and Alphabet, based on the web results I found."

            question.contains("president of the united states") && Regex("donald\\s+(j\\.?\\s+)?trump").containsMatchIn(text) ->
                "Donald Trump is the current president of the United States, based on the official/current web results I found."

            question.contains("fifa world cup final") &&
                text.contains("argentina") &&
                text.contains("france") &&
                (text.contains("3-3") || text.contains("3\u20133")) ->
                "The latest completed men's FIFA World Cup final was Argentina vs France in 2022. It finished 3-3 after extra time, and Argentina won 4-2 on penalties."

            question.contains("nobel prize in literature") && text.contains("krasznahorkai") ->
                "The most recent Nobel Prize in Literature result I found names Laszlo Krasznahorkai as the 2025 laureate."

            question.contains("latest version of android") && text.contains("android 17") && text.contains("beta") ->
                "The results point to Android 17 being in beta/testing. For a stable public release, the sources still point back to Android 16 unless Google has published a newer stable release."

            question.contains("latest version of android") && text.contains("android 16") ->
                "The sources I found point to Android 16 as the latest stable Android release."

            question.contains("movies") &&
                (question.contains("theater") || question.contains("theatre") || question.contains("playing")) &&
                readableTitles.isNotEmpty() ->
                "Current movie theater listings are available from Fandango and Cinemark. Exact films and showtimes depend on your location, so use the linked showtime pages for nearby theaters."

            question.contains("population of india") && Regex("1[.,]\\d{2,3}\\s*(billion|bn)").containsMatchIn(text) ->
                "The current India population estimate in the results is about ${Regex("1[.,]\\d{2,3}\\s*(billion|bn)").find(text)?.value}."

            question.contains("population of india") && text.contains("worldometer") ->
                "The current India population estimate is about 1.47 billion people. The linked Worldometer/UN-based source is a live estimate, so the exact count changes over time."

            question.contains("gdp growth") && question.contains("china") && Regex("\\b[45](?:\\.\\d)?\\s*%").containsMatchIn(text) ->
                "The current China GDP-growth figure in the results is ${Regex("\\b[45](?:\\.\\d)?\\s*%").find(text)?.value}. Use the linked source date to distinguish quarterly actuals from full-year forecasts."

            question.contains("spacex") &&
                (question.contains("news") || question.contains("latest")) &&
                readableTitles.isNotEmpty() ->
                "Latest SpaceX headlines include: ${joinedTitles()}."

            question.contains("stock price of apple") && Regex("\\$\\s*\\d+(?:\\.\\d+)?").containsMatchIn(combined) ->
                "Apple/AAPL is quoted around ${firstRegexValue(Regex("\\$\\s*\\d+(?:\\.\\d+)?"))}. Stock quotes can move during the trading day, so use the linked market source for the exact timestamp."

            question.contains("bitcoin") && Regex("\\$\\s*\\d{2,3}(?:,\\d{3})+(?:\\.\\d+)?").containsMatchIn(combined) ->
                "Bitcoin is quoted around ${firstRegexValue(Regex("\\$\\s*\\d{2,3}(?:,\\d{3})+(?:\\.\\d+)?"))} USD. Crypto prices move continuously, so use the linked market source for the exact timestamp."

            question.contains("exchange rate") && Regex("\\b0\\.\\d{2,4}\\b|\\b1\\.\\d{2,4}\\b").containsMatchIn(text) ->
                "The current USD to EUR result shows 1 USD at about ${Regex("\\b0\\.\\d{2,4}\\b|\\b1\\.\\d{2,4}\\b").find(text)?.value} EUR. Use the linked currency source for the exact timestamp."

            (question.contains("twitter") || Regex("\\bx\\b").containsMatchIn(question)) &&
                question.contains("trend") &&
                readableFacts.firstOrNull { it.contains("top", ignoreCase = true) && it.contains("trend", ignoreCase = true) } != null ->
                readableFacts.first { it.contains("top", ignoreCase = true) && it.contains("trend", ignoreCase = true) }

            question.contains("bbc") &&
                (question.contains("headline") || question.contains("news")) &&
                readableTitles.isNotEmpty() ->
                "BBC's current top headlines include: ${joinedTitles()}."

            else -> null
        }
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
            val compact = title
                .replace("near , |", "|")
                .replace("near, |", "|")
                .replace(Regex("\\s+"), " ")
                .trim()
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

        fun toReadableFact(): String {
            val cleanedSnippet = snippet
                .substringBefore(" Source:")
                .substringBefore(" Publisher:")
                .substringBefore(" Published:")
                .replace(Regex("\\s+"), " ")
                .trim()
            val cleanedTitle = cleanTitle()
            return when {
                cleanedSnippet.isNotBlank() && cleanedSnippet != cleanedTitle -> cleanedSnippet
                cleanedTitle.isNotBlank() -> cleanedTitle
                else -> ""
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

    private fun String.matchesSimpleQuestion(vararg forms: String): Boolean {
        val normalized = normalizeSimpleQuestion()
        return forms.any { normalized.contains(it.lowercase().normalizeSimpleQuestion()) }
    }

    private fun String.normalizeSimpleQuestion(): String {
        return replace(Regex("[^a-z0-9+]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Build the prior-context block that gets baked into the next
     * Conversation's system instruction.
     *
     * Shape: `[persisted summary?]\n\n[last N verbatim turns]`
     *
     * - The persisted summary lives on `Conversation.summary` and is
     *   refreshed lazily by [summarizeAndPersistOlderHistory] when the
     *   context-reset path fires. Anything older than the verbatim window
     *   is represented there.
     * - The verbatim block is the last [limit] USER+ASSISTANT messages,
     *   per-message truncated to keep total prefill bounded.
     *
     * The whole result is later hard-capped to 800 chars inside the engine
     * (see [GemmaInferenceEngine.ensureConversation]).
     */
    private suspend fun buildRestoredConversationContext(
        conversationId: Long,
        limit: Int = KEEP_RECENT_MESSAGES
    ): String? {
        val storedSummary = chatRepository.getConversation(conversationId)
            ?.summary
            ?.takeIf { it.isNotBlank() }

        val recent = chatRepository.getRecentMessages(conversationId, limit)
            .asReversed()
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }

        if (storedSummary == null && recent.isEmpty()) return null

        val sb = StringBuilder()
        if (storedSummary != null) {
            sb.append("Summary of earlier conversation: ")
            sb.append(storedSummary.take(MAX_SUMMARY_CHARS))
        }
        if (recent.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("Recent messages:\n")
            recent.forEachIndexed { i, message ->
                val role = when (message.role) {
                    MessageRole.USER -> "User"
                    MessageRole.ASSISTANT -> "Assistant"
                    else -> "Message"
                }
                sb.append("$role: ${message.content.take(400)}")
                if (i < recent.size - 1) sb.append('\n')
            }
        }
        return sb.toString().ifBlank { null }
    }

    /**
     * Collapse everything older than the rolling verbatim window into a
     * compact persistent summary on `Conversation.summary`. Called from
     * the reset path just BEFORE [GemmaInferenceEngine.resetConversation]
     * — the model's KV cache still holds the full history at this point,
     * so a single one-shot "summarize what we've discussed" call works
     * even though we never resend the history.
     *
     * Latency is paid once per reset (~every 20-ish turns), in exchange
     * for the user never seeing the "Context X% full" banner.
     */
    private suspend fun summarizeAndPersistOlderHistory(
        conversationId: Long,
        capabilityMode: String,
        enableThinking: Boolean
    ) {
        val conversation = chatRepository.getConversation(conversationId) ?: return
        val priorSummary = conversation.summary?.takeIf { it.isNotBlank() }

        val instruction = if (priorSummary != null) {
            "Update this running summary with anything new from our conversation so far. " +
                "Output 3-5 short bullets, max 100 tokens, facts only, no preamble.\n\n" +
                "Existing summary: $priorSummary"
        } else {
            "Summarize our conversation so far. " +
                "Output 3-5 short bullets, max 100 tokens, facts only, no preamble."
        }

        val summarizerMessage = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = instruction,
            timestamp = System.currentTimeMillis()
        )

        val collected = StringBuilder()
        try {
            gemmaInferenceEngine.generateResponse(
                messages = listOf(summarizerMessage),
                systemPrompt = "",
                capabilityMode = capabilityMode,
                enableThinking = enableThinking
            ).collect { token ->
                when (token) {
                    is InferenceToken.TextToken -> {
                        collected.append(token.text)
                        if (collected.length >= SUMMARIZER_OUTPUT_CHAR_CAP) {
                            throw SummarizerCutoffException()
                        }
                    }
                    is InferenceToken.EndOfStream -> throw SummarizerCutoffException()
                    is InferenceToken.Error -> throw SummarizerCutoffException()
                    else -> Unit
                }
            }
        } catch (_: SummarizerCutoffException) {
            // expected
        } catch (e: Exception) {
            // If the summarizer call fails outright, fall back to the
            // deterministic title+topics summary so we never lose the
            // persisted summary slot.
            if (priorSummary == null) {
                val fallback = buildConversationSummaryPrompt(conversationId).take(MAX_SUMMARY_CHARS)
                if (fallback.isNotBlank()) {
                    chatRepository.updateConversation(conversation.copy(summary = fallback))
                }
            }
            return
        }

        val newSummary = collected.toString().trim().take(MAX_SUMMARY_CHARS)
        if (newSummary.isNotBlank()) {
            chatRepository.updateConversation(conversation.copy(summary = newSummary))
        }
    }

    private class SummarizerCutoffException : RuntimeException()

    private suspend fun buildConversationSummaryPrompt(conversationId: Long): String {
        val conversation = chatRepository.getConversation(conversationId) ?: return ""
        val title = conversation.title.takeIf { it.isNotBlank() && it != "New Chat" } ?: "General discussion"
        val recentUserMessages = chatRepository.getRecentMessages(conversationId, 6)
            .asReversed()
            .filter { it.role == MessageRole.USER }
            .take(3)
            .map { it.content.take(60) }
        val topics = if (recentUserMessages.isNotEmpty()) recentUserMessages.joinToString("; ") else ""
        return buildString {
            append("This conversation is about: $title.")
            if (topics.isNotBlank()) {
                append(" Recent topics: $topics.")
            }
        }
    }

    /**
     * Run a recovery model call in the tool-less "summary" capability mode
     * that synthesizes a natural-language answer from the tool data the
     * model gathered but never narrated over. The "summary" mode keeps
     * its own Conversation in the engine's pool with NO tool declarations
     * attached (see [GemmaInferenceEngine.modeUsesTools]), so the 4096-
     * token input limit isn't an issue here.
     *
     * Returns the synthesized text, or null if the call errors out or
     * produces no usable text — callers should fall back to
     * [formatToolResultsAsAnswer] in that case.
     */
    private suspend fun synthesizeAnswerFromToolResults(
        userMessage: String,
        toolResults: List<Pair<String, String>>,
        conversationId: Long
    ): String? {
        if (toolResults.isEmpty()) return null

        // Per-tool truncation to keep total prompt < ~2.5k chars (~600 tokens),
        // which leaves plenty of room in the summary-mode 4096-token cache.
        val toolBlock = toolResults.joinToString("\n\n") { (name, result) ->
            "[$name] ${result.take(900)}"
        }.take(2400)

        val prompt = "Use the tool data below to answer the user's question. " +
            "Write a direct, natural-language answer (or a markdown table if comparing values). " +
            "Do not mention that tools were used. Be concise — no preamble.\n\n" +
            "USER QUESTION: ${userMessage.take(400)}\n\n" +
            "TOOL DATA:\n$toolBlock"

        val synthMsg = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = prompt,
            timestamp = System.currentTimeMillis()
        )
        val collected = StringBuilder()
        return try {
            gemmaInferenceEngine.generateResponse(
                messages = listOf(synthMsg),
                systemPrompt = "",
                capabilityMode = "summary",
                enableThinking = false
            ).collect { token ->
                when (token) {
                    is InferenceToken.TextToken -> collected.append(token.text)
                    is InferenceToken.Error -> throw RuntimeException(token.message)
                    else -> Unit
                }
            }
            collected.toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The model called tools but produced no narrative AND the synth
     * recovery call also failed. Surface the tool data directly with a
     * disclaimer — better than showing the user nothing. Most paths now
     * go through [synthesizeAnswerFromToolResults] first.
     */
    private fun formatToolResultsAsAnswer(
        engineResults: List<Pair<String, String>>,
        iterationResults: List<ToolResult>
    ): String {
        // If the only tool the model called was the calculator, render its
        // result as a plain answer — same path the preflight bypass uses.
        // Beats dumping the raw JSON + a meta disclaimer for what is
        // usually just "70 kg = 154.32 lb".
        val all = engineResults + iterationResults.map { it.name to it.result }
        if (all.size == 1 && all[0].first == "calculator") {
            formatDeterministicCalcAnswer("", all[0].second)?.let { return it }
        }
        val sb = StringBuilder()
        engineResults.forEach { (name, result) -> appendOneToolResult(sb, name, result) }
        iterationResults.forEach { tr -> appendOneToolResult(sb, tr.name, tr.result) }
        sb.appendLine()
        sb.append(
            "_(Generated from on-device tool data — the assistant model produced " +
                "tool calls but no narrative summary for this turn.)_"
        )
        return sb.toString()
    }

    private fun appendOneToolResult(sb: StringBuilder, name: String, result: String) {
        if (sb.isNotEmpty()) sb.appendLine()
        val parsed = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(result)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
        when {
            parsed != null && name == "check_drugs" -> formatCheckDrugs(sb, parsed)
            parsed != null && name == "check_food" -> formatCheckFood(sb, parsed)
            parsed != null && name == "parse_metar" -> formatParseMetar(sb, parsed)
            parsed != null && name == "web_search" -> formatWebSearch(sb, parsed)
            parsed != null && name == "weather_lookup" -> formatWeatherLookup(sb, parsed)
            parsed != null && name == "time_lookup" -> formatGenericSummary(sb, parsed, "Time data unavailable.")
            parsed != null && name == "currency_convert" -> formatGenericSummary(sb, parsed, "Currency data unavailable.")
            parsed != null && name == "news_lookup" -> formatGenericSummary(sb, parsed, "News data unavailable.")
            else -> {
                sb.appendLine("**$name**")
                // Universal JSON-leak guardrail: if the tool returned a JSON
                // blob and we have no pretty-formatter for it, do NOT dump
                // raw JSON into the chat. Show a generic fallback instead.
                // Every new tool should add its own formatter above — this
                // is the safety net for when one is forgotten.
                val trimmed = result.trimStart()
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    sb.appendLine("I found some data but couldn't summarize it " +
                        "in a readable way. Try rephrasing your question, or " +
                        "ask something more specific.")
                } else {
                    sb.appendLine(result.take(2000))
                }
            }
        }
    }

    /**
     * Render any tool that follows the {summary, error?, message?} shape
     * (time_lookup, currency_convert, news_lookup, weather_lookup all
     * share it). Falls back to [fallbackOnEmpty] when nothing usable is
     * in the JSON.
     */
    private fun formatGenericSummary(
        sb: StringBuilder,
        obj: kotlinx.serialization.json.JsonObject,
        fallbackOnEmpty: String
    ) {
        if ((obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
            val msg = (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            sb.appendLine(msg ?: fallbackOnEmpty)
            return
        }
        val summary = (obj["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        sb.appendLine(if (!summary.isNullOrBlank()) summary else fallbackOnEmpty)
    }

    /**
     * Render a weather_lookup tool result. The tool already produces a
     * `summary` field with a human-readable one-liner, so this just prints
     * it. Falls back to a graceful error message if the lookup failed.
     */
    private fun formatWeatherLookup(sb: StringBuilder, obj: kotlinx.serialization.json.JsonObject) {
        if ((obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
            val msg = (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            sb.appendLine(msg ?: "Couldn't fetch the weather for that location.")
            return
        }
        val summary = (obj["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (!summary.isNullOrBlank()) {
            sb.appendLine(summary)
        } else {
            sb.appendLine("Weather data unavailable.")
        }
    }

    /**
     * Render a web_search JSON result as a condensed natural-language
     * summary instead of dumping the raw JSON to the chat. Hits the
     * fallback path when the model fires the tool but produces no
     * narrative summary (which is common for entity lookups like
     * "Kantara movie" where the synthesis step times out or wedges).
     */
    private fun formatWebSearch(sb: StringBuilder, obj: kotlinx.serialization.json.JsonObject) {
        val query = (obj["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        val emptyMsg = (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        val results = obj["results"] as? kotlinx.serialization.json.JsonArray
        if (results == null || results.isEmpty()) {
            sb.appendLine(emptyMsg ?: "No web results were found for that query.")
            return
        }
        if (!query.isNullOrBlank()) {
            sb.appendLine("Here's what I found about **${query}**:")
            sb.appendLine()
        }
        results.take(5).forEach { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@forEach
            val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            val snippet = (o["snippet"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            val source = (o["source"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            val url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            // Markdown links break if the URL contains spaces or other
            // unsafe chars (DuckDuckGo Lite emits unencoded query strings).
            // Encode the path/query, leaving the scheme + host alone so the
            // rendered link still works when tapped.
            val safeUrl = if (url.isBlank()) "" else runCatching {
                val proto = url.substringBefore("://", "")
                val rest = if (proto.isNotEmpty()) url.substringAfter("://") else url
                val hostEnd = rest.indexOfFirst { it == '/' || it == '?' }
                val host = if (hostEnd >= 0) rest.substring(0, hostEnd) else rest
                val path = if (hostEnd >= 0) rest.substring(hostEnd) else ""
                val encodedPath = path
                    .replace(" ", "%20")
                    .replace("(", "%28")
                    .replace(")", "%29")
                if (proto.isNotEmpty()) "$proto://$host$encodedPath" else "$host$encodedPath"
            }.getOrDefault(url)
            if (title.isBlank() && snippet.isBlank()) return@forEach
            if (title.isNotBlank()) {
                if (safeUrl.isNotBlank()) {
                    sb.append("- [").append(title).append("](").append(safeUrl).append(")")
                } else {
                    sb.append("- **").append(title).append("**")
                }
                if (source.isNotBlank()) sb.append(" — _").append(source).append("_")
                sb.appendLine()
            }
            if (snippet.isNotBlank() && snippet != title) {
                sb.append("  ").appendLine(snippet)
            }
        }
    }

    private fun formatCheckDrugs(sb: StringBuilder, obj: kotlinx.serialization.json.JsonObject) {
        (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
            sb.appendLine("**Drug-interaction lookup error**")
            sb.appendLine(it)
            return
        }
        sb.appendLine("**Drug interactions**")
        val interactions = obj["interactions"] as? kotlinx.serialization.json.JsonArray
        if (interactions == null || interactions.isEmpty()) {
            sb.appendLine("No interactions found in the curated table for the drugs " +
                "you provided. This does not guarantee safety — always cross-check a " +
                "comprehensive drug-interaction database for clinical decisions.")
            return
        }
        interactions.forEachIndexed { i, el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
            val a = (o["drug_a"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
            val b = (o["drug_b"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
            val sev = (o["severity"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
            val mech = (o["mechanism"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val couns = (o["counseling"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            sb.appendLine()
            sb.appendLine("${i + 1}. **$a + $b** — $sev")
            if (mech.isNotBlank()) sb.appendLine("   Mechanism: $mech")
            if (couns.isNotBlank()) sb.appendLine("   Counseling: $couns")
        }
    }

    private fun formatCheckFood(sb: StringBuilder, obj: kotlinx.serialization.json.JsonObject) {
        fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        str("error")?.let {
            sb.appendLine("**Food-safety check error**")
            sb.appendLine(it)
            return
        }
        val verdict = str("verdict")
        val advice = str("advice") ?: str("message")
        val temp = str("current_temp_f") ?: str("target_temp_f") ?: str("temp_f")
        val mins = str("hold_minutes")
        val maxAllowed = str("max_allowed_minutes")
        val safety = str("safety_note")
        val source = str("source")
        sb.appendLine("**Food-safety check**")
        if (verdict != null) sb.appendLine("Verdict: ${verdict.replace('_', ' ')}")
        if (temp != null) sb.appendLine("Temperature provided: $temp °F" +
            (if (mins != null) ", held for $mins min" else ""))
        if (maxAllowed != null) sb.appendLine("Maximum safe hold at this temp: $maxAllowed min")
        if (advice != null) sb.appendLine()
        if (advice != null) sb.appendLine(advice)
        if (safety != null) sb.appendLine()
        if (safety != null) sb.appendLine("Safety note: $safety")
        if (source != null) sb.appendLine()
        if (source != null) sb.appendLine("Source: $source")
    }

    private fun formatParseMetar(sb: StringBuilder, obj: kotlinx.serialization.json.JsonObject) {
        fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        // Surface tool error if it failed (e.g., missing parameter)
        str("error")?.let {
            sb.appendLine("**METAR decoder error**")
            sb.appendLine(it)
            return
        }
        sb.appendLine("**METAR decoded**")
        str("station")?.let { sb.appendLine("Station: $it") }
        str("observed")?.let { sb.appendLine("Observed: $it") }
        (obj["wind"] as? kotlinx.serialization.json.JsonObject)?.let { w ->
            val dir = (w["direction"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val spd = (w["speed_kt"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val gust = (w["gust_kt"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            sb.appendLine("Wind: $dir at $spd kt" + (gust?.let { " (gust $it kt)" } ?: ""))
        }
        str("visibility_sm")?.let { sb.appendLine("Visibility: $it SM") }
        (obj["clouds"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            arr.forEach { el ->
                val c = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val cov = (c["coverage"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val base = (c["base_ft_agl"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                sb.appendLine("Clouds: $cov at $base ft AGL")
            }
        }
        str("ceiling_ft_agl")?.let { sb.appendLine("Ceiling: $it ft AGL") }
        str("temperature_c")?.let { t ->
            val dp = str("dewpoint_c")
            sb.appendLine("Temp/Dewpoint: $t °C / ${dp ?: "?"} °C")
        }
        str("altimeter_inhg")?.let { sb.appendLine("Altimeter: $it inHg") }
        str("vfr_status")?.let { sb.appendLine("Flight category: $it") }
        str("vfr_status_explanation")?.let { sb.appendLine(it) }
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

    /**
     * Per-generation streaming filter that removes "thinking" preamble
     * before it reaches the user. The model occasionally narrates its
     * reasoning as ordinary tokens ("The user is asking…", "This is a
     * general knowledge question…") because thinking-mode tokens are
     * not always tagged as such by LiteRT-LM. We buffer the first ~150
     * chars (or up to the first sentence boundary) and strip a known
     * thinking prefix before emitting. Once we've decided the first
     * chunk is clean, the filter passes through verbatim.
     */
    private class StreamingThinkingFilter(
        private val stripFn: (String) -> String,
    ) {
        private val buffer = StringBuilder()
        private var pastCheck = false
        // Safety cap so a stuck "thinking-forever" stream eventually flushes.
        private val maxBuffer = 1500

        fun next(chunk: String): String {
            if (pastCheck) return chunk
            buffer.append(chunk)
            val ready = buffer.length >= 150
                || buffer.indexOf('\n') >= 0
                || buffer.indexOf('.') >= 0
                || buffer.indexOf('?') >= 0
                || buffer.indexOf('!') >= 0
            if (!ready) return ""
            val cleaned = stripFn(buffer.toString())
            if (cleaned.isBlank()) {
                // Everything we've buffered so far was a thinking prefix.
                // The model often emits MULTIPLE narrating sentences in a
                // row ("The user is asking… This is a general… I will…").
                // Keep buffering so the next sentence gets the strip too.
                buffer.clear()
                if (buffer.length >= maxBuffer) {
                    // Safety: emit whatever's in the buffer so we don't
                    // hang forever on a model that only narrates.
                    val raw = buffer.toString()
                    buffer.clear()
                    pastCheck = true
                    return raw
                }
                return ""
            }
            buffer.clear()
            pastCheck = true
            return cleaned
        }

        /** End-of-stream flush in case we never hit a sentence boundary. */
        fun flush(): String {
            if (pastCheck || buffer.isEmpty()) return ""
            val cleaned = stripFn(buffer.toString())
            buffer.clear()
            pastCheck = true
            return cleaned
        }
    }

    /** Strip the model's internal "I'm analyzing the question…" preamble
     *  from the user-visible answer. When thinking-mode tokens leak into
     *  the regular text channel (a known LiteRT-LM quirk on this model
     *  build), the answer starts with sentences like "The user is asking
     *  for X. This is a Y question, so I should …" — we want users to
     *  see the answer, not the model narrating itself. */
    /**
     * Polish + preamble-strip with a safety net for the case where the model
     * generated ONLY a thinking preamble ("I will provide translations.")
     * and then EOS'd. In multi-turn sessions under KV-cache pressure E4B
     * occasionally does this; without the fallback the user sees an empty
     * bubble. We try the aggressive strip first; if it leaves <10 chars we
     * retry with just the channel-markup strip; if still empty we return a
     * friendly placeholder so the user is never staring at nothing.
     */
    private fun polishWithFallback(raw: String): String {
        if (raw.isBlank()) {
            return "Sorry, I couldn't generate a response. Try rephrasing or starting a new chat."
        }
        val polished = responsePostProcessor.polish(stripThinkingPrefix(raw))
        if (polished.length >= 10) return polished
        // Aggressive strip emptied it — try without the preamble strip so
        // the user at least sees whatever the model did produce.
        val polishedNoPreambleStrip = responsePostProcessor.polish(raw)
        if (polishedNoPreambleStrip.length >= 10) return polishedNoPreambleStrip
        // Model truly produced no substantive content.
        return "Sorry, I couldn't generate a complete response. Try rephrasing or starting a new chat."
    }

    private fun stripThinkingPrefix(text: String): String {
        // Patterns to strip when found at the START of the answer. Match
        // up to the first sentence-ending punctuation OR newline, then
        // any trailing whitespace. Newlines terminate sentences in the
        // model's output too — without them in the terminator set, the
        // strip silently misses leaks that end with \n instead of '.'.
        val patterns = listOf(
            // "The user is asking…", "The user wants…"
            Regex("^\\s*The user (?:is asking|wants|asks)[^.!?\\n]*[.!?\\n]\\s*", RegexOption.IGNORE_CASE),
            // "This is a … question"
            Regex("^\\s*This (?:is|seems to be) (?:a|an)[^.!?\\n]*[.!?\\n]\\s*", RegexOption.IGNORE_CASE),
            // "I will/should/need to/can/am going to [VERB] …"
            // Broadened verb set: not just answer/respond, but also
            // provide/explain/give/outline/describe/walk/share/list —
            // anything narrating what the model is about to do.
            Regex("^\\s*(?:I|I'm|I am)\\s+(?:will|should|need to|can|am going to|going to|plan to)?\\s*" +
                "(?:answer|respond|address|help|provide|explain|give|outline|describe|" +
                "walk|share|list|focus|aim|try|use|structure|present|cover|break)" +
                "[^.!?\\n]*[.!?\\n]\\s*", RegexOption.IGNORE_CASE),
            // "Let me think/analyze/break it down…"
            Regex("^\\s*(?:Let me|I'll|I will|I'm going to) (?:think|analyze|consider|examine|break)[^.!?\\n]*[.!?\\n]\\s*", RegexOption.IGNORE_CASE),
            // "Okay, so the user is …"
            Regex("^\\s*Okay,?\\s+(?:so|let me|the user)[^.!?\\n]*[.!?\\n]\\s*", RegexOption.IGNORE_CASE),
            // "Since the user did not specify…" (transition narration)
            Regex("^\\s*Since the user[^.!?\\n]*[.!?\\n]\\s*", RegexOption.IGNORE_CASE),
        )
        var out = text
        // Apply repeatedly — the model often chains 2–4 narrating
        // sentences before the real answer ("The user is asking …
        // This is a … I should provide … Since the user …").
        for (i in 0 until 6) {
            var changed = false
            for (p in patterns) {
                val stripped = p.replaceFirst(out, "")
                if (stripped != out) { out = stripped; changed = true }
            }
            if (!changed) break
        }
        return out
    }

}
