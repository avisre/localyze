package com.localyze.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localyze.ui.theme.OnBackground
import com.localyze.ui.theme.SurfaceVariant
import com.localyze.ui.theme.TextSecondary
import kotlin.coroutines.cancellation.CancellationException

/**
 * A reusable error boundary that catches errors in its children and displays a fallback UI.
 *
 * Uses SubcomposeLayout to isolate child composition so that a crash in any
 * single composable does not tear down the entire screen.
 */
class ErrorBoundaryState {
    var hasError by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set

    fun catchError(throwable: Throwable) {
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
    modifier: Modifier = Modifier,
    state: ErrorBoundaryState = rememberErrorBoundaryState(),
    label: String = "content",
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
        Box(modifier = modifier) {
            fallback(state.error!!) { state.reset() }
        }
    } else {
        androidx.compose.ui.layout.SubcomposeLayout(modifier = modifier) { constraints ->
            val placeable = try {
                subcompose("error_boundary_content") {
                    content()
                }.firstOrNull()?.measure(constraints)
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    state.catchError(e)
                }
                null
            }

            if (placeable == null || state.hasError) {
                val fallbackPlaceable = subcompose("error_boundary_fallback") {
                    fallback(
                        state.error ?: RuntimeException("Composition failed for $label"),
                        { state.reset() }
                    )
                }.first().measure(constraints)
                layout(fallbackPlaceable.width, fallbackPlaceable.height) {
                    fallbackPlaceable.placeRelative(0, 0)
                }
            } else {
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }
        }
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
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = "Error",
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = error.message ?: "An unexpected error occurred",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}

/**
 * A wrapper for async operations that catches errors.
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
