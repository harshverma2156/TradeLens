package com.tradelens.trade

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.CorporateAction
import com.tradelens.marketdata.CorporateActionType
import com.tradelens.marketdata.DataStatus
import com.tradelens.marketdata.Freshness
import com.tradelens.marketdata.IssueType
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.Severity
import com.tradelens.marketdata.Timeframe
import com.tradelens.patterns.Context
import com.tradelens.patterns.PatternKind
import com.tradelens.patterns.PatternReport
import com.tradelens.patterns.PatternStatus
import com.tradelens.ta.Bias
import com.tradelens.ta.SignalGroup
import com.tradelens.ta.TechnicalReport
import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Smart entry, exit and price-target engine (long side, Indian cash market).
 *
 * Inputs are the outputs of the earlier phases, all computed on the same CLOSED daily bars:
 *  Phase 2  data freshness/quality ([DataStatus]) and corporate actions;
 *  Phase 3  indicators, overlays and the family-vote summary ([TechnicalReport]);
 *  Phase 4  chart and candlestick patterns ([PatternReport]).
 * The live price only decides where price is relative to the levels; every confirmation uses closed bars.
 */
object SmartEntryEngine {

    fun evaluate(
        symbol: String,
        tech: TechnicalReport,
        patterns: PatternReport?,
        price: Double,
        status: DataStatus?,
        actions: List<CorporateAction>,
        position: Position?,
        risk: RiskSettings,
        now: Long,
        extraEvents: List<EventRisk> = emptyList(),
        cfg: EngineConfig = EngineConfig(),
    ): Recommendation {
        val c = tech.candles
        val n = c.size
        val atr = tech.oscillators.atr.lastOrNull() ?: c.lastOrNull()?.let { (it.high - it.low).takeIf { r -> r > 0 } } ?: max(price * 0.02, 0.05)
        val longTerm = longTerm(c, price)

        // ---------------------------------------------------------------- gates (Phase 2 + structure)
        val blockers = mutableListOf<Blocker>()
        status?.let { st ->
            if (st.freshness == Freshness.STALE || st.freshness == Freshness.OFFLINE)
                blockers += Blocker(BlockerType.STALE_DATA, "Prices are ${st.freshness.label.lowercase()}${st.asOf?.let { " (last bar ${MarketCalendar.date(it)})" } ?: ""}. No new buy signals until fresh data arrives.")
        }
        if (n < cfg.minBars) blockers += Blocker(BlockerType.INSUFFICIENT_HISTORY, "Only $n daily bars; the engine needs ${cfg.minBars} (the 200-day average and a year of structure).")
        missingCandles(c, status, cfg)?.let { blockers += Blocker(BlockerType.MISSING_CANDLES, it) }
        val events = events(c, tech.oscillators.atr, actions, now, cfg) + extraEvents
        events.filter { it.major && !it.resolved }.forEach { blockers += Blocker(BlockerType.EVENT_RISK, it.title) }
        if (tech.summary.bias == Bias.BEARISH) blockers += Blocker(BlockerType.BEARISH_SIGNALS, "The indicator families vote Sell (${tech.summary.bearishGroups} of ${tech.summary.votingGroups} bearish).")
        // Long-term downtrend: buying support under a falling 200-day average while the weekly trend is down is
        // catching a falling knife, even if short-term momentum has bounced.
        val ema200 = tech.overlays.ema[200]
        val e200 = ema200?.lastOrNull(); val e200Prev = ema200?.getOrNull(n - 21)
        if (e200 != null && e200Prev != null && price < e200 && e200 < e200Prev && Context.higherTimeframeTrend(c, Timeframe.D1).lastOrNull() == Bias.BEARISH)
            blockers += Blocker(BlockerType.BEARISH_SIGNALS, "Long-term downtrend: price is below a falling 200-day average (${f2(e200)}) and the weekly trend is down.")
        val chart = patterns?.patterns.orEmpty().filter { it.type.kind != PatternKind.CANDLESTICK }
        chart.filter { it.status == PatternStatus.FAILED && it.direction == Bias.BULLISH && it.events.last().knownIndex >= n - 10 }
            .forEach { blockers += Blocker(BlockerType.PATTERN_FAILED, "${it.name} failed on ${MarketCalendar.date(it.events.last().knownAt)}: the breakout reversed through its stop.") }
        activeBearish(patterns, price, n)
            .forEach { blockers += Blocker(BlockerType.BEARISH_SIGNALS, "Bearish ${it.name} confirmed on ${MarketCalendar.date(it.events.first { e -> e.type == com.tradelens.patterns.EventType.CONFIRMED }.knownAt)}; price is still below its breakdown level ${f2(it.breakoutLevel)}.") }

        if (n < 30) {
            val r = Rationale("Not enough price history to map support, resistance or volatility.", emptyList(), emptyList(), emptyList(), listOf("Too little data to plan a trade."))
            return Recommendation(symbol, if (position != null) EntryStatus.HOLD else EntryStatus.AVOID, "Not enough data", null, emptyList(), blockers, events, null, r,
                price, atr, emptyList(), emptyList(), longTerm, c.lastOrNull()?.time, now)
        }

        // ---------------------------------------------------------------- levels and zones
        val map = LevelMap.build(c, tech, patterns, price, atr, cfg)
        val zones = Zones.cluster(map.levels, atr, cfg)
        // A zone within atZoneAtr of the price, or one the latest bar traded through and closed within 0.5 ATR of, is
        // "at" the price: the support being tested (e.g. old resistance a pullback has reached) AND the resistance a
        // breakout must clear. It appears in both lists.
        val last = c.last()
        val supports = zones.filter { z ->
            z.low <= price + cfg.atZoneAtr * atr || (last.low <= z.high && last.high >= z.low && z.low - price <= 0.5 * atr)
        }.sortedByDescending { it.high }
        val resistances = zones.filter { it.high > price }.sortedBy { it.low }
        val ind = Indicators(tech, c)
        val x = Inputs(c, tech, patterns, price, atr, cfg, risk, map, zones, supports, resistances, ind, events)

        val plans = listOf(aggressive(x), conservative(x), breakout(x))
        // A breakout that just failed is a failed pattern: no plan may buy into the trapped supply above.
        map.breakouts.firstOrNull()?.takeIf { plans[2].rejections.contains("Breakout failed") }?.let { b ->
            blockers += Blocker(BlockerType.PATTERN_FAILED, "The breakout above ${f2(b.level)} on ${MarketCalendar.date(c[b.index].time)} failed: price closed back below it.")
        }
        val hard = blockers.filter { it.type != BlockerType.POOR_RISK_REWARD && it.type != BlockerType.NO_LOGICAL_STOP }
        val viable = plans.filter { it.viable }
        if (viable.isEmpty()) {
            if (plans.any { p -> p.rejections.any { it.startsWith(RR) } }) blockers += Blocker(BlockerType.POOR_RISK_REWARD, "No setup offers enough reward: the nearest resistance is too close relative to a logical stop.")
            if (plans.any { p -> p.rejections.any { it.startsWith(NOSTOP) } }) blockers += Blocker(BlockerType.NO_LOGICAL_STOP, "The structure below price is too far away (more than ${cfg.maxStopAtr} ATR) for a logical stop-loss.")
            if (blockers.none { it.type == BlockerType.POOR_RISK_REWARD || it.type == BlockerType.NO_LOGICAL_STOP || it.type == BlockerType.PATTERN_FAILED })
                blockers += Blocker(BlockerType.NO_SETUP, "No setup qualifies: " + plans.flatMap { it.rejections }.distinct().joinToString("; ") + ".")
        }
        val final = plans.map { p -> if (p.status == EntryStatus.BUY_NOW && (hard.isNotEmpty() || !p.viable)) p.copy(actionable = false) else p }

        // ---------------------------------------------------------------- holder
        val pos = position?.takeIf { it.quantity > 0 }?.let { holder(x, it) }

        val primary: StrategyPlan? = when {
            final.any { it.actionable } -> final.filter { it.actionable }.maxBy { it.score }
            viable.isNotEmpty() -> final.filter { it.viable }.maxBy { it.score }
            else -> null
        }
        val statusOut = when {
            pos != null -> pos.status
            blockers.isNotEmpty() && (hard.isNotEmpty() || primary == null) -> EntryStatus.AVOID
            primary == null -> EntryStatus.AVOID
            primary.actionable -> EntryStatus.BUY_NOW
            else -> primary.status
        }
        val headline = headline(statusOut, primary, pos, blockers)
        val rationale = when {
            statusOut == EntryStatus.AVOID -> Rationale(
                "No buy signal: " + blockers.joinToString("; ") { it.type.label.lowercase() } + ".",
                ind.bullish.take(3), primary?.rationale?.levels.orEmpty(),
                blockers.map { it.message }, (primary?.rationale?.risks.orEmpty() + ind.bearish.take(3)).distinct(),
            )
            pos != null -> Rationale(pos.reason, ind.bullish.take(4), listOfNotNull("Protective stop" to pos.protectiveStop, pos.nextTarget?.let { it.label to it.price }),
                listOf("Exit if a daily candle closes below ${f2(pos.protectiveStop)}."), (ind.bearish.take(3) + blockers.map { it.message }).distinct())
            else -> primary!!.rationale
        }
        return Recommendation(symbol, statusOut, headline, primary?.type, final, blockers, events, pos, rationale, price, atr,
            supports.take(4), resistances.take(4), longTerm, c.last().time, now)
    }

