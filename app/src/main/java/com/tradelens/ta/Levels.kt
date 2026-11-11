package com.tradelens.ta

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.Timeframe

/** Price levels: Fibonacci, pivots, support/resistance, swings. */
object LevelsCalc {

    data class Swing(val lowIndex: Int, val low: Double, val highIndex: Int, val high: Double) {
        val up get() = highIndex > lowIndex
        val range get() = high - low
    }

    /**
     * The dominant swing in the last [lookback] bars: the highest high and lowest low of the window, ordered
     * by which came first. up = the low came first (an up-leg). Indices refer to [c].
     */
    fun dominantSwing(c: List<Candle>, lookback: Int = 126): Swing? {
        if (c.size < 10) return null
        val from = maxOf(0, c.size - lookback)
        val w = from until c.size
        val hi = w.maxBy { c[it].high }
        val lo = w.minBy { c[it].low }
        if (hi == lo) return null
        return Swing(lo, c[lo].low, hi, c[hi].high)
    }

    data class Level(val label: String, val price: Double)

    val RETRACEMENTS = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)
    val EXTENSIONS = listOf(1.272, 1.618, 2.0, 2.618)

    /**
     * Fibonacci retracement of a swing. Up-leg: level(r) = high − r × range (0% = the high, 100% = the low).
     * Down-leg: level(r) = low + r × range.
     */
    fun fibRetracement(s: Swing): List<Level> = RETRACEMENTS.map { r ->
        Level("${pct(r)}", if (s.up) s.high - r * s.range else s.low + r * s.range)
    }

    /**
     * Two-point Fibonacci extension, measured from the swing start: up-leg level(r) = low + r × range
     * (127.2%, 161.8%, 200%, 261.8%); down-leg level(r) = high − r × range.
     * Variant (three-point / ABC): C ± r × (B − A) from a pullback point C; not used here.
     */
    fun fibExtension(s: Swing): List<Level> = EXTENSIONS.map { r ->
        Level("${pct(r)}", if (s.up) s.low + r * s.range else s.high - r * s.range)
    }

    enum class PivotType { CLASSIC, FIBONACCI, CAMARILLA }

    data class Pivots(val type: PivotType, val basis: String, val pivot: Double, val resistances: List<Double>, val supports: List<Double>)

    /**
     * Floor pivots from the previous completed period's high, low and close:
     *  Classic:   P = (H+L+C)/3; R1 = 2P − L; S1 = 2P − H; R2 = P + (H−L); S2 = P − (H−L); R3 = H + 2(P−L); S3 = L − 2(H−P)
     *  Fibonacci: P as classic; R/S_n = P ± {0.382, 0.618, 1.0} × (H−L)
     *  Camarilla: R/S_n = C ± {1.1/12, 1.1/6, 1.1/4} × (H−L)
     */
    fun pivots(h: Double, l: Double, c: Double, type: PivotType = PivotType.CLASSIC, basis: String = ""): Pivots {
        val p = (h + l + c) / 3
        val r = h - l
        return when (type) {
            PivotType.CLASSIC -> Pivots(type, basis, p, listOf(2 * p - l, p + r, h + 2 * (p - l)), listOf(2 * p - h, p - r, l - 2 * (h - p)))
            PivotType.FIBONACCI -> Pivots(type, basis, p, listOf(0.382, 0.618, 1.0).map { p + it * r }, listOf(0.382, 0.618, 1.0).map { p - it * r })
            PivotType.CAMARILLA -> Pivots(type, basis, p, listOf(1.1 / 12, 1.1 / 6, 1.1 / 4).map { c + it * r }, listOf(1.1 / 12, 1.1 / 6, 1.1 / 4).map { c - it * r })
        }
    }

    /**
     * Pivots for the period the LAST bar belongs to, from the previous completed period (never the current one):
     * intraday bars → previous trading day; daily → previous week; weekly → previous month; monthly → previous year.
     */
    fun pivotsFor(c: List<Candle>, tf: Timeframe, type: PivotType = PivotType.CLASSIC): Pivots? {
        if (c.size < 2) return null
        fun key(t: Long): Long {
            val d = MarketCalendar.date(t)
            return when {
                tf.intraday -> d.toEpochDay()
                tf == Timeframe.D1 -> d.toEpochDay() - (d.dayOfWeek.value - 1)          // Monday of the week
                tf == Timeframe.W1 -> d.year * 12L + d.monthValue
                else -> d.year.toLong()
            }
        }
        val cur = key(c.last().time)
        val prevBars = c.filter { key(it.time) < cur }
        if (prevBars.isEmpty()) return null
        val pk = key(prevBars.last().time)
        val period = prevBars.filter { key(it.time) == pk }
        val basis = when {
            tf.intraday -> "previous day"
            tf == Timeframe.D1 -> "previous week"
            tf == Timeframe.W1 -> "previous month"
            else -> "previous year"
        }
        return pivots(period.maxOf { it.high }, period.minOf { it.low }, period.last().close, type, basis)
    }

    data class SrLevel(val price: Double, val touches: Int)

    /**
     * Support/resistance from swing points: a bar is a swing high (low) if its high (low) is the extreme of the
     * ±[window] bars around it. Points within [tolerancePct] of each other are clustered; touches = cluster size.
     * A swing at bar j is only confirmed at bar j + window, so the last [window] bars never create levels (no look-ahead).
     */
    fun supportResistance(c: List<Candle>, window: Int = 3, tolerancePct: Double = 1.5): List<SrLevel> {
        val pts = mutableListOf<Double>()
        for (i in window until c.size - window) {
            var isHigh = true; var isLow = true
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
        val out = mutableListOf<SrLevel>()
        var group = mutableListOf(pts[0])
        for (k in 1 until pts.size) {
            val avg = group.average()
            if ((pts[k] - avg) / avg * 100 <= tolerancePct) group += pts[k]
            else { out += SrLevel(group.average(), group.size); group = mutableListOf(pts[k]) }
        }
        out += SrLevel(group.average(), group.size)
        return out
    }

    private fun pct(r: Double) = String.format(java.util.Locale.US, "%.1f%%", r * 100)
}
