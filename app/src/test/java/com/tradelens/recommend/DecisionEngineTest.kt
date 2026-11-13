package com.tradelens.recommend

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Decision rules, weights, conflicts, calibration and history. Scores are set directly where a full engine output
 * would be needed, so each rule can be checked on its own. The calibration data is SYNTHETIC: it tests the arithmetic,
 * not any real trading performance.
 */
class DecisionEngineTest {

    private val now = 1_780_000_000_000L

    private val empty = DecisionInputs(
        symbol = "T.NS", price = 100.0, now = now, technical = null, benchmark = null, patterns = null, trade = null,
        flow = null, fundamentals = null, valuation = null, research = null, holding = false, inputAsOf = now,
    )

    private fun block(targetPrice: Double? = null) = PlanBlock(
        currentPrice = 100.0, preferredEntry = null, alternativeEntry = null, stop = null, stopNote = "",
        targets = listOf(PlanTarget("T1", targetPrice, "")), riskReward = null, waitFor = emptyList(), exitWhen = emptyList(),
    )

    private fun scores(vararg pairs: Pair<ScoreComponent, Double?>): Map<ScoreComponent, ComponentScore> =
        pairs.associate { (c, s) -> c to ComponentScore(c, s, "test") }

    // ------------------------------------------------------------------ configuration

    @Test fun defaultWeightsTotalOneHundredPerHorizon() {
        for (h in InvestmentHorizon.values()) {
            assertEquals(100.0, ModelConfig.DEFAULT.weights.getValue(h).values.sum(), 1e-9)
        }
        // The brief's short-term split, exactly.
        val short = ModelConfig.DEFAULT.weights.getValue(InvestmentHorizon.SHORT)
        assertEquals(35.0, short.getValue(ScoreComponent.TECHNICAL), 1e-9)
        assertEquals(10.0, short.getValue(ScoreComponent.NEWS), 1e-9)
    }

    @Test fun weightsMustTotalOneHundred() {
        val bad = ModelConfig.DEFAULT.weights.toMutableMap()
        bad[InvestmentHorizon.SHORT] = mapOf(ScoreComponent.TECHNICAL to 90.0)
        assertThrows(IllegalArgumentException::class.java) { ModelConfig(version = "x", weights = bad) }
    }

    // ------------------------------------------------------------------ insufficient data

    @Test fun noDataGivesNoTradeNotANeutralCall() {
        val d = DecisionEngine.decide(InvestmentHorizon.SHORT, empty)
        assertEquals(DecisionAction.NO_TRADE, d.action)
        assertNull(d.overall)
        assertEquals(0.0, d.coverage, 1e-9)
        assertTrue(d.components.all { it.score == null })
        assertTrue(d.explanation.contains("Nothing is recommended"))
    }

    @Test fun thinCoverageBelowMinimumIsNoTrade() {
        // Long-term: fundamentals (30) and valuation (25) only = 55% coverage, below the 60% minimum.
        val a = DecisionEngine.action(InvestmentHorizon.LONG, 80.0, 0.55, scores(), emptyList(), empty, block(), ModelConfig.DEFAULT)
        assertEquals(DecisionAction.NO_TRADE, a)
    }

    // ------------------------------------------------------------------ action rules

    @Test fun actionBandsForAPositiveAndNegativeLean() {
        val cfg = ModelConfig.DEFAULT
        fun act(h: InvestmentHorizon, score: Double, holding: Boolean = false, target: Double? = null) =
            DecisionEngine.action(h, score, 1.0, scores(), emptyList(), empty.copy(holding = holding), block(target), cfg)
        // Long term: no entry-timing requirement, so a high score is a strong setup.
        assertEquals(DecisionAction.STRONG_BUY_SETUP, act(InvestmentHorizon.LONG, 80.0))
        assertEquals(DecisionAction.BUY, act(InvestmentHorizon.LONG, 70.0))
        assertEquals(DecisionAction.ACCUMULATE, act(InvestmentHorizon.LONG, 60.0))
        assertEquals(DecisionAction.WAIT_FOR_ENTRY, act(InvestmentHorizon.LONG, 50.0))
        assertEquals(DecisionAction.AVOID, act(InvestmentHorizon.LONG, 40.0))
        assertEquals(DecisionAction.AVOID, act(InvestmentHorizon.LONG, 20.0))
        // Short term without a BUY NOW entry: a high score waits for the entry rather than buying.
        assertEquals(DecisionAction.WAIT_FOR_ENTRY, act(InvestmentHorizon.SHORT, 80.0))
        // Holding changes the words: hold, or sell when the evidence has turned.
        assertEquals(DecisionAction.HOLD, act(InvestmentHorizon.LONG, 50.0, holding = true))
        assertEquals(DecisionAction.SELL, act(InvestmentHorizon.LONG, 20.0, holding = true))
    }

    @Test fun reachingTheFirstTargetWhileHoldingBooksProfit() {
        val a = DecisionEngine.action(InvestmentHorizon.MEDIUM, 55.0, 1.0, scores(), emptyList(),
            empty.copy(holding = true, price = 110.0), block(targetPrice = 105.0), ModelConfig.DEFAULT)
        assertEquals(DecisionAction.PARTIAL_PROFIT_BOOKING, a)
    }

    @Test fun highConflictStopsAStrongSetupFromBeingRecommended() {
        val conflicts = listOf(Conflict("x", ConflictSeverity.HIGH, "y"))
        val a = DecisionEngine.action(InvestmentHorizon.LONG, 85.0, 1.0, scores(), conflicts, empty, block(), ModelConfig.DEFAULT)
        assertEquals(DecisionAction.BUY, a)
    }