    // ==================================================================== strategies

    private const val RR = "Reward/risk"
    private const val NOSTOP = "No logical stop"

    private class Inputs(
        val c: List<Candle>, val tech: TechnicalReport, val patterns: PatternReport?, val price: Double, val atr: Double, val cfg: EngineConfig,
        val risk: RiskSettings, val map: LevelMap, val zones: List<Zone>, val supports: List<Zone>, val resistances: List<Zone>, val ind: Indicators,
        val events: List<EventRisk>,
    ) {
        val n = c.size
        val tick = cfg.tickSize
        fun r(p: Double) = RiskCalc.round(p, tick)

        /** Strongest support zone within reach: score × closeness. */
        val supportZone: Zone? = supports.filter { it.score >= cfg.minZoneScore && price - it.high <= cfg.maxPullbackAtr * atr }
            .maxByOrNull { it.score * max(0.1, 1 - cfg.zoneDistanceDecay * max(0.0, (price - it.high) / atr)) }

        /** Candidate targets above [entry]: resistance-type zones (just under them) plus projections. */
        fun targetCandidates(entry: Double, exclude: Zone? = null): List<Level> {
            val resistLike = setOf(LevelSource.SWING_RESISTANCE, LevelSource.SUPPLY_ZONE, LevelSource.SWING_HIGH, LevelSource.HIGH_52W, LevelSource.PATTERN, LevelSource.VALUE_AREA_HIGH)
            val fromZones = zones.filter { z -> z !== exclude && z.low > entry && (z.low > price || z.sources.any { it in resistLike }) }.map { z ->
                val lead = z.members.maxBy { it.weight }
                Level(z.low - 0.05 * atr, lead.source, "zone ${f2(z.low)}–${f2(z.high)} (${z.label})")
            }
            return fromZones + map.projections.filter { it.price > entry }
        }

        /** Builds stop, targets, sizing and the viability checks for an entry. */
        fun build(type: StrategyType, status: EntryStatus, entryRaw: Double, lo: Double, hi: Double, structureLow: Double, structureName: String,
                  trigger: String, invalidation: Double, invalidationRule: String, margin: Double, baseScore: Double,
                  why: String, supporting: List<String>, extraRisks: List<String>, exclude: Zone? = null): StrategyPlan {
            val entry = r(entryRaw)
            val rejections = mutableListOf<String>()
            val stopPair = Exits.placeStop(entry, structureLow, atr, cfg)
            var exit: ExitPlan? = null
            var rr: RiskReward? = null
            if (stopPair == null) rejections += "$NOSTOP: $structureName (${f2(structureLow)}) is ${Exits.f1((entry - structureLow) / atr)} ATR below the entry; the limit is ${cfg.maxStopAtr} ATR."
            else {
                val stop = RiskCalc.roundDown(stopPair.first, tick)
                val targets = Exits.targets(entry, stop, targetCandidates(entry, exclude), atr, cfg)
                rr = RiskCalc.size(entry, stop, targets.map { it.price }, risk)
                val chand = Exits.chandelier(c, atr, cfg)
                val st = tech.overlays.supertrend.line.lastOrNull()?.takeIf { tech.overlays.supertrend.up.lastOrNull() == true }
                exit = ExitPlan(
                    stop, "Below $structureName. ${stopPair.second}", targets,
                    TrailingStop("Chandelier (${cfg.chandelierBars}-bar high − ${cfg.chandelierAtr.toInt()} ATR)", listOfNotNull(chand, st).maxOrNull()?.let { RiskCalc.roundDown(it, tick) },
                        "After T1, trail the stop up to the higher of the chandelier exit${if (st != null) " and the Supertrend line" else ""}. It only moves up, never down."),
                    Exits.partials(entry, targets, cfg), r(invalidation), invalidationRule,
                    listOf(
                        "A daily close below the stop-loss ${f2(stop)}: exit the whole position.",
                        "Before entry: $invalidationRule",
                        "Partial exits at ${targets.joinToString { "${it.label} ${f2(it.price)}" }} as above.",
                        "Time stop: if T1 isn't reached within ${cfg.timeStopBars} sessions, exit; the setup didn't work as expected.",
                        "A confirmed bearish chart pattern, or the indicator summary turning to Sell, is a reason to exit early.",
                    ),
                )
                if (rr == null) rejections += "$NOSTOP: the stop is not below the entry."
                else {
                    if (rr.ratios[0] < cfg.minRewardRiskT1 || rr.ratios[1] < cfg.minRewardRiskT2)
                        rejections += "$RR: T1 ${Exits.f1(rr.ratios[0])}R and T2 ${Exits.f1(rr.ratios[1])}R are below the minimum ${Exits.f1(cfg.minRewardRiskT1)}R / ${Exits.f1(cfg.minRewardRiskT2)}R."
                    if (rr.quantity == 0) rejections += "Position size: one share risks ${f2(rr.riskPerShare)}, more than your per-trade risk budget of ${f2(rr.riskBudget)} (or one share exceeds your max position size)."
                }
            }
            val viable = rejections.isEmpty()
            val score = (baseScore + (rr?.ratios?.getOrNull(1)?.let { min(it, 4.0) * 5 } ?: 0.0) + tech.summary.score / 5.0).roundToInt().coerceIn(0, 100)
            val levels = buildList {
                add("Entry" to entry); add("Entry zone low" to r(lo)); add("Entry zone high" to r(hi))
                exit?.let { e -> add("Stop-loss" to e.stopLoss); e.targets.forEach { add(it.label to it.price) }; add("Invalidation" to e.invalidation) }
            }
            val risks = buildList {
                addAll(extraRisks)
                if (rr != null && rr.ratios[0] < 1.5) add("T1 is only ${Exits.f1(rr.ratios[0])}R away: resistance is close.")
                exit?.targets?.filter { it.basis.startsWith(LevelSource.VOLATILITY.label) }?.takeIf { it.isNotEmpty() }?.let { add("${it.joinToString { t -> t.label }} ${if (it.size == 1) "is a volatility projection" else "are volatility projections"}: there is no mapped resistance that high.") }
                addAll(ind.bearish.take(3))
                events.filter { !it.major && !it.resolved }.forEach { add(it.title) }
                addAll(rejections)
            }
            return StrategyPlan(type, status, viable && status == EntryStatus.BUY_NOW, entry, r(lo), r(hi), trigger, exit, rr, viable, rejections, score, margin,
                Rationale(why, supporting, levels, listOfNotNull("Close below ${f2(invalidation)}: $invalidationRule", exit?.let { "Close below the stop-loss ${f2(it.stopLoss)} after entry." }), risks))
        }
    }

