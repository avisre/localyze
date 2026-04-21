package com.localassistant.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localassistant.ai.AudioRecordingState
import com.localassistant.domain.models.MessageRole
import com.localassistant.ui.components.AssistantMessageBubble
import com.localassistant.ui.components.AudioRecorderButton
import com.localassistant.ui.components.AudioWaveform
import com.localassistant.ui.components.HandleToolConfirmation
import com.localassistant.ui.components.RobotMascot
import com.localassistant.ui.components.ThinkingBubble
import com.localassistant.ui.components.ToolIndicator
import com.localassistant.ui.components.UserMessageBubble
import com.localassistant.ui.components.rememberToolConfirmationState
import com.localassistant.ui.theme.Background
import com.localassistant.ui.theme.Error
import com.localassistant.ui.theme.OnBackground
import com.localassistant.ui.theme.OnPrimary
import com.localassistant.ui.theme.Primary
import com.localassistant.ui.theme.Secondary
import com.localassistant.ui.theme.SurfaceVariant
import com.localassistant.ui.theme.TextSecondary
import com.localassistant.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The main chat screen composable.
 *
 * Layout:
 * - Top: Conversation header and empty-state mascot
 * - Middle: Scrollable message list with LazyColumn
 * - Bottom: Pill-shaped input bar with text field, mic, send, and attach buttons
 * - Header action: New conversation button, hidden during streaming
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onNewConversation: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    sharedText: String? = null,
    sharedImageUris: List<String> = emptyList(),
    onSharedContentConsumed: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val expandedThinking by viewModel.expandedThinking.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    var isSpeechReady by remember { mutableStateOf(false) }
    var speechConversationId by remember { mutableStateOf<Long?>(null) }
    var lastSpokenMessageId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(context) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            isSpeechReady = status == TextToSpeech.SUCCESS
            if (status == TextToSpeech.SUCCESS) {
                // Check if default language is available
                val langResult = tts?.setLanguage(Locale.getDefault())
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    // Fall back to English if default language not supported
                    tts?.setLanguage(Locale.ENGLISH)
                }
            }
        }
        textToSpeech = tts
        onDispose {
            tts?.stop()
            tts?.shutdown()
            textToSpeech = null
            isSpeechReady = false
        }
    }

    // Input field state
    var inputText by remember { mutableStateOf("") }
    val isInputValid = inputText.isNotBlank()

    // Message list scroll state
    val listState = rememberLazyListState()
    val isRecording = recordingState is AudioRecordingState.Recording
    var voiceAmplitudes by remember { mutableStateOf(emptyList<Float>()) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            viewModel.amplitudeFlow.collect { amplitude ->
                voiceAmplitudes = (voiceAmplitudes + amplitude).takeLast(36)
            }
        } else {
            voiceAmplitudes = emptyList()
        }
    }

    LaunchedEffect(sharedText, sharedImageUris, uiState.isStreaming) {
        if (uiState.isStreaming) return@LaunchedEffect

        val hasSharedText = !sharedText.isNullOrBlank()
        val firstImageUri = sharedImageUris.firstOrNull()
        if (!hasSharedText && firstImageUri == null) return@LaunchedEffect

        if (firstImageUri != null) {
            try {
                val bitmap = loadBitmapFromUri(context, Uri.parse(firstImageUri))
                viewModel.sendImageMessage(
                    text = sharedText.orEmpty().ifBlank { "Describe this image" },
                    imageBitmap = bitmap
                )
                onSharedContentConsumed()
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Failed to load shared image: ${e.message}",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        } else if (hasSharedText) {
            viewModel.sendMessage(sharedText!!.trim())
            onSharedContentConsumed()
        }
    }

    LaunchedEffect(
        uiState.currentConversationId,
        uiState.messages.size,
        uiState.isStreaming,
        uiState.voiceAutoPlay,
        isSpeechReady
    ) {
        val lastAssistantMessage = uiState.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }

        if (speechConversationId != uiState.currentConversationId) {
            speechConversationId = uiState.currentConversationId
            lastSpokenMessageId = lastAssistantMessage?.id
            return@LaunchedEffect
        }

        if (!uiState.voiceAutoPlay) {
            textToSpeech?.stop()
            return@LaunchedEffect
        }

        if (!uiState.isStreaming &&
            isSpeechReady &&
            lastAssistantMessage != null &&
            lastAssistantMessage.id != lastSpokenMessageId
        ) {
            lastSpokenMessageId = lastAssistantMessage.id
            textToSpeech?.speak(
                lastAssistantMessage.content,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "assistant-${lastAssistantMessage.id}"
            )
        }
    }

    // Track whether user has scrolled up (to show scroll-to-bottom FAB)
    val isScrolledUp by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex < totalItems - 2
        }
    }

    // Auto-scroll to bottom when new messages arrive or streaming text changes
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        if (uiState.messages.isNotEmpty() || uiState.streamingText.isNotEmpty()) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    // Show error snackbar
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null) {
            val result = snackbarHostState.showSnackbar(
                message = error,
                actionLabel = "Retry",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.regenerateResponse()
            }
            viewModel.clearError()
        }
    }

    // Handle tool confirmations
    val confirmationState = rememberToolConfirmationState()
    HandleToolConfirmation(
        state = confirmationState,
        onExecute = { pending ->
            viewModel.confirmToolExecution(pending)
            // Return a placeholder result - the actual result is handled by the engine
            com.localassistant.domain.models.ToolResult(
                callId = pending.toolCall.callId,
                name = pending.toolCall.name,
                result = "Tool executed successfully",
                isError = false
            )
        }
    )

    // Show confirmation when a tool requires it
    LaunchedEffect(uiState.pendingToolConfirmation) {
        uiState.pendingToolConfirmation?.let {
            confirmationState.showConfirmation(it)
        }
    }

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.startAudioRecording()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Microphone permission is needed for voice input",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bitmap = loadBitmapFromUri(context, it)
                viewModel.sendImageMessage(
                    text = inputText.ifBlank { "Describe this image" },
                    imageBitmap = bitmap
                )
                inputText = ""
            } catch (e: Exception) {
                // Show error via snackbar
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Failed to load image: ${e.message}",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    // Message action dropdown state
    var showActionMenu by remember { mutableStateOf(false) }
    var actionMenuMessageIndex by remember { mutableStateOf(-1) }
    var showEditMessageDialog by remember { mutableStateOf(false) }

    // Mascot size animation
    val hasMessages = uiState.messages.isNotEmpty() || uiState.streamingText.isNotEmpty()
    val mascotSize by animateDpAsState(
        targetValue = if (hasMessages) 72.dp else 132.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "mascot_size"
    )

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Mock mode banner
                if (uiState.isMockMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF9C4))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "MOCK MODE",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFF7B6800)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = "Open conversations",
                            tint = OnBackground
                        )
                    }

                    Text(
                        text = uiState.currentConversationTitle,
                        color = OnBackground,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            viewModel.createNewConversation()
                            onNewConversation()
                        },
                        enabled = !uiState.isStreaming
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "New conversation",
                            tint = if (uiState.isStreaming) TextSecondary else OnBackground
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (hasMessages) Modifier.height(16.dp) else Modifier.weight(1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        if (!hasMessages) {
                            RobotMascot(
                                isThinking = uiState.isStreaming || uiState.isThinking,
                                modifier = Modifier.size(mascotSize)
                            )
                        }
                        if (!hasMessages && !uiState.isStreaming) {
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "What can I help with?",
                                color = OnBackground,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Message list
                if (hasMessages) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Render persisted messages
                            itemsIndexed(
                                items = uiState.messages,
                                key = { index, message -> message.id }
                            ) { index, message ->
                                when (message.role) {
                                    MessageRole.USER -> {
                                        SwipeableMessageItem(
                                            onSwipeLeft = {
                                                copyToClipboard(context, message.content)
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        message = "Copied to clipboard",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                actionMenuMessageIndex = index
                                                showActionMenu = true
                                            }
                                        ) {
                                            UserMessageBubble(
                                                message = message.content,
                                                timestamp = message.timestamp
                                            )
                                        }
                                    }

                                    MessageRole.ASSISTANT -> {
                                        // Show thinking bubble above assistant message if present
                                        if (!message.thinkingContent.isNullOrBlank()) {
                                            ThinkingBubble(
                                                thinkingContent = message.thinkingContent,
                                                isExpanded = expandedThinking.contains(index),
                                                onToggle = { viewModel.toggleThinkingExpanded(index) }
                                            )
                                        }

                                        SwipeableMessageItem(
                                            onSwipeLeft = {
                                                copyToClipboard(context, message.content)
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        message = "Copied to clipboard",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                actionMenuMessageIndex = index
                                                showActionMenu = true
                                            }
                                        ) {
                                            AssistantMessageBubble(
                                                message = message.content,
                                                timestamp = message.timestamp,
                                                isStreaming = false
                                            )
                                        }
                                    }

                                    MessageRole.TOOL -> {
                                        // Tool result messages are shown as ToolIndicator
                                        ToolIndicator(
                                            toolName = message.toolName ?: "Unknown",
                                            isExecuting = false
                                        )
                                    }

                                    MessageRole.SYSTEM -> {
                                        // System messages are not displayed in the chat UI
                                    }
                                }
                            }

                            // Active tool call indicators
                            items(uiState.activeToolCalls.size) { index ->
                                val toolCall = uiState.activeToolCalls[index]
                                ToolIndicator(
                                    toolName = toolCall.toolName,
                                    isExecuting = toolCall.isExecuting
                                )
                            }

                            // Streaming text (in-progress assistant response)
                            if (uiState.streamingText.isNotEmpty()) {
                                item(key = "streaming") {
                                    // Show thinking bubble if currently thinking
                                    if (uiState.thinkingText.isNotEmpty()) {
                                        ThinkingBubble(
                                            thinkingContent = uiState.thinkingText,
                                            isExpanded = expandedThinking.contains(-1),
                                            onToggle = { viewModel.toggleThinkingExpanded(-1) }
                                        )
                                    }
                                    AssistantMessageBubble(
                                        message = uiState.streamingText,
                                        timestamp = System.currentTimeMillis(),
                                        isStreaming = true
                                    )
                                }
                            }
                        }

                        // Scroll-to-bottom FAB
                        if (isScrolledUp && (uiState.isStreaming || uiState.messages.isNotEmpty())) {
                            SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        val totalItems = listState.layoutInfo.totalItemsCount
                                        if (totalItems > 0) {
                                            listState.animateScrollToItem(totalItems - 1)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 8.dp),
                                containerColor = Primary
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Scroll to bottom",
                                    tint = OnPrimary
                                )
                            }
                        }
                    }
                }

                // Bottom input bar
                if (!uiState.allowWebSearch) {
                    WebSearchNotice(onEnable = { viewModel.enableWebSearch() })
                }

                ChatComposer(
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    isStreaming = uiState.isStreaming,
                    isInputValid = isInputValid,
                    recordingState = recordingState,
                    voiceAmplitudes = voiceAmplitudes,
                    onAttachImage = { imagePickerLauncher.launch("image/*") },
                    onStartRecording = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.startAudioRecording()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopRecording = { viewModel.stopAudioRecording() },
                    onCancelRecording = { viewModel.cancelAudioRecording() },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    onStopGeneration = { viewModel.stopGeneration() }
                )
            }

        }
    }

    // Message action bottom sheet
    if (showActionMenu && actionMenuMessageIndex >= 0 && actionMenuMessageIndex < uiState.messages.size) {
        val selectedMessage = uiState.messages[actionMenuMessageIndex]
        val isLastAssistant = actionMenuMessageIndex == uiState.messages.lastIndex &&
                selectedMessage.role == MessageRole.ASSISTANT

        ModalBottomSheet(
            onDismissRequest = { showActionMenu = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                // Copy
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        copyToClipboard(context, selectedMessage.content)
                        showActionMenu = false
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Copied to clipboard",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )

                // Share
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = {
                        shareText(context, selectedMessage.content)
                        showActionMenu = false
                    }
                )

                if (selectedMessage.role == MessageRole.USER || selectedMessage.role == MessageRole.ASSISTANT) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            showActionMenu = false
                            showEditMessageDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showActionMenu = false
                            viewModel.deleteMessage(selectedMessage.id)
                        }
                    )
                }

                // Regenerate (only for last assistant message)
                if (isLastAssistant) {
                    DropdownMenuItem(
                        text = { Text("Regenerate") },
                        onClick = {
                            showActionMenu = false
                            viewModel.regenerateResponse()
                        }
                    )
                }
            }
        }
    }

    val messageBeingEdited = uiState.messages.getOrNull(actionMenuMessageIndex)
    if (showEditMessageDialog && messageBeingEdited != null) {
        var editedText by remember(messageBeingEdited.id) { mutableStateOf(messageBeingEdited.content) }

        AlertDialog(
            onDismissRequest = { showEditMessageDialog = false },
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = SurfaceVariant,
                        cursorColor = Primary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateMessageContent(messageBeingEdited.id, editedText)
                        showEditMessageDialog = false
                    },
                    enabled = editedText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditMessageDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = com.localassistant.ui.theme.Surface
        )
    }
}

