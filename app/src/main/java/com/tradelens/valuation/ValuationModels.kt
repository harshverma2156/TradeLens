package com.tradelens.valuation

import com.tradelens.flow.DataPoint
import com.tradelens.fundamentals.Sector

/**
 * Units: money in ₹ crore, per-share values in ₹, rates as decimals (0.12 = 12%). Model estimates are labelled as such
 * everywhere. Market prices come from the quote feed. Analyst consensus is not part of this engine.
 */

enum class Scenario(val label: String) { BEAR("Bear"), BASE("Base"), BULL("Bull") }

enum class ValuationModel(val label: String) {
    DCF("Discounted cash flow (FCFF)"),
    JUSTIFIED_PB("Justified price / book"),
    DDM("Dividend discount model"),
    HISTORICAL_PE("Historical P/E"),
    INDUSTRY_PE("Industry-relative P/E"),
    EV_EBITDA("EV/EBITDA vs peers"),
    PEG("PEG (growth-adjusted P/E)"),
    FCF_YIELD("Free cash flow yield"),
    SOTP("Sum of the parts"),
}

/** Where an assumption came from. "Assumed" means a default that the user should check; it is never a silent zero. */
enum class AssumptionSource(val label: String) {
    REPORTED("Reported history"),
    DERIVED("Derived from reported figures"),
    ASSUMED("Assumed default"),
    USER("Your setting"),
}

data class Assumption(val name: String, val value: String, val source: AssumptionSource, val why: String)

/** User-adjustable valuation settings. The margin of safety is the buy-discount the user wants before buying. */
data class ValuationConfig(
    /** Buy only at least this much below fair value (0.25 = 25%). */
    val marginOfSafety: Double = 0.25,
    /** Risk-free rate: India 10-year government bond, assumed. */
    val riskFreeRate: Double = 0.068,
    val equityRiskPremium: Double = 0.055,
    /** No beta is supplied by the app's data sources, so 1.0 (the market) is assumed. */
    val beta: Double = 1.0,
    /** Indian corporate tax rate incl. surcharge and cess (25.17%). */
    val taxRate: Double = 0.2517,
    /** Cost of debt when interest and debt are not both reported. */
    val costOfDebtFallback: Double = 0.09,
    /** Share of a rise in revenue that ties up cash in working capital, used when history gives no estimate. */
    val workingCapitalIntensity: Double = 0.10,
    val projectionYears: Int = 5,
) {
    init {
        require(marginOfSafety in 0.0..0.6) { "Margin of safety must be between 0% and 60%" }
        require(beta > 0 && projectionYears in 3..10) { "Invalid beta or projection length" }
        require(taxRate in 0.0..0.5) { "Tax rate must be between 0% and 50%" }
    }
}

data class DcfYear(val year: Int, val growth: Double, val revenue: Double, val ebit: Double, val fcf: Double, val presentValue: Double)

/** One scenario of the DCF, with every intermediate value kept so the result can be audited. */
data class DcfCase(
    val scenario: Scenario,
    val startGrowth: Double,
    val terminalGrowth: Double,
    val margin: Double,
    val wacc: Double,
    val years: List<DcfYear>,
    val terminalValue: Double,
    val presentTerminal: Double,
    val enterpriseValue: Double,
    val netDebt: Double,
    val equityValue: Double,
    val perShare: Double,
) {
    /** Share of enterprise value that comes from the terminal value. Above ~75% the answer rests on the long run. */
    val terminalShare: Double get() = if (enterpriseValue != 0.0) presentTerminal / enterpriseValue else 0.0
}

/** Result of one valuation model. [base] is null when the model is not used or lacks inputs; [reason] says which. */
data class ModelValue(
    val model: ValuationModel,
    val bear: Double?,
    val base: Double?,
    val bull: Double?,
    val reason: String,
    val note: String,
)

data class ZoneBand(val zone: AccumulationZone, val upTo: Double?)

enum class AccumulationZone(val label: String) {
    VERY_ATTRACTIVE("Very attractive accumulation zone"),
    ATTRACTIVE("Attractive accumulation zone"),
    FAIR("Fair valuation zone"),
    EXPENSIVE("Expensive zone"),
    HIGHLY_EXPENSIVE("Highly expensive zone"),
}

data class SensitivityRow(val driver: String, val lowInput: String, val highInput: String, val fairLow: Double?, val fairHigh: Double?, val base: Double?) {
    val swing: Double? get() = if (fairLow != null && fairHigh != null) kotlin.math.abs(fairHigh - fairLow) else null
}

data class PeerComparisonRow(val metric: String, val company: Double?, val peerMedian: Double?, val peerCount: Int)

data class Segment(val name: String, val ebitda: Double, val multiple: Double)

data class ValuationReport(
    val symbol: String,
    val computedAt: Long,
    val sector: Sector?,
    val price: Double?,
    val primary: ValuationModel,
    val primaryNote: String,
    val assumptions: List<Assumption>,
    val dcf: DcfCase?,
    val models: List<ModelValue>,
    val fairBear: Double?,
    val fairBase: Double?,
    val fairBull: Double?,
    /** Lowest and highest per-share estimate across the primary scenarios and the cross-check models. */
    val rangeLow: Double?,
    val rangeHigh: Double?,
    /** Positive: the price is above fair value. */
    val premiumPct: Double?,
    /** Positive: the price is below fair value by this share of fair value. */
    val marginOfSafetyPct: Double?,
    val marginOfSafetyPreference: Double,
    val buyBelow: Double?,
    val zones: List<ZoneBand>,
    val currentZone: AccumulationZone?,
    val sensitivity: List<SensitivityRow>,
    /** Starting growth the market price implies under the base-case model (reverse DCF). Null when outside a plausible range. */
    val impliedGrowth: Double?,
    val baseGrowth: Double?,
    val peerComparison: DataPoint<List<PeerComparisonRow>>,
    val interpretation: Interpretation,
    val dataNote: String,
)

data class Interpretation(
    val valuation: String,
    val growthPricedIn: String,
    val fundamentals: String,
    val couldBeWrong: List<String>,
)