    /** AGGRESSIVE: limit buy at the top of the best support/demand zone, no confirmation required. */
    private fun aggressive(x: Inputs): StrategyPlan {
        val z = x.supportZone ?: return noZone(x, StrategyType.AGGRESSIVE)
        val a = x.atr; val cfg = x.cfg
        val edge = z.high + cfg.atZoneAtr * a
        val inZone = x.price <= edge
        val entry = if (inZone) x.price else z.high
        // The structure under a zone that's being tested includes the lows of the bars testing it (price may have
        // closed just under the zone's lower edge).
        val testLows = (max(0, x.n - cfg.conservativeLookbackBars) until x.n).filter { x.c[it].low <= z.high }.map { x.c[it].low }
        val structure = (testLows + z.low).min()
        return x.build(
            StrategyType.AGGRESSIVE, if (inZone) EntryStatus.BUY_NOW else EntryStatus.WAIT_FOR_CORRECTION,
            entry, z.low, z.high, structure, "the ${z.label} zone",
            if (inZone) "Price is in the zone now: buy at market (or a limit at ${f2(min(x.price, z.high))})." else "Limit buy at ${f2(z.high)} (top of the zone), ${Exits.f1((x.price - z.high) / a)} ATR below the current price.",
            z.low - cfg.stopBufferAtr * a, "a daily close below the zone (${f2(z.low)}) means the support failed: cancel the order.",
            if (inZone) (edge - x.price) / a else (x.price - edge) / a,
            30 + z.score * 4,
            "The ${z.label} zone at ${f2(z.low)}–${f2(z.high)} is the strongest support within ${cfg.maxPullbackAtr.toInt()} ATR (score ${Exits.f1(z.score)}: ${z.members.size} levels agree). " +
                (if (inZone) "Price is sitting on it, so a stop just below the zone keeps the risk small." else "Buying there instead of at ${f2(x.price)} improves the reward/risk.") +
                " This entry comes before any reversal is confirmed.",
            z.members.map { "${it.source.label}: ${it.detail} (${f2(it.price)})" } + x.ind.bullish.take(3),
            listOf("No reversal confirmation yet: support zones fail regularly, and this plan is the first to be stopped out when they do."),
        )
    }

