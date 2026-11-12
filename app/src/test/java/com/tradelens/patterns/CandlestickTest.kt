package com.tradelens.patterns

import com.tradelens.data.model.Candle
import com.tradelens.patterns.Shapes.mirror
import com.tradelens.patterns.Shapes.one
import com.tradelens.patterns.Shapes.only
import com.tradelens.ta.Bias
import org.junit.Assert.*
import org.junit.Test

/**
 * Candlestick patterns on hand-specified bars. The lead-in is a steady 25-bar decline (close 120 → 96, each bar
 * O = C + 0.6, H = O + 0.2, L = C − 0.2, so ATR ≈ 1.2); bearish twins are the price mirror (p → 200 − p).
 */
class CandlestickTest {
    private val e = 1e-9
    private val n = 25

    private fun downtrend(): List<Candle> {
        val t = Shapes.days(n)
        return (0 until n).map { i -> val c = 120.0 - i; Candle(t[i], c + 0.6, c + 0.8, c - 0.2, c, 1_000_000) }
    }

    private fun flat(): List<Candle> {
        val t = Shapes.days(n)
        return (0 until n).map { i -> val c = 96.0 + if (i % 2 == 0) 0.3 else -0.3; Candle(t[i], c - 0.2, c + 0.3, c - 0.3, c, 1_000_000) }
    }

    /** Appends bars given as (open, high, low, close). */
    private fun List<Candle>.then(vararg ohlc: DoubleArray): List<Candle> {
        val t = Shapes.days(size + ohlc.size)
        return this + ohlc.mapIndexed { k, b -> Candle(t[size + k], b[0], b[1], b[2], b[3], 1_000_000) }
    }

    private fun b(o: Double, h: Double, l: Double, c: Double) = doubleArrayOf(o, h, l, c)

    // Bars after the decline (last close 96.0, prior bar O 96.6).
    private val hammer = b(95.6, 96.0, 94.0, 95.9)            // body 0.3, lower shadow 1.6 (80% of range), upper 0.1
    private val confirmUp = b(95.9, 97.7, 95.8, 97.5)          // closes above the hammer high + 0.1 ATR
    private val breakDown = b(95.8, 95.9, 93.4, 93.5)          // closes below the hammer low
    private val inside = b(95.5, 95.8, 95.2, 95.5)             // inside bar: neither confirms nor invalidates

    // ------------------------------------------------------------------ hammer / shooting star and lifecycle

    @Test fun hammerAfterDeclineConfirmedByCloseAboveItsHigh() {
        val c = downtrend().then(hammer, confirmUp)
        val p = Shapes.detect(c).one(PatternType.HAMMER)
        assertEquals(Bias.BULLISH, p.direction)
        assertEquals(PatternStatus.CONFIRMED, p.status)
        assertEquals(n, p.detectedIndex); assertEquals(n + 1, p.confirmedIndex)
        assertEquals(96.0, p.breakoutLevel, e)                                         // the hammer's high
        assertEquals(94.0, p.invalidationLevel, e)                                     // the hammer's low
        assertEquals(97.5, p.entry, e)                                                 // close of the confirming bar
        assertTrue(p.stopLoss < 94.0)
        val r = p.entry - p.stopLoss
        assertEquals(listOf(1.0, 2.0, 3.0).map { p.entry + it * r }, p.targets.map { it.price })   // 1R, 2R, 3R
        assertEquals(listOf("T1 (1R)", "T2 (2R)", "T3 (3R)"), p.targets.map { it.label })
    }

    @Test fun hammerIsFormingUntilConfirmed() {
        val p = Shapes.detect(downtrend().then(hammer)).one(PatternType.HAMMER)
        assertEquals(PatternStatus.FORMING, p.status)
        assertTrue(p.explanation.contains("Waiting for a candle to close above 96.00"))
    }

    @Test fun hammerInvalidatedByCloseBelowItsLow() {
        val p = Shapes.detect(downtrend().then(hammer, breakDown)).one(PatternType.HAMMER)
        assertEquals(PatternStatus.INVALIDATED, p.status)
    }

    @Test fun hammerExpiresWithoutConfirmation() {
        val p = Shapes.detect(downtrend().then(hammer, inside, inside, inside, inside)).one(PatternType.HAMMER)
        assertEquals(PatternStatus.INVALIDATED, p.status)
        assertTrue(p.events.last().detail.contains("No breakout within 3 bars"))
    }

    @Test fun hammerFailsWhenStopIsHitAfterConfirmation() {
        val p = Shapes.detect(downtrend().then(hammer, confirmUp, b(97.4, 97.5, 93.0, 93.2))).one(PatternType.HAMMER)
        assertEquals(PatternStatus.FAILED, p.status)
    }

    @Test fun shootingStarIsTheMirror() {
        val p = Shapes.detect(mirror(downtrend().then(hammer, confirmUp))).one(PatternType.SHOOTING_STAR)
        assertEquals(Bias.BEARISH, p.direction)
        assertEquals(PatternStatus.CONFIRMED, p.status)
        assertEquals(200 - 94.0, p.invalidationLevel, e)                               // the star's high = mirror of the hammer's low
        assertEquals(200 - 96.0, p.breakoutLevel, e)                                   // the star's low = mirror of the hammer's high
    }

