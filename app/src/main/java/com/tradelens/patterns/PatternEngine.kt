package com.tradelens.patterns

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.Timeframe
import com.tradelens.ta.Bias
import com.tradelens.ta.TechnicalEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deterministic chart- and candlestick-pattern detection.
 *
 * The engine WALKS FORWARD one closed bar at a time. At bar t it may only use:
 *  - swing points confirmed by t (a swing at j needs the `pivotWindow` bars after it, so it is known at j + w);
 *  - the zigzag built from those swings, i.e. the zigzag as it looked at t;
 *  - bars ≤ t for breakouts, volume, ATR and the higher-timeframe trend (completed higher-timeframe bars only).
 * Every state change is logged as an event stamped with the bar at which it became knowable, so a run on bars
 * 0..t produces exactly the events of a full run up to t (verified by tests). No step reads a later bar.
 */
object PatternEngine {

    fun analyze(symbol: String, tf: Timeframe, candles: List<Candle>, nowMs: Long, cfg: PatternConfig = PatternConfig()): PatternReport {
        val sorted = candles.sortedBy { it.time }
        val forming = sorted.lastOrNull()?.takeIf { nowMs < TechnicalEngine.barEnd(it.time, tf) }
        return detect(symbol, tf, if (forming != null) sorted.dropLast(1) else sorted, nowMs, cfg)
    }

    /** [c] must be closed bars, oldest first. Index symbols (`^…`) ignore volume. */
    fun detect(symbol: String, tf: Timeframe, c: List<Candle>, nowMs: Long, cfg: PatternConfig = PatternConfig(), isIndex: Boolean = symbol.startsWith("^")): PatternReport {
        val x = Context(c, tf, cfg, ignoreVolume = isIndex)
        val run = Run(x)
        for (t in c.indices) run.step(t)
        val patterns = run.trackers.values.map { it.toPattern(c.lastIndex) }.sortedWith(compareByDescending<DetectedPattern> { it.detectedIndex }.thenBy { it.type.ordinal })
        return PatternReport(symbol, tf, x.higherTimeframe, nowMs, c.size, c.lastOrNull()?.time, patterns, cfg)
    }

    private class PendingCup(val left: Swing, val right: Swing, val cup: Triple<Int, Double, Double>)

    private class Run(val x: Context) {
        val trackers = LinkedHashMap<String, Tracker>()
        private val pivotsAt = Swings.pivots(x.c, x.cfg.pivotWindow).groupBy { it.confirmedAt }
        private val zz = Swings.ZigZag(x.atr, x.cfg.minSwingAtr)
        private val cups = LinkedHashMap<String, PendingCup>()
        /** New trendline patterns must start at or after the end of the last one that resolved (no re-use of its swings). */
        private var trendlineFloor = 0

        fun step(t: Int) {
            var changed = false
            pivotsAt[t]?.let { ps -> for (p in orderFor(ps)) if (zz.add(p)) changed = true }
            val pts = zz.points
            if (changed && x.atr[t] != null) {
                ChartDetectors.reversals(x, pts).forEach { offer(it, t) }
                ChartDetectors.trendlines(x, pts.filter { it.index >= trendlineFloor })?.let { offer(it, t) }
                if (pts.last().high) findCup(pts)
            }
            if (x.atr[t] != null) {
                ChartDetectors.latestPole(x, pts, t)?.let { (p0, p1, bull) ->
                    val type = if (bull) PatternType.BULL_FLAG else PatternType.BEAR_FLAG
                    if (key(type, p0.index) !in trackers) ChartDetectors.flag(x, p0, p1, t, bull)?.let { g ->
                        offer(g, t) { i -> ChartDetectors.flag(x, p0, p1, i, bull) }
                    }
                }
                stepCups(t)
                Candlesticks.at(x, t).forEach { offer(it, t) }
            }
            val stillborn = mutableListOf<String>()
            for (tr in trackers.values) if (!tr.closed) {
                val before = tr.status
                tr.advance(t)
                // Invalidated by bars that closed between its last swing and the bar that confirmed that swing:
                // by the time it could be known it was already dead, so it is never reported.
                if (tr.detectedIndex == t && tr.status == PatternStatus.INVALIDATED && !tr.superseded) { stillborn += tr.key; continue }
                if (tr.g.type.trendlineFamily && before == PatternStatus.FORMING && tr.status != PatternStatus.FORMING && !tr.superseded)
                    trendlineFloor = max(trendlineFloor, tr.resolvedIndex ?: t)
            }
            stillborn.forEach { trackers.remove(it) }
        }