    /** CONSERVATIVE: price must touch the zone, then a bar must close above the reaction high (or a bullish candlestick confirm) with ≥ N indicators agreeing. */
    private fun conservative(x: Inputs): StrategyPlan {
        val z = x.supportZone ?: return noZone(x, StrategyType.CONSERVATIVE)
        val c = x.c; val n = x.n; val a = x.atr; val cfg = x.cfg
        val touch = (n - 1 downTo max(0, n - cfg.conservativeLookbackBars)).firstOrNull { c[it].low <= z.high + 0.1 * a && c[it].close >= z.low - cfg.stopBufferAtr * a }
        val confirmations = x.ind.confirmations()
        val enough = confirmations.size >= cfg.minConfirmations
        val supporting = confirmations + z.members.map { "${it.source.label}: ${it.detail} (${f2(it.price)})" }
        if (touch == null) {
            val trig = z.high + 0.25 * a
            return x.build(StrategyType.CONSERVATIVE, EntryStatus.WAIT_FOR_CORRECTION, trig, z.low, z.high, z.low, "the ${z.label} zone",
                "Wait for price to come down to ${f2(z.low)}–${f2(z.high)}, then buy only after a daily close above the high of the bar that touched it (with ≥ ${cfg.minConfirmations} indicators turning up).",
                z.low - cfg.stopBufferAtr * a, "a close below the zone before any reversal means the support failed.",
                (x.price - z.high) / a, 25 + z.score * 3,
                "Same support zone as the aggressive plan, but this plan waits for buyers to prove themselves there before entering.",
                supporting, emptyList())
        }
        val reactionHigh = c[touch].high
        val reactionLow = (touch until n).minOf { c[it].low }
        val trigger = reactionHigh + cfg.confirmBufferAtr * a
        val closedAbove = (touch + 1 until n).any { c[it].close > trigger }
        val candle = x.patterns?.patterns.orEmpty().firstOrNull { p ->
            p.type.kind == PatternKind.CANDLESTICK && p.direction == Bias.BULLISH && p.status == PatternStatus.CONFIRMED && p.endIndex >= touch - 1 && p.endIndex < n
        }
        val reversal = closedAbove || candle != null
        val holding = c[n - 1].close >= z.low
        return if (reversal && enough && holding) x.build(
            StrategyType.CONSERVATIVE, EntryStatus.BUY_NOW, x.price, z.low, z.high, min(reactionLow, z.low), "the reaction low",
            "Confirmed: buy now. The reversal close is in and ${confirmations.size} indicators agree.",
            reactionLow - cfg.stopBufferAtr * a, "a close below the reaction low ${f2(reactionLow)} cancels the reversal.",
            (x.price - z.low) / a, 45 + z.score * 3 + confirmations.size * 4,
            "Price touched the ${z.label} zone on ${MarketCalendar.date(c[touch].time)} and then ${if (candle != null) "printed a confirmed ${candle.name}" else "closed above that bar's high (${f2(reactionHigh)})"}: buyers defended the zone. " +
                "${confirmations.size} supporting indicators agree, so this is a confirmed reversal entry.",
            supporting + listOfNotNull(candle?.let { "${it.name} confirmed on ${MarketCalendar.date(it.events.first { e -> e.type == com.tradelens.patterns.EventType.CONFIRMED }.knownAt)}" }),
            emptyList(),
        ) else x.build(
            StrategyType.CONSERVATIVE, EntryStatus.WAIT_FOR_CONFIRMATION, trigger, z.low, z.high, min(reactionLow, z.low), "the reaction low",
            "Buy only on a daily close above ${f2(trigger)}" + (if (!enough) " with at least ${cfg.minConfirmations} indicators turning up (now ${confirmations.size})" else "") + ".",
            reactionLow - cfg.stopBufferAtr * a, "a close below ${f2(reactionLow)} before confirmation means the zone is giving way.",
            (trigger - x.price) / a, 30 + z.score * 3 + confirmations.size * 3,
            "Price reached the ${z.label} zone on ${MarketCalendar.date(c[touch].time)}, but no reversal is confirmed yet" +
                (if (!reversal) ": no close above that bar's high (${f2(reactionHigh)})" else "") + (if (!enough) "; only ${confirmations.size} supporting indicators" else "") + ".",
            supporting, emptyList(),
        )
    }

