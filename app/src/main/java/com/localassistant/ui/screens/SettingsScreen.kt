package com.localassistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.localassistant.domain.models.Memory
import com.localassistant.ui.components.SettingsChevronRow
import com.localassistant.ui.components.SettingsToggleRow
import com.localassistant.ui.theme.Background
import com.localassistant.ui.theme.Nunito
import com.localassistant.ui.theme.OnBackground
import com.localassistant.ui.theme.OnPrimary
import com.localassistant.ui.theme.PastelGreen
import com.localassistant.ui.theme.Primary
import com.localassistant.ui.theme.SurfaceVariant
import com.localassistant.ui.theme.TextSecondary
import com.localassistant.ui.viewmodels.SettingsUiState
import com.localassistant.ui.viewmodels.SettingsViewModel

// Alias to avoid clash between the Color named Surface and the Composable Surface
private val SurfaceColor = com.localassistant.ui.theme.Surface

/**
 * Settings screen — Tab 3 of the bottom navigation.
 *
 * Provides toggles for app preferences, access to model info,
 * memory management, permissions info, and about details.
 *
 * Debug tool tester easter egg: tap version number 5 times.
 */
@Composable
fun SettingsScreen(
    onNavigateToDebugTools: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {},
    onNavigateToConversations: () -> Unit = {},
    onNavigateToToolCenter: () -> Unit = {},
    onNavigateToAttachments: () -> Unit = {},
    onNavigateToReplies: () -> Unit = {},
    onNavigateToPerformance: () -> Unit = {},
    onNavigateToBackups: () -> Unit = {},
    onNavigateToProactive: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Permissions result handled via recomposition — no action needed
    }

    // Dialog state
    var showModelInfoDialog by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ── Avatar & App Identity ────────────────────────────────────────────
        Spacer(modifier = Modifier.height(16.dp))

        AvatarSection(onVersionTaps = onNavigateToDebugTools)

        Spacer(modifier = Modifier.height(16.dp))

        // ── Toggle Rows ─────────────────────────────────────────────────────
        SettingsToggleRow(
            icon = "🌙",
            title = "Dark mode",
            subtitle = "Use dark theme",
            checked = uiState.darkMode,
            onCheckedChange = { viewModel.toggleDarkMode() }
        )

        SettingsToggleRow(
            icon = "🧠",
            title = "Thinking mode",
            subtitle = "Show AI reasoning traces",
            checked = uiState.thinkingMode,
            onCheckedChange = { viewModel.toggleThinkingMode() }
        )

        SettingsToggleRow(
            icon = "⚡",
            title = "Stream tokens",
            subtitle = "Display tokens as they generate",
            checked = uiState.streamTokens,
            onCheckedChange = { viewModel.toggleStreamTokens() }
        )

        SettingsToggleRow(
            icon = "🔊",
            title = "Voice auto-play",
            subtitle = "Automatically read AI responses aloud",
            checked = uiState.voiceAutoPlay,
            onCheckedChange = { viewModel.toggleVoiceAutoPlay() }
        )

        SettingsToggleRow(
            icon = "🌐",
            title = "Allow web search",
            subtitle = "Enable DuckDuckGo search tool",
            checked = uiState.allowWebSearch,
            onCheckedChange = { viewModel.toggleAllowWebSearch() }
        )

        SettingsToggleRow(
            icon = "📶",
            title = "Allow cellular download",
            subtitle = "Download model over mobile data (uses ~3.6GB)",
            checked = uiState.allowCellularDownload,
            onCheckedChange = { viewModel.toggleAllowCellularDownload() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Chevron Rows ─────────────────────────────────────────────────────
        SettingsChevronRow(
            icon = "📋",
            title = "Tasks",
            subtitle = "Manage your to-do list",
            onClick = onNavigateToTasks
        )

        SettingsChevronRow(
            icon = "💬",
            title = "Conversations",
            subtitle = "Archive, favorite, folders, bulk actions",
            onClick = onNavigateToConversations
        )

        SettingsChevronRow(
            icon = "OK",
            title = "Tool approval center",
            subtitle = "Review permissions, risk, and history",
            onClick = onNavigateToToolCenter
        )

        SettingsChevronRow(
            icon = "+",
            title = "Attachments",
            subtitle = "Save and search files later",
            onClick = onNavigateToAttachments
        )

        SettingsChevronRow(
            icon = "@",
            title = "Reply assistant",
            subtitle = "Draft replies for texts and email",
            onClick = onNavigateToReplies
        )

        SettingsChevronRow(
            icon = "%",
            title = "Performance",
            subtitle = "Model, RAM, storage, speed, errors",
            onClick = onNavigateToPerformance
        )

        SettingsChevronRow(
            icon = "#",
            title = "Backups",
            subtitle = "Encrypted local backup and restore",
            onClick = onNavigateToBackups
        )

        SettingsChevronRow(
            icon = "!",
            title = "Proactive assistant",
            subtitle = "Reminders, follow-ups, summaries",
            onClick = onNavigateToProactive
        )

        SettingsChevronRow(
            icon = "📱",
            title = "Model info",
            onClick = { showModelInfoDialog = true }
        )

        SettingsChevronRow(
            icon = "💾",
            title = "Memory & context",
            onClick = { viewModel.toggleMemorySection() }
        )

        SettingsChevronRow(
            icon = "🔑",
            title = "Permissions",
            onClick = { showPermissionsDialog = true }
        )

        SettingsChevronRow(
            icon = "ℹ️",
            title = "About",
            onClick = { showAboutDialog = true }
        )

        // ── Memory & Context Section (expandable) ───────────────────────────
        if (uiState.isMemorySectionExpanded) {
            MemorySection(
                uiState = uiState,
                onSearchQueryChange = { viewModel.updateMemorySearchQuery(it) },
                onDeleteMemory = { viewModel.deleteMemory(it) },
                onEditMemory = { memory, content, keywords -> viewModel.updateMemory(memory, content, keywords) },
                onRefreshTransparency = { viewModel.refreshMemoryTransparencyText() },
                onClearAll = { viewModel.showClearMemoriesDialog() }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    if (showModelInfoDialog) {
        ModelInfoDialog(
            uiState = uiState,
            onDismiss = { showModelInfoDialog = false },
            onDeleteModel = {
                viewModel.showDeleteModelDialog()
                showModelInfoDialog = false
            }
        )
    }

    if (uiState.showDeleteModelDialog) {
        DeleteModelConfirmationDialog(
            onConfirm = { viewModel.deleteModel() },
            onDismiss = { viewModel.dismissDeleteModelDialog() }
        )
    }

    if (showPermissionsDialog) {
        PermissionsDialog(
            onDismiss = { showPermissionsDialog = false },
            onRequestPermissions = { permissions ->
                permissionLauncher.launch(permissions.toTypedArray())
            }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    if (uiState.showClearMemoriesDialog) {
        ClearMemoriesConfirmationDialog(
            onConfirm = { viewModel.clearAllMemories() },
            onDismiss = { viewModel.dismissClearMemoriesDialog() }
        )
    }
}

// ── Avatar Section ──────────────────────────────────────────────────────────

@Composable
private fun AvatarSection(
    onVersionTaps: () -> Unit = {}
) {
    var tapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circle avatar with "LA" initials
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "LA",
                fontFamily = Nunito,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = OnPrimary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Local Assistant",
            fontFamily = Nunito,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Version text with easter egg (5 taps triggers debug tools)
        Text(
            text = "v1.0.0",
            fontFamily = Nunito,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.clickable {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime > 1000) {
                    tapCount = 1 // Reset if more than 1 second between taps
                } else {
                    tapCount++
                }
                lastTapTime = currentTime
                if (tapCount >= 5) {
                    tapCount = 0
                    onVersionTaps()
                }
            }
        )
    }
}

// ── Memory Section ──────────────────────────────────────────────────────────

@Composable
private fun MemorySection(
    uiState: SettingsUiState,
    onSearchQueryChange: (String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onEditMemory: (Memory, String, String) -> Unit,
    onRefreshTransparency: () -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Header
        Text(
            text = "Saved Memories (${uiState.memoryCount})",
            fontFamily = Nunito,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = OnBackground,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
        )

        // Search bar
        OutlinedTextField(
            value = uiState.memorySearchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = {
                Text(
                    text = "Search memories...",
                    fontFamily = Nunito,
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            },
            leadingIcon = {
                Text(text = "🔍", fontSize = 18.sp)
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = SurfaceVariant,
                focusedContainerColor = SurfaceColor,
                unfocusedContainerColor = SurfaceColor,
                cursorColor = Primary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onRefreshTransparency) {
                Text("What do you remember?")
            }
        }

        if (uiState.memoryTransparencyText.isNotBlank()) {
            Text(
                text = uiState.memoryTransparencyText,
                fontFamily = Nunito,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Memory list
        val displayMemories = if (uiState.memorySearchQuery.isNotBlank()) {
            uiState.searchResults
        } else {
            uiState.memories
        }

        if (displayMemories.isEmpty()) {
            Text(
                text = "No memories saved yet",
                fontFamily = Nunito,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = displayMemories,
                    key = { it.id }
                ) { memory ->
                    MemoryItem(
                        memory = memory,
                        onEdit = { content, keywords -> onEditMemory(memory, content, keywords) },
                        onDelete = { onDeleteMemory(memory.id) }
                    )
                }
            }
        }

        // Clear all button
        if (uiState.memories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onClearAll,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Clear All Memories",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        HorizontalDivider(
            color = SurfaceVariant,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun MemoryItem(
    memory: Memory,
    onEdit: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Content text
                Text(
                    text = memory.content,
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = OnBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Keywords as chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (keyword in memory.keywords.take(5)) {
                        KeywordChip(keyword = keyword)
                    }
                }
            }

            TextButton(onClick = { showEditDialog = true }) {
                Text("Edit")
            }

            // Delete icon button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = "🗑️",
                    fontSize = 16.sp
                )
            }
        }
    }

    if (showEditDialog) {
        var content by remember(memory.id) { mutableStateOf(memory.content) }
        var keywords by remember(memory.id) { mutableStateOf(memory.keywords.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Memory") }
                    )
                    OutlinedTextField(
                        value = keywords,
                        onValueChange = { keywords = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Keywords") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEdit(content, keywords)
                        showEditDialog = false
                    },
                    enabled = content.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            },
            containerColor = SurfaceColor
        )
    }
}

@Composable
private fun KeywordChip(keyword: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant
    ) {
        Text(
            text = keyword,
            fontFamily = Nunito,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

// ── Model Info Dialog ───────────────────────────────────────────────────────

@Composable
private fun ModelInfoDialog(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onDeleteModel: () -> Unit
) {
    val modelInfo = uiState.modelInfo

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Model Information",
                fontFamily = Nunito,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = OnBackground
            )
        },
        text = {
            Column {
                ModelInfoRow("Model name", modelInfo.modelName)
                ModelInfoRow("Quantization", modelInfo.quantization)
                ModelInfoRow("Context window", modelInfo.contextWindow)
                ModelInfoRow("Model size", if (modelInfo.modelSizeMb > 0) "${modelInfo.modelSizeMb} MB" else "Not downloaded")
                ModelInfoRow("Status", when {
                    modelInfo.isLoaded -> "Loaded"
                    modelInfo.isDownloaded -> "Downloaded (not loaded)"
                    else -> "Not downloaded"
                })
                ModelInfoRow("Downloaded", if (modelInfo.isDownloaded) "Yes" else "No")
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (modelInfo.isDownloaded) {
                    TextButton(
                        onClick = onDeleteModel,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            text = "Delete Model",
                            fontFamily = Nunito,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Close",
                        fontFamily = Nunito,
                        fontWeight = FontWeight.SemiBold,
                        color = Primary
                    )
                }
            }
        },
        containerColor = SurfaceColor
    )
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontFamily = Nunito,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = TextSecondary
        )
        Text(
            text = value,
            fontFamily = Nunito,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = OnBackground
        )
    }
}

