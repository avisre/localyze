package com.localyze.ai

import javax.inject.Inject
import javax.inject.Singleton

interface ModelInitializer {
    suspend fun initialize()
}

@Singleton
class GemmaModelInitializer @Inject constructor(
    private val gemmaInferenceEngine: GemmaInferenceEngine
) : ModelInitializer {
    override suspend fun initialize() {
        gemmaInferenceEngine.initialize()
    }
}
