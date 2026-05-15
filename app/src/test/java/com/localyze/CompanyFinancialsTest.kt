package com.localyze

import com.localyze.tools.FinancialMetric
import com.localyze.tools.FinancialSourceText
import com.localyze.tools.buildFinancialVisualizationAnswer
import com.localyze.tools.companyFinancialWebSearchQueryFor
import com.localyze.tools.parseAnnualFinancialPointsFromCompanyFactsJson
import com.localyze.tools.parseCompanyFinancialIntent
import com.localyze.ui.components.ChartType
import com.localyze.ui.components.extractChartData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyFinancialsTest {

    @Test
    fun parsesMisspelledAmdRevenueVisualizationIntent() {
        val intent = parseCompanyFinancialIntent("visulaize 3 year revenue of amd")

        assertNotNull(intent)
        assertEquals("AMD", intent!!.companies.single().ticker)
        assertEquals(FinancialMetric.REVENUE, intent.metric)
        assertEquals(3, intent.years)
        assertTrue(intent.wantsVisualization)
    }

    @Test
    fun buildsFinancialSearchQueryForOfficialAndSecSources() {
        val query = companyFinancialWebSearchQueryFor("Show 5 year net income of Microsoft")

        assertNotNull(query)
        assertTrue(query!!.contains("MSFT"))
        assertTrue(query.contains("Net income"))
        assertTrue(query.contains("official investor relations"))
        assertTrue(query.contains("SEC company facts"))
        assertTrue(query.contains("5 fiscal years"))
    }

    @Test
    fun parsesAnnualRevenueFromSecCompanyFactsByPeriodEnd() {
        val points = parseAnnualFinancialPointsFromCompanyFactsJson(
            companyFactsJson = amdCompanyFactsFixture,
            metric = FinancialMetric.REVENUE,
            years = 3
        )

        assertEquals(listOf(2023, 2024, 2025), points.map { it.fiscalYear })
        assertEquals(listOf(22_680_000_000L, 25_785_000_000L, 34_639_000_000L), points.map { it.valueUsd })
    }

    @Test
    fun choosesFreshestRevenueTagInsteadOfLargestStaleTag() {
        val points = parseAnnualFinancialPointsFromCompanyFactsJson(
            companyFactsJson = mixedRevenueTagsFixture,
            metric = FinancialMetric.REVENUE,
            years = 3
        )

        assertEquals(listOf(2023, 2024, 2025), points.map { it.fiscalYear })
        assertEquals(listOf(383_285_000_000L, 391_035_000_000L, 416_161_000_000L), points.map { it.valueUsd })
    }

    @Test
    fun buildsRevenueAnswerWithChartableFiscalYearTable() {
        val answer = buildFinancialVisualizationAnswer(
            prompt = "visualize 3 year revenue of AMD",
            sources = listOf(
                FinancialSourceText(
                    title = "Advanced Micro Devices Revenue RAG data",
                    url = "https://ir.amd.com/news-events/press-releases/detail/1276/amd-reports-fourth-quarter-and-full-year-2025-financial-results",
                    snippet = """
                        Company: Advanced Micro Devices (AMD)
                        Metric: Revenue
                        Annual data:
                        FY2023: ${'$'}22.7B
                        FY2024: ${'$'}25.8B
                        FY2025: ${'$'}34.6B
                        RAG basis: official company website pages were crawled for context; SEC XBRL company facts supplied the structured annual values.
                    """.trimIndent()
                )
            )
        )

        assertNotNull(answer)
        assertTrue(answer!!.contains("Advanced Micro Devices Revenue"))
        assertTrue(answer.contains("| Fiscal year | Revenue (USD billions) |"))
        assertTrue(answer.contains("| 2025 | 34.6 |"))
        assertTrue(answer.contains("Data grounding"))
        assertTrue(answer.contains("official investor/IR page"))

        val chart = extractChartData(answer)
        assertNotNull(chart)
        assertEquals(ChartType.BAR, chart!!.type)
        assertEquals(listOf("2023", "2024", "2025"), chart.data.map { it.label })
    }

    @Test
    fun secOnlyFinancialAnswerDoesNotClaimOfficialWebsiteCrawl() {
        val answer = buildFinancialVisualizationAnswer(
            prompt = "visualize 3 year revenue of AMD",
            sources = listOf(
                FinancialSourceText(
                    title = "AMD SEC XBRL company facts",
                    url = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=0000002488&type=10-K",
                    snippet = """
                        Company: Advanced Micro Devices (AMD)
                        Metric: Revenue
                        Annual data:
                        FY2023: ${'$'}22.7B
                        FY2024: ${'$'}25.8B
                        FY2025: ${'$'}34.6B
                        Source detail: SEC EDGAR XBRL company facts.
                    """.trimIndent()
                )
            )
        )

        assertNotNull(answer)
        assertTrue(answer!!.contains("no official company website excerpt was available"))
    }

    @Test
    fun recognizesIndianCompaniesWithInrCurrency() {
        val intent = parseCompanyFinancialIntent("show TCS revenue for the last 3 fiscal years")
        assertNotNull(intent)
        assertEquals("TCS", intent!!.companies.single().ticker)
        assertEquals(FinancialMetric.REVENUE, intent.metric)
        assertEquals("INR", intent.companies.single().currency)
    }

    @Test
    fun recognizesChineseCompaniesWithCnyCurrency() {
        val intent = parseCompanyFinancialIntent("Plot Alibaba revenue over the last 3 years")
        assertNotNull(intent)
        assertEquals("BABA", intent!!.companies.single().ticker)
        assertEquals("CNY", intent.companies.single().currency)
    }

    @Test
    fun rendersIndianRevenueTableInCroreFromCrawledSource() {
        val answer = buildFinancialVisualizationAnswer(
            prompt = "show TCS revenue for the last 3 fiscal years",
            sources = listOf(
                FinancialSourceText(
                    title = "TCS Annual Report — Investor Relations",
                    url = "https://www.tcs.com/who-we-are/investor-relations/financial-statements",
                    snippet = """
                        Tata Consultancy Services (TCS) - Annual revenue (consolidated):
                        For FY 2022, revenue from operations was ₹1,91,754 crore.
                        For FY 2023, revenue from operations was ₹2,25,458 crore.
                        For FY 2024, revenue from operations was ₹2,40,893 crore.
                        Source: BSE annual report disclosure.
                    """.trimIndent()
                )
            )
        )

        assertNotNull(answer)
        assertTrue(answer!!.contains("Tata Consultancy Services Revenue"))
        assertTrue(answer.contains("| Fiscal year | Revenue (INR crore) |"))
        assertTrue(answer.contains("| 2024 | 240,893 |"))
    }

    @Test
    fun rendersChineseRevenueTableInCnyBillions() {
        val answer = buildFinancialVisualizationAnswer(
            prompt = "Plot Alibaba revenue over the last 3 fiscal years",
            sources = listOf(
                FinancialSourceText(
                    title = "Alibaba Group — Annual Results",
                    url = "https://www.alibabagroup.com/en-US/ir-financial-reports",
                    snippet = """
                        Alibaba Group (BABA) - Annual revenue (consolidated):
                        FY2022: ¥853.1 billion.
                        FY2023: ¥868.7 billion.
                        FY2024: ¥941.2 billion.
                        Source: HKEX disclosure.
                    """.trimIndent()
                )
            )
        )

        assertNotNull(answer)
        assertTrue(answer!!.contains("Alibaba Revenue"))
        assertTrue(answer.contains("| Fiscal year | Revenue (CNY billions) |"))
        assertTrue(answer.contains("| 2024 | 941.2 |"))
    }

    @Test
    fun recognizesOtherSupportedCompanies() {
        val nvidia = parseCompanyFinancialIntent("plot last three years revenue for NVIDIA")
        val microsoft = parseCompanyFinancialIntent("show last 4 years profit of msft")

        assertEquals("NVDA", nvidia!!.companies.single().ticker)
        assertEquals(FinancialMetric.REVENUE, nvidia.metric)
        assertEquals("MSFT", microsoft!!.companies.single().ticker)
        assertEquals(FinancialMetric.NET_INCOME, microsoft.metric)
        assertEquals(4, microsoft.years)
    }

    private val amdCompanyFactsFixture = """
        {
          "facts": {
            "us-gaap": {
              "RevenueFromContractWithCustomerExcludingAssessedTax": {
                "units": {
                  "USD": [
                    {"end":"2023-12-30","val":22680000000,"fy":2025,"fp":"FY","form":"10-K","filed":"2026-02-04"},
                    {"end":"2024-12-28","val":25785000000,"fy":2025,"fp":"FY","form":"10-K","filed":"2026-02-04"},
                    {"end":"2025-12-27","val":34639000000,"fy":2025,"fp":"FY","form":"10-K","filed":"2026-02-04"},
                    {"end":"2025-09-27","val":24385000000,"fy":2025,"fp":"Q3","form":"10-Q","filed":"2025-11-05"}
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    private val mixedRevenueTagsFixture = """
        {
          "facts": {
            "us-gaap": {
              "RevenueFromContractWithCustomerExcludingAssessedTax": {
                "units": {
                  "USD": [
                    {"end":"2023-09-30","val":383285000000,"fy":2023,"fp":"FY","form":"10-K","filed":"2023-11-03"},
                    {"end":"2024-09-28","val":391035000000,"fy":2024,"fp":"FY","form":"10-K","filed":"2024-11-01"},
                    {"end":"2025-09-27","val":416161000000,"fy":2025,"fp":"FY","form":"10-K","filed":"2025-10-31"}
                  ]
                }
              },
              "SalesRevenueNet": {
                "units": {
                  "USD": [
                    {"end":"2013-09-28","val":170910000000,"fy":2013,"fp":"FY","form":"10-K","filed":"2013-10-30"},
                    {"end":"2014-09-27","val":182795000000,"fy":2014,"fp":"FY","form":"10-K","filed":"2014-10-27"},
                    {"end":"2015-09-26","val":233715000000,"fy":2015,"fp":"FY","form":"10-K","filed":"2015-10-28"},
                    {"end":"2016-09-24","val":215639000000,"fy":2016,"fp":"FY","form":"10-K","filed":"2016-10-26"},
                    {"end":"2017-09-30","val":229234000000,"fy":2017,"fp":"FY","form":"10-K","filed":"2017-11-03"}
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()
}
