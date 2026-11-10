package com.tradelens.analysis

import com.tradelens.data.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Technical indicators. Every function returns a list aligned to the input (null = not enough data yet). */
object Ta {

    fun sma(v: List<Double>, p: Int): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= p) sum -= v[i - p]
            if (i >= p - 1) out[i] = sum / p
        }
        return out
    }

    /** EMA seeded with an SMA; skips leading nulls so it can run on derived series (e.g. MACD line). */
    fun ema(v: List<Double?>, p: Int): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        val start = v.indexOfFirst { it != null }
        if (start < 0 || v.size - start < p) return out
        var seed = 0.0
        for (i in start until start + p) seed += v[i] ?: return out
        var prev = seed / p
        out[start + p - 1] = prev
        val k = 2.0 / (p + 1)
        for (i in start + p until v.size) {
            val x = v[i] ?: continue
            prev = x * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder's smoothing (used by RSI, ATR, ADX). */
    fun wilder(v: List<Double?>, p: Int): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        val start = v.indexOfFirst { it != null }
        if (start < 0 || v.size - start < p) return out
        var seed = 0.0
        for (i in start until start + p) seed += v[i] ?: return out
        var prev = seed / p
        out[start + p - 1] = prev
        for (i in start + p until v.size) {
            val x = v[i] ?: continue
            prev = (prev * (p - 1) + x) / p
            out[i] = prev
        }
        return out
    }

    fun rsi(c: List<Double>, p: Int = 14): List<Double?> {
        val out = MutableList<Double?>(c.size) { null }
        if (c.size <= p) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..p) {
            val d = c[i] - c[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= p; loss /= p
        out[p] = rsiValue(gain, loss)
        for (i in p + 1 until c.size) {
            val d = c[i] - c[i - 1]
            gain = (gain * (p - 1) + max(d, 0.0)) / p
            loss = (loss * (p - 1) + max(-d, 0.0)) / p
            out[i] = rsiValue(gain, loss)
        }
        return out
    }

    private fun rsiValue(gain: Double, loss: Double) = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)

    data class Macd(val line: List<Double?>, val signal: List<Double?>, val hist: List<Double?>)

    fun macd(c: List<Double>, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val ef = ema(c, fast)
        val es = ema(c, slow)
        val line = c.indices.map { i -> val a = ef[i]; val b = es[i]; if (a != null && b != null) a - b else null }
        val signal = ema(line, sig)
        val hist = c.indices.map { i -> val a = line[i]; val b = signal[i]; if (a != null && b != null) a - b else null }
        return Macd(line, signal, hist)
    }

    fun trueRange(c: List<Candle>): List<Double> = c.indices.map { i ->
        if (i == 0) c[0].high - c[0].low
        else maxOf(c[i].high - c[i].low, abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close))
    }

    fun atr(c: List<Candle>, p: Int = 14): List<Double?> = wilder(trueRange(c), p)

    data class Bands(val mid: List<Double?>, val upper: List<Double?>, val lower: List<Double?>)

    fun bollinger(c: List<Double>, p: Int = 20, k: Double = 2.0): Bands {
        val mid = sma(c, p)
        val upper = MutableList<Double?>(c.size) { null }
        val lower = MutableList<Double?>(c.size) { null }
        for (i in c.indices) {
            val m = mid[i] ?: continue
            var ss = 0.0
            for (j in i - p + 1..i) ss += (c[j] - m) * (c[j] - m)
            val sd = sqrt(ss / p)
            upper[i] = m + k * sd
            lower[i] = m - k * sd
        }
        return Bands(mid, upper, lower)
    }

    data class Adx(val adx: List<Double?>, val plusDi: List<Double?>, val minusDi: List<Double?>)

    fun adx(c: List<Candle>, p: Int = 14): Adx {
        val n = c.size
        val plusDm = MutableList<Double?>(n) { 0.0 }
        val minusDm = MutableList<Double?>(n) { 0.0 }
        for (i in 1 until n) {
            val up = c[i].high - c[i - 1].high
            val down = c[i - 1].low - c[i].low
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
        }
        val tr = wilder(trueRange(c), p)
        val pSm = wilder(plusDm, p)
        val mSm = wilder(minusDm, p)
        val plusDi = MutableList<Double?>(n) { null }
        val minusDi = MutableList<Double?>(n) { null }
        val dx = MutableList<Double?>(n) { null }
        for (i in 0 until n) {
            val t = tr[i] ?: continue
            val pp = pSm[i] ?: continue
            val mm = mSm[i] ?: continue
            if (t == 0.0) continue
            val pdi = 100 * pp / t
            val mdi = 100 * mm / t
            plusDi[i] = pdi
            minusDi[i] = mdi
            dx[i] = if (pdi + mdi == 0.0) 0.0 else 100 * abs(pdi - mdi) / (pdi + mdi)
        }
        return Adx(wilder(dx, p), plusDi, minusDi)
    }

    data class Level(val price: Double, val touches: Int)

    /** Support/resistance from swing highs/lows, clustered when they sit within [tolerancePct] of each other. */
    fun levels(c: List<Candle>, window: Int = 3, tolerancePct: Double = 1.5): List<Level> {
        val pts = mutableListOf<Double>()
        for (i in window until c.size - window) {
            var isHigh = true
            var isLow = true
            for (j in i - window..i + window) {
                if (j == i) continue
                if (c[j].high > c[i].high) isHigh = false
                if (c[j].low < c[i].low) isLow = false
            }
            if (isHigh) pts += c[i].high
            if (isLow) pts += c[i].low
        }
        if (pts.isEmpty()) return emptyList()
        pts.sort()
        val out = mutableListOf<Level>()
        var group = mutableListOf(pts[0])
        for (k in 1 until pts.size) {
            val avg = group.average()
            if ((pts[k] - avg) / avg * 100 <= tolerancePct) group += pts[k]
            else {
                out += Level(group.average(), group.size)
                group = mutableListOf(pts[k])
            }
        }
        out += Level(group.average(), group.size)
        return out
    }
    data class Supertrend(val line: List<Double?>, val up: List<Boolean?>)

    /** Supertrend (ATR [p], multiplier [k]). up[i] = true while the trend is up; line is the trailing stop. */
    fun supertrend(c: List<Candle>, p: Int = 10, k: Double = 3.0): Supertrend {
        val n = c.size
        val atr = atr(c, p)
        val line = MutableList<Double?>(n) { null }
        val up = MutableList<Boolean?>(n) { null }
        var fu = 0.0
        var fl = 0.0
        var trendUp = true
        var started = false
        for (i in 0 until n) {
            val a = atr[i] ?: continue
            val hl2 = (c[i].high + c[i].low) / 2
            val bu = hl2 + k * a
            val bl = hl2 - k * a
            if (!started) {
                fu = bu; fl = bl; trendUp = c[i].close >= hl2; started = true
            } else {
                val pc = c[i - 1].close
                fu = if (bu < fu || pc > fu) bu else fu
                fl = if (bl > fl || pc < fl) bl else fl
                trendUp = if (trendUp) c[i].close >= fl else c[i].close > fu
            }
            up[i] = trendUp
            line[i] = if (trendUp) fl else fu
        }
        return Supertrend(line, up)
    }

    /** On-balance volume. */
    fun obv(c: List<Candle>): List<Double> {
        val out = ArrayList<Double>(c.size)
        var v = 0.0
        for (i in c.indices) {
            if (i > 0) {
                if (c[i].close > c[i - 1].close) v += c[i].volume else if (c[i].close < c[i - 1].close) v -= c[i].volume
            }
            out += v
        }
        return out
    }

    /** Money Flow Index (volume-weighted RSI). */
    fun mfi(c: List<Candle>, p: Int = 14): List<Double?> {
        val n = c.size
        val out = MutableList<Double?>(n) { null }
        val tp = c.map { (it.high + it.low + it.close) / 3 }
        for (i in p until n) {
            var pos = 0.0
            var neg = 0.0
            for (j in i - p + 1..i) {
                val flow = tp[j] * c[j].volume
                if (tp[j] > tp[j - 1]) pos += flow else if (tp[j] < tp[j - 1]) neg += flow
            }
            out[i] = if (neg == 0.0) (if (pos == 0.0) 50.0 else 100.0) else 100 - 100 / (1 + pos / neg)
        }
        return out
    }

    /** Rate of change over [p] bars as a fraction (0.1 = +10%). */
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

    /** Volume profile: the price with the most traded volume (POC) and the 70% value area. */
    fun volumeProfile(c: List<Candle>, bins: Int = 40): Profile? {
        if (c.isEmpty()) return null
        val lo = c.minOf { it.low }
        val hi = c.maxOf { it.high }
        if (hi <= lo) return null
        val step = (hi - lo) / bins
        val vol = DoubleArray(bins)
        for (k in c) {
            if (k.volume <= 0) continue
            val a = ((k.low - lo) / step).toInt().coerceIn(0, bins - 1)
            val b = ((k.high - lo) / step).toInt().coerceIn(0, bins - 1)
            val share = k.volume.toDouble() / (b - a + 1)
            for (j in a..b) vol[j] += share
        }
        val total = vol.sum()
        if (total <= 0) return null
        val poc = vol.indices.maxBy { vol[it] }
        var l = poc
        var h = poc
        var acc = vol[poc]
        while (acc < total * 0.7 && (l > 0 || h < bins - 1)) {
            val nl = if (l > 0) vol[l - 1] else -1.0
            val nh = if (h < bins - 1) vol[h + 1] else -1.0
            if (nh >= nl) { h++; acc += vol[h] } else { l--; acc += vol[l] }
        }
        return Profile(lo + (poc + 0.5) * step, lo + l * step, lo + (h + 1) * step)
    }

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

    /** Volume-weighted average price anchored at [from] (inclusive) through the last candle. */
    fun anchoredVwap(c: List<Candle>, from: Int): Double? {
        var pv = 0.0
        var v = 0.0
        for (i in from until c.size) {
            val tp = (c[i].high + c[i].low + c[i].close) / 3
            pv += tp * c[i].volume
            v += c[i].volume
        }
        return if (v > 0) pv / v else null
    }
}
