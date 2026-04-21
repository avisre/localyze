package com.localassistant.tools

import kotlinx.serialization.json.JsonObject

interface Tool {
    val name: String
    val description: String

    suspend fun execute(args: JsonObject): String

    fun getParameterSchema(): JsonObject

    /**
     * Indicates whether this tool performs a mutating action that should
     * be confirmed by the user before execution.
     *
     * Mutating actions include:
     * - Writing to clipboard (overwrites user clipboard)
     * - Saving to memory (persists data)
     * - Creating tasks (adds to task list)
     * - Writing to calendar (creates events)
     *
     * @return true if the tool requires user confirmation
     */
    fun requiresConfirmation(): Boolean = false
}