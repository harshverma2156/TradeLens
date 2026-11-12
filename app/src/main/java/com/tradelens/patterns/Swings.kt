package com.tradelens.patterns

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.Resampler
import com.tradelens.marketdata.Timeframe
import com.tradelens.ta.Bias
import com.tradelens.ta.Rolling
import com.tradelens.ta.VolatilityCalc
import kotlin.math.abs

/** A swing point. It becomes known at the close of bar [confirmedAt] = index + window, never earlier. */
data class Swing(val index: Int, val price: Double, val high: Boolean, val confirmedAt: Int)

object Swings {

    /**
     * Fractal swing points with a configurable window w: bar j is a swing high if its high is strictly above the
     * w highs before it and at least the w highs after it (ties resolve to the earliest bar); swing lows mirrored.
     * Sorted by confirmation bar, highs before lows on the same bar.
     */
    fun pivots(c: List<Candle>, w: Int): List<Swing> {
        val out = mutableListOf<Swing>()
        for (j in w until c.size - w) {
            var hi = true; var lo = true
            for (k in j - w until j) { if (c[k].high >= c[j].high) hi = false; if (c[k].low <= c[j].low) lo = false }
            for (k in j + 1..j + w) { if (c[k].high > c[j].high) hi = false; if (c[k].low < c[j].low) lo = false }
            if (hi) out += Swing(j, c[j].high, true, j + w)
            if (lo) out += Swing(j, c[j].low, false, j + w)
        }
        return out
    }

    /**
     * Incremental zigzag over confirmed pivots: points alternate high/low. A pivot of the same kind as the last
     * point replaces it if more extreme; an opposite pivot is appended only if the move is ≥ minSwingAtr × ATR.
     * Feeding pivots in confirmation order makes the zigzag at bar t depend only on bars ≤ t.
     */
    class ZigZag(private val atr: List<Double?>, private val minSwingAtr: Double) {
        val points = mutableListOf<Swing>()

        /** Returns true if the zigzag changed. */
        fun add(p: Swing): Boolean {
            val last = points.lastOrNull()
            if (last == null) { points += p; return true }
            if (last.high == p.high) {
                val better = if (p.high) p.price > last.price else p.price < last.price
                if (better) { points[points.lastIndex] = p; return true }
                return false
            }
            val a = atr.getOrNull(p.index) ?: return false
            if (abs(p.price - last.price) >= minSwingAtr * a) { points += p; return true }
            return false
        }
    }
}

/** Straight line through (x0, y0) with [slope] per bar. */
data class TrendLine(val x0: Int, val y0: Double, val slope: Double) {
    fun at(i: Int) = y0 + slope * (i - x0)

    companion object {
        fun through(a: Swing, b: Swing) = TrendLine(a.index, a.price, (b.price - a.price) / (b.index - a.index).coerceAtLeast(1))

        /** Least-squares fit through the points (x = bar index). */
        fun fit(pts: List<Pair<Int, Double>>): TrendLine {
            if (pts.size == 1) return TrendLine(pts[0].first, pts[0].second, 0.0)
            val n = pts.size.toDouble()
            val mx = pts.sumOf { it.first.toDouble() } / n
            val my = pts.sumOf { it.second } / n
            var sxy = 0.0; var sxx = 0.0
            for ((x, y) in pts) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx) }
            val slope = if (sxx == 0.0) 0.0 else sxy / sxx
            return TrendLine(pts[0].first, my + slope * (pts[0].first - mx), slope)
        }

        /** Envelope: the least-squares slope, shifted so every point is on or below (upper) / above (lower) the line. */
        fun envelope(pts: List<Pair<Int, Double>>, upper: Boolean): TrendLine {
            val f = fit(pts)
            val shift = if (upper) pts.maxOf { it.second - f.at(it.first) } else pts.minOf { it.second - f.at(it.first) }
            return f.copy(y0 = f.y0 + shift)
        }
    }
}

/** Per-bar context shared by every detector. All series are causal. */
class Context(val c: List<Candle>, val tf: Timeframe, val cfg: PatternConfig, private val ignoreVolume: Boolean = false) {
    val n = c.size
    val atr: List<Double?> = VolatilityCalc.atr(c, 14)
    /** Indices: providers report an aggregate, not traded shares, so volume is ignored (as in the Phase 3 engine). */
    val hasVolume = !ignoreVolume && c.takeLast(60).count { it.volume > 0 } >= 30
    private val volAvg20: List<Double?> = Rolling.sma(c.map { it.volume.toDouble() }, 20)