    @Test fun hammerNeedsAPriorDecline() {
        assertTrue(Shapes.detect(flat().then(hammer)).only(PatternType.HAMMER).isEmpty())
    }

    @Test fun shortLowerShadowIsNotAHammer() {
        assertTrue(Shapes.detect(downtrend().then(b(95.6, 96.0, 95.0, 95.9))).only(PatternType.HAMMER).isEmpty())   // shadow 0.6 < 2× body… and < 60% of range
    }

    // ------------------------------------------------------------------ engulfing

    @Test fun bullishEngulfing() {
        val c = downtrend().then(b(95.9, 97.2, 95.8, 97.1))                           // body 95.9→97.1 covers the prior 96.6→96.0
        val p = Shapes.detect(c).one(PatternType.BULLISH_ENGULFING)
        assertEquals(Bias.BULLISH, p.direction)
        assertEquals(n - 1, p.startIndex); assertEquals(n, p.endIndex)
        assertEquals(97.2, p.breakoutLevel, e)
        assertEquals(c[n - 1].low, p.invalidationLevel, e)                             // low of the two bars
    }

    @Test fun bearishEngulfingIsTheMirror() {
        val p = Shapes.detect(mirror(downtrend().then(b(95.9, 97.2, 95.8, 97.1)))).one(PatternType.BEARISH_ENGULFING)
        assertEquals(Bias.BEARISH, p.direction)
    }

    @Test fun bodyThatDoesNotCoverIsNotEngulfing() {
        assertTrue(Shapes.detect(downtrend().then(b(95.9, 96.5, 95.8, 96.4))).only(PatternType.BULLISH_ENGULFING).isEmpty())
    }

    // ------------------------------------------------------------------ stars

    private val morningStar = arrayOf(b(96.0, 96.1, 93.9, 94.0), b(93.7, 94.0, 93.5, 93.8), b(94.0, 95.6, 93.9, 95.5))

    @Test fun morningStar() {
        val p = Shapes.detect(downtrend().then(*morningStar)).one(PatternType.MORNING_STAR)
        assertEquals(Bias.BULLISH, p.direction)
        assertEquals(3, p.anchors.size)
        assertEquals(n, p.startIndex); assertEquals(n + 2, p.endIndex)
    }

    @Test fun eveningStarIsTheMirror() {
        assertEquals(Bias.BEARISH, Shapes.detect(mirror(downtrend().then(*morningStar))).one(PatternType.EVENING_STAR).direction)
    }

    @Test fun thirdBarMustCloseBeyondTheFirstBodysMidpoint() {
        val weak = arrayOf(morningStar[0], morningStar[1], b(94.0, 94.9, 93.9, 94.8))   // midpoint is 95.0
        assertTrue(Shapes.detect(downtrend().then(*weak)).only(PatternType.MORNING_STAR).isEmpty())
    }

    // ------------------------------------------------------------------ piercing / dark cloud

    private val piercing = arrayOf(b(96.0, 96.1, 93.9, 94.0), b(93.6, 95.5, 93.5, 95.4))   // opens below 94, closes 95.4 > midpoint 95

    @Test fun piercingPattern() {
        val p = Shapes.detect(downtrend().then(*piercing)).one(PatternType.PIERCING_PATTERN)
        assertEquals(Bias.BULLISH, p.direction)
        assertTrue(Shapes.detect(downtrend().then(*piercing)).only(PatternType.BULLISH_ENGULFING).isEmpty())   // closes below the prior open
    }

    @Test fun darkCloudCoverIsTheMirror() {
        assertEquals(Bias.BEARISH, Shapes.detect(mirror(downtrend().then(*piercing))).one(PatternType.DARK_CLOUD_COVER).direction)
    }

    // ------------------------------------------------------------------ doji

    @Test fun dojiAfterDeclineWarnsOfABottom() {
        val p = Shapes.detect(downtrend().then(b(95.5, 96.5, 94.5, 95.5))).one(PatternType.DOJI)
        assertEquals(Bias.BULLISH, p.direction)
        assertTrue(p.explanation.contains("after a decline"))
        assertEquals(Bias.BEARISH, Shapes.detect(mirror(downtrend().then(b(95.5, 96.5, 94.5, 95.5)))).one(PatternType.DOJI).direction)
    }

    @Test fun dojiWithoutATrendIsNotReported() {
        assertTrue(Shapes.detect(flat().then(b(96.0, 97.0, 95.0, 96.0))).only(PatternType.DOJI).isEmpty())
    }

    // ------------------------------------------------------------------ common fields

    @Test fun everyCandlestickCarriesTheRequiredFields() {
        val r = Shapes.detect(downtrend().then(hammer, confirmUp))
        for (p in r.patterns) {
            assertEquals(com.tradelens.marketdata.Timeframe.D1, p.timeframe)
            assertTrue(p.name.isNotBlank() && p.explanation.isNotBlank())
            assertTrue(p.direction != Bias.NEUTRAL)
            assertTrue(p.keyLevels.map { it.label }.containsAll(listOf("Pattern high", "Pattern low")))
            assertTrue(p.confidence in 0..100 && p.breakdown.total == p.confidence || p.status != PatternStatus.CONFIRMED)
            assertEquals(r.patterns.first { it.id == p.id }.detectedAt, p.detectedAt)
        }
    }
}
