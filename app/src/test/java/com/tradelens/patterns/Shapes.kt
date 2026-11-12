package com.tradelens.patterns

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.Timeframe
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Builds deterministic OHLCV series for pattern tests.
 *
 * [path] draws closes along straight lines between waypoints (bar, price). Each bar opens at the previous close
 * and has a small wick, so every waypoint becomes a clean swing point and nothing else does. [mirror] reflects a
 * series in price (p → 200 − p): a top becomes a bottom, a hammer a shooting star, so each bearish fixture is
 * exactly the reflection of a bullish one.
 */
object Shapes {
    val START: LocalDate = LocalDate.of(2023, 1, 2)

    /** Weekday 09:15 IST timestamps from [START]. */
    fun days(n: Int): List<Long> {
        val out = ArrayList<Long>(n)
        var d = START
        while (out.size < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += MarketCalendar.at(d, MarketCalendar.OPEN)
            d = d.plusDays(1)
        }
        return out
    }

    fun path(vararg pts: Pair<Int, Double>, wick: Double = 0.002, volume: Long = 1_000_000, volumeBoost: Map<IntRange, Double> = emptyMap()): List<Candle> {
        val last = pts.last().first
        val closes = DoubleArray(last + 1)
        for ((a, b) in pts.toList().zipWithNext()) for (i in a.first..b.first) closes[i] = a.second + (b.second - a.second) * (i - a.first) / (b.first - a.first)
        val t = days(last + 1)
        return (0..last).map { i ->
            val c = closes[i]; val o = if (i == 0) c else closes[i - 1]
            val v = volume * (volumeBoost.entries.firstOrNull { i in it.key }?.value ?: 1.0)
            // Wick proportional to the body edge it extends, so the waypoint bar's extreme is never beaten by its neighbour.
            Candle(t[i], o, maxOf(o, c) * (1 + wick), minOf(o, c) * (1 - wick), c, v.toLong())
        }
    }

    fun mirror(c: List<Candle>) = c.map { Candle(it.time, 200 - it.open, 200 - it.low, 200 - it.high, 200 - it.close, it.volume) }

    fun detect(c: List<Candle>, cfg: PatternConfig = PatternConfig(), tf: Timeframe = Timeframe.D1) = PatternEngine.detect("TEST.NS", tf, c, Long.MAX_VALUE, cfg)

    fun PatternReport.only(type: PatternType) = patterns.filter { it.type == type }
    fun PatternReport.one(type: PatternType): DetectedPattern {
        val p = only(type)
        check(p.size == 1) { "expected exactly one $type, got ${p.size}: ${patterns.map { "${it.type}/${it.status}" }}" }
        return p.single()
    }
}
