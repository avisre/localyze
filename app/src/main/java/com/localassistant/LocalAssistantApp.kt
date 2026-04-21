package com.localassistant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class LocalAssistantApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureModelsDirectory()
    }

    private fun ensureModelsDirectory() {
        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }
}