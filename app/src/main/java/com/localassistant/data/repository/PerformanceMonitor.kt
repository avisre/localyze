package com.localassistant.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class PerformanceSnapshot(
    val lastPromptStartedAt: Long = 0L,
    val lastResponseCompletedAt: Long = 0L,
    val lastTokenCount: Int = 0,
    val lastTokensPerSecond: Float = 0f,
    val lastError: String? = null,
    val totalResponses: Int = 0,
    val totalTimeouts: Int = 0
)

@Singleton
class PerformanceMonitor @Inject constructor() {
    private val _snapshot = MutableStateFlow(PerformanceSnapshot())
    val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

    fun startResponse() {
        _snapshot.update {
            it.copy(
                lastPromptStartedAt = System.currentTimeMillis(),
                lastResponseCompletedAt = 0L,
                lastTokenCount = 0,
                lastTokensPerSecond = 0f,
                lastError = null
            )
        }
    }

    fun addToken(text: String) {
        if (text.isBlank()) return
        val roughTokens = (text.length / 4).coerceAtLeast(1)
        _snapshot.update { it.copy(lastTokenCount = it.lastTokenCount + roughTokens) }
    }

    fun completeResponse() {
        _snapshot.update {
            val now = System.currentTimeMillis()
            val elapsedSeconds = ((now - it.lastPromptStartedAt).coerceAtLeast(1)).toFloat() / 1000f
            it.copy(
                lastResponseCompletedAt = now,
                lastTokensPerSecond = it.lastTokenCount / elapsedSeconds,
                totalResponses = it.totalResponses + 1
            )
        }
    }

    fun recordError(message: String, timeout: Boolean = false) {
        _snapshot.update {
            it.copy(
                lastError = message,
                totalTimeouts = if (timeout) it.totalTimeouts + 1 else it.totalTimeouts
            )
        }
    }
}