// ── Delete Model Confirmation Dialog ────────────────────────────────────────

@Composable
private fun DeleteModelConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete Model",
                fontFamily = Nunito,
                fontWeight = FontWeight.Bold,
                color = OnBackground
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete the model file? You will need to download it again to use the assistant.",
                fontFamily = Nunito,
                fontWeight = FontWeight.Normal,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = "Delete",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }
        },
        containerColor = SurfaceColor
    )
}

// ── Clear Memories Confirmation Dialog ──────────────────────────────────────

@Composable
private fun ClearMemoriesConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Clear All Memories",
                fontFamily = Nunito,
                fontWeight = FontWeight.Bold,
                color = OnBackground
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete all saved memories? This action cannot be undone.",
                fontFamily = Nunito,
                fontWeight = FontWeight.Normal,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = "Clear All",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }
        },
        containerColor = SurfaceColor
    )
}

// ── Permissions Dialog ───────────────────────────────────────────────────────

@Composable
private fun PermissionsDialog(
    onDismiss: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit
) {
    val context = LocalContext.current

    data class PermissionEntry(
        val name: String,
        val permission: String,
        val icon: String
    )

    val permissionEntries = remember {
        listOf(
            PermissionEntry("Camera", Manifest.permission.CAMERA, "📷"),
            PermissionEntry("Contacts", Manifest.permission.READ_CONTACTS, "👥"),
            PermissionEntry("Calendar", Manifest.permission.READ_CALENDAR, "📅"),
            PermissionEntry("Microphone", Manifest.permission.RECORD_AUDIO, "🎤"),
            PermissionEntry("Notifications", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else "", "🔔"),
            PermissionEntry("Storage", Manifest.permission.READ_MEDIA_IMAGES, "📁")
        )
    }

    val deniedPermissions = remember(permissionEntries) {
        permissionEntries.filter { entry ->
            entry.permission.isNotEmpty() &&
                ContextCompat.checkSelfPermission(context, entry.permission) != PackageManager.PERMISSION_GRANTED
        }.map { it.permission }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Permissions",
                fontFamily = Nunito,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = OnBackground
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (entry in permissionEntries) {
                    if (entry.permission.isEmpty()) continue

                    val isGranted = ContextCompat.checkSelfPermission(
                        context, entry.permission
                    ) == PackageManager.PERMISSION_GRANTED

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = entry.icon, fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry.name,
                                fontFamily = Nunito,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = OnBackground
                            )
                        }

                        if (isGranted) {
                            Text(
                                text = "✓ Granted",
                                fontFamily = Nunito,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = PastelGreen
                            )
                        } else {
                            Text(
                                text = "✗ Denied",
                                fontFamily = Nunito,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (deniedPermissions.isNotEmpty()) {
                    Button(
                        onClick = { onRequestPermissions(deniedPermissions) },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text(
                            text = "Request",
                            fontFamily = Nunito,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Close",
                        fontFamily = Nunito,
                        fontWeight = FontWeight.SemiBold,
                        color = Primary
                    )
                }
            }
        },
        containerColor = SurfaceColor
    )
}

// ── About Dialog ────────────────────────────────────────────────────────────

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "About Local Assistant",
                fontFamily = Nunito,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = OnBackground
            )
        },
        text = {
            Column {
                Text(
                    text = "Version 1.0.0",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "A private, on-device AI assistant powered by Google's Gemma 4 E4B model. All processing happens locally — your data never leaves your device.",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = OnBackground,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Built with ❤️ for privacy",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Primary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Close",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    color = Primary
                )
            }
        },
        containerColor = SurfaceColor
    )
}