    /** BREAKOUT: a close above resistance, preferably on volume; don't chase more than maxChaseAtr above it. */
    private fun breakout(x: Inputs): StrategyPlan {
        val c = x.c; val n = x.n; val a = x.atr; val cfg = x.cfg
        val b = x.map.breakouts.firstOrNull()
        if (b != null) {
            val failed = (b.index + 1 until n).any { c[it].close < b.level - 0.25 * a }
            val volOk = (b.relativeVolume ?: 0.0) >= cfg.volumeConfirm
            val ext = (x.price - b.level) / a
            val structure = min(b.level, c[b.index].low)
            val volText = b.relativeVolume?.let { "${Exits.f1(it)}× average volume" } ?: "volume unavailable"
            val supporting = listOfNotNull("Breakout bar on ${MarketCalendar.date(c[b.index].time)}: $volText", b.pattern?.let { "${it.name} confirmed (confidence ${it.confidence})" }) + x.ind.bullish.take(3)
            val risks = listOfNotNull(if (!volOk) "Breakout volume was below ${cfg.volumeConfirm}× average: weaker breakouts fail more often." else null)
            if (failed) return x.build(StrategyType.BREAKOUT, EntryStatus.AVOID, b.level + cfg.confirmBufferAtr * a, b.level, b.level, structure, "the breakout bar's low",
                "None: the breakout above ${f2(b.level)} failed.", b.level, "price fell back below the breakout level.", 0.0, 0.0,
                "The ${b.label} on ${MarketCalendar.date(c[b.index].time)} did not hold: price closed back below ${f2(b.level)}.", supporting,
                listOf("Failed breakout: trapped buyers above often sell into rallies.")).copy(viable = false, actionable = false, rejections = listOf("Breakout failed"))
            return if (ext <= cfg.maxChaseAtr) x.build(
                StrategyType.BREAKOUT, EntryStatus.BUY_NOW, x.price, b.level, b.level + cfg.maxChaseAtr * a, structure, "the breakout level",
                "Breakout confirmed by a daily close; price is ${Exits.f1(ext)} ATR above the level, close enough to buy.",
                b.level - 0.25 * a, "a close back below ${f2(b.level)} turns it into a failed breakout.",
                (cfg.maxChaseAtr - ext), 40.0 + (if (volOk) 20 else 5),
                "A daily close on ${MarketCalendar.date(c[b.index].time)} broke above ${f2(b.level)} (${b.label}) on $volText. Old resistance should now act as support.",
                supporting, risks,
            ) else x.build(
                StrategyType.BREAKOUT, EntryStatus.WAIT_FOR_CORRECTION, b.level + cfg.confirmBufferAtr * a, b.level, b.level + 0.5 * a, structure, "the breakout level",
                "Don't chase: price is ${Exits.f1(ext)} ATR above the breakout. Limit buy on a retest of ${f2(b.level)}–${f2(b.level + 0.5 * a)}.",
                b.level - 0.25 * a, "a close back below ${f2(b.level)} turns it into a failed breakout.",
                (ext - cfg.maxChaseAtr), 35.0 + (if (volOk) 15 else 5),
                "The breakout above ${f2(b.level)} is confirmed, but price has already run ${Exits.f1(ext)} ATR; buying now would put the logical stop too far away. Retests of the breakout level are common.",
                supporting, risks,
            )
        }
        // Not broken yet: watch the nearest meaningful resistance (or a forming bullish pattern's trigger).
        val forming = x.patterns?.patterns.orEmpty().filter { it.type.kind != PatternKind.CANDLESTICK && it.status == PatternStatus.FORMING && it.direction != Bias.BEARISH && it.breakoutLevel > x.price && (it.breakoutLevel - x.price) <= cfg.maxBreakoutDistanceAtr * a }
            .minByOrNull { it.breakoutLevel }
        val zone = x.resistances.firstOrNull { it.score >= cfg.minZoneScore && it.low - x.price <= cfg.maxBreakoutDistanceAtr * a }
        val level: Double; val label: String; val ex: Zone?
        when {
            forming != null && (zone == null || forming.breakoutLevel <= zone.high + 0.5 * a) -> { level = forming.breakoutLevel; label = "${forming.name} breakout level"; ex = zone?.takeIf { it.high >= level - 0.5 * a } }
            zone != null -> { level = zone.high; label = "resistance zone ${f2(zone.low)}–${f2(zone.high)} (${zone.label})"; ex = zone }
            else -> return x.build(StrategyType.BREAKOUT, EntryStatus.WAIT_FOR_BREAKOUT, x.price + a, x.price, x.price, x.price - 2 * a, "recent lows", "No meaningful resistance within ${cfg.maxBreakoutDistanceAtr.toInt()} ATR to break.",
                x.price - 2 * a, "n/a", 1.0, 0.0, "There is no well-defined resistance nearby, so there is no breakout to wait for.", emptyList(), emptyList())
                .copy(viable = false, actionable = false, rejections = listOf("No resistance within ${cfg.maxBreakoutDistanceAtr.toInt()} ATR"))
        }
        val trigger = level + cfg.confirmBufferAtr * a
        // Structure under the breakout: the higher of the 10-bar base and the nearest support zone. If that's more than
        // maxStopAtr below the trigger, placeStop rejects the plan: no logical stop.
        val base = listOfNotNull((max(0, n - 10) until n).minOf { c[it].low }, x.supports.firstOrNull { it.high < level }?.low).max()
        return x.build(
            StrategyType.BREAKOUT, EntryStatus.WAIT_FOR_BREAKOUT, trigger, level, trigger, base, "the base below resistance",
            "Buy only after a daily close above ${f2(trigger)}, ideally on at least ${cfg.volumeConfirm}× average volume. An intraday poke above doesn't count.",
            base - cfg.stopBufferAtr * a, "a close below the base (${f2(base)}) means the stock is not ready to break out.",
            (trigger - x.price) / a, 25 + (zone?.score ?: 3.0) * 2,
            "The nearest meaningful resistance is the $label, ${Exits.f1((level - x.price) / a)} ATR above the price. A close through it, preferably on heavy volume, shows buyers have absorbed the sellers there.",
            listOfNotNull(forming?.let { "${it.name} forming (confidence ${it.confidence})" }) + x.ind.bullish.take(3),
            listOf("Breakouts without volume fail often; wait for the close."), exclude = ex,
        )
    }