        /** An outside bar can be both a swing high and low; feed the one that continues the zigzag first. */
        private fun orderFor(ps: List<Swing>): List<Swing> =
            if (ps.size == 2 && zz.points.lastOrNull()?.high == true) ps.sortedBy { it.high } else ps.sortedByDescending { it.high }

        private fun key(type: PatternType, start: Int) = "${type.name}@${x.c[start].time}"

        private fun offer(g: Geometry, t: Int, refit: ((Int) -> Geometry?)? = null) {
            val k = key(g.type, g.start)
            trackers[k]?.let { ex ->
                if (ex.status == PatternStatus.FORMING && ex.g.anchorKey != g.anchorKey && !g.evolving) ex.update(g, t)
                return
            }
            if (g.type.trendlineFamily) {
                val overlapping = trackers.values.filter {
                    it.status == PatternStatus.FORMING && it.g.type.trendlineFamily && it.g.start <= g.end && g.start <= it.g.end
                }
                overlapping.firstOrNull { it.g.type == g.type }?.let { it.update(g, t); return }
                overlapping.forEach { it.supersede(t, g.type) }
            }
            if (g.type.kind == PatternKind.REVERSAL) {
                val mine = g.anchors.drop(1).map { it.index }.toSet()
                trackers.values.filter { it.status == PatternStatus.FORMING && it.g.type.kind == PatternKind.REVERSAL && it.g.anchors.drop(1).all { a -> a.index in mine } }
                    .forEach { it.supersede(t, g.type) }
            }
            trackers[k] = Tracker(x, k, g, t, refit)
        }

        private fun findCup(pts: List<Swing>) {
            val right = pts.last()
            for (k in pts.lastIndex - 2 downTo 0) {
                val left = pts[k]
                if (!left.high) continue
                if (right.index - left.index > x.cfg.maxPatternBars) break
                val cup = ChartDetectors.cup(x, left, right) ?: continue
                val ck = key(PatternType.CUP_AND_HANDLE, left.index)
                if (ck !in trackers) cups[ck] = PendingCup(left, right, cup)
                return
            }
        }

