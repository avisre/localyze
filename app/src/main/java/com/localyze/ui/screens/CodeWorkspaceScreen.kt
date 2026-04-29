package com.localyze.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localyze.ui.components.GenerationFeedback
import com.localyze.ui.components.ReferenceHeader
import com.localyze.ui.theme.Background
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.OnPrimary
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary
import com.localyze.ui.viewmodels.CodeAssistAction
import com.localyze.ui.viewmodels.CodeWorkspaceMessage
import com.localyze.ui.viewmodels.CodeWorkspacePane
import com.localyze.ui.viewmodels.CodeWorkspaceUiState
import com.localyze.ui.viewmodels.CodeWorkspaceViewModel

private val IdeBackground = Color(0xFF101114)
private val IdePanel = Color(0xFF171A20)
private val IdePanelAlt = Color(0xFF20242C)
private val IdeBorder = Color(0xFF303640)
private val IdeText = Color(0xFFE8EAED)
private val IdeMuted = Color(0xFFAAB0BA)
private val IdeAccent = Color(0xFF4BA3FF)

private fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = true
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}

@Composable
fun CodeWorkspaceScreen(
    viewModel: CodeWorkspaceViewModel = hiltViewModel(),
    codeTestPrompt: String? = null,
    onCodeTestPromptConsumed: () -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bitmap = loadBitmapFromUri(context, it)
                viewModel.attachImage(bitmap)
            } catch (e: Exception) {
                android.util.Log.e("CodeWorkspace", "Failed to load image: ${e.message}")
            }
        }
    }

    // Auto-trigger code test when a prompt is provided
    LaunchedEffect(codeTestPrompt) {
        if (codeTestPrompt != null && !uiState.isStreaming && uiState.responseText.isBlank()) {
            android.util.Log.d("CodeWorkspace", "Auto-triggering test prompt: $codeTestPrompt")
            viewModel.triggerTestPrompt(codeTestPrompt)
            onCodeTestPromptConsumed()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(paddingValues)
        ) {
            ReferenceHeader(
                title = "Code",
                subtitle = if (uiState.isStreaming) uiState.generationStatus.ifBlank { "Analyzing" } else "Workspace"
            ) {
            }

            CodeWorkspaceContent(
                uiState = uiState,
                onCodeChange = viewModel::updateCode,
                onInstructionChange = viewModel::updateInstruction,
                onPaneChange = viewModel::selectPane,
                onActionChange = viewModel::selectAction,
                onLoadScenario = viewModel::loadScenario,
                onAsk = viewModel::askAssistant,
                onAttachImage = { imagePickerLauncher.launch("image/*") },
                onRemoveImage = viewModel::removeAttachedImage,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun CodeWorkspaceContent(
    uiState: CodeWorkspaceUiState,
    onCodeChange: (String) -> Unit,
    onInstructionChange: (String) -> Unit,
    onPaneChange: (CodeWorkspacePane) -> Unit,
    onActionChange: (CodeAssistAction) -> Unit,
    onLoadScenario: (String, String, CodeAssistAction) -> Unit,
    onAsk: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val wideLayout = maxWidth >= 820.dp
        if (wideLayout) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IdeActivityRail(modifier = Modifier.width(58.dp).fillMaxHeight())
                EditorPreviewPanel(
                    uiState = uiState,
                    onCodeChange = onCodeChange,
                    onPaneChange = onPaneChange,
                    modifier = Modifier.weight(1.2f).fillMaxHeight()
                )
                CodeAssistantPanel(
                    uiState = uiState,
                    onInstructionChange = onInstructionChange,
                    onActionChange = onActionChange,
                    onLoadScenario = onLoadScenario,
                    onAsk = onAsk,
                    onAttachImage = onAttachImage,
                    onRemoveImage = onRemoveImage,
                    modifier = Modifier.weight(0.8f).fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EditorPreviewPanel(
                    uiState = uiState,
                    onCodeChange = onCodeChange,
                    onPaneChange = onPaneChange,
                    modifier = Modifier.fillMaxWidth().height(560.dp)
                )
                CodeAssistantPanel(
                    uiState = uiState,
                    onInstructionChange = onInstructionChange,
                    onActionChange = onActionChange,
                    onLoadScenario = onLoadScenario,
                    onAsk = onAsk,
                    onAttachImage = onAttachImage,
                    onRemoveImage = onRemoveImage,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 560.dp)
                )
            }
        }
    }
}

