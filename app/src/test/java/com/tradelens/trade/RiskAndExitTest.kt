package com.tradelens.trade

import com.tradelens.data.model.Candle
import org.junit.Assert.*
import org.junit.Test

/** Position sizing, reward/risk, stop placement, targets, trailing stop and partial exits: hand-calculated. */
class RiskAndExitTest {
    private val e = 1e-9
    private val cfg = EngineConfig()
    private val s = RiskSettings(capital = 100_000.0, riskPercent = 1.0, maxPositionPercent = 20.0)

    // ------------------------------------------------------------------ position sizing

    @Test fun riskBudgetLimitsTheQuantity() {
        // Budget 1% of 1,00,000 = 1,000. Risk/share = 100 − 95 = 5 → 200 shares; exposure cap 20,000/100 = 200; cash 1,000.
        // Make the cap looser so risk is the binding limit: 30% → 300 shares.
        val r = RiskCalc.size(100.0, 95.0, listOf(105.0, 110.0, 120.0), s.copy(maxPositionPercent = 30.0))!!
        assertEquals(5.0, r.riskPerShare, e)
        assertEquals(200, r.quantity)
        assertEquals(20_000.0, r.capitalRequired, e)
        assertEquals(20.0, r.exposurePct, e)
        assertEquals(1_000.0, r.maxLoss, e)                                  // never more than the risk budget
        assertEquals(1_000.0, r.riskBudget, e)
        assertTrue(r.limitedBy.startsWith("risk budget"))
        assertEquals(listOf(5.0, 10.0, 20.0), r.rewards)
        assertEquals(listOf(1.0, 2.0, 4.0), r.ratios)
        assertEquals(5.0, r.stopPct, e)
        assertEquals(listOf(5.0, 10.0, 20.0), r.targetPcts)
    }

    @Test fun maxPositionSizeCapsExposure() {
        // Tight stop: risk/share 1 → 1,000 shares by risk, but 20% of capital buys only 200 at ₹100.
        val r = RiskCalc.size(100.0, 99.0, listOf(102.0, 104.0, 106.0), s)!!
        assertEquals(200, r.quantity)
        assertEquals(20.0, r.exposurePct, e)
        assertEquals(200.0, r.maxLoss, e)                                    // below the 1,000 budget
        assertTrue(r.limitedBy.startsWith("max position size"))
    }

    @Test fun availableCapitalCapsQuantity() {
        // 5% risk → 5,000 shares by risk, 500% cap → 5,000, but only 1,00,000 / 100 = 1,000 affordable.
        val r = RiskCalc.size(100.0, 99.0, listOf(102.0, 104.0, 106.0), s.copy(riskPercent = 5.0, maxPositionPercent = 500.0))!!
        assertEquals(1_000, r.quantity)
        assertTrue(r.limitedBy.startsWith("available capital"))
    }

    @Test fun quantityRoundsDownAndCanBeZero() {
        // Risk/share 333 > the 1,000 budget allows 3.003 → 3 shares (never round up past the budget).
        assertEquals(3, RiskCalc.size(1000.0, 667.0, listOf(1400.0, 1700.0, 2000.0), s.copy(maxPositionPercent = 100.0))!!.quantity)
        // One share risks 2,000 > 1,000 budget → 0 shares.
        assertEquals(0, RiskCalc.size(5000.0, 3000.0, listOf(7000.0, 9000.0, 11000.0), s.copy(maxPositionPercent = 100.0))!!.quantity)
    }

    @Test fun noRiskDefinedWithoutAStopBelowTheEntry() {
        assertNull(RiskCalc.size(100.0, 100.0, listOf(110.0), s))
        assertNull(RiskCalc.size(100.0, 101.0, listOf(110.0), s))
        assertNull(RiskCalc.size(0.0, -1.0, listOf(1.0), s))
    }

    @Test fun tickRounding() {
        assertEquals(101.25, RiskCalc.roundDown(101.29, 0.05), e)
        assertEquals(101.30, RiskCalc.roundUp(101.26, 0.05), e)
        assertEquals(101.25, RiskCalc.round(101.27, 0.05), 1e-9)
        assertEquals(101.30, RiskCalc.roundDown(101.30, 0.05), e)           // exact ticks unchanged
    }

    // ------------------------------------------------------------------ stop placement

    @Test fun stopGoesBelowStructureWithABuffer() {
        // Entry 100, zone low 97, ATR 2 → raw stop 97 − 0.3 × 2 = 96.4 (1.8 ATR away: inside 0.75–3.5).
        val (stop, why) = Exits.placeStop(100.0, 97.0, 2.0, cfg)!!
        assertEquals(96.4, stop, e)
        assertTrue(why.contains("below the structure at 97.00"))
    }

    @Test fun tooTightStopIsWidenedToTheMinimum() {
        // Structure 99.5 → raw 98.9, only 0.55 ATR away → widened to 100 − 0.75 × 2 = 98.5.
        val (stop, why) = Exits.placeStop(100.0, 99.5, 2.0, cfg)!!
        assertEquals(98.5, stop, e)
        assertTrue(why.contains("widened"))
    }

