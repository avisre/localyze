package com.localyze.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localyze.ai.AudioRecordingState
import com.localyze.domain.models.MessageRole
import com.localyze.ui.components.AssistantMessageBubble
import com.localyze.ui.components.AudioRecorderButton
import com.localyze.ui.components.AudioWaveform
import com.localyze.ui.components.GenerationFeedback
import com.localyze.ui.components.HandleToolConfirmation
import com.localyze.ui.components.ReferenceHeader
import com.localyze.ui.components.SourceCardsRow
import com.localyze.ui.components.ThinkingBubble
import com.localyze.ui.components.ToolIndicator
import com.localyze.ui.components.parseSourceCards
import com.localyze.ui.components.UserMessageBubble
import com.localyze.ui.components.rememberToolConfirmationState
import com.localyze.ui.theme.Background
import com.localyze.ui.theme.Error
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.OnPrimary
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.Surface as SurfaceColor
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary
import com.localyze.ui.viewmodels.ChatUiState
import com.localyze.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    onOpenAttachments: () -> Unit = {},
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

    // Input field state — rememberSaveable survives config changes (rotation)
    var inputText by rememberSaveable { mutableStateOf("") }
    val isInputValid = inputText.isNotBlank()

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

    // Image permission launcher
    LaunchedEffect(sharedText, sharedImageUris, uiState.isStreaming) {
        if (uiState.isStreaming) return@LaunchedEffect

        val hasSharedText = !sharedText.isNullOrBlank()
        if (!hasSharedText && sharedImageUris.isEmpty()) return@LaunchedEffect

        if (sharedImageUris.isNotEmpty()) {
            for (imageUriStr in sharedImageUris) {
                try {
                    val bitmap = loadBitmapFromUri(context, Uri.parse(imageUriStr))
                    viewModel.sendImageMessage(
                        text = sharedText.orEmpty().ifBlank { "Describe this image" },
                        imageBitmap = bitmap
                    )
                } catch (e: Exception) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Failed to load shared image: ${e.message}",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
            onSharedContentConsumed()
        } else if (hasSharedText) {
            viewModel.sendMessageInNewConversation(
                text = sharedText!!.trim(),
                capabilityModeOverride = "chat"
            )
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

    // Show context-reset notice snackbar
    LaunchedEffect(uiState.contextResetNotice) {
        uiState.contextResetNotice?.let { notice ->
            snackbarHostState.showSnackbar(
                message = notice,
                duration = SnackbarDuration.Short
            )
            viewModel.clearContextResetNotice()
        }
    }

    // Handle tool confirmations
    val confirmationState = rememberToolConfirmationState()
    HandleToolConfirmation(
        state = confirmationState,
        onExecute = { pending ->
            viewModel.confirmToolExecution(pending)
            // Return a placeholder result - the actual result is handled by the engine
            com.localyze.domain.models.ToolResult(
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

    // Image picker launcher (gallery)
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

    // Camera capture: a content:// URI created via FileProvider that the
    // external camera app writes the JPEG to. We hold it in a ref so the
    // launcher result callback can read the file back.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            try {
                val bitmap = loadBitmapFromUri(context, uri)
                viewModel.sendImageMessage(
                    text = inputText.ifBlank { "Describe this image" },
                    imageBitmap = bitmap
                )
                inputText = ""
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Failed to load photo: ${e.message}",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    fun launchCameraCapture() {
        try {
            val capturesDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
            val photoFile = File(capturesDir, "capture_${System.currentTimeMillis()}.jpg")
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, photoFile)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Couldn't open camera: ${e.message}",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Camera permission is needed to take photos",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Attach-menu sheet state. The paperclip button now opens a bottom
    // sheet offering "Take photo" or "Choose from gallery" instead of
    // jumping straight to the gallery picker.
    var showAttachMenu by remember { mutableStateOf(false) }
    val attachSheetState = rememberModalBottomSheetState()

    val onAttachClick: () -> Unit = { showAttachMenu = true }
    val onChooseFromGallery: () -> Unit = {
        showAttachMenu = false
        imagePickerLauncher.launch("image/*")
    }
    val onTakePhoto: () -> Unit = {
        showAttachMenu = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Message action dropdown state
    var showActionMenu by remember { mutableStateOf(false) }
    var actionMenuMessageIndex by remember { mutableStateOf(-1) }
    var showEditMessageDialog by remember { mutableStateOf(false) }

    // In-thread message search state
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val hasMessages = uiState.messages.isNotEmpty() || uiState.streamingText.isNotEmpty()

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
                ReferenceHeader(
                    title = "Localyze.ai",
                    subtitle = when {
                        uiState.isThinking -> "Thinking..."
                        uiState.isStreaming -> "Generating..."
                        uiState.isUsingThreadContext -> "On-device • Context aware"
                        else -> "On-device"
                    },
                    actions = {
                        if (hasMessages) {
                            IconButton(onClick = { isSearching = !isSearching; if (!isSearching) searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = if (isSearching) "Close search" else "Search messages",
                                    tint = if (isSearching) Primary else TextSecondary
                                )
                            }
                        }
                        IconButton(onClick = { /* Privacy status is shown in the chips below. */ }) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = "Privacy status",
                                tint = TextSecondary
                            )
                        }
                        IconButton(onClick = onOpenDrawer) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = "Open conversations",
                                tint = TextSecondary
                            )
                        }
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
                )

                // Search bar
                if (isSearching && hasMessages) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search in this chat...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search",
                                tint = TextSecondary
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear search",
                                        tint = TextSecondary
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Background,
                            unfocusedContainerColor = Background
                        )
                    )
                }

                // Message list
                if (!hasMessages) {
                    ChatHomeContent(
                        inputText = inputText,
                        onInputTextChange = { inputText = it },
                        isStreaming = uiState.isStreaming,
                        isInputValid = isInputValid,
                        recordingState = recordingState,
                        allowWebSearch = uiState.allowWebSearch,
                        voiceAmplitudes = voiceAmplitudes,
                        onAttachImage = onAttachClick,
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
                        onStopGeneration = { viewModel.stopGeneration() },
                        onPrompt = { prompt -> viewModel.sendMessage(prompt) },
                        onEnableWebSearch = { viewModel.enableWebSearch() },
                        onNewChat = {
                            viewModel.createNewConversation()
                            onNewConversation()
                        },
                        onOpenAttachments = onOpenAttachments,
                        onOpenConversations = onOpenDrawer,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    ChatMessageList(
                        uiState = uiState,
                        searchQuery = searchQuery,
                        expandedThinking = expandedThinking,
                        onToggleThinking = viewModel::toggleThinkingExpanded,
                        onCopyMessage = { content ->
                            copyToClipboard(context, content)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Copied to clipboard",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        onLongClickMessage = { index ->
                            actionMenuMessageIndex = index
                            showActionMenu = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                if (hasMessages) {
                    ChatComposer(
                        inputText = inputText,
                        onInputTextChange = { inputText = it },
                        isStreaming = uiState.isStreaming,
                        isInputValid = isInputValid,
                        recordingState = recordingState,
                        voiceAmplitudes = voiceAmplitudes,
                        onAttachImage = onAttachClick,
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
                        onStopGeneration = { viewModel.stopGeneration() },
                        modifier = Modifier.imePadding()
                    )
                }
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

    // Attach picker sheet (camera vs gallery)
    if (showAttachMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachMenu = false },
            sheetState = attachSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.PhotoCamera,
                            contentDescription = null,
                            tint = Primary
                        )
                    },
                    text = { Text("Take photo") },
                    onClick = onTakePhoto
                )
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = Primary
                        )
                    },
                    text = { Text("Choose from gallery") },
                    onClick = onChooseFromGallery
                )
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
            containerColor = com.localyze.ui.theme.Surface
        )
    }
}

