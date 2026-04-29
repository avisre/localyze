package com.localyze.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localyze.data.local.SettingsDataStore
import com.localyze.data.repository.ChatRepository
import com.localyze.domain.usecases.ChatResponseEvent
import com.localyze.domain.usecases.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CODE_WORKSPACE_MODE = "code"
internal const val MAX_CODE_WORKSPACE_PROMPT_CODE_CHARS = 8_000

enum class CodeAssistAction(
    val label: String,
    val instruction: String,
    val promptPrefix: String
) {
    Explain(
        "Explain",
        "Explain what this code does, how it works, and any important patterns or concepts it demonstrates.",
        "Explain this code in detail:"
    ),
    Debug(
        "Debug",
        "Find bugs, errors, security issues, or logic problems in this code and explain what's wrong.",
        "Debug this code and identify any issues:"
    ),
    Fix(
        "Fix",
        "Fix any bugs or issues and provide corrected code with explanations of what was changed.",
        "Fix any bugs or issues in this code:"
    ),
    Optimize(
        "Optimize",
        "Optimize this code for performance, readability, or best practices while preserving behavior.",
        "Optimize this code:"
    ),
    Review(
        "Review",
        "Provide a comprehensive code review covering style, architecture, security, and maintainability.",
        "Review this code comprehensively:"
    )
}

enum class CodeWorkspacePane(val label: String) {
    Editor("Editor"),
    Preview("Preview")
}

data class CodeWorkspaceMessage(
    val role: String, // "user" or "assistant"
    val content: String
)

data class CodeWorkspaceUiState(
    val code: String = "",
    val language: String = "HTML",
    val instruction: String = "",
    val selectedPane: CodeWorkspacePane = CodeWorkspacePane.Editor,
    val selectedAction: CodeAssistAction = CodeAssistAction.Explain,
    val messages: List<CodeWorkspaceMessage> = emptyList(),
    val responseText: String = "",
    val thinkingText: String = "",
    val generationStatus: String = "",
    val activeToolCalls: List<ActiveToolCall> = emptyList(),
    val isStreaming: Boolean = false,
    val enableThinking: Boolean = false,
    val streamTokens: Boolean = true,
    val allowWebSearch: Boolean = false,
    val currentConversationId: Long = -1L,
    val attachedImage: android.graphics.Bitmap? = null,
    val error: String? = null
)