@Composable
private fun IdeActivityRail(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = IdeBackground,
        border = BorderStroke(1.dp, IdeBorder),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RailIcon(Icons.Outlined.Code, "Files", selected = true)
            RailIcon(Icons.Outlined.Visibility, "Preview", selected = false)
            RailIcon(Icons.Outlined.Terminal, "Terminal", selected = false)
        }
    }
}

@Composable
private fun RailIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) IdeAccent else IdeMuted,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = if (selected) IdeAccent else IdeMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun EditorPreviewPanel(
    uiState: CodeWorkspaceUiState,
    onCodeChange: (String) -> Unit,
    onPaneChange: (CodeWorkspacePane) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = IdePanel,
        border = BorderStroke(1.dp, IdeBorder),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            IdeTabBar(
                selectedPane = uiState.selectedPane,
                fileName = "Code",
                onPaneChange = onPaneChange
            )
            when (uiState.selectedPane) {
                CodeWorkspacePane.Editor -> IdeEditor(
                    uiState = uiState,
                    onCodeChange = onCodeChange,
                    modifier = Modifier.weight(1f)
                )
                CodeWorkspacePane.Preview -> WebsitePreviewPane(
                    uiState = uiState,
                    modifier = Modifier.weight(1f)
                )
            }
            IdeStatusBar(uiState)
        }
    }
}

@Composable
private fun IdeTabBar(
    selectedPane: CodeWorkspacePane,
    fileName: String,
    onPaneChange: (CodeWorkspacePane) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IdeBackground)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IdeTab(
            text = fileName,
            icon = Icons.Outlined.Code,
            selected = selectedPane == CodeWorkspacePane.Editor,
            onClick = { onPaneChange(CodeWorkspacePane.Editor) }
        )
        IdeTab(
            text = "Preview",
            icon = Icons.Outlined.Visibility,
            selected = selectedPane == CodeWorkspacePane.Preview,
            onClick = { onPaneChange(CodeWorkspacePane.Preview) }
        )
    }
}

