package com.localyze.ai

/**
 * Sealed class representing the state of model loading.
 */
sealed class ModelLoadState {

    /** Model has not been loaded yet. */
    data object NotLoaded : ModelLoadState()

    /** Model is currently being loaded, with optional progress indicator. */
    data class Loading(val progress: Float = 0f) : ModelLoadState()

    /** Model has been successfully loaded and is ready for inference. */
    data object Loaded : ModelLoadState()

    /** An error occurred during model loading. */
    data class Error(val message: String, val cause: Throwable? = null) : ModelLoadState()
}