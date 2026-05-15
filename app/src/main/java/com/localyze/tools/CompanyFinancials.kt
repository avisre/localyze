package com.localyze.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

internal enum class FinancialMetric(
    val displayName: String,
    val xbrlTags: List<String>
) {
    REVENUE(
        displayName = "Revenue",
        xbrlTags = listOf(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues",
            "SalesRevenueNet",
            "SalesRevenueGoodsNet"
        )
    ),
    NET_INCOME(
        displayName = "Net income",
        xbrlTags = listOf("NetIncomeLoss", "ProfitLoss")
    ),
    TOTAL_ASSETS(
        displayName = "Total assets",
        xbrlTags = listOf("Assets")
    );

    /** Header for the rendered Markdown table; unit depends on company currency. */
    fun tableHeader(currency: String = "USD"): String {
        val unit = when (currency) {
            "INR" -> "INR crore"
            "CNY" -> "CNY billions"
            "EUR" -> "EUR billions"
            "GBP" -> "GBP billions"
            "CAD" -> "CAD billions"
            "CHF" -> "CHF billions"
            "MXN" -> "MXN billions"
            "JPY" -> "JPY billions"
            else -> "USD billions"
        }
        return "$displayName ($unit)"
    }

    // Back-compat alias for callers that expect a property; defaults to USD.
    val tableHeader: String get() = tableHeader("USD")
}

internal data class CompanyProfile(
    val name: String,
    val ticker: String,
    val cik: String,
    val aliases: List<String>,
    val officialUrls: List<String>,
    /** ISO-style currency the company reports in. "USD" for SEC filers,
     *  "INR" for Indian listings (values usually in crore), "CNY" for
     *  Chinese listings (values usually in billions ¥). */
    val currency: String = "USD"
)

internal data class CompanyFinancialIntent(
    val companies: List<CompanyProfile>,
    val metric: FinancialMetric,
    val years: Int,
    val wantsVisualization: Boolean
)

internal data class AnnualFinancialPoint(
    val fiscalYear: Int,
    /** Value in the smallest unit of [currency] (e.g. USD: dollars, INR: rupees,
     *  CNY: yuan). The field is named `valueUsd` for backwards compatibility
     *  with US-only callers but its actual currency is [currency]. */
    val valueUsd: Long,
    val filedAt: String = "",
    val periodEnd: String = "",
    val tag: String = "",
    val currency: String = "USD"
)

internal data class FinancialSourceText(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Hardcoded annual revenue / net income figures for non-US companies (Indian
 * BSE/NSE listings, Chinese HKEX/SSE listings) where SEC XBRL is not available
 * and live web-crawl extraction is unreliable. These come from each company's
 * published annual reports / investor-relations disclosures.
 *
 * Values are in the company's native currency, smallest unit (rupees for INR,
 * yuan for CNY). The deterministic-RAG path uses these to build the
 * visualization table.
 */
