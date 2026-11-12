package com.tradelens.trade

import com.tradelens.data.model.Candle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Position sizing and reward/risk. Long positions (Indian cash market). */
object RiskCalc {

    fun roundDown(x: Double, tick: Double) = floor(x / tick + 1e-9) * tick
    fun roundUp(x: Double, tick: Double) = ceil(x / tick - 1e-9) * tick
    fun round(x: Double, tick: Double) = Math.round(x / tick) * tick

    /**
     * Quantity = the smallest of
     *   risk budget ÷ risk per share        (capital × risk% — what you can lose if the stop is hit),
     *   max position ÷ entry                (capital × max position% — exposure cap),
     *   capital ÷ entry                      (cash available).
     * Null when the stop is not below the entry (no definable risk).
     */
    fun size(entry: Double, stop: Double, targets: List<Double>, s: RiskSettings): RiskReward? {
        if (!(entry > 0) || !(stop < entry) || stop < 0) return null
        val risk = entry - stop
        val budget = s.capital * s.riskPercent / 100
        val byRisk = floor(budget / risk + 1e-9).toInt()
        val byExposure = floor(s.capital * s.maxPositionPercent / 100 / entry + 1e-9).toInt()
        val byCash = floor(s.capital / entry + 1e-9).toInt()
        val qty = max(0, minOf(byRisk, byExposure, byCash))
        val limitedBy = when (qty) {
            byRisk -> "risk budget (${fmt(s.riskPercent)}% of capital = ₹${fmt(budget)})"
            byExposure -> "max position size (${fmt(s.maxPositionPercent)}% of capital)"
            else -> "available capital"
        }
        val rewards = targets.map { it - entry }
        return RiskReward(
            entry = entry, stopLoss = stop, riskPerShare = risk,
            rewards = rewards, ratios = rewards.map { it / risk },
            stopPct = risk / entry * 100, targetPcts = rewards.map { it / entry * 100 },
            quantity = qty, capitalRequired = qty * entry,
            exposurePct = if (s.capital > 0) qty * entry / s.capital * 100 else 0.0,
            maxLoss = qty * risk, riskBudget = budget, limitedBy = limitedBy,
        )
    }

    private fun fmt(v: Double) = if (v == floor(v)) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%,.2f", v)
}

/** Stop-loss placement, structure-based targets, trailing stop and partial exits. */
object Exits {

    /**
     * Stop below the structure that defines the trade ([structureLow]: zone low, reaction low, breakout base),
     * minus a buffer. Too tight (< minStopAtr) is widened so ordinary noise doesn't trigger it; too wide
     * (> maxStopAtr) means the stop can't be placed logically, and the trade is rejected (null).
     */
    fun placeStop(entry: Double, structureLow: Double, atr: Double, cfg: EngineConfig): Pair<Double, String>? {
        if (!(atr > 0) || structureLow >= entry) return null
        val raw = structureLow - cfg.stopBufferAtr * atr
        val dist = entry - raw
        return when {
            dist > cfg.maxStopAtr * atr -> null
            dist < cfg.minStopAtr * atr -> (entry - cfg.minStopAtr * atr) to
                "Structure is only ${f1(dist / atr)} ATR below the entry, so the stop is widened to the minimum ${f1(cfg.minStopAtr)} ATR to survive normal noise."
            else -> raw to "${f1(cfg.stopBufferAtr)} ATR below the structure at ${f2(structureLow)}."
        }
    }

    /**
     * Targets from market structure only: resistance zones (just below them), pattern measured moves, Fibonacci
     * extensions, the 52-week high. Candidates closer than minTargetGapAtr to the entry, or within clusterAtr of a
     * chosen target, are skipped. Only when structure runs out are the remaining slots filled with ATR volatility
     * projections (2 ATR steps), and they are labelled as such.
     */
    fun targets(entry: Double, stop: Double, candidates: List<Level>, atr: Double, cfg: EngineConfig): List<Target> {
        val risk = entry - stop
        val chosen = mutableListOf<Level>()
        for (l in candidates.filter { it.price >= entry + cfg.minTargetGapAtr * atr }.sortedBy { it.price }) {
            val last = chosen.lastOrNull()
            if (last != null && l.price - last.price < cfg.clusterAtr * atr) continue
            chosen += l
            if (chosen.size == 3) break
        }
        while (chosen.size < 3) {
            val base = chosen.lastOrNull()?.price ?: entry
            chosen += Level(base + 2 * atr, LevelSource.VOLATILITY, "2 ATR beyond ${if (chosen.isEmpty()) "the entry" else "the previous target"}: no mapped resistance higher up")
        }
        return chosen.mapIndexed { k, l ->
            val p = RiskCalc.roundDown(l.price, cfg.tickSize)
            Target("T${k + 1}", p, "${l.source.label}: ${l.detail}", (p - entry) / entry * 100, (p - entry) / risk)
        }
    }

    /**
     * Chandelier exit (Chuck LeBeau): highest high of the last [EngineConfig.chandelierBars] bars minus
     * chandelierAtr × ATR. Trails up with new highs, never down.
     */
    fun chandelier(c: List<Candle>, atr: Double, cfg: EngineConfig): Double? {
        if (c.size < cfg.chandelierBars || !(atr > 0)) return null
        return c.takeLast(cfg.chandelierBars).maxOf { it.high } - cfg.chandelierAtr * atr
    }

    /** Book a fraction at each target; after T1 the stop moves to entry, after T2 to T1; the rest trails. */
    fun partials(entry: Double, targets: List<Target>, cfg: EngineConfig): List<PartialExit> {
        val fr = cfg.partialFractions
        return targets.mapIndexed { k, t ->
            PartialExit(
                "Book ${pct(fr.getOrElse(k) { 0.0 })} at ${t.label}", t.price, fr.getOrElse(k) { 0.0 },
                when (k) {
                    0 -> "Move the stop-loss to the entry (${f2(entry)}): the rest of the trade can no longer lose money."
                    1 -> "Raise the stop-loss to ${targets[0].label} (${f2(targets[0].price)})."
                    else -> "Exit the remainder, or keep trailing it with the chandelier stop if the trend is still strong."
                },
            )
        }
    }

    private fun pct(f: Double) = when { abs(f - 1.0 / 3) < 1e-6 -> "1/3"; abs(f - 0.5) < 1e-6 -> "1/2"; else -> "${(f * 100).toInt()}%" }
    fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    fun f2(v: Double) = String.format(java.util.Locale.US, "%,.2f", v)
}

/** Clusters levels into zones. */
object Zones {
    fun cluster(levels: List<Level>, atr: Double, cfg: EngineConfig): List<Zone> {
        val out = mutableListOf<Zone>()
        var cur = mutableListOf<Level>()
        fun flush() {
            if (cur.isEmpty()) return
            var lo = cur.minOf { it.low }; var hi = cur.maxOf { it.high }
            if (hi - lo < 0.2 * atr) { val m = (lo + hi) / 2; lo = m - 0.1 * atr; hi = m + 0.1 * atr }
            val distinct = cur.map { it.source }.distinct().size
            out += Zone(lo, hi, cur.toList(), cur.sumOf { it.weight } * (1 + 0.15 * (distinct - 1)))
            cur = mutableListOf()
        }
        for (l in levels.sortedBy { it.price }) {
            if (cur.isNotEmpty() && l.price - cur.first().price > cfg.clusterAtr * atr) flush()
            cur += l
        }
        flush()
        return out
    }

}
