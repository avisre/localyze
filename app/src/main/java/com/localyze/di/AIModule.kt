package com.localyze.di

import com.localyze.ai.GemmaInferenceEngine
import com.localyze.ai.GemmaModelInitializer
import com.localyze.ai.ModelInitializer
import com.localyze.ai.MockGemmaEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt module for AI layer dependencies.
 *
 * Provides both the real inference engine and the mock engine,
 * controlled by BuildConfig.USE_MOCK_ENGINE.
 *
 * IMPORTANT: The real Gemma 4 E4B LiteRT-LM model IS publicly available
 * at https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm.
 * Mock mode is retained for development velocity, CI/CD, and unsupported-device
 * fallback â€” NOT because the model format is unavailable.
 *
 * See BLOCKERS.md for migration path from mock to real model.
 */
@Module
@InstallIn(SingletonComponent::class)
object AIModule {

    @Provides
    @Singleton
    @Named("useMockEngine")
    fun provideUseMockEngine(): Boolean = com.localyze.BuildConfig.USE_MOCK_ENGINE

    @Provides
    @Singleton
    fun provideMockEngine(): MockGemmaEngine = MockGemmaEngine()

    @Provides
    @Singleton
    fun provideModelInitializer(initializer: GemmaModelInitializer): ModelInitializer = initializer
}
