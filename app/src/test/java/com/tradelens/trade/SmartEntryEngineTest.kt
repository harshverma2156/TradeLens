package com.tradelens.trade

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.CorporateAction
import com.tradelens.marketdata.CorporateActionType
import com.tradelens.marketdata.DataMode
import com.tradelens.marketdata.DataStatus
import com.tradelens.marketdata.Freshness
import com.tradelens.marketdata.Timeframe
import com.tradelens.patterns.NoLookAheadTest
import com.tradelens.patterns.PatternEngine
import com.tradelens.patterns.Shapes
import com.tradelens.ta.TechnicalEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Engine scenarios on synthetic daily bars (see [Shapes]).
 *
 * The base is a wave uptrend: +10 over 12 bars, −5 over 8 bars, repeated, so each trough lands on the previous peak
 * (old resistance becomes support). The live price is a separate input from the closed bars, so tests can move it
 * through a zone without touching the confirmation logic.
 */
class SmartEntryEngineTest {
    private val risk = RiskSettings(100_000.0, 1.0, 20.0)
    private val fresh = DataStatus("Test", DataMode.DELAYED, Freshness.MARKET_CLOSED, null, null)

    companion object {
        fun waves(n: Int, start: Double = 100.0): MutableList<Pair<Int, Double>> {
            val pts = mutableListOf(0 to start); var i = 0; var p = start
            repeat(n) { i += 12; p += 10; pts += i to p; i += 8; p -= 5; pts += i to p }
            return pts
        }
        val uptrend: List<Candle> by lazy { Shapes.path(*waves(22).toTypedArray()) }   // 441 bars, ends on a trough at 210

        /** The same waves going down: −10 over 12 bars, +5 over 8 bars, from 300 (prices stay positive). */
        val downtrend: List<Candle> by lazy {
            val pts = mutableListOf(0 to 300.0); var i = 0; var p = 300.0
            repeat(22) { i += 12; p -= 10; pts += i to p; i += 8; p += 5; pts += i to p }
            Shapes.path(*pts.toTypedArray())
        }
    }

    private fun run(c: List<Candle>, price: Double = c.last().close, status: DataStatus? = fresh, position: Position? = null,
                    actions: List<CorporateAction> = emptyList(), now: Long = c.last().time + 86_400_000L, cfg: EngineConfig = EngineConfig()): Recommendation {
        val tech = TechnicalEngine.analyze("T.NS", Timeframe.D1, c, Long.MAX_VALUE)
        val pat = PatternEngine.detect("T.NS", Timeframe.D1, tech.candles, Long.MAX_VALUE)
        return SmartEntryEngine.evaluate("T.NS", tech, pat, price, status, actions, position, risk, now, cfg = cfg)
    }

    // ------------------------------------------------------------------ aggressive

    @Test fun aggressiveBuysInsideTheSupportZoneAndWaitsAboveIt() {
        val base = run(uptrend)
        val a = base.plan(StrategyType.AGGRESSIVE)
        assertTrue("uptrend should have a support zone: ${a.rejections}", a.exit != null)
        val zoneMid = (a.entryLow + a.entryHigh) / 2
        val inZone = run(uptrend, price = zoneMid).plan(StrategyType.AGGRESSIVE)
        assertEquals(EntryStatus.BUY_NOW, inZone.status)
        assertEquals(RiskCalc.round(zoneMid, 0.05), inZone.entry, 1e-9)
        assertTrue(inZone.exit!!.stopLoss < inZone.entryLow)                               // stop under the zone
        // +2 ATR sits between zones (+3 would land on the next zone up, the old 215 peak, where buying is again "at support").
        val above = run(uptrend, price = a.entryHigh + 2 * base.atr)
        val w = above.plan(StrategyType.AGGRESSIVE)
        assertEquals(EntryStatus.WAIT_FOR_CORRECTION, w.status)
        assertEquals(w.entryHigh, w.entry, 1e-9)                                           // limit order at the top of the zone
        assertTrue(w.marginAtr > 0)
        assertTrue(w.trigger.startsWith("Limit buy"))
    }

