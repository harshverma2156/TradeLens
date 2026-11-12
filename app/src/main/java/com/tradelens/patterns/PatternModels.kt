package com.tradelens.patterns

import com.tradelens.marketdata.Timeframe
import com.tradelens.ta.Bias

enum class PatternKind(val label: String) { REVERSAL("Reversal"), CONTINUATION("Continuation"), CANDLESTICK("Candlestick") }

/**
 * Every pattern the engine can detect. [expected] is the textbook direction; NEUTRAL patterns (symmetrical
 * triangle and rectangle with no prior trend, a doji outside a trend) take the direction of whichever side breaks.
 */
enum class PatternType(val label: String, val kind: PatternKind, val expected: Bias) {
    HEAD_AND_SHOULDERS("Head and Shoulders", PatternKind.REVERSAL, Bias.BEARISH),
    INVERSE_HEAD_AND_SHOULDERS("Inverse Head and Shoulders", PatternKind.REVERSAL, Bias.BULLISH),
    DOUBLE_TOP("Double Top", PatternKind.REVERSAL, Bias.BEARISH),
    DOUBLE_BOTTOM("Double Bottom", PatternKind.REVERSAL, Bias.BULLISH),
    TRIPLE_TOP("Triple Top", PatternKind.REVERSAL, Bias.BEARISH),
    TRIPLE_BOTTOM("Triple Bottom", PatternKind.REVERSAL, Bias.BULLISH),

    BULL_FLAG("Bull Flag", PatternKind.CONTINUATION, Bias.BULLISH),
    BEAR_FLAG("Bear Flag", PatternKind.CONTINUATION, Bias.BEARISH),
    ASCENDING_TRIANGLE("Ascending Triangle", PatternKind.CONTINUATION, Bias.BULLISH),
    DESCENDING_TRIANGLE("Descending Triangle", PatternKind.CONTINUATION, Bias.BEARISH),
    SYMMETRICAL_TRIANGLE("Symmetrical Triangle", PatternKind.CONTINUATION, Bias.NEUTRAL),
    CUP_AND_HANDLE("Cup and Handle", PatternKind.CONTINUATION, Bias.BULLISH),
    RECTANGLE("Rectangle", PatternKind.CONTINUATION, Bias.NEUTRAL),
    RISING_WEDGE("Rising Wedge", PatternKind.CONTINUATION, Bias.BEARISH),
    FALLING_WEDGE("Falling Wedge", PatternKind.CONTINUATION, Bias.BULLISH),
    ASCENDING_CHANNEL("Ascending Price Channel", PatternKind.CONTINUATION, Bias.BULLISH),
    DESCENDING_CHANNEL("Descending Price Channel", PatternKind.CONTINUATION, Bias.BEARISH),

    BULLISH_ENGULFING("Bullish Engulfing", PatternKind.CANDLESTICK, Bias.BULLISH),
    BEARISH_ENGULFING("Bearish Engulfing", PatternKind.CANDLESTICK, Bias.BEARISH),
    HAMMER("Hammer", PatternKind.CANDLESTICK, Bias.BULLISH),
    SHOOTING_STAR("Shooting Star", PatternKind.CANDLESTICK, Bias.BEARISH),
    MORNING_STAR("Morning Star", PatternKind.CANDLESTICK, Bias.BULLISH),
    EVENING_STAR("Evening Star", PatternKind.CANDLESTICK, Bias.BEARISH),
    DOJI("Doji", PatternKind.CANDLESTICK, Bias.NEUTRAL),
    PIERCING_PATTERN("Piercing Pattern", PatternKind.CANDLESTICK, Bias.BULLISH),
    DARK_CLOUD_COVER("Dark Cloud Cover", PatternKind.CANDLESTICK, Bias.BEARISH);

    /** Triangles, wedges, rectangles and channels: built from trendlines through several swing points. */
    val trendlineFamily get() = this in setOf(ASCENDING_TRIANGLE, DESCENDING_TRIANGLE, SYMMETRICAL_TRIANGLE, RECTANGLE, RISING_WEDGE, FALLING_WEDGE, ASCENDING_CHANNEL, DESCENDING_CHANNEL)
}

/**
 *  FORMING      structure complete, waiting for a close through the breakout level.
 *  CONFIRMED    a candle CLOSED through the breakout level (in the pattern's direction).
 *  FAILED       confirmed, then a close through the stop-loss before the first target.
 *  INVALIDATED  never confirmed: a close through the invalidation level, no breakout in time, or the
 *               structure evolved into a different pattern.
 */
enum class PatternStatus(val label: String) { FORMING("Forming"), CONFIRMED("Confirmed"), FAILED("Failed"), INVALIDATED("Invalidated") }

/** A chart coordinate: bar index into [PatternReport.candles] and price. */
data class ChartPoint(val index: Int, val time: Long, val price: Double, val label: String = "")

/** A line to draw: neckline, trendline, flag pole, outline leg. */
data class ChartSegment(val from: ChartPoint, val to: ChartPoint, val role: SegmentRole)

enum class SegmentRole { OUTLINE, NECKLINE, UPPER, LOWER, POLE, RIM }

data class PriceLevel(val label: String, val price: Double)

enum class EventType { DETECTED, UPDATED, CONFIRMED, TARGET_HIT, FAILED, INVALIDATED, CLOSED }

/**
 * One state change. [index] is the bar where it happened; [knownIndex] is the bar at whose close the engine
 * could first know it (≥ index; larger when a swing point needed later bars to be confirmed).
 */
data class PatternEvent(val type: EventType, val index: Int, val knownIndex: Int, val knownAt: Long, val detail: String)