internal val nativeFinancialFacts: Map<String, Map<FinancialMetric, Map<Int, Long>>> = mapOf(
    // ── India (values in rupees) ─────────────────────────────────────────
    "TCS" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 1_91_754L * 10_000_000L,
            2023 to 2_25_458L * 10_000_000L,
            2024 to 2_40_893L * 10_000_000L
        ),
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 38_327L * 10_000_000L,
            2023 to 42_303L * 10_000_000L,
            2024 to 45_908L * 10_000_000L
        )
    ),
    "RELIANCE" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 7_92_756L * 10_000_000L,
            2023 to 9_74_864L * 10_000_000L,
            2024 to 10_00_122L * 10_000_000L
        ),
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 60_705L * 10_000_000L,
            2023 to 73_670L * 10_000_000L,
            2024 to 79_020L * 10_000_000L
        )
    ),
    "INFY" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 1_21_641L * 10_000_000L,
            2023 to 1_46_767L * 10_000_000L,
            2024 to 1_53_670L * 10_000_000L
        ),
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 22_110L * 10_000_000L,
            2023 to 24_095L * 10_000_000L,
            2024 to 26_233L * 10_000_000L
        )
    ),
    "HDFCBANK" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 1_67_695L * 10_000_000L,
            2023 to 2_04_666L * 10_000_000L,
            2024 to 4_07_995L * 10_000_000L
        )
    ),
    "WIPRO" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 79_093L * 10_000_000L,
            2023 to 90_487L * 10_000_000L,
            2024 to 89_760L * 10_000_000L
        )
    ),
    "ICICIBANK" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 1_57_536L * 10_000_000L,
            2023 to 1_86_179L * 10_000_000L,
            2024 to 2_36_037L * 10_000_000L
        )
    ),
    "BHARTIARTL" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 1_16_547L * 10_000_000L,
            2023 to 1_39_145L * 10_000_000L,
            2024 to 1_49_982L * 10_000_000L
        )
    ),
    // ── China (values in yuan; "568.7B" → 568_700_000_000) ────────────────
    "BABA" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 853_100_000_000L,
            2023 to 868_687_000_000L,
            2024 to 941_168_000_000L
        )
    ),
    "TCEHY" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 554_552_000_000L,
            2023 to 609_015_000_000L,
            2024 to 660_257_000_000L
        ),
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 188_243_000_000L,
            2023 to 115_216_000_000L,
            2024 to 194_073_000_000L
        )
    ),
    "BYDDY" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 424_061_000_000L,
            2023 to 602_315_000_000L,
            2024 to 777_102_000_000L
        )
    ),
    "JD" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 1_046_236_000_000L,
            2023 to 1_084_662_000_000L,
            2024 to 1_158_819_000_000L
        )
    ),
    "PDD" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 130_558_000_000L,
            2023 to 247_639_000_000L,
            2024 to 393_836_000_000L
        )
    ),
    "VFS" to mapOf(
        FinancialMetric.REVENUE to mapOf(
            2022 to 633_000_000L,
            2023 to 1_205_000_000L,
            2024 to 1_794_000_000L
        ),
        FinancialMetric.NET_INCOME to mapOf(
            2022 to -2_104_000_000L,
            2023 to -2_396_000_000L,
            2024 to -3_181_000_000L
        )
    ),
    // ── UK ──────────────────────────────────────────────────────────────
    "SHEL" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 381_314_000_000L, 2023 to 323_183_000_000L, 2024 to 284_322_000_000L)),
    "AZN"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 44_351_000_000L, 2023 to 45_811_000_000L, 2024 to 54_073_000_000L)),
    "BP"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 241_393_000_000L, 2023 to 213_032_000_000L, 2024 to 194_038_000_000L)),
    "HSBC" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 51_727_000_000L, 2023 to 66_058_000_000L, 2024 to 86_708_000_000L)),
    "UL"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 60_073_000_000L, 2023 to 59_604_000_000L, 2024 to 60_761_000_000L)),
    "DEO"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 15_452_000_000L, 2023 to 17_113_000_000L, 2024 to 20_269_000_000L)),
    "GSK"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 29_324_000_000L, 2023 to 30_328_000_000L, 2024 to 31_376_000_000L)),
    "TSCO.L" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 61_344_000_000L, 2023 to 65_762_000_000L, 2024 to 68_187_000_000L)),
    // ── Germany ─────────────────────────────────────────────────────────
    "SAP"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 30_870_000_000L, 2023 to 31_207_000_000L, 2024 to 34_176_000_000L)),
    "SIE"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 71_977_000_000L, 2023 to 77_769_000_000L, 2024 to 75_897_000_000L)),
    "VOW3" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 279_232_000_000L, 2023 to 322_259_000_000L, 2024 to 324_658_000_000L)),
    "MBG"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 150_017_000_000L, 2023 to 153_218_000_000L, 2024 to 145_595_000_000L)),
    "BMW"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 142_610_000_000L, 2023 to 155_498_000_000L, 2024 to 142_380_000_000L)),
    // ── France ──────────────────────────────────────────────────────────
    "MC"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 79_184_000_000L, 2023 to 86_153_000_000L, 2024 to 84_683_000_000L)),
    "TTE"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 263_310_000_000L, 2023 to 218_945_000_000L, 2024 to 195_613_000_000L)),
    "SAN"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 42_997_000_000L, 2023 to 43_070_000_000L, 2024 to 41_080_000_000L)),
    "RMS"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 11_602_000_000L, 2023 to 13_427_000_000L, 2024 to 15_169_000_000L)),
    // ── Netherlands ─────────────────────────────────────────────────────
    "ASML" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 21_173_000_000L, 2023 to 27_558_000_000L, 2024 to 28_263_000_000L)),
    "HEIA" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 28_719_000_000L, 2023 to 30_307_000_000L, 2024 to 30_357_000_000L)),
    // ── Switzerland ─────────────────────────────────────────────────────
    "NESN" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 94_425_000_000L, 2023 to 92_995_000_000L, 2024 to 91_354_000_000L)),
    "NVS"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 50_545_000_000L, 2023 to 45_440_000_000L, 2024 to 50_320_000_000L)),
    "ROG"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 63_281_000_000L, 2023 to 58_722_000_000L, 2024 to 60_478_000_000L)),
    // ── Italy / Spain ───────────────────────────────────────────────────
    "STLA" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 179_592_000_000L, 2023 to 189_544_000_000L, 2024 to 156_887_000_000L)),
    "ITX"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 32_569_000_000L, 2023 to 35_947_000_000L, 2024 to 38_632_000_000L)),
    // ── Canada (CAD billions or USD for Shopify) ─────────────────────────
    "SHOP" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 5_599_000_000L, 2023 to 7_060_000_000L, 2024 to 8_881_000_000L)),
    "RY"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 48_991_000_000L, 2023 to 56_138_000_000L, 2024 to 65_010_000_000L)),
    "TD"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 49_032_000_000L, 2023 to 51_829_000_000L, 2024 to 56_750_000_000L)),
    "ENB"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 53_310_000_000L, 2023 to 43_654_000_000L, 2024 to 49_577_000_000L)),
    "SU"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 57_989_000_000L, 2023 to 51_138_000_000L, 2024 to 53_198_000_000L)),
    "BCE"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 24_174_000_000L, 2023 to 24_672_000_000L, 2024 to 24_438_000_000L)),
    // ── Mexico (values in MXN) ──────────────────────────────────────────
    "AMX"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 859_127_000_000L, 2023 to 813_660_000_000L, 2024 to 798_640_000_000L)),
    "FMX"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 681_293_000_000L, 2023 to 750_273_000_000L, 2024 to 802_410_000_000L)),
    "WALMEX" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 815_101_000_000L, 2023 to 880_080_000_000L, 2024 to 940_245_000_000L)),
    "CX"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 15_576_000_000L, 2023 to 17_388_000_000L, 2024 to 16_270_000_000L)),
    "BIMBOA" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 398_148_000_000L, 2023 to 410_531_000_000L, 2024 to 430_295_000_000L)),
    // ── New UK (added Aug 2026 — 10-per-country expansion) ──────────────
    "BARC" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 25_001_000_000L, 2023 to 25_378_000_000L, 2024 to 26_793_000_000L)),
    "RIO"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 55_554_000_000L, 2023 to 54_041_000_000L, 2024 to 53_658_000_000L)),
    // ── New Germany ─────────────────────────────────────────────────────
    "ALV"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 152_676_000_000L, 2023 to 161_700_000_000L, 2024 to 179_790_000_000L)),
    "DBK"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 27_210_000_000L, 2023 to 28_866_000_000L, 2024 to 30_134_000_000L)),
    "BAYN" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 50_739_000_000L, 2023 to 47_637_000_000L, 2024 to 46_606_000_000L)),
    "BAS"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 87_327_000_000L, 2023 to 68_902_000_000L, 2024 to 65_260_000_000L)),
    "ADS"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 22_511_000_000L, 2023 to 21_427_000_000L, 2024 to 23_683_000_000L)),
    // ── New France ──────────────────────────────────────────────────────
    "AIR"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 58_763_000_000L, 2023 to 65_446_000_000L, 2024 to 69_232_000_000L)),
    "CS"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 101_980_000_000L, 2023 to 102_724_000_000L, 2024 to 110_354_000_000L)),
    "BNP"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 50_419_000_000L, 2023 to 45_874_000_000L, 2024 to 48_831_000_000L)),
    "OR"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 38_261_000_000L, 2023 to 41_183_000_000L, 2024 to 43_482_000_000L)),
    "CA"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 81_368_000_000L, 2023 to 83_270_000_000L, 2024 to 81_192_000_000L)),
    "SU.PA" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 34_176_000_000L, 2023 to 35_902_000_000L, 2024 to 38_152_000_000L)),
    // ── New Canada ──────────────────────────────────────────────────────
    "CNR"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 17_106_000_000L, 2023 to 16_835_000_000L, 2024 to 17_046_000_000L)),
    "BMO"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 33_704_000_000L, 2023 to 31_211_000_000L, 2024 to 32_874_000_000L)),
    "CM"   to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 21_829_000_000L, 2023 to 23_410_000_000L, 2024 to 25_606_000_000L)),
    "MFC"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 24_644_000_000L, 2023 to 24_854_000_000L, 2024 to 26_315_000_000L)),
    // ── New Mexico ──────────────────────────────────────────────────────
    "GFNORTEO" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 142_710_000_000L, 2023 to 165_810_000_000L, 2024 to 179_460_000_000L)),
    "PENOLES" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 144_300_000_000L, 2023 to 142_700_000_000L, 2024 to 156_310_000_000L)),
    "ALPEKA"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 175_290_000_000L, 2023 to 161_320_000_000L, 2024 to 156_870_000_000L)),
    "LIVEPOLC" to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 156_590_000_000L, 2023 to 173_320_000_000L, 2024 to 184_640_000_000L)),
    "KOFL"  to mapOf(FinancialMetric.REVENUE to mapOf(
        2022 to 226_740_000_000L, 2023 to 245_080_000_000L, 2024 to 264_770_000_000L))
)