    @Test fun zonesCombineMultipleIndependentLevels() {
        val r = run(uptrend)
        val z = r.supports.first()
        assertTrue("zone made of ${z.label}", z.sources.size >= 2)
        // Supports are at or below price (a zone being tested may sit just above the close); resistances reach above it.
        assertTrue(r.supports.all { it.low <= r.price + 0.5 * r.atr })
        assertTrue(r.resistances.all { it.high > r.price })
    }

    // ------------------------------------------------------------------ conservative

    @Test fun conservativeWaitsForAReversalCloseThenBuys() {
        // Ends on the trough bar that touched support: touched, not yet reversed.
        val touched = run(uptrend).plan(StrategyType.CONSERVATIVE)
        assertEquals(touched.rejections.toString(), EntryStatus.WAIT_FOR_CONFIRMATION, touched.status)
        assertTrue(touched.trigger, touched.trigger.startsWith("Buy only on a daily close above"))
        assertTrue("trigger ${touched.entry} above the touch bar's high ${uptrend.last().high}", touched.entry > uptrend.last().high)
        // Two up bars later: closed above the touch bar's high with indicators turning up → buy.
        val bounced = Shapes.path(*(waves(22) + listOf(442 to 212.5)).toTypedArray())
        val p = run(bounced).plan(StrategyType.CONSERVATIVE)
        assertEquals("${p.rationale.why} ${p.rejections}", EntryStatus.BUY_NOW, p.status)
        assertTrue(p.rationale.supporting.toString(), p.rationale.supporting.size >= 2)
        assertTrue(p.rationale.why, p.rationale.why.contains("buyers defended the zone"))
        assertTrue("stop under the zone and the latest low", p.exit!!.stopLoss < p.entryLow && p.exit!!.stopLoss < bounced.last().low)
    }

    // ------------------------------------------------------------------ breakout

    private fun range(vararg tail: Pair<Int, Double>, boost: Map<IntRange, Double> = emptyMap()): List<Candle> {
        val pts = waves(14)                                                                // ends at 170 on bar 280
        var i = 280
        repeat(5) { i += 8; pts += i to 178.0; i += 8; pts += i to 170.5 }
        i += 8; pts += i to 177.0                                                          // bar 368, just under resistance
        return Shapes.path(*(pts + tail).toTypedArray(), volumeBoost = boost)
    }

    @Test fun breakoutWaitsForTheCloseThenBuysAndWontChase() {
        val before = run(range())
        val wait = before.plan(StrategyType.BREAKOUT)
        assertEquals(EntryStatus.WAIT_FOR_BREAKOUT, wait.status)
        assertTrue("trigger above resistance ≈ 178.36, was ${wait.entry}", wait.entry > 178.3)
        assertTrue(wait.trigger.contains("daily close above"))

        val broke = range(369 to 180.0, boost = mapOf(369..369 to 3.0))
        val r = run(broke)
        val b = r.plan(StrategyType.BREAKOUT)
        assertEquals(EntryStatus.BUY_NOW, b.status)
        assertTrue(b.rationale.supporting.any { it.contains("average volume") })
        assertTrue(b.exit!!.stopLoss < b.entryLow)                                          // back under the broken level
        val chased = run(broke, price = b.entryLow + 3 * r.atr).plan(StrategyType.BREAKOUT)
        assertEquals(EntryStatus.WAIT_FOR_CORRECTION, chased.status)                        // don't chase: wait for the retest
        assertTrue(chased.trigger.startsWith("Don't chase"))
    }

    @Test fun failedBreakoutBlocksEveryBuy() {
        val r = run(range(369 to 180.0, 372 to 174.5))
        assertTrue(r.blockers.map { it.type }.toString(), r.blockers.any { it.type == BlockerType.PATTERN_FAILED })
        assertEquals(EntryStatus.AVOID, r.status)
        assertTrue(r.strategies.none { it.actionable })
    }

    // ------------------------------------------------------------------ gates (market conditions)

    private fun assertBlocked(r: Recommendation, t: BlockerType) {
        assertTrue("expected $t in ${r.blockers}", r.blockers.any { it.type == t })
        assertEquals(EntryStatus.AVOID, r.status)
        assertTrue(r.strategies.none { it.actionable })
        assertTrue(r.rationale.invalidation.isNotEmpty())                                   // the blockers are explained
    }

