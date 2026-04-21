package com.localyze.di

import android.content.Context
import com.localyze.tools.ToolRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for accessing ToolRegistry from outside of Android entry points.
 * Used by DebugToolTesterScreen to get access to tools.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ToolRegistryEntryPoint {
    fun toolRegistry(): ToolRegistry
}

/**
 * Static accessor for ToolRegistry.
 */
object ToolProvider {
    private var toolRegistry: ToolRegistry? = null

    fun getToolRegistry(context: Context): ToolRegistry {
        return toolRegistry ?: EntryPoints.get(context.applicationContext, ToolRegistryEntryPoint::class.java).toolRegistry().also {
            toolRegistry = it
        }
    }
}