    // ------------------------------------------------------------------ conflicts

    @Test fun strongFundamentalsWithExpensiveValuationIsShownNotHidden() {
        val c = DecisionEngine.conflicts(empty, scores(ScoreComponent.FUNDAMENTALS to 80.0, ScoreComponent.VALUATION to 30.0))
        assertEquals("Strong fundamentals but expensive valuation", c.single().title)
    }

    @Test fun bullishChartWithWeakFundamentalsIsFlagged() {
        val c = DecisionEngine.conflicts(empty, scores(ScoreComponent.TECHNICAL to 70.0, ScoreComponent.FUNDAMENTALS to 30.0))
        assertTrue(c.any { it.title == "Bullish chart but weak fundamentals" })
    }

    @Test fun positiveNewsWithBearishPriceIsFlagged() {
        val c = DecisionEngine.conflicts(empty, scores(ScoreComponent.NEWS to 70.0, ScoreComponent.TECHNICAL to 30.0))
        assertTrue(c.any { it.title == "Positive news but bearish price action" })
    }

    @Test fun volumeBuyingWhilePriceFallsIsFlagged() {
        val c = DecisionEngine.conflicts(empty, scores(ScoreComponent.VOLUME to 70.0, ScoreComponent.TECHNICAL to 30.0))
        assertTrue(c.any { it.title == "Volume buying while price falls" })
    }

    @Test fun missingScoresDoNotCreateConflicts() {
        // Fundamentals unknown is not "weak": no bullish-chart conflict may be invented from a missing input.
        val c = DecisionEngine.conflicts(empty, scores(ScoreComponent.TECHNICAL to 70.0, ScoreComponent.FUNDAMENTALS to null))
        assertTrue(c.isEmpty())
    }

    // ------------------------------------------------------------------ plans

    @Test fun longTermPlanWithoutValuationSetsNothingInvented() {
        val p = DecisionEngine.plan(InvestmentHorizon.LONG, empty)
        assertNull(p.preferredEntry)
        assertNull(p.stop)
        assertNull(p.riskReward)
        assertTrue(p.targets.all { it.price == null && it.note.isNotBlank() })
        assertTrue(p.waitFor.single().contains("valuation estimate"))
    }

    @Test fun longTermHasNoPriceStopAndSaysSo() {
        val p = DecisionEngine.plan(InvestmentHorizon.LONG, empty)
        assertTrue(p.stopNote.contains("thesis"))
    }

    // ------------------------------------------------------------------ calibration (SYNTHETIC data)

    /**
     * Synthetic: each decision hits with probability equal to its confidence, drawn from a fixed pseudo-random sequence.
     * A well-calibrated model should then have an error below the naive baseline. Tests the arithmetic only.
     */
    private fun syntheticSamples(n: Int): List<CalibrationSample> = (0 until n).map { k ->
        val conf = (k % 100) / 100.0
        val u = ((k * 2654435761L + 12345L) % 1_000_003L) / 1_000_003.0
        CalibrationSample(time = k.toLong(), confidence = conf, hit = u < conf)
    }

    @Test fun calibrationOnSyntheticHistoryTracksTheDesignedRates() {
        val r = Calibration.fit(syntheticSamples(2000))
        val table = r.table
        assertNotNull(r.reason, table)
        assertTrue("calibrated error should not exceed the naive baseline", table!!.calibrationError <= table.baselineError + 1e-9)
        assertTrue(table.predict(0.95)!! > table.predict(0.05)!!)
    }

    @Test fun tooFewDecisionsLeavesConfidenceUncalibrated() {
        val r = Calibration.fit(syntheticSamples(50))
        assertNull(r.table)
        assertTrue(r.reason.contains("needs at least"))
    }

    @Test fun uncalibratedDecisionSaysItIsNotAProbability() {
        val d = DecisionEngine.decide(InvestmentHorizon.SHORT, empty, calibration = null)
        assertNull(d.confidence)
        assertTrue(d.confidenceNote.contains("not a probability of profit"))
    }

    // ------------------------------------------------------------------ history

    @Test fun historyIsAppendOnlyAndRoundTrips() {
        val file = File.createTempFile("tradelens-history", ".jsonl")
        try {
            val h = RecommendationHistory(file)
            val rec = DecisionEngine.decide(InvestmentHorizon.LONG, empty).toRecord("T.NS", 100.0)
            assertTrue(h.append(rec))
            assertFalse("the same recommendation must not be saved over", h.append(rec))
            val back = h.all().single()
            assertEquals(rec, back)
            assertEquals(ModelConfig.DEFAULT.version, back.modelVersion)
            assertEquals(InvestmentHorizon.LONG.name, back.horizon)
        } finally {
            file.delete()
        }
    }

    @Test fun damagedLineIsSkippedNotRepaired() {
        val file = File.createTempFile("tradelens-damaged", ".jsonl")
        try {
            val h = RecommendationHistory(file)
            h.append(DecisionEngine.decide(InvestmentHorizon.SHORT, empty).toRecord("A.NS", 1.0))
            file.appendText("{not json\n")
            h.append(DecisionEngine.decide(InvestmentHorizon.MEDIUM, empty).toRecord("A.NS", 1.0))
            assertEquals(2, h.all().size)
            assertEquals(2, h.forSymbol("A.NS").size)
        } finally {
            file.delete()
        }
    }

    @Test fun recordKeepsEveryComponentScore() {
        val rec = DecisionEngine.decide(InvestmentHorizon.SHORT, empty).toRecord("T.NS", 100.0)
        assertEquals(ScoreComponent.values().size, rec.scores.size)
        assertTrue(rec.scores.values.all { it == null })   // unavailable stays unavailable in the record
    }
}