    private fun noZone(x: Inputs, t: StrategyType) = StrategyPlan(
        t, EntryStatus.WAIT_FOR_CORRECTION, false, x.price, x.price, x.price, "No support zone strong enough within ${x.cfg.maxPullbackAtr.toInt()} ATR.",
        null, null, false, listOf("No support zone with score ≥ ${x.cfg.minZoneScore} within ${x.cfg.maxPullbackAtr.toInt()} ATR below the price"), 0, 1.0,
        Rationale("There is no well-defined support close enough to buy against.", emptyList(), emptyList(), emptyList(), listOf("Without nearby support, a stop-loss can't be placed tightly.")),
    )

    // ==================================================================== holder

    private fun holder(x: Inputs, p: Position): PositionAdvice {
        val a = x.atr; val c = x.c; val cfg = x.cfg
        // The support that was holding BEFORE the latest bar (relative to the previous close): if the latest close went
        // through it, that's the exit. Measuring from the current price would just pick the next zone down.
        val prevClose = c[x.n - 2].close
        val support = x.zones.filter { it.score >= cfg.minZoneScore && it.high < prevClose }.maxByOrNull { it.high }
        val stop = RiskCalc.roundDown(support?.let { it.low - cfg.stopBufferAtr * a } ?: (prevClose - cfg.maxStopAtr * a), x.tick)
        // The chandelier is the trailing level to move up to once T1 is reached; it doesn't decide EXIT by itself,
        // otherwise every normal trend pullback deeper than 3 ATR would close the position.
        val trail = Exits.chandelier(c, a, cfg)?.takeIf { x.price > p.avgPrice + a }?.let { RiskCalc.roundDown(it, x.tick) }
        val pnl = (x.price - p.avgPrice) / p.avgPrice * 100
        val ref = max(p.avgPrice, x.price)
        val targets = Exits.targets(ref, min(stop, ref - a), x.targetCandidates(ref), a, cfg)   // guard: the stop may sit above price when exiting
        val nextTarget = targets.firstOrNull()
        val lastClose = c.last().close
        val bearishPattern = activeBearish(x.patterns, x.price, x.n).firstOrNull()
        // Partial booking at resistance only when price RALLIED into it (not when a pullback rests on old resistance).
        val rallying = x.n > 6 && lastClose > c[x.n - 6].close
        val res = x.resistances.firstOrNull { it.low > x.price - cfg.atZoneAtr * a }
        val atResistance = rallying && res != null && x.price >= res.low - cfg.atZoneAtr * a
        val rsi = x.tech.oscillators.rsi.lastOrNull() ?: 50.0
        val ema50 = x.tech.overlays.ema[50]?.lastOrNull()
        return when {
            lastClose < stop -> PositionAdvice(EntryStatus.EXIT, pnl, stop, trail, nextTarget, "The last daily close (${f2(lastClose)}) is below the protective stop ${f2(stop)}${support?.let { " under the ${it.label} zone" } ?: ""}. Exit.")
            bearishPattern != null -> PositionAdvice(EntryStatus.EXIT, pnl, stop, trail, nextTarget, "A bearish ${bearishPattern.name} is confirmed (target ${f2(bearishPattern.targets[1].price)}). The structure that supported the position has broken.")
            x.tech.summary.bias == Bias.BEARISH && ema50 != null && lastClose < ema50 -> PositionAdvice(EntryStatus.EXIT, pnl, stop, trail, nextTarget, "The indicator families vote Sell and price closed below the 50-day EMA (${f2(ema50)}).")
            pnl > 0 && (atResistance || (rsi >= 75 && x.price > p.avgPrice + 2 * a)) -> PositionAdvice(EntryStatus.PARTIAL_PROFIT_BOOKING, pnl, stop, trail, nextTarget,
                (if (atResistance) "Price has rallied into resistance at ${f2(res!!.low)}–${f2(res.high)} (${res.label})." else "RSI ${Exits.f1(rsi)} is stretched after a ${Exits.f1((x.price - p.avgPrice) / a)}-ATR gain.") +
                    " Book part of the profit (about a third) and raise the stop on the rest to ${f2(listOfNotNull(stop, p.avgPrice, trail).max())}.")
            else -> PositionAdvice(EntryStatus.HOLD, pnl, stop, trail, nextTarget,
                "The trend structure is intact: price is above the protective stop ${f2(stop)}${support?.let { " (below the ${it.label} zone)" } ?: ""}. " +
                    (nextTarget?.let { "Next target ${f2(it.price)} (${it.basis})." } ?: ""))
        }
    }