    @Test fun stopTooFarAwayCannotBePlacedLogically() {
        // Structure 92 → raw 91.4 = 4.3 ATR > 3.5 ATR maximum.
        assertNull(Exits.placeStop(100.0, 92.0, 2.0, cfg))
        assertNull(Exits.placeStop(100.0, 100.5, 2.0, cfg))                // structure above the entry
        assertNull(Exits.placeStop(100.0, 95.0, 0.0, cfg))                 // no volatility estimate
    }

    // ------------------------------------------------------------------ targets

    private fun lv(p: Double, src: LevelSource = LevelSource.SWING_RESISTANCE) = Level(p, src, "test")

    @Test fun targetsComeFromStructureInOrder() {
        // Entry 100, stop 96 (risk 4), ATR 2. Candidates: 100.8 (too close: < 0.5 ATR), 106, 106.7 (within 0.6 ATR of 106: skipped),
        // 112 (52-week high), 120.37 (extension), 130.
        val t = Exits.targets(100.0, 96.0, listOf(lv(130.0), lv(106.7), lv(100.8), lv(112.0, LevelSource.HIGH_52W), lv(106.0), lv(120.37, LevelSource.FIB_EXTENSION)), 2.0, cfg)
        listOf(106.0, 112.0, 120.35).zip(t).forEach { (want, got) -> assertEquals(want, got.price, 1e-9) }   // rounded DOWN to the ₹0.05 tick
        assertEquals(listOf("T1", "T2", "T3"), t.map { it.label })
        assertEquals(1.5, t[0].rewardRisk, e); assertEquals(3.0, t[1].rewardRisk, e)
        assertEquals(6.0, t[0].pctFromEntry, e)
        assertTrue(t[1].basis.startsWith("52-week high")); assertTrue(t[2].basis.startsWith("Fibonacci extension"))
    }

    @Test fun volatilityProjectionOnlyFillsMissingStructure() {
        // Only one structural level above: T2 and T3 are 2-ATR steps beyond it, and say so.
        val t = Exits.targets(100.0, 96.0, listOf(lv(106.0)), 2.0, cfg)
        assertEquals(listOf(106.0, 110.0, 114.0), t.map { it.price })
        assertFalse(t[0].basis.startsWith(LevelSource.VOLATILITY.label))
        assertTrue(t.drop(1).all { it.basis.startsWith(LevelSource.VOLATILITY.label) })
        // At all-time highs with nothing above: everything is a labelled projection from the entry.
        assertEquals(listOf(104.0, 108.0, 112.0), Exits.targets(100.0, 96.0, emptyList(), 2.0, cfg).map { it.price })
    }

    @Test fun targetsScaleWithVolatilityNotAFixedPercentage() {
        // The same structure-free stock at twice the volatility gets targets twice as far away.
        val calm = Exits.targets(100.0, 96.0, emptyList(), 1.0, cfg).map { it.price - 100 }
        val wild = Exits.targets(100.0, 96.0, emptyList(), 2.0, cfg).map { it.price - 100 }
        calm.zip(wild).forEach { (a, b) -> assertEquals(a * 2, b, 1e-9) }
    }

    // ------------------------------------------------------------------ trailing and partials

    @Test fun chandelierTrailsTheHighestHigh() {
        val c = (0 until 30).map { k -> Candle(k.toLong(), 100.0 + k, 101.0 + k, 99.0 + k, 100.5 + k, 1) }
        // Highest high of the last 22 bars = 101 + 29 = 130; minus 3 × ATR 2 = 124.
        assertEquals(124.0, Exits.chandelier(c, 2.0, cfg)!!, e)
        assertNull(Exits.chandelier(c.take(10), 2.0, cfg))
    }

    @Test fun partialExitsBookThirdsAndRatchetTheStop() {
        val t = Exits.targets(100.0, 96.0, listOf(lv(106.0), lv(112.0), lv(120.0)), 2.0, cfg)
        val p = Exits.partials(100.0, t, cfg)
        assertEquals(1.0, p.sumOf { it.fraction }, 1e-9)
        assertEquals(listOf("Book 1/3 at T1", "Book 1/3 at T2", "Book 1/3 at T3"), p.map { it.label })
        assertEquals(t.map { it.price }, p.map { it.price })
        assertTrue(p[0].action.contains("stop-loss to the entry (100.00)"))
        assertTrue(p[1].action.contains("T1 (106.00)"))
    }

    // ------------------------------------------------------------------ zones

    @Test fun nearbyLevelsClusterAndAgreementScoresHigher() {
        val levels = listOf(lv(100.0, LevelSource.EMA50), lv(100.5, LevelSource.SWING_SUPPORT), lv(100.9, LevelSource.FIBONACCI), lv(105.0, LevelSource.EMA20))
        val z = Zones.cluster(levels, 2.0, cfg)                              // cluster width 0.6 ATR = 1.2
        assertEquals(2, z.size)
        val (a, b) = z
        assertEquals(100.0, a.low, e); assertEquals(100.9, a.high, e); assertEquals(3, a.members.size)
        assertEquals((2.0 + 2.0 + 1.2) * (1 + 0.15 * 2), a.score, e)      // three sources agree: +30%
        assertEquals(104.8, b.low, e); assertEquals(105.2, b.high, e)      // single level padded to 0.2 ATR wide
        assertEquals(1.5, b.score, e)
    }
}
