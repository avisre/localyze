package com.localyze.domain.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCall(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val callId: String = ""
)