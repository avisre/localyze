package com.localassistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/**
 * A reusable error boundary that catches errors in its children and displays a fallback UI.
 *
 * Usage:
 * ```
 * ErrorBoundary {
 *     ChatScreen()
 * }
 * ```
 */
class ErrorBoundaryState {
    var hasError by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set

    fun catchError(throwable: Throwable) {
        // Don't catch cancellation exceptions - they're intentional
        if (throwable is CancellationException) {
            throw throwable
        }
        hasError = true
        error = throwable
        errorMessage = throwable.message ?: "An unexpected error occurred"
    }

    fun reset() {
        hasError = false
        error = null
        errorMessage = null
    }
}

@Composable
fun rememberErrorBoundaryState(): ErrorBoundaryState {
    return remember { ErrorBoundaryState() }
}

@Composable
fun ErrorBoundary(
    state: ErrorBoundaryState = rememberErrorBoundaryState(),
    fallback: @Composable (Throwable, () -> Unit) -> Unit = { error, onRetry ->
        DefaultErrorFallback(
            error = error,
            onRetry = onRetry,
            onDismiss = { state.reset() }
        )
    },
    content: @Composable () -> Unit
) {
    if (state.hasError) {
        fallback(state.error!!) { state.reset() }
    } else {
        // Note: Compose doesn't support try-catch around composables
        // Errors should be caught at the caller level or using SideEffect
        content()
    }
}

@Composable
fun DefaultErrorFallback(
    error: Throwable,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = "Error",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = error.message ?: "An unexpected error occurred",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}

/**
 * A safer wrapper for async operations that catches errors.
 */
suspend fun <T> safeOperation(
    errorBoundary: ErrorBoundaryState? = null,
    operation: suspend () -> T
): Result<T> {
    return try {
        Result.success(operation())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorBoundary?.catchError(e)
        Result.failure(e)
    }
}