package com.localyze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CodeTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Forward to MainActivity to trigger the test
        val prompt = intent.getStringExtra("prompt") ?: "make a portfolio website with animations"
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            putExtra("triggerTest", true)
            putExtra("testPrompt", prompt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }
}
