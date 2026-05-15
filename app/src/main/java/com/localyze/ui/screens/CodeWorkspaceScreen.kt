package com.localyze.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localyze.ui.components.StructuredMarkdownText
import com.localyze.ui.viewmodels.CodeAssistAction
import com.localyze.ui.viewmodels.CodeWorkspaceMessage
import com.localyze.ui.viewmodels.CodeWorkspacePane
import com.localyze.ui.viewmodels.CodeWorkspaceUiState
import com.localyze.ui.viewmodels.CodeWorkspaceViewModel

private fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}

private val WorkspaceBackground = Color(0xFFF5F5F7)
private val WorkspaceSurface = Color.White
private val WorkspaceElevated = Color(0xFFFBFBFD)
private val WorkspaceLine = Color(0xFFD2D2D7)
private val WorkspaceText = Color(0xFF1D1D1F)
private val WorkspaceMuted = Color(0xFF6E6E73)
private val WorkspaceSubtle = Color(0xFFE5E5EA)
private val WorkspaceGreen = Color(0xFF34C759)
private val WorkspaceRed = Color(0xFFFF3B30)

@Composable
fun CodeWorkspaceScreen(
    viewModel: CodeWorkspaceViewModel = hiltViewModel(),
    codeTestPrompt: String? = null,
    onCodeTestPromptConsumed: () -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try { viewModel.attachImage(loadBitmapFromUri(context, it)) }
            catch (e: Exception) { android.util.Log.e("CodeWorkspace", "Failed to load image: ${e.message}") }
        }
    }

    LaunchedEffect(codeTestPrompt) {
        if (codeTestPrompt != null && !uiState.isStreaming && uiState.responseText.isBlank()) {
            viewModel.triggerTestPrompt(codeTestPrompt)
            onCodeTestPromptConsumed()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    if (uiState.isFullScreenPreview) {
        FullScreenPreview(
            html = previewHtmlFromCode(uiState.code),
            onClose = { viewModel.exitFullScreenPreview() }
        )
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = WorkspaceBackground
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WorkspaceBackground)
                    .padding(paddingValues)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // H1: Status bar with live feedback
                CodeWorkspaceStatusBar(uiState = uiState, onStop = { viewModel.stopGeneration() })

                Spacer(modifier = Modifier.height(10.dp))

                // Main editor + preview area
                EditorPreviewPanel(
                    uiState = uiState,
                    onCodeChange = viewModel::updateCode,
                    onPaneChange = viewModel::selectPane,
                    onEnterFullScreen = { viewModel.toggleFullScreenPreview() },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Chat/assistant panel at bottom
                CodeAssistantPanel(
                    uiState = uiState,
                    onInstructionChange = viewModel::updateInstruction,
                    onActionChange = viewModel::selectAction,
                    onLoadScenario = viewModel::loadScenario,
                    onAsk = viewModel::askAssistant,
                    onStop = { viewModel.stopGeneration() },
                    onAttachImage = { imagePickerLauncher.launch("image/*") },
                    onRemoveImage = viewModel::removeAttachedImage,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// H1: VISIBILITY OF SYSTEM STATUS — always shows what's happening
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CodeWorkspaceStatusBar(
    uiState: CodeWorkspaceUiState,
    onStop: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "status_pulse")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "pulse")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WorkspaceSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.5.dp, WorkspaceLine)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (uiState.isStreaming) {
                    // Animated spinner
                    Canvas(modifier = Modifier.size(18.dp)) {
                        drawCircle(WorkspaceSubtle, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        drawArc(WorkspaceGreen, pulse * 360f, 100f, false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Column {
                        Text(uiState.generationStatus.ifBlank { "Working..." }, color = WorkspaceText, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Text("Tap stop to cancel", color = WorkspaceMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                } else if (uiState.code.isNotBlank()) {
                    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = WorkspaceGreen) {}
                    Text("Ready - ${uiState.code.lines().size} lines - ${uiState.language}", color = WorkspaceMuted, style = MaterialTheme.typography.labelMedium)
                } else {
                    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = WorkspaceGreen) {}
                    Text("Ready - describe what to build", color = WorkspaceMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (uiState.isStreaming) {
                // H3: User control — stop button always visible during generation
                Surface(
                    onClick = onStop,
                    shape = RoundedCornerShape(16.dp),
                    color = WorkspaceRed.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, WorkspaceRed.copy(alpha = 0.35f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Stop, "Stop", tint = WorkspaceRed, modifier = Modifier.size(14.dp))
                        Text("Stop", color = WorkspaceRed, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// FULL-SCREEN PREVIEW
// ═══════════════════════════════════════════

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FullScreenPreview(html: String, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        if (html.isNotBlank()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        setBackgroundColor(android.graphics.Color.WHITE)
                    }
                },
                update = { it.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null) }
            )
        }
        // H3: Clear exit from full-screen
        Surface(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Exit full screen", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════
// EDITOR + PREVIEW PANEL
// ═══════════════════════════════════════════

@Composable
private fun EditorPreviewPanel(
    uiState: CodeWorkspaceUiState,
    onCodeChange: (String) -> Unit,
    onPaneChange: (CodeWorkspacePane) -> Unit,
    onEnterFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPreview = uiState.selectedPane == CodeWorkspacePane.Preview
    val hasCode = uiState.code.isNotBlank()

    Surface(
        modifier = modifier,
        color = WorkspaceSurface,
        border = BorderStroke(0.5.dp, WorkspaceLine),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab bar with clear labels
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip("Editor", !isPreview) { onPaneChange(CodeWorkspacePane.Editor) }
                    FilterChip("Preview", isPreview) { onPaneChange(CodeWorkspacePane.Preview) }
                }
                if (isPreview && hasCode) {
                    // H3: Full-screen button clearly accessible
                    Surface(
                        onClick = onEnterFullScreen,
                        shape = RoundedCornerShape(8.dp),
                        color = WorkspaceSubtle.copy(alpha = 0.7f)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Filled.Fullscreen, "Full screen", tint = WorkspaceMuted, modifier = Modifier.size(16.dp))
                            Text("Full Screen", color = WorkspaceMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Content area
            if (isPreview) {
                WebsitePreviewPane(uiState = uiState, modifier = Modifier.weight(1f))
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = uiState.code,
                        onValueChange = onCodeChange,
                        modifier = Modifier.fillMaxSize().testTag("codeWorkspaceEditor"),
                        placeholder = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Paste code here, or ask below to generate it.", color = WorkspaceMuted, style = MaterialTheme.typography.bodyMedium)
                                Text("Try: \"Build a landing page\" or \"Explain this code\"", color = WorkspaceMuted.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = WorkspaceText, fontFamily = FontFamily.Monospace),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = WorkspaceText, unfocusedTextColor = WorkspaceText,
                            focusedContainerColor = WorkspaceSurface, unfocusedContainerColor = WorkspaceSurface,
                            disabledContainerColor = WorkspaceSurface,
                            cursorColor = WorkspaceGreen,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        enabled = !uiState.isStreaming
                    )
                    // H10: Context-sensitive help when editor is empty
                    if (uiState.code.isBlank() && !uiState.isStreaming) {
                        Surface(
                            shape = RoundedCornerShape(8.dp), color = WorkspaceElevated,
                            border = BorderStroke(0.5.dp, WorkspaceLine),
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                        ) {
                            Text("Empty editor - ask me to write code below", color = WorkspaceMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) WorkspaceGreen.copy(alpha = 0.10f) else Color.Transparent
    ) {
        Text(
            text = label,
            color = if (selected) WorkspaceGreen else WorkspaceMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ═══════════════════════════════════════════
// WEBSITE PREVIEW — with loading state
// ═══════════════════════════════════════════

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebsitePreviewPane(uiState: CodeWorkspaceUiState, modifier: Modifier = Modifier) {
    val html = previewHtmlFromCode(uiState.code)
    Box(modifier = modifier.fillMaxSize().background(Color.White)) {
        if (html.isBlank()) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Visibility, null, tint = WorkspaceLine, modifier = Modifier.size(44.dp))
                Text("No preview available", color = WorkspaceMuted, style = MaterialTheme.typography.titleSmall)
                Text("Generate HTML code in the Editor tab to see a live preview here", color = WorkspaceMuted.copy(alpha = 0.74f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                        settings.setSupportZoom(true); settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        setBackgroundColor(android.graphics.Color.WHITE)
                    }
                },
                update = { it.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null) }
            )
        }
    }
}

// ═══════════════════════════════════════════
// ASSISTANT CHAT PANEL — with feedback
// ═══════════════════════════════════════════

@Composable
private fun CodeAssistantPanel(
    uiState: CodeWorkspaceUiState,
    onInstructionChange: (String) -> Unit,
    onActionChange: (CodeAssistAction) -> Unit,
    onLoadScenario: (String, String, CodeAssistAction) -> Unit,
    onAsk: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showActionsMenu by remember { mutableStateOf(false) }
    var showTemplatesMenu by remember { mutableStateOf(false) }
    data class Scenario(val name: String, val code: String, val instruction: String, val action: CodeAssistAction)
    val scenarios = remember {
        listOf(
            Scenario("Ecommerce Landing", "", "Build a modern ecommerce landing page with product grid, hero, cart", CodeAssistAction.WebsiteRequest),
            Scenario("Portfolio Site", "", "Create a developer portfolio website with projects and contact form", CodeAssistAction.WebsiteRequest),
            Scenario("Restaurant Page", "", "Build a restaurant website with menu and reservations", CodeAssistAction.WebsiteRequest),
            Scenario("Dashboard UI", "", "Create an analytics dashboard with charts and stats cards", CodeAssistAction.WebsiteRequest),
            Scenario("Kotlin Coroutine", "suspend fun <T> fetchDataWithRetry(\n    apiCall: suspend () -> T,\n    maxRetries: Int = 3\n): Flow<T> = flow {\n    var attempts = 0\n    while (attempts < maxRetries) {\n        try {\n            emit(apiCall())\n            break\n        } catch (e: IOException) {\n            attempts++\n            delay(1000L * attempts)\n        }\n    }\n}.buffer(Channel.CONFLATED)", "Explain how this Kotlin coroutine retry logic works", CodeAssistAction.Explain),
            Scenario("Python SQL Bug", "import sqlite3\n\ndef get_user(user_id):\n    conn = sqlite3.connect('data.db')\n    cursor = conn.cursor()\n    cursor.execute(f'SELECT * FROM users WHERE id = {user_id}')\n    result = cursor.fetchall()\n    return result", "Find the security issues", CodeAssistAction.Debug)
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = WorkspaceSurface,
        border = BorderStroke(0.5.dp, WorkspaceLine)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            // H1: Progress bar visible during generation
            AnimatedVisibility(visible = uiState.isStreaming, enter = slideInVertically() + fadeIn(), exit = fadeOut()) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(uiState.generationStatus.ifBlank { "Processing..." }, color = WorkspaceText, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = onStop) { Text("Cancel", color = WorkspaceRed, style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WorkspaceGreen, trackColor = WorkspaceSubtle)
                }
            }

            // Action + Template menus
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    TextButton(onClick = { showActionsMenu = true }) { Text(uiState.selectedAction.label, color = WorkspaceGreen, style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(expanded = showActionsMenu, onDismissRequest = { showActionsMenu = false }) {
                        CodeAssistAction.entries.forEach { action ->
                            DropdownMenuItem(text = { Text(action.label) }, onClick = { onActionChange(action); showActionsMenu = false })
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { showTemplatesMenu = true }) { Text("Templates", color = WorkspaceMuted, style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(expanded = showTemplatesMenu, onDismissRequest = { showTemplatesMenu = false }) {
                        scenarios.forEach { s ->
                            DropdownMenuItem(text = { Text(s.name) }, onClick = { onLoadScenario(s.code, s.instruction, s.action); showTemplatesMenu = false })
                        }
                    }
                }
            }

            // Image preview
            if (uiState.attachedImage != null) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp).padding(bottom = 6.dp)) {
                    Image(uiState.attachedImage.asImageBitmap(), "Attached", modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                    IconButton(onClick = onRemoveImage, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                        Icon(Icons.Filled.Close, "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Input row
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = WorkspaceElevated, border = BorderStroke(0.5.dp, WorkspaceLine)) {
                    TextField(
                        value = uiState.instruction,
                        onValueChange = onInstructionChange,
                        placeholder = { Text(getPlaceholderForAction(uiState.selectedAction), color = WorkspaceMuted, style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.fillMaxWidth().testTag("codeWorkspaceInstruction"),
                        minLines = 1, maxLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = WorkspaceText),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = WorkspaceGreen, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        enabled = !uiState.isStreaming
                    )
                }
                IconButton(onClick = onAttachImage, enabled = !uiState.isStreaming, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Image, "Attach", tint = WorkspaceMuted, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = if (uiState.isStreaming) onStop else onAsk,
                    enabled = !uiState.isStreaming || true, // always enabled for stop
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isStreaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (uiState.isStreaming) "Stop" else "Send",
                        tint = if (uiState.isStreaming) WorkspaceRed else (if (uiState.instruction.isNotBlank() || uiState.code.isNotBlank()) WorkspaceGreen else WorkspaceMuted),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Response messages — H1: clear status per message
            val showResponseLog = (uiState.selectedPane != CodeWorkspacePane.Preview || uiState.isStreaming) &&
                (uiState.messages.isNotEmpty() || uiState.responseText.isNotBlank())
            if (showResponseLog) {
                Surface(modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 120.dp), color = WorkspaceElevated, shape = RoundedCornerShape(8.dp), border = BorderStroke(0.5.dp, WorkspaceLine)) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(uiState.messages) { msg -> ResponseBubble(msg.content, msg.role == "user") }
                        if (uiState.responseText.isNotBlank()) item { ResponseBubble(uiState.responseText, false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponseBubble(text: String, isUser: Boolean) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(if (isUser) "You" else "Assistant", color = WorkspaceMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 2.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = if (isUser) 4.dp else 10.dp, bottomEnd = if (isUser) 10.dp else 4.dp),
            color = if (isUser) WorkspaceGreen.copy(alpha = 0.10f) else WorkspaceSurface,
            border = BorderStroke(0.5.dp, WorkspaceLine),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            if (isUser) Text(text, color = WorkspaceText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            else StructuredMarkdownText(markdown = text, modifier = Modifier.padding(10.dp))
        }
    }
}

private fun getPlaceholderForAction(action: CodeAssistAction): String = when (action) {
    CodeAssistAction.Explain -> "Explain this code..."
    CodeAssistAction.Debug -> "Find bugs in this code..."
    CodeAssistAction.Fix -> "Fix issues in this code..."
    CodeAssistAction.Optimize -> "Optimize this code..."
    CodeAssistAction.Review -> "Review this code..."
    CodeAssistAction.WebsiteRequest -> "Build me an ecommerce landing page..."
}

// ═══════════════════════════════════════════
// HTML PREVIEW UTILITIES
// ═══════════════════════════════════════════

internal fun previewHtmlFromCode(code: String): String {
    val trimmed = code.trim()
    if (trimmed.isBlank()) return ""
    val lower = trimmed.lowercase()
    if (lower.contains("<!doctype") || (lower.contains("<html") && lower.contains("</html>"))) return ensureViewportMeta(trimmed)
    if (lower.contains("<body") || lower.contains("<head")) return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}</style>\n</head>\n$trimmed\n</html>"
    val tags = listOf("<div", "<main", "<section", "<nav", "<header", "<footer", "<article", "<form", "<table", "<input", "<button", "<h1", "<h2", "<h3", "<p>", "<ul", "<ol", "<li", "<span", "<img", "<a ")
    if (tags.any { lower.contains(it) }) return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:16px}</style>\n</head>\n<body>\n$trimmed\n</body>\n</html>"
    if (lower.contains("{") && lower.contains("}") && (lower.contains("@media") || lower.contains("@keyframes") || (lower.contains(":") && lower.contains(";")))) return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <style>\n*{margin:0;padding:0;box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:16px}\n$trimmed\n  </style>\n</head>\n<body><div>CSS Preview</div></body>\n</html>"
    return ""
}

private fun ensureViewportMeta(html: String): String {
    if (html.lowercase().contains("viewport")) return html
    return Regex("(<head[^>]*>)", RegexOption.IGNORE_CASE).replace(html) { "${it.value}\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" }
}
