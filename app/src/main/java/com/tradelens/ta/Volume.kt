package com.tradelens.ta

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar

/** Volume indicators. Volumes are summed as Double, so very large volumes can't overflow. */
object VolumeCalc {

    /** On-Balance Volume (Joseph Granville): OBV_0 = 0; ±volume by close direction; unchanged close adds 0. */
    fun obv(c: List<Candle>): List<Double> {
        var v = 0.0
        return c.indices.map { i ->
            if (i > 0) when {
                c[i].close > c[i - 1].close -> v += c[i].volume
                c[i].close < c[i - 1].close -> v -= c[i].volume
            }
            v
        }
    }

    /**
     * Accumulation/Distribution line (Marc Chaikin): CLV = ((C − L) − (H − C)) / (H − L); AD = Σ CLV × volume.
     * H = L (no range) → CLV = 0.
     */
    fun accumulationDistribution(c: List<Candle>): List<Double> {
        var ad = 0.0
        return c.map { k ->
            val range = k.high - k.low
            val clv = if (range == 0.0) 0.0 else ((k.close - k.low) - (k.high - k.close)) / range
            ad += clv * k.volume
            ad
        }
    }

    /**
     * Money Flow Index (Gene Quong & Avrum Soudack), n = 14. Raw flow = TP × volume, positive when TP rose,
     * negative when it fell, ignored when unchanged. MFI = 100 − 100 / (1 + ΣPos/ΣNeg).
     * No negative flow → 100; no flow at all → 50 (neutral).
     */
    fun mfi(c: List<Candle>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(c.size) { null }
        val tp = c.map { (it.high + it.low + it.close) / 3 }
        for (i in n until c.size) {
            var pos = 0.0; var neg = 0.0
            for (j in i - n + 1..i) {
                val flow = tp[j] * c[j].volume
                if (tp[j] > tp[j - 1]) pos += flow else if (tp[j] < tp[j - 1]) neg += flow
            }
            out[i] = when { neg == 0.0 && pos == 0.0 -> 50.0; neg == 0.0 -> 100.0; else -> 100 - 100 / (1 + pos / neg) }
        }
        return out
    }

    /**
     * Session VWAP: Σ(TP × V) / ΣV, reset at each IST trading day. Only meaningful for intraday bars;
     * returns all-null for daily and longer bars (use [anchoredVwap] there).
     */
    fun sessionVwap(c: List<Candle>, intraday: Boolean): List<Double?> {
        if (!intraday) return List(c.size) { null }
        var day: java.time.LocalDate? = null
        var pv = 0.0; var vv = 0.0
        return c.map { k ->
            val d = MarketCalendar.date(k.time)
            if (d != day) { day = d; pv = 0.0; vv = 0.0 }
            pv += (k.high + k.low + k.close) / 3 * k.volume
            vv += k.volume
            if (vv > 0) pv / vv else null
        }
    }

    /** Anchored VWAP from bar [from] (inclusive): Σ(TP × V) / ΣV over bars from..i. Null before the anchor. */
    fun anchoredVwap(c: List<Candle>, from: Int): List<Double?> {
        var pv = 0.0; var vv = 0.0
        return c.indices.map { i ->
            if (i < from) null else {
                val k = c[i]
                pv += (k.high + k.low + k.close) / 3 * k.volume
                vv += k.volume
                if (vv > 0) pv / vv else null
            }
        }
    }

    data class Profile(
        val poc: Double, val valueAreaHigh: Double, val valueAreaLow: Double,
        val binLow: Double, val binSize: Double, val volumes: DoubleArray,
    )

    /**
     * Volume profile over [c]: each bar's volume is spread evenly across the price bins its H–L range touches.
     * POC = midpoint of the busiest bin. Value area = the POC plus neighbouring bins, adding whichever side
     * has more volume, until it holds [valueArea] (70%) of the total (CBOT Market Profile convention).
     * Null with no volume or no price range.
     */
    fun volumeProfile(c: List<Candle>, bins: Int = 40, valueArea: Double = 0.70): Profile? {
        if (c.isEmpty()) return null
        val lo = c.minOf { it.low }
        val hi = c.maxOf { it.high }
        if (!(hi > lo)) return null
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
        var l = poc; var h = poc; var acc = vol[poc]
        while (acc < total * valueArea && (l > 0 || h < bins - 1)) {
            val nl = if (l > 0) vol[l - 1] else -1.0
            val nh = if (h < bins - 1) vol[h + 1] else -1.0
            if (nh >= nl) { h++; acc += vol[h] } else { l--; acc += vol[l] }
        }
        return Profile(lo + (poc + 0.5) * step, lo + (h + 1) * step, lo + l * step, lo, step, vol)
    }
}
