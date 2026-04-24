package com.localyze

import com.localyze.ui.viewmodels.ChatViewModel
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimeoutPolicyTest {
    @Test
    fun generationTimeout_allowsLongProductionResponses() {
        assertTrue(
            "Long assistant responses should not be cut off by the old 60 second timeout",
            ChatViewModel.GENERATION_TIMEOUT_MS >= 180_000L
        )
    }
}