// Net income and total-assets data — sample so balance-sheet and profit
// queries return deterministic answers for showcase companies across regions.
internal val nativeFinancialFactsExtras: Map<String, Map<FinancialMetric, Map<Int, Long>>> = mapOf(
    // US profits & assets
    "AAPL" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2023 to 96_995_000_000L, 2024 to 93_736_000_000L, 2025 to 99_803_000_000L),
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2023 to 352_583_000_000L, 2024 to 364_980_000_000L, 2025 to 365_725_000_000L)
    ),
    "MSFT" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2023 to 72_361_000_000L, 2024 to 88_136_000_000L, 2025 to 96_641_000_000L),
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2023 to 411_976_000_000L, 2024 to 512_163_000_000L, 2025 to 596_900_000_000L)
    ),
    "AMZN" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to -2_722_000_000L, 2023 to 30_425_000_000L, 2024 to 59_248_000_000L),
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2022 to 462_675_000_000L, 2023 to 527_854_000_000L, 2024 to 624_894_000_000L)
    ),
    // India banks/IT
    "HDFCBANK" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 38_053L * 10_000_000L, 2023 to 45_997L * 10_000_000L, 2024 to 64_062L * 10_000_000L),
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2022 to 21_22_934L * 10_000_000L, 2023 to 24_66_081L * 10_000_000L, 2024 to 36_17_623L * 10_000_000L)
    ),
    "ICICIBANK" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 23_339L * 10_000_000L, 2023 to 31_896L * 10_000_000L, 2024 to 40_888L * 10_000_000L),
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2022 to 14_11_298L * 10_000_000L, 2023 to 15_84_207L * 10_000_000L, 2024 to 17_57_213L * 10_000_000L)
    ),
    // China
    "BABA" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 47_079_000_000L, 2023 to 72_783_000_000L, 2024 to 79_741_000_000L)
    ),
    "IDCBY" to mapOf(
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2022 to 39_610_000_000_000L, 2023 to 44_697_000_000_000L, 2024 to 48_822_000_000_000L)
    ),
    // UK / EU banks & big-cap profits
    "HSBC" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 15_115_000_000L, 2023 to 22_432_000_000L, 2024 to 22_867_000_000L),
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2022 to 2_966_000_000_000L, 2023 to 3_038_677_000_000L, 2024 to 2_956_956_000_000L)
    ),
    "SHEL" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 42_309_000_000L, 2023 to 19_359_000_000L, 2024 to 16_087_000_000L)
    ),
    "SAP" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 5_366_000_000L, 2023 to 1_710_000_000L, 2024 to 3_098_000_000L)
    ),
    // Canada
    "RY" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 15_813_000_000L, 2023 to 14_865_000_000L, 2024 to 16_240_000_000L),
        FinancialMetric.TOTAL_ASSETS to mapOf(
            2022 to 1_917_267_000_000L, 2023 to 2_004_858_000_000L, 2024 to 2_152_330_000_000L)
    ),
    // Mexico
    "AMX" to mapOf(
        FinancialMetric.NET_INCOME to mapOf(
            2022 to 87_435_000_000L, 2023 to 53_770_000_000L, 2024 to 38_206_000_000L)
    )
)

internal fun lookupNativeFinancialPoints(
    company: CompanyProfile,
    metric: FinancialMetric,
    years: Int
): List<AnnualFinancialPoint> {
    // Look in both fact tables: the main per-company-revenue map and the
    // extras map (net income, total assets) for showcase entries.
    val byYear = nativeFinancialFacts[company.ticker]?.get(metric)
        ?: nativeFinancialFactsExtras[company.ticker]?.get(metric)
        ?: return emptyList()
    return byYear.entries
        .sortedBy { it.key }
        .takeLast(years.coerceIn(2, 10))
        .map { (year, value) ->
            AnnualFinancialPoint(
                fiscalYear = year,
                valueUsd = value,
                currency = company.currency
            )
        }
}

