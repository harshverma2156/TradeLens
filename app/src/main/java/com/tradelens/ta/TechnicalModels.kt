package com.tradelens.ta

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.Timeframe

enum class Bias { BULLISH, BEARISH, NEUTRAL }

enum class IndicatorCategory(val label: String) {
    TREND("Trend"), MOMENTUM("Momentum"), VOLATILITY("Volatility"), VOLUME("Volume"), LEVELS("Levels"),
}

/**
 * Indicator families. Members of a family measure nearly the same thing (e.g. RSI, Stochastic and Williams %R
 * are all "where is the close within its recent range"), so each family casts ONE vote: the average of its
 * members. Weights sum to 1 over the voting families. CONTEXT readings describe location only and never vote.
 */
enum class SignalGroup(val label: String, val weight: Double) {
    MOVING_AVERAGES("Moving averages", 0.20),
    TREND_SYSTEMS("Trend systems (Supertrend, PSAR, Ichimoku)", 0.15),
    TREND_STRENGTH("Trend strength (ADX/DMI)", 0.10),
    MOMENTUM_OSCILLATORS("Momentum oscillators (RSI, Stochastic, Stoch RSI, CCI, Williams %R)", 0.15),
    MACD("MACD", 0.10),
    VOLUME_FLOW("Volume flow (OBV, A/D, MFI)", 0.15),
    VWAP("VWAP", 0.05),
    VOLATILITY_CHANNELS("Volatility channels (Bollinger, Keltner, Donchian)", 0.10),
    CONTEXT("Levels & volatility (context only, no vote)", 0.0);

    val votes get() = weight > 0
}

data class IndicatorReading(
    val id: String,
    val name: String,
    val category: IndicatorCategory,
    val group: SignalGroup,
    val timeframe: Timeframe,
    /** Headline value (null while warming up). */
    val value: Double?,
    val display: String,
    /** Secondary values, e.g. MACD signal and histogram. */
    val components: List<Pair<String, Double?>> = emptyList(),
    val bias: Bias,
    /** 0–100: how convincing this reading is on its own. */
    val strength: Int,
    val explanation: String,
    /** Mathematical definition, parameters and documented variants (shown in the expandable section). */
    val formula: String,
    val params: String,
    val warmupBars: Int,
    val ready: Boolean,
    /** Open time of the last CLOSED bar used. */
    val barTime: Long?,
    /** Wall-clock time of the calculation. */
    val computedAt: Long,
) {
    val signedScore: Int get() = when (bias) { Bias.BULLISH -> strength; Bias.BEARISH -> -strength; Bias.NEUTRAL -> 0 }
}

data class GroupVote(val group: SignalGroup, val score: Int, val bias: Bias, val members: List<String>, val ready: Int)

data class TechnicalSummary(
    val bias: Bias,
    val label: String,             // "Buy" / "Sell" / "Neutral"
    val score: Int,                // −100..100, weighted over families
    val bullishGroups: Int,
    val bearishGroups: Int,
    val votingGroups: Int,
    val explanation: String,
)

/** Series for chart overlays, aligned to [TechnicalReport.candles] (closed bars). */
data class Overlays(
    val ema: Map<Int, List<Double?>>,
    val sma: Map<Int, List<Double?>>,
    val bollinger: VolatilityCalc.Bands,
    val keltner: VolatilityCalc.Bands,
    val donchian: VolatilityCalc.Bands,
    val supertrend: TrendCalc.Supertrend,
    val psar: TrendCalc.Psar,
    val ichimokuTenkan: List<Double?>,
    val ichimokuKijun: List<Double?>,
    /** Senkou spans already shifted forward by the displacement: value at i = raw span from bar i − 26. */
    val cloudA: List<Double?>,
    val cloudB: List<Double?>,
    val vwap: List<Double?>,
    val anchoredVwap: List<Double?>,
    val anchorIndex: Int?,
    val pivots: LevelsCalc.Pivots?,
    val fibRetracement: List<LevelsCalc.Level>,
    val fibExtension: List<LevelsCalc.Level>,
    val profile: VolumeCalc.Profile?,
    val supports: List<Double>,
    val resistances: List<Double>,
)

data class Oscillators(
    val rsi: List<Double?>,
    val macd: MomentumCalc.Macd,
    val stoch: MomentumCalc.Stoch,
    val stochRsi: MomentumCalc.Stoch,
    val cci: List<Double?>,
    val williamsR: List<Double?>,
    val dmi: TrendCalc.Dmi,
    val mfi: List<Double?>,
    val obv: List<Double>,
    val adLine: List<Double>,
    val atr: List<Double?>,
)

data class TechnicalReport(
    val symbol: String,
    val timeframe: Timeframe,
    val computedAt: Long,
    /** Closed bars the indicators were computed on. */
    val candles: List<Candle>,
    /** The bar still forming (if any): shown on the chart, never used for signals. */
    val formingBar: Candle?,
    val readings: List<IndicatorReading>,
    val groups: List<GroupVote>,
    val summary: TechnicalSummary,
    val overlays: Overlays,
    val oscillators: Oscillators,
) {
    val lastClosedBarTime: Long? get() = candles.lastOrNull()?.time
    fun reading(id: String) = readings.firstOrNull { it.id == id }
}