// Helper composables

/**
 * Small inline prompt that makes the web-search permission state visible.
 */
@Composable
private fun WebSearchNotice(
    onEnable: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Web search is off",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onEnable) {
            Text("Enable")
        }
    }
}

@Composable
private fun ChatComposer(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isStreaming: Boolean,
    isInputValid: Boolean,
    recordingState: AudioRecordingState,
    voiceAmplitudes: List<Float>,
    onAttachImage: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showVoiceStrip = recordingState is AudioRecordingState.Recording ||
            recordingState is AudioRecordingState.Ready ||
            recordingState is AudioRecordingState.Error

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            AnimatedVisibility(
                visible = showVoiceStrip,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                VoiceInputStrip(
                    recordingState = recordingState,
                    amplitudes = voiceAmplitudes,
                    onCancel = onCancelRecording,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onAttachImage,
                    enabled = !isStreaming,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach image",
                        tint = if (isStreaming) TextSecondary else OnBackground
                    )
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp, max = 148.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, SurfaceVariant)
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        placeholder = {
                            Text(
                                text = "Ask anything...",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("chatInput"),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = OnBackground),
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            cursorColor = Primary,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isStreaming
                    )
                }

                AudioRecorderButton(
                    recordingState = recordingState,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onCancelRecording = onCancelRecording
                )

                FloatingActionButton(
                    onClick = {
                        if (isStreaming) {
                            onStopGeneration()
                        } else if (isInputValid) {
                            onSend()
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    containerColor = if (isStreaming || isInputValid) Primary else SurfaceVariant,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isStreaming) "Stop generation" else "Send message",
                        tint = if (isStreaming || isInputValid) OnPrimary else TextSecondary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceInputStrip(
    recordingState: AudioRecordingState,
    amplitudes: List<Float>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when (recordingState) {
        is AudioRecordingState.Recording -> "Listening"
        is AudioRecordingState.Ready -> "Transcribed"
        is AudioRecordingState.Error -> recordingState.message
        is AudioRecordingState.Idle -> ""
    }
    val elapsedText = (recordingState as? AudioRecordingState.Recording)
        ?.elapsedSeconds
        ?.let { formatElapsedTime(it) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = when (recordingState) {
            is AudioRecordingState.Error -> Error.copy(alpha = 0.08f)
            else -> Primary.copy(alpha = 0.08f)
        },
        border = BorderStroke(
            1.dp,
            if (recordingState is AudioRecordingState.Error) Error.copy(alpha = 0.22f)
            else Primary.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (recordingState is AudioRecordingState.Error) Error else Secondary,
                        shape = CircleShape
                    )
            )
            Text(
                text = statusText,
                color = if (recordingState is AudioRecordingState.Error) Error else OnBackground,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(min = 112.dp, max = 152.dp)
            )
            if (recordingState is AudioRecordingState.Recording) {
                AudioWaveform(
                    amplitudes = amplitudes,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            elapsedText?.let {
                Text(
                    text = it,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel recording",
                    tint = TextSecondary
                )
            }
        }
    }
}

private fun formatElapsedTime(elapsedSeconds: Float): String {
    val totalSeconds = elapsedSeconds.toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableMessageItem(
    onSwipeLeft: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit
) {
    var dragDistance by remember { mutableStateOf(0f) }
    var didTriggerSwipe by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onLongClick = onLongClick,
                onClick = { /* no-op */ }
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragDistance = 0f
                        didTriggerSwipe = false
                    },
                    onDragEnd = {
                        dragDistance = 0f
                        didTriggerSwipe = false
                    },
                    onDragCancel = {
                        dragDistance = 0f
                        didTriggerSwipe = false
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragDistance += dragAmount
                    if (!didTriggerSwipe && dragDistance < -72f) {
                        didTriggerSwipe = true
                        onSwipeLeft()
                    }
                }
            }
    ) {
        content()
    }
}

// Utility functions

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}

/**
 * Copy text to the system clipboard.
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Chat message", text)
    clipboard.setPrimaryClip(clip)
}

/**
 * Share text via an Android share intent.
 */
private fun shareText(context: Context, text: String) {
    val intent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(intent, "Share message")
    context.startActivity(shareIntent)
}