    // ==================================================================== indicators (Phase 3)

    private class Indicators(val tech: TechnicalReport, val c: List<Candle>) {
        private val voting = tech.readings.filter { it.ready && it.group != SignalGroup.CONTEXT }
        val bullish = voting.filter { it.bias == Bias.BULLISH }.sortedByDescending { it.strength }.map { "${it.name}: ${it.explanation}" }
        val bearish = voting.filter { it.bias == Bias.BEARISH }.sortedByDescending { it.strength }.map { "${it.name}: ${it.explanation}" }

        /** Reversal confirmations from momentum and flow, each from a different indicator family. */
        fun confirmations(): List<String> {
            val o = tech.oscillators
            val i = c.size - 1
            val out = mutableListOf<String>()
            val h = o.macd.hist; if ((h.getOrNull(i) ?: return out) > (h.getOrNull(i - 1) ?: Double.MAX_VALUE)) out += "MACD histogram rising (momentum turning up)"
            val r = o.rsi; val ri = r.getOrNull(i); val rp = r.getOrNull(i - 1)
            if (ri != null && rp != null && ri > rp && ri >= 40) out += "RSI rising (${Exits.f1(ri)})"
            val k = o.stoch.k.getOrNull(i); val d = o.stoch.d.getOrNull(i)
            if (k != null && d != null && k > d) out += "Stochastic %K above %D"
            val e20 = tech.overlays.ema[20]?.getOrNull(i)
            if (e20 != null && c[i].close > e20) out += "Close back above the 20-day EMA"
            val m = o.mfi.getOrNull(i); val mp = o.mfi.getOrNull(i - 1)
            if (m != null && mp != null && m > mp && m >= 40) out += "Money Flow Index rising"
            return out
        }
    }

    // ==================================================================== gates

    /**
     * Bearish chart patterns that still matter: confirmed within the last 20 sessions, still tracked, and price still
     * below the level it broke. A pattern whose breakdown price has since been reclaimed no longer blocks anything.
     */
    fun activeBearish(patterns: PatternReport?, price: Double, n: Int) = patterns?.patterns.orEmpty().filter {
        it.type.kind != PatternKind.CANDLESTICK && it.direction == Bias.BEARISH && it.status == PatternStatus.CONFIRMED && !it.closed &&
            (it.confirmedIndex ?: -1) >= n - 20 && price < it.breakoutLevel
    }

