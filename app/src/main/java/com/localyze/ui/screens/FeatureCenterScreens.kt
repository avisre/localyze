package com.localyze.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localyze.domain.models.AttachmentMemory
import com.localyze.domain.models.ReplyDraft
import com.localyze.domain.models.Task
import com.localyze.domain.models.ToolAudit
import com.localyze.ui.theme.Background
import com.localyze.ui.theme.Nunito
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.Surface
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary
import com.localyze.ui.viewmodels.AttachmentMemoryViewModel
import com.localyze.ui.viewmodels.BackupViewModel
import com.localyze.ui.viewmodels.PerformanceViewModel
import com.localyze.ui.viewmodels.ProactiveViewModel
import com.localyze.ui.viewmodels.ReplyAssistantViewModel
import com.localyze.ui.viewmodels.ToolCenterItem
import com.localyze.ui.viewmodels.ToolCenterViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCenterScreen(
    onBack: () -> Unit,
    viewModel: ToolCenterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    StandardFeatureScaffold(title = "Tool Approval", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionTitle("Tools") }
            items(uiState.tools, key = { item -> item.name }) { item ->
                ToolItem(item)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("Recent Activity", Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearHistory() }) { Text("Clear") }
                }
            }
            items(uiState.audits, key = { audit -> audit.id }) { audit ->
                ToolAuditItem(audit)
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ToolItem(item: ToolCenterItem) {
    FeatureCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, color = OnBackground)
                Text(item.description, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = item.riskLevel.uppercase(),
                color = when (item.riskLevel) {
                    "high" -> MaterialTheme.colorScheme.error
                    "medium" -> Primary
                    else -> TextSecondary
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
        Text(
            text = if (item.requiresConfirmation) "Requires confirmation" else "Runs without confirmation",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ToolAuditItem(audit: ToolAudit) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    FeatureCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(audit.toolName, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(audit.status, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
        Text("${audit.riskLevel} risk - ${dateFormat.format(Date(audit.createdAt))}", color = TextSecondary)
        if (audit.resultPreview.isNotBlank()) {
            Text(audit.resultPreview, color = OnBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentMemoryScreen(
    onBack: () -> Unit,
    viewModel: AttachmentMemoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }
    StandardFeatureScaffold(title = "Attachments", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                        Text(if (uiState.isWorking) "Importing" else "Add Attachment")
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.updateQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search attachment memory") }
                )
            }
            items(uiState.attachments, key = { attachment -> attachment.id }) { attachment ->
                AttachmentItem(attachment, onDelete = { viewModel.delete(attachment.id) })
            }
        }
    }
}

@Composable
private fun AttachmentItem(attachment: AttachmentMemory, onDelete: () -> Unit) {
    FeatureCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(attachment.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(attachment.mimeType, color = TextSecondary)
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        Text(attachment.summary.ifBlank { "Saved for later reference." }, color = OnBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyAssistantScreen(
    onBack: () -> Unit,
    viewModel: ReplyAssistantViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showManualDialog by remember { mutableStateOf(false) }
    StandardFeatureScaffold(title = "Reply Assistant", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openNotificationSettings(context) }, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                        Text("Notification Access")
                    }
                    TextButton(onClick = { showManualDialog = true }) { Text("New Draft") }
                }
            }
            items(uiState.drafts, key = { draft -> draft.id }) { draft ->
                ReplyDraftItem(
                    draft = draft,
                    onSave = { text -> viewModel.updateDraft(draft, text) },
                    onShare = { shareDraft(context, draft.draftText.ifBlank { draft.originalText }) },
                    onHandled = { viewModel.setHandled(draft.id, !draft.isHandled) },
                    onDelete = { viewModel.delete(draft.id) }
                )
            }
        }
    }
    if (showManualDialog) {
        ManualReplyDialog(
            onDismiss = { showManualDialog = false },
            onSave = { sender, original, draft ->
                viewModel.saveManualDraft(sender, original, draft)
                showManualDialog = false
            }
        )
    }
}

@Composable
private fun ReplyDraftItem(
    draft: ReplyDraft,
    onSave: (String) -> Unit,
    onShare: () -> Unit,
    onHandled: () -> Unit,
    onDelete: () -> Unit
) {
    var text by remember(draft.id, draft.draftText) { mutableStateOf(draft.draftText) }
    FeatureCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(draft.sender, fontWeight = FontWeight.SemiBold)
                Text(draft.sourcePackage, color = TextSecondary)
            }
            Text(if (draft.isHandled) "Handled" else "Open", color = TextSecondary)
        }
        Text(draft.originalText, color = TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text("Draft") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
            TextButton(onClick = onShare) { Text("Share") }
            TextButton(onClick = onHandled) { Text(if (draft.isHandled) "Reopen" else "Done") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun ManualReplyDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var sender by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Reply Draft") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(original, { original = it }, label = { Text("Original text") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft, { draft = it }, label = { Text("Draft reply") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onSave(sender, original, draft) }, enabled = original.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Surface
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    onBack: () -> Unit,
    viewModel: PerformanceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    StandardFeatureScaffold(title = "Performance", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Button(onClick = { viewModel.refresh() }, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                    Text("Refresh")
                }
            }
            item { MetricCard("Model", uiState.modelLoadState, "Backend: ${uiState.backend} - ${uiState.modelSizeMb} MB") }
            item { MetricCard("Speed", "%.1f token/sec".format(uiState.performance.lastTokensPerSecond), "${uiState.performance.lastTokenCount} rough tokens last response") }
            item { MetricCard("Memory", "%.1f GB available".format(uiState.availableRamGb), "%.1f GB total - %.0f MB app heap".format(uiState.totalRamGb, uiState.appHeapUsedMb)) }
            item { MetricCard("Storage", "%.1f GB available".format(uiState.availableStorageGb), "Timeouts: ${uiState.performance.totalTimeouts} - Responses: ${uiState.performance.totalResponses}") }
            uiState.performance.lastError?.let { error ->
                item { MetricCard("Last Error", error, "") }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, subtitle: String) {
    FeatureCard {
        Text(title, color = TextSecondary)
        Text(value, color = OnBackground, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) Text(subtitle, color = TextSecondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    StandardFeatureScaffold(title = "Backups", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = uiState.passphrase,
                onValueChange = { viewModel.setPassphrase(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Backup passphrase") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.exportBackup() }, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                    Text(if (uiState.isWorking) "Working" else "Export")
                }
                TextButton(onClick = { viewModel.importBackup() }) { Text("Import") }
                TextButton(onClick = { copyText(context, uiState.backupText) }) { Text("Copy") }
                TextButton(onClick = { shareDraft(context, uiState.backupText) }) { Text("Share") }
            }
            uiState.status?.let { Text(it, color = Primary) }
            uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = { viewModel.setInputText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                minLines = 10,
                label = { Text("Backup text") }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveScreen(
    onBack: () -> Unit,
    viewModel: ProactiveViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    StandardFeatureScaffold(title = "Proactive", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SwitchRow("Proactive assistant", uiState.proactiveAssistant, viewModel::setProactiveAssistant) }
            item { SwitchRow("Task follow-ups", uiState.taskFollowups, viewModel::setTaskFollowups) }
            item { SwitchRow("Daily summary", uiState.dailySummary, viewModel::setDailySummary) }
            item {
                HorizontalDivider(color = SurfaceVariant)
                SectionTitle("Pending Tasks")
            }
            items(uiState.pendingTasks, key = { task -> task.id }) { task ->
                TaskPreview(task)
            }
        }
    }
}

@Composable
private fun TaskPreview(task: Task) {
    FeatureCard {
        Text(task.title, color = OnBackground, fontWeight = FontWeight.SemiBold)
        if (task.description.isNotBlank()) Text(task.description, color = TextSecondary)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    FeatureCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), color = OnBackground, fontWeight = FontWeight.SemiBold)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StandardFeatureScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontFamily = Nunito, fontWeight = FontWeight.Bold, color = OnBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = OnBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { inner ->
        content(
            androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = inner.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = inner.calculateBottomPadding() + 16.dp
            )
        )
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(vertical = 4.dp),
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun FeatureCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun shareDraft(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share draft"))
}

private fun copyText(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Localyze", text))
}
