package com.localassistant.ui.components

/**
 * Formats a timestamp (in milliseconds since epoch) into a human-readable relative time string.
 *
 * Rules:
 * - < 60 seconds: "just now"
 * - < 60 minutes: "{n}m ago"
 * - < 24 hours: "{h}h ago"
 * - < 48 hours: "yesterday"
 * - else: "{d}d ago"
 */
fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diffMillis = now - timestamp

    if (diffMillis < 0) return "just now"

    val seconds = diffMillis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        hours < 48 -> "yesterday"
        else -> "${days}d ago"
    }
}