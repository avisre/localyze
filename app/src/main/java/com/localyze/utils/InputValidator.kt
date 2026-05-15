package com.localyze.utils

import java.net.URI

/**
 * Utility class for input validation throughout the app.
 */
object InputValidator {

    // Maximum lengths for various inputs
    const val MAX_MESSAGE_LENGTH = 10_000
    const val MAX_CONVERSATION_TITLE_LENGTH = 100
    const val MAX_MEMORY_CONTENT_LENGTH = 5_000
    const val MAX_TASK_TITLE_LENGTH = 200
    const val MAX_TASK_DESCRIPTION_LENGTH = 2_000
    const val MAX_SEARCH_QUERY_LENGTH = 500

    // Minimum lengths
    const val MIN_MESSAGE_LENGTH = 1
    const val MIN_SEARCH_QUERY_LENGTH = 2

    /**
     * Validates a user message.
     * @return ValidationResult with success status and optional error message
     */
    fun validateMessage(content: String?): ValidationResult {
        if (content.isNullOrBlank()) {
            return ValidationResult.Error("Message cannot be empty")
        }
        // Reject dangerous patterns in the original text before sanitization
        if (containsDangerousPatterns(content)) {
            return ValidationResult.Error("Message contains invalid characters")
        }
        val sanitized = sanitizeText(content)
        if (sanitized.isBlank()) {
            return ValidationResult.Error("Message contains only invalid characters")
        }
        if (sanitized.length < MIN_MESSAGE_LENGTH) {
            return ValidationResult.Error("Message is too short")
        }
        if (sanitized.length > MAX_MESSAGE_LENGTH) {
            return ValidationResult.Error(
                "Message is too long (${sanitized.length}/$MAX_MESSAGE_LENGTH characters)"
            )
        }
        return ValidationResult.Success
    }

    /**
     * Validates a conversation title.
     */
    fun validateConversationTitle(title: String?): ValidationResult {
        if (title.isNullOrBlank()) {
            return ValidationResult.Error("Title cannot be empty")
        }
        if (title.length > MAX_CONVERSATION_TITLE_LENGTH) {
            return ValidationResult.Error(
                "Title is too long (${title.length}/$MAX_CONVERSATION_TITLE_LENGTH characters)"
            )
        }
        return ValidationResult.Success
    }

    /**
     * Validates memory content.
     */
    fun validateMemoryContent(content: String?): ValidationResult {
        if (content.isNullOrBlank()) {
            return ValidationResult.Error("Memory content cannot be empty")
        }
        if (content.length > MAX_MEMORY_CONTENT_LENGTH) {
            return ValidationResult.Error(
                "Memory is too long (${content.length}/$MAX_MEMORY_CONTENT_LENGTH characters)"
            )
        }
        return ValidationResult.Success
    }

    /**
     * Validates a task title.
     */
    fun validateTaskTitle(title: String?): ValidationResult {
        if (title.isNullOrBlank()) {
            return ValidationResult.Error("Task title cannot be empty")
        }
        if (title.length > MAX_TASK_TITLE_LENGTH) {
            return ValidationResult.Error(
                "Title is too long (${title.length}/$MAX_TASK_TITLE_LENGTH characters)"
            )
        }
        return ValidationResult.Success
    }

    /**
     * Validates a search query.
     */
    fun validateSearchQuery(query: String?): ValidationResult {
        if (query.isNullOrBlank()) {
            return ValidationResult.Error("Search query cannot be empty")
        }
        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            return ValidationResult.Error("Search query is too short")
        }
        if (query.length > MAX_SEARCH_QUERY_LENGTH) {
            return ValidationResult.Error(
                "Search query is too long"
            )
        }
        return ValidationResult.Success
    }

    /**
     * Validates a URL.
     */
    fun validateUrl(url: String?): ValidationResult {
        if (url.isNullOrBlank()) {
            return ValidationResult.Error("URL cannot be empty")
        }
        val parsed = try {
            URI(url.trim())
        } catch (_: Exception) {
            return ValidationResult.Error("Invalid URL format")
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return ValidationResult.Error("Only HTTP and HTTPS URLs are allowed")
        }
        if (parsed.host.isNullOrBlank()) {
            return ValidationResult.Error("Invalid URL format")
        }
        return ValidationResult.Success
    }

    /**
     * Sanitizes text input by removing potentially dangerous characters.
     */
    fun sanitizeText(input: String?): String {
        if (input == null) return ""
        // Build regex for control characters using Unicode escapes
        val controlChars = (0x00..0x08).plus(0x0B..0x0C).plus(0x0E..0x1F).plus(0x7F)
            .joinToString("") { "\\u${it.toString(16).padStart(4, '0')}" }
        val cleaned = input.replace(Regex("[$controlChars]"), "")
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<\\s*/?\\s*script[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<\\s*script", RegexOption.IGNORE_CASE), "")
            .replace(Regex("javascript:", RegexOption.IGNORE_CASE), "")
            .replace(Regex("on\\w+\\s*=", RegexOption.IGNORE_CASE), "")
            .trim()
        return cleaned
    }

    /**
     * Truncates text to a maximum length with ellipsis.
     */
    fun truncateWithEllipsis(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) {
            text
        } else {
            text.take(maxLength - 3) + "..."
        }
    }

    /**
     * Checks if text contains potentially dangerous patterns.
     */
    private fun containsDangerousPatterns(content: String): Boolean {
        val dangerousPatterns = listOf(
            Regex("<\\s*script", RegexOption.IGNORE_CASE),
            Regex("javascript\\s*:", RegexOption.IGNORE_CASE),
            Regex("data\\s*:\\s*text/html", RegexOption.IGNORE_CASE),
            Regex("vbscript\\s*:", RegexOption.IGNORE_CASE),
            Regex("on\\w+\\s*=", RegexOption.IGNORE_CASE),
            Regex("eval\\s*\\(", RegexOption.IGNORE_CASE),
            Regex("expression\\s*\\(", RegexOption.IGNORE_CASE),
            Regex("drop\\s+table", RegexOption.IGNORE_CASE),
            Regex("\\$\\{"),
            Regex("\\{\\{"),
            Regex("<%"),
            Regex("#include", RegexOption.IGNORE_CASE),
            Regex("null_byte", RegexOption.IGNORE_CASE),
            Regex("\u0000")
        )
        return dangerousPatterns.any { it.containsMatchIn(content) }
    }
}

/**
 * Sealed class representing validation results.
 */
sealed class ValidationResult {
    data object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
}