        private fun stepCups(t: Int) {
            val it = cups.entries.iterator()
            while (it.hasNext()) {
                val (k, p) = it.next()
                val buf = x.cfg.confirmBufferAtr * x.atrAt(t)
                if (t <= p.right.index) continue
                if (k in trackers || x.c[t].close > p.right.price + buf || t - p.right.index > max(x.cfg.handleMinBars + 5, (p.right.index - p.left.index) / 3)) { it.remove(); continue }
                val g = ChartDetectors.cupWithHandle(x, p.left, p.right, p.cup, t) ?: continue
                offer(g, t) { i -> ChartDetectors.cupWithHandle(x, p.left, p.right, p.cup, i) }
                it.remove()
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private class Tracker(val x: Context, val key: String, var g: Geometry, val detectedIndex: Int, val refit: ((Int) -> Geometry?)?) {
        val cfg = x.cfg
        var status = PatternStatus.FORMING
        var direction = g.direction
        val events = mutableListOf<PatternEvent>()
        var checked = if (g.evolving) detectedIndex else g.end
        var confirmedIndex: Int? = null
        var resolvedIndex: Int? = null
        var level = 0.0; var entry = 0.0; var stop = 0.0
        var targets: List<Double> = emptyList()
        var targetsHit = 0
        var closed = false
        var superseded = false

        init { log(EventType.DETECTED, g.end, detectedIndex, "${g.type.label} detected: ${g.note}") }

        private fun log(type: EventType, index: Int, known: Int, detail: String) {
            events += PatternEvent(type, index, known, x.c[known].time, detail)
        }

        fun update(ng: Geometry, t: Int) {
            g = Geometry(ng.type, ng.direction, ng.bidirectional, min(g.start, ng.start), ng.end, ng.anchors, ng.anchorLabels, ng.lines, ng.levels,
                ng.up, ng.down, ng.stopBull, ng.stopBear, ng.height, ng.expiresAt, ng.fit, ng.note, ng.evolving, ng.rTargets)
            direction = ng.direction
            log(EventType.UPDATED, ng.end, t, "Structure extended to ${ng.anchors.size} swing points.")
        }

        fun supersede(t: Int, by: PatternType) {
            superseded = true
            end(PatternStatus.INVALIDATED, EventType.INVALIDATED, t, t, "Structure evolved into a ${by.label}.")
        }

        private fun end(s: PatternStatus, e: EventType, i: Int, t: Int, why: String) {
            status = s; closed = true; resolvedIndex = i
            log(e, i, t, why)
        }

        fun advance(t: Int) {
            for (i in checked + 1..t) {
                if (closed) break
                bar(i, t)
            }
            checked = t
            if (!closed && status == PatternStatus.FORMING && refit != null) {
                val ng = refit.invoke(t)
                if (ng == null) end(PatternStatus.INVALIDATED, EventType.INVALIDATED, t, t,
                    if (t > g.expiresAt) "No breakout within ${g.expiresAt - g.end} bars." else "Price action stopped fitting a ${g.type.label} (too deep, too long or drifting the wrong way).")
                else g = ng
            }
        }

        private fun bar(i: Int, t: Int) {
            val k = x.c[i]
            val buf = cfg.confirmBufferAtr * x.atrAt(i)
            if (status == PatternStatus.FORMING) {
                val up = g.up(i); val dn = g.down(i)
                val bull = k.close > up + buf
                val bear = k.close < dn - buf
                when {
                    bull && (g.bidirectional || direction == Bias.BULLISH) -> confirm(i, t, Bias.BULLISH, up)
                    bear && (g.bidirectional || direction == Bias.BEARISH) -> confirm(i, t, Bias.BEARISH, dn)
                    bull -> end(PatternStatus.INVALIDATED, EventType.INVALIDATED, i, t, "Closed at ${f(k.close)}, above the invalidation level ${f(up)}, before any breakdown.")
                    bear -> end(PatternStatus.INVALIDATED, EventType.INVALIDATED, i, t, "Closed at ${f(k.close)}, below the invalidation level ${f(dn)}, before any breakout.")
                    i > g.expiresAt -> end(PatternStatus.INVALIDATED, EventType.INVALIDATED, i, t, "No breakout within ${g.expiresAt - g.end} bars of completion.")
                }
                return
            }
            if (status != PatternStatus.CONFIRMED) return
            val s = if (direction == Bias.BULLISH) 1.0 else -1.0
            if (s * (k.close - stop) < 0) {
                if (targetsHit == 0) end(PatternStatus.FAILED, EventType.FAILED, i, t, "Closed at ${f(k.close)}, through the stop-loss ${f(stop)}, before the first target.")
                else { closed = true; resolvedIndex = i; log(EventType.CLOSED, i, t, "Stopped out at ${f(stop)} after reaching target $targetsHit.") }
                return
            }
            val extreme = if (s > 0) k.high else k.low
            while (targetsHit < targets.size && s * (extreme - targets[targetsHit]) >= 0) {
                targetsHit++
                log(EventType.TARGET_HIT, i, t, "Target $targetsHit (${f(targets[targetsHit - 1])}) reached.")
            }
            if (targetsHit == targets.size) { closed = true; resolvedIndex = i; log(EventType.CLOSED, i, t, "Final target reached.") }
            else if (i - (confirmedIndex ?: i) > max(30, 3 * (g.end - g.start))) { closed = true; resolvedIndex = i; log(EventType.CLOSED, i, t, "Tracking window ended without hitting the stop or the final target.") }
        }

        private fun confirm(i: Int, t: Int, dir: Bias, lvl: Double) {
            status = PatternStatus.CONFIRMED; direction = dir; confirmedIndex = i
            level = lvl
            entry = x.c[i].close
            stop = stopFor(dir, lvl, i)
            targets = targetsFor(dir, lvl, entry, stop)
            val rv = x.relativeVolume(i)
            log(EventType.CONFIRMED, i, t, "Closed at ${f(entry)} ${if (dir == Bias.BULLISH) "above" else "below"} the breakout level ${f(lvl)}" +
                (rv?.let { " on ${String.format(java.util.Locale.US, "%.1f", it)}× average volume" } ?: "") + ".")
        }

        /**
         * Structural stop (beyond the right shoulder, second top, flag low, last swing, …), but always at least
         * 0.5 ATR beyond the breakout level: on steep wedges/channels the projected line can pass the last swing.
         */
        fun stopFor(dir: Bias, lvl: Double, i: Int): Double {
            val m = 0.5 * x.atrAt(i)
            return if (dir == Bias.BULLISH) min(g.stopBull, lvl - m) else max(g.stopBear, lvl + m)
        }

        fun targetsFor(dir: Bias, lvl: Double, entry: Double, stop: Double): List<Double> {
            val s = if (dir == Bias.BULLISH) 1.0 else -1.0
            return if (g.rTargets) { val r = abs(entry - stop); listOf(1.0, 2.0, 3.0).map { entry + s * it * r } }
            else MEASURED.map { lvl + s * it * g.height }
        }

        // -------------------------------------------------------------- output

        fun toPattern(last: Int): DetectedPattern {
            val c = x.c
            val a = x.atrAt(g.end)
            val dir = direction
            val forming = status == PatternStatus.FORMING
            val evalAt = confirmedIndex ?: min(last, max(g.end, resolvedIndex ?: last))
            val bullSide = if (dir == Bias.NEUTRAL) true else dir == Bias.BULLISH
            val buf = cfg.confirmBufferAtr * x.atrAt(evalAt)
            val lvl: Double; val ent: Double; val stp: Double; val tg: List<Double>
            if (confirmedIndex != null) { lvl = level; ent = entry; stp = stop; tg = targets }
            else {
                lvl = if (bullSide) g.up(evalAt) else g.down(evalAt)
                ent = lvl + (if (bullSide) buf else -buf)
                stp = stopFor(if (bullSide) Bias.BULLISH else Bias.BEARISH, lvl, evalAt)
                tg = targetsFor(if (bullSide) Bias.BULLISH else Bias.BEARISH, lvl, ent, stp)
            }
            val invalid = if (bullSide) g.down(evalAt) else g.up(evalAt)
            val rv = confirmedIndex?.let { x.relativeVolume(it) }
            val vt = x.volumeTrend(g.start, g.end)
            val volume = VolumeCheck(
                x.hasVolume, rv, rv?.let { it >= cfg.volumeConfirm }, vt,
                when {
                    !x.hasVolume -> "No volume data (index or illiquid series); volume can't confirm."
                    rv != null -> "Breakout volume ${String.format(java.util.Locale.US, "%.1f", rv)}× the 20-bar average: " +
                        if (rv >= cfg.volumeConfirm) "confirmed." else "below the ${cfg.volumeConfirm}× needed, so not confirmed."
                    else -> "Breakout volume pending." + (vt?.let { " Volume during the formation ${if (it < 0.9) "contracted" else if (it > 1.1) "expanded" else "held steady"} (${String.format(java.util.Locale.US, "%.2f", it)}× second half vs first)." } ?: "")
                },
            )
            val htf = x.htfAt(evalAt)
            val breakdown = score(dir, rv, vt, htf, a)
            val conf = if (status == PatternStatus.FAILED || status == PatternStatus.INVALIDATED) min(30, breakdown.total) else breakdown.total
            // Lines run to the breakout bar; unconfirmed ones to where they ended (or the latest bar, capped at expiry).
            val stopAt = (confirmedIndex ?: min(resolvedIndex ?: last, max(g.expiresAt, g.end))).coerceIn(g.end, last)
            val anchors = g.anchors.mapIndexed { k, s -> ChartPoint(s.index, c[s.index].time, s.price, g.anchorLabels.getOrElse(k) { "" }) }
            val segments = buildList {
                if (g.type.kind == PatternKind.REVERSAL) anchors.zipWithNext().forEach { (p, q) -> add(ChartSegment(p, q, SegmentRole.OUTLINE)) }
                g.lines.forEach { (ln, from, role) ->
                    val to = if (role == SegmentRole.POLE) g.anchors[1].index else stopAt
                    if (to > from) add(ChartSegment(ChartPoint(from, c[from].time, ln.at(from)), ChartPoint(to, c[to].time, ln.at(to)), role))
                }
            }
            val levels = g.levels + (if (g.bidirectional && forming) listOf(PriceLevel("Bullish trigger", g.up(evalAt)), PriceLevel("Bearish trigger", g.down(evalAt))) else emptyList())
            return DetectedPattern(
                key, g.type, dir, x.tf, status, detectedIndex, c[detectedIndex].time, g.start, g.end, confirmedIndex, conf, breakdown,
                anchors, segments, levels, lvl, ent, stp,
                tg.mapIndexed { k, v -> PriceLevel(if (g.rTargets) "T${k + 1} (${k + 1}R)" else "T${k + 1} (${MEASURED_LABEL[k]})", v) },
                invalid, g.height, volume, htf, targetsHit, closed, explain(dir, htf, volume, lvl, stp, tg, invalid), events.toList(),
            )
        }

        private fun score(dir: Bias, rv: Double?, vt: Double?, htf: Bias?, a: Double): ConfidenceBreakdown {
            val geometry = (10 + 25 * g.fit).roundToInt()
            val minH = if (g.type.kind == PatternKind.CANDLESTICK) 1.0 else cfg.minHeightAtr
            val size = (10 * ((g.height / a - minH) / minH).coerceIn(0.0, 1.0)).roundToInt()
            val breakout = if (status == PatternStatus.CONFIRMED) 20 else 0
            val volume = when {
                !x.hasVolume -> 10
                rv != null && rv >= cfg.volumeConfirm -> (12 + 8 * ((rv - cfg.volumeConfirm) / cfg.volumeConfirm).coerceIn(0.0, 1.0)).roundToInt()
                rv != null -> (6 * rv / cfg.volumeConfirm).roundToInt().coerceIn(0, 6)
                g.type.kind == PatternKind.CONTINUATION && vt != null -> if (vt < 0.9) 8 else if (vt <= 1.1) 5 else 2
                else -> 5
            }
            val h = when { dir == Bias.NEUTRAL || htf == null || htf == Bias.NEUTRAL -> 7; htf == dir -> 15; else -> 0 }
            return ConfidenceBreakdown(geometry, size, breakout, volume, h)
        }

        private fun explain(dir: Bias, htf: Bias?, v: VolumeCheck, lvl: Double, stp: Double, tg: List<Double>, invalid: Double): String = buildString {
            append("${g.type.label} (${g.type.kind.label.lowercase()}, ${x.tf.label}). ${g.note} ")
            val word = if (dir == Bias.BEARISH) "below" else "above"
            when (status) {
                PatternStatus.FORMING -> append(
                    if (g.bidirectional && dir == Bias.NEUTRAL) "Waiting for a close above ${f(g.up(x.n - 1))} (bullish) or below ${f(g.down(x.n - 1))} (bearish). "
                    else "Waiting for a candle to close $word ${f(lvl)}. A close ${if (dir == Bias.BEARISH) "above" else "below"} ${f(invalid)} first would invalidate it. ")
                PatternStatus.CONFIRMED -> append("Confirmed on ${MarketCalendar.date(x.c[confirmedIndex!!].time)} by a close $word ${f(lvl)}. " +
                    if (targetsHit > 0) "$targetsHit of ${tg.size} targets reached. " else "")
                PatternStatus.FAILED -> append("It broke out, then closed back through the stop ${f(stp)} before the first target: a failed pattern. ")
                PatternStatus.INVALIDATED -> append("${events.last().detail} ")
            }
            append(v.note).append(' ')
            x.higherTimeframe?.let { h ->
                append("${h.label} trend: ${htf?.name?.lowercase() ?: "not enough history"}")
                if (htf != null && dir != Bias.NEUTRAL && htf != Bias.NEUTRAL) append(if (htf == dir) " (aligned)" else " (against the pattern)")
                append(". ")
            }
            append("Detected by fixed geometric rules from past prices; patterns fail often, so treat it as one input, not a prediction.")
        }

        companion object {
            val MEASURED = listOf(0.618, 1.0, 1.618)
            val MEASURED_LABEL = listOf("0.618× height", "measured move", "1.618× height")
            fun f(v: Double) = String.format(java.util.Locale.US, "%,.2f", v)
        }
    }
}

/** Memoises pattern reports per (source, symbol, timeframe, closed-bar identity), like [com.tradelens.ta.TechnicalCache]. */
class PatternCache(private val maxEntries: Int = 24) {
    private data class Key(val source: String, val symbol: String, val tf: Timeframe, val count: Int, val first: Long, val last: Long, val lastHash: Int)

    private val map = object : LinkedHashMap<Key, PatternReport>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PatternReport>?) = size > maxEntries
    }
    var misses = 0; private set

    @Synchronized
    fun get(source: String, symbol: String, tf: Timeframe, candles: List<Candle>, nowMs: Long): PatternReport {
        val sorted = candles.sortedBy { it.time }
        val forming = sorted.lastOrNull()?.takeIf { nowMs < TechnicalEngine.barEnd(it.time, tf) }
        val closed = if (forming != null) sorted.dropLast(1) else sorted
        val key = Key(source, symbol, tf, closed.size, closed.firstOrNull()?.time ?: 0, closed.lastOrNull()?.time ?: 0, closed.lastOrNull()?.hashCode() ?: 0)
        map[key]?.let { return it }
        misses++
        return PatternEngine.detect(symbol, tf, closed, nowMs).also { map[key] = it }
    }
}
