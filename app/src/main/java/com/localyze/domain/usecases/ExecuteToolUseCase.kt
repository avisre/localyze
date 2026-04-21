package com.localyze.domain.usecases

import com.localyze.domain.models.ToolCall
import com.localyze.domain.models.ToolResult
import com.localyze.tools.ToolDispatcher
import javax.inject.Inject

/**
 * Thin wrapper around [ToolDispatcher] for cleaner ViewModel code.
 *
 * This use case provides a domain-layer abstraction over tool execution,
 * keeping the ViewModel free from direct infrastructure dependencies.
 */
class ExecuteToolUseCase @Inject constructor(
    private val toolDispatcher: ToolDispatcher
) {

    /**
     * Execute a single tool call and return its result.
     *
     * @param toolCall The tool call to execute.
     * @return The result of the tool execution.
     */
    suspend fun execute(toolCall: ToolCall): ToolResult = toolDispatcher.dispatch(toolCall)

    /**
     * Execute multiple tool calls sequentially and return all results.
     *
     * @param toolCalls The list of tool calls to execute.
     * @return The list of results for each tool call, in order.
     */
    suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> =
        toolDispatcher.dispatchAll(toolCalls)
}