internal val knownCompanyProfiles = listOf(
    CompanyProfile(
        name = "Advanced Micro Devices",
        ticker = "AMD",
        cik = "0000002488",
        aliases = listOf("amd", "advanced micro devices", "advanced micro devices inc"),
        officialUrls = listOf(
            "https://ir.amd.com/news-events/press-releases/detail/1276/amd-reports-fourth-quarter-and-full-year-2025-financial-results",
            "https://ir.amd.com/financial-information/sec-filings",
            "https://www.amd.com/en/newsroom/press-releases"
        )
    ),
    CompanyProfile(
        name = "Apple",
        ticker = "AAPL",
        cik = "0000320193",
        aliases = listOf("apple", "apple inc", "aapl"),
        officialUrls = listOf(
            "https://investor.apple.com/sec-filings/default.aspx",
            "https://www.apple.com/newsroom/"
        )
    ),
    CompanyProfile(
        name = "Microsoft",
        ticker = "MSFT",
        cik = "0000789019",
        aliases = listOf("microsoft", "microsoft corp", "msft"),
        officialUrls = listOf(
            "https://www.microsoft.com/en-us/Investor/sec-filings.aspx",
            "https://www.microsoft.com/en-us/investor/earnings"
        )
    ),
    CompanyProfile(
        name = "Alphabet",
        ticker = "GOOGL",
        cik = "0001652044",
        aliases = listOf("alphabet", "google", "googl", "goog"),
        officialUrls = listOf("https://abc.xyz/investor/")
    ),
    CompanyProfile(
        name = "Amazon",
        ticker = "AMZN",
        cik = "0001018724",
        aliases = listOf("amazon", "amazon.com", "amzn"),
        officialUrls = listOf("https://ir.aboutamazon.com/annual-reports-proxies-and-shareholder-letters/default.aspx")
    ),
    CompanyProfile(
        name = "Tesla",
        ticker = "TSLA",
        cik = "0001318605",
        aliases = listOf("tesla", "tesla inc", "tsla"),
        officialUrls = listOf("https://ir.tesla.com/")
    ),
    CompanyProfile(
        name = "Meta",
        ticker = "META",
        cik = "0001326801",
        aliases = listOf("meta", "facebook", "meta platforms"),
        officialUrls = listOf("https://investor.fb.com/financials/default.aspx")
    ),
    CompanyProfile(
        name = "NVIDIA",
        ticker = "NVDA",
        cik = "0001045810",
        aliases = listOf("nvidia", "nvda"),
        officialUrls = listOf("https://investor.nvidia.com/financial-info/financial-reports/default.aspx")
    ),
    CompanyProfile(
        name = "Intel",
        ticker = "INTC",
        cik = "0000050863",
        aliases = listOf("intel", "intel corp", "intc"),
        officialUrls = listOf("https://www.intc.com/filings-reports/annual-reports-proxies")
    ),
    CompanyProfile(
        name = "Qualcomm",
        ticker = "QCOM",
        cik = "0000804328",
        aliases = listOf("qualcomm", "qcom"),
        officialUrls = listOf("https://investor.qualcomm.com/financial-information/annual-reports")
    ),
    CompanyProfile(
        name = "Oracle",
        ticker = "ORCL",
        cik = "0001341439",
        aliases = listOf("oracle", "orcl"),
        officialUrls = listOf("https://investor.oracle.com/financials/default.aspx")
    ),
    CompanyProfile(
        name = "Salesforce",
        ticker = "CRM",
        cik = "0001108524",
        aliases = listOf("salesforce", "crm"),
        officialUrls = listOf("https://investor.salesforce.com/financials/default.aspx")
    ),
    CompanyProfile(
        name = "Adobe",
        ticker = "ADBE",
        cik = "0000796343",
        aliases = listOf("adobe", "adbe"),
        officialUrls = listOf("https://www.adobe.com/investor-relations/financial-documents.html")
    ),
    CompanyProfile(
        name = "Netflix",
        ticker = "NFLX",
        cik = "0001065280",
        aliases = listOf("netflix", "nflx"),
        officialUrls = listOf("https://ir.netflix.net/financials/annual-reports-and-proxies/default.aspx")
    ),
    CompanyProfile(
        name = "IBM",
        ticker = "IBM",
        cik = "0000051143",
        aliases = listOf("ibm", "international business machines"),
        officialUrls = listOf("https://www.ibm.com/investor/financials/financial-reporting")
    ),
    CompanyProfile(
        name = "Broadcom",
        ticker = "AVGO",
        cik = "0001730168",
        aliases = listOf("broadcom", "avgo"),
        officialUrls = listOf("https://investors.broadcom.com/financial-information/annual-reports")
    ),
    CompanyProfile(
        name = "Dell",
        ticker = "DELL",
        cik = "0001571996",
        aliases = listOf("dell", "dell technologies"),
        officialUrls = listOf("https://investors.delltechnologies.com/financial-information/annual-reports")
    ),
    // ── Indian listings (BSE/NSE). SEC CIK left blank — they don't file
    //    with EDGAR. Values usually reported in crore (1 cr = 1e7). Source:
    //    company investor-relations pages + BSE annual reports.
    CompanyProfile(
        name = "Tata Consultancy Services",
        ticker = "TCS",
        cik = "",
        aliases = listOf("tcs", "tata consultancy", "tata consultancy services"),
        officialUrls = listOf(
            "https://www.tcs.com/who-we-are/investor-relations/financial-statements",
            "https://www.bseindia.com/stock-share-price/tata-consultancy-services-ltd/tcs/532540/"
        ),
        currency = "INR"
    ),
    CompanyProfile(
        name = "Reliance Industries",
        ticker = "RELIANCE",
        cik = "",
        aliases = listOf("reliance", "reliance industries", "ril", "reliance ind"),
        officialUrls = listOf(
            "https://www.ril.com/InvestorRelations/FinancialReporting.aspx",
            "https://www.bseindia.com/stock-share-price/reliance-industries-ltd/reliance/500325/"
        ),
        currency = "INR"
    ),
    CompanyProfile(
        name = "Infosys",
        ticker = "INFY",
        cik = "",
        aliases = listOf("infosys", "infy", "infosys ltd", "infosys limited"),
        officialUrls = listOf(
            "https://www.infosys.com/investors.html",
            "https://www.bseindia.com/stock-share-price/infosys-ltd/infy/500209/"
        ),
        currency = "INR"
    ),
    CompanyProfile(
        name = "HDFC Bank",
        ticker = "HDFCBANK",
        cik = "",
        aliases = listOf("hdfc", "hdfc bank", "hdfc bank ltd"),
        officialUrls = listOf(
            "https://www.hdfcbank.com/personal/about-us/investor-relations",
            "https://www.bseindia.com/stock-share-price/hdfc-bank-ltd/hdfcbank/500180/"
        ),
        currency = "INR"
    ),
    CompanyProfile(
        name = "Wipro",
        ticker = "WIPRO",
        cik = "",
        aliases = listOf("wipro", "wipro ltd", "wipro limited"),
        officialUrls = listOf(
            "https://www.wipro.com/investors/",
            "https://www.bseindia.com/stock-share-price/wipro-ltd/wipro/507685/"
        ),
        currency = "INR"
    ),
    CompanyProfile(
        name = "ICICI Bank",
        ticker = "ICICIBANK",
        cik = "",
        aliases = listOf("icici", "icici bank"),
        officialUrls = listOf("https://www.icicibank.com/about-us/investor-relations"),
        currency = "INR"
    ),
    CompanyProfile(
        name = "Tata Steel",
        ticker = "TATASTEEL",
        cik = "",
        aliases = listOf("tata steel"),
        officialUrls = listOf("https://www.tatasteel.com/investors/"),
        currency = "INR"
    ),
    CompanyProfile(
        name = "Adani Enterprises",
        ticker = "ADANIENT",
        cik = "",
        aliases = listOf("adani", "adani enterprises", "adani group"),
        officialUrls = listOf("https://www.adanienterprises.com/investors"),
        currency = "INR"
    ),
    CompanyProfile(
        name = "Bharti Airtel",
        ticker = "BHARTIARTL",
        cik = "",
        aliases = listOf("airtel", "bharti airtel"),
        officialUrls = listOf("https://www.airtel.in/about-bharti/equity/investors"),
        currency = "INR"
    ),
    CompanyProfile(
        name = "Hindustan Unilever",
        ticker = "HUL",
        cik = "",
        aliases = listOf("hul", "hindustan unilever", "hindustan lever"),
        officialUrls = listOf("https://www.hul.co.in/investor-relations/"),
        currency = "INR"
    ),
    // ── Chinese listings (HKEX/SSE/NYSE-ADR). Values typically reported in
    //    billions ¥ (CNY/RMB). Alibaba & JD have SEC ADR filings too.
    CompanyProfile(
        name = "Alibaba",
        ticker = "BABA",
        cik = "0001577552",
        aliases = listOf("alibaba", "alibaba group", "baba"),
        officialUrls = listOf("https://www.alibabagroup.com/en-US/ir-financial-reports"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "Tencent",
        ticker = "TCEHY",
        cik = "",
        aliases = listOf("tencent", "tencent holdings", "tcehy", "0700"),
        officialUrls = listOf("https://www.tencent.com/en-us/investors.html"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "BYD",
        ticker = "BYDDY",
        cik = "",
        aliases = listOf("byd", "byd auto", "byd company", "byddy"),
        officialUrls = listOf("https://www.bydglobal.com/en/InvestorRelations.html"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "JD.com",
        ticker = "JD",
        cik = "0001549802",
        aliases = listOf("jd", "jd.com", "jd dot com", "jingdong"),
        officialUrls = listOf("https://ir.jd.com/"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "Baidu",
        ticker = "BIDU",
        cik = "0001329099",
        aliases = listOf("baidu"),
        officialUrls = listOf("https://ir.baidu.com/"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "Pinduoduo",
        ticker = "PDD",
        cik = "0001737806",
        aliases = listOf("pdd", "pinduoduo", "temu"),
        officialUrls = listOf("https://investor.pddholdings.com/"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "NIO",
        ticker = "NIO",
        cik = "0001736541",
        aliases = listOf("nio", "nio inc"),
        officialUrls = listOf("https://ir.nio.com/"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "Meituan",
        ticker = "MPNGY",
        cik = "",
        aliases = listOf("meituan", "mpngy"),
        officialUrls = listOf("https://www.meituan.com/en-US/about-us"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "ICBC",
        ticker = "IDCBY",
        cik = "",
        aliases = listOf("icbc", "industrial and commercial bank of china"),
        officialUrls = listOf("http://www.icbc.com.cn/icbc/EN/investor%20relations/"),
        currency = "CNY"
    ),
    CompanyProfile(
        name = "PetroChina",
        ticker = "PTR",
        cik = "",
        aliases = listOf("petrochina", "ptr"),
        officialUrls = listOf("http://www.petrochina.com.cn/ptr/Investor/list_common.shtml"),
        currency = "CNY"
    ),
    // ── Vietnam (Nasdaq-listed ADR, reports in USD). ────────────────────
    CompanyProfile(
        name = "VinFast",
        ticker = "VFS",
        cik = "",
        aliases = listOf("vinfast", "vfs", "vinfast auto", "vinfast auto ltd"),
        officialUrls = listOf("https://ir.vinfastauto.com/financial-information/quarterly-results"),
        currency = "USD"
    ),
    // ── UK (LSE/USD-ADR; mostly USD reporters except FTSE-100 retailers). ──
    CompanyProfile(name = "Shell", ticker = "SHEL", cik = "",
        aliases = listOf("shell", "shel", "royal dutch shell"),
        officialUrls = listOf("https://www.shell.com/investors.html"), currency = "USD"),
    CompanyProfile(name = "AstraZeneca", ticker = "AZN", cik = "",
        aliases = listOf("astrazeneca", "azn", "astra zeneca"),
        officialUrls = listOf("https://www.astrazeneca.com/investor-relations.html"), currency = "USD"),
    CompanyProfile(name = "BP", ticker = "BP", cik = "",
        aliases = listOf("bp", "british petroleum"),
        officialUrls = listOf("https://www.bp.com/en/global/corporate/investors.html"), currency = "USD"),
    CompanyProfile(name = "HSBC", ticker = "HSBC", cik = "",
        aliases = listOf("hsbc", "hsbc holdings"),
        officialUrls = listOf("https://www.hsbc.com/investors"), currency = "USD"),
    CompanyProfile(name = "Unilever", ticker = "UL", cik = "",
        aliases = listOf("unilever", "ulvr", "ul"),
        officialUrls = listOf("https://www.unilever.com/investors/"), currency = "EUR"),
    CompanyProfile(name = "Diageo", ticker = "DEO", cik = "",
        aliases = listOf("diageo", "deo"),
        officialUrls = listOf("https://www.diageo.com/en/investors"), currency = "GBP"),
    CompanyProfile(name = "GSK", ticker = "GSK", cik = "",
        aliases = listOf("gsk", "glaxosmithkline", "glaxo smithkline"),
        officialUrls = listOf("https://www.gsk.com/en-gb/investors/"), currency = "GBP"),
    CompanyProfile(name = "Tesco", ticker = "TSCO.L", cik = "",
        aliases = listOf("tesco", "tesco plc"),
        officialUrls = listOf("https://www.tescoplc.com/investors/"), currency = "GBP"),
    CompanyProfile(name = "Barclays", ticker = "BARC", cik = "",
        aliases = listOf("barclays", "barc"),
        officialUrls = listOf("https://home.barclays/investor-relations/"), currency = "GBP"),
    CompanyProfile(name = "Rio Tinto", ticker = "RIO", cik = "",
        aliases = listOf("rio tinto", "rio"),
        officialUrls = listOf("https://www.riotinto.com/en/invest"), currency = "USD"),
    // ── Germany ──────────────────────────────────────────────────────
    CompanyProfile(name = "SAP", ticker = "SAP", cik = "",
        aliases = listOf("sap", "sap se"),
        officialUrls = listOf("https://www.sap.com/about/investors.html"), currency = "EUR"),
    CompanyProfile(name = "Siemens", ticker = "SIE", cik = "",
        aliases = listOf("siemens", "sie", "siemens ag"),
        officialUrls = listOf("https://www.siemens.com/global/en/company/investor-relations.html"), currency = "EUR"),
    CompanyProfile(name = "Volkswagen", ticker = "VOW3", cik = "",
        aliases = listOf("volkswagen", "vw", "vow3", "vw group"),
        officialUrls = listOf("https://www.volkswagen-group.com/en/investors-15799"), currency = "EUR"),
    CompanyProfile(name = "Mercedes-Benz", ticker = "MBG", cik = "",
        aliases = listOf("mercedes", "mercedes-benz", "mbg", "daimler"),
        officialUrls = listOf("https://group.mercedes-benz.com/investors/"), currency = "EUR"),
    CompanyProfile(name = "BMW", ticker = "BMW", cik = "",
        aliases = listOf("bmw", "bmw group"),
        officialUrls = listOf("https://www.bmwgroup.com/en/investor-relations.html"), currency = "EUR"),
    CompanyProfile(name = "Allianz", ticker = "ALV", cik = "",
        aliases = listOf("allianz", "alv"),
        officialUrls = listOf("https://www.allianz.com/en/investor_relations.html"), currency = "EUR"),
    CompanyProfile(name = "Deutsche Bank", ticker = "DBK", cik = "",
        aliases = listOf("deutsche bank", "dbk"),
        officialUrls = listOf("https://investor-relations.db.com/"), currency = "EUR"),
    CompanyProfile(name = "Bayer", ticker = "BAYN", cik = "",
        aliases = listOf("bayer", "bayn"),
        officialUrls = listOf("https://www.bayer.com/en/investors"), currency = "EUR"),
    CompanyProfile(name = "BASF", ticker = "BAS", cik = "",
        aliases = listOf("basf", "bas"),
        officialUrls = listOf("https://www.basf.com/global/en/investors.html"), currency = "EUR"),
    CompanyProfile(name = "Adidas", ticker = "ADS", cik = "",
        aliases = listOf("adidas", "ads"),
        officialUrls = listOf("https://www.adidas-group.com/en/investors/"), currency = "EUR"),
    // ── France ───────────────────────────────────────────────────────
    CompanyProfile(name = "LVMH", ticker = "MC", cik = "",
        aliases = listOf("lvmh", "moet hennessy louis vuitton", "louis vuitton"),
        officialUrls = listOf("https://www.lvmh.com/investors/"), currency = "EUR"),
    CompanyProfile(name = "TotalEnergies", ticker = "TTE", cik = "",
        aliases = listOf("totalenergies", "total", "tte", "total energies"),
        officialUrls = listOf("https://totalenergies.com/investors"), currency = "USD"),
    CompanyProfile(name = "Sanofi", ticker = "SAN", cik = "",
        aliases = listOf("sanofi", "san"),
        officialUrls = listOf("https://www.sanofi.com/en/investors"), currency = "EUR"),
    CompanyProfile(name = "Hermes", ticker = "RMS", cik = "",
        aliases = listOf("hermes", "rms", "hermès"),
        officialUrls = listOf("https://finance.hermes.com/"), currency = "EUR"),
    CompanyProfile(name = "Airbus", ticker = "AIR", cik = "",
        aliases = listOf("airbus", "air"),
        officialUrls = listOf("https://www.airbus.com/en/investors"), currency = "EUR"),
    CompanyProfile(name = "AXA", ticker = "CS", cik = "",
        aliases = listOf("axa"),
        officialUrls = listOf("https://www.axa.com/en/investor"), currency = "EUR"),
    CompanyProfile(name = "BNP Paribas", ticker = "BNP", cik = "",
        aliases = listOf("bnp paribas", "bnp"),
        officialUrls = listOf("https://invest.bnpparibas/"), currency = "EUR"),
    CompanyProfile(name = "LOreal", ticker = "OR", cik = "",
        aliases = listOf("l'oreal", "loreal", "l'oréal"),
        officialUrls = listOf("https://www.loreal-finance.com/"), currency = "EUR"),
    CompanyProfile(name = "Carrefour", ticker = "CA", cik = "",
        aliases = listOf("carrefour"),
        officialUrls = listOf("https://www.carrefour.com/en/finance"), currency = "EUR"),
    CompanyProfile(name = "Schneider Electric", ticker = "SU.PA", cik = "",
        aliases = listOf("schneider electric", "schneider"),
        officialUrls = listOf("https://www.se.com/ww/en/about-us/investor-relations/"), currency = "EUR"),
    // ── Netherlands ──────────────────────────────────────────────────
    CompanyProfile(name = "ASML", ticker = "ASML", cik = "",
        aliases = listOf("asml", "asml holding"),
        officialUrls = listOf("https://www.asml.com/en/investors"), currency = "EUR"),
    CompanyProfile(name = "Heineken", ticker = "HEIA", cik = "",
        aliases = listOf("heineken", "heia"),
        officialUrls = listOf("https://www.theheinekencompany.com/investors"), currency = "EUR"),
    // ── Switzerland ──────────────────────────────────────────────────
    CompanyProfile(name = "Nestle", ticker = "NESN", cik = "",
        aliases = listOf("nestle", "nestlé", "nesn"),
        officialUrls = listOf("https://www.nestle.com/investors"), currency = "CHF"),
    CompanyProfile(name = "Novartis", ticker = "NVS", cik = "",
        aliases = listOf("novartis", "nvs"),
        officialUrls = listOf("https://www.novartis.com/investors"), currency = "USD"),
    CompanyProfile(name = "Roche", ticker = "ROG", cik = "",
        aliases = listOf("roche", "rog", "roche holding"),
        officialUrls = listOf("https://www.roche.com/investors"), currency = "CHF"),
    // ── Italy ────────────────────────────────────────────────────────
    CompanyProfile(name = "Stellantis", ticker = "STLA", cik = "",
        aliases = listOf("stellantis", "stla"),
        officialUrls = listOf("https://www.stellantis.com/en/investors"), currency = "EUR"),
    // ── Spain ────────────────────────────────────────────────────────
    CompanyProfile(name = "Inditex", ticker = "ITX", cik = "",
        aliases = listOf("inditex", "itx", "zara"),
        officialUrls = listOf("https://www.inditex.com/investors"), currency = "EUR"),
    // ── Canada ───────────────────────────────────────────────────────
    CompanyProfile(name = "Shopify", ticker = "SHOP", cik = "",
        aliases = listOf("shopify", "shop"),
        officialUrls = listOf("https://investors.shopify.com/financials/default.aspx"), currency = "USD"),
    CompanyProfile(name = "RBC", ticker = "RY", cik = "",
        aliases = listOf("rbc", "royal bank of canada", "ry"),
        officialUrls = listOf("https://www.rbc.com/investor-relations/"), currency = "CAD"),
    CompanyProfile(name = "TD Bank", ticker = "TD", cik = "",
        aliases = listOf("td bank", "toronto dominion", "td"),
        officialUrls = listOf("https://www.td.com/ca/en/about-td/for-investors/"), currency = "CAD"),
    CompanyProfile(name = "Enbridge", ticker = "ENB", cik = "",
        aliases = listOf("enbridge", "enb"),
        officialUrls = listOf("https://www.enbridge.com/investment-center"), currency = "CAD"),
    CompanyProfile(name = "Suncor", ticker = "SU", cik = "",
        aliases = listOf("suncor", "suncor energy", "su"),
        officialUrls = listOf("https://www.suncor.com/en-ca/investor-centre"), currency = "CAD"),
    CompanyProfile(name = "BCE", ticker = "BCE", cik = "",
        aliases = listOf("bce", "bell canada"),
        officialUrls = listOf("https://www.bce.ca/investors"), currency = "CAD"),
    CompanyProfile(name = "CNR", ticker = "CNR", cik = "",
        aliases = listOf("cnr", "canadian national", "canadian national railway"),
        officialUrls = listOf("https://www.cn.ca/en/investors/"), currency = "CAD"),
    CompanyProfile(name = "BMO", ticker = "BMO", cik = "",
        aliases = listOf("bmo", "bank of montreal"),
        officialUrls = listOf("https://www.bmo.com/main/about-bmo/investor-relations/home/"), currency = "CAD"),
    CompanyProfile(name = "CIBC", ticker = "CM", cik = "",
        aliases = listOf("cibc", "canadian imperial bank"),
        officialUrls = listOf("https://www.cibc.com/en/about-cibc/investor-relations.html"), currency = "CAD"),
    CompanyProfile(name = "Manulife", ticker = "MFC", cik = "",
        aliases = listOf("manulife", "mfc"),
        officialUrls = listOf("https://www.manulife.com/en/investors.html"), currency = "CAD"),
    // ── Mexico ───────────────────────────────────────────────────────
    CompanyProfile(name = "America Movil", ticker = "AMX", cik = "",
        aliases = listOf("america movil", "amx", "telcel", "américa móvil"),
        officialUrls = listOf("https://www.americamovil.com/investors"), currency = "MXN"),
    CompanyProfile(name = "FEMSA", ticker = "FMX", cik = "",
        aliases = listOf("femsa", "fmx", "fomento economico mexicano"),
        officialUrls = listOf("https://femsa.gcs-web.com/"), currency = "MXN"),
    CompanyProfile(name = "Walmex", ticker = "WALMEX", cik = "",
        aliases = listOf("walmex", "walmart de mexico"),
        officialUrls = listOf("https://www.walmex.mx/en/inversionistas.html"), currency = "MXN"),
    CompanyProfile(name = "Cemex", ticker = "CX", cik = "",
        aliases = listOf("cemex", "cx"),
        officialUrls = listOf("https://www.cemex.com/investors"), currency = "USD"),
    CompanyProfile(name = "Grupo Bimbo", ticker = "BIMBOA", cik = "",
        aliases = listOf("grupo bimbo", "bimbo", "bimboa"),
        officialUrls = listOf("https://www.grupobimbo.com/en/investors"), currency = "MXN"),
    CompanyProfile(name = "Banorte", ticker = "GFNORTEO", cik = "",
        aliases = listOf("banorte", "gfnorteo", "grupo financiero banorte"),
        officialUrls = listOf("https://investors.banorte.com/en"), currency = "MXN"),
    CompanyProfile(name = "Penoles", ticker = "PENOLES", cik = "",
        aliases = listOf("peñoles", "penoles", "industrias peñoles"),
        officialUrls = listOf("https://www.penoles.com.mx/Inversionistas.html"), currency = "MXN"),
    CompanyProfile(name = "Alpek", ticker = "ALPEKA", cik = "",
        aliases = listOf("alpek", "alpeka"),
        officialUrls = listOf("https://www.alpek.com/inversionistas"), currency = "MXN"),
    CompanyProfile(name = "Liverpool", ticker = "LIVEPOLC", cik = "",
        aliases = listOf("liverpool", "el puerto de liverpool", "livepolc"),
        officialUrls = listOf("https://www.elpuertodeliverpool.mx/"), currency = "MXN"),
    CompanyProfile(name = "Coca-Cola FEMSA", ticker = "KOFL", cik = "",
        aliases = listOf("kof", "coca-cola femsa", "coca cola femsa"),
        officialUrls = listOf("https://coca-colafemsa.com/en/investors/"), currency = "MXN")
)

internal fun parseCompanyFinancialIntent(prompt: String): CompanyFinancialIntent? {
    val metric = metricForPrompt(prompt) ?: return null
    val companies = findMentionedCompanies(prompt)
    if (companies.isEmpty()) return null

    val lower = prompt.lowercase()
    val wantsVisualization = Regex(
        "\\b(visu?ali[sz]e|visu?lai[sz]e|visual|chart|graph|plot|trend|line|bar|compare|comparison|show|display|table)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)

    val hasAnnualWindow = Regex(
        "\\b(last|past|previous|latest|recent)?\\s*(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten)\\s*[- ]?(years?|yrs?|fiscal|annual)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)

    if (!wantsVisualization && !hasAnnualWindow && !lower.contains("annual")) return null

    return CompanyFinancialIntent(
        companies = companies.take(4),
        metric = metric,
        years = requestedYearCount(prompt),
        wantsVisualization = wantsVisualization
    )
}

internal fun companyFinancialWebSearchQueryFor(prompt: String): String? {
    val intent = parseCompanyFinancialIntent(prompt) ?: return null
    val companyPart = intent.companies.joinToString(" ") { "${it.ticker} ${it.name}" }
    // Direct the crawler at the right disclosure system per region.
    val sourcesHint = when {
        intent.companies.any { it.currency == "INR" } ->
            "official investor relations annual report BSE NSE filings"
        intent.companies.any { it.currency == "CNY" } ->
            "official investor relations annual report HKEX SEC ADR filings"
        intent.companies.any { it.currency in setOf("EUR", "GBP", "CHF") } ->
            "official investor relations annual report LSE Euronext Xetra filings"
        intent.companies.any { it.currency == "CAD" } ->
            "official investor relations annual report TSX filings"
        intent.companies.any { it.currency == "MXN" } ->
            "official investor relations annual report BMV filings"
        else ->
            "official investor relations annual report SEC company facts"
    }
    return "$companyPart ${intent.metric.displayName} last ${intent.years} fiscal years $sourcesHint"
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal fun parseAnnualFinancialPointsFromCompanyFactsJson(
    companyFactsJson: String,
    metric: FinancialMetric,
    years: Int
): List<AnnualFinancialPoint> {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    return runCatching {
        val root = json.parseToJsonElement(companyFactsJson).jsonObject
        val usGaap = root["facts"]?.jsonObject?.get("us-gaap")?.jsonObject ?: return@runCatching emptyList()

        metric.xbrlTags
            .mapIndexedNotNull { index, tag ->
                val points = parseAnnualFactsForTag(usGaap[tag], tag)
                points.takeIf { it.isNotEmpty() }?.let {
                    AnnualFinancialCandidate(priorityIndex = index, points = it)
                }
            }
            .maxWithOrNull(
                compareBy<AnnualFinancialCandidate> { candidate ->
                    candidate.points.maxOf { it.fiscalYear }
                }
                    .thenBy { candidate -> candidate.points.size }
                    .thenBy { candidate -> -candidate.priorityIndex }
            )
            ?.points
            .orEmpty()
            .sortedBy { it.fiscalYear }
            .takeLast(years.coerceIn(2, 10))
    }.getOrDefault(emptyList())
}

internal fun buildFinancialVisualizationAnswer(
    prompt: String,
    sources: List<FinancialSourceText>
): String? {
    val intent = parseCompanyFinancialIntent(prompt) ?: return null
    val sections = mutableListOf<String>()

    intent.companies.forEach { company ->
        val relevantSources = sources.filter { it.mentionsCompany(company) }
        val points = extractAnnualFinancialPointsFromSources(company, intent.metric, sources)
            .sortedBy { it.fiscalYear }
            .takeLast(intent.years)
        if (points.size < 2) return@forEach

        val titleYears = if (points.size == 1) "Latest Fiscal Year" else "Last ${points.size} Fiscal Years"
        val title = "## ${company.name} ${intent.metric.displayName} ($titleYears)"
        val table = buildString {
            appendLine("| Fiscal year | ${intent.metric.tableHeader(company.currency)} |")
            appendLine("|---|---:|")
            points.forEach { point ->
                appendLine("| ${point.fiscalYear} | ${formatValueForTable(point.valueUsd, company.currency)} |")
            }
        }.trimEnd()
        val summary = buildFinancialSummarySentence(company, intent.metric, points)
        val hasOfficialCrawl = relevantSources.any { source ->
            source.snippet.contains("Crawled official website:", ignoreCase = true) ||
                company.officialUrls.any { officialUrl -> source.url.equals(officialUrl, ignoreCase = true) }
        }
        val hasSecFacts = relevantSources.any { source ->
            source.url.contains("sec.gov", ignoreCase = true) ||
                source.snippet.contains("SEC XBRL", ignoreCase = true) ||
                source.snippet.contains("SEC EDGAR", ignoreCase = true)
        }
        val basis = when {
            hasOfficialCrawl && hasSecFacts ->
                "Data grounding: I crawled the company's official investor/IR page for context, then used structured SEC XBRL annual facts for the numeric RAG table."
            hasOfficialCrawl ->
                "Data grounding: I crawled the company's official investor/IR page and extracted the annual values from the retrieved financial text."
            else ->
                "Data grounding: I used structured SEC XBRL annual facts returned by web search for the numeric RAG table; no official company website excerpt was available in this run."
        }

        sections += listOf(title, "", summary, "", table, "", basis).joinToString("\n")
    }

    if (sections.isEmpty()) return null
    val intro = if (intent.companies.size > 1) {
        "I found company-specific annual ${intent.metric.displayName.lowercase()} data for ${sections.size} companies."
    } else {
        "I found annual ${intent.metric.displayName.lowercase()} data for ${intent.companies.first().name}."
    }

    return listOf(intro, "", sections.joinToString("\n\n")).joinToString("\n")
}

internal fun extractAnnualFinancialPointsFromSources(
    company: CompanyProfile,
    metric: FinancialMetric,
    sources: List<FinancialSourceText>
): List<AnnualFinancialPoint> {
    val relevantText = sources
        .filter { source ->
            source.mentionsCompany(company)
        }
        .joinToString("\n") { "${it.title}\n${it.snippet}" }

    if (relevantText.isBlank()) return emptyList()

    val structured = parseStructuredFinancialLines(relevantText, company.currency)
    val officialSentences = parseOfficialFinancialSentences(relevantText, metric, company.currency)
    val indianForm = if (company.currency == "INR") parseIndianFinancialLines(relevantText, metric) else emptyList()

    return (structured + officialSentences + indianForm)
        // Net income (and similar) can legitimately be negative — losses.
        // Drop only zero-value rows.
        .filter { it.valueUsd != 0L }
        .map { it.copy(currency = company.currency) }
        .groupBy { it.fiscalYear }
        .mapValues { (_, values) ->
            values.maxWithOrNull(
                compareBy<AnnualFinancialPoint> { it.filedAt }
                    .thenBy { it.periodEnd }
                    .thenBy { it.valueUsd }
            )
        }
        .values
        .filterNotNull()
        .sortedBy { it.fiscalYear }
}

private fun metricForPrompt(prompt: String): FinancialMetric? {
    val lower = prompt.lowercase()
    return when {
        Regex("\\b(total\\s+assets|balance\\s+sheet|assets|book\\s+value)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
            FinancialMetric.TOTAL_ASSETS
        Regex("\\b(net\\s+income|profit|profits|earnings|net\\s+profit|pat)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
            FinancialMetric.NET_INCOME
        Regex("\\b(revenue|revenues|sales|net\\s+sales|turnover)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
            FinancialMetric.REVENUE
        else -> null
    }
}

private fun findMentionedCompanies(prompt: String): List<CompanyProfile> {
    val lower = prompt.lowercase()
    val normalized = lower.replace(Regex("[^a-z0-9]+"), " ").trim()
    val tickerTokens = Regex("\\b[A-Z]{1,5}\\b").findAll(prompt).map { it.value.uppercase() }.toSet()

    return knownCompanyProfiles.filter { profile ->
        profile.ticker in tickerTokens ||
            containsTerm(normalized, profile.ticker.lowercase()) ||
            profile.aliases.any { alias -> containsTerm(normalized, alias) }
    }
}

private fun containsTerm(text: String, term: String): Boolean {
    val normalizedTerm = term.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    if (normalizedTerm.isBlank()) return false
    return Regex("(^|\\s)${Regex.escape(normalizedTerm)}(\\s|$)").containsMatchIn(text)
}

private fun requestedYearCount(prompt: String): Int {
    val numberWords = mapOf(
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10
    )
    val match = Regex(
        "\\b(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten)\\s*[- ]?(years?|yrs?|fiscal|annual)\\b",
        RegexOption.IGNORE_CASE
    ).find(prompt)
    val raw = match?.groupValues?.getOrNull(1)?.lowercase()
    return (raw?.toIntOrNull() ?: numberWords[raw]).orDefault(3).coerceIn(2, 10)
}

private fun Int?.orDefault(default: Int): Int = this ?: default

private data class AnnualFinancialCandidate(
    val priorityIndex: Int,
    val points: List<AnnualFinancialPoint>
)

private fun parseAnnualFactsForTag(element: JsonElement?, tag: String): List<AnnualFinancialPoint> {
    val rows = runCatching {
        element
            ?.jsonObject
            ?.get("units")
            ?.jsonObject
            ?.get("USD")
            ?.jsonArray
    }.getOrNull() ?: return emptyList()

    return rows.mapNotNull { row ->
        val obj = row.jsonObject
        val form = obj.stringValue("form")
        val fp = obj.stringValue("fp")
        val end = obj.stringValue("end")
        val fiscalYear = end.take(4).toIntOrNull() ?: obj.stringValue("fy").toIntOrNull()
        val value = obj["val"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (fiscalYear == null || value == null) return@mapNotNull null
        if (!fp.equals("FY", ignoreCase = true)) return@mapNotNull null
        if (!form.contains("10-K", ignoreCase = true)) return@mapNotNull null
        if (value <= 0L) return@mapNotNull null
        AnnualFinancialPoint(
            fiscalYear = fiscalYear,
            valueUsd = value,
            filedAt = obj.stringValue("filed"),
            periodEnd = end,
            tag = tag
        )
    }
        .groupBy { it.fiscalYear }
        .mapValues { (_, values) ->
            values.maxWithOrNull(
                compareBy<AnnualFinancialPoint> { it.filedAt }
                    .thenBy { it.periodEnd }
                    .thenBy { it.valueUsd }
            )
        }
        .values
        .filterNotNull()
        .sortedBy { it.fiscalYear }
}

private fun JsonObject.stringValue(key: String): String {
    return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

private fun FinancialSourceText.mentionsCompany(company: CompanyProfile): Boolean {
    val haystack = "${title}\n${url}\n${snippet}".lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
    return containsTerm(haystack, company.ticker.lowercase()) ||
        company.aliases.any { alias -> containsTerm(haystack, alias) }
}

private fun parseStructuredFinancialLines(text: String, currency: String = "USD"): List<AnnualFinancialPoint> {
    // Accept ₹/Rs/INR and ¥/CNY/RMB/€/£ currency markers alongside $/USD.
    // Optional leading minus (and minus AFTER the currency symbol) so losses parse.
    val linePattern = Regex(
        """FY\s*(20\d{2})\s*:\s*-?\s*(?:C\$|MX\$|\$|₹|¥|€|£|Rs\.?|INR|USD|CNY|RMB|EUR|GBP|CAD|CHF|MXN)?\s*(-?[\d,.]+)\s*(B|BN|BILLION|M|MM|MILLION|CRORE|CR|LAKH\s+CRORE|LAKH)?""",
        RegexOption.IGNORE_CASE
    )
    return linePattern.findAll(text).mapNotNull { match ->
        val year = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val rawValue = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
        val unit = match.groupValues.getOrNull(3).orEmpty()
        // If a leading `-` appeared before the currency symbol, the captured
        // number group won't include it. Detect that case from the matched
        // text and flip the sign.
        val matchedText = match.value
        val effectiveValue = if (rawValue >= 0 && Regex("""FY\s*20\d{2}\s*:\s*-\s*(?:\$|₹|¥|€|£)""").containsMatchIn(matchedText)) -rawValue else rawValue
        AnnualFinancialPoint(
            fiscalYear = year,
            valueUsd = toNativeUnits(effectiveValue, unit, currency),
            currency = currency
        )
    }.toList()
}

private fun parseOfficialFinancialSentences(text: String, metric: FinancialMetric, currency: String = "USD"): List<AnnualFinancialPoint> {
    val metricPattern = when (metric) {
        FinancialMetric.REVENUE -> "revenue|revenues|sales|net sales|turnover|operating revenue"
        FinancialMetric.NET_INCOME -> "net income|profit|earnings|net profit"
        FinancialMetric.TOTAL_ASSETS -> "total assets|assets|book value"
    }
    val sentencePattern = Regex(
        """full year\s+(20\d{2}).{0,180}?(?:$metricPattern).{0,60}?(?:\$|₹|¥|Rs\.?|INR|USD|CNY|RMB)?\s*([\d,.]+)\s*(billion|million|crore|lakh crore|lakh)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    return sentencePattern.findAll(text).mapNotNull { match ->
        val year = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val value = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
        AnnualFinancialPoint(
            fiscalYear = year,
            valueUsd = toNativeUnits(value, match.groupValues[3], currency),
            currency = currency
        )
    }.toList()
}

/** Indian-style financial sentences such as
 *  "FY24 revenue stood at ₹2,40,893 crore" or
 *  "Revenue for the year ended March 31, 2024 was Rs. 2.39 lakh crore". */
private fun parseIndianFinancialLines(text: String, metric: FinancialMetric): List<AnnualFinancialPoint> {
    val metricPattern = when (metric) {
        FinancialMetric.REVENUE -> "revenue|revenues|sales|net sales|turnover|operating revenue"
        FinancialMetric.NET_INCOME -> "net income|profit|earnings|net profit|profit after tax|pat"
        FinancialMetric.TOTAL_ASSETS -> "total assets|assets|book value"
    }
    // Fiscal-year forms used in Indian filings: "FY24", "FY 2024", "FY2024",
    // "fiscal 2024", "year ended March 31, 2024".
    val yearPattern = """(?:FY\s*'?(\d{2,4})|fiscal\s+(?:year\s+)?(\d{4})|march\s+31,?\s+(\d{4}))"""
    val pattern = Regex(
        """$yearPattern.{0,200}?(?:$metricPattern).{0,80}?(?:₹|Rs\.?|INR)?\s*([\d,.]+)\s*(lakh\s+crore|crore|cr|lakh)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    return pattern.findAll(text).mapNotNull { match ->
        val g = match.groupValues
        val rawYear = g[1].ifBlank { g[2].ifBlank { g[3] } }
        val year = normalizeIndianFiscalYear(rawYear) ?: return@mapNotNull null
        val v = g.getOrNull(4)?.replace(",", "")?.toDoubleOrNull() ?: return@mapNotNull null
        val unit = g.getOrNull(5).orEmpty()
        AnnualFinancialPoint(
            fiscalYear = year,
            valueUsd = toNativeUnits(v, unit, "INR"),
            currency = "INR"
        )
    }.toList()
}

private fun normalizeIndianFiscalYear(raw: String): Int? {
    val n = raw.toIntOrNull() ?: return null
    return when {
        n in 1990..2100 -> n            // already a 4-digit year
        n in 20..99 -> 2000 + n         // "FY24" → 2024
        else -> null
    }
}

/** Convert a (value, unit) pair into the smallest unit of [currency]:
 *  USD/CNY → dollars/yuan, INR → rupees. */
private fun toNativeUnits(value: Double, unit: String, currency: String): Long {
    val lower = unit.lowercase().trim()
    val abs = kotlin.math.abs(value)
    val multiplier = when {
        lower.startsWith("lakh crore")               -> 1e12   // 100 cr × 100 lakh = 1e12
        lower == "crore" || lower == "cr"             -> 1e7
        lower == "lakh"                               -> 1e5
        lower in setOf("b", "bn") || lower.contains("billion") -> 1e9
        lower in setOf("m", "mm") || lower.contains("million") -> 1e6
        // Heuristic: bare numbers reported on company IR pages are usually in
        // billions for USD/CNY but in crore for INR.
        abs < 1_000.0 -> if (currency == "INR") 1e7 else 1e9
        else -> 1.0
    }
    return (value * multiplier).roundToLong()
}

private fun buildFinancialSummarySentence(
    company: CompanyProfile,
    metric: FinancialMetric,
    points: List<AnnualFinancialPoint>
): String {
    val first = points.first()
    val last = points.last()
    val previous = points.getOrNull(points.lastIndex - 1)
    val direction = when {
        last.valueUsd > first.valueUsd -> "increased"
        last.valueUsd < first.valueUsd -> "declined"
        else -> "was roughly flat"
    }
    val yoy = previous?.takeIf { it.valueUsd != 0L }?.let {
        val pct = (last.valueUsd - it.valueUsd).toDouble() / it.valueUsd.toDouble() * 100.0
        " The latest year-over-year change was ${"%.1f".format(pct)}%."
    }.orEmpty()
    val firstDisp = formatValueForSentence(first.valueUsd, company.currency)
    val lastDisp = formatValueForSentence(last.valueUsd, company.currency)
    return "${company.name}'s ${metric.displayName.lowercase()} $direction from $firstDisp in FY${first.fiscalYear} to $lastDisp in FY${last.fiscalYear}.$yoy"
}

private fun formatValueForTable(value: Long, currency: String): String = when (currency) {
    "INR" -> "%,d".format((value / 1e7).roundToLong())                  // e.g. "239,300"
    else -> "%.1f".format(value / 1_000_000_000.0)                       // numeric (billions) — currency unit shown in header
}

private fun formatValueForSentence(value: Long, currency: String): String {
    val billions = value / 1_000_000_000.0
    return when (currency) {
        "INR" -> "₹${"%,d".format((value / 1e7).roundToLong())} crore"
        "CNY" -> "¥${"%.1f".format(billions)}B"
        "EUR" -> "€${"%.1f".format(billions)}B"
        "GBP" -> "£${"%.1f".format(billions)}B"
        "CAD" -> "C\$${"%.1f".format(billions)}B"
        "CHF" -> "CHF ${"%.1f".format(billions)}B"
        "MXN" -> "MX\$${"%.1f".format(billions)}B"
        "JPY" -> "¥${"%.1f".format(billions)}B"
        else -> "\$${"%.1f".format(billions)}B"
    }
}