    /** ATR at bar i, falling back to the nearest earlier value. */
    fun atrAt(i: Int): Double {
        var k = i.coerceAtMost(n - 1)
        while (k >= 0) { atr[k]?.let { return it }; k-- }
        return (c.getOrNull(i)?.let { it.high - it.low } ?: 1.0).coerceAtLeast(1e-9)
    }

    /**
     * Volume of bar i ÷ average of the 20 bars BEFORE it (the bar itself excluded). Null when those bars carry
     * no volume (indices). Deliberately independent of [hasVolume], which looks at the latest bars.
     */
    fun relativeVolume(i: Int): Double? {
        if (ignoreVolume || i < 1) return null
        val avg = volAvg20.getOrNull(i - 1) ?: return null
        return if (avg <= 0) null else c[i].volume / avg
    }

    /** Average volume of the second half of [from, to] ÷ first half (bars ≤ to only). */
    fun volumeTrend(from: Int, to: Int): Double? {
        if (ignoreVolume || to - from < 6) return null
        val mid = (from + to) / 2
        val a = (from until mid).map { c[it].volume.toDouble() }.average()
        val b = (mid..to).map { c[it].volume.toDouble() }.average()
        return if (a <= 0) null else b / a
    }

    /** Direction of the [bars] closes before bar [i] (exclusive), in ATR: ≥ +[atrs] up, ≤ −[atrs] down. */
    fun trendBefore(i: Int, bars: Int, atrs: Double = 1.0): Bias {
        val a = i - 1; val b = i - 1 - bars
        if (b < 0) return Bias.NEUTRAL
        val d = (c[a].close - c[b].close) / atrAt(a)
        return when { d >= atrs -> Bias.BULLISH; d <= -atrs -> Bias.BEARISH; else -> Bias.NEUTRAL }
    }

    val higherTimeframe: Timeframe? = higherOf(tf)
    private val htf: List<Bias?> = higherTimeframeTrend(c, tf)
    fun htfAt(i: Int): Bias? = htf.getOrNull(i)

    companion object {
        fun higherOf(tf: Timeframe): Timeframe? = when {
            tf.intraday -> Timeframe.D1
            tf == Timeframe.D1 -> Timeframe.W1
            tf == Timeframe.W1 -> Timeframe.MN1
            else -> null
        }

        /**
         * Higher-timeframe trend for each base bar, from COMPLETED higher-timeframe bars only (those that ended
         * before the base bar's own higher-timeframe bar began), so no future base bars leak in.
         * Rule on the higher-timeframe closes: +1 each for close > EMA10, EMA10 rising over 3 bars, EMA10 > EMA30
         * (−1 for the opposite); ≥ +2 bullish, ≤ −2 bearish, else neutral. Null with < 10 completed bars.
         */
        fun higherTimeframeTrend(c: List<Candle>, tf: Timeframe): List<Bias?> {
            val h = higherOf(tf) ?: return List(c.size) { null }
            val cal = MarketCalendar()
            val agg = Resampler.aggregate(c, h, cal)
            val closes = agg.map { it.close }
            val e10 = Rolling.ema(closes, 10)
            val e30 = Rolling.ema(closes, 30)
            val trend = agg.indices.map { k ->
                val e = e10[k] ?: return@map null
                val prev = e10.getOrNull(k - 3)
                var s = if (closes[k] > e) 1 else if (closes[k] < e) -1 else 0
                if (prev != null) s += if (e > prev) 1 else if (e < prev) -1 else 0
                e30[k]?.let { s += if (e > it) 1 else if (e < it) -1 else 0 }
                when { s >= 2 -> Bias.BULLISH; s <= -2 -> Bias.BEARISH; else -> Bias.NEUTRAL }
            }
            val starts = agg.map { it.time }
            return c.map { k ->
                val b = cal.bucketStart(k.time, h) ?: return@map null
                val pos = starts.binarySearch(b).let { if (it >= 0) it else -it - 1 }   // index of the base bar's own bucket
                trend.getOrNull(pos - 1)
            }
        }
    }
}
