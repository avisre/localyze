package com.localyze.domain.models

data class ToolResult(
    val callId: String,
    val name: String,
    val result: String,
    val isError: Boolean = false
)