@HiltViewModel
class CodeWorkspaceViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val chatRepository: ChatRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodeWorkspaceUiState())
    val uiState: StateFlow<CodeWorkspaceUiState> = _uiState.asStateFlow()
    private var generationJob: Job? = null

    init {
        observeSettings()
        resetEngineForWorkspace()
    }

    fun updateCode(value: String) {
        _uiState.update { it.copy(code = value) }
    }

    fun updateInstruction(value: String) {
        _uiState.update { it.copy(instruction = value) }
    }

    fun selectPane(value: CodeWorkspacePane) {
        _uiState.update { it.copy(selectedPane = value) }
    }

    fun selectAction(value: CodeAssistAction) {
        _uiState.update { it.copy(selectedAction = value) }
    }

    fun attachImage(bitmap: android.graphics.Bitmap) {
        _uiState.update { it.copy(attachedImage = bitmap) }
    }

    fun removeAttachedImage() {
        _uiState.update { it.copy(attachedImage = null) }
    }

    fun loadScenario(code: String, instruction: String, action: CodeAssistAction) {
        _uiState.update {
            it.copy(
                code = code,
                instruction = instruction,
                selectedAction = action,
                messages = emptyList(),
                responseText = "",
                thinkingText = "",
                generationStatus = "",
                activeToolCalls = emptyList(),
                error = null
            )
        }
    }

    fun askAssistant() {
        val state = _uiState.value
        if (state.isStreaming) return

        val prompt = if (state.instruction.isBlank() && state.code.isBlank()) {
            _uiState.update { it.copy(error = "Enter code in the editor or type a question.") }
            return
        } else {
            buildCodeWorkspacePrompt(
                language = state.language,
                action = state.selectedAction,
                code = state.code,
                instruction = state.instruction
            )
        }

        _uiState.update {
            it.copy(
                isStreaming = true,
                responseText = "",
                thinkingText = "",
                generationStatus = "Analyzing code",
                activeToolCalls = emptyList(),
                error = null,
                messages = it.messages + CodeWorkspaceMessage(
                    role = "user",
                    content = state.instruction.ifBlank { "${state.selectedAction.label} this code" }
                )
            )
        }

        generationJob = viewModelScope.launch {
            try {
                val conversationId = ensureWorkspaceConversation()
                if (state.attachedImage != null) {
                    val imagePrompt = buildCodeWorkspacePromptWithImage(
                        language = state.language,
                        action = state.selectedAction,
                        code = state.code,
                        instruction = state.instruction
                    )
                    sendMessageUseCase.sendMessageWithImage(
                        conversationId = conversationId,
                        userMessage = imagePrompt,
                        imageBitmap = state.attachedImage,
                        capabilityMode = CODE_WORKSPACE_MODE,
                        enableThinking = state.enableThinking
                    )
                } else {
                    sendMessageUseCase.sendMessage(
                        conversationId = conversationId,
                        userMessage = prompt,
                        capabilityMode = CODE_WORKSPACE_MODE,
                        enableThinking = state.enableThinking
                    )
                }
                    .catch { e ->
                        if (e is CancellationException) throw e
                        _uiState.update {
                            it.copy(
                                isStreaming = false,
                                generationStatus = "",
                                error = "Code assistant error: ${e.message}"
                            )
                        }
                    }
                    .collect { event -> handleResponseEvent(event) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isStreaming = false, generationStatus = "", activeToolCalls = emptyList()) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        generationStatus = "",
                        activeToolCalls = emptyList(),
                        error = "Code assistant error: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        sendMessageUseCase.stopGeneration()
        _uiState.update { it.copy(isStreaming = false, generationStatus = "", activeToolCalls = emptyList()) }
    }

    fun clearResponse() {
        _uiState.update { it.copy(responseText = "", thinkingText = "", generationStatus = "", activeToolCalls = emptyList(), messages = emptyList()) }
    }

    fun clearWorkspace() {
        _uiState.update {
            it.copy(
                code = "",
                instruction = "",
                selectedPane = CodeWorkspacePane.Editor,
                messages = emptyList(),
                responseText = "",
                thinkingText = "",
                generationStatus = "",
                activeToolCalls = emptyList(),
                attachedImage = null,
                error = null
            )
        }
    }

    fun applyResponseToEditor(responseTextOverride: String? = null) {
        val state = _uiState.value
        val textToProcess = responseTextOverride ?: state.responseText
        android.util.Log.d("CodeWorkspace", "applyResponseToEditor called, responseText length=${textToProcess.length}")

        // Step 1: Extract all code blocks from the response
        val responseForCode = stripThoughtSections(textToProcess)
        val blocks = extractAllCodeBlocks(responseForCode)
        android.util.Log.d("CodeWorkspace", "Extracted ${blocks.size} code blocks")

        if (blocks.isEmpty()) {
            // No code blocks found, check if the raw response is HTML-like
            val trimmed = extractCompleteHtmlDocument(responseForCode) ?: responseForCode.trim()
            if (looksLikeHtml(trimmed)) {
                android.util.Log.d("CodeWorkspace", "Falling back to full response as HTML, length=${trimmed.length}")
                _uiState.update {
                it.copy(
                    code = trimmed,
                    language = "HTML",
                    selectedPane = CodeWorkspacePane.Editor
                )
            }
            } else {
                android.util.Log.d("CodeWorkspace", "Response does not look like HTML, skipping apply")
            }
            return
        }

        // Step 2: Find the best HTML block (complete document preferred)
        val bestHtml = findBestHtmlBlock(blocks)
        if (bestHtml != null) {
            android.util.Log.d("CodeWorkspace", "Found complete HTML block, lang=${bestHtml.language}, length=${bestHtml.code.length}")
            _uiState.update {
                it.copy(
                    code = bestHtml.code,
                    language = "HTML",
                    selectedPane = CodeWorkspacePane.Editor
                )
            }
            return
        }

        // Step 3: Merge multiple blocks into a single HTML file
        val merged = mergeBlocksIntoHtml(blocks)
        if (merged != null) {
            android.util.Log.d("CodeWorkspace", "Merged ${blocks.size} blocks into HTML, length=${merged.length}")
            _uiState.update {
                it.copy(
                    code = merged,
                    language = "HTML",
                    selectedPane = CodeWorkspacePane.Editor
                )
            }
            return
        }

        // Step 4: Use the largest code block whatever it is
        val largest = blocks.maxByOrNull { it.code.length }!!
        val detectedLang = largest.language.ifBlank { detectLanguageFromCode(largest.code) }
        android.util.Log.d("CodeWorkspace", "Using largest block: detectedLang=$detectedLang, length=${largest.code.length}")
        _uiState.update {
            it.copy(
                code = largest.code,
                language = detectedLang,
                selectedPane = CodeWorkspacePane.Editor
            )
        }
    }

    fun enableWebSearch() {
        viewModelScope.launch { settingsDataStore.setAllowWebSearch(true) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsDataStore.thinkingMode.collect { enabled ->
                val previous = _uiState.value.enableThinking
                _uiState.update { it.copy(enableThinking = enabled) }
                if (previous != enabled && !_uiState.value.isStreaming) {
                    resetEngineForWorkspace()
                }
            }
        }
        viewModelScope.launch {
            settingsDataStore.streamTokens.collect { enabled ->
                _uiState.update { it.copy(streamTokens = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.allowWebSearch.collect { enabled ->
                val previous = _uiState.value.allowWebSearch
                _uiState.update { it.copy(allowWebSearch = enabled) }
                if (previous != enabled && !_uiState.value.isStreaming) {
                    resetEngineForWorkspace()
                }
            }
        }
    }

    private fun resetEngineForWorkspace() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.currentConversationId > 0L) {
                sendMessageUseCase.resetEngineConversationWithSavedContext(
                    conversationId = state.currentConversationId,
                    capabilityMode = CODE_WORKSPACE_MODE,
                    enableThinking = false
                )
            } else {
                sendMessageUseCase.resetEngineConversation(
                    capabilityMode = CODE_WORKSPACE_MODE,
                    enableThinking = false
                )
            }
        }
    }

    private suspend fun ensureWorkspaceConversation(): Long {
        val existing = _uiState.value.currentConversationId
        if (existing > 0L) return existing

        val conversation = chatRepository.createConversation(capabilityMode = CODE_WORKSPACE_MODE)
        chatRepository.updateConversation(conversation.copy(title = "Code Workspace"))
        sendMessageUseCase.resetEngineConversation(
            capabilityMode = CODE_WORKSPACE_MODE,
            enableThinking = false
        )
        _uiState.update { it.copy(currentConversationId = conversation.id) }
        return conversation.id
    }

    private fun handleResponseEvent(event: ChatResponseEvent) {
        when (event) {
            is ChatResponseEvent.StreamingToken -> {
                _uiState.update { state ->
                    if (state.streamTokens) {
                        state.copy(responseText = state.responseText + event.text, isStreaming = true, generationStatus = "Writing code answer")
                    } else {
                        state.copy(isStreaming = true, generationStatus = "Writing code answer")
                    }
                }
            }

            is ChatResponseEvent.ThinkingToken -> {
                _uiState.update { state ->
                    if (state.streamTokens) {
                        state.copy(thinkingText = state.thinkingText + event.text, isStreaming = true, generationStatus = "Thinking through the code")
                    } else {
                        state.copy(isStreaming = true, generationStatus = "Thinking through the code")
                    }
                }
            }

            is ChatResponseEvent.ToolCallStarted -> {
                _uiState.update { state ->
                    state.copy(
                        generationStatus = toolStatus(event.toolName, executing = true),
                        activeToolCalls = state.activeToolCalls +
                            ActiveToolCall(toolName = event.toolName, isExecuting = true)
                    )
                }
            }

            is ChatResponseEvent.ToolCallCompleted -> {
                _uiState.update { state ->
                    state.copy(
                        generationStatus = toolStatus(event.toolName, executing = false),
                        activeToolCalls = state.activeToolCalls.map { call ->
                            if (call.toolName == event.toolName && call.isExecuting) {
                                call.copy(isExecuting = false, result = event.result)
                            } else {
                                call
                            }
                        }
                    )
                }
            }

            is ChatResponseEvent.Completed -> {
                android.util.Log.d("CodeWorkspace", "Streaming completed, fullText length=${event.fullText.length}")
                val completedText = if (_uiState.value.responseText.isBlank()) {
                    event.fullText
                } else {
                    _uiState.value.responseText
                }
                _uiState.update { state ->
                    android.util.Log.d("CodeWorkspace", "Completed text length=${completedText.length}, containsCodeBlock=${completedText.contains("```")}")
                    state.copy(
                        isStreaming = false,
                        generationStatus = "",
                        responseText = "",
                        thinkingText = state.thinkingText.ifBlank { event.thinkingText.orEmpty() },
                        activeToolCalls = emptyList(),
                        messages = state.messages + CodeWorkspaceMessage(role = "assistant", content = completedText)
                    )
                }
                // Auto-apply code blocks when streaming completes
                android.util.Log.d("CodeWorkspace", "Calling applyResponseToEditor() after stream completion")
                applyResponseToEditor(completedText)
                android.util.Log.d("CodeWorkspace", "After applyResponseToEditor: code length=${_uiState.value.code.length}, selectedPane=${_uiState.value.selectedPane}")
            }

            is ChatResponseEvent.Error -> {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        generationStatus = "",
                        activeToolCalls = emptyList(),
                        error = event.message
                    )
                }
            }
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    private fun toolStatus(toolName: String, executing: Boolean): String {
        return when (toolName) {
            "web_search" -> if (executing) "Searching docs and examples" else "Reading web sources"
            "file_reader" -> if (executing) "Reading file" else "Using file context"
            else -> if (executing) "Using $toolName" else "Processing tool result"
        }
    }

    fun triggerTestPrompt(prompt: String) {
        _uiState.update { it.copy(instruction = prompt) }
        askAssistant()
    }
}

// Code block extraction

private data class CodeBlock(val language: String, val code: String)

private fun stripThoughtSections(text: String): String {
    return text
        .replace(Regex("""<thought[\s\S]*?</thought>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<think[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
        .trim()
}

private fun extractCompleteHtmlDocument(text: String): String? {
    val lower = text.lowercase()
    val end = lower.lastIndexOf("</html>")
    if (end < 0) return null

    val doctypeStart = lower.lastIndexOf("<!doctype", startIndex = end)
    val htmlStart = lower.lastIndexOf("<html", startIndex = end)
    val start = maxOf(doctypeStart, htmlStart)
    if (start < 0 || start > end) return null

    return text.substring(start, end + "</html>".length).trim()
}

/**
 * Extract ALL code blocks from a markdown response.
 * Handles various formats the AI might use:
 * - ```html ... ```  (language-tagged)
 * - ``` ... ```      (untagged)
 * - ```\n ... ```    (newline before content)
 * - ~~...~~          (strikethrough from model corrections)
 */
private fun extractAllCodeBlocks(markdown: String): List<CodeBlock> {
    val results = mutableListOf<CodeBlock>()
    // Match code blocks with optional language tag, allowing flexible whitespace
    val regex = Regex("""```[ \t]*([A-Za-z0-9_+-]*)[^\S\r\n]*(?:\r?\n)([\s\S]*?)```""")
    regex.findAll(markdown).forEach { match ->
        results.add(CodeBlock(
            language = match.groupValues[1].trim().lowercase(),
            code = match.groupValues[2].trim()
        ))
    }
    // Fallback: code blocks without any language tag and no required newline
    if (results.isEmpty()) {
        val simpleRegex = Regex("""```(?:\r?\n)([\s\S]*?)```""")
        simpleRegex.findAll(markdown).forEach { match ->
            results.add(CodeBlock(
                language = "",
                code = match.groupValues[1].trim()
            ))
        }
    }
    // Strip strikethrough markers the model sometimes adds
    return results.map { block ->
        block.copy(code = block.code.replace(Regex("~~(.*?)~~"), "$1"))
    }
}

/**
 * Find the best complete HTML document from the extracted blocks.
 * Prefers a block that is a full standalone HTML document.
 */
private fun findBestHtmlBlock(blocks: List<CodeBlock>): CodeBlock? {
    // First: look for a complete HTML document (has <!DOCTYPE or <html)
    val completeHtml = blocks.firstOrNull { block ->
        val lower = block.code.lowercase()
        (lower.contains("<!doctype") || lower.contains("<html")) &&
            lower.contains("</html>")
    }
    if (completeHtml != null) return completeHtml

    // Second: look for a block tagged as html that has <body and </body>
    val htmlTagged = blocks.firstOrNull { block ->
        (block.language == "html" || block.language == "htm") &&
            block.code.lowercase().contains("<body") &&
            block.code.lowercase().contains("</body>")
    }
    if (htmlTagged != null) return htmlTagged

    // Third: look for any block that looks like a real HTML page
    val htmlLike = blocks.firstOrNull { block ->
        val lower = block.code.lowercase()
        (lower.contains("<body") || lower.contains("<head")) &&
            (lower.contains("<div") || lower.contains("<main") || lower.contains("<section") ||
             lower.contains("<header") || lower.contains("<nav") || lower.contains("<footer"))
    }
    return htmlLike
}

/**
 * Merge multiple code blocks (e.g. separate HTML + CSS + JS blocks)
 * into a single complete HTML document.
 */
private fun mergeBlocksIntoHtml(blocks: List<CodeBlock>): String? {
    if (blocks.size < 2) return null

    val htmlBlocks = blocks.filter { b ->
        val lower = b.code.lowercase()
        b.language == "html" || b.language == "htm" ||
        lower.contains("<div") || lower.contains("<body") || lower.contains("<main") ||
        lower.contains("<section") || lower.contains("<nav") || lower.contains("<header")
    }
    val cssBlocks = blocks.filter { b ->
        b.language == "css" ||
        (!b.code.lowercase().contains("<div") && !b.code.lowercase().contains("<body") &&
         (b.code.lowercase().contains("{") && b.code.lowercase().contains("}") &&
          !b.code.lowercase().contains("function ") && !b.code.lowercase().contains("const ")))
    }
    val jsBlocks = blocks.filter { b ->
        b.language in setOf("javascript", "js") ||
        b.code.lowercase().contains("function ") || b.code.lowercase().contains("document.") ||
        b.code.lowercase().contains("addeventlistener") || b.code.lowercase().contains("const ") && b.code.lowercase().contains("=>")
    }

    // Need at least an HTML fragment to merge into
    val htmlBody = htmlBlocks.firstOrNull()?.code ?: return null

    val cssContent = cssBlocks.joinToString("\n\n") { it.code }
    val jsContent = jsBlocks.joinToString("\n\n") { it.code }

    return buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("  <meta charset=\"UTF-8\">")
        appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("  <style>")
        appendLine("    * { margin: 0; padding: 0; box-sizing: border-box; }")
        appendLine("    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif; }")
        if (cssContent.isNotBlank()) {
            appendLine(cssContent.prependIndent("    "))
        }
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")

        // If the html body has <body> tags, strip them
        val bodyContent = stripHtmlWrapper(htmlBody)
        appendLine(bodyContent.prependIndent("  "))

        if (jsContent.isNotBlank()) {
            appendLine("  <script>")
            appendLine(jsContent.prependIndent("    "))
            appendLine("  </script>")
        }
        appendLine("</body>")
        appendLine("</html>")
    }
}

/**
 * Strip outer <html>/<head>/<body> wrapper tags from HTML content,
 * keeping only the body contents.
 */
private fun stripHtmlWrapper(html: String): String {
    var content = html.trim()
    // Remove everything up to and including <body...>
    val bodyOpen = Regex("""<body[^>]*>""", RegexOption.IGNORE_CASE)
    val bodyClose = Regex("""</body>""", RegexOption.IGNORE_CASE)
    val htmlOpen = Regex("""<html[^>]*>""", RegexOption.IGNORE_CASE)
    val htmlClose = Regex("""</html>""", RegexOption.IGNORE_CASE)
    val headBlock = Regex("""<head[\s\S]*?</head>""", RegexOption.IGNORE_CASE)
    val doctype = Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE)

    // If it has <body>...</body>, extract just the inner content
    val bodyMatch = bodyOpen.find(content)
    if (bodyMatch != null) {
        content = content.substring(bodyMatch.range.last + 1)
        val closeMatch = bodyClose.find(content)
        if (closeMatch != null) {
            content = content.substring(0, closeMatch.range.first)
        }
        return content.trim()
    }

    // Otherwise strip the common wrappers
    content = doctype.replace(content, "")
    content = htmlOpen.replace(content, "")
    content = htmlClose.replace(content, "")
    content = headBlock.replace(content, "")
    content = bodyOpen.replace(content, "")
    content = bodyClose.replace(content, "")
    return content.trim()
}

/**
 * Check if raw text (no code blocks) looks like HTML that can be previewed.
 */
private fun looksLikeHtml(text: String): Boolean {
    val lower = text.lowercase()
    return lower.contains("<html") || lower.contains("<!doctype") ||
           lower.contains("<body") || lower.contains("<div") ||
           lower.contains("<head") || lower.contains("<main") ||
           lower.contains("<section") || lower.contains("<nav")
}

private fun detectLanguageFromCode(code: String): String {
    val lower = code.lowercase()
    return when {
        lower.contains("<!doctype html") || (lower.contains("<html") && lower.contains("</html")) -> "HTML"
        lower.contains("<style") && lower.contains("<script") && lower.contains("<div") -> "HTML"
        lower.contains("<div") || lower.contains("<span") || lower.contains("<body") || lower.contains("<nav") -> "HTML"
        lower.contains("<style") || lower.contains("@media") || lower.contains("@keyframes") -> "CSS"
        lower.contains("document.") || (lower.contains("const ") && lower.contains("=") && lower.contains(";")) -> "JavaScript"
        lower.contains("fun ") && lower.contains(":") -> "Kotlin"
        lower.contains("public static void main") -> "Java"
        lower.contains("def ") && lower.contains(":") -> "Python"
        lower.contains("interface ") && lower.contains("{") -> "TypeScript"
        lower.contains("struct ") || lower.contains("func main()") -> "Go"
        lower.contains("#include <") -> "C++"
        lower.contains("using system;") -> "C#"
        lower.contains("select ") && lower.contains("from ") -> "SQL"
        else -> "Plain text"
    }
}

// Prompt engineering

internal fun buildCodeWorkspacePrompt(
    language: String,
    action: CodeAssistAction,
    code: String,
    instruction: String
): String {
    val trimmedCode = code.trimEnd().take(MAX_CODE_WORKSPACE_PROMPT_CODE_CHARS)
    val userInstruction = instruction.trim()
    val detectedLang = language.ifBlank { detectLanguageFromCode(trimmedCode) }

    return buildString {
        appendLine("You are a senior software engineer assisting in Localyze's code workspace.")
        appendLine("Your job is to help the user understand, debug, fix, and improve their code.")
        appendLine("Detected language: $detectedLang")
        appendLine("Action: ${action.label} - ${action.instruction}")
        appendLine()
        appendLine("OUTPUT CONTRACT - follow exactly:")
        appendLine("1. Start with a clear, concise summary of what the code does (2-3 sentences).")
        appendLine("2. If the action is Explain: describe the logic step by step, call out patterns/algorithms, and note any assumptions.")
        appendLine("3. If the action is Debug: list each bug or issue with line references and severity (Critical/Warning/Info).")
        appendLine("4. If the action is Fix: provide corrected code in a fenced code block with the same language tag, then explain what was fixed.")
        appendLine("5. If the action is Optimize: explain the performance or readability issue, then show the optimized version with benchmarks if applicable.")
        appendLine("6. If the action is Review: cover code style, architecture, security, maintainability, and test coverage.")
        appendLine("7. Use markdown formatting: headers, bullet points, bold for key terms, and fenced code blocks for any code.")
        appendLine("8. Be specific: reference variable names, function names, and line numbers when pointing out issues.")
        appendLine("9. Do not output <thought>, <think>, planning notes, or self-review text.")
        appendLine("10. Keep explanations accessible but technically accurate. Use analogies for complex concepts.")
        appendLine()
        if (trimmedCode.isBlank()) {
            appendLine("No code was provided in the editor. The user is asking a general question.")
        } else {
            appendLine("CODE TO ANALYZE:")
            appendLine("```$detectedLang")
            appendLine(trimmedCode)
            appendLine("```")
        }
        if (userInstruction.isNotBlank()) {
            appendLine()
            appendLine("USER'S SPECIFIC REQUEST:")
            appendLine(userInstruction)
        }
    }.trim()
}

internal fun buildCodeWorkspacePromptWithImage(
    language: String,
    action: CodeAssistAction,
    code: String,
    instruction: String
): String {
    val basePrompt = buildCodeWorkspacePrompt(language, action, code, instruction)
    return buildString {
        appendLine(basePrompt)
        appendLine()
        appendLine("ADDITIONAL CONTEXT: The user has attached an image of their screen or code.")
        appendLine("Please analyze the image along with the code/editor content.")
        appendLine("If the image shows code, compare it with what's in the editor and note any discrepancies.")
        appendLine("If the image shows an error message, explain what the error means and how to fix it.")
        appendLine("If the image shows a UI, describe what the code is trying to achieve visually.")
    }.trim()
}

