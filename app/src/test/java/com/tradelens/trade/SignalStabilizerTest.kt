package com.tradelens.trade

import org.junit.Assert.*
import org.junit.Test

/** Signal stability: cooldown, hysteresis, safety-first exits and level tolerance (defaults: 15 min, 0.15 ATR, 0.2 ATR). */
class SignalStabilizerTest {
    private val min = 60_000L

    private fun plan(status: EntryStatus, entry: Double, stop: Double, margin: Double) = StrategyPlan(
        StrategyType.AGGRESSIVE, status, status == EntryStatus.BUY_NOW, entry, entry - 1, entry, "trigger",
        ExitPlan(stop, "basis", emptyList(), TrailingStop("t", null, "r"), emptyList(), stop, "rule", emptyList()),
        null, true, emptyList(), 50, margin, Rationale("why $entry", emptyList(), emptyList(), emptyList(), emptyList()),
    )

    /** ATR 2 → hysteresis 0.3 price units, level tolerance 0.4. */
    private fun rec(status: EntryStatus, margin: Double = 1.0, entry: Double = 100.0, stop: Double = 96.0, price: Double = entry) = Recommendation(
        "X", status, "headline $status $entry", StrategyType.AGGRESSIVE, listOf(plan(status, entry, stop, margin)), emptyList(), emptyList(), null,
        Rationale("why $entry", emptyList(), emptyList(), emptyList(), emptyList()), price, 2.0, emptyList(), emptyList(),
        LongTermView(false, "", "", emptyList()), 1L, 0L,
    )

    @Test fun firstRecommendationPassesThrough() {
        val r = rec(EntryStatus.BUY_NOW)
        assertSame(r, SignalStabilizer().publish(r, 0))
    }

    @Test fun statusChangeWaitsForTheCooldown() {
        val s = SignalStabilizer()
        s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION), 0)
        val held = s.publish(rec(EntryStatus.BUY_NOW, price = 100.7), 5 * min)
        assertEquals(EntryStatus.WAIT_FOR_CORRECTION, held.status)
        assertEquals(100.7, held.price, 0.0)                                         // the price itself is always current
        assertEquals(EntryStatus.BUY_NOW, held.stability!!.pendingStatus)
        assertTrue(held.stability!!.reason.contains("cooldown: 10 min left"))
        // After the cooldown the change goes through.
        assertEquals(EntryStatus.BUY_NOW, s.publish(rec(EntryStatus.BUY_NOW), 16 * min).status)
    }

    @Test fun tinyMovesPastTheBoundaryDontFlipTheStatus() {
        val s = SignalStabilizer()
        s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION), 0)
        val held = s.publish(rec(EntryStatus.BUY_NOW, margin = 0.05), 60 * min)      // cooldown long over, but only 0.05 ATR inside
        assertEquals(EntryStatus.WAIT_FOR_CORRECTION, held.status)
        assertTrue(held.stability!!.reason.contains("0.05 ATR past the boundary"))
        assertEquals(EntryStatus.BUY_NOW, s.publish(rec(EntryStatus.BUY_NOW, margin = 0.2), 61 * min).status)
    }

    @Test fun riskReducingChangesApplyImmediately() {
        for (to in listOf(EntryStatus.AVOID, EntryStatus.EXIT, EntryStatus.PARTIAL_PROFIT_BOOKING)) {
            val s = SignalStabilizer()
            s.publish(rec(EntryStatus.BUY_NOW), 0)
            val r = s.publish(rec(to, margin = 0.0), 1 * min)                        // within the cooldown, zero margin
            assertEquals(to, r.status)
            assertNull(r.stability)
        }
    }

    @Test fun recoveringFromAvoidIsNotRushed() {
        val s = SignalStabilizer()
        s.publish(rec(EntryStatus.BUY_NOW), 0)
        s.publish(rec(EntryStatus.AVOID), 1 * min)
        assertEquals(EntryStatus.AVOID, s.publish(rec(EntryStatus.BUY_NOW), 2 * min).status)    // cooldown restarts at the AVOID
        assertEquals(EntryStatus.BUY_NOW, s.publish(rec(EntryStatus.BUY_NOW), 17 * min).status)
    }

    @Test fun cooldownCountsFromTheLastRealChangeNotFromHeldUpdates() {
        val s = SignalStabilizer()
        s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION), 0)
        repeat(10) { k -> assertEquals(EntryStatus.WAIT_FOR_CORRECTION, s.publish(rec(EntryStatus.BUY_NOW), (k + 1) * min).status) }
        assertEquals(EntryStatus.BUY_NOW, s.publish(rec(EntryStatus.BUY_NOW), 15 * min).status)
    }

    @Test fun smallLevelDriftKeepsThePublishedLevels() {
        val s = SignalStabilizer()
        s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION, entry = 100.0, stop = 96.0), 0)
        // Entry 0.3 and stop 0.2 lower: under the 0.4 tolerance → same numbers as before.
        val r = s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION, entry = 99.7, stop = 95.8), 1 * min)
        assertEquals(100.0, r.plan(StrategyType.AGGRESSIVE).entry, 0.0)
        assertEquals(96.0, r.plan(StrategyType.AGGRESSIVE).exit!!.stopLoss, 0.0)
        assertEquals("headline WAIT_FOR_CORRECTION 100.0", r.headline)
        // A real move (1.0 = 0.5 ATR) is published.
        assertEquals(99.0, s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION, entry = 99.0, stop = 95.0), 2 * min).plan(StrategyType.AGGRESSIVE).entry, 0.0)
    }

    @Test fun resetForgetsThePublishedState() {
        val s = SignalStabilizer()
        s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION), 0)
        s.reset("X")
        assertEquals(EntryStatus.BUY_NOW, s.publish(rec(EntryStatus.BUY_NOW), 1 * min).status)
    }

    @Test fun stabilityRulesAreConfigurable() {
        val s = SignalStabilizer(StabilityConfig(cooldownMs = 0, hysteresisAtr = 0.0))
        s.publish(rec(EntryStatus.WAIT_FOR_CORRECTION), 0)
        assertEquals(EntryStatus.BUY_NOW, s.publish(rec(EntryStatus.BUY_NOW, margin = 0.0), 1).status)
    }
}
