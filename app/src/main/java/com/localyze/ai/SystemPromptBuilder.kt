package com.localyze.ai

import com.localyze.domain.models.Memory
import com.localyze.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds system prompts for the Gemma 4 E4B model based on the active
 * capability mode, thinking mode, registered tools, and user memories.
 *
 * Each capability mode has a tailored system prompt that defines the AI's
 * personality and behavior for that mode.
 */
@Singleton
class SystemPromptBuilder @Inject constructor(
    private val toolRegistry: ToolRegistry
) {

    companion object {
        private const val MODE_CHAT = "chat"
        private const val MODE_SEE = "see"
        private const val MODE_WRITE = "write"
        private const val MODE_BRAINSTORM = "brainstorm"
        private const val MODE_CODE = "code"
        private const val MODE_DATA = "data"
        private const val MODE_COMMUNICATION = "communication"
    }

    // â”€â”€ Per-capability system prompts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val chatPrompt = """
        You are a helpful, friendly AI assistant running entirely on-device for privacy.
        You can see, hear, and understand images and audio natively.
        You have access to tools on the user's device.
        Be concise but thorough. Use tools when they would help answer the user's request.
        For texts and emails, prepare a draft for user review; never claim it was sent.
    """.trimIndent()

    private val seePrompt = """
        You are a visual analysis assistant.
        The user will share images or video frames for you to analyze.
        Describe what you see in detail, answer questions about the content,
        read text (OCR), identify objects, describe charts and diagrams.
        Be thorough and precise in your visual descriptions.
    """.trimIndent()

    private val writePrompt = """
        You are a writing assistant.
        Help the user create, edit, and refine written content â€” emails, essays,
        stories, reports, social media posts.
        Match the user's desired tone and style.
        Offer alternatives and suggestions.
        Structure content clearly with appropriate formatting.
    """.trimIndent()

    private val brainstormPrompt = """
        You are a creative ideation partner.
        Help the user generate ideas, explore possibilities, make connections
        between concepts, and think outside the box.
        Offer diverse perspectives, play devil's advocate when useful,
        and build on the user's ideas enthusiastically.
        Quantity of ideas first, then refine for quality.
    """.trimIndent()

    private val codePrompt = """
        You are a programming assistant.
        Help with writing, debugging, explaining, and refactoring code across
        all major programming languages.
        Provide working code examples, explain concepts clearly, catch bugs,
        and suggest best practices.
        Format code in markdown code blocks with language tags.
    """.trimIndent()

    private val dataPrompt = """
        You are a data analysis assistant.
        Help the user understand data, interpret charts and graphs, perform
        calculations, identify trends and patterns, and draw insights from
        numerical information.
        Be precise with numbers and clearly explain your analytical reasoning.
    """.trimIndent()

    private val communicationPrompt = """
        You are a communication assistant.
        Help the user write, refine, and reply to texts and emails.
        Match the requested tone, keep the message clear, and ask for missing
        recipient details when needed.
        If the user asks you to send or reply, use the draft tools to open the
        system compose screen for user review. Never send messages automatically.
    """.trimIndent()

    // â”€â”€ Thinking mode instruction â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val thinkingInstruction = """
        Before answering, think through your reasoning step by step inside
        <thought>...</thought> tags, then provide your final answer outside those tags.
    """.trimIndent()

    // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Build the complete system prompt for the given capability mode.
     *
     * @param capabilityMode The active mode (chat, see, write, brainstorm, code, data).
     * @param enableThinking Whether to append thinking-mode instructions.
     * @return The assembled system prompt string.
     */
    fun buildSystemPrompt(
        capabilityMode: String,
        enableThinking: Boolean,
        includeToolDescriptions: Boolean = true
    ): String {
        val sb = StringBuilder()

        // 1. Select the base prompt for the capability mode
        sb.appendLine(selectModePrompt(capabilityMode))
        sb.appendLine()

        // 2. Append thinking instruction if enabled
        if (enableThinking) {
            sb.appendLine(thinkingInstruction)
            sb.appendLine()
        }

        // 3. Append tool descriptions if tools are registered. Real LiteRT-LM
        // conversations receive native tool declarations separately, so callers
        // can skip this text block to keep the prompt inside the context window.
        if (includeToolDescriptions) {
            val toolPrompt = buildToolSystemPrompt()
            if (toolPrompt.isNotBlank()) {
                sb.appendLine(toolPrompt)
                sb.appendLine()
            }
        }

        // 4. Append current date/time context
        sb.appendLine("Current date: ${java.time.LocalDate.now()}")
        sb.appendLine("Current time: ${java.time.LocalTime.now().withNano(0)}")

        return sb.toString().trimEnd()
    }

    /**
     * Build the tool system prompt section listing all registered tools
     * in the format Gemma 4 expects for native function calling.
     */
    fun buildToolSystemPrompt(): String {
        val tools = toolRegistry.getAllTools()
        if (tools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("You have access to the following tools:")

        for (tool in tools) {
            sb.appendLine()
            sb.appendLine("${tool.name}: ${tool.description}")

            val schema = tool.getParameterSchema()
            if (schema.isNotEmpty()) {
                sb.append("  Parameters: ")
                sb.appendLine(schema.toString())
            }
        }

        sb.appendLine()
        sb.appendLine("To use a tool, respond with a JSON object containing 'name' and 'arguments' fields.")

        return sb.toString()
    }

    /**
     * Build a memory prompt section from a list of saved memories.
     *
     * @param memories The memories to include.
     * @return A formatted string injecting memories as context, or empty string if none.
     */
    fun buildMemoryPrompt(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("You remember these facts about the user:")
        for (memory in memories) {
            sb.appendLine("- ${memory.content}")
        }
        return sb.toString()
    }

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun selectModePrompt(capabilityMode: String): String {
        return when (capabilityMode) {
            MODE_CHAT -> chatPrompt
            MODE_SEE -> seePrompt
            MODE_WRITE -> writePrompt
            MODE_BRAINSTORM -> brainstormPrompt
            MODE_CODE -> codePrompt
            MODE_DATA -> dataPrompt
            MODE_COMMUNICATION -> communicationPrompt
            else -> chatPrompt // Default to chat mode for unknown modes
        }
    }
}
