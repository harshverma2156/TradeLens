package com.tradelens.analysis

import com.tradelens.data.model.Candle
import com.tradelens.ta.LevelsCalc
import com.tradelens.ta.MomentumCalc
import com.tradelens.ta.Rolling
import com.tradelens.ta.TrendCalc
import com.tradelens.ta.VolatilityCalc
import com.tradelens.ta.VolumeCalc
import kotlin.math.max
import kotlin.math.min

/**
 * Facade kept for the rating engine (AnalysisEngine). Every formula is implemented once, in the
 * `com.tradelens.ta` calculation services; these functions only delegate. Lists are aligned to the input,
 * null = not enough data yet.
 */
object Ta {
    fun sma(v: List<Double>, p: Int): List<Double?> = Rolling.sma(v, p)
    fun ema(v: List<Double?>, p: Int): List<Double?> = Rolling.ema(v, p)
    fun wilder(v: List<Double?>, p: Int): List<Double?> = Rolling.rma(v, p)
    fun rsi(c: List<Double>, p: Int = 14): List<Double?> = MomentumCalc.rsi(c, p)

    data class Macd(val line: List<Double?>, val signal: List<Double?>, val hist: List<Double?>)
    fun macd(c: List<Double>, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd =
        MomentumCalc.macd(c, fast, slow, sig).let { Macd(it.line, it.signal, it.hist) }

    fun trueRange(c: List<Candle>): List<Double> = VolatilityCalc.trueRange(c)
    fun atr(c: List<Candle>, p: Int = 14): List<Double?> = VolatilityCalc.atr(c, p)

    data class Bands(val mid: List<Double?>, val upper: List<Double?>, val lower: List<Double?>)
    fun bollinger(c: List<Double>, p: Int = 20, k: Double = 2.0): Bands =
        VolatilityCalc.bollinger(c, p, k).let { Bands(it.mid, it.upper, it.lower) }

    data class Adx(val adx: List<Double?>, val plusDi: List<Double?>, val minusDi: List<Double?>)
    fun adx(c: List<Candle>, p: Int = 14): Adx = TrendCalc.dmi(c, p).let { Adx(it.adx, it.plusDi, it.minusDi) }

    data class Level(val price: Double, val touches: Int)
    fun levels(c: List<Candle>, window: Int = 3, tolerancePct: Double = 1.5): List<Level> =
        LevelsCalc.supportResistance(c, window, tolerancePct).map { Level(it.price, it.touches) }

    data class Supertrend(val line: List<Double?>, val up: List<Boolean?>)
    fun supertrend(c: List<Candle>, p: Int = 10, k: Double = 3.0): Supertrend =
        TrendCalc.supertrend(c, p, k).let { Supertrend(it.line, it.up) }

    fun obv(c: List<Candle>): List<Double> = VolumeCalc.obv(c)
    fun mfi(c: List<Candle>, p: Int = 14): List<Double?> = VolumeCalc.mfi(c, p)

    fun roc(v: List<Double>, p: Int): List<Double?> =
        v.indices.map { i -> if (i >= p && v[i - p] > 0) v[i] / v[i - p] - 1 else null }

    /** Calendar day in IST, used to align series from different symbols. */
    fun dayKey(timeMillis: Long): Long = (timeMillis + 19_800_000L) / 86_400_000L

    data class Weekly(val candles: List<Candle>, val weekOfDay: IntArray)

    /** Resamples daily candles into Monday-based weeks. weekOfDay[i] = index of the week holding day i. */
    fun weekly(c: List<Candle>): Weekly {
        val out = mutableListOf<Candle>()
        val map = IntArray(c.size)
        var curWeek = Long.MIN_VALUE
        for (i in c.indices) {
            val d = dayKey(c[i].time)
            val week = Math.floorDiv(d + 3, 7L)  // epoch day 0 was a Thursday; +3 makes weeks start on Monday
            if (week != curWeek) {
                out += c[i]
                curWeek = week
            } else {
                val w = out.last()
                out[out.size - 1] = Candle(w.time, w.open, max(w.high, c[i].high), min(w.low, c[i].low), c[i].close, w.volume + c[i].volume)
            }
            map[i] = out.size - 1
        }
        return Weekly(out, map)
    }

    data class Profile(val poc: Double, val valueLow: Double, val valueHigh: Double)

    fun volumeProfile(c: List<Candle>, bins: Int = 40): Profile? =
        VolumeCalc.volumeProfile(c, bins)?.let { Profile(it.poc, it.valueAreaLow, it.valueAreaHigh) }

    data class Swing(val lowIndex: Int, val low: Double, val highIndex: Int, val high: Double)

    /** The dominant swing in [c]: for an up-leg, the lowest low before the highest high (mirrored for down-legs). */
    fun lastSwing(c: List<Candle>, up: Boolean): Swing? {
        if (c.size < 10) return null
        return if (up) {
            val hi = c.indices.maxBy { c[it].high }
            if (hi < 3) return null
            val lo = (0 until hi).minBy { c[it].low }
            Swing(lo, c[lo].low, hi, c[hi].high)
        } else {
            val lo = c.indices.minBy { c[it].low }
            if (lo < 3) return null
            val hi = (0 until lo).maxBy { c[it].high }
            Swing(lo, c[lo].low, hi, c[hi].high)
        }
    }

    /** Volume-weighted average price anchored at [from] (inclusive), at the last candle. */
    fun anchoredVwap(c: List<Candle>, from: Int): Double? = VolumeCalc.anchoredVwap(c, from).lastOrNull()
}
