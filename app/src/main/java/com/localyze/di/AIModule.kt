package com.localyze.di

import com.localyze.ai.GemmaModelInitializer
import com.localyze.ai.ModelInitializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for AI layer dependencies.
 *
 * Provides the real inference engine (GemmaInferenceEngine) and
 * model initialization. Mock mode has been removed — the app
 * always uses the real Gemma 4 E4B LiteRT-LM model.
 */
@Module
@InstallIn(SingletonComponent::class)
object AIModule {

    @Provides
    @Singleton
    fun provideModelInitializer(initializer: GemmaModelInitializer): ModelInitializer = initializer
}
