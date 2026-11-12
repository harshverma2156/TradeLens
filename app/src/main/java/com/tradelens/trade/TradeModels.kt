package com.tradelens.trade

/**
 * What the user should do now. The first four are for someone without a position; HOLD, PARTIAL_PROFIT_BOOKING
 * and EXIT are for someone holding the stock; AVOID applies to both (a holder sees EXIT or HOLD instead).
 */
enum class EntryStatus(val label: String, val buySignal: Boolean = false) {
    BUY_NOW("BUY NOW", buySignal = true),
    WAIT_FOR_CORRECTION("WAIT FOR CORRECTION"),
    WAIT_FOR_BREAKOUT("WAIT FOR BREAKOUT"),
    WAIT_FOR_CONFIRMATION("WAIT FOR CONFIRMATION"),
    HOLD("HOLD"),
    PARTIAL_PROFIT_BOOKING("PARTIAL PROFIT BOOKING"),
    EXIT("EXIT"),
    AVOID("AVOID");

    /** Changes INTO these reduce risk and bypass the stability cooldown. */
    val riskReducing get() = this == EXIT || this == AVOID || this == PARTIAL_PROFIT_BOOKING
}

enum class StrategyType(val label: String, val summary: String) {
    AGGRESSIVE("Aggressive", "Buy near a support or demand zone before full confirmation: best price, highest chance of being stopped out."),
    CONSERVATIVE("Conservative", "Wait for price to react at support: a reversal candle close plus supporting indicators. Later entry, fewer false starts."),
    BREAKOUT("Breakout", "Buy only after a candle closes above resistance, preferably on above-average volume."),
}

enum class LevelSource(val label: String, val weight: Double) {
    DEMAND_ZONE("Demand zone", 2.5),
    SUPPLY_ZONE("Supply zone", 2.5),
    SWING_SUPPORT("Historical support", 2.0),
    SWING_RESISTANCE("Historical resistance", 2.0),
    SWING_LOW("Previous swing low", 1.0),
    SWING_HIGH("Previous swing high", 1.0),
    EMA20("EMA 20", 1.5),
    EMA50("EMA 50", 2.0),
    EMA200("EMA 200", 2.5),
    FIBONACCI("Fibonacci retracement", 1.2),
    POC("Volume profile POC", 2.0),
    VALUE_AREA_HIGH("Value area high", 1.0),
    VALUE_AREA_LOW("Value area low", 1.0),
    VWAP("Session VWAP", 1.0),
    ANCHORED_VWAP("Anchored VWAP", 1.5),
    PATTERN("Chart pattern level", 2.0),
    BREAKOUT_RETEST("Breakout retest", 2.0),
    HIGH_52W("52-week high", 1.5),
    // Target-only sources (projections, never support for an entry)
    FIB_EXTENSION("Fibonacci extension", 1.0),
    MEASURED_MOVE("Pattern measured move", 1.5),
    VOLATILITY("ATR volatility projection", 0.5),
}

/** One price level, optionally a band (supply/demand zones have a low and high). */
data class Level(val price: Double, val source: LevelSource, val detail: String, val low: Double = price, val high: Double = price, val weight: Double = source.weight)

/** Nearby levels clustered together. Score = summed weights, with a bonus for agreement between different sources. */
data class Zone(val low: Double, val high: Double, val members: List<Level>, val score: Double) {
    val mid get() = (low + high) / 2
    val sources get() = members.map { it.source }.distinct()
    val label get() = sources.joinToString(" + ") { it.label }
    val hasDemand get() = LevelSource.DEMAND_ZONE in sources
}

data class Target(val label: String, val price: Double, val basis: String, val pctFromEntry: Double, val rewardRisk: Double)

data class PartialExit(val label: String, val price: Double, val fraction: Double, val action: String)

data class TrailingStop(val method: String, val current: Double?, val rule: String)

data class ExitPlan(
    val stopLoss: Double,
    val stopBasis: String,
    val targets: List<Target>,
    val trailing: TrailingStop,
    val partials: List<PartialExit>,
    /** Before entry: a close beyond this cancels the setup. After entry the stop-loss governs. */
    val invalidation: Double,
    val invalidationRule: String,
    val exitConditions: List<String>,
)

data class RiskReward(
    val entry: Double,
    val stopLoss: Double,
    val riskPerShare: Double,
    /** Reward per share to T1, T2, T3. */
    val rewards: List<Double>,
    val ratios: List<Double>,
    val stopPct: Double,
    val targetPcts: List<Double>,
    val quantity: Int,
    val capitalRequired: Double,
    /** Capital required as % of trading capital. */
    val exposurePct: Double,
    val maxLoss: Double,
    val riskBudget: Double,
    /** Which limit set the quantity: risk budget, max position size or available capital. */
    val limitedBy: String,
)

data class Rationale(
    val why: String,
    val supporting: List<String>,
    val levels: List<Pair<String, Double>>,
    val invalidation: List<String>,
    val risks: List<String>,
)

data class StrategyPlan(
    val type: StrategyType,
    val status: EntryStatus,
    /** True when this plan says BUY NOW and passes every check. */
    val actionable: Boolean,
    val entry: Double,
    val entryLow: Double,
    val entryHigh: Double,
    val trigger: String,
    val exit: ExitPlan?,
    val risk: RiskReward?,
    /** Passes the stop, reward/risk and sizing checks (it may still be a WAIT). */
    val viable: Boolean,
    val rejections: List<String>,
    val score: Int,
    /** How far price is from flipping this plan's status, in ATR (used for signal stability). */
    val marginAtr: Double,
    val rationale: Rationale,
)

