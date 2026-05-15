package com.localyze

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceButtonTest {
    @get:Rule
    val composeRule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(codeWorkspaceIntent()),
        activityProvider = { rule -> rule.getActivity() }
    )

    @Test
    fun askButtonIsVisibleInCodeWorkspace() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("Editor")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNode(hasText("Editor")).assertExists()
        composeRule.onNode(hasText("Preview")).assertExists()
        composeRule.onNode(hasContentDescription("Send")).assertExists()
    }

    private fun ActivityScenarioRule<MainActivity>.getActivity(): MainActivity {
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        return requireNotNull(activity)
    }
}

private fun codeWorkspaceIntent(): Intent {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    return Intent(context, MainActivity::class.java).apply {
        putExtra("triggerTest", true)
        putExtra("testPrompt", "code-workspace-device-test")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
