package com.localyze

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localyze.ui.components.AssistantMessageBubble
import com.localyze.ui.components.ChartData
import com.localyze.ui.components.InlineBarChart
import com.localyze.ui.components.InlineLineChart
import com.localyze.ui.theme.LocalyzeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialVisualizationInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun financialChartComponentsAreVisibleOnDevice() {
        val data = listOf(
            ChartData("2023", 22.7f),
            ChartData("2024", 25.8f),
            ChartData("2025", 34.6f)
        )

        composeRule.setContent {
            LocalyzeTheme {
                Column {
                    InlineLineChart(data = data)
                    InlineBarChart(data = data)
                }
            }
        }

        composeRule.onNodeWithContentDescription("Inline line chart", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Inline bar chart", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun multiCompanyFinancialAnswerRendersEveryTableAsChartsOnDevice() {
        composeRule.setContent {
            LocalyzeTheme {
                AssistantMessageBubble(
                    message = multiCompanyRevenueAnswer,
                    timestamp = 1L
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("Inline line chart", useUnmergedTree = true)
            .assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Inline bar chart", useUnmergedTree = true)
            .assertCountEquals(2)
    }

    private val multiCompanyRevenueAnswer = """
        ## Advanced Micro Devices Revenue (Last 3 Fiscal Years)

        | Fiscal year | Revenue (USD billions) |
        |---|---:|
        | 2023 | 22.7 |
        | 2024 | 25.8 |
        | 2025 | 34.6 |

        ## NVIDIA Revenue (Last 3 Fiscal Years)

        | Fiscal year | Revenue (USD billions) |
        |---|---:|
        | 2023 | 27.0 |
        | 2024 | 60.9 |
        | 2025 | 130.5 |
    """.trimIndent()
}