enum class BlockerType(val label: String) {
    STALE_DATA("Data is stale"),
    MISSING_CANDLES("Required candles are missing"),
    INSUFFICIENT_HISTORY("Not enough history"),
    POOR_RISK_REWARD("Risk/reward is unattractive"),
    PATTERN_FAILED("A pattern has failed"),
    NO_LOGICAL_STOP("No logical stop-loss"),
    EVENT_RISK("Unresolved major event"),
    BEARISH_SIGNALS("Technical picture is bearish"),
    NO_SETUP("No valid setup"),
}

data class Blocker(val type: BlockerType, val message: String)

data class EventRisk(val title: String, val date: Long?, val major: Boolean, val resolved: Boolean, val source: String)

data class Position(val quantity: Int, val avgPrice: Double)

data class PositionAdvice(
    val status: EntryStatus,
    val pnlPct: Double,
    /** Structural stop: a daily close below it means EXIT. */
    val protectiveStop: Double,
    /** Chandelier trailing level: once T1 has been reached, raise the stop to this (never lower it). */
    val trailingStop: Double?,
    val nextTarget: Target?,
    val reason: String,
)

/**
 * Long-term view, deliberately separate from the short-term technical plan: it never sets a stop-loss and never
 * produces a BUY NOW. Valuation-based accumulation needs fundamentals the app doesn't have yet.
 */
data class LongTermView(
    val valuationAvailable: Boolean,
    val valuationNote: String,
    val trend: String,
    val context: List<String>,
)

data class StabilityNote(val heldStatus: EntryStatus, val pendingStatus: EntryStatus, val reason: String)

data class Recommendation(
    val symbol: String,
    val status: EntryStatus,
    val headline: String,
    val primary: StrategyType?,
    val strategies: List<StrategyPlan>,
    val blockers: List<Blocker>,
    val events: List<EventRisk>,
    val position: PositionAdvice?,
    val rationale: Rationale,
    val price: Double,
    val atr: Double,
    val supports: List<Zone>,
    val resistances: List<Zone>,
    val longTerm: LongTermView,
    /** Time of the last closed bar used; recommendations only change when this (or the price) changes. */
    val dataAsOf: Long?,
    val computedAt: Long,
    val stability: StabilityNote? = null,
) {
    val primaryPlan get() = strategies.firstOrNull { it.type == primary }
    fun plan(t: StrategyType) = strategies.first { it.type == t }
}

data class RiskSettings(val capital: Double, val riskPercent: Double, val maxPositionPercent: Double)

/**
 * Tunables. Distances are in ATR(14) so they adapt to each stock's volatility; nothing is a fixed percentage.
 */
data class EngineConfig(
    val minBars: Int = 200,
    val clusterAtr: Double = 0.6,
    val minZoneScore: Double = 2.5,
    /** Furthest support (in ATR) the aggressive/conservative plans will wait for. */
    val maxPullbackAtr: Double = 4.0,
    /** Zone preference falls by this fraction per ATR of distance below the price (nearer support wins ties). */
    val zoneDistanceDecay: Double = 0.2,
    /** Furthest resistance (in ATR) the breakout plan watches. */
    val maxBreakoutDistanceAtr: Double = 4.0,
    /** Price within this many ATR above a zone counts as "at the zone". */
    val atZoneAtr: Double = 0.25,
    val stopBufferAtr: Double = 0.3,
    val minStopAtr: Double = 0.75,
    val maxStopAtr: Double = 3.5,
    val minRewardRiskT1: Double = 1.0,
    val minRewardRiskT2: Double = 2.0,
    val minTargetGapAtr: Double = 0.5,
    val confirmBufferAtr: Double = 0.1,
    /** Don't chase a breakout that has already run more than this. */
    val maxChaseAtr: Double = 1.5,
    val breakoutLookbackBars: Int = 5,
    val volumeConfirm: Double = 1.3,
    val conservativeLookbackBars: Int = 3,
    val minConfirmations: Int = 2,
    val chandelierBars: Int = 22,
    val chandelierAtr: Double = 3.0,
    val timeStopBars: Int = 20,
    val partialFractions: List<Double> = listOf(1.0 / 3, 1.0 / 3, 1.0 / 3),
    /** A bar with a gap ≥ this many ATR (or a range ≥ shockRangeAtr) in the last [shockSettleBars] bars is an unresolved event. */
    val shockGapAtr: Double = 3.0,
    val shockRangeAtr: Double = 4.0,
    val shockSettleBars: Int = 3,
    /** More than this many consecutive weekdays without a bar in the recent window = missing candles. */
    val maxWeekdayGap: Int = 3,
    val recentWindowBars: Int = 60,
    val tickSize: Double = 0.05,
)

/** Signal stability: how readily a published recommendation may change. */
data class StabilityConfig(
    /** A non-risk-reducing status change needs at least this long since the last change. */
    val cooldownMs: Long = 15 * 60_000L,
    /** …and price must be at least this many ATR past the decision boundary. */
    val hysteresisAtr: Double = 0.15,
    /** Entry/stop/target moves smaller than this many ATR are ignored (levels stay put). */
    val levelToleranceAtr: Double = 0.2,
)
