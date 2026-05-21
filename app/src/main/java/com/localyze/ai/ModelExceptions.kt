package com.localyze.ai

/**
 * Thrown when the model file cannot be found at the expected path.
 */
class ModelNotFoundException(message: String) : Exception(message)

/**
 * Thrown when the model fails to load (e.g., corrupt file, incompatible format).
 */
class ModelLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when an error occurs during inference generation.
 */
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the combined input exceeds the model's context window capacity.
 */
class ContextWindowExceededException(message: String) : Exception(message)

