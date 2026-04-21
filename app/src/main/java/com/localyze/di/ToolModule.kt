package com.localyze.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for tool dependencies.
 *
 * All tool classes (CalendarTool, ContactsTool, etc.) have @Inject constructors,
 * so Hilt can provide them automatically. ToolRegistry also uses @Inject with
 * all tools as constructor parameters, so Hilt resolves the full graph.
 *
 * This module is kept as a placeholder for any future tool-specific bindings
 * that may require manual @Provides methods (e.g. tool-specific qualifiers).
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolModule