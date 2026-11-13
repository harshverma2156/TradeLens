package com.tradelens.fundamentals

import com.tradelens.flow.DataPoint
import com.tradelens.flow.Provenance
import com.tradelens.flow.ShareholdingQuarter

/**
 * Units used throughout: money in ₹ crore (1 crore = 10 million), shares as an absolute count, per-share values in ₹,
 * ratios as multiples (x) and growth/margins as percentages. A null field means "not reported": it is never read as zero.
 */

/** Reporting basis. Consolidated (group, incl. subsidiaries) is the default; standalone (parent only) is shown when asked. */
enum class Basis(val label: String) { CONSOLIDATED("Consolidated"), STANDALONE("Standalone") }

/** Industry group. Ratios are judged against group-specific thresholds, and some ratios do not apply at all to a group. */
enum class Sector(val label: String) {
    BANK("Bank"),
    NBFC("NBFC, lender or insurer"),
    MANUFACTURING("Manufacturing / industrial"),
    IT("IT services"),
    FMCG("FMCG / consumer"),
    PHARMA("Pharma / healthcare"),
    ENERGY_UTILITY("Energy / utilities"),
    REAL_ESTATE("Real estate / infrastructure"),
    OTHER("Other / unclassified"),
}

/** The six scored areas of the quality score, plus valuation which is shown but not scored. */
enum class Pillar(val label: String, val scored: Boolean) {
    PROFITABILITY("Profitability", true),
    GROWTH("Growth", true),
    FINANCIAL_STRENGTH("Financial strength", true),
    CASH_FLOW("Cash flow", true),
    GOVERNANCE("Governance", true),
    CONSISTENCY("Business consistency", true),
    VALUATION("Valuation (not scored)", false),
}

enum class Metric(val label: String, val pillar: Pillar, val unit: String, val higherIsBetter: Boolean) {
    ROE("Return on equity (ROE)", Pillar.PROFITABILITY, "%", true),
    ROCE("Return on capital employed (ROCE)", Pillar.PROFITABILITY, "%", true),
    ROA("Return on assets (ROA)", Pillar.PROFITABILITY, "%", true),
    GROSS_MARGIN("Gross margin", Pillar.PROFITABILITY, "%", true),
    OPERATING_MARGIN("Operating margin", Pillar.PROFITABILITY, "%", true),
    NET_MARGIN("Net profit margin", Pillar.PROFITABILITY, "%", true),

    REVENUE_CAGR("Revenue CAGR", Pillar.GROWTH, "%", true),
    EBITDA_CAGR("EBITDA CAGR", Pillar.GROWTH, "%", true),
    EPS_CAGR("EPS CAGR", Pillar.GROWTH, "%", true),
    NET_PROFIT_CAGR("Net profit CAGR", Pillar.GROWTH, "%", true),
    QUARTERLY_REVENUE_GROWTH("Quarterly revenue growth (vs same quarter last year)", Pillar.GROWTH, "%", true),
    ANNUAL_REVENUE_GROWTH("Annual revenue growth", Pillar.GROWTH, "%", true),

    DEBT_TO_EQUITY("Debt / equity", Pillar.FINANCIAL_STRENGTH, "x", false),
    INTEREST_COVERAGE("Interest coverage (EBIT ÷ interest)", Pillar.FINANCIAL_STRENGTH, "x", true),
    CURRENT_RATIO("Current ratio", Pillar.FINANCIAL_STRENGTH, "x", true),
    QUICK_RATIO("Quick ratio", Pillar.FINANCIAL_STRENGTH, "x", true),

    CASH_CONVERSION("Cash conversion (operating cash flow ÷ net profit)", Pillar.CASH_FLOW, "x", true),
    FCF_MARGIN("Free cash flow margin", Pillar.CASH_FLOW, "%", true),

    PE("P/E (trailing)", Pillar.VALUATION, "x", false),
    FORWARD_PE("Forward P/E", Pillar.VALUATION, "x", false),
    PB("Price / book (P/B)", Pillar.VALUATION, "x", false),
    PEG("PEG (P/E ÷ EPS CAGR)", Pillar.VALUATION, "x", false),
    EV_EBITDA("EV / EBITDA", Pillar.VALUATION, "x", false),
    PRICE_TO_SALES("Price / sales", Pillar.VALUATION, "x", false),
    DIVIDEND_YIELD("Dividend yield", Pillar.VALUATION, "%", true),
    FCF_YIELD("Free cash flow yield", Pillar.VALUATION, "%", true),
}

/** One metric for one period. [value] is null when it cannot be computed; [reason] says why. */
data class Reading(val metric: Metric, val value: Double?, val applicable: Boolean, val reason: String = "")

