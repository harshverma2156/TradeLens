package com.tradelens.trade

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.patterns.DetectedPattern
import com.tradelens.patterns.PatternKind
import com.tradelens.patterns.PatternReport
import com.tradelens.patterns.PatternStatus
import com.tradelens.patterns.Swings
import com.tradelens.ta.Bias
import com.tradelens.ta.LevelsCalc
import com.tradelens.ta.TechnicalReport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A resistance that a close crossed recently: the breakout plan's trigger, then its retest level. */
data class Breakout(val level: Double, val index: Int, val relativeVolume: Double?, val label: String, val pattern: DetectedPattern? = null)

/**
 * Every price level the engine reasons about, built from closed bars only.
 *
 *  [levels]      support/resistance-type levels that may form an entry or a target zone;
 *  [projections] target-only levels (Fibonacci extensions, pattern measured moves): never support for an entry;
 *  [breakouts]   resistances closed through in the last [EngineConfig.breakoutLookbackBars] bars, newest first.
 */
class LevelMap(val levels: List<Level>, val projections: List<Level>, val breakouts: List<Breakout>, val swingLows: List<Pair<Int, Double>>) {

    companion object {
        fun build(c: List<Candle>, tech: TechnicalReport, patterns: PatternReport?, price: Double, atr: Double, cfg: EngineConfig): LevelMap {
            val n = c.size
            val i = n - 1
            val levels = mutableListOf<Level>()
            val proj = mutableListOf<Level>()
            fun add(p: Double?, src: LevelSource, detail: String, w: Double = src.weight) { if (p != null && p.isFinite() && p > 0) levels += Level(p, src, detail, weight = w) }

            // Moving averages
            add(tech.overlays.ema[20]?.getOrNull(i), LevelSource.EMA20, "20-day exponential average")
            add(tech.overlays.ema[50]?.getOrNull(i), LevelSource.EMA50, "50-day exponential average")
            add(tech.overlays.ema[200]?.getOrNull(i), LevelSource.EMA200, "200-day exponential average")

            // Fibonacci retracements of the dominant swing (0% and 100% are the swing extremes, covered below)
            tech.overlays.fibRetracement.filter { it.label in setOf("38.2%", "50.0%", "61.8%", "78.6%") }.forEach {
                add(it.price, LevelSource.FIBONACCI, "${it.label} retracement", if (it.label == "61.8%") 1.5 else if (it.label == "78.6%") 0.8 else 1.2)
            }
            tech.overlays.fibExtension.forEach { proj += Level(it.price, LevelSource.FIB_EXTENSION, "${it.label} extension of the last swing") }

            // Volume profile, VWAPs
            tech.overlays.profile?.let { p ->
                add(p.poc, LevelSource.POC, "most traded price of the last 126 bars")
                add(p.valueAreaHigh, LevelSource.VALUE_AREA_HIGH, "top of the 70% value area")
                add(p.valueAreaLow, LevelSource.VALUE_AREA_LOW, "bottom of the 70% value area")
            }
            tech.overlays.vwap.getOrNull(i)?.let { add(it, LevelSource.VWAP, "today's volume-weighted average price") }
            tech.overlays.anchoredVwap.getOrNull(i)?.let { add(it, LevelSource.ANCHORED_VWAP, "average price paid since the swing ${tech.overlays.anchorIndex?.let { a -> "on ${MarketCalendar.date(c[a].time)}" } ?: ""}") }

            // Historical support/resistance (clustered swing points, weighted by touches)
            val window = c.takeLast(min(n, 250))
            LevelsCalc.supportResistance(window).forEach { s ->
                val w = 1.0 + 0.5 * min(s.touches, 3)
                if (s.price <= price) add(s.price, LevelSource.SWING_SUPPORT, "touched ${s.touches}× in the last year", w)
                else add(s.price, LevelSource.SWING_RESISTANCE, "touched ${s.touches}× in the last year", w)
            }

            // Previous swing highs and lows (5-bar fractals, confirmed)
            val piv = Swings.pivots(c, 5).filter { it.index >= n - 250 }
            piv.filter { it.high }.takeLast(5).forEach { add(it.price, LevelSource.SWING_HIGH, "swing high on ${MarketCalendar.date(c[it.index].time)}") }
            val lows = piv.filter { !it.high }.takeLast(5)
            lows.forEach { add(it.price, LevelSource.SWING_LOW, "swing low on ${MarketCalendar.date(c[it.index].time)}") }

            // 52-week high
            if (n >= 30) {
                val hi = c.takeLast(min(n, 252)).maxOf { it.high }
                if (hi > price) add(hi, LevelSource.HIGH_52W, "highest price of the last year")
            }

            // Supply and demand zones
            levels += supplyDemand(c, tech.oscillators.atr, cfg)

            // Chart patterns (Phase 4)
            patterns?.patterns.orEmpty().filter { it.type.kind != PatternKind.CANDLESTICK && !it.closed }.forEach { p ->
                when {
                    p.status == PatternStatus.CONFIRMED && p.direction == Bias.BULLISH -> {
                        levels += Level(p.breakoutLevel, LevelSource.BREAKOUT_RETEST, "retest of the ${p.name} breakout")
                        p.targets.forEach { t -> proj += Level(t.price, LevelSource.MEASURED_MOVE, "${p.name} ${t.label}") }
                    }
                    p.status == PatternStatus.FORMING && p.direction != Bias.BEARISH -> levels += Level(p.breakoutLevel, LevelSource.PATTERN, "${p.name} breakout level (forming)")
                    p.direction == Bias.BEARISH -> levels += Level(p.breakoutLevel, LevelSource.PATTERN, "${p.name} ${if (p.status == PatternStatus.CONFIRMED) "broken support, now resistance" else "support that would confirm it"}")
                }
            }

            // Recent breakouts through resistance (or a just-confirmed bullish pattern)
            val brk = mutableListOf<Breakout>()
            patterns?.patterns.orEmpty().filter { it.type.kind != PatternKind.CANDLESTICK && it.status == PatternStatus.CONFIRMED && it.direction == Bias.BULLISH && (it.confirmedIndex ?: -1) >= n - cfg.breakoutLookbackBars }
                .forEach { p -> brk += Breakout(p.breakoutLevel, p.confirmedIndex!!, p.volume.breakoutRelativeVolume, "${p.name} breakout", p) }
            val resist = levels.filter { it.source == LevelSource.SWING_RESISTANCE || it.source == LevelSource.SWING_SUPPORT || it.source == LevelSource.SWING_HIGH || it.source == LevelSource.SUPPLY_ZONE || it.source == LevelSource.HIGH_52W }
            for (j in max(1, n - cfg.breakoutLookbackBars) until n) {
                val a = tech.oscillators.atr.getOrNull(j - 1) ?: continue
                for (l in resist) {
                    // (A 5-bar fractal needs 5 later bars, so no swing high inside the lookback can be the breakout bar's own.)
                    val top = l.high
                    if (c[j - 1].close <= top && c[j].close > top + cfg.confirmBufferAtr * a && (j - 20 until j).any { k -> k >= 0 && c[k].high >= top - 0.25 * a })
                        brk += Breakout(top, j, relVol(c, j), "close above ${l.source.label.lowercase()} ${f2(top)}")
                }
            }
            val breakouts = brk.sortedWith(compareByDescending<Breakout> { it.index }.thenByDescending { it.level }).distinctBy { (it.level / max(atr, 1e-9) * 2).toInt() }
            breakouts.forEach { b -> if (levels.none { it.source == LevelSource.BREAKOUT_RETEST && abs(it.price - b.level) < 0.2 * atr }) levels += Level(b.level, LevelSource.BREAKOUT_RETEST, "retest of the ${b.label}") }

            return LevelMap(levels, proj, breakouts, lows.map { it.index to it.price })
        }

        /**
         * Demand zone: a small-bodied "base" candle (body ≤ 0.8 ATR) followed by an impulse: the next close is above the
         * base's high and within 3 bars price runs ≥ 1.5 ATR beyond it. Zone = base low → base body top. Supply mirrored.
         * A zone dies once a later close goes through it; each later visit (test) weakens it.
         */
        fun supplyDemand(c: List<Candle>, atr: List<Double?>, cfg: EngineConfig): List<Level> {
            val n = c.size
            val demand = mutableListOf<Level>()
            val supply = mutableListOf<Level>()
            for (j in max(1, n - 250) until n - 3) {
                val a = atr.getOrNull(j) ?: continue
                val b = c[j]
                if (abs(b.close - b.open) > 0.8 * a) continue
                val after = (j + 1..j + 3).map { c[it] }
                val bodyTop = max(b.open, b.close); val bodyBot = min(b.open, b.close)
                if (c[j + 1].close > b.high && after.maxOf { it.close } - b.high >= 1.5 * a && bodyTop > b.low) {
                    val later = (j + 4 until n)
                    if (later.none { c[it].close < b.low }) {
                        val tests = countVisits(later.map { c[it].low <= bodyTop })
                        demand += Level((b.low + bodyTop) / 2, LevelSource.DEMAND_ZONE, "base on ${MarketCalendar.date(b.time)} before a ${f1((after.maxOf { it.close } - b.high) / a)}-ATR rally, tested ${tests}×",
                            b.low, bodyTop, max(1.0, LevelSource.DEMAND_ZONE.weight - 0.5 * tests))
                    }
                }
                if (c[j + 1].close < b.low && b.low - after.minOf { it.close } >= 1.5 * a && b.high > bodyBot) {
                    val later = (j + 4 until n)
                    if (later.none { c[it].close > b.high }) {
                        val tests = countVisits(later.map { c[it].high >= bodyBot })
                        supply += Level((bodyBot + b.high) / 2, LevelSource.SUPPLY_ZONE, "base on ${MarketCalendar.date(b.time)} before a ${f1((b.low - after.minOf { it.close }) / a)}-ATR drop, tested ${tests}×",
                            bodyBot, b.high, max(1.0, LevelSource.SUPPLY_ZONE.weight - 0.5 * tests))
                    }
                }
            }
            return demand.takeLast(6) + supply.takeLast(6)
        }

        /** Number of separate visits (runs of consecutive true values). */
        private fun countVisits(touch: List<Boolean>) = touch.indices.count { touch[it] && (it == 0 || !touch[it - 1]) }

        fun relVol(c: List<Candle>, j: Int): Double? {
            if (j < 20) return null
            val avg = (j - 20 until j).map { c[it].volume.toDouble() }.average()
            return if (avg > 0) c[j].volume / avg else null
        }

        fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
        fun f2(v: Double) = String.format(java.util.Locale.US, "%,.2f", v)
    }
}
