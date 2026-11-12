package com.tradelens.patterns

import com.tradelens.data.model.Candle
import com.tradelens.ta.Bias
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Candlestick patterns completed at the close of bar i (definitions after Steve Nison, sizes in ATR).
 *
 * Context: reversal patterns need a prior trend over the [PatternConfig.candleTrendBars] bars before the pattern
 * (close change ≥ 1 ATR). A pattern is only a signal once a later close confirms it (handled by the engine):
 * bullish → close above the pattern's high, bearish → close below its low, within [PatternConfig.candleConfirmBars].
 *
 * Documented variant: Nison's piercing / dark-cloud / star patterns want a gap beyond the prior bar's low/high or
 * body. NSE daily gaps are smaller and rarer than on 1980s futures, so here the open only has to be beyond the
 * prior CLOSE, and a star's body only has to sit beyond the first body (≤ 10% overlap).
 */
internal object Candlesticks {

    private val Candle.body get() = abs(close - open)
    private val Candle.range get() = high - low
    private val Candle.upper get() = high - max(open, close)
    private val Candle.lower get() = min(open, close) - low
    private val Candle.bull get() = close > open
    private val Candle.bear get() = close < open
    private fun f(v: Double) = String.format(java.util.Locale.US, "%,.2f", v)

    fun at(x: Context, i: Int): List<Geometry> {
        if (i < x.cfg.candleTrendBars + 3 || x.atr[i] == null) return emptyList()
        val c = x.c
        val a = x.atrAt(i)
        val k = c[i]; val p = c[i - 1]; val pp = c[i - 2]
        val out = mutableListOf<Geometry>()
        val tb = x.cfg.candleTrendBars
        val trend1 = x.trendBefore(i, tb)        // before a one-bar pattern
        val trend2 = x.trendBefore(i - 1, tb)    // before a two-bar pattern
        val trend3 = x.trendBefore(i - 2, tb)

        // Engulfing: the second body fully covers the first; first body is a real body (≥ 10% of its range).
        if (p.bear && k.bull && k.open <= p.close && k.close >= p.open && k.body > p.body && p.body >= 0.1 * p.range && k.body >= 0.5 * a && trend2 == Bias.BEARISH)
            out += make(x, PatternType.BULLISH_ENGULFING, i - 1, i, min(1.0, (k.body / p.body - 1) / 1.5 + 0.4),
                "A bullish body (${f(k.body)}) engulfed the prior bearish body (${f(p.body)}) after a decline.")
        if (p.bull && k.bear && k.open >= p.close && k.close <= p.open && k.body > p.body && p.body >= 0.1 * p.range && k.body >= 0.5 * a && trend2 == Bias.BULLISH)
            out += make(x, PatternType.BEARISH_ENGULFING, i - 1, i, min(1.0, (k.body / p.body - 1) / 1.5 + 0.4),
                "A bearish body (${f(k.body)}) engulfed the prior bullish body (${f(p.body)}) after an advance.")

        // Hammer / shooting star: shadow ≥ 2× body and ≥ 60% of the range, other shadow ≤ 15% of the range, real range.
        if (k.range >= 0.6 * a && k.body >= 0.05 * k.range) {
            if (k.lower >= 2 * k.body && k.lower >= 0.6 * k.range && k.upper <= 0.15 * k.range && trend1 == Bias.BEARISH)
                out += make(x, PatternType.HAMMER, i, i, min(1.0, k.lower / k.range),
                    "Long lower shadow (${(k.lower / k.range * 100).toInt()}% of the range): sellers pushed price down and buyers drove it back up, after a decline.")
            if (k.upper >= 2 * k.body && k.upper >= 0.6 * k.range && k.lower <= 0.15 * k.range && trend1 == Bias.BULLISH)
                out += make(x, PatternType.SHOOTING_STAR, i, i, min(1.0, k.upper / k.range),
                    "Long upper shadow (${(k.upper / k.range * 100).toInt()}% of the range): buyers were rejected at the highs, after an advance.")
        }

        // Morning / evening star: long body, small star body beyond it, then a body closing past the first body's midpoint.
        if (pp.body >= 0.6 * a && p.body <= 0.35 * pp.body) {
            val mid = (pp.open + pp.close) / 2
            if (pp.bear && max(p.open, p.close) <= pp.close + 0.1 * pp.body && k.bull && k.close > mid && trend3 == Bias.BEARISH)
                out += make(x, PatternType.MORNING_STAR, i - 2, i, min(1.0, 0.5 + (k.close - mid) / pp.body),
                    "A long bearish bar, a small-bodied star below it, then a bullish close (${f(k.close)}) above the first body's midpoint (${f(mid)}).")
            if (pp.bull && min(p.open, p.close) >= pp.close - 0.1 * pp.body && k.bear && k.close < mid && trend3 == Bias.BULLISH)
                out += make(x, PatternType.EVENING_STAR, i - 2, i, min(1.0, 0.5 + (mid - k.close) / pp.body),
                    "A long bullish bar, a small-bodied star above it, then a bearish close (${f(k.close)}) below the first body's midpoint (${f(mid)}).")
        }

        // Piercing / dark cloud: long first body; second opens beyond its close and closes past its midpoint, but not past its open.
        if (p.body >= 0.6 * a) {
            val mid = (p.open + p.close) / 2
            if (p.bear && k.bull && k.open < p.close && k.close > mid && k.close < p.open && trend2 == Bias.BEARISH)
                out += make(x, PatternType.PIERCING_PATTERN, i - 1, i, min(1.0, 0.4 + (k.close - mid) / (p.open - mid) * 0.6),
                    "Opened below the prior close (${f(p.close)}) and closed at ${f(k.close)}, above the midpoint (${f(mid)}) of the prior bearish body.")
            if (p.bull && k.bear && k.open > p.close && k.close < mid && k.close > p.open && trend2 == Bias.BULLISH)
                out += make(x, PatternType.DARK_CLOUD_COVER, i - 1, i, min(1.0, 0.4 + (mid - k.close) / (mid - p.open) * 0.6),
                    "Opened above the prior close (${f(p.close)}) and closed at ${f(k.close)}, below the midpoint (${f(mid)}) of the prior bullish body.")
        }

        // Doji: body ≤ 10% of a meaningful range (≥ 0.3 ATR). Only reported inside a trend, where Nison says it matters:
        // after an advance it warns of a top (bearish), after a decline of a bottom (bullish). Outside a trend it's noise.
        if (k.range >= 0.3 * a && k.body <= 0.1 * k.range && trend1 != Bias.NEUTRAL) {
            val dir = if (trend1 == Bias.BULLISH) Bias.BEARISH else Bias.BULLISH
            out += make(x, PatternType.DOJI, i, i, 1 - k.body / (0.1 * k.range).coerceAtLeast(1e-12) * 0.5,
                "Open and close almost equal (body ${(k.body / k.range * 100).toInt()}% of the range): indecision" +
                    if (trend1 == Bias.BULLISH) " after an advance, a warning the up-move may stall." else " after a decline, a warning the down-move may stall.",
                dir)
        }
        return out
    }

    private fun make(x: Context, type: PatternType, from: Int, to: Int, fit: Double, note: String, dir: Bias = type.expected): Geometry {
        val bars = (from..to)
        val hi = bars.maxOf { x.c[it].high }; val lo = bars.minOf { x.c[it].low }
        val a = x.atrAt(to)
        val anchors = bars.map { Swing(it, x.c[it].close, false, to) }
        return Geometry(
            type, dir, dir == Bias.NEUTRAL, from, to, anchors, bars.mapIndexed { k, _ -> if (bars.count() == 1) type.label else "Bar ${k + 1}" },
            emptyList(),
            listOf(PriceLevel("Pattern high", hi), PriceLevel("Pattern low", lo)),
            up = { hi }, down = { lo },
            stopBull = lo - 0.1 * a, stopBear = hi + 0.1 * a,
            height = hi - lo, expiresAt = to + x.cfg.candleConfirmBars, fit = fit.coerceIn(0.0, 1.0), note = note, rTargets = true,
        )
    }
}
