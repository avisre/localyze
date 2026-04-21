package com.localyze.ui.viewmodels

/**
 * Represents the UI state of the Capabilities screen.
 */
data class CapabilitiesUiState(
    /** The currently selected capability mode, or null if none selected. */
    val selectedMode: String? = null,
    /** Whether a new conversation is being created. */
    val isCreating: Boolean = false
)

/**
 * Represents a single capability item displayed in the capabilities grid.
 */
data class CapabilityItem(
    /** Display title of the capability (e.g. "Chat", "Code Help"). */
    val title: String,
    /** Short description shown below the title. */
    val description: String,
    /** Mode string passed to [ChatRepository.createConversation] (e.g. "chat", "code"). */
    val mode: String,
    /** Pastel tint color for the Canvas-drawn icon, encoded as an ARGB long. */
    val iconTint: Long
)

/**
 * The complete list of capabilities displayed in the 2Ã—3 grid.
 *
 * Each item's [CapabilityItem.title] must match the dispatch keys used by
 * [com.localyze.ui.components.CapabilityCard]'s internal
 * [drawCapabilityIcon] function so the correct Canvas icon is rendered.
 */
val CAPABILITIES = listOf(
    CapabilityItem("Chat", "General assistant for any question or task", "chat", 0xFFA8C8E8),
    CapabilityItem("See & Understand", "Analyze images, read text, describe scenes", "see", 0xFFF0B090),
    CapabilityItem("Write & Draft", "Create and refine written content", "write", 0xFFA8D4B0),
    CapabilityItem("Brainstorm", "Generate ideas and explore possibilities", "brainstorm", 0xFFF0D890),
    CapabilityItem("Code Help", "Write, debug, and explain code", "code", 0xFFC8B8E8),
    CapabilityItem("Data & Charts", "Analyze data and interpret charts", "data", 0xFFA8D4D0),
    CapabilityItem("Texts & Email", "Draft replies, emails, and text messages", "communication", 0xFFF0B8D0)
)