/** One reporting period (annual or quarterly). Cash-flow capex is a positive number for cash spent on fixed assets. */
data class FinancialPeriod(
    val periodEnd: Long,
    val months: Int,
    val revenue: Double? = null,
    val grossProfit: Double? = null,
    val ebitda: Double? = null,
    val ebit: Double? = null,
    val interestExpense: Double? = null,
    val netProfit: Double? = null,
    val eps: Double? = null,
    /** Shares outstanding, absolute count. */
    val shares: Double? = null,
    val equity: Double? = null,
    val totalDebt: Double? = null,
    val cash: Double? = null,
    val totalAssets: Double? = null,
    val currentAssets: Double? = null,
    val currentLiabilities: Double? = null,
    val inventory: Double? = null,
    val receivables: Double? = null,
    val operatingCashFlow: Double? = null,
    val investingCashFlow: Double? = null,
    val financingCashFlow: Double? = null,
    val capex: Double? = null,
    /** Cash-flow statement sign: negative means working capital absorbed cash. */
    val workingCapitalChange: Double? = null,
)

data class ManagementChange(val date: Long, val role: String, val change: String)
data class RegulatoryAction(val date: Long, val authority: String, val summary: String)

/** Filing-based governance inputs. Each list is null when not disclosed; an empty list means "none reported". */
data class GovernanceRecord(
    val auditorQualifications: List<String>? = null,
    val relatedPartyValue: Double? = null,
    val contingentLiabilities: Double? = null,
    val managementChanges: List<ManagementChange>? = null,
    val regulatoryActions: List<RegulatoryAction>? = null,
)

data class FundamentalSnapshot(
    val symbol: String,
    /** Null when the source does not classify the company. The generic profile is then used and this is said. */
    val sector: Sector?,
    val basis: Basis,
    val currency: String,
    /** Oldest first. */
    val annual: List<FinancialPeriod>,
    /** Oldest first. */
    val quarterly: List<FinancialPeriod>,
    val forwardEps: Double? = null,
    val dividendPerShare: Double? = null,
    val governance: DataPoint<GovernanceRecord>,
    /** Quarter-end shareholding, oldest first. */
    val shareholding: DataPoint<List<ShareholdingQuarter>>,
    val updatedAt: Long?,
    /** Share price at the snapshot date, from the quote feed. Needed for peer multiples (P/E, EV/EBITDA) in Phase 8. */
    val marketPrice: Double? = null,
)

enum class Severity(val label: String, val penalty: Int) { HIGH("High", 35), MEDIUM("Medium", 15), LOW("Low", 5) }

data class RedFlag(val title: String, val severity: Severity, val evidence: String)

data class MetricScore(val reading: Reading, val score: Double)

data class Trend(val metric: Metric, val previous: Double, val current: Double, val improved: Boolean)

data class IndustryPosition(val metric: Metric, val value: Double, val peerMedian: Double, val betterThanPct: Int, val peerCount: Int)

data class PillarScore(val pillar: Pillar, val score: Double?, val scored: Int, val applicable: Int)

data class QualityScore(val score: Int?, val band: String?, val pillarsScored: Int, val pillarsTotal: Int, val note: String)

data class FundamentalConfig(
    /** Relative weight of each scored pillar. Pillars without data are left out and the rest are re-weighted. */
    val weights: Map<Pillar, Double> = DEFAULT_WEIGHTS,
    /** Fewer scored pillars than this and no overall score is given. */
    val minPillars: Int = 3,
    /** Fewer peers with a metric than this and no industry position is given. */
    val minPeers: Int = 3,
) {
    init {
        require(weights.values.all { it >= 0.0 } && weights.values.sum() > 0.0) { "Weights must be non-negative and not all zero" }
    }

    companion object {
        val DEFAULT_WEIGHTS = mapOf(
            Pillar.PROFITABILITY to 25.0,
            Pillar.GROWTH to 20.0,
            Pillar.FINANCIAL_STRENGTH to 20.0,
            Pillar.CASH_FLOW to 15.0,
            Pillar.GOVERNANCE to 10.0,
            Pillar.CONSISTENCY to 10.0,
        )
    }
}

data class FundamentalAnalysis(
    val sector: Sector,
    val sectorKnown: Boolean,
    val profileRationale: String,
    val basis: Basis,
    val currency: String,
    val latestPeriodEnd: Long?,
    val updatedAt: Long?,
    val readings: List<Reading>,
    val pillars: List<PillarScore>,
    val quality: QualityScore,
    val summary: String,
    val strongest: List<MetricScore>,
    val weakest: List<MetricScore>,
    val improving: List<Trend>,
    val deteriorating: List<Trend>,
    val redFlags: List<RedFlag>,
    val governanceChecks: Int,
    val industry: DataPoint<List<IndustryPosition>>,
    val missingFields: List<String>,
    val staleNote: String?,
    val caveat: String,
)

data class FundamentalReport(
    val symbol: String,
    val computedAt: Long,
    val snapshot: DataPoint<FundamentalSnapshot>,
    val analysis: FundamentalAnalysis?,
    val provenance: Provenance?,
)