data class VolumeCheck(
    val available: Boolean,
    /** Breakout bar volume ÷ average of the 20 bars before it. Null until a breakout. */
    val breakoutRelativeVolume: Double?,
    /** True/false once there is a breakout; null before. */
    val confirmed: Boolean?,
    /** Average volume of the formation's second half ÷ first half (< 1 = contracting, typical before a breakout). */
    val formationTrend: Double?,
    val note: String,
)

/** Additive score parts. Max: geometry 35 + size 10 + breakout 20 + volume 20 + higher timeframe 15 = 100. */
data class ConfidenceBreakdown(val geometry: Int, val size: Int, val breakout: Int, val volume: Int, val higherTimeframe: Int) {
    val total get() = (geometry + size + breakout + volume + higherTimeframe).coerceIn(0, 100)
}

data class DetectedPattern(
    /** Stable identity: type + first anchor bar time. */
    val id: String,
    val type: PatternType,
    val direction: Bias,
    val timeframe: Timeframe,
    val status: PatternStatus,
    /** Bar index / time at which the pattern was first detectable (all its swing points confirmed). */
    val detectedIndex: Int,
    val detectedAt: Long,
    val startIndex: Int,
    val endIndex: Int,
    val confirmedIndex: Int?,
    val confidence: Int,
    val breakdown: ConfidenceBreakdown,
    /** Named swing points (shoulders, head, tops, rims, pole ends, …). */
    val anchors: List<ChartPoint>,
    val segments: List<ChartSegment>,
    val keyLevels: List<PriceLevel>,
    /** Close beyond this confirms the pattern (projected to the confirmation bar, or the latest bar while forming). */
    val breakoutLevel: Double,
    val entry: Double,
    val stopLoss: Double,
    val targets: List<PriceLevel>,
    /** Before confirmation, a close beyond this kills the pattern. */
    val invalidationLevel: Double,
    val patternHeight: Double,
    val volume: VolumeCheck,
    val higherTimeframe: Bias?,
    val targetsHit: Int,
    /** No longer tracked: failed, invalidated, final target reached, stopped after a target, or tracking window over. */
    val closed: Boolean,
    val explanation: String,
    val events: List<PatternEvent>,
) {
    val name get() = type.label
    val bullish get() = direction == Bias.BULLISH

    /**
     * The only input an AI explainer may receive: facts produced by this deterministic engine. An explainer may
     * rephrase them; it must never add, remove or alter patterns or levels.
     */
    fun facts(): Map<String, Any?> = mapOf(
        "pattern" to type.label, "kind" to type.kind.label, "direction" to direction.name.lowercase(), "timeframe" to timeframe.label,
        "status" to status.label, "detectedAt" to detectedAt, "confidence" to confidence,
        "breakoutLevel" to breakoutLevel, "entry" to entry, "stopLoss" to stopLoss, "targets" to targets.map { it.price },
        "invalidation" to invalidationLevel, "volumeConfirmed" to volume.confirmed, "relativeVolume" to volume.breakoutRelativeVolume,
        "higherTimeframe" to higherTimeframe?.name?.lowercase(), "keyLevels" to keyLevels.associate { it.label to it.price },
    )
}

/**
 * Tunables. Price distances are in ATR(14) units so one setting works for a ₹50 stock and a ₹50,000 one, on
 * any timeframe. Durations are in bars of the analysed timeframe.
 */
data class PatternConfig(
    /** Bars on each side a swing high/low must dominate. A swing is confirmed this many bars after it. */
    val pivotWindow: Int = 5,
    /** Minimum zigzag leg, in ATR. Smaller swings are treated as noise. */
    val minSwingAtr: Double = 1.5,
    /** Two peaks/troughs count as "equal" within this many ATR. */
    val equalityAtr: Double = 1.0,
    /** Minimum pattern height (ATR). */
    val minHeightAtr: Double = 2.5,
    val minPatternBars: Int = 15,
    /** Each leg between consecutive swings of a reversal pattern must span at least this many bars (no one-day spikes). */
    val minLegBars: Int = 3,
    val maxPatternBars: Int = 300,
    /** Max distance (ATR) of any swing point from its fitted trendline. */
    val trendlineFitAtr: Double = 0.75,
    /** A trendline is "flat" if it moves less than this many ATR over the whole pattern. */
    val flatAtr: Double = 1.0,
    /** A close must exceed the breakout level by this many ATR to confirm. */
    val confirmBufferAtr: Double = 0.1,
    /** Stop-loss buffer beyond the structural level (ATR). */
    val stopBufferAtr: Double = 0.25,
    /** Breakout-bar volume ÷ 20-bar average needed for volume confirmation. */
    val volumeConfirm: Double = 1.3,
    val poleMinAtr: Double = 4.0,
    val poleMaxBars: Int = 25,
    val flagMinBars: Int = 5,
    val flagMaxBars: Int = 25,
    val cupMinBars: Int = 25,
    val handleMinBars: Int = 5,
    /** Candlestick patterns must be confirmed by a close beyond their high/low within this many bars. */
    val candleConfirmBars: Int = 3,
    /** Bars before a candlestick pattern used to judge the prior trend. */
    val candleTrendBars: Int = 10,
)

data class PatternReport(
    val symbol: String,
    val timeframe: Timeframe,
    val higherTimeframe: Timeframe?,
    val computedAt: Long,
    /** Closed bars analysed; every index in a pattern refers to this list. */
    val candleCount: Int,
    val lastBarTime: Long?,
    val patterns: List<DetectedPattern>,
    val config: PatternConfig,
) {
    val active get() = patterns.filter { !it.closed }
}