    /** Calendar gaps in the recent window larger than a plausible run of holidays, or Phase 2's missing-bar warnings there. */
    fun missingCandles(c: List<Candle>, status: DataStatus?, cfg: EngineConfig): String? {
        if (c.size < 2) return null
        val from = max(1, c.size - cfg.recentWindowBars)
        for (k in from until c.size) {
            var d = MarketCalendar.date(c[k - 1].time).plusDays(1)
            val end = MarketCalendar.date(c[k].time)
            var weekdays = 0
            while (d.isBefore(end)) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) weekdays++; d = d.plusDays(1) }
            if (weekdays > cfg.maxWeekdayGap) return "$weekdays trading days have no bar between ${MarketCalendar.date(c[k - 1].time)} and $end. Levels and indicators would be unreliable."
        }
        val windowStart = c[from - 1].time
        status?.quality?.issues?.firstOrNull { it.type == IssueType.MISSING_BARS && it.severity != Severity.INFO && (it.time ?: 0) >= windowStart }
            ?.let { return "The data provider is missing recent bars: ${it.message}." }
        return null
    }

    /**
     * Event risk. Unresolved major events block new entries:
     *  - a split/bonus ex-date within the next 10 days, or within the last 5 bars (history may still be adjusting);
     *  - a price shock in the last [EngineConfig.shockSettleBars] bars (gap ≥ shockGapAtr or range ≥ shockRangeAtr ATR):
     *    usually results or news. It counts as resolved once that many sessions have passed.
     * Upcoming dividends are minor (warned, not blocked). Results dates aren't available from the current provider.
     */
    fun events(c: List<Candle>, atr: List<Double?>, actions: List<CorporateAction>, now: Long, cfg: EngineConfig): List<EventRisk> {
        val out = mutableListOf<EventRisk>()
        val n = c.size
        if (n == 0) return out
        val recentFrom = c[max(0, n - 5)].time
        for (a in actions) {
            val d = MarketCalendar.date(a.exDate)
            val upcoming = a.exDate > c.last().time && a.exDate - now <= 10 * 86_400_000L
            val recent = a.exDate in recentFrom..c.last().time
            when {
                a.type != CorporateActionType.DIVIDEND && (upcoming || recent) -> out += EventRisk(
                    "${a.type.name.lowercase().replaceFirstChar { it.uppercase() }} ${if (upcoming) "ex-date coming up on" else "took effect on"} $d: price history and levels may not be adjusted consistently yet.",
                    a.exDate, major = true, resolved = false, source = a.source)
                a.type == CorporateActionType.DIVIDEND && upcoming -> out += EventRisk("Dividend ex-date on $d: the price will drop by about the dividend amount.", a.exDate, major = false, resolved = false, source = a.source)
            }
        }
        for (k in max(1, n - 10) until n) {
            val a = atr.getOrNull(k - 1) ?: continue
            val gap = abs(c[k].open - c[k - 1].close) / a
            val range = (c[k].high - c[k].low) / a
            if (gap >= cfg.shockGapAtr || range >= cfg.shockRangeAtr) {
                val settled = n - 1 - k >= cfg.shockSettleBars
                out += EventRisk(
                    "Large move on ${MarketCalendar.date(c[k].time)} (${if (gap >= cfg.shockGapAtr) "gap of ${Exits.f1(gap)} ATR" else "range of ${Exits.f1(range)} ATR"}), usually results or news. " +
                        if (settled) "It has had ${n - 1 - k} sessions to settle." else "Wait ${cfg.shockSettleBars - (n - 1 - k)} more session(s) for price to settle before trusting levels.",
                    c[k].time, major = true, resolved = settled, source = "price action")
            }
        }
        return out
    }

    // ==================================================================== long term (separate)

    private fun longTerm(c: List<Candle>, price: Double): LongTermView {
        val notes = mutableListOf<String>()
        val closes = c.map { it.close }
        val ema200 = com.tradelens.ta.Rolling.ema(closes, 200).lastOrNull()
        ema200?.let { notes += "Price is ${f1(abs(price / it - 1) * 100)}% ${if (price >= it) "above" else "below"} its 200-day average (${f2(it)})." }
        if (c.size >= 30) {
            val yr = c.takeLast(min(c.size, 252))
            val hi = yr.maxOf { it.high }; val lo = yr.minOf { it.low }
            notes += "${f1((1 - price / hi) * 100)}% below its 52-week high (${f2(hi)}), ${f1((price / lo - 1) * 100)}% above its 52-week low (${f2(lo)})."
        }
        val weekly = Context.higherTimeframeTrend(c, Timeframe.D1).lastOrNull()
        val trend = when {
            ema200 == null -> "Not enough history for a long-term trend."
            weekly == Bias.BULLISH && price > ema200 -> "Long-term uptrend: above the 200-day average with a rising weekly trend."
            weekly == Bias.BEARISH && price < ema200 -> "Long-term downtrend: below the 200-day average with a falling weekly trend."
            else -> "Long-term trend is mixed."
        }
        return LongTermView(
            false,
            "Valuation-based accumulation (buying in stages when the business is cheap relative to its earnings, cash flow or book value) needs fundamental data, " +
                "which the current data source doesn't provide. No valuation recommendation is made. This section never sets a stop-loss: short-term stops belong to the trade plan above.",
            trend, notes,
        )
    }

    // ==================================================================== text

    private fun headline(s: EntryStatus, p: StrategyPlan?, pos: PositionAdvice?, blockers: List<Blocker>): String = when (s) {
        EntryStatus.BUY_NOW -> "${p!!.type.label} entry available now near ${f2(p.entry)}"
        EntryStatus.WAIT_FOR_CORRECTION -> "Wait for a pullback to ${f2(p!!.entryLow)}–${f2(p.entryHigh)}"
        EntryStatus.WAIT_FOR_BREAKOUT -> "Wait for a daily close above ${f2(p!!.entry)}"
        EntryStatus.WAIT_FOR_CONFIRMATION -> "At support; wait for a close above ${f2(p!!.entry)}"
        EntryStatus.HOLD -> "Hold: structure intact, stop ${f2(pos!!.protectiveStop)}"
        EntryStatus.PARTIAL_PROFIT_BOOKING -> "Book partial profit; raise the stop on the rest"
        EntryStatus.EXIT -> "Exit: the protective level has broken"
        EntryStatus.AVOID -> "Avoid for now: " + (blockers.firstOrNull()?.type?.label?.lowercase() ?: "no setup with acceptable risk")
    }

    fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    fun f2(v: Double) = String.format(java.util.Locale.US, "%,.2f", v)
}