    @Test fun staleDataBlocksBuying() {
        assertBlocked(run(uptrend, status = fresh.copy(freshness = Freshness.STALE)), BlockerType.STALE_DATA)
        assertBlocked(run(uptrend, status = fresh.copy(freshness = Freshness.OFFLINE)), BlockerType.STALE_DATA)
    }

    @Test fun insufficientHistoryBlocksBuying() = assertBlocked(run(uptrend.takeLast(150)), BlockerType.INSUFFICIENT_HISTORY)

    @Test fun missingCandlesBlockBuying() {
        val holed = uptrend.filterIndexed { k, _ -> k !in 410..415 }                         // six trading days missing in the recent window
        assertBlocked(run(holed), BlockerType.MISSING_CANDLES)
        // A normal holiday run (≤ 3 weekdays) is fine.
        assertTrue(run(uptrend.filterIndexed { k, _ -> k !in 410..411 }).blockers.none { it.type == BlockerType.MISSING_CANDLES })
    }

    @Test fun priceShockIsUnresolvedUntilItSettles() {
        // Last bar gaps up ~6 ATR: likely results/news.
        val last = uptrend.last()
        val shock = uptrend.dropLast(1) + last.copy(open = last.open + 10, high = last.high + 11, close = last.close + 10.5, low = last.open + 9.5)
        val r = run(shock)
        assertBlocked(r, BlockerType.EVENT_RISK)
        assertTrue(r.events.single().title.contains("Wait"))
        // Three quiet sessions later it counts as settled: still listed, no longer blocking.
        val t = Shapes.days(shock.size + 3)
        val settled = shock + (1..3).map { k -> val p = shock.last().close; Candle(t[shock.size + k - 1], p, p * 1.003, p * 0.997, p, 1_000_000) }
        val s = run(settled)
        assertTrue(s.blockers.none { it.type == BlockerType.EVENT_RISK })
        assertTrue(s.events.any { it.resolved })
    }

    @Test fun splitIsMajorEventRiskButDividendOnlyAWarning() {
        val now = uptrend.last().time + 86_400_000L
        val split = CorporateAction("T.NS", CorporateActionType.SPLIT, uptrend[uptrend.size - 2].time, 2.0, null, "test")
        assertBlocked(run(uptrend, actions = listOf(split), now = now), BlockerType.EVENT_RISK)
        val div = CorporateAction("T.NS", CorporateActionType.DIVIDEND, now + 3 * 86_400_000L, null, 5.0, "test")
        val r = run(uptrend, actions = listOf(div), now = now)
        assertTrue(r.blockers.none { it.type == BlockerType.EVENT_RISK })
        assertTrue(r.events.single().let { !it.major && it.title.startsWith("Dividend") })
        assertTrue(r.strategies.filter { it.exit != null }.all { p -> p.rationale.risks.any { it.startsWith("Dividend") } })
    }

    @Test fun bearishTechnicalPictureBlocksBuying() = assertBlocked(run(downtrend), BlockerType.BEARISH_SIGNALS)

    @Test fun poorRewardRiskRejectsThePlan() {
        // Demand 6R to T2: nothing qualifies, so every plan is rejected for reward/risk and the stock is AVOID.
        val r = run(uptrend, cfg = EngineConfig(minRewardRiskT2 = 50.0))
        assertTrue(r.strategies.filter { it.exit != null }.all { p -> p.rejections.any { it.startsWith("Reward/risk") } })
        assertBlocked(r, BlockerType.POOR_RISK_REWARD)
    }

    @Test fun stopTooFarFromStructureRejectsThePlan() {
        val r = run(uptrend, cfg = EngineConfig(maxStopAtr = 0.5, minStopAtr = 0.1))
        assertTrue(r.strategies.filter { it.exit == null && it.rejections.isNotEmpty() }.any { p -> p.rejections.any { it.startsWith("No logical stop") } })
        assertBlocked(r, BlockerType.NO_LOGICAL_STOP)
    }

    // ------------------------------------------------------------------ holder statuses

