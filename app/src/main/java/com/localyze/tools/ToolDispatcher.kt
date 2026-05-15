package com.localyze.tools

import com.localyze.data.repository.ToolAuditRepository
import com.localyze.domain.models.ToolCall
import com.localyze.domain.models.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a dispatch operation that may require confirmation.
 */
sealed class DispatchResult {
    /**
     * Tool executed immediately and returned a result.
     */
    data class Completed(val toolResult: ToolResult) : DispatchResult()

    /**
     * Tool requires user confirmation before execution.
     */
    data class PendingConfirmation(
        val tool: Tool,
        val toolCall: ToolCall,
        val message: String
    ) : DispatchResult()
}

@Singleton
class ToolDispatcher @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val toolAuditRepository: ToolAuditRepository
) {

    /**
     * Retrieve a registered tool by name.
     */
    fun getTool(toolName: String): Tool? = toolRegistry.getTool(toolName)

    /**
     * Check if a tool requires confirmation before execution.
     */
    fun requiresConfirmation(toolName: String): Boolean {
        val tool = toolRegistry.getTool(toolName) ?: return false
        return tool.requiresConfirmation()
    }

    fun riskLevel(toolName: String): String = when (toolName) {
        "clipboard", "calendar", "alarm", "email_draft", "sms_draft", "memory", "task" -> "high"
        "contacts", "web_search", "file_reader" -> "medium"
        else -> "low"
    }

    /**
     * Dispatch a tool call, returning either a completed result or a pending confirmation.
     */
    suspend fun dispatchWithConfirmation(toolCall: ToolCall): DispatchResult {
        val tool = toolRegistry.getTool(toolCall.name)
        if (tool == null) {
            val result = unknownTool(toolCall)
            recordAudit(toolCall, "error", false, result.result)
            return DispatchResult.Completed(result)
        }

        // If tool requires confirmation, return pending status
        if (tool.requiresConfirmation()) {
            val action = getActionDescription(toolCall)
            recordAudit(toolCall, "pending", true, action)
            return DispatchResult.PendingConfirmation(
                tool = tool,
                toolCall = toolCall,
                message = "$action This action will modify data on your device."
            )
        }

        // Otherwise execute immediately
        return DispatchResult.Completed(executeTool(tool, toolCall))
    }

    /**
     * Execute a tool call immediately (for confirmed or non-confirming tools).
     */
    suspend fun dispatch(toolCall: ToolCall): ToolResult {
        val tool = toolRegistry.getTool(toolCall.name)
        if (tool == null) {
            val result = unknownTool(toolCall)
            recordAudit(toolCall, "error", false, result.result)
            return result
        }

        return executeTool(tool, toolCall)
    }

    /**
     * Confirm and execute a pending tool call.
     */
    suspend fun confirmAndExecute(pending: DispatchResult.PendingConfirmation): ToolResult {
        return executeTool(pending.tool, pending.toolCall)
    }

    private suspend fun executeTool(tool: Tool, toolCall: ToolCall): ToolResult {
        return try {
            val result = tool.execute(toolCall.arguments)
            recordAudit(toolCall, "completed", tool.requiresConfirmation(), result)
            ToolResult(
                callId = toolCall.callId,
                name = toolCall.name,
                result = result,
                isError = false
            )
        } catch (e: SecurityException) {
            val message = "Permission denied: ${e.message}. Please grant the required permission."
            recordAudit(toolCall, "error", tool.requiresConfirmation(), message)
            ToolResult(
                callId = toolCall.callId,
                name = toolCall.name,
                result = message,
                isError = true
            )
        } catch (e: Exception) {
            val message = "Error executing ${toolCall.name}: ${e.message}"
            recordAudit(toolCall, "error", tool.requiresConfirmation(), message)
            ToolResult(
                callId = toolCall.callId,
                name = toolCall.name,
                result = message,
                isError = true
            )
        }
    }

    suspend fun dispatchAll(toolCalls: List<ToolCall>): List<ToolResult> {
        return toolCalls.map { dispatch(it) }
    }

    /**
     * Get a human-readable description of what the tool call will do.
     */
    private fun getActionDescription(toolCall: ToolCall): String {
        return when (toolCall.name) {
            "clipboard" -> {
                val action = (toolCall.arguments["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (action == "write") {
                    val text = (toolCall.arguments["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(50)
                    "The assistant wants to copy \"$text...\" to your clipboard."
                } else "The assistant wants to access your clipboard."
            }
            "memory" -> {
                val action = (toolCall.arguments["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (action == "save") {
                    val content = (toolCall.arguments["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(50)
                    "The assistant wants to save \"$content...\" to long-term memory."
                } else "The assistant wants to search your memories."
            }
            "task" -> {
                val action = (toolCall.arguments["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                when (action) {
                    "create" -> {
                        val title = (toolCall.arguments["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        "The assistant wants to create a task: \"$title\"."
                    }
                    "complete" -> "The assistant wants to mark a task as complete."
                    else -> "The assistant wants to access your tasks."
                }
            }
            else -> "The assistant wants to use the ${toolCall.name} tool."
        }
    }

    private fun unknownTool(toolCall: ToolCall): ToolResult = ToolResult(
        callId = toolCall.callId,
        name = toolCall.name,
        result = "Unknown tool: ${toolCall.name}",
        isError = true
    )

    private suspend fun recordAudit(
        toolCall: ToolCall,
        status: String,
        requiresConfirmation: Boolean,
        resultPreview: String
    ) {
        runCatching {
            toolAuditRepository.record(
                toolName = toolCall.name,
                riskLevel = riskLevel(toolCall.name),
                status = status,
                requiresConfirmation = requiresConfirmation,
                argumentsPreview = toolCall.arguments.toString(),
                resultPreview = resultPreview
            )
        }
    }
}
