package com.localyze

import com.localyze.ui.components.ChartType
import com.localyze.ui.components.extractChartData
import com.localyze.ui.components.extractChartResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InlineChartExtractionTest {

    @Test
    fun extractsAppleRevenueTableAsBarChart() {
        val answer = """
            | Fiscal year | Revenue (USD billions) |
            |---|---:|
            | 2023 | 383.3 |
            | 2024 | 391.0 |
            | 2025 | 416.2 |
        """.trimIndent()

        val chartResult = extractChartData(answer)

        assertNotNull(chartResult)
        val data = chartResult!!.data
        assertEquals(ChartType.BAR, chartResult.type)
        assertEquals(listOf("2023", "2024", "2025"), data.map { it.label })
        assertEquals(383.3f, data[0].value, 0.01f)
        assertEquals(391.0f, data[1].value, 0.01f)
        assertEquals(416.2f, data[2].value, 0.01f)
    }

    @Test
    fun extractsStockPriceTableAsLineChart() {
        val answer = """
            | Date | Close (USD) |
            |---|---:|
            | 2025-01 | 234.10 |
            | 2025-02 | 241.55 |
            | 2025-03 | 228.90 |
            | 2025-04 | 236.40 |
        """.trimIndent()

        val chartResult = extractChartData(answer)

        assertNotNull(chartResult)
        assertEquals(ChartType.LINE, chartResult!!.type)
        assertEquals(4, chartResult.data.size)
    }

    @Test
    fun extractsPercentageTableAsPieChart() {
        val answer = """
            | Category | Share |
            |---|---:|
            | iPhone | 52% |
            | Services | 22% |
            | Mac | 10% |
            | iPad | 8% |
            | Other | 8% |
        """.trimIndent()

        val chartResult = extractChartData(answer)

        assertNotNull(chartResult)
        assertEquals(ChartType.PIE, chartResult!!.type)
        assertEquals(5, chartResult.data.size)
    }

    @Test
    fun extractsGenericTableAsBarChart() {
        val answer = """
            | Product | Sales |
            |---|---:|
            | Widget A | 120 |
            | Widget B | 340 |
            | Widget C | 210 |
        """.trimIndent()

        val chartResult = extractChartData(answer)

        assertNotNull(chartResult)
        assertEquals(ChartType.BAR, chartResult!!.type)
        assertEquals(3, chartResult.data.size)
    }

    @Test
    fun extractsKeyValuePairsAsBarChart() {
        val answer = """
            - Revenue: $394B
            - Expenses: $250B
            - Profit: $144B
        """.trimIndent()

        val chartResult = extractChartData(answer)

        assertNotNull(chartResult)
        assertEquals(ChartType.BAR, chartResult!!.type)
        assertEquals(3, chartResult.data.size)
    }

    @Test
    fun usesFirstEligibleTableWhenAnswerContainsMultipleFinancialTables() {
        val answer = """
            | Fiscal year | Revenue (USD billions) |
            |---|---:|
            | 2023 | 22.7 |
            | 2024 | 25.8 |
            | 2025 | 34.6 |

            | Fiscal year | Revenue (USD billions) |
            |---|---:|
            | 2023 | 27.0 |
            | 2024 | 60.9 |
            | 2025 | 130.5 |
        """.trimIndent()

        val chartResult = extractChartData(answer)

        assertNotNull(chartResult)
        assertEquals(ChartType.BAR, chartResult!!.type)
        assertEquals(listOf("2023", "2024", "2025"), chartResult.data.map { it.label })
        assertEquals(34.6f, chartResult.data.last().value, 0.01f)
    }

    @Test
    fun extractsAllEligibleTablesForMultiCompanyVisualization() {
        val answer = """
            ## AMD Revenue

            | Fiscal year | Revenue (USD billions) |
            |---|---:|
            | 2023 | 22.7 |
            | 2024 | 25.8 |
            | 2025 | 34.6 |

            ## NVIDIA Revenue

            | Fiscal year | Revenue (USD billions) |
            |---|---:|
            | 2023 | 27.0 |
            | 2024 | 60.9 |
            | 2025 | 130.5 |
        """.trimIndent()

        val charts = extractChartResults(answer)

        assertEquals(2, charts.size)
        assertEquals(ChartType.BAR, charts[0].type)
        assertEquals(ChartType.BAR, charts[1].type)
        assertEquals(34.6f, charts[0].data.last().value, 0.01f)
        assertEquals(130.5f, charts[1].data.last().value, 0.01f)
    }
}