@Composable
private fun IdeTab(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(42.dp)
            .background(if (selected) IdePanel else IdeBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) IdeAccent else IdeMuted,
            modifier = Modifier.size(17.dp)
        )
        Text(
            text = text,
            color = if (selected) IdeText else IdeMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IdeEditor(
    uiState: CodeWorkspaceUiState,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IdePanelAlt)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Code,
                contentDescription = null,
                tint = IdeAccent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Code",
                color = IdeText,
                style = MaterialTheme.typography.labelMedium
            )
            if (uiState.language.isNotBlank() && uiState.language != "Plain text") {
                Text(
                    text = "- ${uiState.language}",
                    color = IdeMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        TextField(
            value = uiState.code,
            onValueChange = onCodeChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("codeWorkspaceEditor"),
            placeholder = { Text("Write or paste code", color = IdeMuted) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = IdeText,
                fontFamily = FontFamily.Monospace
            ),
            colors = TextFieldDefaults.colors(
                focusedTextColor = IdeText,
                unfocusedTextColor = IdeText,
                focusedContainerColor = IdePanel,
                unfocusedContainerColor = IdePanel,
                disabledContainerColor = IdePanel,
                cursorColor = IdeAccent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            enabled = !uiState.isStreaming
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebsitePreviewPane(
    uiState: CodeWorkspaceUiState,
    modifier: Modifier = Modifier
) {
    val html = previewHtmlFromCode(uiState.code)
    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(10.dp)
                .testTag("codeWorkspacePreview"),
            color = Color.White,
            border = BorderStroke(1.dp, IdeBorder),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (html.isBlank()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = null,
                            tint = IdeMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Run code to see a preview",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.textZoom = 100
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            "https://localhost",
                            html,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun IdeStatusBar(uiState: CodeWorkspaceUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(IdeAccent.copy(alpha = 0.22f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Code",
            color = IdeText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = uiState.language,
            color = IdeText,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = "${uiState.code.lines().size.coerceAtLeast(1)} lines",
            color = IdeText,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun CodeAssistantPanel(
    uiState: CodeWorkspaceUiState,
    onInstructionChange: (String) -> Unit,
    onActionChange: (CodeAssistAction) -> Unit,
    onLoadScenario: (String, String, CodeAssistAction) -> Unit,
    onAsk: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Code Assistant",
                    color = OnBackground,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
            }

            // Action chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CodeAssistAction.entries.forEach { action ->
                    ActionChip(
                        text = action.label,
                        selected = uiState.selectedAction == action,
                        onClick = { onActionChange(action) }
                    )
                }
            }

            // Quick scenario chips
            Text(
                text = "Quick Scenarios",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScenarioChip("Kotlin Coroutine", onClick = {
                    onLoadScenario(
                        "suspend fun <T> fetchDataWithRetry(\n    apiCall: suspend () -> T,\n    maxRetries: Int = 3\n): Flow<T> = flow {\n    var attempts = 0\n    while (attempts < maxRetries) {\n        try {\n            emit(apiCall())\n            break\n        } catch (e: IOException) {\n            attempts++\n            delay(1000L * attempts)\n        }\n    }\n}.buffer(Channel.CONFLATED)",
                        "Explain how this Kotlin coroutine retry logic works",
                        CodeAssistAction.Explain
                    )
                })
                ScenarioChip("Python SQL", onClick = {
                    onLoadScenario(
                        "import sqlite3\n\ndef get_user(user_id):\n    conn = sqlite3.connect('data.db')\n    cursor = conn.cursor()\n    cursor.execute(f'SELECT * FROM users WHERE id = {user_id}')\n    result = cursor.fetchall()\n    return result",
                        "Find the security issues in this Python database code",
                        CodeAssistAction.Debug
                    )
                })
                ScenarioChip("JS Optimize", onClick = {
                    onLoadScenario(
                        "const users = await db.users.findAll();\nfor (const user of users) {\n    user.orders = await db.orders.find({ userId: user.id });\n}",
                        "Optimize this JavaScript database query loop",
                        CodeAssistAction.Optimize
                    )
                })
                ScenarioChip("Java Review", onClick = {
                    onLoadScenario(
                        "public class UserRepository {\n    private static UserRepository instance;\n    private List<User> users = new ArrayList<>();\n    \n    public static UserRepository getInstance() {\n        if (instance == null) {\n            instance = new UserRepository();\n        }\n        return instance;\n    }\n}",
                        "Review this Java singleton for thread safety issues",
                        CodeAssistAction.Review
                    )
                })
                ScenarioChip("SQL Fix", onClick = {
                    onLoadScenario(
                        "SELECT u.name, o.total\nFROM users u, orders o\nWHERE u.id = o.user_id\nAND o.status = 'pending'\nORDER BY o.total DESC",
                        "Fix this SQL query to use proper JOIN syntax",
                        CodeAssistAction.Fix
                    )
                })
                ScenarioChip("CSS Debug", onClick = {
                    onLoadScenario(
                        ".container {\n    display: flex;\n    justify-content: center;\n    align-items: center;\n    flex-direction: column;\n}\n.sidebar {\n    width: 300px;\n    position: fixed;\n    left: 0;\n    height: 100vh;\n}\n.main {\n    margin-left: 300px;\n    width: 100%;\n}",
                        "Debug why the main content overflows on mobile",
                        CodeAssistAction.Debug
                    )
                })
            }

            // Attached image preview
            if (uiState.attachedImage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Image(
                        bitmap = uiState.attachedImage.asImageBitmap(),
                        contentDescription = "Attached image",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove image",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = uiState.instruction,
                onValueChange = onInstructionChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("codeWorkspaceInstruction"),
                placeholder = { Text(getPlaceholderForAction(uiState.selectedAction)) },
                minLines = 2,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnBackground),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = SurfaceVariant,
                    cursorColor = Primary,
                    focusedTextColor = OnBackground,
                    unfocusedTextColor = OnBackground
                ),
                shape = RoundedCornerShape(10.dp),
                enabled = !uiState.isStreaming
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onAttachImage,
                    enabled = !uiState.isStreaming,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = "Attach image",
                        tint = if (uiState.isStreaming) TextSecondary else Primary
                    )
                }

                Button(
                    onClick = onAsk,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("codeWorkspaceAskButton"),
                    enabled = (uiState.instruction.isNotBlank() || uiState.code.isNotBlank() || uiState.attachedImage != null) && !uiState.isStreaming
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = OnPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ask")
                }
            }

            if (uiState.isStreaming && uiState.generationStatus.isNotBlank()) {
                GenerationFeedback(
                    status = uiState.generationStatus,
                    detail = "Analyzing your code",
                    modifier = Modifier.padding(top = 10.dp, start = 0.dp, end = 0.dp)
                )
            }

            // Response area - chat style
            if (uiState.messages.isNotEmpty() || uiState.responseText.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp),
                    color = Background,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.messages) { message ->
                            ResponseMessageBubble(
                                text = message.content,
                                isUser = message.role == "user"
                            )
                        }
                        if (uiState.responseText.isNotBlank()) {
                            item {
                                ResponseMessageBubble(
                                    text = uiState.responseText,
                                    isUser = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Primary else Color.White,
        border = BorderStroke(1.dp, if (selected) Primary else SurfaceVariant),
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (selected) OnPrimary else OnBackground,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun ScenarioChip(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF0F9FF),
        border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
        modifier = Modifier.height(28.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color(0xFF0369A1),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ResponseMessageBubble(
    text: String,
    isUser: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (isUser) "You" else "Assistant",
            color = Primary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 4.dp else 12.dp,
                bottomEnd = if (isUser) 12.dp else 4.dp
            ),
            color = if (isUser) Primary.copy(alpha = 0.12f) else Color.White,
            border = BorderStroke(1.dp, if (isUser) Primary.copy(alpha = 0.3f) else SurfaceVariant),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Text(
                text = text,
                color = OnBackground,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

private fun getPlaceholderForAction(action: CodeAssistAction): String {
    return when (action) {
        CodeAssistAction.Explain -> "Ask me to explain this code..."
        CodeAssistAction.Debug -> "Ask me to find bugs in this code..."
        CodeAssistAction.Fix -> "Ask me to fix issues in this code..."
        CodeAssistAction.Optimize -> "Ask me to optimize this code..."
        CodeAssistAction.Review -> "Ask me to review this code..."
    }
}

/**
 * Convert raw code into a previewable HTML string.
 * - If the code is already a complete HTML document, pass it through (with repair if needed).
 * - If it's an HTML fragment (has <div>, <nav>, etc. but no <html>), wrap it properly.
 * - If it's pure CSS, wrap it in an HTML shell with a demo element.
 * - If it's pure JS, wrap it in an HTML shell with an output div.
 */
private fun previewHtmlFromCode(code: String): String {
    val trimmed = code.trim()
    if (trimmed.isBlank()) return ""
    val lower = trimmed.lowercase()

    // Case 1: Complete HTML document - pass through, but ensure it has viewport meta
    if (lower.contains("<!doctype") || (lower.contains("<html") && lower.contains("</html>"))) {
        return ensureViewportMeta(trimmed)
    }

    // Case 2: Has <body> or <head> but is missing <html> wrapper
    if (lower.contains("<body") || lower.contains("<head")) {
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif; }</style>
</head>
$trimmed
</html>""".trimIndent()
    }

    // Case 3: Has HTML content tags (<div>, <nav>, <main>, etc.) - wrap in full doc
    val htmlContentTags = listOf("<div", "<main", "<section", "<nav", "<header", "<footer", "<article", "<form", "<table", "<input", "<button", "<h1", "<h2", "<h3", "<p>", "<ul", "<ol", "<li", "<span", "<img", "<a ")
    if (htmlContentTags.any { lower.contains(it) }) {
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif; padding: 16px; }</style>
</head>
<body>
$trimmed
</body>
</html>""".trimIndent()
    }

    // Case 4: Pure CSS (has selectors and braces)
    if ((lower.contains("{") && lower.contains("}")) &&
        (lower.contains("@media") || lower.contains("@keyframes") || lower.contains(":") && lower.contains(";"))) {
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif; padding: 16px; }</style>
  <style>
$trimmed
  </style>
</head>
<body>
  <div class="preview">CSS Preview - styles applied</div>
  <div>Hello World</div>
  <div class="container">Content Area</div>
</body>
</html>""".trimIndent()
    }

    // Case 5: Pure JavaScript
    val jsIndicators = listOf("document.", "queryselector", "getelementbyid", "addeventlistener", "createelement", "function ", "const ", "let ", "window.", "console.log")
    if (jsIndicators.any { lower.contains(it) }) {
        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif; padding: 16px; } #output { white-space: pre-wrap; font-family: monospace; }</style>
</head>
<body>
  <div id="output"></div>
  <script>
    // Override console.log to show in the output div
    const _origLog = console.log;
    console.log = function(...args) { _origLog.apply(console, args); document.getElementById('output').textContent += args.join(' ') + '\n'; };
  </script>
  <script>
$trimmed
  </script>
</body>
</html>""".trimIndent()
    }

    // Case 6: Unknown / not previewable
    return ""
}

/**
 * Ensure a complete HTML document has the viewport meta tag for mobile preview.
 * Insert it inside <head> if missing.
 */
private fun ensureViewportMeta(html: String): String {
    val lower = html.lowercase()
    if (lower.contains("viewport")) return html
    // Insert viewport meta right after <head>
    val headRegex = Regex("(<head[^>]*>)", RegexOption.IGNORE_CASE)
    return headRegex.replace(html) { result ->
        "${result.value}\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    }
}