// Helper composables

@Composable
fun ChatMessageList(
    uiState: ChatUiState,
    searchQuery: String = "",
    expandedThinking: Set<Int>,
    onToggleThinking: (Int) -> Unit,
    onCopyMessage: (String) -> Unit,
    onLongClickMessage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val filteredMessages = remember(uiState.messages, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.messages.mapIndexed { i, m -> i to m }
        } else {
            val query = searchQuery.lowercase()
            uiState.messages.mapIndexed { i, m -> i to m }.filter { (_, message) ->
                message.content.lowercase().contains(query) ||
                message.thinkingContent?.lowercase()?.contains(query) == true ||
                message.toolResult?.lowercase()?.contains(query) == true
            }
        }
    }
    val scope = rememberCoroutineScope()
    val hasDisplayItems = uiState.messages.isNotEmpty() ||
            uiState.streamingText.isNotEmpty() ||
            uiState.isStreaming ||
            uiState.activeToolCalls.isNotEmpty()
    var followOutput by rememberSaveable(uiState.currentConversationId) { mutableStateOf(true) }
    val atBottomState = remember(listState) {
        derivedStateOf {
            if (!hasDisplayItems) return@derivedStateOf true
            ChatScrollPolicy.isAtBottom(listState.toChatListViewportSnapshot())
        }
    }
    val isScrolledUp by remember(hasDisplayItems, atBottomState) {
        derivedStateOf {
            hasDisplayItems && !atBottomState.value
        }
    }

    LaunchedEffect(uiState.currentConversationId) {
        followOutput = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { atBottomState.value }
            .distinctUntilChanged()
            .collect { isAtBottom ->
                if (isAtBottom) {
                    followOutput = true
                }
            }
    }

    // A. Snap to bottom on structural changes (new messages, tool calls, followOutput restored).
    // We intentionally do NOT key on streamingText.length because that restarts this effect
    // on every token, causing scroll fighting and preventing the list from keeping up with
    // a growing last item.
    LaunchedEffect(
        uiState.currentConversationId,
        uiState.messages.size,
        uiState.activeToolCalls.size,
        followOutput
    ) {
        if (!hasDisplayItems || !followOutput || listState.isScrollInProgress) {
            return@LaunchedEffect
        }

        // Compute anchor index from uiState so we don't depend on layout timing.
        val bottomAnchorIndex = uiState.messages.size +
            (if (uiState.isStreaming && uiState.generationStatus.isNotBlank()) 1 else 0) +
            uiState.activeToolCalls.size +
            (if (uiState.streamingText.isNotEmpty()) 1 else 0)
        if (bottomAnchorIndex >= 0) {
            listState.requestScrollToItem(bottomAnchorIndex)
        }
    }

    // B. Follow growing streaming content without fighting user scroll.
    // Reacts to layout changes (e.g., last item growing) and scrolls by the exact
    // overshoot amount so the bottom anchor stays in view.
    LaunchedEffect(listState, uiState.isStreaming, followOutput, uiState.currentConversationId) {
        if (!followOutput || !uiState.isStreaming) return@LaunchedEffect
        snapshotFlow { Triple(listState.layoutInfo, listState.isScrollInProgress, listState.canScrollForward) }
            .collect { (layoutInfo, isScrolling, canScrollForward) ->
                if (!followOutput || !uiState.isStreaming) return@collect
                if (isScrolling) return@collect
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                if (lastVisible != null) {
                    val overshoot = (lastVisible.offset + lastVisible.size) - layoutInfo.viewportEndOffset
                    if (overshoot > 1) {
                        listState.scrollBy(overshoot.toFloat())
                    } else if (canScrollForward) {
                        // The last visible item fits, but there are more items just below
                        // (e.g., the tiny bottom anchor). Nudge a bit more to bring them in.
                        listState.scrollBy(100f)
                    }
                }
            }
    }

    val userScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    // If user drags up (available.y > 0) while at bottom,
                    // they are intentionally leaving the bottom; stop following immediately.
                    if (available.y > 0 && atBottomState.value) {
                        followOutput = false
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    followOutput = atBottomState.value
                }
                return Offset.Zero
            }
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(userScrollConnection)
                .testTag(ChatScrollPolicy.MESSAGE_LIST_TAG)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { _, pair ->
                    val (index, message) = pair
                    if (message.id > 0L) {
                        "message-${message.id}"
                    } else {
                        "message-${message.role}-${message.timestamp}-$index"
                    }
                }
            ) { _, pair ->
                val (originalIndex, message) = pair
                when (message.role) {
                    MessageRole.USER -> {
                        SwipeableMessageItem(
                            onSwipeLeft = { onCopyMessage(message.content) },
                            onLongClick = { onLongClickMessage(originalIndex) }
                        ) {
                            UserMessageBubble(
                                message = message.content,
                                timestamp = message.timestamp,
                                imageUris = message.imageUris
                            )
                        }
                    }

                    MessageRole.ASSISTANT -> {
                        if (!message.thinkingContent.isNullOrBlank()) {
                            ThinkingBubble(
                                thinkingContent = message.thinkingContent,
                                isExpanded = expandedThinking.contains(originalIndex),
                                onToggle = { onToggleThinking(originalIndex) }
                            )
                        }

                        SwipeableMessageItem(
                            onSwipeLeft = { onCopyMessage(message.content) },
                            onLongClick = { onLongClickMessage(originalIndex) }
                        ) {
                            AssistantMessageBubble(
                                message = message.content,
                                timestamp = message.timestamp,
                                isStreaming = false
                            )
                        }
                    }

                    MessageRole.TOOL -> {
                        ToolIndicator(
                            toolName = message.toolName ?: "Unknown",
                            isExecuting = false
                        )
                    }

                    MessageRole.SYSTEM -> Unit
                }
            }

            if (uiState.isStreaming && uiState.generationStatus.isNotBlank()) {
                item(key = "generation-feedback") {
                    GenerationFeedback(
                        status = uiState.generationStatus,
                        detail = if (uiState.activeToolCalls.any { it.toolName == "web_search" }) {
                            "Fetching sources before drafting"
                        } else {
                            "The answer will appear below"
                        }
                    )
                }
            }

            itemsIndexed(
                items = uiState.activeToolCalls,
                key = { index, toolCall -> "tool-${toolCall.toolName}-$index" }
            ) { _, toolCall ->
                val cards = if (!toolCall.isExecuting && toolCall.toolName == "web_search") {
                    parseSourceCards(toolCall.result)
                } else emptyList()
                val detail = when {
                    toolCall.toolName == "web_search" && !toolCall.isExecuting && cards.isNotEmpty() ->
                        "Read ${cards.size} ${if (cards.size == 1) "source" else "sources"}"
                    else -> null
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolIndicator(
                        toolName = toolCall.toolName,
                        isExecuting = toolCall.isExecuting,
                        detail = detail
                    )
                    if (cards.isNotEmpty()) {
                        SourceCardsRow(cards = cards)
                    }
                }
            }

            if (uiState.streamingText.isNotEmpty()) {
                item(key = "streaming-assistant") {
                    if (uiState.thinkingText.isNotEmpty()) {
                        ThinkingBubble(
                            thinkingContent = uiState.thinkingText,
                            isExpanded = expandedThinking.contains(-1),
                            onToggle = { onToggleThinking(-1) }
                        )
                    }
                    AssistantMessageBubble(
                        message = uiState.streamingText,
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true,
                        modifier = Modifier.testTag("streamingAssistantMessage")
                    )
                }
            }

            item(key = ChatScrollPolicy.BOTTOM_ANCHOR_KEY) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .testTag(ChatScrollPolicy.BOTTOM_ANCHOR_TAG)
                )
            }
        }

        if (isScrolledUp) {
            SmallFloatingActionButton(
                onClick = {
                    followOutput = true
                    val bottomAnchorIndex = uiState.messages.size +
                        (if (uiState.isStreaming && uiState.generationStatus.isNotBlank()) 1 else 0) +
                        uiState.activeToolCalls.size +
                        (if (uiState.streamingText.isNotEmpty()) 1 else 0)
                    if (bottomAnchorIndex >= 0) {
                        listState.requestScrollToItem(bottomAnchorIndex)
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

private fun LazyListState.toChatListViewportSnapshot(): ChatListViewportSnapshot {
    val layoutInfo = layoutInfo
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
    return ChatListViewportSnapshot(
        totalItemsCount = layoutInfo.totalItemsCount,
        lastVisibleItemIndex = lastVisibleItem?.index,
        lastVisibleItemBottom = lastVisibleItem?.let { it.offset + it.size },
        viewportEndOffset = layoutInfo.viewportEndOffset,
        canScrollForward = canScrollForward
    )
}

@Composable
private fun ChatHomeContent(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isStreaming: Boolean,
    isInputValid: Boolean,
    recordingState: AudioRecordingState,
    allowWebSearch: Boolean,
    voiceAmplitudes: List<Float>,
    onAttachImage: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    onPrompt: (String) -> Unit,
    onEnableWebSearch: () -> Unit,
    onNewChat: () -> Unit,
    onOpenAttachments: () -> Unit,
    onOpenConversations: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Text(
            text = "Localyze.ai",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp
            ),
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "How can I help?",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        HomePromptCard(
            inputText = inputText,
            onInputTextChange = onInputTextChange,
            isStreaming = isStreaming,
            isInputValid = isInputValid,
            recordingState = recordingState,
            voiceAmplitudes = voiceAmplitudes,
            onAttachImage = onAttachImage,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onSend = onSend,
            onStopGeneration = onStopGeneration,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HomePromptCard(
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
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(0.5.dp, SurfaceVariant),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
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

            TextField(
                value = inputText,
                onValueChange = onInputTextChange,
                placeholder = {
                    Text(
                        text = "Message Localyze.ai...",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp, max = 144.dp)
                    .testTag("chatInput"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = OnBackground),
                minLines = 2,
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = Primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                enabled = !isStreaming
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onAttachImage,
                    enabled = !isStreaming,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach image",
                        tint = if (isStreaming) TextSecondary else TextSecondary
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                AudioRecorderButton(
                    recordingState = recordingState,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onCancelRecording = onCancelRecording
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (isStreaming) {
                            onStopGeneration()
                        } else if (isInputValid) {
                            onSend()
                        }
                    },
                    modifier = Modifier.size(44.dp),
                    containerColor = if (isStreaming || isInputValid) Primary else SurfaceVariant,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isStreaming) "Stop generation" else "Send message",
                        tint = if (isStreaming || isInputValid) OnPrimary else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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
        color = SurfaceColor,
        shadowElevation = 0.dp,
        border = BorderStroke(0.5.dp, SurfaceVariant),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
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
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    border = BorderStroke(0.5.dp, SurfaceVariant)
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        placeholder = {
                            Text(
                                text = "Message Localyze.ai...",
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
                        color = if (recordingState is AudioRecordingState.Error) Error else Primary,
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
    // First decode bounds only to get dimensions
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }

    // Downscale via inSampleSize until both dimensions fit under maxDimension.
    // Note the `||` here: previously this loop used `&&`, which stopped
    // shrinking as soon as either dimension fit — leaving e.g. a portrait
    // 4000x6000 camera shot at 2000x3000, oversize for the vision encoder
    // and reliably crashing liblitertlm_jni.so with a SIGSEGV.
    val maxDimension = 1024
    var inSampleSize = 1
    if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
        var h = options.outHeight
        var w = options.outWidth
        while (h / inSampleSize > maxDimension || w / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }
    }

    val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = true
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (inSampleSize > 1) {
                val targetWidth = options.outWidth / inSampleSize
                val targetHeight = options.outHeight / inSampleSize
                decoder.setTargetSize(targetWidth, targetHeight)
            }
        }
    } else {
        @Suppress("DEPRECATION")
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalArgumentException("Could not open input stream for URI: $uri")
    }

    // Belt-and-suspenders: if the decoder still produced something larger
    // than maxDimension (rounding effects, decoder ignoring target size),
    // do a final scale here.
    val w = decoded.width
    val h = decoded.height
    val needsFurtherScale = w > maxDimension || h > maxDimension
    val targetConfig = decoded.config ?: Bitmap.Config.ARGB_8888
    val argb = if (targetConfig != Bitmap.Config.ARGB_8888) {
        decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    } else decoded
    if (!needsFurtherScale) return argb
    val ratio = maxDimension.toFloat() / maxOf(w, h)
    val newW = (w * ratio).toInt().coerceAtLeast(1)
    val newH = (h * ratio).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(argb, newW, newH, true)
    if (scaled !== argb) argb.recycle()
    return scaled
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
