package com.localyze.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localyze.tools.Tool
import com.localyze.tools.ToolRegistry
import com.localyze.ui.theme.Background
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.Primary
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug tool tester screen â€” only visible in debug builds.
 * Accessible via easter egg (5 taps on version number in Settings).
 * Shows all 10 tools, allows executing each with test parameters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugToolTesterScreen(
    toolRegistry: ToolRegistry,
    onBack: () -> Unit
) {
    val tools = toolRegistry.getAllTools()
    var executingTool by remember { mutableStateOf<String?>(null) }
    var toolResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ðŸ”§ Debug Tool Tester",
                        fontWeight = FontWeight.Bold,
                        color = OnBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = OnBackground
                        )
                    }
                }
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Tap a tool to execute it with test parameters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(tools) { tool ->
                ToolTesterCard(
                    tool = tool,
                    isExecuting = executingTool == tool.name,
                    onExecute = {
                        executingTool = tool.name
                        toolResult = null
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // Execute tool when requested
    LaunchedEffect(executingTool) {
        if (executingTool != null) {
            val tool = tools.find { it.name == executingTool }
            if (tool != null) {
                try {
                    val testArgs = getTestArgsForTool(tool.name)
                    val result = tool.execute(testArgs)
                    toolResult = tool.name to result
                } catch (e: Exception) {
                    toolResult = tool.name to "Error: ${e.message}"
                }
            }
            executingTool = null
        }
    }

    // Show result dialog
    if (toolResult != null) {
        AlertDialog(
            onDismissRequest = { toolResult = null },
            title = {
                Text(
                    text = "Result: ${toolResult!!.first}",
                    fontWeight = FontWeight.Bold,
                    color = OnBackground
                )
            },
            text = {
                Text(
                    text = toolResult!!.second,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnBackground
                )
            },
            confirmButton = {
                Button(
                    onClick = { toolResult = null },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Primary
                    )
                ) {
                    Text("OK")
                }
            },
            containerColor = SurfaceVariant
        )
    }
}

@Composable
private fun ToolTesterCard(
    tool: Tool,
    isExecuting: Boolean,
    onExecute: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = OnBackground
                )
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Primary,
                    strokeWidth = 2.dp
                )
            } else {
                OutlinedButton(
                    onClick = onExecute,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Run", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Returns test parameters for each tool.
 */
private fun getTestArgsForTool(toolName: String): kotlinx.serialization.json.JsonObject {
    return when (toolName) {
        "calendar" -> buildJsonObject {
            put("action", "read")
            put("start_date", "2026-04-15")
            put("end_date", "2026-04-22")
            put("max_results", 5)
        }
        "contacts" -> buildJsonObject {
            put("name", "test")
        }
        "alarm" -> buildJsonObject {
            put("label", "Test reminder")
            put("trigger_iso", java.time.Instant.now().plusSeconds(60).toString())
        }
        "clipboard_read" -> buildJsonObject { }
        "clipboard_write" -> buildJsonObject {
            put("text", "hello from AI")
        }
        "system_info" -> buildJsonObject { }
        "web_search" -> buildJsonObject {
            put("query", "android development")
        }
        "file_read" -> buildJsonObject {
            put("path", "/proc/version")
        }
        "memory_save" -> buildJsonObject {
            put("fact", "I am vegetarian")
            put("tags", "diet,food,preference")
        }
        "memory_search" -> buildJsonObject {
            put("keywords", "vegetarian,food")
        }
        else -> buildJsonObject { }
    }
}