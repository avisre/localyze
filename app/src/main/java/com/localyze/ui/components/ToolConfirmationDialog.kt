package com.localyze.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localyze.tools.DispatchResult
import com.localyze.domain.models.ToolResult

/**
 * State holder for tool confirmation dialogs.
 */
class ToolConfirmationState {
    var pendingConfirmation by mutableStateOf<DispatchResult.PendingConfirmation?>(null)
        private set
    var isExecuting by mutableStateOf(false)
        private set
    var lastResult by mutableStateOf<ToolResult?>(null)
        private set

    fun showConfirmation(pending: DispatchResult.PendingConfirmation) {
        pendingConfirmation = pending
        isExecuting = false
    }

    fun startExecution() {
        isExecuting = true
    }

    fun complete(result: ToolResult) {
        lastResult = result
        pendingConfirmation = null
        isExecuting = false
    }

    fun dismiss() {
        pendingConfirmation = null
        isExecuting = false
    }

    fun clearLastResult() {
        lastResult = null
    }
}

@Composable
fun rememberToolConfirmationState(): ToolConfirmationState {
    return remember { ToolConfirmationState() }
}

/**
 * Dialog for confirming tool execution.
 */
@Composable
fun ToolConfirmationDialog(
    pending: DispatchResult.PendingConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isExecuting: Boolean = false
) {
    AlertDialog(
        onDismissRequest = { if (!isExecuting) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Confirm Action",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = pending.message,
                    style = MaterialTheme.typography.bodyMedium
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "Tool: ${pending.tool.name}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = pending.tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isExecuting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Executing...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isExecuting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text("Confirm", modifier = Modifier.padding(start = 8.dp))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isExecuting
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text("Cancel", modifier = Modifier.padding(start = 8.dp))
            }
        }
    )
}

/**
 * Shows a snackbar-style result notification for completed tool calls.
 */
@Composable
fun ToolResultNotification(
    result: ToolResult,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (result.isError) "Tool Error" else "Tool Completed",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (result.isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
                Text(
                    text = if (result.isError) {
                        result.result.take(100)
                    } else {
                        "${result.name} completed successfully"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Handles tool confirmations in a screen.
 * Call this from your screen Composable.
 *
 * Usage:
 * ```
 * val confirmationState = rememberToolConfirmationState()
 * val scope = rememberCoroutineScope()
 *
 * // Handle confirmations
 * HandleToolConfirmation(
 *     state = confirmationState,
 *     onExecute = { pending ->
 *         viewModel.confirmToolExecution(pending)
 *     }
 * )
 *
 * // Show confirmation when needed
 * LaunchedEffect(uiState.pendingTool) {
 *     uiState.pendingTool?.let {
 *         confirmationState.showConfirmation(it)
 *     }
 * }
 * ```
 */
@Composable
fun HandleToolConfirmation(
    state: ToolConfirmationState,
    onExecute: suspend (DispatchResult.PendingConfirmation) -> ToolResult
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pending = state.pendingConfirmation

    // Handle execution when isExecuting becomes true
    androidx.compose.runtime.LaunchedEffect(state.isExecuting, pending) {
        if (state.isExecuting && pending != null) {
            val result = onExecute(pending)
            state.complete(result)
        }
    }

    if (pending != null && !state.isExecuting) {
        ToolConfirmationDialog(
            pending = pending,
            onConfirm = {
                state.startExecution()
            },
            onDismiss = { state.dismiss() },
            isExecuting = state.isExecuting
        )
    }

    // Show result notification
    state.lastResult?.let { result ->
        ToolResultNotification(
            result = result,
            onDismiss = { state.clearLastResult() }
        )
    }
}