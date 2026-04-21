package com.localyze

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class LocalyzeApp : Application() {

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