package com.localyze.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localyze.domain.models.Conversation
import com.localyze.ui.components.ReferenceHeader
import com.localyze.ui.theme.*
import com.localyze.ui.viewmodels.ConversationFilter
import com.localyze.ui.viewmodels.ConversationsViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    onNavigateToChat: (Long) -> Unit,
    onCreateNewChat: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pinnedConversations = viewModel.getPinnedConversations()
    val unpinnedConversations = viewModel.getUnpinnedConversations()
    val selectedCount = uiState.selectedIds.size
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Show error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(message = it, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            ReferenceHeader(
                title = "Library",
                subtitle = null,
                actions = {
                    if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = OnBackground
                        )
                    }
                    }
                    if (uiState.conversations.isNotEmpty()) {
                        var libraryMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = {
                                    if (selectedCount > 0) viewModel.clearSelection()
                                    else libraryMenuOpen = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = if (selectedCount > 0) "Cancel selection" else "Library actions",
                                    tint = TextSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = libraryMenuOpen,
                                onDismissRequest = { libraryMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        libraryMenuOpen = false
                                        viewModel.selectAllFiltered()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Delete all",
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        libraryMenuOpen = false
                                        viewModel.showClearAllConfirmDialog()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateNewChat,
                containerColor = Primary,
                shape = RoundedCornerShape(16.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = OnPrimary
                    )
                },
                text = {
                    Text(
                        text = "New chat",
                        color = OnPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Search bar
            LibrarySummaryRow(
                conversations = uiState.conversations.size,
                pinned = uiState.conversations.count { it.isPinned },
                archived = uiState.conversations.count { it.isArchived },
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = {
                    Text(
                        text = "Search conversations...",
                        fontFamily = Nunito,
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = TextSecondary
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.FilterList,
                            contentDescription = "Filter",
                            tint = TextSecondary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = SurfaceVariant,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                    cursorColor = Primary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )

            ConversationFilters(
                selectedFilter = uiState.filter,
                folders = uiState.folders,
                folderFilter = uiState.folderFilter,
                onFilterChange = { viewModel.updateFilter(it) },
                onFolderChange = { viewModel.updateFolderFilter(it) }
            )

            val totalCount = pinnedConversations.size + unpinnedConversations.size
            if (selectedCount > 0) {
                Text(
                    text = "$selectedCount selected",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (selectedCount > 0) {
                BulkActionRow(
                    onSelectAll = { viewModel.selectAllFiltered() },
                    onArchive = { viewModel.bulkArchiveSelected(true) },
                    onRestore = { viewModel.bulkArchiveSelected(false) },
                    onExport = {
                        viewModel.exportSelected { exportText ->
                            shareConversationExport(context, "Selected conversations", exportText)
                        }
                    },
                    onDelete = { viewModel.bulkDeleteSelected() }
                )
            }

            // Conversation list
            if (totalCount == 0) {
                EmptyConversationsView(hasSearch = uiState.searchQuery.isNotBlank())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pinned section
                    if (pinnedConversations.isNotEmpty()) {
                        item {
                            SectionHeader(title = "Pinned")
                        }
                        items(
                            items = pinnedConversations,
                            key = { "pinned_${it.id}" }
                        ) { conversation ->
                            ConversationItem(
                                conversation = conversation,
                                isSelected = conversation.id in uiState.selectedIds,
                                selectionMode = selectedCount > 0,
                                onClick = { onNavigateToChat(conversation.id) },
                                onSelectionToggle = { viewModel.toggleSelection(conversation.id) },
                                onPinToggle = { viewModel.togglePinConversation(conversation) },
                                onFavoriteToggle = { viewModel.toggleFavoriteConversation(conversation) },
                                onArchiveToggle = { viewModel.toggleArchiveConversation(conversation) },
                                onFolderUpdate = { folder -> viewModel.updateConversationFolder(conversation, folder) },
                                onDelete = { viewModel.showDeleteConfirmDialog(conversation) },
                                onRename = { newTitle ->
                                    viewModel.updateConversation(conversation.copy(title = newTitle))
                                },
                                onExport = {
                                    viewModel.exportConversation(conversation.id) { exportText ->
                                        shareConversationExport(context, conversation.title, exportText)
                                    }
                                }
                            )
                        }
                    }

                    // Unpinned section
                    if (unpinnedConversations.isNotEmpty()) {
                        item {
                            if (pinnedConversations.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            SectionHeader(title = "Recent")
                        }
                        items(
                            items = unpinnedConversations,
                            key = { "unpinned_${it.id}" }
                        ) { conversation ->
                            ConversationItem(
                                conversation = conversation,
                                isSelected = conversation.id in uiState.selectedIds,
                                selectionMode = selectedCount > 0,
                                onClick = { onNavigateToChat(conversation.id) },
                                onSelectionToggle = { viewModel.toggleSelection(conversation.id) },
                                onPinToggle = { viewModel.togglePinConversation(conversation) },
                                onFavoriteToggle = { viewModel.toggleFavoriteConversation(conversation) },
                                onArchiveToggle = { viewModel.toggleArchiveConversation(conversation) },
                                onFolderUpdate = { folder -> viewModel.updateConversationFolder(conversation, folder) },
                                onDelete = { viewModel.showDeleteConfirmDialog(conversation) },
                                onRename = { newTitle ->
                                    viewModel.updateConversation(conversation.copy(title = newTitle))
                                },
                                onExport = {
                                    viewModel.exportConversation(conversation.id) { exportText ->
                                        shareConversationExport(context, conversation.title, exportText)
                                    }
                                }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                        DeleteAllChatsRow(
                            count = uiState.conversations.size,
                            onClick = { viewModel.showClearAllConfirmDialog() }
                        )
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (uiState.showDeleteConfirmDialog && uiState.conversationToDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmDialog() },
            title = {
                Text(
                    text = "Delete Conversation",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = OnBackground
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${uiState.conversationToDelete?.title}\"? This action cannot be undone.",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        uiState.conversationToDelete?.id?.let {
                            viewModel.deleteConversation(it)
                        }
                    },
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
                TextButton(onClick = { viewModel.dismissDeleteConfirmDialog() }) {
                    Text(
                        text = "Cancel",
                        fontFamily = Nunito,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            },
            containerColor = Surface
        )
    }

    // Clear All Confirmation Dialog
    if (uiState.showClearAllConfirmDialog) {
        val n = uiState.conversations.size
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearAllConfirmDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Delete all chats?",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = OnBackground
                )
            },
            text = {
                Text(
                    text = "This will permanently remove all $n chat${if (n == 1) "" else "s"} " +
                        "and every message inside them. This cannot be undone.",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearAllConversations() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "Delete all",
                        fontFamily = Nunito,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearAllConfirmDialog() }) {
                    Text(
                        text = "Cancel",
                        fontFamily = Nunito,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            },
            containerColor = Surface
        )
    }
}

@Composable
private fun LibrarySummaryRow(
    conversations: Int,
    pinned: Int,
    archived: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibrarySummaryCard(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = "Conversations",
            value = conversations.toString(),
            selected = true,
            modifier = Modifier.weight(1f)
        )
        LibrarySummaryCard(
            icon = Icons.Outlined.StarBorder,
            title = "Pinned",
            value = pinned.toString(),
            modifier = Modifier.weight(1f)
        )
        LibrarySummaryCard(
            icon = Icons.Outlined.Archive,
            title = "Archived",
            value = archived.toString(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LibrarySummaryCard(
    icon: ImageVector,
    title: String,
    value: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(62.dp),
        color = if (selected) Primary.copy(alpha = 0.08f) else Surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Primary.copy(alpha = 0.18f) else SurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(21.dp)
            )
            Column {
                Text(
                    text = title,
                    color = OnBackground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ConversationFilters(
    selectedFilter: ConversationFilter,
    folders: List<String>,
    folderFilter: String,
    onFilterChange: (ConversationFilter) -> Unit,
    onFolderChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ConversationFilter.entries.forEach { filter ->
            TextButton(
                onClick = { onFilterChange(filter) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (selectedFilter == filter) Primary else TextSecondary
                )
            ) {
                Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp)
            }
        }
    }
    if (folders.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(
                onClick = { onFolderChange("") },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (folderFilter.isBlank()) Primary else TextSecondary
                )
            ) { Text("All folders", fontSize = 12.sp) }
            folders.take(3).forEach { folder ->
                TextButton(
                    onClick = { onFolderChange(folder) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (folderFilter == folder) Primary else TextSecondary
                    )
                ) { Text(folder.take(14), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun BulkActionRow(
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        TextButton(onClick = onSelectAll) { Text("Select all") }
        TextButton(onClick = onArchive) { Text("Archive") }
        TextButton(onClick = onRestore) { Text("Restore") }
        TextButton(onClick = onExport) { Text("Export") }
        TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DeleteAllChatsRow(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = count > 0,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (count > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else Surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (count > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.3f) else SurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = if (count > 0) MaterialTheme.colorScheme.error else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Delete all",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = if (count > 0) MaterialTheme.colorScheme.error else TextSecondary
                )
                Text(
                    text = if (count > 0) {
                        "Permanently remove all $count conversation${if (count == 1) "" else "s"}"
                    } else {
                        "No conversations to delete"
                    },
                    fontFamily = Nunito,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontFamily = Nunito,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = TextSecondary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onSelectionToggle: () -> Unit,
    onPinToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onFolderUpdate: (String) -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onExport: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onSelectionToggle() else onClick() },
                onLongClick = onSelectionToggle
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent
        ),
        shape = RoundedCornerShape(0.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon based on capability mode
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                val modeIcon = when (conversation.capabilityMode) {
                    "see" -> Icons.Outlined.Visibility
                    "write" -> Icons.Outlined.Edit
                    "brainstorm" -> Icons.Outlined.Lightbulb
                    "code" -> Icons.Outlined.Terminal
                    "data" -> Icons.Outlined.Assessment
                    "communication" -> Icons.Outlined.Email
                    else -> Icons.Outlined.ChatBubbleOutline
                }
                Icon(
                    imageVector = modeIcon,
                    contentDescription = conversation.capabilityMode,
                    tint = Primary,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Conversation info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = conversation.title,
                    fontFamily = Nunito,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = dateFormat.format(Date(conversation.updatedAt)),
                        fontFamily = Nunito,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    if (conversation.messageCount > 0) {
                        Text(
                            text = " \u2022 ${conversation.messageCount} messages",
                            fontFamily = Nunito,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Pin indicator
            if (conversation.isPinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (conversation.isFavorite) {
                Icon(
                    imageVector = Icons.Outlined.StarBorder,
                    contentDescription = "Favorite",
                    tint = Primary,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(19.dp)
                )
            }
            if (conversation.isArchived) {
                Text("Archived", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Conversation actions",
                    tint = TextSecondary
                )
            }
        }
    }

    // Context menu
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
            leadingIcon = {
                Icon(
                    imageVector = if (conversation.isPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                    contentDescription = null
                )
            },
            onClick = {
                onPinToggle()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(if (conversation.isFavorite) "Remove favorite" else "Favorite") },
            onClick = {
                onFavoriteToggle()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text(if (conversation.isArchived) "Restore" else "Archive") },
            onClick = {
                onArchiveToggle()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text("Move to folder") },
            onClick = {
                showFolderDialog = true
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null
                )
            },
            onClick = {
                showRenameDialog = true
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text("Export") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.IosShare,
                    contentDescription = null
                )
            },
            onClick = {
                onExport()
                showMenu = false
            }
        )
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onClick = {
                onDelete()
                showMenu = false
            }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        var newTitle by remember { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = "Rename Conversation",
                    fontFamily = Nunito,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = OnBackground
                )
            },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = SurfaceVariant,
                        cursorColor = Primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(newTitle.trim())
                        showRenameDialog = false
                    },
                    enabled = newTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(
                        text = "Save",
                        fontFamily = Nunito,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(
                        text = "Cancel",
                        fontFamily = Nunito,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            },
            containerColor = Surface
        )
    }

    if (showFolderDialog) {
        var folder by remember { mutableStateOf(conversation.folder) }
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Move to Folder", fontFamily = Nunito, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Folder or project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onFolderUpdate(folder.trim())
                        showFolderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text("Cancel") }
            },
            containerColor = Surface
        )
    }
}

private fun shareConversationExport(context: Context, title: String, exportText: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, exportText)
    }
    context.startActivity(Intent.createChooser(intent, "Export conversation"))
}

@Composable
private fun EmptyConversationsView(hasSearch: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (hasSearch) "No conversations found" else "No conversations yet",
            fontFamily = Nunito,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (hasSearch) "Try adjusting your search" else "Start a chat to create your first conversation",
            fontFamily = Nunito,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = TextSecondary
        )
    }
}