    @Test fun holderHoldsWhileStructureIsIntact() {
        val r = run(uptrend, position = Position(10, 190.0))
        assertEquals(EntryStatus.HOLD, r.status)
        assertTrue(r.position!!.protectiveStop < uptrend.last().close)
        assertTrue(r.position!!.pnlPct > 0)
        assertNotNull("in profit by > 1 ATR: a trailing level is offered", r.position!!.trailingStop)
    }

    @Test fun holderBooksPartialProfitWhenRallyingIntoResistance() {
        // Rally from the 210 trough back toward the 215 peak, then price reaches that resistance.
        val rally = Shapes.path(*(waves(22) + listOf(450 to 214.5)).toTypedArray())
        val res = run(rally).resistances.first { it.low > 214.5 }
        val r = run(rally, price = res.low, position = Position(10, 190.0))
        assertEquals(EntryStatus.PARTIAL_PROFIT_BOOKING, r.status)
        assertTrue(r.position!!.reason.contains("rallied into resistance"))
        // A pullback that merely rests on old resistance (now support) is not a reason to book profit.
        assertEquals(EntryStatus.HOLD, run(uptrend, position = Position(10, 190.0)).status)
    }

    @Test fun holderExitsWhenACloseBreaksTheProtectiveStop() {
        val crash = Shapes.path(*(waves(22) + listOf(443 to 186.0)).toTypedArray())          // two bars, decisively through the supports
        val r = run(crash, position = Position(10, 205.0))
        assertEquals(EntryStatus.EXIT, r.status)
        assertTrue(crash.last().close < r.position!!.protectiveStop)
    }

    // ------------------------------------------------------------------ invariants over many market states

    @Test fun everyPlanIsInternallyConsistent() {
        var plans = 0; var buys = 0
        for (seed in 1..6) {
            val all = NoLookAheadTest.randomWalk(700, seed)
            for (end in 300..700 step 25) {
                val r = run(all.take(end))
                if (r.status == EntryStatus.BUY_NOW) {
                    buys++
                    assertTrue(r.blockers.isEmpty())
                    assertTrue(r.primaryPlan!!.actionable && r.primaryPlan!!.viable)
                }
                if (r.status == EntryStatus.AVOID) assertTrue("AVOID must say why", r.blockers.isNotEmpty())
                assertTrue(r.rationale.why.isNotBlank())
                for (p in r.strategies) {
                    val x = p.exit ?: continue
                    val rr = p.risk ?: continue
                    plans++
                    val a = r.atr; val tick = 0.05
                    assertTrue("stop below entry", x.stopLoss < p.entry)
                    assertTrue("stop ≥ 0.75 ATR away", p.entry - x.stopLoss >= 0.75 * a - 2 * tick)
                    assertTrue("stop ≤ 3.5 ATR away", p.entry - x.stopLoss <= 3.5 * a + 2 * tick)
                    assertTrue("targets above entry and ordered", x.targets.zipWithNext().all { (u, v) -> v.price > u.price } && x.targets.first().price > p.entry)
                    assertEquals(3, x.targets.size)
                    x.targets.forEachIndexed { k, t -> assertEquals((t.price - p.entry) / (p.entry - x.stopLoss), t.rewardRisk, 1e-9); assertEquals(t.price - p.entry, rr.rewards[k], 1e-9) }
                    assertTrue("loss at stop within budget", rr.maxLoss <= rr.riskBudget + 1e-6)
                    assertTrue("exposure within cap", rr.exposurePct <= 20.0 + 1e-6)
                    assertTrue(x.partials.sumOf { it.fraction } in 0.999..1.001)
                    assertTrue(p.rationale.levels.isNotEmpty() && p.rationale.invalidation.isNotEmpty())
                    if (p.viable) assertTrue(rr.ratios[0] >= 1.0 - 1e-9 && rr.ratios[1] >= 2.0 - 1e-9 && rr.quantity > 0)
                }
            }
        }
        assertTrue("exercise: $plans plans, $buys buys", plans > 100 && buys > 0)
    }

    @Test fun longTermViewIsSeparateAndHasNoStop() {
        val lt = run(uptrend).longTerm
        assertFalse(lt.valuationAvailable)
        assertTrue(lt.valuationNote.contains("never sets a stop-loss"))
        assertTrue(lt.trend.startsWith("Long-term uptrend"))
        assertTrue(lt.context.any { it.contains("200-day average") })
    }
}
