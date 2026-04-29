package com.localyze.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
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
import com.localyze.BuildConfig
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.localyze.data.billing.BillingConstants
import com.localyze.domain.models.Memory
import com.localyze.ui.components.AssistantMark
import com.localyze.ui.components.ReferenceDivider
import com.localyze.ui.components.ReferenceHeader
import com.localyze.ui.components.ReferenceSettingsGroup
import com.localyze.ui.components.ReferenceSettingsRow
import com.localyze.ui.components.SettingsChevronRow
import com.localyze.ui.components.SettingsToggleRow
import com.localyze.ui.theme.Background
import com.localyze.ui.theme.Nunito
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.OnPrimary
import com.localyze.ui.theme.PastelGreen
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary
import com.localyze.ui.viewmodels.SettingsUiState
import com.localyze.ui.viewmodels.SettingsViewModel

// Alias to avoid clash between the Color named Surface and the Composable Surface
private val SurfaceColor = com.localyze.ui.theme.Surface

/**
 * Settings screen â€” Tab 3 of the bottom navigation.
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
        // Permissions result handled via recomposition â€” no action needed
    }

    // Dialog state
    var showModelInfoDialog by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    fun isGranted(permission: String): Boolean {
        return permission.isBlank() ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        ""
    }
    val microphoneStatus = if (isGranted(Manifest.permission.RECORD_AUDIO)) "Allowed" else "Ask"
    val notificationStatus = if (isGranted(notificationPermission)) "Allowed" else "Ask"

    SettingsReferenceContent(
        uiState = uiState,
        microphoneStatus = microphoneStatus,
        notificationStatus = notificationStatus,
        onOpenModelInfo = { showModelInfoDialog = true },
        onDeleteModel = { viewModel.showDeleteModelDialog() },
        onOpenPermissions = { showPermissionsDialog = true },
        onOpenAbout = { showAboutDialog = true },
        onBuyPremium = {
            context.findActivity()?.let { activity ->
                viewModel.launchPremiumPurchase(activity)
            } ?: viewModel.refreshPremiumSubscription()
        },
        onRestorePurchase = { viewModel.restorePremiumPurchase() },
        onManageSubscription = { openPlaySubscriptionCenter(context) },
        onSearchQueryChange = { viewModel.updateMemorySearchQuery(it) },
        onDeleteMemory = { viewModel.deleteMemory(it) },
        onEditMemory = { memory, content, keywords -> viewModel.updateMemory(memory, content, keywords) },
        onRefreshTransparency = { viewModel.refreshMemoryTransparencyText() },
        onClearMemories = { viewModel.showClearMemoriesDialog() },
        onToggleMemoryEnabled = { viewModel.toggleMemoryEnabled() },
        onReviewMemories = {
            viewModel.refreshMemoryTransparencyText()
            viewModel.showMemorySection()
        },
        onManageMemories = { viewModel.showMemorySection() },
        onToggleWebSearch = { viewModel.toggleAllowWebSearch() },
        onToggleThinking = { viewModel.toggleThinkingMode() },
        onToggleStreamTokens = { viewModel.toggleStreamTokens() },
        onToggleVoiceAutoPlay = { viewModel.toggleVoiceAutoPlay() },
        onToggleDarkMode = { viewModel.toggleDarkMode() },
        onToggleCellularDownload = { viewModel.toggleAllowCellularDownload() },
        onNavigateToDebugTools = onNavigateToDebugTools,
        onNavigateToConversations = onNavigateToConversations,
        onNavigateToToolCenter = onNavigateToToolCenter,
        onNavigateToAttachments = onNavigateToAttachments,
        onNavigateToPerformance = onNavigateToPerformance,
        onNavigateToBackups = onNavigateToBackups
    )


    // â”€â”€ Dialogs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€ Avatar Section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun SettingsReferenceContent(
    uiState: SettingsUiState,
    microphoneStatus: String,
    notificationStatus: String,
    onOpenModelInfo: () -> Unit,
    onDeleteModel: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
    onBuyPremium: () -> Unit,
    onRestorePurchase: () -> Unit,
    onManageSubscription: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onEditMemory: (Memory, String, String) -> Unit,
    onRefreshTransparency: () -> Unit,
    onClearMemories: () -> Unit,
    onToggleMemoryEnabled: () -> Unit,
    onReviewMemories: () -> Unit,
    onManageMemories: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onToggleThinking: () -> Unit,
    onToggleStreamTokens: () -> Unit,
    onToggleVoiceAutoPlay: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleCellularDownload: () -> Unit,
    onNavigateToDebugTools: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToToolCenter: () -> Unit,
    onNavigateToAttachments: () -> Unit,
    onNavigateToPerformance: () -> Unit,
    onNavigateToBackups: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 22.dp)
    ) {
        ReferenceHeader(
            title = "Settings",
            subtitle = "Privacy, subscription, and local data."
        )

        ReferenceSettingsGroup(title = "AI & Model") {
            ReferenceSettingsRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = "Model",
                subtitle = uiState.modelInfo.modelName,
                value = if (uiState.modelInfo.isDownloaded) "Installed" else "Missing",
                onClick = onOpenModelInfo
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Delete,
                title = "Delete model",
                subtitle = "Free up storage by removing the local model",
                danger = true,
                onClick = onDeleteModel
            )
        }

        ReferenceSettingsGroup(title = "Memory (Opt-In)") {
            ReferenceSettingsRow(
                icon = Icons.Outlined.Memory,
                title = "Memory",
                subtitle = if (uiState.memoryEnabled) {
                    "${uiState.memoryCount} saved items"
                } else {
                    "${uiState.memoryCount} saved items, assistant access off"
                },
                value = if (uiState.memoryEnabled) "On" else "Off",
                onClick = onToggleMemoryEnabled
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.AccountCircle,
                title = "What do you remember about me?",
                subtitle = "View saved memories",
                onClick = onReviewMemories
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Storage,
                title = "Manage memories",
                subtitle = "Review, edit, and delete",
                onClick = onManageMemories
            )
        }

        if (uiState.isMemorySectionExpanded) {
            MemorySection(
                uiState = uiState,
                onSearchQueryChange = onSearchQueryChange,
                onDeleteMemory = onDeleteMemory,
                onEditMemory = onEditMemory,
                onRefreshTransparency = onRefreshTransparency,
                onClearAll = onClearMemories
            )
        }

        ReferenceSettingsGroup(title = "Data & Privacy") {
            ReferenceSettingsRow(
                icon = Icons.Outlined.Delete,
                title = "Delete all chats",
                subtitle = "Open Library to remove conversations",
                danger = true,
                onClick = onNavigateToConversations
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Storage,
                title = "What is stored locally?",
                subtitle = "See what data stays on your device",
                onClick = onOpenAbout
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Security,
                title = "Privacy promise",
                subtitle = "No chats are sent to our servers",
                value = "Local AI",
                onClick = onOpenAbout
            )
        }

        ReferenceSettingsGroup(title = "Backup & Export") {
            ReferenceSettingsRow(
                icon = Icons.Outlined.ImportExport,
                title = "Export conversations",
                subtitle = "Save conversations as a file",
                onClick = onNavigateToBackups
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Backup,
                title = "Encrypted backup",
                subtitle = "Back up your data securely",
                onClick = onNavigateToBackups
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Backup,
                title = "Restore from backup",
                subtitle = "Restore your conversations and data",
                onClick = onNavigateToBackups
            )
        }

        val premium = uiState.premiumSubscription
        ReferenceSettingsGroup(title = "Subscription") {
            ReferenceSettingsRow(
                icon = Icons.Outlined.Bolt,
                title = premium.title,
                subtitle = premium.errorMessage ?: premium.statusMessage,
                value = premium.displayValue,
                showChevron = premium.canPurchase || premium.isPremiumActive || premium.isPending,
                onClick = when {
                    premium.canPurchase -> onBuyPremium
                    premium.isPremiumActive || premium.isPending -> onManageSubscription
                    else -> null
                }
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.History,
                title = "Restore purchase",
                subtitle = "Recover from Google Play",
                value = if (premium.isLoading) "Checking" else null,
                onClick = onRestorePurchase
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Settings,
                title = "Manage subscription",
                subtitle = "Billing is handled by Google Play",
                onClick = onManageSubscription
            )
        }

        ReferenceSettingsGroup(title = "Assistant") {
            ReferenceSettingsRow(
                icon = Icons.Outlined.Public,
                title = "Web search",
                subtitle = "Let the assistant fetch current sourced results",
                checked = uiState.allowWebSearch,
                onCheckedChange = { onToggleWebSearch() },
                showChevron = false
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Settings,
                title = "Thinking mode",
                subtitle = "Show reasoning traces",
                checked = uiState.thinkingMode,
                onCheckedChange = { onToggleThinking() },
                showChevron = false
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Bolt,
                title = "Stream tokens",
                subtitle = "Show responses while generating",
                checked = uiState.streamTokens,
                onCheckedChange = { onToggleStreamTokens() },
                showChevron = false
            )
        }

        ReferenceSettingsGroup(title = "Support & Safety") {
            ReferenceSettingsRow(
                icon = Icons.Outlined.Image,
                title = "Report / Flag response",
                subtitle = "Help improve safety",
                danger = true,
                onClick = onOpenAbout
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Mic,
                title = "Microphone",
                subtitle = "Permission status: $microphoneStatus",
                onClick = onOpenPermissions
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.BugReport,
                title = "Performance",
                subtitle = "Model speed, RAM, crashes",
                onClick = onNavigateToPerformance
            )
            ReferenceDivider()
            ReferenceSettingsRow(
                icon = Icons.Outlined.Info,
                title = "About",
                subtitle = "Version ${BuildConfig.VERSION_NAME}",
                onClick = onOpenAbout
            )
        }
    }
}

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
            text = "Localyze",
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

// â”€â”€ Memory Section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextSecondary
                )
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
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete memory",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
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

// â”€â”€ Model Info Dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€ Delete Model Confirmation Dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€ Clear Memories Confirmation Dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€ Permissions Dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            PermissionEntry("Contacts", Manifest.permission.READ_CONTACTS, "\uD83D\uDC65"),
            PermissionEntry("Calendar", Manifest.permission.READ_CALENDAR, "\uD83D\uDCC5"),
            PermissionEntry("Microphone", Manifest.permission.RECORD_AUDIO, "\uD83C\uDFA4"),
            PermissionEntry("Notifications", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else "", "\uD83D\uDD14")
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
                            if (false) {
                            Text(text = entry.icon, fontSize = 18.sp)
                            }
                            Icon(
                                imageVector = when (entry.name) {
                                    "Contacts" -> Icons.Outlined.AccountCircle
                                    "Calendar" -> Icons.Outlined.History
                                    "Microphone" -> Icons.Outlined.Mic
                                    "Notifications" -> Icons.Outlined.NotificationsNone
                                    else -> Icons.Outlined.Folder
                                },
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(18.dp)
                            )
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
                                text = "Granted",
                                fontFamily = Nunito,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = PastelGreen
                            )
                        } else {
                            Text(
                                text = "Denied",
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

// â”€â”€ About Dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "About Localyze",
                fontFamily = Nunito,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = OnBackground
            )
        },
        text = {
            Column {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "A private, on-device AI assistant powered by Google's Gemma 4 E4B model. All processing happens locally, and your data stays on your device.",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = OnBackground,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Built for privacy",
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

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun openPlaySubscriptionCenter(context: Context) {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
                "?sku=${Uri.encode(BillingConstants.PREMIUM_SUBSCRIPTION_PRODUCT_ID)}" +
                "&package=${Uri.encode(context.packageName)}"
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
